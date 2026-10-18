#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Verifies the release artifacts without publishing anything: builds the release profile into temporary local
# repositories, checks the installed files and POMs, reproducibility, jlink and a consumer project (scripts/README.md).
# Usage: scripts/verify-release.sh [--jdk <JDK home>] [--require-jlink] [--self-test]
#   internal (used by --self-test): --check <repo> | --compare <repo1> <repo2> | --consumer <repo>
# Requires: bash, mvn (3.9+), a JDK 17+ (jar, javac, jlink), unzip, python3.
# Exits 1 with a message on the first failed check.
set -euo pipefail

SCRIPT=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PUBLISHED=(uom-parent uom-engine uom uom-jsr)
UNPUBLISHED=(uom-codegen uom-tck uom-examples)
# Maven 3.9 chained local repository (comma-separated tail): installs go to the temporary head, the tail is only read
TAIL=${VERIFY_REPO_TAIL:-$HOME/.m2/repository}
[[ -n ${VERIFY_REPO_TAIL:-} || -d $TAIL ]] || TAIL=
EXPECTED=${VERIFY_EXPECTED:-$ROOT/scripts/consumer/expected-output.txt}

fail() { echo "verify-release: FAIL: $*" >&2; exit 1; }
say() { echo "verify-release: $*"; }
oneline() { local s=$1; echo "${s//$'\n'/ }"; }

USAGE="usage: verify-release.sh [--jdk <JDK home>] [--require-jlink] [--self-test]"
MODE=release REQUIRE_JLINK='' ARGS=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --jdk)
      [[ -x ${2:-}/bin/java ]] || fail "--jdk: no bin/java in '${2:-}'"
      JAVA_HOME=$(cd "$2" && pwd); export JAVA_HOME; shift 2 ;;
    --require-jlink) REQUIRE_JLINK=1; shift ;;
    --self-test) MODE=self-test; shift ;;
    --check|--consumer) MODE=${1#--}; ARGS=("${2:?$1 needs a repository}"); shift 2 ;;
    --compare) MODE=compare; ARGS=("${2:?}" "${3:?--compare needs two repositories}"); shift 3 ;;
    *) fail "unknown argument '$1' ($USAGE)" ;;
  esac
done
# mvn runs on JAVA_HOME when set: the jar tools must be the same JDK
[[ -z ${JAVA_HOME:-} ]] || export PATH=$JAVA_HOME/bin:$PATH
for t in mvn java javac jar jlink unzip python3; do command -v "$t" > /dev/null || fail "$t not found on the PATH"; done
JDK=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mvn_repo() { # <repo> <mvn args...>
  local repo=$1; shift
  mvn -B -Dmaven.repo.local="$repo" ${TAIL:+-Dmaven.repo.local.tail="$TAIL"} "$@"
}

# Builds a copy of the working tree (uncommitted changes included) into a new local repository.
build() { # <name>
  local src=$tmp/src-$1
  mkdir -p "$src"
  (cd "$ROOT" && tar --exclude=./.git --exclude='./*/target' --exclude=./target -cf - .) | (cd "$src" && tar -xf -)
  mvn_repo "$tmp/repo-$1" -f "$src/pom.xml" -Prelease -Dgpg.skip=true -DskipTests install > "$tmp/build-$1.log" 2>&1 \
    || { tail -50 "$tmp/build-$1.log" >&2; fail "release build $1 failed (log above)"; }
}

version_of() { # <repo>
  local d=("$1"/org/tinygears/uom-parent/*/)
  [[ ${#d[@]} -eq 1 && -d ${d[0]} ]] || fail "expected one version of uom-parent in $1, found ${#d[@]}"
  basename "${d[0]}"
}

files_of() { # <repo> <artifact> <version>: installed files without the repository bookkeeping
  local dir=$1/org/tinygears/$2/$3
  [[ -d $dir ]] || return 0
  local f
  for f in "$dir"/*; do
    f=${f##*/}
    [[ $f == '*' || $f == _remote.repositories || $f == maven-metadata-local.xml ]] || echo "$f"
  done
}

