# CLAUDE.md

Guidance for working in **botmaker-plugin-host**, the loader a BotMaker Studio plugin host uses to get a
plugin off one project's own classpath.

Read the umbrella `../CLAUDE.md` first, `../botmaker-studio-api/CLAUDE.md` for the contract this loads
against, and `../docs/refactor/24-plugin-platform.md` for why any of it exists.

## What this module is, and what it is not

It is **one class**, `PluginLoader`, and that is deliberate. The three plugin-facing modules are three
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

**Nothing in this module may name a type outside `com.botmaker.plugin.api` and the JDK.** Not the SDK, not
Studio, not JavaFX, not `botmaker-shared`. Two reasons, and the second is the operational one:

1. A host that is not Studio must be able to use it. Reaching for `botmaker-shared` here would drag OpenCV
   and JNA into a command-line tool that wants to load one jar and read an id.
2. Every type crossing the plugin boundary is a contract type or a JDK type — that is what makes
   `ServiceLoader.load(StudioPlugin.class, loader)` safe without reflecting on a plugin's own classes. A
   third namespace here would be a third namespace to get the parent-first/child-first answer right for.

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

## Why `botmaker-studio-api` is `provided`

A host has the contract already. More to the point, the parent-first arm exists precisely so a contract
class is the **same** `Class` object on both sides — and a transitive second copy at a second version is the
one thing that can make that false. So this module refuses to supply one, and a consumer declares it.

## Building

```bash
mvn test        # PluginLoaderTest (10)
mvn install     # com.github.LiQiyeDev:botmaker-plugin-host:0.0.0-SNAPSHOT
```

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
