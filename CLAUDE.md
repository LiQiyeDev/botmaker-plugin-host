# CLAUDE.md

Guidance for working in **botmaker-plugin-host**, the loader a BotMaker Studio plugin host uses to get a
plugin off one project's own classpath.

Read the umbrella `../CLAUDE.md` first, `../botmaker-studio-api/CLAUDE.md` for the contract this loads
against, and `../docs/refactor/24-plugin-platform.md` for why any of it exists.

## What this module is, and what it is not

It is **two ways of getting to the same interface**: `PluginLoader`, which runs a `ServiceLoader` pass over
one project's own jars, and — since 2026-08-30 — `ProcessPlugin`, which starts a plugin in **another
process** and makes it look like an ordinary `CompanionPlugin`. A host has one plugin interface and two
transports, and this module is the only place that knows there is a second one.

The three plugin-facing modules are three
different relationships and the fastest way to keep them straight is by who depends on which:

| module | who declares it | scope | on whose classloader |
|---|---|---|---|
| `botmaker-studio-api` | the plugin **and** the host | `provided` in both | the host's — it must be one `Class` object |
| `botmaker-plugin-toolkit` | the plugin | `compile` | the **plugin's**, never the host's |
| `botmaker-plugin-host` | the **host** | `compile` | the host's |

So: `botmaker-studio` depends on this and must never depend on the toolkit; a plugin depends on the toolkit
and never on this.

It was extracted from `botmaker-studio` on 2026-08-28 (plugin-ecosystem plan, phase 5) as a **pure move** —
not a line of behaviour changed. The reason for the move is that Studio stopped being the only host: the
CLI's `validate` and `run` load a plugin to check it, and the registry's CI loads a plugin to admit it. All
three must load it *the same way*, and the delegation split is the last code here that should exist in two
copies.

## The one rule

**A host that is a command-line tool must be able to depend on this module and get the contract, the
protocol, and nothing else worth mentioning.** That is what the dependency list has to keep true: no SDK, no
Studio, no `botmaker-shared`, no OpenCV, no JNA, no JavaFX on anybody's runtime classpath.

What is here, and why each is allowed:

| dependency | scope | why it does not break the rule |
|---|---|---|
| `botmaker-studio-api` | `provided` | a host has the contract already, and there must be exactly one copy |
| `botmaker-plugin-protocol` | `compile` | nothing but that module names `PluginPeer`, so nobody else can supply it. ~150 KB, and it names no BotMaker type of its own |
| `gson` | `compile` | reading `companion.json`. Arrives under lsp4j anyway; declared because a module that uses a library should say so |
| `javafx-controls`, `javafx-graphics` | `provided` | **only** so the contract's own signatures can be named — `Capture` hands back an `Image` and a `Color`, `Dialogs.owner()` a `Window`. No `Node` is created and no toolkit is started; a host that never calls `ServicesPeer` needs none of it at runtime |

The rule that stood here until 2026-08-30 was stricter and is worth recording because it *reads* better:
*nothing may name a type outside `com.botmaker.plugin.api` and the JDK*. It broke the moment the host had to
map wire records to contract records — and it had to, because `botmaker-plugin-protocol` deliberately names
no BotMaker type, so somebody with both has to translate, and only the host has both. The rule above is the
one that was actually load-bearing; the old one was a proxy for it that happened to hold while there was one
transport.

**What has not changed:** every type crossing the *plugin classloader* boundary is a contract type or a JDK
type. That is what makes `ServiceLoader.load(StudioPlugin.class, loader)` safe with no reflection on a
plugin's own classes, and no protocol type ever crosses it — a spawned plugin has no classloader on this
side at all.

## Two service types, one pass

Since 2026-08-30 `open()` runs the `ServiceLoader` pass **twice** on the same loader — once for
`StudioPlugin`, once for `CompanionPlugin` — and exposes them as `plugins()` and `companions()`. Two lists
rather than one merged list: the interfaces are unrelated types, and a host wants each kind separately (a
palette comes from one, a toolbar from both).

Two details that are easy to get wrong and have no symptom until they matter:

