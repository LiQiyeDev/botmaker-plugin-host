package com.botmaker.plugin.host;

import com.botmaker.plugin.api.StudioPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a host is entitled to assume of the loader.
 *
 * <p>The two halves worth holding are the ones with no visible symptom when they are wrong: the
 * parent-first rule, whose failure surfaces much later as a {@link ClassCastException} between two
 * identically named classes, and <em>nothing loadable answers null</em>, whose failure is a project opening
 * with no menus at all rather than the bundled ones.
 */
class PluginLoaderTest {

    /** Named by the services file written in {@link #a_services_file_on_the_classpath_is_loaded}. */
    public static final class Stub implements StudioPlugin {
        @Override
        public String id() {
            return "test.stub";
        }
    }

    // ---- the delegation split ----

    @Test
    void the_contract_is_answered_by_the_parent() {
        assertTrue(PluginLoader.parentFirst("com.botmaker.plugin.api.StudioPlugin"));
        assertTrue(PluginLoader.parentFirst("com.botmaker.plugin.api.value.ValueType"));
    }

    @Test
    void the_platform_namespaces_are_answered_by_the_parent() {
        for (String name : List.of("java.util.List", "javax.swing.JFrame", "javafx.scene.Node",
                "jdk.internal.misc.Unsafe", "sun.misc.Unsafe")) {
            assertTrue(PluginLoader.parentFirst(name), name);
        }
    }

    @Test
    void the_sdk_is_answered_by_the_project_first() {
        // The whole reason the split exists: a bot's palette must come from the SDK IT pins, not from the
        // one the host was compiled against.
        assertFalse(PluginLoader.parentFirst("com.botmaker.sdk.plugin.SdkPlugin"));
        assertFalse(PluginLoader.parentFirst("com.botmaker.sdk.api.interaction.Mouse"));
    }

    @Test
    void a_plugin_package_that_merely_starts_like_the_contract_is_not_parent_first() {
        // The prefixes end in a dot on purpose. Without it com.botmaker.plugin.apix.* — or a third-party
        // javafxsupport.* — would be silently taken from the host.
        assertFalse(PluginLoader.parentFirst("com.botmaker.plugin.apix.Thing"));
        assertFalse(PluginLoader.parentFirst("com.botmaker.plugin.host.PluginLoader"));
        assertFalse(PluginLoader.parentFirst("javafxsupport.Thing"));
    }

    // ---- nothing loadable answers null ----

    @Test
    void no_classpath_is_null() {
        assertNull(PluginLoader.open(null));
        assertNull(PluginLoader.open(List.of()));
    }

    @Test
    void a_classpath_of_nothing_but_blanks_is_null() {
        assertNull(PluginLoader.open(List.of("", "   ")));
    }

    @Test
    void a_classpath_with_no_services_file_is_null(@TempDir Path dir) {
        // The ordinary case for a project that pins a library which is not a plugin.
        assertNull(PluginLoader.open(List.of(dir.toString())));
    }

    // ---- the round trip ----

    @Test
    void a_services_file_on_the_classpath_is_loaded(@TempDir Path dir) throws IOException {
        // The class itself is on the parent, and the child directory has no copy — which is exactly the
        // fallback arm of the child-first branch, so this exercises the split rather than working around
        // it. What is being asserted is the ServiceLoader pass: a declaration on the project's own
        // classpath produces an instance typed as the contract.
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("com.botmaker.plugin.api.StudioPlugin"),
                Stub.class.getName() + "\n");

        try (PluginLoader loaded = PluginLoader.open(List.of(dir.toString()))) {
            assertNotNull(loaded);
            assertEquals(1, loaded.plugins().size());
            assertEquals("test.stub", loaded.plugins().get(0).id());
        }
    }

    @Test
    void a_plugin_whose_own_dependency_is_missing_answers_null(@TempDir Path dir) throws IOException {
        // Found on 2026-08-28 by loading the archetype's skeleton with the toolkit left off the classpath.
        // ServiceLoader's Class.forName throws NoClassDefFoundError — an Error, not a RuntimeException — so
        // before the LinkageError arm it left `open` and aborted the host's project-open.
        //
        // Built rather than described: a plugin is compiled against a helper, the helper's .class is then
        // deleted, which is exactly the state a jar resolved without its dependency is in.
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "no javac in this JRE");

        Path src = Files.createDirectories(dir.resolve("src/p"));
        // The helper is a SUPERCLASS, which is what the real case looks like: the archetype's skeleton
        // extends the toolkit's AbstractStudioPlugin. A missing class named only inside a method body would
        // not reproduce this — the JVM resolves those lazily, so the plugin would load and fail later.
        Files.writeString(src.resolve("Helper.java"),
                "package p; public abstract class Helper { public String name() { return \"broken\"; } }");
        Files.writeString(src.resolve("BrokenPlugin.java"),
                "package p; public final class BrokenPlugin extends Helper"
                        + " implements com.botmaker.plugin.api.StudioPlugin {"
                        + " @Override public String id() { return name(); } }");

        Path classes = Files.createDirectories(dir.resolve("classes"));
        String contract = StudioPlugin.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        int status = javac.run(null, null, null,
                "-cp", contract, "-d", classes.toString(),
                src.resolve("Helper.java").toString(), src.resolve("BrokenPlugin.java").toString());
        assumeTrue(status == 0, "could not compile the fixture");

        Files.delete(classes.resolve("p/Helper.class"));
        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(services.resolve("com.botmaker.plugin.api.StudioPlugin"), "p.BrokenPlugin\n");

        assertNull(PluginLoader.open(List.of(classes.toString())));
    }

    @Test
    void a_missing_classpath_entry_does_not_lose_the_rest(@TempDir Path dir) throws IOException {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("com.botmaker.plugin.api.StudioPlugin"),
                Stub.class.getName() + "\n");

        try (PluginLoader loaded = PluginLoader.open(
                List.of("", dir.resolve("gone.jar").toString(), dir.toString()))) {
            assertNotNull(loaded);
            assertEquals(1, loaded.plugins().size());
        }
    }
}
