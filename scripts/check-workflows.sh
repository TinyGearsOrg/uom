#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Statically checks the GitHub configuration (milestone 7, D6): the CI workflow builds and checks only (no deploy,
# no secrets, read-only token, actions pinned to a commit hash) and runs the commands of CONTRIBUTING.md; the release
# workflow only creates the GitHub release of a version tag, with the job token; dependabot.yml.
# Usage: scripts/check-workflows.sh [--self-test]
# Requires: bash, python3 with PyYAML.
# Exits 1 with a message on the first failed check; --self-test mutates copies and asserts each is detected.
set -euo pipefail

SCRIPT=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
ROOT=${CHECK_WORKFLOWS_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}

fail() { echo "check-workflows: FAIL: $*" >&2; exit 1; }

self_test() {
  local status out c name mutation expect
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  # name|python mutation of the copy|expected message fragment (no "|" inside a field)
  local cases=(
    "baseline||"
    "secret-reference|sub(CI, 'run: mvn -B verify', 'run: mvn -B verify -Dtoken=\${{ secrets.TOKEN }}')|ci.yml references secrets"
    "github-token|sub(CI, 'run: git diff --exit-code', 'run: echo \${{ github.token }}')|ci.yml references secrets"
    "pull-request-target|sub(CI, 'pull_request:', 'pull_request_target:')|triggers are ['pull_request_target', 'push'], expected ['pull_request', 'push']"
    "extra-trigger|sub(CI, 'pull_request:', 'pull_request:\n  workflow_dispatch:')|triggers are ['pull_request', 'push', 'workflow_dispatch']"
    "write-permission|sub(CI, 'contents: read', 'contents: write')|permissions are {'contents': 'write'}, expected {'contents': 'read'}"
    "job-permission|sub(CI, '    timeout-minutes: 20', '    timeout-minutes: 20\n    permissions: write-all')|job generated sets permissions"
    "deploy|sub(CI, 'run: mvn -B verify', 'run: mvn -B -Prelease deploy')|job build runs a forbidden command: 'mvn -B -Prelease deploy'"
    "release-profile|sub(CI, 'run: mvn -B verify', 'run: mvn -B -Prelease verify')|job build runs a forbidden command: 'mvn -B -Prelease verify'"
    "git-push|sub(CI, 'run: git diff --exit-code', 'run: git diff --exit-code && git push origin HEAD')|job generated runs a forbidden command"
    "gh-release|sub(CI, 'run: git diff --exit-code', 'run: git diff --exit-code\n      - run: gh release create v1')|job generated runs a forbidden command: 'gh release create v1"
    "unpinned-action|sub(CI, 'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1', 'actions/checkout@v7')|action 'actions/checkout@v7' is not an allowed action pinned to a commit hash"
    "short-hash|sub(CI, 'checkout@3d3c42e5aac5ba805825da76410c181273ba90b1', 'checkout@3d3c42e5')|action 'actions/checkout@3d3c42e5 # v7.0.1' is not an allowed action pinned to a commit hash"
    "no-version-comment|sub(CI, 'b1 # v7.0.1', 'b1')|action 'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1' is not an allowed action pinned to a commit hash"
    "third-party-action|sub(CI, 'actions/setup-java@', 'someone/setup-java@')|action 'someone/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1' is not an allowed action"
    "no-timeout|sub(CI, '    timeout-minutes: 20\n    steps:\n      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1\n        with:\n          persist-credentials: false\n      # Spotless', '    steps:\n      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1\n        with:\n          persist-credentials: false\n      # Spotless')|job analyze has no timeout-minutes"
    "no-concurrency|drop(CI, 'concurrency:', '== \'pull_request\' }}\n')|concurrency group must contain github.ref"
    "push-all-branches|sub(CI, '  push:\n    branches: [main]\n', '  push:\n')|push must be filtered to branches ['main'], is None"
    "push-other-branch|sub(CI, 'branches: [main]', 'branches: [main, ng]')|push must be filtered to branches ['main'], is {'branches': ['main', 'ng']}"
    "trigger-list|sub(CI, '  push:\n    branches: [main]\n  pull_request:\n', '  [push, pull_request]\n')|push must be filtered to branches ['main'], is None"
    "pull-request-filter|sub(CI, '  pull_request:\n', '  pull_request:\n    branches: [main]\n')|pull_request must not be filtered"
    "cancel-all|sub(CI, \"cancel-in-progress: \${{ github.event_name == 'pull_request' }}\", 'cancel-in-progress: true')|cancel-in-progress must be"
    "matrix|sub(CI, 'java: [17, 21, 25]', 'java: [21, 25]')|build matrix java is [21, 25], expected [17, 21, 25]"
    "distribution|sub(CI, 'distribution: temurin', 'distribution: zulu')|setup-java distribution is 'zulu'"
    "missing-job|drop(CI, '  generated:', 'git status --porcelain)\"\n')|jobs are ['analyze', 'build'], expected ['analyze', 'build', 'generated']"
    "prek-unpinned|sub(CI, 'prek-version: 0.5.5', 'prek-version: latest')|job analyze: prek-version is 'latest', expected an exact version"
    "prek-tag|sub(CI, 'j178/prek-action@1382ad72dea72f9d53832f2bcb65b3e87a947204 # v3.0.1', 'j178/prek-action@v3')|action 'j178/prek-action@v3' is not an allowed action pinned to a commit hash"
    "no-javadoc-check|sub(CI, '      - run: bash scripts/check-javadoc.sh\n', '')|the run commands differ from the CI block of CONTRIBUTING.md"
    "contributing-drift|sub('CONTRIBUTING.md', 'mvn -B verify\n', 'mvn -B install\n')|the run commands differ from the CI block of CONTRIBUTING.md"
    "extra-workflow|add('.github/workflows/deploy.yml', 'name: deploy\n')|workflow files are ['ci.yml', 'deploy.yml', 'release.yml'], expected ['ci.yml', 'release.yml']"
    "release-trigger|sub(RL, \"tags: ['v[0-9]+.[0-9]+.[0-9]+']\", 'branches: [main]')|release.yml: triggers are {'push': {'branches': ['main']}}"
    "release-top-write|sub(RL, 'permissions:\n  contents: read', 'permissions:\n  contents: write')|release.yml: permissions are {'contents': 'write'}, expected {'contents': 'read'}"
    "release-job-write|sub(RL, '    permissions:\n      contents: write', '    permissions: write-all')|release.yml: job github-release permissions are write-all, expected {'contents': 'write'}"
    "release-fork|sub(RL, \"    if: github.repository == 'TinyGearsOrg/uom'\n\", '')|release.yml: job github-release must run only in TinyGearsOrg/uom"
    "release-secret|sub(RL, 'GH_TOKEN: \${{ github.token }}', 'GH_TOKEN: \${{ secrets.PAT }}')|release.yml references secrets"
    "release-deploy|sub(RL, 'run: gh release create', 'run: mvn -B -Prelease deploy && gh release create')|release.yml: job github-release runs a forbidden command"
    "release-injection|sub(RL, 'notes \"\${TAG#v}\"', 'notes \"\${{ github.ref_name }}\"')|release.yml: a run command expands an expression"
    "release-commands|sub(RL, ' --verify-tag', '')|release.yml: the run commands are"
    "release-action|sub(RL, 'actions/checkout@', 'someone/checkout@')|release.yml: action 'someone/checkout@"
    "yaml-error|sub(CI, 'permissions:', 'permissions: [')|ci.yml does not parse"
    "dependabot-ecosystem|drop(DB, '  - package-ecosystem: github-actions', 'patterns: [\"*\"]\n')|dependabot ecosystems are ['maven'], expected ['github-actions', 'maven']"
    "untracked-drift|sub(CI, ' && test -z', ' ; test -z')|the run commands differ from the CI block of CONTRIBUTING.md"
    "persist-credentials|sub(CI, 'persist-credentials: false', 'persist-credentials: true')|job build: checkout must set persist-credentials: false"
    "job-environment|sub(CI, '    name: analyze (prek hooks)\n', '    name: analyze (prek hooks)\n    environment: production\n')|job analyze sets environment"
    "reusable-workflow|sub(CI, '  analyze:\n', '  analyze:\n    uses: someone/x/.github/workflows/a.yml@v1\n')|job analyze sets uses"
    "central-property|sub(CI, 'run: mvn -B verify', 'run: mvn -B verify -Dcentral.skipPublishing=false')|job build runs a forbidden command"
    "jdk-outside-matrix|sub(CI, 'java-version: 21', 'java-version: 25')|job generated: java-version is 25, expected 21"
    "cache-outside-build|sub(CI, 'java-version: 21\n', 'java-version: 21\n          cache: maven\n')|job generated: setup-java must not set cache"
    "dependabot-groups|drop(DB, '    groups:', 'patterns: [\"*\"]\n')|dependabot maven: needs directories ['/', '/scripts/consumer'], groups and open-pull-requests-limit"
    "dependabot-consumer|sub(DB, ', \"/scripts/consumer\"', '')|dependabot maven: needs directories ['/', '/scripts/consumer']"
    "dependabot-interval|sub(DB, 'interval: weekly', 'interval: daily')|dependabot maven: interval is 'daily', expected 'weekly'"
    "dependabot-eclipse|sub(DB, '      - dependency-name: \"org.eclipse.platform:*\"\n', '')|dependabot maven: must ignore ['org.eclipse.jdt:*', 'org.eclipse.platform:*']"
    "dependabot-cooldown|sub(DB, 'default-days: 7', 'default-days: 3')|dependabot maven: cooldown default-days is 3, expected at least 7"
  )
  for c in "${cases[@]}"; do
    IFS='|' read -r name mutation expect <<< "$c"
    rm -rf "$tmp/copy" && mkdir -p "$tmp/copy"
    cp -r "$ROOT/.github" "$ROOT/CONTRIBUTING.md" "$tmp/copy/"
    if [[ -n $mutation ]]; then
      python3 - "$tmp/copy" <<EOF
import sys, pathlib
root = pathlib.Path(sys.argv[1])
CI, DB, RL = '.github/workflows/ci.yml', '.github/dependabot.yml', '.github/workflows/release.yml'
def edit(f, fn):
    p = root / f; t = p.read_text(); n = fn(t)
    if n == t: sys.exit("self-test: mutation did not change " + f)
    p.write_text(n)
def drop(f, start, end):
    edit(f, lambda t: t[:t.index(start)] + t[t.index(end, t.index(start)) + len(end):])
def sub(f, old, new):
    edit(f, lambda t: t.replace(old, new, 1))
def add(f, text):
    (root / f).write_text(text)
$mutation
EOF
    fi
    set +e
    out=$(CHECK_WORKFLOWS_ROOT="$tmp/copy" bash "$SCRIPT" 2>&1)
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
  echo "check-workflows: self-test passed"
}

