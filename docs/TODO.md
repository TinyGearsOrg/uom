# TODO

Open work only. The item numbers are stable IDs: a gap is a finished item; the decisions, with their rationale and when
to revisit them, are in [DESIGN.md](DESIGN.md) section 1 (DR-01 to DR-24); the last version of this file with the full
discussion is `git show 5d734d3:docs/TODO.md`. The groups: gaps against the JSR-385 reference implementation Indriya
(10, 13, 14), open defects and hardening carried over from the milestone reviews (21 to 32), then the steps before the
first release ("Release readiness"). Each item says under "Needed by" what, if anything, it blocks.

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

## 31. Build and CI tooling

**Status:** open.
- Add `slf4j-nop` at test scope in `uom-tck`: the TCK run prints "SLF4J: No SLF4J providers were found" (no slf4j entry in
  the POM today). On JDK 21 the TCK also prints "No output directory specified" and leaves an empty root `target/`.
- CI owner decisions: `on: push` plus `pull_request` runs a PR from a branch of this repository twice (a
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
