# TODO

Open work only. The item numbers are stable IDs: a gap is a finished item; the decisions, with their rationale and when
to revisit them, are in [DESIGN.md](DESIGN.md) section 9 (DR-01 to DR-27); the last version of this file with the full
discussion is `git show 5d734d3:docs/TODO.md`. The groups: positioning against the JSR-385 reference implementation
Indriya (14), then the steps before the first release ("Release readiness"). Each item says under "Needed by" what, if
anything, it blocks.

## 14. Positioning and community

**Status:** open. Position uom as a typed, multi-system JSR-385 implementation, not as the reference implementation. The
reference status is the expert group's, so engage the unitsofmeasurement maintainers early and share the TCK result as the
first second implementation. The TCK runs in every CI build (`uom-tck`), so the README badge is backed by CI.

**Needed by:** before the first release announcement.

## Release readiness

No version has been released, signed, tagged or uploaded. The release is prepared and is the owner's action
([RELEASING.md](RELEASING.md)). Before the first release:

- Confirm the contact and the "best-effort" response wording of `SECURITY.md` (private vulnerability reporting is
  enabled on `TinyGearsOrg/uom`).
- Verify the Central Portal namespace `org.tinygears` (DNS TXT record of `tinygears.org`; without the domain the group
  id changes).
- Create the OpenPGP signing key, publish it to the key servers, and keep a backup and a revocation certificate.
- Generate a Central Portal user token and put it into `~/.m2/settings.xml` under the server id `central` (never into
  the repository).
