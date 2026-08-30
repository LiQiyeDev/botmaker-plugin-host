package com.botmaker.plugin.host;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * How a host finds a companion plugin that runs in <em>another process</em>: one
 * {@code META-INF/botmaker/companion.json} on the project's own resolved classpath.
 *
 * <h2>Why the classpath and not somewhere better suited</h2>
 *
 * <p>Because there must not be a second install path. A plugin is installed as an ordinary Maven dependency
 * through the host's library service, the registry's gate resolves it as a coordinate, and a project pins
 * what it pins — all of which is already true and none of which should have to learn about a new kind of
 * plugin. A descriptor on that same classpath inherits every one of those properties for free; a registry
 * of processes kept anywhere else would inherit none of them and would immediately need its own answer to
 * "which project is this one for".
 *
 * <p>It is also what keeps <em>uninstalling</em> honest: remove the dependency and the process is gone,
 * because nothing anywhere else remembers it.
 *
 * <h2>The file</h2>
 *
 * <pre>{@code
 * {
 *   "id": "botmaker-pilot",
 *   "displayName": "Remote Pilot",
 *   "command": ["node", "host/index.js"],
 *   "workingDirectory": "pilot",
 *   "handshakeTimeoutMillis": 15000
 * }
 * }</pre>
 *
 * <p>{@code command} is an <b>array, never a string</b>, so nothing has to guess where one argument ends
 * and the next begins — a path with a space in it is the ordinary case on two of the three platforms this
 * runs on, and quoting rules that differ per platform are how a launcher becomes unportable.
 *
 * <p>{@code workingDirectory} is resolved <b>against whatever classpath entry carried this file</b>: the
 * directory itself when the entry is a directory, its parent when the entry is a jar. That is the only base
 * the host can know, and it is the reason a plugin shipping its own tree is a plugin the user installed as
 * a directory or a plugin that unpacks itself.
 *
 * <p><b>What is deliberately not answered here: a plugin's own files, packed inside its jar.</b> A jar is
 * not a directory and a process cannot be started inside one, so a companion shipping a Node tree either
 * unpacks it itself on first run or names a command that is already installed. That is the same question
 * the asset-bundle module would have answered and it stays unanswered until a second plugin needs it —
 * see the umbrella's plan notes. It is called out rather than hidden because it is the first thing a plugin
 * author hits.
 *
 * @param id                     the plugin's stable identifier, shared with {@code CompanionPlugin.id()}.
 *                               The handshake's id wins if the two disagree; this one names the plugin in a
 *                               diagnostic for a process that never got as far as handshaking
 * @param displayName            the name a user reads, or {@code ""} to fall back to {@code id}
 * @param command                the process and its arguments; empty makes the descriptor unusable
 * @param workingDirectory       an absolute directory to start it in, already resolved
 * @param handshakeTimeoutMillis how long the host waits for {@code initialize} before giving up
 * @param source                 the classpath entry this was read from, for diagnostics
 */
