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

The one-time steps of the first release are done (namespace `org.tinygears`, signing key, Central token,
`SECURITY.md`); a release follows [RELEASING.md](RELEASING.md). List here, as `- ` items, what blocks the next release:
`release.sh check` fails while any is listed.
