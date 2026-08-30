package com.botmaker.plugin.host;

import com.botmaker.plugin.protocol.Capability;
import com.botmaker.plugin.protocol.Handshake;
import com.botmaker.plugin.protocol.HostInfo;
import com.botmaker.plugin.protocol.Peers;
import com.botmaker.plugin.protocol.PluginPeer;
import com.botmaker.plugin.protocol.StudioPeer;
import com.botmaker.plugin.protocol.WireToolbarItem;

import org.eclipse.lsp4j.jsonrpc.Launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * A companion plugin, in a real second process, that behaves badly on request.
 *
 * <p>Run as {@code java -cp <the test classpath> CompanionFixture <mode> <marker file>} by
 * {@link ProcessPluginTest}. It is a fixture rather than a mock deliberately: everything interesting about
 * {@link ProcessPlugin} — a process that never answers, one that dies twice, one that is still running after
 * the host thinks it closed — is a property of an actual operating-system process, and a stubbed
 * {@code Process} would be testing the stub.
 *
 * <p>It writes what it was asked to do into the marker file, one line per event, because the alternative is
 * asserting on the child's stderr and racing the drain thread.
 *
 * <p><b>Nothing here may print to stdout.</b> That is the JSON-RPC channel, and a stray {@code println}
 * corrupts the connection — the exact mistake the module's documentation warns companion authors about,
 * which is a good reason for its own test fixture to have to live by it.
 */
public final class CompanionFixture implements PluginPeer {

    /** Answers everything, publishes two toolbar items, records what it is told. */
    static final String OK = "ok";

    /** Starts, and never answers {@code initialize}. */
    static final String SILENT = "silent";

    /** Exits before speaking at all. */
    static final String CRASH = "crash";

    /** Handshakes with a blank id, which the host must refuse. */
    static final String ANONYMOUS = "anonymous";

    /** Handshakes, then exits — every time, so the host's one restart is used up and it fails. */
    static final String DIE_AFTER = "dieAfter";

    /** Handshakes, then calls the host back and records the answers. */
    static final String CALLS_BACK = "callsBack";

    private final String mode;
    private final Path marker;
    private StudioPeer host;

    private CompanionFixture(String mode, Path marker) {
        this.mode = mode;
        this.marker = marker;
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path marker = Path.of(args[1]);
        if (CRASH.equals(mode)) {
            System.err.println("fixture: exiting before the handshake, on purpose");
            System.exit(3);
        }

        CompanionFixture fixture = new CompanionFixture(mode, marker);
        Launcher<StudioPeer> launcher = Peers.forPlugin(fixture, System.in, System.out,
                Executors.newCachedThreadPool());
        fixture.host = launcher.getRemoteProxy();
        // Blocks until the host closes the connection, which is how this process ordinarily ends.
        launcher.startListening().get();
    }

    @Override
    public CompletableFuture<Handshake> initialize(HostInfo host) {
        record("initialize " + host.hostName() + " " + host.projectDir());
        if (SILENT.equals(mode)) {
            // Never completed. A host cannot tell this apart from a slow start, which is the point.
            return new CompletableFuture<>();
        }
        if (ANONYMOUS.equals(mode)) {
            return CompletableFuture.completedFuture(new Handshake("", "No Name", "1", Set.of()));
        }
        if (DIE_AFTER.equals(mode)) {
            exitShortly();
        }
        if (CALLS_BACK.equals(mode)) {
            callBack();
        }
        return CompletableFuture.completedFuture(new Handshake("fixture", "The Fixture", "1.0",
                Set.of(Capability.TOOLBAR, Capability.PROJECT_LIFECYCLE, Capability.TELEMETRY,
                        "somethingNewerThanThisHost")));
    }

    @Override
    public CompletableFuture<List<WireToolbarItem>> toolbarItems() {
        return CompletableFuture.completedFuture(List.of(
                new WireToolbarItem("go", "Go", "Does the thing", "🎮", "RUN", 10, "BOT_STOPPED"),
                // Dropped by the host: STUDIO is its own section of the bar.
                new WireToolbarItem("mine", "Mine", "", "", "STUDIO", 0, "ALWAYS"),
                // Dropped by the host: a group this build has never heard of.
                new WireToolbarItem("future", "Future", "", "", "HOLOGRAM", 0, "ALWAYS"),
                // Dropped by the wire's own rule: no label.
                new WireToolbarItem("blank", "", "", "", "TOOLS", 0, "ALWAYS")));
    }

    @Override
    public void toolbarInvoked(String itemId) {
        record("invoked " + itemId);
    }

    @Override
    public void telemetry(byte[] frame) {
        record("telemetry " + frame.length);
    }

    @Override
    public void runStateChanged(boolean running) {
        record("runStateChanged " + running);
    }

    @Override
    public void projectClosing() {
        record("projectClosing");
    }

    @Override
    public void shutdown() {
        record("shutdown");
    }

    private void callBack() {
        host.status("hello from the fixture");
        host.themeTokens().thenAccept(tokens -> record("theme " + tokens.background()));
        host.isRunning().thenAccept(running -> record("isRunning " + running));
        host.selectRegion().thenAccept(region -> record("region " + region.x() + " " + region.cancelled()));
    }

    private void exitShortly() {
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            record("dying");
            System.err.println("fixture: exiting after the handshake, on purpose");
            Runtime.getRuntime().halt(4);
        });
        killer.setDaemon(true);
        killer.start();
    }

    private void record(String line) {
        try {
            Files.writeString(marker, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            System.err.println("fixture: could not record '" + line + "': " + unwritable);
        }
    }
}
