# TODO

Open work only. The item numbers are stable IDs: a gap is a finished item; the decisions, with their rationale and when
to revisit them, are in [DESIGN.md](DESIGN.md) section 1 (DR-01 to DR-14); the last version of this file with the full
discussion is `git show 5d734d3:docs/TODO.md`. The groups: gaps against the JSR-385 reference implementation Indriya
(10, 13, 14), pre-commit (17), open defects and hardening carried over from the milestone reviews (21 to 32), the
native API (33), then the steps before the first release ("Release readiness"). Each item says under
"Needed by" what, if anything, it blocks.

## 10. Behaviour comparison with Indriya

**Status:** probed and documented. Probes against Indriya 2.2 decided offset arithmetic (DR-01), `asType` with alternate
units (DR-02), offset-unit products (DR-03) and the provider coexistence (DR-05; `ProviderCoexistenceTest`). Equality,
`isEquivalentTo`, numbers, formats and parsing are compared in [INDRIYA.md](INDRIYA.md), whose examples print both
libraries' results and run in `DocsSnippetsTest`. Three differences there are marked open and need a decision:
- The system unit of a prefixed derived unit prints as its decomposition (`kPa.getSystemUnit()` is `kg/(m·s^2)`, equal
  to `Pa`; Indriya gives `Pa`). Giving it no symbol lets the format find `Pa`, but `N·m` then prints as `J`.
- Text Indriya writes does not parse in uom: the Greek mu `μ` (U+03BC) as micro, `℃`, `㎥`, `one`, `%`, `l`, `m2`.
  Accepting some as parse-only aliases conflicts with "look-alike characters are different symbols" (CUSTOM-UNITS.md).
- A parsed formula keeps its normalised text as its symbol (`m*s^-1` prints `m·s^-1`, equal to `m/s`); Indriya prints
  `m/s`.

Symbols that neither library parses (`bar`, `kWh`, `atm`, `mmHg`, `Å`, and `°`, `′`, `″`, which uom spells `deg`,
`arcmin`, `arcsec`) belong to item 13.

**Needed by:** the three decisions before 1.0.

## 13. Scope gaps against Indriya

**Status:** known, deferred or unchecked. Items marked "unchecked" were read from Indriya's README only.
- User-defined quantity types: out of scope for 1.0 (REQUIREMENTS F14); Indriya supports them.
- Typed quantity products and quotients (`Length` / `Time` returning `Speed`): out of scope; `multiply` and `divide` return
  untyped results and need `asType` or `X.from` (DR-10).
- Closed set of 33 generated quantity types.
- Unit sets and formats beyond the eight catalogs and the standard format (for example UCUM, and unit sets of the separate
  unitsofmeasurement projects): unchecked.
- Poise, stokes and oersted are untyped `UomUnit<?>` constants; extra quantity types (`DynamicViscosity`,
  `KinematicViscosity`, `MagneticFieldStrength`) would type them and extend `data/quantity-types.txt`.

**Needed by:** decide which gaps matter for 1.0 and say so in the README.

## 14. Positioning and community

**Status:** open. Position uom as a typed, multi-system JSR-385 implementation, not as the reference implementation. The
reference status is the expert group's, so engage the unitsofmeasurement maintainers early and share the TCK result as the
first second implementation. Re-run the TCK in CI and publish the result rather than relying on the README claim.

**Needed by:** before the first release announcement.

## 17. Pre-commit configuration

**Status:** not started. There is no `.pre-commit-config.yaml`; the checks run only in CI and by hand (`scripts/check-pom.sh`,
`scripts/check-javadoc.sh`, `scripts/check-workflows.sh`). Add a config whose hooks are cheap and local: the formatting
check (`spotless:check`), whitespace and end-of-file fixers, a check that generated files are not edited by hand (regenerate and
diff, or a header check), the `scripts/check-*.sh` scripts that need no network, and a commit-message hook for the
conventional prefixes of `CONTRIBUTING.md`. Keep `mvn clean verify` out of the hooks (too slow); CI stays the gate.
Document the setup in `CONTRIBUTING.md` and `AGENTS.md`.

**Needed by:** nothing.

## 22. Decimal arithmetic residuals

**Status:** open.
- Cost: a huge integer parses with a full superlinear `BigDecimal` parse (no digit bound at parse time; two million
  digits take tens of seconds). Only the scale and quantity contexts are bounded. At precision 100000 inexact results below
  1 throw `ArithmeticException("scale out of range")`.

**Needed by:** decide before 1.0.

## 23. Alternate-unit registry (global state)

**Status:** open, decision needed. Alternate units (`~` lines, `UomUnit.alternate`) register in one JVM-wide registry.
- Alternates of user `.units` files and of a failed `parse` stay registered for the life of the JVM; loading the same
  definition again works, a different one is an error until restart. A scoped registry for run-time files is the proposed
  fix.
- `rad.alternate("rad")` throws while `METRE.alternate("m")` returns an equal unit: accept `{existingAtom:1}` as the same
  definition or document it.
