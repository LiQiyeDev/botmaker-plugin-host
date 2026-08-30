package com.botmaker.plugin.host;

import com.botmaker.plugin.api.EnabledWhen;
import com.botmaker.plugin.api.ToolbarGroup;
import com.botmaker.plugin.api.ToolbarItem;
import com.botmaker.plugin.protocol.HostInfo;
import com.botmaker.plugin.protocol.StudioPeer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ProcessPlugin} against a real second process.
 *
 * <p>Every test here starts an actual JVM running {@link CompanionFixture}, because everything worth
 * checking about this class is a property of an operating-system process: one that never answers, one that
 * dies twice, one that has to be gone after the host closes it. A stubbed {@code Process} would check that
 * the stub does what the test told it to.
 *
 * <p><b>The rule under test throughout is the module's one promise:</b> a broken companion plugin becomes a
 * reported failure, never a host that waits.
 */
class ProcessPluginTest {

    private static final long PATIENCE_MILLIS = 20_000;

    @TempDir
    Path work;

    private final List<String> reported = new ArrayList<>();
    private final List<ProcessPlugin> started = new ArrayList<>();

    private Path marker;

    @BeforeEach
    void setUp() throws IOException {
        marker = work.resolve("events.log");
        Files.writeString(marker, "");
    }

    @AfterEach
    void tearDown() {
        for (ProcessPlugin plugin : started) plugin.close();
    }

    @Test
    void a_companion_in_another_process_looks_like_an_ordinary_plugin() throws Exception {
        ProcessPlugin plugin = launch(CompanionFixture.OK);

        assertEquals("fixture", plugin.id(), "the handshake's id wins over the descriptor's");
        assertEquals("The Fixture", plugin.displayName());
        assertTrue(plugin.isAlive());
        assertNotNull(plugin.handshake());
    }

    @Test
    void only_the_toolbar_items_the_host_can_draw_survive() throws Exception {
        ProcessPlugin plugin = launch(CompanionFixture.OK);

        List<ToolbarItem> items = plugin.toolbarItems();
        assertEquals(1, items.size(),
                "STUDIO is the host's own group, HOLOGRAM is unknown, and a blank label is not a button");
        ToolbarItem go = items.getFirst();
        assertEquals("go", go.id());
        assertEquals("Go", go.label().get());
        assertEquals("🎮", go.icon().get());
        assertEquals("Does the thing", go.tooltip());
        assertEquals(ToolbarGroup.RUN, go.group());
        assertEquals(10, go.order());
        assertEquals(EnabledWhen.BOT_STOPPED, go.enabledWhen());
    }

    @Test
    void pressing_a_button_reaches_the_other_process() throws Exception {
        ProcessPlugin plugin = launch(CompanionFixture.OK);

        plugin.toolbarItems().getFirst().onClick().accept(null);

        await("the press to arrive", () -> events().contains("invoked go"));
    }

    @Test
    void telemetry_and_run_state_reach_the_other_process() throws Exception {
        ProcessPlugin plugin = launch(CompanionFixture.OK);

        plugin.deliverTelemetry(new byte[7]);
        plugin.deliverRunState(true);

        await("the frame", () -> events().contains("telemetry 7"));
        await("the run state", () -> events().contains("runStateChanged true"));
    }

    @Test
    void a_plugin_may_call_the_host_back() throws Exception {
        List<String> said = new ArrayList<>();
        StudioPeer host = new StudioPeer() {
            @Override
            public void status(String message) {
                synchronized (said) {
                    said.add(message);
                }
            }
        };

        launch(CompanionFixture.CALLS_BACK, host, CompanionDescriptor.DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);

        await("the status line", () -> {
            synchronized (said) {
                return said.contains("hello from the fixture");
            }
        });
        // The rest of StudioPeer is entirely defaulted here, which is the point of every member being
        // default: a host that implements nothing still answers, and the answers are the honest ones.
        await("the defaulted theme", () -> events().contains("theme #FFFFFF"));
        await("the defaulted run state", () -> events().contains("isRunning false"));
        await("a cancelled overlay", () -> events().contains("region 0 true"));
    }

    @Test
    void a_process_that_never_answers_is_refused_in_bounded_time() {
        long before = System.nanoTime();

        CompanionLaunchException refused = assertThrows(CompanionLaunchException.class,
                () -> launch(CompanionFixture.SILENT, new StudioPeer() {}, 800));

        long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
        assertTrue(refused.getMessage().contains("did not answer"), refused.getMessage());
        assertTrue(elapsedMillis < PATIENCE_MILLIS,
                "the host waited " + elapsedMillis + " ms, which is not bounded by the descriptor");
        assertEquals("fixture-under-test", refused.pluginId());
    }

