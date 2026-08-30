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
mvn test        # PluginLoaderTest (9) — the split, the nulls, and a real ServiceLoader round trip
mvn install     # com.github.LiQiyeDev:botmaker-plugin-host:0.0.0-SNAPSHOT
```

Published through JitPack, which serves each git tag under `com.github.LiQiyeDev` regardless of this pom's
`groupId`/`version`. Releases are cut from the umbrella with `../release.sh --plugin-host <version>`.