- **`open()` returns a loader when *either* list is non-empty.** The old rule was "no `StudioPlugin`, no
  loader", which would treat a project whose only plugin is a companion as a project with no plugins at all
  — the classloader closed and every companion silently absent.
- **The two passes share one `load(Class<T>, URLClassLoader)`.** The interesting part of that method is the
  failure handling — a `ServiceConfigurationError` or a `NoClassDefFoundError` from a plugin whose own
  dependency is missing is an ordinary state and must be caught — and it is exactly the thing that must not
  exist in two copies drifting apart.

## The delegation split, and the thing that will bite

Parent-first for `com.botmaker.plugin.api.`, `java.`, `javax.`, `javafx.`, `jdk.`, `sun.`; child-first for
everything else with a parent fallback.

**Every prefix ends in a dot, and that is not cosmetic.** Without it `com.botmaker.plugin.apix.*` — or a
third-party `javafxsupport.*` — is silently taken from the host. `PluginLoaderTest` holds that case
specifically, because it is the kind of bug that produces a working build and a wrong answer.

**Two SDK class-spaces are live at once** while a host also declares a compile dependency on the SDK: the
host's own and the loader's. They must never exchange an SDK type. Studio does not today — every consumer of
a catalog entry reaches it through `simpleName()` / `qualifiedName()` / `offered()` and nothing compares a
`Class<?>` across the two. That is a condition of this class working, not a preference.

## Out-of-process plugins — `ProcessPlugin`, and the promise it keeps

Four classes, and the shape is worth holding: `CompanionDescriptor` finds one, `ProcessPlugin` runs one,
`ServicesPeer` is the host seen from the other side, and `Wire` is the translation.

**The promise is that a broken companion plugin cannot stop a project from opening.** It is the same rule
`PluginHost.discover` already applies to an in-process plugin whose constructor throws, and an
out-of-process plugin has three new ways to break it. Each has a named answer:

- **Will not start, or starts and never answers.** `launch` is the only blocking call and it is bounded by
  the descriptor's `handshakeTimeoutMillis`. It throws `CompanionLaunchException`, which is **checked** —
  that is the design, not ceremony: the compiler makes a host decide what to do about a broken plugin
  instead of leaving it to be discovered at the worst moment.
- **Dies later.** Supervised through `Process.onExit()`, restarted **once**, and a second death is reported
  through the failure reporter with the child's last stderr lines attached. Once rather than forever,
  because a restart loop is how a broken plugin becomes a fan spinning up with nothing on screen to explain
  it.
- **Hangs while answering.** Everything after the handshake is a notification or is not waited on. The
  toolbar is asked for once, at launch.

**The handshake is raced against `Process.onExit()`, and that is not an optimisation.** LSP4J does not fail
a pending request when the stream underneath it closes, so a plugin that crashes mid-handshake would
otherwise cost the full timeout and be reported as *"did not answer"* — sending its author to look for a
hang while they have an exit code and a stack trace in front of them. With the race it is reported at once
as *"exited (3) before answering the handshake"*. It took a test from 19.5 s to 4.5 s, which is the smaller
half of the point.

**`stdout` is the protocol and `stderr` is the log.** A `console.log` in a Node companion is not a stray
line, it is the reason the plugin stopped responding. Its stderr is drained (required, not courteous — a
child blocked on a full pipe looks exactly like one that has hung), prefixed with the plugin's id, and the
last 40 lines are kept so a failure can be reported in the plugin's own words.

**`projectClosing()` sends both `projectClosing` and `shutdown`, then kills the process.** That looks like a
waste of the protocol's distinction between them and is not: the process was spawned from *this project's*
resolved classpath, so the next project — which may pin a different version of the same plugin — must get
its own. The distinction is there for a host that pools one process across projects, and this is not one.

**A shutdown hook reaps the child if the JVM exits without anybody closing it.** A child process is not a
resource the JVM reclaims, and an orphan is the failure a user can neither see nor connect to anything they
did.

### Two things a reader will look for and not find

