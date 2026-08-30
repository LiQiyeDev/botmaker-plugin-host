# Changelog

All notable changes to `botmaker-plugin-host`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this module uses
[semantic versioning](https://semver.org/). `release.sh` refuses to cut a version with no section here.

## [Unreleased]

### Added

- **`ProcessPlugin`** — a companion plugin running in **another process**, made to look like an ordinary
  `CompanionPlugin`. This is what the two-interface split was for: `CompanionPlugin` was drawn so every
  member of it is expressible as data, `botmaker-plugin-protocol` is that data on a wire, and this is where
  they meet — a host asking a plugin for its toolbar items cannot tell whether the answer came from a
  `ServiceLoader` or from a Node process. **One plugin interface, two transports**, and only this module
  knows there is a second one.
- **The promise, and what enforces it: a broken companion plugin cannot stop a project from opening.**
  `launch` is the only blocking call and is bounded by the descriptor's timeout; it throws
  `CompanionLaunchException`, which is **checked** so a host cannot forget to decide what to do. A plugin
  that dies later is supervised through `Process.onExit()`, restarted **once**, and a second death is
  reported with the child's last stderr lines. Once rather than forever: a restart loop is how a broken
  plugin becomes a fan spinning up with nothing on screen to explain it.
- **The handshake is raced against `Process.onExit()`.** LSP4J does not fail a pending request when the
  stream underneath it closes, so a plugin that crashes mid-handshake would otherwise cost the full timeout
  and be reported as "did not answer" — sending its author to look for a hang while they have an exit code
  in front of them. It is reported at once as "exited (3) before answering the handshake".
- **`CompanionDescriptor`** — `META-INF/botmaker/companion.json` on the project's own resolved classpath,
  naming the command and its working directory. The classpath and not a registry somewhere else, so there is
  no second install path: a companion is an ordinary Maven dependency, the plugin registry's gate resolves
  it as a coordinate, and removing the dependency removes the plugin because nothing anywhere else remembers
  it. Read straight out of the jars and directories rather than through a `ClassLoader` — a classloader
  would also answer from the *host's* classpath, and the entry itself is the base the working directory is
  resolved against. `command` is an array, never a string, because per-platform quoting rules are how a
  launcher becomes unportable.
- **`ServicesPeer`** — the host's own `StudioServices` seen from the other side of the wire, with an
  `Executor` for the UI thread (`Platform::runLater` in Studio, `Runnable::run` in a test) because
  `Capture` and `Dialogs` are JavaFX and a wire request arrives on whichever thread LSP4J was reading with.
  `capabilitiesOf` declares `RUNS` only when the host is not `Runs.NONE`: a host claiming run control it
  does not have is a plugin drawing a Start button that does nothing.
- **`Wire`** — the contract-to-wire mapping, in one class. It is the host's job because
  `botmaker-plugin-protocol` deliberately names no BotMaker type, so whoever has both has to translate, and
  only the host has both. The cost is two record sets kept in step by hand; the mitigation is that the
  constructor calls are positional, so a component added to one and not the other does not compile *here*.
- **`PluginLoader.companions()`** — the `CompanionPlugin`s on the same classpath, beside `plugins()`. Two
  lists rather than one merged list, because the two interfaces are unrelated types and a host wants each
  kind separately: a palette comes from one, a toolbar from both. `open()` now returns a loader when the
  classpath carries **either** kind — a project whose only plugin is a companion used to be treated as a
  project with no plugins at all. The `ServiceLoader` pass is one generic method serving both, so the
  failure handling that makes a broken plugin an ordinary state exists once.
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

### Changed

- **The module's own rule was restated, because the old one broke.** It read *nothing here may name a type
  outside `com.botmaker.plugin.api` and the JDK*, and mapping wire records to contract records makes that
  impossible — the protocol module names no BotMaker type on purpose, so somebody with both has to
  translate. The rule that was actually load-bearing is the one that stands: **a host that is a command-line
  tool must be able to depend on this and get the contract, the protocol, and nothing else worth
  mentioning.** No SDK, no Studio, no `botmaker-shared`, no OpenCV, no JNA, and no JavaFX on anybody's
  runtime classpath — the two `javafx-*` entries are `provided` and exist only so `Capture`'s `Image` and
  `Color` and `Dialogs`' `Window` can be named.
- **`botmaker-plugin-protocol` moved ahead of this module in the umbrella reactor**, since this now consumes
  it.

### Known limitation

- **This module cannot be released until `botmaker-plugin-protocol` is its own GitHub repository.** It
  presently lives as a directory inside the umbrella, which resolves in the reactor and nowhere else.
  `jitpack.yml` requires `PLUGIN_PROTOCOL_TAG` so the failure is loud rather than a published pom nobody can
  resolve, and `release.sh` has no `--plugin-protocol` flag yet.

### Deliberately absent

- **Extraction of a plugin's own files out of a jar.** `workingDirectory` is resolved against the classpath
  entry that carried the descriptor, and a jar is not a directory a process can start in. A companion
  shipping its own tree unpacks it itself or names a command already installed. Same question the dropped
  asset-bundle module would have answered; still not two callers, so still not a mechanism.
- **A subscription to `Runs`.** `deliverTelemetry` and `deliverRunState` are methods the host calls.
  Subscribing here would mean holding the host's services for the life of the process and unsubscribing
  correctly on every failure path — a second lifecycle beside one the host already runs.
- **Any dependency that reaches a network, a screen or a native library.** A host that is a command-line
  tool must be able to load a plugin without downloading OpenCV and JNA.
- **A plugin cache, a reload watcher, or a sandbox.** A plugin runs arbitrary code in the host's process;
  this module loads it and says so plainly. Anything that implied otherwise would be a security claim
  nothing here can keep.
