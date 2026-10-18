# TODO

Open work only. The item numbers are stable IDs: a gap is a finished item; the decisions, with their rationale and when
to revisit them, are in [DESIGN.md](DESIGN.md) section 1 (DR-01 to DR-27); the last version of this file with the full
discussion is `git show 5d734d3:docs/TODO.md`. The groups: positioning against the JSR-385 reference implementation
Indriya (14), open defects and hardening carried over from the milestone reviews (21 to 32), then the steps before the
first release ("Release readiness"). Each item says under "Needed by" what, if anything, it blocks.

## 14. Positioning and community

**Status:** open. Position uom as a typed, multi-system JSR-385 implementation, not as the reference implementation. The
reference status is the expert group's, so engage the unitsofmeasurement maintainers early and share the TCK result as the
first second implementation. Re-run the TCK in CI and publish the result rather than relying on the README claim.

**Needed by:** before the first release announcement.

## 31. Build and CI tooling

**Status:** open.
- `check-pom.sh`, `check-javadoc.sh` and `check-workflows.sh` are not CI steps (`check-workflows.sh` needs PyYAML, and
  adding it changes the `CONTRIBUTING.md` block it compares). `verify-release.sh` runs `check-pom.sh` on the installed
  POMs.
- Plugin updates were deferred for stability (compiler 3.13.0, surefire 3.2.5, exec 3.2.0; dependabot proposes newer ones).
  `waitForPublishCompletion` is deprecated in central-publishing-maven-plugin 0.11.0 and prints a warning per module;
  `skipPublishing` only filters artifacts and the upload mojo still runs on the last module; re-check on a plugin update.
- `verify-release.sh`: no self-test mutation for the consumer's resolved dependency set or the `jlink` stage; it builds
  twice and downloads the dependencies twice on a runner without `~/.m2`; `scripts/consumer/expected-output.txt`
  duplicates what `ExamplesTest` pins.
- `maven-javadoc-plugin` 3.12.0 misses the warning count when `-Xmaxwarns` is raised, so only `check-javadoc.sh` raises it;
  it counts only `file:line: warning:` lines.

**Needed by:** nothing.

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