check_entries() { # <jar> <allowed regex> <required entries...>
  local jar=$1 allowed=$2 e list; shift 2
  list=$(jar tf "$jar") || fail "$(basename "$jar"): not a readable jar"
  for e in "$@"; do grep -qxF "$e" <<< "$list" || fail "$(basename "$jar") lacks $e"; done
  while IFS= read -r e; do
    [[ $e =~ $allowed ]] || fail "$(basename "$jar"): stray entry $e"
    [[ ! $e =~ [a-z0-9](Test|Tests|IT)(\$[^/]*)?\.(class|java)$ ]] || fail "$(basename "$jar"): test class $e"
  done <<< "$list"
}

describe_expected() { # <artifact> <version>
  case $1 in
    uom-engine) printf '%s\n' "org.tinygears.uom.engine@$2" 'exports org.tinygears.uom.engine' \
      'exports org.tinygears.uom.engine.parse' \
      'requires java.base mandated' \
      'qualified exports org.tinygears.uom.engine.arithmetic to org.tinygears.uom org.tinygears.uom.jsr' \
      'qualified exports org.tinygears.uom.engine.format to org.tinygears.uom org.tinygears.uom.jsr' \
      'qualified exports org.tinygears.uom.engine.internal to org.tinygears.uom org.tinygears.uom.jsr' ;;
    uom) printf '%s\n' "org.tinygears.uom@$2" 'exports org.tinygears.uom' 'exports org.tinygears.uom.quantity' \
      'exports org.tinygears.uom.systems' 'requires java.base mandated' \
      'requires org.tinygears.uom.engine transitive' \
      'qualified exports org.tinygears.uom.internal to org.tinygears.uom.jsr' ;;
    uom-jsr) printf '%s\n' "org.tinygears.uom.jsr@$2" 'exports org.tinygears.uom.jsr' \
      'exports org.tinygears.uom.jsr.quantity' 'exports org.tinygears.uom.jsr.systems' 'requires java.base mandated' \
      'requires java.measure transitive' 'requires org.tinygears.uom transitive' \
      'requires org.tinygears.uom.engine transitive' \
      'provides javax.measure.spi.ServiceProvider with org.tinygears.uom.jsr.systems.UomServiceProvider' \
      'contains org.tinygears.uom.jsr.spi' ;;
  esac
}

