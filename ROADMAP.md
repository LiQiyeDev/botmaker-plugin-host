# ROADMAP — botmaker-plugin-host

The running engineering log. `CHANGELOG.md` is the short, per-release answer; this is the detail and the
reasoning.

## Done

### 2026-08-28 — the module exists, as a pure extraction

Plugin-ecosystem plan, phase 5. `PluginLoader` moved out of `botmaker-studio` with **no behaviour change**:
the class is byte-for-byte the same logic, made `public`, in `com.botmaker.plugin.host`.

**Why a module and not a copy.** Three consumers are coming — Studio, the CLI's `validate` and `run`, and
the registry's CI — and the parent-first/child-first split is precisely the code that must not drift between
two of them. A wrong answer there does not throw where it happens: it throws much later as a
`ClassCastException` between two classes whose names are identical, which the class's own javadoc calls the
least diagnosable failure available.

**Why not somewhere that already exists:**

- **Not `botmaker-plugin-toolkit`.** That is a *plugin's* dependency, resolved onto the plugin's own
  classloader. Loading plugins is the *host's* job, and the two must not be able to reach each other.
- **Not `botmaker-shared`.** It would drag OpenCV and JNA into a command-line tool whose whole job is to
  open one jar and read an id.
- **Not `botmaker-studio-api`.** The contract is interfaces and records with no implementation, and must be
  allowed to version slowly. This is implementation and will move.

**The one thing that changed shape**: `parentFirst(String)` was a private static inside the private
`Inverted` loader and is now package-private on `PluginLoader`, so `PluginLoaderTest` can assert it
directly. It is the one decision in the class with **no visible symptom when it is wrong** — a class
resolved from the wrong side still loads.

**The scope of `botmaker-studio-api` here is `provided`, and that is an argument rather than a convention.**
The parent-first arm exists so a contract class is the same `Class` object on both sides; a transitive
second copy at a second version is the one thing that can make that false. So this module refuses to supply
one and a consumer declares it. Studio already did.

Nine tests landed with it, where Studio had none for this class: the split (including the case the
dot-terminated prefixes exist for), the three ways a classpath answers `null`, and a real `ServiceLoader`
round trip that goes through the child-first fallback arm.

## Deferred / next

- **A second host is the proof.** Studio consuming this changes nothing about Studio; the module earns its
  place the day `botmaker validate` loads a plugin through it (plan phase 7) and the registry's CI runs the
  same code (phase 8).
- **`PluginHost` did not move and should not yet.** Studio's `PluginHost` is the bundled-fallback policy,
  the swap-on-project-change lifecycle and the two catalogs it serves — all of it about *Studio's* open
  project. What a CLI needs is `open`/`plugins`/`close`, which is what moved. If the CLI turns out to want
  the fallback rule as well, lift **that rule** with a named argument for the bundled set; do not lift a
  class whose job is to hold a static that a single-shot process has no use for.
- **No `module-info.java`.** Worth revisiting only if a consumer goes modular; a `URLClassLoader` over an
  unnamed-module classpath is the whole design, and JPMS would make the child-first arm considerably harder
  to state.
