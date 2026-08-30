# ROADMAP — botmaker-plugin-host

The running engineering log. `CHANGELOG.md` is the short, per-release answer; this is the detail and the
reasoning.

## Done

### 2026-08-30 — a second transport was built and reverted the same day

`ProcessPlugin`, `CompanionDescriptor`, `ServicesPeer`, `CompanionLaunchException` and `Wire` — spawning a
companion plugin in another process, handshaking with it over `botmaker-plugin-protocol`, supervising it
and presenting it as an ordinary contract plugin. 37 tests, against a real second JVM. All of it is gone,
with `PluginLoader.companions()` and the `botmaker-plugin-protocol` module itself; this module is back to
one class and its dependency on the contract, `provided`.

The reason is in `../botmaker-studio-api/ROADMAP.md`: the machinery outweighed the one plugin asking for
it. **Two findings from it are worth keeping, because they are properties of LSP4J rather than of this
design, and whoever tries again will hit both:**

- **LSP4J does not fail a pending request when the stream underneath it closes.** A peer that crashes
  mid-request leaves its caller waiting for a timeout, so a plugin that died during its handshake was
  reported as *"did not answer"* — the worst diagnostic available, since its author has an exit code in
  front of them. The fix is to race the request against `Process.onExit()`.
- **LSP4J's default executor is a plain cached pool with non-daemon threads**, which keeps the JVM alive
  after the last window closes. Pass a daemon factory.

**And one rule this module nearly lost.** It read *nothing here may name a type outside
`com.botmaker.plugin.api` and the JDK*, and mapping wire records to contract records made it impossible —
it was widened to a dependency table. With the revert the original rule stands again, unamended, which is
the better outcome: the rule was a good one and the pressure on it came from work that has gone.

The code is at `4653064` and `9dc68c8` if it is ever wanted.

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

### 2026-08-28 — fail-open did not cover the commonest failure

Found the same day, by the plugin archetype (plan phase 6): loading a generated skeleton with the toolkit
left off the classpath threw `NoClassDefFoundError` straight out of `open`. `ServiceLoader`'s
`Class.forName` raises it when a provider's **superclass** is absent, and it is an `Error` — so it walked
past `catch (ServiceConfigurationError | RuntimeException)` and would have aborted Studio's project-open
rather than falling back to the bundled plugins. Exactly the class of failure the null return exists for,
and the *likeliest* one in the wild: a plugin resolved without its own dependency.

`LinkageError` joins the catch. Deliberately **not** `Error` — a broken plugin must not make an
`OutOfMemoryError` look like a missing services file.

The test builds the state rather than describing it: compile a plugin against a helper superclass, delete
the helper's `.class`, load. It fails without the fix, which was checked. Note the shape, because a weaker
fixture does **not** reproduce: a missing class named only inside a method body resolves lazily, so the
plugin loads happily and fails later. It has to be a supertype — which is what a real plugin extending
`AbstractStudioPlugin` has.

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
