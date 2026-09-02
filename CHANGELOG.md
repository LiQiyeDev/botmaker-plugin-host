# Changelog

All notable changes to `botmaker-plugin-host`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [0.0.2] — 2026-09-02

### Changed

- **Compiled for Java 25 (LTS).** A host embedding the loader needs a 25 runtime. The delegation split and
  every prefix in it are unchanged.

## [0.0.1] — 2026-09-02

First release. `0.x` because the contract whose namespace it delegates parent-first is still `0.x`.

### Added

- **The module** — the ninth BotMaker repository, and the third a plugin platform needs: the contract says
  what a plugin contributes, the toolkit is what a plugin compiles against, and this is what a **host** uses
  to load one. It exists because Studio stopped being the only host — the CLI's `validate` and `run`, and
  the plugin registry's CI, all have to load a plugin exactly as Studio does.
- **`PluginLoader`** — moved out of `botmaker-studio` unchanged. Opens one project's resolved classpath,
  runs the `ServiceLoader` pass over it and hands back the `StudioPlugin`s found there, or `null` when there
  is nothing to load or nothing loadable. `Closeable`, and closing is required rather than housekeeping: an
  open `URLClassLoader` holds every jar it read, and on Windows a held jar cannot be replaced.
- **Inverted delegation** — parent-first for `com.botmaker.plugin.api.**` and the platform namespaces,
  child-first for everything else. Parent-first for the contract because a contract class must be the *same*
  `Class` object on both sides of the boundary; child-first for `com.botmaker.sdk.**` because a bot's
  palette must come from the SDK **it** pins and not from the one the host was compiled against.
- **Fail-open covers a plugin whose own dependency is missing.** `open` catches `LinkageError` as well as
  `ServiceConfigurationError` and `RuntimeException`. A plugin jar resolved without one of its own
  dependencies — the toolkit absent from a project's classpath is the ordinary way — fails inside
  `ServiceLoader`'s `Class.forName` as a `NoClassDefFoundError`, which is an `Error` and escaped: it aborted
  whatever the host was doing, which is opening a project. Deliberately not `Error`: a broken plugin must
  not make an `OutOfMemoryError` look like a missing services file. Found on 2026-08-28 by loading the
  plugin archetype's own skeleton.
- **`PluginLoaderTest`** (10) — the split, including the case the dot-terminated prefixes exist for
  (`com.botmaker.plugin.apix.*` must **not** be parent-first); the four ways a classpath answers `null`,
  including a plugin whose superclass is missing — built by compiling one against a helper and then deleting
  the helper's `.class`, which is exactly the state a jar resolved without its dependency is in; and a real
  `ServiceLoader` round trip through the child-first fallback arm.

### Deliberately absent

- **Any dependency but the contract**, which is `provided`. No SDK, no Studio, no JavaFX, no
  `botmaker-shared` — a host that is a command-line tool must be able to load a plugin without downloading
  OpenCV and JNA.
- **A plugin cache, a reload watcher, or a sandbox.** A plugin runs arbitrary code in the host's process;
  this module loads it and says so plainly. Anything that implied otherwise would be a security claim
  nothing here can keep.
