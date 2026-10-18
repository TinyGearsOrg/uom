#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks the POMs against the Maven Central rules and the release setup (milestone 7, D1 and D2).
# Usage: scripts/check-pom.sh [--self-test]
# Requires: bash, mvn (3.9+, Maven Central reachable or plugins cached in ~/.m2), python3.
# Exits 1 with a message on the first failed check; --self-test mutates copies of the POMs
# and asserts that each mutation is detected.
set -euo pipefail

SCRIPT=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
ROOT=${CHECK_POM_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
MODULES=(uom-engine uom uom-jsr uom-codegen uom-tck uom-examples)

fail() { echo "check-pom: FAIL: $*" >&2; exit 1; }

self_test() {
  local status out
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  # name|python mutation of the copy|expected message fragment
  local cases=(
    "baseline||"
    "license|drop('pom.xml', '<licenses>', '</licenses>')|uom-parent: licenses/license/name"
    "developer-email|drop('pom.xml', '<email>', '</email>')|uom-parent: developers/developer/email"
    "deploy-version|drop_version('pom.xml', 'maven-deploy-plugin')|plugin.version' for"
    "clean-version|drop_version('pom.xml', 'maven-clean-plugin')|maven-clean-plugin is not pinned"
    "codegen-deploy-skip|sub('uom-codegen/pom.xml', '<skip>true</skip>', '<skip>false</skip>')|uom-codegen: maven-deploy-plugin skip"
    "tck-skip-publishing|sub('uom-tck/pom.xml', '<skipPublishing>true<', '<skipPublishing>false<')|uom-tck: the release profile must set central-publishing skipPublishing to the literal true, found ['false']"
    "codegen-skip-expression|sub('uom-codegen/pom.xml', '<skipPublishing>true<', '<skipPublishing>\${central.skipPublishing}<')|uom-codegen: the release profile must set central-publishing skipPublishing to the literal true"
    "examples-no-release-profile|drop('uom-examples/pom.xml', '<profiles>', '</profiles>')|uom-examples: the release profile must set central-publishing skipPublishing to the literal true, found []"
    "enforcer-rule|drop('pom.xml', '<banDuplicatePomDependencyVersions', '/>')|enforcer rule banDuplicatePomDependencyVersions"
    "outputTimestamp|drop('pom.xml', '<project.build.outputTimestamp>', '</project.build.outputTimestamp>')|project.build.outputTimestamp"
    "exclude-artifacts|drop('pom.xml', '<excludeArtifacts>', '</excludeArtifacts>')|central-publishing excludeArtifacts is []"
    "exclude-tck|sub('pom.xml', '<artifact>uom-tck</artifact>', '')|central-publishing excludeArtifacts is ['uom-codegen', 'uom-examples']"
    "ignore-published|drop('pom.xml', '<ignorePublishedComponents>', '</ignorePublishedComponents>')|central-publishing ignorePublishedComponents is ''"
    "wait-for-publish|drop('pom.xml', '<waitForPublishCompletion>', '</waitForPublishCompletion>')|central-publishing waitForPublishCompletion is ''"
    "fail-on-build-failure|drop('pom.xml', '<failOnBuildFailure>', '</failOnBuildFailure>')|central-publishing failOnBuildFailure is ''"
    "auto-publish|sub('pom.xml', '<autoPublish>false<', '<autoPublish>true<')|central-publishing autoPublish is 'true'"
    "codegen-javadoc-skip|drop('uom-codegen/pom.xml', '<maven.javadoc.skip>', '</maven.javadoc.skip>')|uom-codegen: property maven.javadoc.skip is ''"
    "examples-source-skip|drop('uom-examples/pom.xml', '<maven.source.skip>', '</maven.source.skip>')|uom-examples: property maven.source.skip is ''"
    "snapshot-dependency|sub('uom-jsr/pom.xml', '<dependencies>', '<dependencies><dependency><groupId>com.example</groupId><artifactId>x</artifactId><version>1.0-SNAPSHOT</version></dependency>')|uom-jsr: default: SNAPSHOT dependency com.example:x:1.0-SNAPSHOT"
    "unresolved-expression|sub('uom/pom.xml', '<description>', '<description>\${no.such.property} ')|uom: default: unresolved expression '\${no.such.property}"
    "doclint|drop('pom.xml', '<doclint>', '</doclint>')|maven-javadoc-plugin doclint is ''"
    "javadoc-fail-on-warnings|sub('pom.xml', '<javadoc.failOnWarnings>true<', '<javadoc.failOnWarnings>false<')|maven-javadoc-plugin failOnWarnings is 'false'"
    "enforcer-phase|sub('pom.xml', '<phase>validate</phase>', '<phase>none</phase>')|uom-parent: default: maven-enforcer-plugin executions must be bound to validate"
    "release-version-phase|sub('pom.xml', '<phase>deploy</phase>', '<phase>install</phase>')|the release profile must have one maven-enforcer-plugin execution bound to deploy, found 0"
    "release-version-skip|sub('pom.xml', '<skip>false</skip>', '<skip>\${enforcer.skip}</skip>')|must set skip to the literal false, found '\${enforcer.skip}'"
    "release-version-no-skip|sub('pom.xml', '<skip>false</skip>', '')|must set skip to the literal false, found ''"
    "release-version-rule|drop('pom.xml', '<requireReleaseVersion>', '</requireReleaseVersion>')|lacks the rule requireReleaseVersion"
    "release-deps-rule|drop('pom.xml', '<requireReleaseDeps>', '</requireReleaseDeps>')|lacks the rule requireReleaseDeps"
    "release-maven-rule|sub('pom.xml', '<version>[3.9,3.10)</version>', '<version>[3.9,)</version>')|must require Maven [3.9,3.10), found '[3.9,)'"
    "release-version-order|move_enforcer_last()|must be declared before central-publishing-maven-plugin"
    "release-version-module-override|sub('uom-jsr/pom.xml', '</project>', '<profiles><profile><id>release</id><build><plugins><plugin><artifactId>maven-enforcer-plugin</artifactId><executions><execution><id>enforce-release-version</id><phase>none</phase></execution></executions></plugin></plugins></build></profile></profiles></project>')|uom-jsr: release: maven-enforcer-plugin executions must be bound to validate and deploy"
    "manifest-vendor|drop('pom.xml', '<Implementation-Vendor>', '</Implementation-Vendor>')|uom-engine: manifest entry Implementation-Vendor is ''"
    "distribution-management|sub('pom.xml', '<issueManagement>', '<distributionManagement><repository><id>x</id><url>https://example.invalid/repo</url></repository></distributionManagement><issueManagement>')|distributionManagement must not be set"
    "alt-deployment-property|sub('pom.xml', '<surefire.version>', '<altDeploymentRepository>x::https://example.invalid/repo</altDeploymentRepository><surefire.version>')|property altDeploymentRepository must not be set"
    "release-active|sub('pom.xml', '<id>release</id>', '<id>release</id><activation><activeByDefault>true</activeByDefault></activation>')|release profile must not have an activation"
  )
  local c name mutation expect
  for c in "${cases[@]}"; do
    IFS='|' read -r name mutation expect <<< "$c"
    rm -rf "$tmp/copy" && mkdir -p "$tmp/copy"
    cp "$ROOT/pom.xml" "$tmp/copy/"
    for m in "${MODULES[@]}"; do mkdir -p "$tmp/copy/$m" && cp "$ROOT/$m/pom.xml" "$tmp/copy/$m/"; done
    if [[ -n $mutation ]]; then
      python3 - "$tmp/copy" <<EOF
import re, sys, pathlib
root = pathlib.Path(sys.argv[1])
def edit(f, fn):
    p = root / f; t = p.read_text(); n = fn(t)
    if n == t: sys.exit("self-test: mutation did not change " + f)
    p.write_text(n)
def drop(f, start, end):
    edit(f, lambda t: t[:t.index(start)] + t[t.index(end, t.index(start)) + len(end):])
def sub(f, old, new):
    edit(f, lambda t: t.replace(old, new, 1))
def move_enforcer_last():
    def f(t):
        i = t.index('<id>release</id>'); s = t.rindex('<plugin>', i, t.index('<artifactId>maven-enforcer-plugin', i))
        e = t.index('</plugin>', s) + len('</plugin>'); block = t[s:e]; t = t[:s] + t[e:]
        k = t.index('</plugins>', i); return t[:k] + block + t[k:]
    edit('pom.xml', f)
def drop_version(f, artifact):
    edit(f, lambda t: re.sub(r'(<artifactId>' + artifact + r'</artifactId>\s*)<version>[^<]*</version>', r'\1', t, count=1))
$mutation
EOF
    fi
    set +e
    out=$(CHECK_POM_ROOT="$tmp/copy" bash "$SCRIPT" 2>&1)
    status=$?
    set -e
    if [[ $name == baseline ]]; then
      [[ $status -eq 0 ]] || { echo "$out"; fail "self-test: unmodified copy fails (exit $status)"; }
    else
      [[ $status -eq 1 ]] || { echo "$out"; fail "self-test $name: expected exit 1, got $status"; }
      [[ $out == *"$expect"* ]] || { echo "$out"; fail "self-test $name: message lacks '$expect'"; }
    fi
    echo "self-test $name: ok (exit $status)"
  done
  echo "check-pom: self-test passed"
}

if [[ ${1:-} == --self-test ]]; then self_test; exit 0; fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Maven prints model warnings only at WARN level, so this run is not -q; the enforcer runs in every
# build and is skipped here so that the POM checks below report dependency problems precisely.
if ! mvn -B -f "$ROOT/pom.xml" -Denforcer.skip=true validate > "$tmp/validate.log" 2>&1; then
  cat "$tmp/validate.log" >&2; fail "mvn validate failed"
fi
if grep -q "plugin.version' for" "$tmp/validate.log"; then
  grep "plugin.version' for" "$tmp/validate.log" >&2; fail "Maven warns about a missing plugin version"
fi

mvn -q -B -f "$ROOT/pom.xml" help:effective-pom -Doutput="$tmp/default.xml" > "$tmp/eff.log" 2>&1 \
  || { cat "$tmp/eff.log" >&2; fail "help:effective-pom failed"; }
mvn -q -B -f "$ROOT/pom.xml" -Prelease help:effective-pom -Doutput="$tmp/release.xml" > "$tmp/eff.log" 2>&1 \
  || { cat "$tmp/eff.log" >&2; fail "help:effective-pom -Prelease failed"; }

python3 - "$ROOT/pom.xml" "$tmp/default.xml" "$tmp/release.xml" <<'EOF'
import os, re, sys
import xml.etree.ElementTree as ET

def fail(msg):
    print("check-pom: FAIL: " + msg, file=sys.stderr); sys.exit(1)

def load(path):
    root = ET.parse(path).getroot()
    for e in root.iter():
        e.tag = e.tag.split('}', 1)[-1]
    return root

def text(e, path):
    x = e.find(path)
    return (x.text or '').strip() if x is not None else ''

def projects(root):
    ps = [root] if root.tag == 'project' else root.findall('project')
    return {text(p, 'artifactId'): p for p in ps}

def plugins(p):
    return {(text(x, 'groupId') or 'org.apache.maven.plugins', text(x, 'artifactId')): x
            for x in p.findall('build/plugins/plugin')}

def plugin(p, aid):
    return next((x for (g, a), x in plugins(p).items() if a == aid), None)

REPO = 'https://github.com/TinyGearsOrg/uom'
PUBLISHED = ['uom-parent', 'uom-engine', 'uom', 'uom-jsr']
UNPUBLISHED = ['uom-codegen', 'uom-tck', 'uom-examples']
RELEASE_ONLY = ['maven-source-plugin', 'maven-javadoc-plugin', 'maven-gpg-plugin', 'central-publishing-maven-plugin']

raw = load(sys.argv[1])
props = {e.tag: (e.text or '').strip() for e in raw.findall('properties/*')}
pinned = {}
for x in raw.findall('build/pluginManagement/plugins/plugin'):
    v = re.sub(r'\$\{([^}]+)\}', lambda m: props.get(m.group(1), m.group(0)), text(x, 'version'))
    if v and '${' not in v:
        pinned[(text(x, 'groupId') or 'org.apache.maven.plugins', text(x, 'artifactId'))] = v
# the effective POM omits the default tag, so check it in the source
if text(raw, 'scm/tag') != 'HEAD':
    fail("uom-parent: scm/tag is '%s', expected 'HEAD'" % text(raw, 'scm/tag'))
release = [x for x in raw.findall('profiles/profile') if text(x, 'id') == 'release']
if not release:
    fail("uom-parent: no profile with id 'release'")
if release[0].find('activation') is not None:
    fail("uom-parent: the release profile must not have an activation (it is enabled only with -Prelease)")
# -Prelease deploy on a SNAPSHOT must fail before the Central plugin; a literal skip, so -Denforcer.skip cannot stop it
guard = [e for x in release[0].findall('build/plugins/plugin') if text(x, 'artifactId') == 'maven-enforcer-plugin'
         for e in x.findall('executions/execution') if text(e, 'phase') == 'deploy']
if len(guard) != 1:
    fail("uom-parent: the release profile must have one maven-enforcer-plugin execution bound to deploy, found %d" % len(guard))
if text(guard[0], 'configuration/skip') != 'false':
    fail("uom-parent: the release-version enforcer execution must set skip to the literal false, found '%s'"
         % text(guard[0], 'configuration/skip'))
for rule in ('requireReleaseVersion', 'requireReleaseDeps', 'requireMavenVersion'):
    if guard[0].find('configuration/rules/' + rule) is None:
        fail("uom-parent: the release-version enforcer execution lacks the rule " + rule)
# Maven 3.10.0 adds maven-metadata-local.xml to the Central bundle, which Central rejects
if text(guard[0], 'configuration/rules/requireMavenVersion/version') != '[3.9,3.10)':
    fail("uom-parent: the release-version enforcer must require Maven [3.9,3.10), found '%s'"
         % text(guard[0], 'configuration/rules/requireMavenVersion/version'))
plugin_ids = [text(x, 'artifactId') for x in release[0].findall('build/plugins/plugin')]
if plugin_ids.index('maven-enforcer-plugin') > plugin_ids.index('central-publishing-maven-plugin'):
    fail("uom-parent: the release-version enforcer must be declared before central-publishing-maven-plugin")

# a literal in each unpublished module, so no -D flag can include it in the Central bundle
for a in UNPUBLISHED:
    m = load(os.path.join(os.path.dirname(sys.argv[1]), a, 'pom.xml'))
    vals = [text(x, 'configuration/skipPublishing') for pr in m.findall('profiles/profile') if text(pr, 'id') == 'release'
            for x in pr.findall('build/plugins/plugin') if text(x, 'artifactId') == 'central-publishing-maven-plugin']
    if vals != ['true']:
        fail("%s: the release profile must set central-publishing skipPublishing to the literal true, found %s" % (a, vals))

default, rel = projects(load(sys.argv[2])), projects(load(sys.argv[3]))
for a in PUBLISHED + UNPUBLISHED:
    if a not in default or a not in rel:
        fail(a + ": missing from the effective POM")

expected = {
    'url': REPO,
    'licenses/license/name': 'Apache License, Version 2.0',
    'licenses/license/url': 'https://www.apache.org/licenses/LICENSE-2.0.txt',
    'licenses/license/distribution': 'repo',
    'developers/developer/id': 'netomi',
    'developers/developer/name': 'Thomas Neidhart',
    'developers/developer/email': 'thomas.neidhart@gmail.com',
    'scm/url': REPO,
    'scm/connection': 'scm:git:https://github.com/TinyGearsOrg/uom.git',
    'scm/developerConnection': 'scm:git:git@github.com:TinyGearsOrg/uom.git',
    'issueManagement/system': 'GitHub',
    'issueManagement/url': REPO + '/issues',
}
descriptions = set()
for a in PUBLISHED:
    p = default[a]
    for f in ('name', 'description', 'inceptionYear'):
        if not text(p, f):
            fail(a + ": " + f + " is empty")
    descriptions.add(text(p, 'description'))
    for path, want in expected.items():
        got = text(p, path)
        if got != want:
            fail("%s: %s is '%s', expected '%s'" % (a, path, got, want))
if len(descriptions) != len(PUBLISHED):
    fail("the published modules must each have their own description")

for mode, eff in (('default', default), ('release', rel)):
    for a, p in eff.items():
        ts = text(p, 'properties/project.build.outputTimestamp')
        if not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ', ts):
            fail("%s: project.build.outputTimestamp is '%s', expected an ISO-8601 instant" % (a, ts))
        for (g, aid), x in plugins(p).items():
            if not text(x, 'version'):
                fail("%s: %s: plugin %s has no version" % (a, mode, aid))
            if pinned.get((g, aid)) != text(x, 'version'):
                fail("%s: %s: plugin %s:%s is not pinned in the pluginManagement of uom-parent" % (a, mode, g, aid))
        enf = plugin(p, 'maven-enforcer-plugin')
        if enf is None:
            fail(a + ": maven-enforcer-plugin is not in the build")
        phases = ['validate'] if mode == 'default' else ['validate', 'deploy']
        if sorted(text(e, 'phase') for e in enf.findall('executions/execution')) != sorted(phases):
            fail("%s: %s: maven-enforcer-plugin executions must be bound to %s" % (a, mode, ' and '.join(phases)))
        # the build's rules; the release profile's deploy execution is checked above
        rules = {r.tag: r for e in enf.findall('executions/execution') if text(e, 'phase') == 'validate'
                 for r in e.findall('configuration/rules/*')}
        if 'enforce' not in [text(g, '.') for g in enf.findall('executions/execution/goals/goal')]:
            fail(a + ": maven-enforcer-plugin has no enforce goal")
        for rule, version in (('requireJavaVersion', '[17,)'), ('requireMavenVersion', '[3.9,)'),
                              ('banDuplicatePomDependencyVersions', None), ('dependencyConvergence', None)):
            if rule not in rules:
                fail("%s: enforcer rule %s is missing" % (a, rule))
            if version and text(rules[rule], 'version') != version:
                fail("%s: enforcer rule %s version is '%s', expected '%s'" % (a, rule, text(rules[rule], 'version'), version))
        if mode == 'default':
            for aid in RELEASE_ONLY:
                if plugin(p, aid) is not None:
                    fail("%s: %s is active without -Prelease" % (a, aid))
        else:
            for aid, goal in (('maven-source-plugin', 'jar-no-fork'), ('maven-javadoc-plugin', 'jar'),
                              ('maven-gpg-plugin', 'sign'), ('central-publishing-maven-plugin', None)):
                x = plugin(p, aid)
                if x is None:
                    fail("%s: release: %s is missing" % (a, aid))
                if goal and goal not in [text(g, '.') for g in x.findall('executions/execution/goals/goal')]:
                    fail("%s: release: %s has no %s goal" % (a, aid, goal))
            gpg = plugin(p, 'maven-gpg-plugin')
            if text(gpg, 'executions/execution/configuration/skip') != 'true':
                fail(a + ": release: maven-gpg-plugin must be skipped unless -Dgpg.skip=false")
            c = plugin(p, 'central-publishing-maven-plugin')
            for path, want in (('extensions', 'true'), ('configuration/publishingServerId', 'central'),
                               ('configuration/autoPublish', 'false'),
                               ('configuration/skipPublishing', 'true' if a in UNPUBLISHED else 'false')):
                if text(c, path) != want:
                    fail("%s: release: central-publishing %s is '%s', expected '%s'" % (a, path.split('/')[-1], text(c, path), want))
            # explicit values, so -D user properties cannot widen the upload
            for param, want in (('ignorePublishedComponents', 'false'), ('waitForPublishCompletion', 'false'),
                                ('failOnBuildFailure', 'true')):
                if text(c, 'configuration/' + param) != want:
                    fail("%s: release: central-publishing %s is '%s', expected '%s'" % (a, param, text(c, 'configuration/' + param), want))
            excluded = [text(e, '.') for e in c.findall('configuration/excludeArtifacts/*')]
            if sorted(excluded) != sorted(UNPUBLISHED):
                fail("%s: release: central-publishing excludeArtifacts is %s, expected %s" % (a, excluded, UNPUBLISHED))
            jd = plugin(p, 'maven-javadoc-plugin')
            for param, want in (('doclint', 'all'), ('failOnWarnings', 'true')):
                got = text(jd, 'executions/execution/configuration/' + param)
                if got != want:
                    fail("%s: release: maven-javadoc-plugin %s is '%s', expected '%s'" % (a, param, got, want))
        if p.find('distributionManagement') is not None:
            fail("%s: %s: distributionManagement must not be set (plain mvn deploy would upload)" % (a, mode))
        for e in p.findall('properties/*'):
            if e.tag.startswith(('altDeploymentRepository', 'altReleaseDeploymentRepository',
                                 'altSnapshotDeploymentRepository', 'maven.deploy.')):
                fail("%s: %s: property %s must not be set (deploy override)" % (a, mode, e.tag))
        for prop in ('maven.javadoc.skip', 'maven.source.skip'):
            if (text(p, 'properties/' + prop) == 'true') != (a in UNPUBLISHED):
                fail("%s: property %s is '%s', expected %s" % (a, prop, text(p, 'properties/' + prop), 'true' if a in UNPUBLISHED else 'unset'))
        if a in PUBLISHED:
            for d in p.findall('dependencies/dependency'):
                if text(d, 'version').endswith('-SNAPSHOT') and text(d, 'groupId') != 'org.tinygears':
                    fail("%s: %s: SNAPSHOT dependency %s:%s:%s" % (a, mode, text(d, 'groupId'), text(d, 'artifactId'), text(d, 'version')))
            # inactive profiles are kept verbatim in the effective POM
            for e in [e for c in p if c.tag != 'profiles' for e in c.iter()]:
                for v in [e.text or ''] + list(e.attrib.values()):
                    if '${' in v:
                        fail("%s: %s: unresolved expression '%s' in <%s>" % (a, mode, v.strip(), e.tag))
        if a in ('uom-engine', 'uom', 'uom-jsr') and mode == 'default':
            jar = plugin(p, 'maven-jar-plugin')
            for entry, want in (('Implementation-Title', text(p, 'name')), ('Implementation-Version', text(p, 'version')),
                                ('Implementation-Vendor', 'TinyGearsOrg')):
                got = text(jar, 'configuration/archive/manifestEntries/' + entry) if jar is not None else ''
                if got != want:
                    fail("%s: manifest entry %s is '%s', expected '%s'" % (a, entry, got, want))
        deploy = plugin(p, 'maven-deploy-plugin')
        skip = text(deploy, 'configuration/skip') if deploy is not None else ''
        if (skip == 'true') != (a in UNPUBLISHED):
            fail("%s: maven-deploy-plugin skip is '%s', expected %s" % (a, skip, 'true' if a in UNPUBLISHED else 'not true'))
print("check-pom: OK")
EOF
