#!/usr/bin/env bash
# Tests prune_runs (tools/lib.sh): old folders go, the newest N, latest and
# other names stay, and 0 or a bad value turns pruning off.
set -euo pipefail
source "$(dirname "$0")/../lib.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

setup() {
  rm -rf "$tmp/runs"
  mkdir -p "$tmp/runs/keep-me"
  local i
  for i in 01 02 03 04 05 06; do
    mkdir -p "$tmp/runs/client-202610${i}T000000Z/screenshots"
    touch -d "-$((20 - 10#$i)) days -12 hours" "$tmp/runs/client-202610${i}T000000Z"
  done
  touch -d "-30 days" "$tmp/runs/keep-me"
  ln -sfn client-20261001T000000Z "$tmp/runs/latest"
}
expect() {
  local want=$1 got
  got="$(cd "$tmp/runs" && find . -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | LC_ALL=C sort | tr '\n' ' ')"
  if [[ "$got" != "$want" ]]; then
    printf 'FAIL %s\n  want: %s\n  got:  %s\n' "$2" "$want" "$got" >&2
    failures=$((failures + 1))
  fi
}

# Folders are 19.5..14.5 days old (half days, away from the boundary); keep the 2 newest, latest and anything not named client-.
setup
COUNTERSTRAIN_RUNS_KEEP=2 prune_runs "$tmp/runs" client- 2>/dev/null
expect "client-20261001T000000Z client-20261005T000000Z client-20261006T000000Z keep-me " "keeps the newest, latest and other names"

# Only folders older than the limit go: 17 days keeps those 14.5 to 16.5 days old.
setup
COUNTERSTRAIN_RUNS_KEEP=1 COUNTERSTRAIN_RUNS_KEEP_DAYS=17 prune_runs "$tmp/runs" client- 2>/dev/null
expect "client-20261001T000000Z client-20261004T000000Z client-20261005T000000Z client-20261006T000000Z keep-me " "spares folders younger than the limit"

all="client-20261001T000000Z client-20261002T000000Z client-20261003T000000Z client-20261004T000000Z client-20261005T000000Z client-20261006T000000Z keep-me "
for off in "COUNTERSTRAIN_RUNS_KEEP=0" "COUNTERSTRAIN_RUNS_KEEP_DAYS=0" "COUNTERSTRAIN_RUNS_KEEP=lots"; do
  setup
  env "$off" bash -c 'source "$1"; prune_runs "$2" client-' _ "$(dirname "$0")/../lib.sh" "$tmp/runs" 2>/dev/null
  expect "$all" "$off turns pruning off"
done

# The default (7 days, 30 newest) keeps all six.
setup
prune_runs "$tmp/runs" client- 2>/dev/null
expect "$all" "the default keeps the 30 newest"

((failures == 0)) && echo "prune-runs: ok" || { echo "prune-runs: $failures failure(s)" >&2; exit 1; }
