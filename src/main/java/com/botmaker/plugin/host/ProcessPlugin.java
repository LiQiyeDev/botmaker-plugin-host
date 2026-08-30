package com.botmaker.plugin.host;

import com.botmaker.plugin.api.ActionContext;
import com.botmaker.plugin.api.CompanionPlugin;
import com.botmaker.plugin.api.EnabledWhen;
import com.botmaker.plugin.api.ToolbarGroup;
import com.botmaker.plugin.api.ToolbarItem;
import com.botmaker.plugin.protocol.Capability;
import com.botmaker.plugin.protocol.Handshake;
import com.botmaker.plugin.protocol.HostInfo;
import com.botmaker.plugin.protocol.Peers;
import com.botmaker.plugin.protocol.PluginPeer;
import com.botmaker.plugin.protocol.StudioPeer;
import com.botmaker.plugin.protocol.WireToolbarItem;

import org.eclipse.lsp4j.jsonrpc.Launcher;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A companion plugin running in <em>another process</em>, made to look like an ordinary
 * {@link CompanionPlugin}.
 *
 * <p>This is the class the whole two-interface split was for. {@code CompanionPlugin} was drawn so that
 * every member of it is expressible as data; {@code botmaker-plugin-protocol} is that data on a wire; and
 * this is where the two meet, so that a host asking a plugin for its toolbar items cannot tell whether the
 * answer came from a {@code ServiceLoader} or from a Node process. <b>The host has one plugin interface and
 * two transports</b>, and only this file knows there is a second one.
 *
 * <h2>The promise, and what enforces it</h2>
 *
 * <p><b>A broken companion plugin may not stop a project from opening.</b> That is the rule
 * {@code PluginHost.discover} already applies to an in-process plugin whose constructor throws, and an
 * out-of-process plugin has three new ways to break it: a process that will not start, one that starts and
 * never answers, and one that dies later.
 *
 * <ul>
 *   <li><b>Will not start / will not answer</b> — {@link #launch} is the only blocking call and it is bounded
 *       by {@link CompanionDescriptor#handshakeTimeoutMillis()}. It throws {@link CompanionLaunchException},
 *       which is checked, so a host cannot forget to decide what to do about it.
 *   <li><b>Dies later</b> — supervised. The process is restarted <b>once</b>, and a second death is reported
 *       through the failure reporter and the plugin goes quiet. Once rather than forever because a plugin
 *       that cannot survive two starts is broken, and a restart loop is how a broken plugin becomes a fan
 *       spinning up on a laptop with nothing on screen to explain it.
 *   <li><b>Hangs while answering</b> — every call after the handshake is either a notification or is not
 *       waited on. The host asks for the toolbar once, at launch, and after that only tells.
 * </ul>
 *
 * <h2>stdout is the protocol; stderr is the log</h2>
 *
 * <p>The child's stdin and stdout carry JSON-RPC, so <b>anything a plugin prints to stdout corrupts the
 * connection</b> — a {@code console.log} in a Node companion is not a stray line in a log, it is the reason
 * the plugin stopped responding. Its stderr is drained here, prefixed with the plugin's id and forwarded, and
 * the last few lines are kept so that a failure can be reported with the plugin's own last words rather than
 * with an exit code alone. It is the single most common thing a new companion author gets wrong, which is
 * why it is stated here and in the protocol module's README rather than left to be discovered.
 *
 * <h2>One process per bind, and why {@code projectClosing()} ends it</h2>
 *
 * <p>The protocol distinguishes {@code projectClosing} (this project is over) from {@code shutdown} (you are
 * being discarded), and this host sends both, in that order, when a project closes. That looks like a waste
 * of the distinction and is not: the process was spawned from <em>this project's</em> resolved classpath, so
 * the next project — which may pin a different version of the same plugin — must get its own. The
 * distinction is there for a host that pools one process across projects, and this is not one.
 */
public final class ProcessPlugin implements CompanionPlugin, Closeable {

    /** How many stderr lines are kept for a failure message. Enough for a stack trace, not a log file. */
    private static final int LOG_TAIL_LINES = 40;

    /** How long the process gets to exit politely after {@code shutdown} before it is killed. */
    private static final long SHUTDOWN_GRACE_MILLIS = 2_000L;

    private final CompanionDescriptor descriptor;
    private final StudioPeer host;
    private final HostInfo hostInfo;
    private final Consumer<String> reporter;

    /** The last few lines the child said on stderr, so a death can be reported in the plugin's own words. */
    private final Deque<String> log = new ArrayDeque<>();

    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    private Session session;
    private Handshake handshake;
    private List<ToolbarItem> items = List.of();
    private Map<String, WireToolbarItem> published = Map.of();
    private boolean restarted;
    private boolean failed;
    private Thread shutdownHook;

    private ProcessPlugin(CompanionDescriptor descriptor, StudioPeer host, HostInfo hostInfo,
                          Consumer<String> reporter) {
        this.descriptor = descriptor;
        this.host = host;
        this.hostInfo = hostInfo;
        this.reporter = reporter == null ? System.err::println : reporter;
    }

    /**
     * Starts {@code descriptor}'s process, handshakes with it, and returns it as a {@link CompanionPlugin}.
     *
     * <p>Blocks for at most the descriptor's handshake timeout, and that is the only place this class ever
     * makes the caller wait. Everything the process is asked for afterwards either has an answer already or
     * is a notification.
     *
     * @param host     what the plugin may call back on — normally a {@link ServicesPeer}
     * @param hostInfo what the plugin is told about the host and the open project
     * @param reporter where a later failure is reported, so a user sees it: a host passes its status line.
     *                 {@code null} means stderr, which is honest for a CLI and wrong for an editor
     * @throws CompanionLaunchException when the process will not start, does not answer in time, or answers
     *                                  a handshake with no id
     */
    public static ProcessPlugin launch(CompanionDescriptor descriptor, StudioPeer host, HostInfo hostInfo,
                                       Consumer<String> reporter) throws CompanionLaunchException {
        if (descriptor == null || !descriptor.isUsable()) {
            throw new CompanionLaunchException(descriptor == null ? "" : descriptor.id(),
                    "The companion descriptor names no id or no command.");
        }
        ProcessPlugin plugin = new ProcessPlugin(descriptor, host, hostInfo, reporter);
        plugin.start(true);
        return plugin;
    }

    /** The same, reporting a later failure to stderr. */
    public static ProcessPlugin launch(CompanionDescriptor descriptor, StudioPeer host, HostInfo hostInfo)
            throws CompanionLaunchException {
        return launch(descriptor, host, hostInfo, null);
    }

    /**
     * The plugin's own id, from the handshake.
     *
     * <p>The handshake's rather than the descriptor's, when they disagree: the descriptor is what somebody
     * wrote in a file and the handshake is what the code answers, and the same rule holds here as everywhere
     * else in this project — the running thing is the authority. The descriptor's id is what names a plugin
     * that never got this far.
     */
    @Override
    public String id() {
        Handshake current = handshake;
        return current != null && current.isUsable() ? current.id() : descriptor.id();
    }

    @Override
    public String displayName() {
        Handshake current = handshake;
        if (current != null && !current.displayName().isBlank()) return current.displayName();
        return descriptor.displayName();
    }

    /**
     * The buttons the plugin published at launch.
     *
     * <p>Asked once and cached, exactly as the in-process merge asks each plugin once. A request per button
     * per layout is how a toolbar becomes the slowest thing in the editor, and here it would also be a
     * process boundary crossed on the UI thread.
     *
     * <p><b>The labels are still live, though the set is not.</b> Each item's label is a {@link Supplier}
     * reading the most recently published text for that id, so a plugin that restarts and republishes gets
     * its new labels onto the existing bar. What it cannot do is add or remove a button without the host
     * rebuilding — which is the honest cost of the wire not carrying a supplier.
     */
    @Override
    public List<ToolbarItem> toolbarItems() {
        return items;
    }

    /**
     * The project is closing: tell the plugin, then end the process.
     *
     * <p>Both messages, in that order, for the reason in the class javadoc — this host gives each bind its
     * own process, so {@code projectClosing} is immediately followed by the plugin being discarded. The
     * notification still goes first: a plugin that holds a port or a nested display should be given the
     * chance to let go of it before its process is asked to exit.
     */
    @Override
    public void projectClosing() {
        PluginPeer remote = remote();
        if (remote != null && declares(Capability.PROJECT_LIFECYCLE)) {
            tell(() -> remote.projectClosing());
        }
        close();
    }

    /**
     * Forwards one telemetry frame, if the plugin asked for them.
     *
     * <p>A method the host calls rather than a subscription this class makes for itself, because
     * {@code Runs} lives on {@code StudioServices} and subscribing here would mean holding the host's
     * services for the life of the process and unsubscribing correctly on every failure path. The host
     * already owns that lifecycle for its in-process plugins; this joins it rather than starting a second.
     */
    public void deliverTelemetry(byte[] frame) {
        PluginPeer remote = remote();
        if (remote == null || frame == null || !declares(Capability.TELEMETRY)) return;
        tell(() -> remote.telemetry(frame));
    }

    /** Forwards a run starting or stopping, if the plugin asked for telemetry. */
    public void deliverRunState(boolean running) {
        PluginPeer remote = remote();
        if (remote == null || !declares(Capability.TELEMETRY)) return;
        tell(() -> remote.runStateChanged(running));
    }

    /** Whether the process is up. False after a second death, and after {@link #close()}. */
    public boolean isAlive() {
        synchronized (lock) {
            return !failed && !closed.get() && session != null && session.process.isAlive();
        }
    }

    /** What the plugin said it implements, for a host that wants to explain itself in a diagnostic. */
    public Handshake handshake() {
        return handshake;
    }

    /** The descriptor this was launched from. */
    public CompanionDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Ends the process. Idempotent, and safe from any thread.
     *
     * <p>Politely first — a {@code shutdown} notification, then {@link Process#destroy()}, then
     * {@link Process#destroyForcibly()} after a grace period. A plugin that ignores all three does not get a
     * fourth chance: an orphaned child process outliving the editor is the failure a user cannot see and
     * cannot fix.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Session ending;
        Thread hook;
        synchronized (lock) {
            ending = session;
            session = null;
            hook = shutdownHook;
            shutdownHook = null;
        }
        removeHook(hook);
        if (ending == null) return;

        if (ending.remote != null) tell(() -> ending.remote.shutdown());
        end(ending);
    }

    // ---------------------------------------------------------------------------------------------------
    // Starting, and starting again
    // ---------------------------------------------------------------------------------------------------

    /**
     * Spawns, wires, handshakes and publishes.
     *
     * @param initial true for the first attempt, whose failure is the caller's to handle; false for a
     *                restart, whose failure is nobody's to handle and is reported instead
     */
    private void start(boolean initial) throws CompanionLaunchException {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(descriptor.command());
            builder.directory(descriptor.workingDirectory().toFile());
            // NOT redirectErrorStream: stdout is the protocol. Merging the two would put the plugin's own
            // log lines into the JSON-RPC stream, which is the one thing that cannot be recovered from.
            process = builder.start();
        } catch (IOException | RuntimeException notStarted) {
            throw new CompanionLaunchException(descriptor.id(),
                    "Could not start " + String.join(" ", descriptor.command()) + " in "
                            + descriptor.workingDirectory() + ": " + notStarted.getMessage(), notStarted);
        }

        // Daemon threads, and it is not a detail: LSP4J's default executor is a plain cached pool whose
        // non-daemon threads would keep the JVM alive after the editor's last window closed.
        ExecutorService executor = Executors.newCachedThreadPool(daemons(descriptor.id()));
        Launcher<PluginPeer> launcher =
                Peers.forHost(host, process.getInputStream(), process.getOutputStream(), executor);
        Future<?> listening = launcher.startListening();
        Thread drain = drainStderr(process);
        Session started = new Session(process, launcher.getRemoteProxy(), listening, executor, drain);

        Handshake shook;
        try {
            // Raced against the process exiting, and the race is worth the four lines: LSP4J does not fail
            // a pending request when the stream underneath it closes, so a plugin that dies mid-handshake
            // would otherwise cost the full timeout and be reported as "did not answer" — which sends its
            // author looking for a hang rather than reading the exit code and the stderr they already have.
            Object first = CompletableFuture
                    .anyOf(started.remote.initialize(hostInfo), process.onExit())
                    .get(descriptor.handshakeTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (first instanceof Process dead) {
                end(started);
                throw new CompanionLaunchException(descriptor.id(),
                        "The plugin exited (" + dead.exitValue() + ") before answering the handshake."
                                + tail());
            }
            shook = (Handshake) first;
        } catch (TimeoutException silent) {
            end(started);
            throw new CompanionLaunchException(descriptor.id(),
                    "The plugin started but did not answer in " + descriptor.handshakeTimeoutMillis()
                            + " ms." + tail(), silent);
        } catch (ExecutionException | RuntimeException refused) {
            end(started);
            throw new CompanionLaunchException(descriptor.id(),
                    "The plugin failed its handshake: " + refused.getMessage() + tail(), refused);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            end(started);
            throw new CompanionLaunchException(descriptor.id(),
                    "Interrupted while waiting for the plugin's handshake.", interrupted);
        }

        if (shook == null || !shook.isUsable()) {
            end(started);
            throw new CompanionLaunchException(descriptor.id(),
                    "The plugin answered a handshake with no id, so nothing it contributes could be "
                            + "attributed to it." + tail());
        }
        warnAboutUnknownCapabilities(shook);

        synchronized (lock) {
            if (closed.get()) {
                // Closed while we were handshaking. Nothing was published, so ending the session is all
                // there is to undo.
                end(started);
                return;
            }
            session = started;
            handshake = shook;
            failed = false;
        }
        publishToolbar(started, shook);
        supervise(started);
        if (initial) installShutdownHook();
    }

    /**
     * Asks for the toolbar once and turns it into contract items.
     *
     * <p>A failure here is <b>not</b> a launch failure: a plugin that handshaked and then could not answer
     * for its buttons is a plugin with no buttons, which is an ordinary state — a companion may exist only
     * to watch telemetry. It is reported and the plugin stays.
     */
    private void publishToolbar(Session started, Handshake shook) {
        if (!shook.implementsCapability(Capability.TOOLBAR)) {
            items = List.of();
            published = Map.of();
            return;
        }
        List<WireToolbarItem> wire;
        try {
            wire = started.remote.toolbarItems()
                    .get(descriptor.handshakeTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (TimeoutException | ExecutionException | RuntimeException noAnswer) {
            report("declared toolbar items and did not answer for them: " + noAnswer);
            return;
        }
        if (wire == null) return;

        Map<String, WireToolbarItem> byId = new LinkedHashMap<>();
        List<ToolbarItem> built = new ArrayList<>();
        for (WireToolbarItem item : wire) {
            if (!Wire.isDrawable(item)) {
                if (item != null) report("published an unusable toolbar item: " + item);
                continue;
            }
            if (byId.put(item.id(), item) != null) {
                report("published two toolbar items called '" + item.id() + "'; the later one wins.");
            }
        }
        published = Map.copyOf(byId);
        for (WireToolbarItem item : byId.values()) {
            built.add(toolbarItem(item));
        }
        items = List.copyOf(built);
    }

    /**
     * One contract item over one wire item.
     *
     * <p>The label and icon are suppliers reading {@link #published}, so a republish after a restart reaches
     * a bar the host has already drawn. The press is a notification naming the item's id — which is what a
     * {@code Consumer<ActionContext>} becomes when it has to cross a process. {@link ActionContext} itself
     * does not cross: it hands over the host's live {@code StudioServices}, and the plugin already has those
     * as a {@link StudioPeer}.
     */
    private ToolbarItem toolbarItem(WireToolbarItem item) {
        String itemId = item.id();
        ToolbarGroup group = Wire.group(item.group());
        EnabledWhen enabledWhen = Wire.enabledWhen(item.enabledWhen());
        String tooltip = item.tooltip().isBlank() ? null : item.tooltip();
        Supplier<String> label = () -> text(itemId, WireToolbarItem::label, item.label());
        Supplier<String> icon = () -> {
            String current = text(itemId, WireToolbarItem::icon, item.icon());
            return current.isBlank() ? null : current;
        };
        return new ToolbarItem(itemId, label, tooltip, icon, group, item.order(), enabledWhen,
                context -> press(itemId));
    }

    private String text(String itemId, java.util.function.Function<WireToolbarItem, String> field,
                        String fallback) {
        WireToolbarItem current = published.get(itemId);
        return current == null ? fallback : field.apply(current);
    }

    private void press(String itemId) {
        PluginPeer remote = remote();
        if (remote == null) {
            report("could not be told about '" + itemId + "' because it is not running.");
            return;
        }
        tell(() -> remote.toolbarInvoked(itemId));
    }

    // ---------------------------------------------------------------------------------------------------
    // Supervision
    // ---------------------------------------------------------------------------------------------------

    /**
     * Watches for the process dying and restarts it once.
     *
     * <p>{@link Process#onExit()} rather than a polling thread: the JVM already knows, and a poll would be a
     * thread per plugin doing nothing most of the time.
     */
    private void supervise(Session started) {
        started.process.onExit().thenAccept(exited -> died(started, exited.exitValue()));
    }

    private void died(Session dead, int exitCode) {
        boolean giveUp;
        synchronized (lock) {
            if (closed.get() || session != dead) return;   // An expected death: we ended it ourselves.
            session = null;
            giveUp = restarted;
            restarted = true;
            if (giveUp) giveUpUnderLock();
        }
        // Outside the lock from here: end() waits on a process, and the reporter is the host's own code —
        // neither belongs inside a monitor a supervision callback also takes.
        end(dead);
        if (giveUp) {
            report("exited (" + exitCode + ") a second time and will not be restarted." + tail());
            return;
        }
        report("exited (" + exitCode + "); restarting it once." + tail());
        try {
            start(false);
        } catch (CompanionLaunchException notRestarted) {
            synchronized (lock) {
                giveUpUnderLock();
            }
            report("could not be restarted: " + notRestarted.getMessage());
        }
    }

    /** The plugin is done: no process, no buttons, and nothing left for the host to call. */
    private void giveUpUnderLock() {
        failed = true;
        items = List.of();
        published = Map.of();
    }

    // ---------------------------------------------------------------------------------------------------
    // Process plumbing
    // ---------------------------------------------------------------------------------------------------

    /** Ends one session: stop listening, kill the process, release the threads. Never throws. */
    private void end(Session ending) {
        ending.listening.cancel(true);
        Process process = ending.process;
        try {
            process.destroy();
            if (!process.waitFor(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (RuntimeException stubborn) {
            process.destroyForcibly();
        }
        ending.executor.shutdownNow();
        if (ending.drain != null) ending.drain.interrupt();
    }

    /**
     * Drains the child's stderr onto ours, prefixed, keeping the tail for a failure message.
     *
     * <p>Draining is not optional even if nobody reads it: a child whose stderr pipe fills stops writing,
     * and a plugin blocked on a full pipe looks exactly like a plugin that has hung.
     */
    private Thread drainStderr(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.err.println("[" + descriptor.id() + "] " + line);
                    synchronized (log) {
                        log.addLast(line);
                        while (log.size() > LOG_TAIL_LINES) log.removeFirst();
                    }
                }
            } catch (IOException | RuntimeException closed) {
                // The pipe closing is how this thread ends in the ordinary case.
            }
        }, "companion-stderr-" + descriptor.id());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** The child's last words, as a sentence to append to a failure message, or {@code ""}. */
    private String tail() {
        List<String> lines;
        synchronized (log) {
            if (log.isEmpty()) return "";
            lines = List.copyOf(log);
        }
        return System.lineSeparator() + String.join(System.lineSeparator(), lines);
    }

    /**
     * Kills the child if the JVM exits without anybody closing this.
     *
     * <p>Belt and braces, and earned: a child process is not a resource the JVM reclaims, so a host that
     * crashes or is killed leaves it running with no window and no way for a user to connect it to anything
     * they did. The hook does the minimum — it does not try to be polite to a JVM that is already leaving.
     */
    private void installShutdownHook() {
        Thread hook = new Thread(() -> {
            Session running;
            synchronized (lock) {
                running = session;
            }
            if (running != null) running.process.destroyForcibly();
        }, "companion-reaper-" + descriptor.id());
        synchronized (lock) {
            shutdownHook = hook;
        }
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            synchronized (lock) {
                shutdownHook = null;
            }
        }
    }

    private static void removeHook(Thread hook) {
        if (hook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // Being removed during shutdown is not a failure; the hook is about to run or has run.
        }
    }

    private PluginPeer remote() {
        synchronized (lock) {
            return session == null ? null : session.remote;
        }
    }

    private boolean declares(String capability) {
        Handshake current = handshake;
        return current != null && current.implementsCapability(capability);
    }

    /**
     * Sends a notification, swallowing a broken pipe.
     *
     * <p>A notification to a dead process is not an error worth propagating: the supervisor has already been
     * told by {@link Process#onExit()} and is dealing with it, and a second report of the same death would
     * reach the user as two messages about one event.
     */
    private void tell(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException gone) {
            // Deliberately silent; see above.
        }
    }

    private void warnAboutUnknownCapabilities(Handshake shook) {
        for (String capability : shook.capabilities()) {
            if (!Capability.isKnown(capability)) {
                System.err.println("Note: companion plugin '" + shook.id() + "' declares the capability '"
                        + capability + "', which this host does not know about and will not call.");
            }
        }
    }

    private void report(String what) {
        reporter.accept("Companion plugin '" + descriptor.id() + "' " + what);
    }

    private static ThreadFactory daemons(String pluginId) {
        return runnable -> {
            Thread thread = new Thread(runnable, "companion-rpc-" + pluginId);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * One live connection to one process.
     *
     * <p>Grouped rather than kept as five fields because a restart replaces all of them at once, and the one
     * bug this shape rules out is a half-replaced connection — a new process talking over the old process's
     * streams. {@link #died} compares by identity for the same reason: only the session that actually died
     * may trigger a restart.
     */
    private record Session(Process process, PluginPeer remote, Future<?> listening, ExecutorService executor,
                           Thread drain) {
    }
}
