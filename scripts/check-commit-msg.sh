#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks that a commit subject has a conventional prefix of CONTRIBUTING.md (the commit-msg hook of
# .pre-commit-config.yaml). Merge, revert and fixup subjects that git writes itself pass.
# Usage: scripts/check-commit-msg.sh <commit message file> | --self-test
# Exits 1 with a message when the subject has no allowed prefix.
set -euo pipefail

SCRIPT=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
PREFIXES='feat|fix|perf|refactor|chore|docs|test|style|build|ci'

fail() { echo "check-commit-msg: FAIL: $*" >&2; exit 1; }

self_test() {
  local out status c expect text
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  # expected exit status|message
  local cases=(
    "0|feat: bound decimal values"
    "0|fix(engine): round once"
    "0|refactor!: rename the modules"
    $'0|# a comment git strips\ndocs: describe the hooks'
    "0|Merge branch 'ng' into native-prototype"
    "0|Revert \"feat: x\""
    "0|fixup! fix: y"
    "1|Add pre-commit hooks"
    "1|feature: x"
    "1|fix:missing space"
    "1|fix: "
    $'0|\n\nfeat: leading blank lines, which git strips'
    $'1|\n\nnot a subject'
  )
  for c in "${cases[@]}"; do
    expect=${c%%|*} text=${c#*|}
    printf '%s\n' "$text" > "$tmp/msg"
    set +e
    out=$(bash "$SCRIPT" "$tmp/msg" 2>&1)
    status=$?
    set -e
    [[ $status -eq $expect ]] || { echo "$out"; fail "self-test '$text': expected exit $expect, got $status"; }
  done
  echo "check-commit-msg: self-test passed"
}

[[ ${1:-} == --self-test ]] && { self_test; exit 0; }
[[ $# -eq 1 && -f $1 ]] || fail "usage: check-commit-msg.sh <commit message file> | --self-test"

# the first line that is neither a comment nor blank: git strips both before it records the message
subject=$(grep -v -m1 -E '^(#|[[:space:]]*$)' "$1" || true)
[[ $subject =~ ^(Merge |Revert \"|fixup! |squash! |amend! ) ]] && exit 0
[[ $subject =~ ^($PREFIXES)(\([a-z0-9-]+\))?!?:\ [^[:space:]] ]] \
  || fail "subject '$subject' lacks a prefix: $(tr '|' ' ' <<< "$PREFIXES") followed by ': ' (CONTRIBUTING.md)"