- **Nothing extracts a plugin's own files out of a jar.** `workingDirectory` is resolved against whatever
  classpath entry carried the descriptor — the directory itself, or the jar's parent — and a jar is not a
  directory you can start a process in. A companion shipping its own tree either unpacks it on first run or
  names a command already installed. This is the same question the dropped asset-bundle module would have
  answered, and it stays unanswered until a second plugin needs it. It is the first thing a companion author
  hits, so it is called out rather than left to be discovered.
- **Nothing subscribes to `Runs` for the plugin.** `deliverTelemetry` and `deliverRunState` are methods the
  host calls. Subscribing here would mean holding the host's `StudioServices` for the life of the process
  and unsubscribing correctly on every failure path — a second lifecycle beside the one the host already
  runs for its in-process plugins.

### The cancellation gap, recorded rather than hidden

`Capture.selectRegion(Consumer)` signals a cancel by **never invoking its callback** — in process an editor
simply leaves its slot as it found it. A request-response wire has no equivalent silence, which is why
`WireRegion.CANCELLED` exists; but the host cannot tell a cancel from a slow user, so `ServicesPeer` arms a
**ten-minute** safety timeout that resolves cancelled. That is a mitigation against a leak, not a fix, and
it is long on purpose so it never fires during a real interaction. The fix is a contract member saying the
user dismissed the overlay, which has to be argued on the host-is-the-only-possible-source test in its own
right and was not, here.

### Do not release this module until `botmaker-plugin-protocol` is its own repository

It presently lives as a **directory inside the umbrella**, which resolves in the reactor and nowhere else.
`jitpack.yml` requires `PLUGIN_PROTOCOL_TAG` in `.deps.env` so the failure is loud rather than a published
pom nobody can resolve, and `release.sh` has no `--plugin-protocol` flag yet. Creating the repository and
adding the flag (the archetype's block: no `.deps.env`, no pom edit, forced by nothing, forcing nothing) is
the prerequisite for any `--plugin-host` release.

## Why `botmaker-studio-api` is `provided`

A host has the contract already. More to the point, the parent-first arm exists precisely so a contract
class is the **same** `Class` object on both sides — and a transitive second copy at a second version is the
one thing that can make that false. So this module refuses to supply one, and a consumer declares it.

## Building

```bash
mvn test        # 47: PluginLoaderTest 10, ProcessPluginTest 12, ServicesPeerTest 9, WireTest 8,
                #     CompanionDescriptorTest 8
mvn install     # com.github.LiQiyeDev:botmaker-plugin-host:0.0.0-SNAPSHOT
```

**`ProcessPluginTest` starts real JVMs, and that is deliberate.** `CompanionFixture` is a second process
that misbehaves on request — never answers, exits before speaking, handshakes with no id, dies twice. Every
interesting property of `ProcessPlugin` is a property of an operating-system process, and a stubbed
`Process` would check that the stub does what the test told it to. The child runs on
`System.getProperty("java.class.path")`, which under surefire is a manifest-only jar whose `Class-Path` the
child honours exactly as this JVM did.

What is **not** tested, on purpose: the ten-minute cancellation timeout (a test that waited for it would
take ten minutes), and anything that needs a JavaFX toolkit — `Wire.png` is checked only on the
nothing-to-encode path, because constructing an `Image` starts a toolkit and this module must be usable
without one.

**Fail-open catches `LinkageError` too, and that is the arm most likely to fire.** A plugin resolved without
one of its own dependencies fails inside `ServiceLoader`'s `Class.forName` as a `NoClassDefFoundError` —
an `Error`, which walked past the original catch and aborted the host's project-open until 2026-08-28. It is
deliberately not `Error`: a broken plugin must not make an `OutOfMemoryError` look like a missing services
file. Note the shape a test for it needs — a missing class named only inside a method body resolves lazily
and does **not** reproduce; the absent class has to be a supertype.

Do not add a test that asserts `open` returned non-null on a real jar you built in `target/`. What is worth
holding is what has **no visible symptom when it is wrong**: the parent-first rule, and *nothing loadable
answers `null`* rather than an empty loader — the first fails much later as a `ClassCastException` between
two identically named classes, and the second as a project that opens with no menus instead of the bundled
ones.

Published through JitPack. **The maintainer owns the publish** — releases are cut from the umbrella with
`../release.sh --plugin-host <version>`.