    @Test
    void a_process_that_exits_before_speaking_is_refused() {
        long before = System.nanoTime();

        CompanionLaunchException refused = assertThrows(CompanionLaunchException.class,
                () -> launch(CompanionFixture.CRASH));

        // Named by its exit code rather than reported as a hang, and reported at once rather than after
        // the timeout: the handshake is raced against the process exiting precisely so that a plugin whose
        // author has a stack trace in front of them is not told "did not answer".
        assertTrue(refused.getMessage().contains("exited (3)"), refused.getMessage());
        long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
        assertTrue(elapsedMillis < CompanionDescriptor.DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
                "the host waited " + elapsedMillis + " ms for a process that had already exited");
    }

    @Test
    void an_anonymous_plugin_is_refused() {
        CompanionLaunchException refused =
                assertThrows(CompanionLaunchException.class, () -> launch(CompanionFixture.ANONYMOUS));

        assertTrue(refused.getMessage().contains("no id"), refused.getMessage());
    }

    @Test
    void a_plugin_that_keeps_dying_is_restarted_once_and_then_reported() throws Exception {
        ProcessPlugin plugin = launch(CompanionFixture.DIE_AFTER);

        await("the plugin to give up", () -> !plugin.isAlive());
        await("both deaths to be reported", () -> {
            synchronized (reported) {
                return reported.stream().anyMatch(line -> line.contains("restarting it once"))
                        && reported.stream().anyMatch(line -> line.contains("a second time"));
            }
        });
        assertTrue(plugin.toolbarItems().isEmpty(), "a plugin that has given up contributes nothing");
        // Twice, not more: the restart is a single retry rather than a loop.
        assertEquals(1, count("restarting it once"));
    }

    @Test
    void closing_a_project_tells_the_plugin_and_then_ends_the_process() throws Exception {
        ProcessPlugin plugin = launch(CompanionFixture.OK);

        plugin.projectClosing();

        assertFalse(plugin.isAlive(), "the process is spawned per bind, so a project close ends it");
        await("the plugin to have been told first", () -> events().contains("projectClosing"));
    }

    @Test
    void closing_twice_is_harmless() throws Exception {
        ProcessPlugin plugin = launch(CompanionFixture.OK);

        plugin.close();
        plugin.close();

        assertFalse(plugin.isAlive());
    }

    @Test
    void a_descriptor_with_no_command_is_refused_without_starting_anything() {
        CompanionDescriptor empty =
                new CompanionDescriptor("nothing", "", List.of(), work, 1000, work);

        CompanionLaunchException refused = assertThrows(CompanionLaunchException.class,
                () -> ProcessPlugin.launch(empty, new StudioPeer() {}, hostInfo(), this::report));

        assertTrue(refused.getMessage().contains("no id or no command"), refused.getMessage());
    }

    // -----------------------------------------------------------------------------------------------

    private ProcessPlugin launch(String mode) throws CompanionLaunchException {
        return launch(mode, new StudioPeer() {}, CompanionDescriptor.DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    private ProcessPlugin launch(String mode, StudioPeer host, long timeoutMillis)
            throws CompanionLaunchException {
        ProcessPlugin plugin = ProcessPlugin.launch(descriptor(mode, timeoutMillis), host, hostInfo(),
                this::report);
        started.add(plugin);
        return plugin;
    }

    /**
     * A descriptor running this very test's classpath in a second JVM.
     *
     * <p>{@code java.class.path} is what surefire booted this JVM with — often a manifest-only jar, whose
     * {@code Class-Path} the child honours exactly as this one did.
     */
    private CompanionDescriptor descriptor(String mode, long timeoutMillis) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"),
                CompanionFixture.class.getName(), mode, marker.toString());
        return new CompanionDescriptor("fixture-under-test", "", command, work, timeoutMillis, work);
    }

    private static HostInfo hostInfo() {
        return HostInfo.of("BotMaker Studio", "test", "/tmp/project", "/tmp/project/resources");
    }

    private void report(String line) {
        synchronized (reported) {
            reported.add(line);
        }
    }

    private long count(String fragment) {
        synchronized (reported) {
            return reported.stream().filter(line -> line.contains(fragment)).count();
        }
    }

    private List<String> events() {
        try {
            return Files.readAllLines(marker);
        } catch (IOException notYet) {
            return List.of();
        }
    }

    /** Polls rather than sleeps: these are two processes and the handover time is nobody's to predict. */
    private static void await(String what, BooleanSupplier until) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PATIENCE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (until.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }
}