public record CompanionDescriptor(
        String id,
        String displayName,
        List<String> command,
        Path workingDirectory,
        long handshakeTimeoutMillis,
        Path source) {

    /** Where a companion plugin declares itself, on its own classpath entry. */
    public static final String RESOURCE = "META-INF/botmaker/companion.json";

    /**
     * How long a process gets to answer {@code initialize} when it does not say.
     *
     * <p>Generous on purpose. The number that matters is not how long a healthy plugin takes but how long a
     * user waits for a broken one, and a cold Node or Python start on a laptop that is also resolving Maven
     * is routinely seconds. A timeout tight enough to be tidy is a timeout that drops working plugins on
     * slow machines, which is the failure nobody can reproduce.
     */
    public static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 15_000L;

    public CompanionDescriptor {
        id = id == null ? "" : id.trim();
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        command = command == null ? List.of() : List.copyOf(command);
        handshakeTimeoutMillis = handshakeTimeoutMillis > 0
                ? handshakeTimeoutMillis
                : DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
    }

    /**
     * Whether this is worth trying to launch at all.
     *
     * <p>Two fields and no more: a plugin with no id cannot be named in the diagnostic it is about to earn,
     * and one with no command is not a process. Everything else has an honest empty value.
     */
    public boolean isUsable() {
        return !id.isEmpty() && !command.isEmpty();
    }

    /**
     * Every companion declared on {@code classpath}, in classpath order.
     *
     * <p>Reads the jars and directories itself rather than going through a {@link ClassLoader}, for two
     * reasons that are both about being exact. A classloader would also answer from the <em>host's</em> own
     * classpath, and a host that bundles a companion would then find it once per project it opens; and the
     * classpath entry is not incidental here — it is the base {@code workingDirectory} is resolved against,
     * and a {@code URL} from {@code getResources} makes that a string-surgery problem.
     *
     * <p>Never throws. A jar that will not open, a file that is not JSON and a descriptor missing its id are
     * all the same kind of event — one plugin is not there — and none of them may cost the rest of the
     * classpath, which is the rule {@link PluginLoader} already follows for a plugin that will not load.
     */
    public static List<CompanionDescriptor> discover(List<String> classpath) {
        if (classpath == null || classpath.isEmpty()) return List.of();
        List<CompanionDescriptor> found = new ArrayList<>();
        for (String entry : classpath) {
            if (entry == null || entry.isBlank()) continue;
            CompanionDescriptor descriptor = readFrom(new File(entry).toPath().toAbsolutePath());
            if (descriptor != null) found.add(descriptor);
        }
        return List.copyOf(found);
    }

    private static CompanionDescriptor readFrom(Path entry) {
        try {
            if (Files.isDirectory(entry)) {
                Path file = entry.resolve(RESOURCE);
                if (!Files.isRegularFile(file)) return null;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    return parse(reader, entry, entry);
                }
            }
            if (!Files.isRegularFile(entry)) return null;
            try (ZipFile jar = new ZipFile(entry.toFile())) {
                ZipEntry declared = jar.getEntry(RESOURCE);
                if (declared == null) return null;
                try (InputStream in = jar.getInputStream(declared);
                     Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    // The base for workingDirectory is the directory the jar sits in — the only place a
                    // process could plausibly be started from, since a jar is not one.
                    return parse(reader, parentOf(entry), entry);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("Warning: ignoring " + RESOURCE + " in " + entry + ": " + unreadable);
            return null;
        }
    }

    /**
     * Reads one descriptor, field by field rather than by binding the record.
     *
     * <p>Hand-read on purpose: a reflective bind turns a file with one wrong type into an exception that
     * costs the whole descriptor, and this file is written by hand by somebody who is not looking at this
     * class. Every field here degrades to its empty value, and only {@link #isUsable()} refuses.
     */
    private static CompanionDescriptor parse(Reader reader, Path base, Path source) {
        JsonElement root = JsonParser.parseReader(reader);
        if (root == null || !root.isJsonObject()) return null;
        JsonObject json = root.getAsJsonObject();

        String id = string(json, "id");
        String displayName = string(json, "displayName");
        List<String> command = strings(json, "command");
        String workingDirectory = string(json, "workingDirectory");
        long timeout = number(json, "handshakeTimeoutMillis");

        Path directory = workingDirectory.isBlank() ? base : base.resolve(workingDirectory).normalize();
        CompanionDescriptor descriptor =
                new CompanionDescriptor(id, displayName, command, directory, timeout, source);
        if (!descriptor.isUsable()) {
            System.err.println("Warning: " + RESOURCE + " in " + source
                    + " declares no id or no command, so nothing can be launched from it.");
            return null;
        }
        return descriptor;
    }

    private static String string(JsonObject json, String member) {
        JsonElement value = json.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static long number(JsonObject json, String member) {
        JsonElement value = json.get(member);
        if (value == null || !value.isJsonPrimitive()) return 0L;
        try {
            return value.getAsLong();
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    private static List<String> strings(JsonObject json, String member) {
        JsonElement value = json.get(member);
        if (value == null || !value.isJsonArray()) return List.of();
        List<String> items = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element != null && element.isJsonPrimitive()) items.add(element.getAsString());
        }
        return items;
    }

    private static Path parentOf(Path jar) {
        Path parent = jar.getParent();
        return parent != null ? parent : jar.toAbsolutePath().getRoot();
    }
}
