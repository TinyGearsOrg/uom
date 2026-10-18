# Releasing

Instructions for the maintainer. **Not yet executed:** no version of uom has been released, signed, tagged or
uploaded. Everything up to the upload is prepared and tested (`scripts/check-pom.sh`, `scripts/verify-release.sh`);
the steps below that touch keys, credentials, Maven Central or tags are the maintainer's alone.

Published: `org.tinygears:uom-parent` (pom only), and `org.tinygears:uom-jsr` and `org.tinygears:uom`, each with a
jar, a sources jar and a javadoc jar. Not published: `uom-codegen`, `uom-tck`, `uom-examples` (deploy skipped, excluded from the Central
bundle, `skipPublishing` set literally in their POMs).

> **Warning.** `mvn -Prelease deploy` is never a dry run: on a release version it uploads the bundle to the Central
> Portal. On a `-SNAPSHOT` version the release profile's enforcer execution (`requireReleaseVersion`,
> `requireReleaseDeps`, not skippable with `-Denforcer.skip`) fails the build before the Central plugin runs. Run it
> only in step 5 below. To check the artifacts locally, use `bash scripts/verify-release.sh`, which installs into a
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

- [ ] The CI workflow is green on `main` for the commit to release.
- [ ] `CHANGELOG.md`: the `Unreleased` section is complete; rename it to the version and date.
- [ ] `docs/api/public-api.txt`: the diff since the last release matches the version step (a removed or changed line
      needs a major version after 1.0, see [DESIGN.md](DESIGN.md), section 8).
- [ ] The scm, url and issue URLs in `pom.xml` point to the current repository name.
- [ ] `docs/TODO.md` has no open item that blocks this release.

## Procedure

Example for version `1.0.0`; the next development version is `1.0.1-SNAPSHOT`.

1. **Set the version** in all six POMs:

   ```
   mvn -B org.codehaus.mojo:versions-maven-plugin:2.23.0:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
   ```

   Change the version of the dependency snippets in `README.md`, `docs/NATIVE.md` and `docs/JSR.md` by hand, and the
   status lines: "Not released" at the end of the README status paragraph, "Not published yet; after
   `mvn -q -B install -DskipTests` in this repository" under its *Install*, and "not published yet" in
   `docs/NATIVE.md`. In `pom.xml`, set
   `project.build.outputTimestamp` to the release date (or the committer date of the previous
   commit, `git log -1 --format=%cI`), as its comment says.
2. **Verify**, with JDK 21:

   ```
   mvn -q -B clean verify
   bash scripts/check-pom.sh
   bash scripts/check-javadoc.sh
   bash scripts/verify-release.sh --require-jlink
   ```

   All must pass. `verify-release.sh` builds the release artifacts into a temporary repository, checks their
   contents, reproducibility, a `jlink` image and a consumer project, and uploads nothing
   ([scripts/README.md](../scripts/README.md)). `--require-jlink` makes a JDK that cannot link (no `jmods`, as in
   some distribution packages) fail the check instead of skipping the `jlink` stage. With another JDK on the `PATH`,
   pass `--jdk <JDK 21 home>`.
3. **Commit** the version change: `git commit -m "build: release 1.0.0"` (do not tag yet).
4. **Check the credentials**: `~/.m2/settings.xml` has the `central` server, and `gpg --list-secret-keys` shows the
   key.
5. **Upload**, with JDK 21:

   ```
   mvn -B -Prelease -Dgpg.skip=false clean deploy
   ```

   This builds, tests, signs and uploads one bundle with the three published artifacts to the Central Portal. With
   `autoPublish=false` nothing is public yet.
6. **Publish in the Portal.** In *Deployments*, wait for the deployment to be *Validated*, check its file list
   (`uom-parent`, `uom-jsr`, `uom`: pom, jar, sources and javadoc jars, each with `.asc` and checksums; nothing of
   `uom-codegen`, `uom-tck`, `uom-examples`), then press *Publish*. If anything is wrong, press *Drop*, fix, and start
   again at step 2; a published version can never be changed or deleted.
7. **Tag** the release commit and push it:

   ```
   git tag -s v1.0.0 -m "uom 1.0.0"
   git push origin main v1.0.0
   ```

8. **Next snapshot:**

   ```
   mvn -B org.codehaus.mojo:versions-maven-plugin:2.23.0:set -DnewVersion=1.0.1-SNAPSHOT -DgenerateBackupPoms=false
   ```

   Add a new empty `Unreleased` section to `CHANGELOG.md`, commit `build: prepare 1.0.1-SNAPSHOT` and push.

The artifacts appear on Maven Central (`https://repo1.maven.org/maven2/org/tinygears/uom/`) some minutes to hours
after *Publish*.