if [[ ${1:-} == --self-test ]]; then self_test; exit 0; fi
[[ $# -eq 0 ]] || fail "usage: check-workflows.sh [--self-test]"

python3 - "$ROOT" <<'EOF'
import pathlib, re, sys
try:
    import yaml
except ImportError:
    print("check-workflows: FAIL: python3 module yaml (PyYAML) not found", file=sys.stderr); sys.exit(1)

def fail(msg):
    print("check-workflows: FAIL: " + msg, file=sys.stderr); sys.exit(1)

root = pathlib.Path(sys.argv[1])
wf_dir = root / '.github/workflows'
files = sorted(p.name for p in wf_dir.iterdir()) if wf_dir.is_dir() else []
if files != ['ci.yml', 'release.yml']:
    fail("workflow files are %s, expected ['ci.yml', 'release.yml'] (no deploy workflow)" % files)

def load(rel):
    p = root / rel
    if not p.is_file():
        fail(rel + " is missing")
    text = p.read_text()
    try:
        return text, yaml.safe_load(text)
    except yaml.YAMLError as e:
        fail("%s does not parse: %s" % (p.name, str(e).splitlines()[0]))

text, ci = load('.github/workflows/ci.yml')
if not isinstance(ci, dict):
    fail("ci.yml is not a mapping")
if re.search(r'\bsecrets\b|github\.token|GITHUB_TOKEN', text):
    fail("ci.yml references secrets or the token")

# YAML 1.1 reads the key 'on' as true
on = ci.get('on', ci.get(True))
triggers = sorted(on) if isinstance(on, dict) else sorted([on] if isinstance(on, str) else on or [])
if triggers != ['pull_request', 'push']:
    fail("triggers are %s, expected ['pull_request', 'push']" % triggers)
if ci.get('permissions') != {'contents': 'read'}:
    fail("permissions are %s, expected {'contents': 'read'}" % ci.get('permissions'))
filters = on if isinstance(on, dict) else {}
if filters.get('push') != {'branches': ['main']}:
    fail("push must be filtered to branches ['main'], is %s" % filters.get('push'))
if filters.get('pull_request') is not None:
    fail("pull_request must not be filtered, is %s" % filters.get('pull_request'))
concurrency = ci.get('concurrency') or {}
if '${{ github.ref }}' not in str(concurrency.get('group', '')):
    fail("concurrency group must contain github.ref")
# only pull-request runs are cancelled, so every commit on main gets a complete run
CANCEL = "${{ github.event_name == 'pull_request' }}"
if concurrency.get('cancel-in-progress') != CANCEL:
    fail("cancel-in-progress must be %s, is %s" % (CANCEL, concurrency.get('cancel-in-progress')))

jobs = ci.get('jobs') or {}
if sorted(jobs) != ['analyze', 'build', 'generated']:
    fail("jobs are %s, expected ['analyze', 'build', 'generated']" % sorted(jobs))
FORBIDDEN = re.compile(r'deploy|-Prelease|publish|\bgpg\b|\bgit\s+(push|tag)\b|\bgh\s+release\b|central', re.I)
# a full commit hash with the release it is, as Dependabot writes it: tags can move, hashes cannot
ACTION = re.compile(r'(actions/(checkout|setup-java)|j178/prek-action)@[0-9a-f]{40} # v\d+\.\d+\.\d+')
commands = []
for name, job in jobs.items():
    for key in ('permissions', 'environment', 'secrets', 'uses', 'container', 'services'):
        if key in job:
            fail("job %s sets %s" % (name, key))
    if not isinstance(job.get('timeout-minutes'), int) or job['timeout-minutes'] <= 0:
        fail("job %s has no timeout-minutes" % name)
    if job.get('runs-on') != 'ubuntu-latest':
        fail("job %s runs on %s, expected ubuntu-latest" % (name, job.get('runs-on')))
    for step in job.get('steps') or []:
        if 'uses' in step:
            with_ = step.get('with') or {}
            if step['uses'].startswith('actions/checkout@') and with_.get('persist-credentials') is not False:
                fail("job %s: checkout must set persist-credentials: false" % name)
            # a new prek release must not change the hooks' results unannounced
            if step['uses'].startswith('j178/prek-action@') and not re.fullmatch(
                    r'\d+\.\d+\.\d+', str(with_.get('prek-version', ''))):
                fail("job %s: prek-version is '%s', expected an exact version" % (name, with_.get('prek-version')))
            if step['uses'].startswith('actions/setup-java@'):
                if with_.get('distribution') != 'temurin':
                    fail("job %s: setup-java distribution is '%s', expected 'temurin'" % (name, with_.get('distribution')))
                java = with_.get('java-version')
                if name != 'build' and str(java) != '21':
                    fail("job %s: java-version is %s, expected 21" % (name, java))
                # only build fills ~/.m2; the others would save a partial cache under the same key
                if name != 'build' and 'cache' in with_:
                    fail("job %s: setup-java must not set cache" % name)
        for line in str(step.get('run', '')).splitlines():
            if line.strip():
                if FORBIDDEN.search(line):
                    fail("job %s runs a forbidden command: '%s'" % (name, line.strip()))
                commands.append(line.strip())
# the version comment is not in the parsed YAML, so the pins are checked on the text
USES = re.compile(r'^\s*-?\s*uses:\s*(.*?)\s*$', re.M)
for uses in USES.findall(text):
    if not ACTION.fullmatch(uses):
        fail("action '%s' is not an allowed action pinned to a commit hash with its version" % uses)
matrix = ((jobs['build'].get('strategy') or {}).get('matrix') or {}).get('java')
if matrix != [17, 21, 25]:
    fail("build matrix java is %s, expected [17, 21, 25]" % matrix)

# the fenced block after "run what the CI runs" in CONTRIBUTING.md, in job order
contrib = (root / 'CONTRIBUTING.md').read_text()
m = re.search(r'run what the CI runs.*?\n```\n(.*?)```', contrib, re.S)
documented = [l.strip() for l in m.group(1).splitlines() if l.strip()] if m else []
if commands != documented:
    fail("the run commands differ from the CI block of CONTRIBUTING.md:\n  ci.yml:       %s\n  CONTRIBUTING: %s"
         % (commands, documented))

# release.yml creates the GitHub release of a pushed version tag, with the job token only and nothing built or uploaded
text, rl = load('.github/workflows/release.yml')
if not isinstance(rl, dict):
    fail("release.yml is not a mapping")
if re.search(r'\bsecrets\b|GITHUB_TOKEN', text) or text.count('github.token') != 1 \
        or 'GH_TOKEN: ${{ github.token }}' not in text:
    fail("release.yml references secrets, or the token other than as GH_TOKEN of the release step")
on = rl.get('on', rl.get(True))
if on != {'push': {'tags': ['v[0-9]+.[0-9]+.[0-9]+']}}:
    fail("release.yml: triggers are %s, expected {'push': {'tags': ['v[0-9]+.[0-9]+.[0-9]+']}}" % on)
if rl.get('permissions') != {'contents': 'read'}:
    fail("release.yml: permissions are %s, expected {'contents': 'read'}" % rl.get('permissions'))
rjobs = rl.get('jobs') or {}
if sorted(rjobs) != ['github-release']:
    fail("release.yml: jobs are %s, expected ['github-release']" % sorted(rjobs))
job = rjobs['github-release']
for key in ('environment', 'secrets', 'uses', 'container', 'services'):
    if key in job:
        fail("release.yml: job github-release sets %s" % key)
if job.get('permissions') != {'contents': 'write'}:
    fail("release.yml: job github-release permissions are %s, expected {'contents': 'write'}" % job.get('permissions'))
if job.get('if') != "github.repository == 'TinyGearsOrg/uom'":
    fail("release.yml: job github-release must run only in TinyGearsOrg/uom (if: %s)" % job.get('if'))
if job.get('runs-on') != 'ubuntu-latest' or not isinstance(job.get('timeout-minutes'), int):
    fail("release.yml: job github-release needs runs-on ubuntu-latest and timeout-minutes")
RELEASE_FORBIDDEN = re.compile(r'deploy|-Prelease|publish|\bgpg\b|\bgit\s+(push|tag)\b|central|\bmvn\b', re.I)
release_commands = []
for step in job.get('steps') or []:
    if 'uses' in step:
        if not step['uses'].startswith('actions/checkout@'):
            fail("release.yml: action '%s' is not actions/checkout" % step['uses'])
        if (step.get('with') or {}).get('persist-credentials') is not False:
            fail("release.yml: checkout must set persist-credentials: false")
    run = str(step.get('run', ''))
    if run:
        if RELEASE_FORBIDDEN.search(run):
            fail("release.yml: job github-release runs a forbidden command: '%s'" % run.strip())
        if '${{' in run:
            fail("release.yml: a run command expands an expression; pass it through env")
        release_commands.append(run.strip())
for uses in re.findall(r'^\s*-?\s*uses:\s*(.*?)\s*$', text, re.M):
    if not re.fullmatch(r'actions/checkout@[0-9a-f]{40} # v\d+\.\d+\.\d+', uses):
        fail("release.yml: action '%s' is not actions/checkout pinned to a commit hash with its version" % uses)
EXPECTED_RELEASE = [
    'bash scripts/release.sh notes "${TAG#v}" > "$RUNNER_TEMP/notes.md"',
    'gh release create "$TAG" --verify-tag --title "uom ${TAG#v}" --notes-file "$RUNNER_TEMP/notes.md"',
]
if release_commands != EXPECTED_RELEASE:
    fail("release.yml: the run commands are %s, expected %s" % (release_commands, EXPECTED_RELEASE))

text, db = load('.github/dependabot.yml')
if not isinstance(db, dict) or db.get('version') != 2:
    fail("dependabot.yml: version must be 2")
updates = {u.get('package-ecosystem'): u for u in db.get('updates') or []}
if sorted(updates) != ['github-actions', 'maven']:
    fail("dependabot ecosystems are %s, expected ['github-actions', 'maven']" % sorted(updates))
for eco, u in sorted(updates.items()):
    interval = (u.get('schedule') or {}).get('interval')
    if interval != 'weekly':
        fail("dependabot %s: interval is '%s', expected 'weekly'" % (eco, interval))
    # the Eclipse formatter jars follow the Spotless lockfile, never a version of their own
    if eco == 'maven':
        ignored = sorted(i.get('dependency-name') for i in u.get('ignore') or [])
        if ignored != ['org.eclipse.jdt:*', 'org.eclipse.platform:*']:
            fail("dependabot maven: must ignore ['org.eclipse.jdt:*', 'org.eclipse.platform:*'], ignores %s" % ignored)
    days = (u.get('cooldown') or {}).get('default-days')
    if not isinstance(days, int) or days < 7:
        fail("dependabot %s: cooldown default-days is %s, expected at least 7" % (eco, days))
    where = u.get('directories') if eco == 'maven' else u.get('directory')
    want = ['/', '/scripts/consumer'] if eco == 'maven' else '/'
    if where != want or not u.get('groups') or not isinstance(u.get('open-pull-requests-limit'), int):
        fail("dependabot %s: needs directories %s, groups and open-pull-requests-limit" % (eco, want))
print("check-workflows: OK")
EOF
