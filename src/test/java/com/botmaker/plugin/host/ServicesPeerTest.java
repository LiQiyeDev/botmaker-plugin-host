package com.botmaker.plugin.host;

import com.botmaker.plugin.api.Capture;
import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.Region;
import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.Theme;
import com.botmaker.plugin.api.ThemeTokens;
import com.botmaker.plugin.protocol.Capability;
import com.botmaker.plugin.protocol.WireColor;
import com.botmaker.plugin.protocol.WireRegion;

import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Window;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host's own services, seen from the other side of the wire.
 *
 * <p>Everything here runs the "UI thread" inline, which is what a test wants and what no real host does.
 * What that leaves untested on purpose is the cancellation timeout: it is ten minutes long precisely so it
 * never fires during a user's interaction, and a test that waited for it would be a test that took ten
 * minutes. See {@link ServicesPeer}'s class javadoc for why the timeout exists at all and why it is a
 * mitigation rather than a fix.
 */
class ServicesPeerTest {

    @Test
    void a_host_that_runs_no_bots_does_not_claim_run_control() {
        Set<String> offered = ServicesPeer.capabilitiesOf(new StubServices());

        assertTrue(offered.contains(Capability.CAPTURE));
        assertTrue(offered.contains(Capability.DIALOGS));
        assertTrue(offered.contains(Capability.THEME));
        assertTrue(offered.contains(Capability.STATUS));
        assertFalse(offered.contains(Capability.RUNS),
                "Runs.NONE is a real answer, and a plugin must not draw a Start button for it");
    }

    @Test
    void a_host_that_does_run_bots_says_so() {
        StubServices services = new StubServices();
        services.runs = new StubRuns();

        assertTrue(ServicesPeer.capabilitiesOf(services).contains(Capability.RUNS));
    }

    @Test
    void an_overlay_answers_with_what_the_user_chose() throws Exception {
        StubServices services = new StubServices();
        services.region = new Region(4, 8, 16, 32);

        WireRegion chosen = peer(services).selectRegion().get();

        assertEquals(WireRegion.of(4, 8, 16, 32), chosen);
    }

    @Test
    void a_sampled_colour_crosses_as_channels() throws Exception {
        StubServices services = new StubServices();
        services.color = Color.rgb(12, 34, 56);

        assertEquals(WireColor.of(12, 34, 56), peer(services).sampleColor().get());
    }

    @Test
    void a_host_whose_overlay_throws_answers_cancelled_rather_than_never() throws Exception {
        StubServices services = new StubServices();
        services.captureThrows = true;

        assertTrue(peer(services).selectRegion().get().cancelled());
    }

    @Test
    void a_chooser_answers_with_a_path_or_with_nothing() throws Exception {
        StubServices services = new StubServices();
        services.chosen = Path.of("/tmp", "picture.png");

        assertEquals(Path.of("/tmp", "picture.png").toString(),
                peer(services).chooseFile("Pick", "", List.of("png")).get());

        services.chosen = null;
        assertEquals("", peer(services).chooseDirectory("Pick", "").get(),
                "a dismissed dialog is an empty answer, not an unanswered request");
    }

    @Test
    void run_control_and_status_reach_the_host() throws Exception {
        StubServices services = new StubServices();
        StubRuns runs = new StubRuns();
        services.runs = runs;
        ServicesPeer peer = peer(services);

        peer.runStart().get();
        assertTrue(peer.isRunning().get());
        peer.runStop().get();
        assertFalse(peer.isRunning().get());

        peer.status("half way");
        assertEquals(List.of("half way"), services.said);
    }

    @Test
    void a_status_line_that_throws_is_not_a_failed_request() {
        StubServices services = new StubServices();
        services.statusThrows = true;

        peer(services).status("anything");   // must not throw
    }

    @Test
    void the_theme_crosses_as_data() throws ExecutionException, InterruptedException {
        StubServices services = new StubServices();

        assertEquals("#FFFFFF", peer(services).themeTokens().get().background());
    }

    private static ServicesPeer peer(StudioServices services) {
        // Inline, so a test is one thread. A JavaFX host passes Platform::runLater here.
        return new ServicesPeer(services, Runnable::run);
    }

    /** A host with no window, no toolkit and no bot — which is exactly what the CLI is. */
    private static final class StubServices implements StudioServices {

        final List<String> said = new ArrayList<>();
        Runs runs = Runs.NONE;
        Region region;
        Color color;
        Path chosen;
        boolean captureThrows;
        boolean statusThrows;

        @Override
        public Path projectDir() {
            return Path.of(".");
        }

        @Override
        public Path resourcesDir() {
            return Path.of(".");
        }

        @Override
        public Theme theme() {
            throw new UnsupportedOperationException("a companion plugin has no Scene to style");
        }

        @Override
        public ThemeTokens themeTokens() {
            return ThemeTokens.DEFAULT;
        }

        @Override
        public Capture capture() {
            return new Capture() {
                @Override
                public void selectRegion(Consumer<Region> onSelected) {
                    if (captureThrows) throw new IllegalStateException("no screen");
                    // A null region is a cancel, which is how the in-process contract has no cancel at all:
                    // it simply never calls back. Answering null here is the closest a stub can get.
                    if (region != null) onSelected.accept(region);
                }

                @Override
                public void pickPoint(Consumer<Region> onPicked) {
                    selectRegion(onPicked);
                }

                @Override
                public void sampleColor(Consumer<Color> onSampled) {
                    if (color != null) onSampled.accept(color);
                }

                @Override
                public void grabFrame(Consumer<Image> onGrabbed) {
                    onGrabbed.accept(null);
                }

                @Override
                public Image toFxImage(BufferedImage image) {
                    return null;
                }
            };
        }

        @Override
        public Dialogs dialogs() {
            return new Dialogs() {
                @Override
                public Window owner() {
                    return null;
                }

                @Override
                public Choice chooseProgram(Path initialDir) {
                    return choice();
                }

                @Override
                public Choice chooseFile(String title, Path initialDir, String... extensions) {
                    return choice();
                }

                @Override
                public Choice chooseDirectory(String title, Path initialDir) {
                    return choice();
                }

                private Choice choice() {
                    return new Choice(true, Optional.ofNullable(chosen));
                }
            };
        }

        @Override
        public Runs runs() {
            return runs;
        }

        @Override
        public void status(String message) {
            if (statusThrows) throw new IllegalStateException("no status bar");
            said.add(message);
        }
    }

    private static final class StubRuns implements Runs {

        private boolean running;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public OptionalLong pid() {
            return OptionalLong.empty();
        }

        @Override
        public AutoCloseable onStateChanged(Consumer<Boolean> listener) {
            return () -> {
            };
        }

        @Override
        public AutoCloseable onTelemetry(Consumer<byte[]> listener) {
            return () -> {
            };
        }
    }
}