# Stage 2: the installed files, the jar contents and the installed POMs.
check() { # <repo>
  local repo=$1 v a got want jar pkg meta lic
  v=$(version_of "$repo")
  got=$(for a in "$repo"/org/tinygears/*; do echo "${a##*/}"; done | sort)
  want=$(printf '%s\n' "${PUBLISHED[@]}" "${UNPUBLISHED[@]}" | sort)
  [[ $got == "$want" ]] || fail "installed artifacts of org.tinygears are [$(oneline "$got")], expected [$(oneline "$want")]"
  for a in "${PUBLISHED[@]}" "${UNPUBLISHED[@]}"; do
    got=$(files_of "$repo" "$a" "$v" | sort)
    case $a in
      uom-parent) want="$a-$v.pom" ;;
      uom-engine|uom-jsr|uom) want=$(printf '%s\n' "$a-$v-javadoc.jar" "$a-$v-sources.jar" "$a-$v.jar" "$a-$v.pom" | sort) ;;
      # installed locally for the reactor, but never deployed: no sources or javadoc jar
      *) want=$(printf '%s\n' "$a-$v.jar" "$a-$v.pom" | sort) ;;
    esac
    [[ $got == "$want" ]] || fail "$a: installed files are [$(oneline "$got")], expected [$(oneline "$want")]"
  done
  for a in uom-engine uom uom-jsr; do
    jar=$repo/org/tinygears/$a/$v/$a-$v
    case $a in
      uom-jsr) pkg='org/tinygears/uom/jsr'; meta='|META-INF/services/(javax\.measure\.spi\.ServiceProvider)?' ;;
      uom) pkg='org/tinygears/uom'; meta='' ;;
      *) pkg="org/tinygears/uom/${a#uom-}"; meta='' ;;
    esac
    meta="META-INF/(MANIFEST\.MF|LICENSE|maven/(org\.tinygears/($a/(pom\.xml|pom\.properties)?)?)?)?$meta|org/(tinygears/(uom/)?)?"
    check_entries "$jar.jar" "^($meta|module-info\.class|$pkg/([a-z]+/)*([A-Za-z0-9_\$]+\.class|package-info\.class)?)$" \
      module-info.class META-INF/LICENSE META-INF/MANIFEST.MF
    check_entries "$jar-sources.jar" "^($meta|module-info\.java|$pkg/([a-z]+/)*(([A-Za-z0-9_]+|package-info)\.java)?)$" \
      module-info.java META-INF/LICENSE
    check_entries "$jar-javadoc.jar" \
      '^([A-Za-z0-9._-]+/)*([A-Za-z0-9._-]+\.(html|css|js|svg|png|gif|md|zip|MF)|element-list|[A-Z_]+)?$' \
      index.html element-list
    ! jar tf "$jar-javadoc.jar" | grep -q '/internal/' || fail "$a-$v-javadoc.jar documents an internal package"
    lic=$(unzip -p "$jar.jar" META-INF/LICENSE)
    [[ $lic == "$(cat "$ROOT/LICENSE")" ]] || fail "$a-$v.jar: META-INF/LICENSE differs from LICENSE"
    got=$(unzip -p "$jar.jar" META-INF/MANIFEST.MF | tr -d '\r')
    for want in "Implementation-Title: $a" "Implementation-Version: $v" "Implementation-Vendor: TinyGearsOrg" \
                "Build-Jdk-Spec: $JDK"; do
      grep -qxF "$want" <<< "$got" || fail "$a-$v.jar: manifest lacks '$want'"
    done
    # a named module: the manifest must not suggest another name
    ! grep -q '^Automatic-Module-Name:' <<< "$got" || fail "$a-$v.jar: manifest has Automatic-Module-Name"
    got=$(jar --describe-module --file "$jar.jar" | sed '1s/ .*//; /^$/d')
    [[ $got == "$(describe_expected "$a" "$v")" ]] \
      || fail "$a-$v.jar: jar --describe-module gives [$(oneline "$got")], expected [$(oneline "$(describe_expected "$a" "$v")")]"
  done
  # the POM checks of scripts/check-pom.sh on the installed POMs, laid out as the source tree
  mkdir -p "$tmp/poms"
  cp "$repo/org/tinygears/uom-parent/$v/uom-parent-$v.pom" "$tmp/poms/pom.xml"
  for a in uom-engine uom uom-jsr "${UNPUBLISHED[@]}"; do
    mkdir -p "$tmp/poms/$a" && cp "$repo/org/tinygears/$a/$v/$a-$v.pom" "$tmp/poms/$a/pom.xml"
  done
  CHECK_POM_ROOT=$tmp/poms bash "$ROOT/scripts/check-pom.sh" > "$tmp/check-pom.log" 2>&1 \
    || { cat "$tmp/check-pom.log" >&2; fail "installed POMs: check-pom.sh failed (above)"; }
}

