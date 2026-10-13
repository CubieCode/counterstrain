#!/usr/bin/env bash
# Tests gpu_lock (tools/lib.sh): a child script reuses a lock its parent holds,
# while an unrelated process waits for it and times out.
set -euo pipefail
lib="$(cd "$(dirname "$0")/.." && pwd)/lib.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export XDG_CACHE_HOME="$tmp"
unset COUNTERSTRAIN_GPU_LOCK
failures=0
check() {
  if [[ "$1" != "$2" ]]; then
    printf 'FAIL %s\n  want: %s\n  got:  %s\n' "$3" "$2" "$1" >&2
    failures=$((failures + 1))
  fi
}

# The parent holds the lock; a child script sourcing lib.sh takes it at once.
got="$(bash -c 'source "$1"; gpu_lock 5; bash -c '\''source "$1"; gpu_lock 0 && echo reused'\'' child "$1"; true' parent "$lib" 2>&1)"
check "$got" reused "a child reuses the inherited lock"

# ("; true" keeps the parent shell alive: bash -c would exec its last command.)
# Without the inherited fd (env var alone), another process waits and times out.
got="$(bash -c 'source "$1"; gpu_lock 5; bash -c '\''exec 9>&-; source "$1"; gpu_lock 1 && echo taken'\'' child "$1"; true' parent "$lib" 2>&1 || true)"
check "$(grep -c 'timed out waiting for the GPU lock' <<<"$got")" 1 "a process without the fd waits and times out"

# Once the holder exits, the lock is free again.
bash -c 'source "$1"; gpu_lock 5' holder "$lib"
got="$(bash -c 'source "$1"; gpu_lock 0 && echo free' later "$lib" 2>&1)"
check "$got" free "the lock is released when its holder exits"

((failures == 0)) && echo "gpu-lock: ok" || { echo "gpu-lock: $failures failure(s)" >&2; exit 1; }
