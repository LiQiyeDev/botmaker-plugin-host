package com.botmaker.plugin.host;

import com.botmaker.plugin.api.Capture;
import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.protocol.Capability;
import com.botmaker.plugin.protocol.StudioPeer;
import com.botmaker.plugin.protocol.WireColor;
import com.botmaker.plugin.protocol.WireRegion;
import com.botmaker.plugin.protocol.WireThemeTokens;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A host's {@code StudioServices} exposed to a plugin in another process — the callback half of the
 * protocol.
 *
 * <p>The mirror of {@link ProcessPlugin}: that one makes a remote plugin look like a
 * {@code CompanionPlugin} to the host, and this one makes the host look like a {@code StudioPeer} to the
 * remote plugin. Between them the process boundary is invisible from either side, which is the whole
 * ambition of the module.
 *
 * <h2>Every call arrives on a JSON-RPC thread, and almost none of them may stay there</h2>
 *
 * <p>{@code Capture} puts an overlay over the screen and {@code Dialogs} opens a native chooser: both are
 * JavaFX, both must run on the toolkit's own thread, and a wire request arrives on whichever thread LSP4J
 * is reading with. So this takes an {@link Executor} — {@code Platform::runLater} in Studio,
 * {@code Runnable::run} in a test — and everything touching those two services goes through it.
 *
 * <p>It is a constructor parameter rather than a hard-coded {@code Platform.runLater} because this module
 * must not depend on a running JavaFX toolkit. A host that never calls the overlay never starts one, and
 * the CLI is exactly that host.
 *
 * <h2>Cancellation, and the one gap this class cannot close</h2>
 *
 * <p>{@code Capture.selectRegion(Consumer)} signals a cancel by <b>never invoking its callback</b> — in
 * process an editor simply leaves its slot as it found it. A request-response wire has no equivalent
 * silence: the request has to be answered or the caller waits forever, which is why the wire has
 * {@link WireRegion#CANCELLED}. The host cannot tell the difference, so every such request carries a safety
 * timeout that resolves cancelled.
 *
 * <p><b>That is a mitigation, not a fix, and it is recorded rather than hidden.</b> The fix is a contract
 * member saying the user dismissed the overlay, which is a growth of {@code Capture} that has to be argued
 * on the host-is-the-only-possible-source test in its own right. The timeout is long by design: it exists
 * so nothing leaks forever, not to bound how long a user may take.
 */
public final class ServicesPeer implements StudioPeer {

    /**
     * How long an overlay or a dialog request may stay outstanding before it is answered cancelled.
     *
     * <p>Deliberately far longer than any human interaction. It is not a patience limit — it is the only
     * thing standing between a cancelled overlay and a plugin waiting for the rest of the session.
     */
    private static final long INTERACTION_TIMEOUT_MINUTES = 10;

    /** How long a single frame grab may take. A capture that has not answered by now is not going to. */
    private static final long FRAME_TIMEOUT_SECONDS = 30;

    private final StudioServices services;
    private final Executor uiThread;

    /**
     * @param services the host's own services, exactly as an in-process plugin receives them
     * @param uiThread where anything touching {@code Capture} or {@code Dialogs} is run —
     *                 {@code Platform::runLater} for a JavaFX host
     */
    public ServicesPeer(StudioServices services, Executor uiThread) {
        this.services = services;
        this.uiThread = uiThread;
    }

    /**
     * What a host built on {@code services} can honestly say it offers.
     *
     * <p>Four of the five are unconditional because the contract makes them so: {@code capture()} and
     * {@code dialogs()} are abstract members every host implements, and {@code themeTokens()} and
     * {@code status()} have defaults that answer rather than fail. {@link Capability#RUNS} is the one that
     * varies, because {@code Runs.NONE} is a real and supported answer — the CLI's validator runs no bots —
     * and a host declaring run control it does not have would have a plugin drawing a Start button that
     * does nothing.
     */
    public static Set<String> capabilitiesOf(StudioServices services) {
        Set<String> offered = new LinkedHashSet<>(
                List.of(Capability.CAPTURE, Capability.STATUS, Capability.THEME, Capability.DIALOGS));
        if (services != null && services.runs() != Runs.NONE) offered.add(Capability.RUNS);
        return Set.copyOf(offered);
    }

    @Override
    public CompletableFuture<WireRegion> selectRegion() {
        return interaction(WireRegion.CANCELLED,
                done -> capture().selectRegion(region -> done.accept(Wire.region(region))));
    }

    @Override
    public CompletableFuture<WireRegion> pickPoint() {
        return interaction(WireRegion.CANCELLED,
                done -> capture().pickPoint(region -> done.accept(Wire.region(region))));
    }

    @Override
    public CompletableFuture<WireColor> sampleColor() {
        return interaction(WireColor.CANCELLED,
                done -> capture().sampleColor(color -> done.accept(Wire.color(color))));
    }

    @Override
    public CompletableFuture<byte[]> grabFrame() {
        CompletableFuture<byte[]> answer = new CompletableFuture<>();
        // Encoding happens on whichever thread the callback arrives on, which for a JavaFX host is the FX
        // thread — a full-screen PNG encode there is a visible stutter. Handed off deliberately.
        onUi(() -> capture().grabFrame(image -> CompletableFuture
                .supplyAsync(() -> Wire.png(image))
                .thenAccept(answer::complete)), () -> answer.complete(new byte[0]));
        return answer.completeOnTimeout(new byte[0], FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public CompletableFuture<Void> runStart() {
        return run(() -> services.runs().start());
    }

    @Override
    public CompletableFuture<Void> runStop() {
        return run(() -> services.runs().stop());
    }

    @Override
    public CompletableFuture<Boolean> isRunning() {
        return CompletableFuture.completedFuture(safely(() -> services.runs().isRunning(), false));
    }

    @Override
    public CompletableFuture<WireThemeTokens> themeTokens() {
        return CompletableFuture.completedFuture(
                safely(() -> Wire.themeTokens(services.themeTokens()), WireThemeTokens.DEFAULT));
    }

    @Override
    public CompletableFuture<String> chooseFile(String title, String initialDir, List<String> extensions) {
        String[] filters = extensions == null ? new String[0] : extensions.toArray(String[]::new);
        return chooser(() -> services.dialogs().chooseFile(title, pathOrNull(initialDir), filters));
    }

    @Override
    public CompletableFuture<String> chooseDirectory(String title, String initialDir) {
        return chooser(() -> services.dialogs().chooseDirectory(title, pathOrNull(initialDir)));
    }

    @Override
    public void status(String message) {
        // Not dispatched to the UI thread: a host's status line is its own to marshal, and every
        // implementation in this project already does. Dispatching here would double it.
        try {
            services.status(message == null ? "" : message);
        } catch (RuntimeException ignored) {
            // A status line that throws must never be why a plugin's request fails.
        }
    }

    /**
     * An overlay request: run it on the UI thread, answer whatever the callback brings, and answer
     * {@code cancelled} if nothing ever does.
     *
     * <p>The {@code Consumer<Consumer<T>>} shape reads badly and is the honest one — the caller is handed
     * the completion and decides when to use it, because that is exactly what {@code Capture}'s own
     * signature does.
     */
    private <T> CompletableFuture<T> interaction(T cancelled, Consumer<Consumer<T>> start) {
        CompletableFuture<T> answer = new CompletableFuture<>();
        onUi(() -> start.accept(answer::complete), () -> answer.complete(cancelled));
        return answer.completeOnTimeout(cancelled, INTERACTION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    private CompletableFuture<String> chooser(Supplier<Dialogs.Choice> open) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        onUi(() -> {
            Dialogs.Choice choice = open.get();
            Optional<Path> chosen = choice == null ? Optional.empty() : choice.path();
            answer.complete(chosen.map(Path::toString).orElse(""));
        }, () -> answer.complete(""));
        return answer.completeOnTimeout("", INTERACTION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    private CompletableFuture<Void> run(Runnable action) {
        safely(() -> {
            action.run();
            return null;
        }, null);
        // Answered rather than awaited: start() and stop() are asynchronous in every host that implements
        // them, so a future that resolved "when the bot is running" would be a promise this cannot keep.
        // A plugin learns the truth from runStateChanged, which is why that notification exists.
        return CompletableFuture.completedFuture(null);
    }

    private Capture capture() {
        return services.capture();
    }

    /**
     * Runs {@code action} on the host's UI thread, taking {@code onFailure} if it cannot be reached at all.
     *
     * <p>The failure arm is not defensive padding: a host shutting down rejects work, and a request left
     * hanging because of it would outlive the host it was asked of.
     */
    private void onUi(Runnable action, Runnable onFailure) {
        try {
            uiThread.execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException failed) {
                    System.err.println("Warning: a plugin's request failed in the host: " + failed);
                    onFailure.run();
                }
            });
        } catch (RuntimeException unreachable) {
            System.err.println("Warning: could not reach the host's UI thread: " + unreachable);
            onFailure.run();
        }
    }

    private static <T> T safely(Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException failed) {
            System.err.println("Warning: a plugin's request failed in the host: " + failed);
            return fallback;
        }
    }

    /** {@code Dialogs} takes {@code null} for "the host's choice", and the wire spells that {@code ""}. */
    private static Path pathOrNull(String directory) {
        return directory == null || directory.isBlank() ? null : Path.of(directory);
    }
}