# Stage 3: two builds give identical bytes (on the same JDK; Build-Jdk-Spec differs between JDKs).
compare() { # <repo1> <repo2>
  local v a f diffs=()
  v=$(version_of "$1")
  for a in "${PUBLISHED[@]}"; do
    for f in $(files_of "$1" "$a" "$v"); do
      cmp -s "$1/org/tinygears/$a/$v/$f" "$2/org/tinygears/$a/$v/$f" || diffs+=("$f")
    done
  done
  [[ ${#diffs[@]} -eq 0 ]] || fail "not reproducible: ${diffs[*]} differ between two builds"
}

run_examples() { # <label> <java> <args...>: compares the output with the expected file
  local label=$1 out; shift
  out=$("$@" -Dstdout.encoding=UTF-8 2>&1) || { echo "$out" >&2; fail "consumer ($label) failed to run"; }
  [[ $out == "$(cat "$EXPECTED")" ]] \
    || { diff <(echo "$out") "$EXPECTED" >&2 || true; fail "consumer ($label): output differs from $EXPECTED"; }
}

MAIN=org.tinygears.uom.examples.Examples

# Stage 4: a consumer project on the class path and on the module path; sets CONSUMER_PATH for stage 5.
consumer() { # <repo>
  local repo=$1 v c=$tmp/consumer cp got
  v=$(version_of "$repo")
  rm -rf "$c" && cp -r "$ROOT/scripts/consumer" "$c"
  mkdir -p "$c/src/main/java/org/tinygears/uom/examples"
  cp "$ROOT"/uom-examples/src/main/java/org/tinygears/uom/examples/*.java "$c/src/main/java/org/tinygears/uom/examples/"
  # module-info.java present: Maven compiles on the module path
  mvn_repo "$repo" -f "$c/pom.xml" -Duom.version="$v" package dependency:build-classpath \
    -Dmdep.outputFile="$c/classpath.txt" > "$tmp/consumer.log" 2>&1 \
    || { tail -50 "$tmp/consumer.log" >&2; fail "consumer: mvn package failed (log above)"; }
  cp=$(cat "$c/classpath.txt")
  got=$(tr ':' '\n' <<< "$cp" | sed 's|.*/||' | sort)
  [[ $got == "$(printf '%s\n' "uom-$v.jar" "uom-jsr-$v.jar" "uom-engine-$v.jar" unit-api-2.2.jar | sort)" ]] \
    || fail "consumer: dependencies are [$(oneline "$got")], expected uom, uom-jsr, uom-engine, unit-api 2.2"
  for a in uom uom-jsr uom-engine; do
    [[ ":$cp:" == *":$repo/org/tinygears/$a/$v/$a-$v.jar:"* ]] || fail "consumer: $a not resolved from $repo"
  done
  mkdir -p "$c/cp"
  javac --release 17 -encoding UTF-8 -cp "$cp" -d "$c/cp" "$c"/src/main/java/org/tinygears/uom/examples/*.java \
    || fail "consumer: javac on the class path failed"
  run_examples "class path" java -cp "$c/cp:$cp" "$MAIN"
  CONSUMER_PATH=$c/target/uom-consumer-1.jar:$cp
  run_examples "module path" java -p "$CONSUMER_PATH" -m "org.tinygears.uom.consumer/$MAIN"
}

# Stage 5: a jlink image with org.tinygears.uom.jsr and the consumer.
jlink_image() {
  local img=$tmp/image got
  rm -rf "$img"
  # jdk.localedata: Examples.locales formats for Locale.GERMANY, which the root locale data lacks
  if ! jlink -p "$CONSUMER_PATH" --add-modules org.tinygears.uom.jsr,org.tinygears.uom.consumer,jdk.localedata --output "$img" \
       > "$tmp/jlink.log" 2>&1; then
    # a JDK without jmods cannot link if its runtime is not linkable or was modified (distribution packages)
    if grep -q -e 'Module java.base not found' -e 'has been modified' "$tmp/jlink.log"; then
      [[ -z $REQUIRE_JLINK ]] || fail "jlink: this JDK cannot link and --require-jlink is set: $(head -1 "$tmp/jlink.log")"
      JLINK="SKIPPED (this JDK cannot link: $(head -1 "$tmp/jlink.log"))"; return
    fi
    cat "$tmp/jlink.log" >&2; fail "jlink failed"
  fi
  got=$("$img/bin/java" --list-modules | sed 's/@.*//' | sort)
  [[ $got == "$(printf '%s\n' java.base java.measure jdk.localedata org.tinygears.uom org.tinygears.uom.consumer \
      org.tinygears.uom.engine org.tinygears.uom.jsr)" ]] \
    || fail "jlink image modules are [$(oneline "$got")]"
  run_examples "jlink image" "$img/bin/java" -m "org.tinygears.uom.consumer/$MAIN"
  JLINK=ok
}

self_test() {
  local base=$tmp/repo-base name mutation expect status out v
  say "self-test: building the release artifacts once"
  build base
  v=$(version_of "$base")
  # name|mode|python mutation of the copy (j = jar path prefix of a module)|expected message fragment
  local cases=(
    "baseline|check||"
    "baseline-compare|compare||"
    "baseline-consumer|consumer||"
    "license|check|drop(j('uom-jsr') + '.jar', 'META-INF/LICENSE')|uom-jsr-$v.jar lacks META-INF/LICENSE"
    "module-info|check|drop(j('uom') + '.jar', 'module-info.class')|uom-$v.jar lacks module-info.class"
    "sources-license|check|drop(j('uom') + '-sources.jar', 'META-INF/LICENSE')|uom-$v-sources.jar lacks META-INF/LICENSE"
    "stray-data|check|add(j('uom') + '.jar', 'data/si.units')|uom-$v.jar: stray entry data/si.units"
    "test-class|check|add(j('uom-jsr') + '.jar', 'org/tinygears/uom/jsr/UnitTest.class')|uom-jsr-$v.jar: test class org/tinygears/uom/jsr/UnitTest.class"
    "javadoc-internal|check|add(j('uom-jsr') + '-javadoc.jar', 'org.tinygears.uom.jsr/org/tinygears/uom/jsr/internal/X.html')|uom-jsr-$v-javadoc.jar documents an internal package"
    "manifest|check|manifest(j('uom'), 'Implementation-Vendor: TinyGearsOrg', 'Implementation-Vendor: x')|uom-$v.jar: manifest lacks 'Implementation-Vendor: TinyGearsOrg'"
    "license-text|check|replace(j('uom') + '.jar', 'META-INF/LICENSE', b'x')|uom-$v.jar: META-INF/LICENSE differs from LICENSE"
    "no-javadoc|check|rm(j('uom') + '-javadoc.jar')|uom: installed files are [uom-$v-sources.jar uom-$v.jar uom-$v.pom]"
    "tck-sources|check|touch(j('uom-tck') + '-sources.jar')|uom-tck: installed files are [uom-tck-$v-sources.jar uom-tck-$v.jar"
    "extra-artifact|check|mkdir('org/tinygears/uom-extra')|installed artifacts of org.tinygears are [uom uom-codegen uom-engine uom-examples uom-extra uom-jsr"
    "snapshot-dependency|check|sub(j('uom-jsr') + '.pom', '<dependencies>', '<dependencies><dependency><groupId>com.example</groupId><artifactId>x</artifactId><version>1.0-SNAPSHOT</version></dependency>')|uom-jsr: default: SNAPSHOT dependency com.example:x:1.0-SNAPSHOT"
    "not-reproducible|compare|replace(j('uom-jsr') + '-sources.jar', 'META-INF/LICENSE', b'x')|not reproducible: uom-jsr-$v-sources.jar differ"
    "consumer-output|consumer|expected()|consumer (class path): output differs"
    "module-descriptor|check|swap(j('uom') + '.jar', 'module-info.class', j('uom-jsr') + '.jar')|uom-$v.jar: jar --describe-module gives [org.tinygears.uom.jsr@$v"
    "resolved-from-tail|consumer|to_tail('uom')|consumer: uom not resolved from"
  )
  local c mode
  for c in "${cases[@]}"; do
    IFS='|' read -r name mode mutation expect <<< "$c"
    rm -rf "$tmp/copy" "$tmp/expected" "$tmp/tail" && cp -r "$base" "$tmp/copy" && cp "$EXPECTED" "$tmp/expected"
    if [[ -n $mutation ]]; then
      python3 - "$tmp/copy" "$v" "$tmp/expected" <<EOF
import os, sys, zipfile, pathlib
root, v, expected_file = pathlib.Path(sys.argv[1]), sys.argv[2], pathlib.Path(sys.argv[3])
def j(a): return 'org/tinygears/%s/%s/%s-%s' % (a, v, a, v)
def rewrite(f, fn):
    p = root / f; entries = []
    with zipfile.ZipFile(p) as z:
        entries = [(i, z.read(i)) for i in z.infolist()]
    new = fn(entries)
    if new == entries: sys.exit("self-test: mutation did not change " + f)
    with zipfile.ZipFile(p, 'w', zipfile.ZIP_DEFLATED) as z:
        for i, data in new: z.writestr(i, data)
def drop(f, name): rewrite(f, lambda es: [(i, d) for i, d in es if i.filename != name])
def add(f, name): rewrite(f, lambda es: es + [(zipfile.ZipInfo(name), b'x')])
def replace(f, name, data): rewrite(f, lambda es: [(i, data if i.filename == name else d) for i, d in es])
def manifest(f, old, new):
    rewrite(f + '.jar', lambda es: [(i, d.replace(old.encode(), new.encode()) if i.filename == 'META-INF/MANIFEST.MF' else d) for i, d in es])
def swap(f, name, other):
    with zipfile.ZipFile(root / other) as z: data = z.read(name)
    replace(f, name, data)
def to_tail(a):
    d = root.parent / 'tail' / 'org/tinygears'; d.mkdir(parents=True)
    (root / 'org/tinygears' / a).rename(d / a)
def rm(f): (root / f).unlink()
def touch(f): (root / f).write_bytes(b'x')
def mkdir(d): (root / d / v).mkdir(parents=True)
def sub(f, old, new):
    p = root / f; t = p.read_text(); n = t.replace(old, new, 1)
    if n == t: sys.exit("self-test: mutation did not change " + f)
    p.write_text(n)
def expected(): expected_file.write_text(expected_file.read_text().replace('basics: ', 'basics: x', 1))
$mutation
EOF
    fi
    set +e
    case $mode in
      check) out=$(bash "$SCRIPT" --check "$tmp/copy" 2>&1) ;;
      compare) out=$(bash "$SCRIPT" --compare "$base" "$tmp/copy" 2>&1) ;;
      # the tail holds what to_tail moved out of the copy, then the usual tail
      consumer) out=$(VERIFY_EXPECTED=$tmp/expected VERIFY_REPO_TAIL=$tmp/tail${TAIL:+,$TAIL} \
                      bash "$SCRIPT" ${REQUIRE_JLINK:+--require-jlink} --consumer "$tmp/copy" 2>&1) ;;
    esac
    status=$?
    set -e
    if [[ $name == baseline* ]]; then
      [[ $status -eq 0 ]] || { echo "$out"; fail "self-test $name: unmodified copy fails (exit $status)"; }
    else
      [[ $status -eq 1 ]] || { echo "$out"; fail "self-test $name: expected exit 1, got $status"; }
      [[ $out == *"$expect"* ]] || { echo "$out"; fail "self-test $name: message lacks '$expect'"; }
    fi
    say "self-test $name: ok (exit $status)"
  done
  say "self-test passed"
}

