# botmaker-plugin-host

Loading a **BotMaker Studio plugin** out of one project's own resolved jars.

`botmaker-studio-api` says what a plugin contributes. `botmaker-plugin-toolkit` is what a plugin compiles
against. This is the other side: what a **host** uses to get a plugin onto a classloader in the first place
— Studio, the `botmaker` CLI's `validate` and `run`, and the plugin registry's CI.

```xml
<dependency>
    <groupId>com.github.LiQiyeDev</groupId>
    <artifactId>botmaker-plugin-host</artifactId>
    <version>v0.1.0</version>
</dependency>
```

`botmaker-studio-api` is `provided` here — declare it yourself. That is not tidiness: a contract class must
be the **same `Class` object** on both sides of the plugin boundary, and a transitive second copy is the one
thing that can make that false.

## Using it

```java
try (PluginLoader loaded = PluginLoader.open(project.resolvedClasspath())) {
    if (loaded == null) return bundledPlugins();   // nothing to load, or nothing loadable
    for (StudioPlugin plugin : loaded.plugins()) {
        …
    }
}
```

Two things to know before you write the second line:

- **`open` returns `null`, not an empty loader.** An unresolvable pin, a jar with no services file and a
  plugin whose constructor throws are all the same answer, because the caller's fallback is the same in all
  three: the bundled plugin set. A project must open with the bundled menus rather than with none.
- **`close()` is required.** An open `URLClassLoader` holds every jar it read, and on Windows a held jar
  cannot be replaced — a project left unclosed makes the next dependency resolve fail on a file lock.

## Plugins in another process

A companion plugin can also run outside the JVM entirely, in any language, speaking
[`botmaker-plugin-protocol`](https://github.com/LiQiyeDev/botmaker-plugin-protocol) over its own stdin and
stdout. It arrives at the same `CompanionPlugin` interface, so a host that draws a toolbar cannot tell the
two apart:

```java
for (CompanionDescriptor declared : CompanionDescriptor.discover(project.resolvedClasspath())) {
    try {
        companions.add(ProcessPlugin.launch(declared, new ServicesPeer(services, Platform::runLater),
                HostInfo.of("BotMaker Studio", version, projectDir, resourcesDir), statusBar::say));
    } catch (CompanionLaunchException broken) {
        statusBar.say(broken.getMessage());   // and carry on: the project still opens
    }
}
```

- **A plugin declares itself** with a `META-INF/botmaker/companion.json` on the project's classpath, naming
  the command and its working directory. So it is installed, resolved and removed as an ordinary Maven
  dependency, with no second install path.
- **`CompanionLaunchException` is checked**, and that is the design. A process that will not start or will
  not answer must become a line in a status bar, never a project that hangs on opening.
- **A plugin that dies is restarted once**, and a second death is reported with its own last stderr lines.
- **`close()` — or `projectClosing()` — ends the process.** A shutdown hook reaps it if the JVM exits first.

## Why it is a module and not eighty lines you copy

Delegation is inverted: **parent-first** for `com.botmaker.plugin.api.**` and the platform namespaces,
**child-first** for everything else. That split is the whole point — the pinned SDK is not the bundled SDK,
so a plain parent-first `URLClassLoader` would resolve the plugin from the host's own compile dependency and
answer with the wrong palette.

It is also the last code in this project that should exist in two copies. A wrong answer there does not
throw where it happens; it throws much later as a `ClassCastException` between two classes with identical
names.

## Building

```bash
mvn test        # 47 — the delegation split, the nulls, a real ServiceLoader round trip, and a real second
                #      process that misbehaves on request
mvn install     # com.github.LiQiyeDev:botmaker-plugin-host:0.0.0-SNAPSHOT
```

Published through JitPack, which serves each git tag under `com.github.LiQiyeDev` regardless of this pom's
`groupId`/`version`. Releases are cut from the umbrella with `../release.sh --plugin-host <version>`.
