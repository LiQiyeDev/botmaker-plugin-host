# Changelog

All notable changes to `botmaker-plugin-host`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

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
- **`PluginLoaderTest`** (9) — the split, including the case the dot-terminated prefixes exist for
  (`com.botmaker.plugin.apix.*` must **not** be parent-first); the three ways a classpath answers `null`;
  and a real `ServiceLoader` round trip through the child-first fallback arm.

### Deliberately absent

- **Any dependency but the contract**, which is `provided`. No SDK, no Studio, no JavaFX, no
  `botmaker-shared` — a host that is a command-line tool must be able to load a plugin without downloading
  OpenCV and JNA.
- **A plugin cache, a reload watcher, or a sandbox.** A plugin runs arbitrary code in the host's process;
  this module loads it and says so plainly. Anything that implied otherwise would be a security claim
  nothing here can keep.