JLINK=
case $MODE in
  self-test) self_test; exit 0 ;;
  check) check "${ARGS[0]}"; say "check: OK"; exit 0 ;;
  compare) compare "${ARGS[@]}"; say "compare: OK"; exit 0 ;;
  consumer) consumer "${ARGS[0]}"; jlink_image; say "consumer: OK (jlink: $JLINK)"; exit 0 ;;
esac

say "JDK $JDK ($(command -v java)), Maven repository tail ${TAIL:-none}"
say "[1/5] release build (mvn -Prelease -Dgpg.skip=true -DskipTests install into a temporary repository)"
build 1
say "[2/5] installed artifacts, jar contents and installed POMs"
check "$tmp/repo-1"
say "[3/5] reproducibility: second build from another directory, byte comparison"
build 2
compare "$tmp/repo-1" "$tmp/repo-2"
say "[4/5] consumer on the class path and on the module path"
consumer "$tmp/repo-1"
say "[5/5] jlink image with org.tinygears.uom.jsr"
jlink_image
[[ $JLINK == ok ]] || say "[5/5] $JLINK"
if [[ $JDK != 21 ]]; then
  say "NOTE: built with JDK $JDK; releases are built with JDK 21 (docs/RELEASING.md). The JDK 21 run was NOT done;"
  say "      rerun with: bash scripts/verify-release.sh --jdk <JDK 21 home> --require-jlink"
fi
if [[ $JLINK == ok ]]; then say "OK (nothing was signed, deployed or uploaded)"
else say "OK (jlink skipped); nothing was signed, deployed or uploaded"; fi