- One conflicting registration breaks initialisation: `SI` loads all 33 factories through `PREFERRED`, so a conflicting
  alternate breaks `SI` (the factories are built lazily per type in `QuantityTypes`).

**Needed by:** decide before 1.0 (user-visible behaviour of `load`).

## 27. User-unit loader

**Status:** open, decision needed.
- Fallback preferred units are not members of the system (`USCustomary.SYSTEM.getUnit(Time.class)` is `s`, but
  `getUnit("s")` is null). Indriya 2.2 never returns a non-member: its `Units` registers each unit with its quantity
  type, and systems-common 2.1's `USCustomary` and `Imperial` return null from `getUnit(Class)` for every type. Options:
  list the fallback units as members, document the fallback, or return null (then `toSystem(system)` fails for those
  types).

**Needed by:** nothing.

## 31. Build and CI tooling

**Status:** open.
- Add `slf4j-nop` at test scope in `uom-tck`: the TCK run prints "SLF4J: No SLF4J providers were found" (no slf4j entry in
  the POM today). On JDK 21 the TCK also prints "No output directory specified" and leaves an empty root `target/`.
- CI owner decisions: pin actions to full SHAs (needs `ACTION` in `check-workflows.sh` to accept `@[0-9a-f]{40}`; they
  are pinned to major tags today); `on: push` plus `pull_request` runs a PR from a branch of this repository twice (a
  `main`-only push filter halves it); `cancel-in-progress: true` also cancels superseded runs on `main` (use
  `${{ github.event_name == 'pull_request' }}`); job-level `permissions: {contents: read}` needs `check-workflows.sh`
  changed, which now forbids it.
- `check-pom.sh`, `check-javadoc.sh` and `check-workflows.sh` are not CI steps (`check-workflows.sh` needs PyYAML, and
  adding it changes the `CONTRIBUTING.md` block it compares). `verify-release.sh` runs `check-pom.sh` on the installed
  POMs.
- Plugin updates were deferred for stability (compiler 3.13.0, surefire 3.2.5, exec 3.2.0; dependabot proposes newer ones).
  `waitForPublishCompletion` is deprecated in central-publishing-maven-plugin 0.11.0 and prints a warning per module;
  `skipPublishing` only filters artifacts and the upload mojo still runs on the last module; re-check on a plugin update.
  A non-quiet build warns "JAR will be empty" for `uom-tck`.
- `verify-release.sh`: no self-test mutation for the consumer's resolved dependency set or the `jlink` stage;
  `xargs -n1 basename` splits on whitespace (a repository path with spaces breaks it); it builds twice and downloads the
  dependencies twice on a runner without `~/.m2`; `scripts/consumer/expected-output.txt` duplicates what `ExamplesTest` pins.
- `mvn test -pl uom` without `-am` reads `uom-jsr` from `~/.m2`, so `ApiSurfaceTest` can compare a stale jar (same for
  `-pl uom-codegen`): say so in `CONTRIBUTING.md`. The API pin strings were generated on JDK 25 and pass on Temurin 21.
- `maven-javadoc-plugin` 3.12.0 misses the warning count when `-Xmaxwarns` is raised, so only `check-javadoc.sh` raises it;
  it counts only `file:line: warning:` lines.

**Needed by:** the CI decisions before the first push of the workflow; the rest nothing.

## 33. A native API next to the JSR-385 implementation

**Status:** decided (DESIGN DR-21), in progress on branch `native-prototype`: complete the split, the native API as
`uom` and the JSR-385 implementation as `uom-jsr`. Done: the engine with the shared arithmetic, the 33 generated native
types with typed products, the native catalogs, offset units, the bridge, and the formats, systems, `toSystem`,
run-time `.units` files and serialization on the engine's shared format code, the renames (`uom` is the native API,
`uom-jsr` the JSR-385 implementation with the bridge, the TCK runs on `uom-jsr`), and the user documents and examples,
which present `uom` first ([NATIVE-API.md](NATIVE-API.md), "Progress"). Open: merging `native-prototype` into `ng`.

**Needed by:** before 1.0 (module boundaries and the public API).

## Release readiness

No version has been released, signed, tagged or uploaded. The release is prepared and is the owner's action
([RELEASING.md](RELEASING.md)). Before the first release:

- Enable GitHub private vulnerability reporting for the repository (`SECURITY.md` points to it), and confirm the
  contact and the "best-effort" response wording of `SECURITY.md`.
- Verify the Central Portal namespace `org.tinygears` (DNS TXT record of `tinygears.org`; without the domain the group
  id changes).
- Create the OpenPGP signing key, publish it to the key servers, and keep a backup and a revocation certificate.
- Generate a Central Portal user token and put it into `~/.m2/settings.xml` under the server id `central` (never into
  the repository).
- Replace `0.1.0-SNAPSHOT` with the release version and set `project.build.outputTimestamp` (RELEASING, step 1).
- Decide the CI items of item 31 (SHA pinning of actions, duplicate push and pull-request runs, `cancel-in-progress` on
  `main`, job-level permissions), push the branch and see the workflow green on JDK 21 and 25.
- Decide the repository rename; the POM's `url`, `scm` and `issueManagement` follow it.
