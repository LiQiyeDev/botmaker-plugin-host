package com.botmaker.plugin.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding a companion plugin on a project's classpath.
 *
 * <p>The theme running through these: <b>a descriptor that is wrong is one plugin missing, never a
 * classpath the host cannot read.</b> That is the same rule {@link PluginLoader} applies to a plugin whose
 * constructor throws, and it is what keeps a hand-written file from being able to stop a project opening.
 */
class CompanionDescriptorTest {

    @TempDir
    Path work;

    @Test
    void a_descriptor_in_a_directory_entry_is_found() throws IOException {
        Path entry = directoryEntry("""
                {
                  "id": "botmaker-pilot",
                  "displayName": "Remote Pilot",
                  "command": ["node", "host/index.js"],
                  "workingDirectory": "pilot",
                  "handshakeTimeoutMillis": 30000
                }
                """);

        List<CompanionDescriptor> found = CompanionDescriptor.discover(List.of(entry.toString()));

        assertEquals(1, found.size());
        CompanionDescriptor pilot = found.getFirst();
        assertEquals("botmaker-pilot", pilot.id());
        assertEquals("Remote Pilot", pilot.displayName());
        assertEquals(List.of("node", "host/index.js"), pilot.command());
        assertEquals(entry.resolve("pilot"), pilot.workingDirectory());
        assertEquals(30000L, pilot.handshakeTimeoutMillis());
    }

    @Test
    void a_descriptor_in_a_jar_resolves_its_directory_against_the_jars_own() throws IOException {
        Path jar = jarEntry("""
                { "id": "packed", "command": ["run.sh"], "workingDirectory": "tools" }
                """);

        List<CompanionDescriptor> found = CompanionDescriptor.discover(List.of(jar.toString()));

        assertEquals(1, found.size());
        // A process cannot be started inside a jar, so the only base the host can offer is where it sits.
        assertEquals(jar.getParent().resolve("tools"), found.getFirst().workingDirectory());
    }

    @Test
    void a_descriptor_with_no_working_directory_starts_where_it_was_found() throws IOException {
        Path entry = directoryEntry("""
                { "id": "here", "command": ["run.sh"] }
                """);

        CompanionDescriptor descriptor =
                CompanionDescriptor.discover(List.of(entry.toString())).getFirst();

        assertEquals(entry, descriptor.workingDirectory());
        assertEquals("here", descriptor.displayName(), "displayName falls back to the id");
        assertEquals(CompanionDescriptor.DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
                descriptor.handshakeTimeoutMillis());
    }

    @Test
    void a_classpath_with_no_companion_on_it_is_the_ordinary_case() throws IOException {
        Path plain = Files.createDirectory(work.resolve("plain"));

        assertTrue(CompanionDescriptor.discover(List.of(plain.toString())).isEmpty());
        assertTrue(CompanionDescriptor.discover(List.of()).isEmpty());
        assertTrue(CompanionDescriptor.discover(null).isEmpty());
        assertTrue(CompanionDescriptor.discover(List.of(work.resolve("missing.jar").toString())).isEmpty());
    }

    @Test
    void a_descriptor_that_is_not_json_costs_only_itself() throws IOException {
        Path broken = directoryEntry("this is not json at all", "broken");
        Path good = directoryEntry("""
                { "id": "good", "command": ["run.sh"] }
                """, "good");

        List<CompanionDescriptor> found =
                CompanionDescriptor.discover(List.of(broken.toString(), good.toString()));

        assertEquals(List.of("good"), found.stream().map(CompanionDescriptor::id).toList());
    }

    @Test
    void a_descriptor_naming_no_command_or_no_id_is_dropped() throws IOException {
        Path noCommand = directoryEntry("""
                { "id": "idle" }
                """, "no-command");
        Path noId = directoryEntry("""
                { "command": ["run.sh"] }
                """, "no-id");

        assertTrue(CompanionDescriptor.discover(List.of(noCommand.toString(), noId.toString())).isEmpty());
    }

    @Test
    void a_field_of_the_wrong_type_degrades_rather_than_failing() throws IOException {
        // A command written as a string is the mistake an author makes once. It is not an array, so there
        // is no command, so the descriptor is unusable — which is a named refusal rather than an exception.
        Path entry = directoryEntry("""
                { "id": "wrong", "command": "run.sh", "handshakeTimeoutMillis": "soon" }
                """);

        assertTrue(CompanionDescriptor.discover(List.of(entry.toString())).isEmpty());
    }

    @Test
    void descriptors_arrive_in_classpath_order() throws IOException {
        Path first = directoryEntry("""
                { "id": "first", "command": ["a"] }
                """, "one");
        Path second = directoryEntry("""
                { "id": "second", "command": ["b"] }
                """, "two");

        List<String> ids = CompanionDescriptor.discover(List.of(first.toString(), second.toString()))
                .stream().map(CompanionDescriptor::id).toList();

        assertEquals(List.of("first", "second"), ids);
    }

    private Path directoryEntry(String json) throws IOException {
        return directoryEntry(json, "classes");
    }

    private Path directoryEntry(String json, String name) throws IOException {
        Path entry = Files.createDirectories(work.resolve(name));
        Path file = entry.resolve(CompanionDescriptor.RESOURCE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return entry;
    }

    private Path jarEntry(String json) throws IOException {
        Path lib = Files.createDirectories(work.resolve("lib"));
        Path jar = lib.resolve("companion.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(CompanionDescriptor.RESOURCE));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
}
