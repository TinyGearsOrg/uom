# Releasing

Instructions for the maintainer. **Not yet executed:** no version of uom has been released, signed, tagged or
uploaded. Everything up to the upload is prepared and tested; `scripts/release.sh` scripts the version edits, the
checks before the upload, the tag and the next snapshot. The upload and the publishing in the Central Portal stay
manual, and the steps that touch keys, credentials, Maven Central or tags are the maintainer's alone.

Published: `org.tinygears:uom-parent` (pom only), and `org.tinygears:uom-engine`, `org.tinygears:uom` and
`org.tinygears:uom-jsr`, each with a jar, a sources jar and a javadoc jar. Not published: `uom-codegen`, `uom-tck`, `uom-examples` (deploy skipped, excluded from the Central
bundle, `skipPublishing` set literally in their POMs).

> **Warning.** `mvn -Prelease deploy` is never a dry run: on a release version it uploads the bundle to the Central
> Portal. On a `-SNAPSHOT` version the release profile's enforcer execution (`requireReleaseVersion`,
> `requireReleaseDeps`, not skippable with `-Denforcer.skip`) fails the build before the Central plugin runs. Run it
> only in step 3 below. To check the artifacts locally, use `bash scripts/verify-release.sh`, which installs into a
> temporary local repository and uploads nothing.

## One-time setup

1. **Central Portal account.** Sign in at <https://central.sonatype.com>.
2. **Namespace `org.tinygears`.** In the Portal, *Namespaces*, add `org.tinygears` and verify it: the Portal shows a
   verification key that must be published as a DNS TXT record of `tinygears.org`. Without control of that domain the
   group id must change (for example `io.github.tinygearsorg`, verified through the GitHub organization), which is an
   API-visible change of the coordinates.
3. **User token.** In the Portal, *Account*, *Generate User Token*. Put it into `~/.m2/settings.xml` (never into the
   repository) under the server id the POM uses:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN_USERNAME</username>
         <password>TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

4. **Signing key.** Central requires an OpenPGP signature (`.asc`) for every file:

   ```
   gpg --full-generate-key                          # RSA 4096 or ed25519, with an expiry date
   gpg --list-secret-keys --keyid-format=long       # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
   gpg --keyserver keys.openpgp.org --send-keys <KEYID>
   ```

   Keep a revocation certificate and a backup of the key outside the machine. The build signs with `gpg` (the default
   key, or `-Dgpg.keyname=<KEYID>`); under `mvn -B` the plugin runs gpg in batch mode and reads the passphrase from
   the environment variable `MAVEN_GPG_PASSPHRASE` when it is set, else gpg-agent asks for it.
5. **JDK 21.** Build releases with a JDK 21 (Temurin): the jars record `Build-Jdk-Spec`, so the bytes are
   reproducible per JDK version, and the reproducibility check of `verify-release.sh` compares builds on one JDK.

## Checklist

`release.sh check` validates the git state, CI, the versions, the changelog, the build timestamp, the repository URLs,
the release environment and the build; these stay a review:

- [ ] `CHANGELOG.md`: the `Unreleased` section describes the release completely.
- [ ] `docs/api/public-api.txt`: the diff since the last release matches the version step (a removed or changed line
      needs a major version after 1.0, see [DESIGN.md](DESIGN.md), section 8; `check` fails on removed lines within one
      major version from 1.0).
- [ ] No open GitHub issue blocks this release.

## Procedure

Example for version `1.0.0`; the next development version is `1.0.1-SNAPSHOT`. Use JDK 21 throughout (with another
JDK on the `PATH`, pass `--jdk <JDK 21 home>` to `check`).

1. **Prepare** the version and push it:

   ```
   bash scripts/release.sh prepare 1.0.0
   git diff
   git commit -am "build: release 1.0.0"
   git push origin main
   ```

   `prepare` sets the version in all six POMs and in the dependency snippets of `README.md`, `docs/NATIVE.md` and
   `docs/JSR.md`, replaces the "not released" status and badge of the first release, sets
   `project.build.outputTimestamp` to the release date and renames `## [Unreleased]` in `CHANGELOG.md` to
   `## [1.0.0] - <date>`. It edits only: review the diff before the commit.
2. **Check**, once CI is green for the release commit:

   ```
   bash scripts/release.sh check 1.0.0
   ```

   It fails on the first problem: a dirty tree, a branch other than `main` or not equal to `origin/main`, an existing
   tag, no green CI run for the commit, a version or a text left at the old version, a changelog date that differs from
   the build timestamp, POM URLs of another repository, removed public API lines
   within a major version, a JDK other than 21, no `central` server in `~/.m2/settings.xml`, no gpg secret key. Then it
   runs `mvn -q -B clean verify`, `check-pom.sh`, `check-javadoc.sh` and `verify-release.sh --require-jlink`
   ([scripts/README.md](../scripts/README.md)), which builds the release artifacts into a temporary repository, checks
   their contents, reproducibility, a `jlink` image and a consumer project, and uploads nothing.
3. **Upload**:

   ```
   mvn -B -Prelease -Dgpg.skip=false clean deploy
   ```

   This builds, tests, signs and uploads one bundle with the four published artifacts to the Central Portal. With
   `autoPublish=false` nothing is public yet.
4. **Publish in the Portal.** In *Deployments*, wait for the deployment to be *Validated*, check its file list
   (`uom-parent`, `uom-engine`, `uom`, `uom-jsr`: pom, jar, sources and javadoc jars, each with `.asc` and checksums;
   nothing of `uom-codegen`, `uom-tck`, `uom-examples`), then press *Publish*. If anything is wrong, press *Drop*, fix,
   and start again at step 1 with the same version; a published version can never be changed or deleted.
5. **Tag**, once the version is on Maven Central (minutes to hours after *Publish*):

   ```
   bash scripts/release.sh tag 1.0.0
   ```

   It checks that `uom-1.0.0.pom` is on `repo1.maven.org`, then creates the signed tag `v1.0.0` on the release commit
   and pushes it. The pushed tag starts `.github/workflows/release.yml`, which creates the GitHub release "uom 1.0.0"
   with the notes of the `1.0.0` section of `CHANGELOG.md` (`release.sh notes 1.0.0`) and links to Maven Central; it
   fails if the tagged commit is not at version 1.0.0.
6. **Next snapshot:**

   ```
   bash scripts/release.sh next 1.0.1-SNAPSHOT
   git commit -am "build: prepare 1.0.1-SNAPSHOT"
   git push origin main
   ```

   `next` sets the version and adds an empty `Unreleased` section to `CHANGELOG.md`.
