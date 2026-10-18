#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# The scripted steps of a release (docs/RELEASING.md): prepare the version edits, check everything before the upload,
# tag the version once it is on Maven Central, and start the next snapshot. Never uploads, signs artifacts or publishes.
# Usage: scripts/release.sh prepare <version>
#        scripts/release.sh check <version> [--jdk <JDK home>] [--no-verify]
#        scripts/release.sh tag <version>
#        scripts/release.sh next <version>-SNAPSHOT
#        scripts/release.sh notes <version>
#        scripts/release.sh --self-test
# Requires: bash, git, mvn (3.9+), python3; check also gh (CI status), curl and gpg; tag also curl and gpg.
# Exits 1 with a message on the first failed check.
set -euo pipefail

SCRIPT=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
ROOT=${RELEASE_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
# overridable for the self-test
CENTRAL=${RELEASE_CENTRAL:-https://repo1.maven.org/maven2}
SETTINGS=${RELEASE_SETTINGS:-$HOME/.m2/settings.xml}
GPG_KEYS=${RELEASE_GPG_KEYS:-gpg --list-secret-keys --with-colons}
JDK_MAJOR=${RELEASE_JDK_MAJOR:-21}
TODAY=${RELEASE_DATE:-$(date -u +%Y-%m-%d)}
# set: no fetch, CI status, Central lookup or push (the self-test works on a clone without a remote)
OFFLINE=${RELEASE_OFFLINE:-}
TAG_FLAG=${RELEASE_TAG_FLAG:--s}
VERSIONS=org.codehaus.mojo:versions-maven-plugin:2.23.0
DOCS=(README.md docs/NATIVE.md docs/JSR.md)

fail() { echo "release: FAIL: $*" >&2; exit 1; }
say() { echo "release: $*"; }
usage() { fail "usage: release.sh prepare <version> | check <version> [--jdk <JDK home>] [--no-verify] | tag <version> | next <version>-SNAPSHOT | notes <version> | --self-test"; }

release_version() { [[ $1 =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$1' is not a release version (MAJOR.MINOR.PATCH)"; }
git_() { git -C "$ROOT" "$@"; }
clean_tree() { [[ -z $(git_ status --porcelain) ]] || fail "the working tree is not clean: commit or stash first"; }
pom_version() {
  python3 - "$ROOT/pom.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
print(ET.parse(sys.argv[1]).getroot().find('m:version', ns).text)
EOF
}
# the GitHub repository of the POM's <url>, as owner/name
pom_repo() { sed -n 's|.*<url>https://github.com/\([^<]*\)</url>.*|\1|p' "$ROOT/pom.xml" | head -1; }
set_version() {
  (cd "$ROOT" && mvn -q -B "$VERSIONS:set" -DnewVersion="$1" -DgenerateBackupPoms=false) \
    || fail "mvn versions:set $1 failed"
}

prepare() { # <version>
  local v=$1 old repo
  release_version "$v"
  clean_tree
  old=$(pom_version)
  [[ $old == *-SNAPSHOT ]] || fail "pom.xml version is $old, expected a -SNAPSHOT version to release from"
  grep -q '^## \[Unreleased\]' "$ROOT/CHANGELOG.md" || fail "CHANGELOG.md has no '## [Unreleased]' section"
  repo=$(pom_repo)
  set_version "$v"
  python3 - "$ROOT" "$old" "$v" "$TODAY" "$repo" "${DOCS[@]}" <<'EOF'
import pathlib, re, sys
root, old, v, today, repo, docs = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6:]
def edit(f, fn):
    p = root / f; p.write_text(fn(p.read_text()))
for d in docs:
    edit(d, lambda t: t.replace('<version>%s</version>' % old, '<version>%s</version>' % v))
# the first release replaces the texts of an unreleased project; later releases find them gone
edit('README.md', lambda t: t
     .replace('**Status:** in development, not released.', '**Status:** released, version %s.' % v)
     .replace('<img src="https://img.shields.io/badge/release-not%20yet-lightgrey" alt="Release: not yet">',
              '<a href="https://central.sonatype.com/artifact/org.tinygears/uom"><img '
              'src="https://img.shields.io/maven-central/v/org.tinygears/uom" alt="Maven Central"></a>')
     .replace('Java 17 or later. Not published yet; after `mvn -q -B install -DskipTests` in this repository:',
              'Java 17 or later, from Maven Central:'))
edit('docs/NATIVE.md', lambda t: re.sub(r'Java 17 or later; not published yet \(build it with\s+`mvn -q -B install -DskipTests`\)\.',
                                        'Java 17 or later, from Maven Central.', t))
edit('pom.xml', lambda t: re.sub(r'<project.build.outputTimestamp>[^<]*</project.build.outputTimestamp>',
                                 '<project.build.outputTimestamp>%sT00:00:00Z</project.build.outputTimestamp>' % today, t, count=1))
edit('CHANGELOG.md', lambda t: t.replace('## [Unreleased]', '## [%s] - %s' % (v, today), 1)
     .replace('[Unreleased]: https://github.com/%s/commits/main' % repo, '[%s]: https://github.com/%s/releases/tag/v%s' % (v, repo, v))
     .replace('[Unreleased]: https://github.com/%s/compare/' % repo, '[%s]: https://github.com/%s/compare/' % (v, repo)))
EOF
  say "prepared $v: review 'git diff', commit it as 'build: release $v', push it, wait for CI, then run 'release.sh check $v'"
}

check() { # <version> [--jdk <home>] [--no-verify]
  local v=$1 jdk='' verify=1 repo sha major java last removed
  shift
  while [[ $# -gt 0 ]]; do
    case $1 in
      --jdk) jdk=$(cd "${2:?--jdk needs a JDK home}" && pwd); shift 2 ;;
      --no-verify) verify=; shift ;;
      *) usage ;;
    esac
  done
  release_version "$v"
  repo=$(pom_repo)

  say "git state"
  clean_tree
  [[ $(git_ rev-parse --abbrev-ref HEAD) == main ]] || fail "not on branch main"
  ! git_ rev-parse -q --verify "refs/tags/v$v" > /dev/null || fail "tag v$v exists"
  if [[ -z $OFFLINE ]]; then
    git_ fetch -q origin
    [[ $(git_ rev-parse HEAD) == $(git_ rev-parse origin/main) ]] \
      || fail "HEAD is not origin/main: push the release commit (and pull) first"
    [[ -z $(git_ ls-remote --tags origin "refs/tags/v$v") ]] || fail "tag v$v exists on origin"
    sha=$(git_ rev-parse HEAD)
    local runs
    runs=$(gh run list -R "$repo" --workflow ci.yml --commit "$sha" --json status,conclusion \
           --jq '[.[] | select(.status == "completed") | .conclusion] | join(" ")') || fail "gh run list failed"
    [[ " $runs " == *" success "* && " $runs " != *" failure "* && " $runs " != *" cancelled "* ]] \
      || fail "CI has no green run for $sha (conclusions: ${runs:-none yet}): wait for it or fix it"
  fi

  say "versions and texts"
  python3 - "$ROOT" "$v" "$TODAY" "${DOCS[@]}" <<'EOF'
import pathlib, re, sys, xml.etree.ElementTree as ET
root, v, today, docs = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4:]
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
def fail(msg): print('release: FAIL: ' + msg, file=sys.stderr); sys.exit(1)
poms = [root / 'pom.xml'] + sorted(p for p in root.glob('*/pom.xml'))
for p in poms:
    r = ET.parse(p).getroot()
    got = (r.find('m:version', ns) if p.parent == root else r.find('m:parent/m:version', ns)).text
    if got != v: fail('%s: version is %s, expected %s' % (p.relative_to(root), got, v))
    if re.search(r'-SNAPSHOT\s*</version>', p.read_text()): fail('%s has a -SNAPSHOT version' % p.relative_to(root))
for d in docs:
    t = (root / d).read_text()
    found = re.findall(r'<artifactId>uom(?:-jsr)?</artifactId>\s*<version>([^<]*)</version>', t)
    if not found or set(found) != {v}: fail('%s: dependency version is %s, expected %s' % (d, ', '.join(found) or 'missing', v))
readme = (root / 'README.md').read_text()
for stale in ('not released', 'release-not%20yet', 'Not published yet'):
    if stale in readme: fail('README.md still says %r' % stale)
if 'not published yet' in (root / 'docs/NATIVE.md').read_text(): fail("docs/NATIVE.md still says 'not published yet'")
log = (root / 'CHANGELOG.md').read_text()
m = re.search(r'^## \[([^\]]*)\](?: - (\d{4}-\d{2}-\d{2}))?$', log, re.M)
if not m or m.group(1) != v or not m.group(2):
    fail("CHANGELOG.md: latest entry is '%s', expected '## [%s] - <date>'" % (m.group(0) if m else 'missing', v))
stamp = re.search(r'<project.build.outputTimestamp>([^<]*)<', (root / 'pom.xml').read_text()).group(1)
if not stamp.startswith(m.group(2)):
    fail('pom.xml: project.build.outputTimestamp is %s, not the changelog date %s' % (stamp, m.group(2)))
todo = (root / 'docs/TODO.md').read_text()
section = todo.split('## Release readiness', 1)
if len(section) == 2 and re.search(r'^- ', section[1].split('\n## ', 1)[0], re.M):
    fail('docs/TODO.md: "Release readiness" still lists open steps')
EOF

  say "repository and public API"
  local origin
  origin=$(git_ remote get-url origin 2>/dev/null || true)
  if [[ -n $origin ]]; then
    origin=${origin%.git}; origin=${origin#git@github.com:}; origin=${origin#https://github.com/}
    [[ $origin == "$repo" ]] || fail "pom.xml names github.com/$repo, but origin is $origin"
  fi
  last=$(git_ describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true)
  if [[ -n $last ]]; then
    removed=$(git_ diff "$last" HEAD -- docs/api/public-api.txt | grep -c '^-[^-]' || true)
    major=${v%%.*}
    if [[ $removed -gt 0 && $major -ge 1 && $major == "$(cut -d. -f1 <<< "${last#v}")" ]]; then
      fail "docs/api/public-api.txt lost $removed lines since $last: a breaking change needs a major version"
    fi
    say "public API since $last: $removed removed or changed lines"
  fi

  say "release environment"
  java=${jdk:+$jdk/bin/}java
  [[ $("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/ *java.specification.version = //p') == "$JDK_MAJOR" ]] \
    || fail "$java is not JDK $JDK_MAJOR: releases are built with JDK $JDK_MAJOR (pass --jdk)"
  [[ -f $SETTINGS ]] || fail "$SETTINGS has no server 'central' with a username and password"
  python3 - "$SETTINGS" <<'EOF' || fail "$SETTINGS has no server 'central' with a username and password"
import sys, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot(); ns = r.tag[1:].split('}')[0] if r.tag.startswith('{') else ''
q = lambda t: '{%s}%s' % (ns, t) if ns else t
ok = any(s.findtext(q('id')) == 'central' and s.findtext(q('username')) and s.findtext(q('password'))
         for s in r.iter(q('server')))
sys.exit(0 if ok else 1)
EOF
  $GPG_KEYS 2>/dev/null | grep -q '^sec' || fail "gpg has no secret key to sign with"

  if [[ -n $verify ]]; then
    say "build and release checks (JDK $JDK_MAJOR)"
    (cd "$ROOT" && JAVA_HOME=${jdk:-${JAVA_HOME:-}} mvn -q -B clean verify) || fail "mvn clean verify failed"
    bash "$ROOT/scripts/check-pom.sh"
    bash "$ROOT/scripts/check-javadoc.sh"
    bash "$ROOT/scripts/verify-release.sh" ${jdk:+--jdk "$jdk"} --require-jlink
  fi
  say "check $v: OK. Upload with JDK $JDK_MAJOR: mvn -B -Prelease -Dgpg.skip=false clean deploy," \
      "then publish the deployment in the Central Portal and run 'release.sh tag $v'"
}

tag() { # <version>
  local v=$1 url
  release_version "$v"
  clean_tree
  [[ $(pom_version) == "$v" ]] || fail "pom.xml version is $(pom_version), expected $v: tag the release commit"
  ! git_ rev-parse -q --verify "refs/tags/v$v" > /dev/null || fail "tag v$v exists"
  if [[ -z $OFFLINE ]]; then
    url=$CENTRAL/org/tinygears/uom/$v/uom-$v.pom
    curl -sfI --max-time 30 "$url" > /dev/null \
      || fail "$url is not on Maven Central yet: publish the deployment in the Portal first, then wait"
  fi
  git_ tag "$TAG_FLAG" "v$v" -m "uom $v"
  if [[ -z $OFFLINE ]]; then git_ push origin "v$v"; fi
  say "tagged v$v; start the next version with 'release.sh next <version>-SNAPSHOT'"
}

next() { # <version>-SNAPSHOT
  local v=$1 cur repo
  [[ $v =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]] || fail "'$v' is not a snapshot version (MAJOR.MINOR.PATCH-SNAPSHOT)"
  clean_tree
  cur=$(pom_version)
  [[ $cur != *-SNAPSHOT ]] || fail "pom.xml version is $cur, already a snapshot"
  repo=$(pom_repo)
  set_version "$v"
  python3 - "$ROOT/CHANGELOG.md" "$cur" "$repo" <<'EOF'
import re, sys
p, cur, repo = sys.argv[1], sys.argv[2], sys.argv[3]
t = open(p).read()
t = re.sub(r'^## \[', '## [Unreleased]\n\n## [', t, count=1, flags=re.M)
t = re.sub(r'^\[', '[Unreleased]: https://github.com/%s/compare/v%s...HEAD\n[' % (repo, cur), t, count=1, flags=re.M)
open(p, 'w').write(t)
EOF
  say "now at $v: commit it as 'build: prepare $v' and push"
}

notes() { # <version>: the release notes, from the version's CHANGELOG.md section (the release workflow uses them)
  local v=$1
  release_version "$v"
  [[ $(pom_version) == "$v" ]] || fail "pom.xml version is $(pom_version), not $v: the tag is not a release commit"
  python3 - "$ROOT/CHANGELOG.md" "$v" <<'EOF'
import re, sys
p, v = sys.argv[1], sys.argv[2]
t = open(p).read()
m = re.search(r'^## \[%s\] - \d{4}-\d{2}-\d{2}\n(.*?)(?=^## \[|^\[[^\]]+\]: |\Z)' % re.escape(v), t, re.M | re.S)
if not m or not m.group(1).strip():
    print('release: FAIL: CHANGELOG.md has no entry for %s' % v, file=sys.stderr); sys.exit(1)
print(m.group(1).strip())
print()
print('Maven Central: [org.tinygears:uom](https://central.sonatype.com/artifact/org.tinygears/uom/%s), '
      '[org.tinygears:uom-jsr](https://central.sonatype.com/artifact/org.tinygears/uom-jsr/%s), '
      '[org.tinygears:uom-engine](https://central.sonatype.com/artifact/org.tinygears/uom-engine/%s).' % (v, v, v))
EOF
}

self_test() {
  local out status base major
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  base=$tmp/base
  git clone -q "$ROOT" "$base"
  git -C "$base" remote remove origin
  git -C "$base" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "test: base"
  # no open release steps in the copy
  python3 - "$base/docs/TODO.md" <<'EOF'
import sys
p = sys.argv[1]; t = open(p).read(); i = t.find('## Release readiness')
open(p, 'w').write(t[:i] if i >= 0 else t)
EOF
  git -C "$base" -c user.name=t -c user.email=t@t commit -q -am "test: no open release steps"
  printf '<settings><servers><server><id>central</id><username>u</username><password>p</password></server></servers></settings>\n' \
    > "$tmp/settings.xml"
  major=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/ *java.specification.version = //p')
  export RELEASE_OFFLINE=1 RELEASE_SETTINGS=$tmp/settings.xml RELEASE_GPG_KEYS="echo sec:u" RELEASE_JDK_MAJOR=$major \
         RELEASE_DATE=2030-01-02 RELEASE_TAG_FLAG=-a
  run() { # <dir> <args...>: sets out and status
    set +e
    out=$(RELEASE_ROOT=$1 bash "$SCRIPT" "${@:2}" 2>&1)
    status=$?
    set -e
  }
  commit() { git -C "$1" -c user.name=t -c user.email=t@t commit -q -am "$2"; }

  run "$base" prepare 9.9.9
  [[ $status -eq 0 ]] || { echo "$out"; fail "self-test prepare: exit $status"; }
  for f in pom.xml uom-jsr/pom.xml README.md docs/NATIVE.md docs/JSR.md; do
    grep -q '9\.9\.9' "$base/$f" || fail "self-test prepare: $f lacks 9.9.9"
  done
  grep -q '^## \[9.9.9\] - 2030-01-02$' "$base/CHANGELOG.md" || fail "self-test prepare: no changelog entry"
  grep -q '<project.build.outputTimestamp>2030-01-02T00:00:00Z<' "$base/pom.xml" || fail "self-test prepare: timestamp"
  commit "$base" "build: release 9.9.9"
  say "self-test prepare: ok"

  # name|python mutation of the copy (empty: none)|check version|expected message fragment (empty: passes)
  local cases=(
    "baseline||9.9.9|"
    "other-version||9.9.8|pom.xml: version is 9.9.9, expected 9.9.8"
    "snapshot-in-docs|sub('docs/JSR.md', '<version>9.9.9</version>', '<version>9.9.9-SNAPSHOT</version>')|9.9.9|docs/JSR.md: dependency version is 9.9.9-SNAPSHOT, expected 9.9.9"
    "module-version|sub('uom/pom.xml', '<version>9.9.9</version>', '<version>9.9.8</version>')|9.9.9|uom/pom.xml: version is 9.9.8, expected 9.9.9"
    "changelog|sub('CHANGELOG.md', '## [9.9.9] - 2030-01-02', '## [Unreleased]')|9.9.9|CHANGELOG.md: latest entry is '## [Unreleased]'"
    "timestamp|sub('pom.xml', '2030-01-02T00:00:00Z', '2026-01-01T00:00:00Z')|9.9.9|outputTimestamp is 2026-01-01T00:00:00Z, not the changelog date 2030-01-02"
    "status-text|add('README.md', 'not released')|9.9.9|README.md still says 'not released'"
    "release-steps|add('docs/TODO.md', '## Release readiness\\n\\n- Verify the namespace.\\n')|9.9.9|\"Release readiness\" still lists open steps"
    "dirty|untracked('x.txt')|9.9.9|the working tree is not clean"
    "tag-exists|tag_('v9.9.9')|9.9.9|tag v9.9.9 exists"
  )
  local c name mutation v expect copy
  for c in "${cases[@]}"; do
    IFS='|' read -r name mutation v expect <<< "$c"
    copy=$tmp/copy && rm -rf "$copy" && cp -r "$base" "$copy"
    if [[ -n $mutation ]]; then
      python3 - "$copy" <<EOF
import pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1])
def sub(f, old, new):
    p = root / f; t = p.read_text(); n = t.replace(old, new, 1)
    if n == t: sys.exit('self-test: mutation did not change ' + f)
    p.write_text(n); commit()
def add(f, text): p = root / f; p.write_text(p.read_text() + '\n' + text + '\n'); commit()
def untracked(f): (root / f).write_text('x')
def tag_(t): subprocess.run(['git', '-C', str(root), 'tag', t], check=True)
def commit(): subprocess.run(['git', '-C', str(root), '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-q', '-am', 'test'], check=True)
$mutation
EOF
    fi
    run "$copy" check "$v" --no-verify
    if [[ -z $expect ]]; then
      [[ $status -eq 0 ]] || { echo "$out"; fail "self-test $name: expected success, got exit $status"; }
    else
      [[ $status -eq 1 ]] || { echo "$out"; fail "self-test $name: expected exit 1, got $status"; }
      [[ $out == *"$expect"* ]] || { echo "$out"; fail "self-test $name: message lacks '$expect'"; }
    fi
    say "self-test check $name: ok"
  done

  RELEASE_SETTINGS=$tmp/none.xml run "$base" check 9.9.9 --no-verify
  [[ $status -eq 1 && $out == *"has no server 'central'"* ]] || { echo "$out"; fail "self-test settings: not detected"; }
  RELEASE_GPG_KEYS=true run "$base" check 9.9.9 --no-verify
  [[ $status -eq 1 && $out == *"no secret key"* ]] || { echo "$out"; fail "self-test gpg: not detected"; }
  say "self-test check settings and gpg: ok"

  run "$base" notes 9.9.9
  [[ $status -eq 0 && $out == "The first release"*"org.tinygears:uom-jsr"* && $out != *"## ["* ]] \
    || { echo "$out"; fail "self-test notes: wrong notes"; }
  run "$base" notes 9.9.8
  [[ $status -eq 1 && $out == *"pom.xml version is 9.9.9, not 9.9.8"* ]] || { echo "$out"; fail "self-test notes: version"; }
  say "self-test notes: ok"

  run "$base" tag 9.9.9
  [[ $status -eq 0 ]] || { echo "$out"; fail "self-test tag: exit $status"; }
  git -C "$base" rev-parse -q --verify refs/tags/v9.9.9 > /dev/null || fail "self-test tag: no tag v9.9.9"
  run "$base" next 9.9.10-SNAPSHOT
  [[ $status -eq 0 ]] || { echo "$out"; fail "self-test next: exit $status"; }
  grep -q '<version>9.9.10-SNAPSHOT</version>' "$base/pom.xml" || fail "self-test next: pom.xml version"
  [[ $(grep -m2 '^## \[' "$base/CHANGELOG.md" | tr '\n' '|') == '## [Unreleased]|## [9.9.9] - 2030-01-02|' ]] \
    || fail "self-test next: changelog sections"
  grep -q '^\[Unreleased\]: https://github.com/.*/compare/v9.9.9...HEAD$' "$base/CHANGELOG.md" \
    || fail "self-test next: changelog link"
  say "self-test tag and next: ok"
  say "self-test passed"
}

case ${1:-} in
  prepare) [[ $# -eq 2 ]] || usage; prepare "$2" ;;
  check) [[ $# -ge 2 ]] || usage; check "${@:2}" ;;
  tag) [[ $# -eq 2 ]] || usage; tag "$2" ;;
  next) [[ $# -eq 2 ]] || usage; next "$2" ;;
  notes) [[ $# -eq 2 ]] || usage; notes "$2" ;;
  --self-test) self_test ;;
  *) usage ;;
esac
