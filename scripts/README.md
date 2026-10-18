# Scripts

Release checks, the release steps and the commit-message check. Only `release.sh tag` tags and pushes; none of them
signs artifacts, deploys or uploads anything. Run them from any directory.

| Script | Checks |
|---|---|
| `check-pom.sh [--self-test]` | POM metadata and the release profile against the Maven Central rules, including the enforcer execution that fails `-Prelease deploy` on a SNAPSHOT |
| `check-javadoc.sh` | no javadoc warnings in the published modules (doclint all); it reruns javadoc with `-Xmaxwarns 100000`, as maven-javadoc-plugin 3.12.0 misses the count when that is raised, and counts the `file:line: warning:` lines |
| `verify-release.sh [--jdk <JDK home>] [--require-jlink] [--self-test]` | the release artifacts and a consumer project, below |
| `check-workflows.sh [--self-test]` | `.github/workflows/ci.yml` (the only workflow) and `.github/dependabot.yml`, below |
| `release.sh prepare\|check\|tag\|next\|notes <version>`, `release.sh --self-test` | the scripted steps of a release ([docs/RELEASING.md](../docs/RELEASING.md)): `prepare` sets the version in the POMs, the dependency snippets, the README status and badge, the build timestamp and the changelog heading; `check` validates the git state (clean, on `main`, equal to `origin/main`, no tag yet, CI green for the commit), the versions and texts, the changelog date against the build timestamp, the POM's repository against `origin`, removed public API lines since the last tag (a major version from 1.0), JDK 21, Maven 3.9.x, the `central` server in `~/.m2/settings.xml` and a gpg secret key, then runs the build and the release checks; `tag` tags and pushes once the version is on Maven Central; `next` sets the next snapshot and an empty `Unreleased` section; `notes` prints the version's changelog section as the release notes (`.github/workflows/release.yml`) and fails unless the POMs are at that version. `--self-test` runs every step on a clone and asserts that each failed check is detected |
| `check-commit-msg.sh <file> \| --self-test` | the commit subject starts with a conventional prefix of `CONTRIBUTING.md` (the commit-msg hook); merge, revert and fixup subjects pass |

## verify-release.sh

Builds a copy of the working tree (uncommitted changes included) with
`mvn -Prelease -Dgpg.skip=true -DskipTests install` into a temporary local repository
(`-Dmaven.repo.local`, with `~/.m2/repository` as a read-only `maven.repo.local.tail`, so it works offline when the
plugins are cached), then:

1. **Release build** as above.
2. **Installed artifacts:** `uom-parent` (pom), `uom-engine`, `uom` and `uom-jsr` (pom, jar, sources and javadoc jar),
   exactly; `uom-codegen`, `uom-tck` and `uom-examples` are installed (pom and jar) but have no
   sources or javadoc jar (they
   are never deployed, see `check-pom.sh`). The jars contain `module-info.class`, `META-INF/LICENSE` (equal to
   `LICENSE`), the manifest entries (`Implementation-Title`, `-Version`, `-Vendor`, `Build-Jdk-Spec` of the JDK, no
   `Automatic-Module-Name`) and nothing else (no tests, no data files); the sources jars likewise; the javadoc jars document no internal package;
   `jar --describe-module` gives the exact module descriptors; `check-pom.sh` passes on the installed POMs.
3. **Reproducibility:** a second build from another directory into another repository gives byte-identical poms,
   jars, sources and javadoc jars. Builds on different JDKs differ (`Build-Jdk-Spec`); releases use JDK 21.
4. **Consumer** (`consumer/`): a project that depends only on `org.tinygears:uom-jsr` resolves exactly `uom-jsr`, `uom`,
   `uom-engine` and `unit-api` 2.2, and runs `uom-examples`' `Examples` (the package copied in: the native examples, then the JSR-385 ones) on the class path and on the module path
   (module `org.tinygears.uom.consumer`); the output must equal `consumer/expected-output.txt`, the values that
   `ExamplesTest` pins (update both together).
5. **jlink:** an image with `org.tinygears.uom.jsr`, the consumer and `jdk.localedata` (for the German locale example)
   contains exactly those modules plus `java.base` and `java.measure`, and runs the consumer with the same output.
   Reported as `SKIPPED` (and the run ends with `OK (jlink skipped)`) when the JDK cannot link (no `jmods` and a
   runtime image that is not linkable or was modified by a distribution package); `--require-jlink` fails instead.
   Before a release it runs on JDK 21 with `--require-jlink` (docs/RELEASING.md); CI does not run it.

`--jdk <JDK home>` runs everything on that JDK (`JAVA_HOME`); options may come in any order. On a JDK other than 21 the script ends with a note that
the JDK 21 run is still to be done. `--self-test` builds once and asserts that each check fails on a mutated copy
(missing licence, stray file, test class, manifest entry, wrong module descriptor, missing javadoc jar, extra
artifact, `-SNAPSHOT` dependency, a differing byte, a wrong consumer output, `uom` resolved from outside the
temporary repository, an extra dependency of the consumer, an extra module in the `jlink` image). Exit status: 0 passed, 1 a check failed (with the reason).

## check-workflows.sh

Parses the workflows and the dependabot configuration (python3 with PyYAML) and fails when: a workflow file other than
`ci.yml` and `release.yml` exists. In `ci.yml`: the triggers are not exactly `push` (filtered to `main`) and an unfiltered `pull_request` (no
`pull_request_target`); `cancel-in-progress` is not limited to pull-request runs; `permissions` is not
exactly `contents: read` or a job sets its own; the workflow mentions `secrets`, `github.token` or `GITHUB_TOKEN`; a
job is not `build` (matrix JDK 17, 21 and 25), `generated` or `analyze`, lacks `timeout-minutes` or runs a command
containing deploy, `-Prelease`, publish, central, gpg, `git push`/`git tag` or `gh release`; an action is not
`actions/checkout`, `actions/setup-java` or `j178/prek-action` (with an exact `prek-version`) pinned to a full commit hash
with its version as a comment
(`@<40 hex digits> # v7.0.1`, the form Dependabot keeps current), checkout keeps credentials, setup-java is not Temurin or sets
a Maven cache outside `build`; the run commands differ from the CI block of `CONTRIBUTING.md`. In `release.yml`: the
trigger is not exactly a pushed `v[0-9]+.[0-9]+.[0-9]+` tag; `permissions` is not `contents: read` with
`contents: write` on its one job `github-release` only; the job is not limited to `TinyGearsOrg/uom`; it uses a secret,
or the token other than as `GH_TOKEN` of the release step; it uses an action other than `actions/checkout` pinned by
hash, or a checkout that keeps credentials; a run command expands an expression, contains deploy, `-Prelease`,
publish, central, gpg, `mvn` or `git push`/`git tag`, or the commands differ from `release.sh notes` and
`gh release create --verify-tag`. Dependabot does not
update Maven (the root and `scripts/consumer`) and GitHub Actions weekly, grouped, with a PR limit and a cooldown of at
least 7 days, and ignores the Eclipse formatter jars of `uom-codegen` (pinned to the Spotless lockfile). `--self-test` asserts that each of these mutations is
detected.
