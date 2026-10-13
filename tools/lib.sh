# Shared helpers for tools/*.sh. Source it; do not execute it.
# shellcheck shell=bash

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mod_dir="$repo_root/mod"
pack_dir="$repo_root/pack"
# Gitignored scratch area for logs, test instances and screenshots.
local_dir="$repo_root/.local"

# packwiz has no tagged releases; CI and tools/install-packwiz.sh build this commit.
PACKWIZ_COMMIT="ef87d964f8cbd52b3b13ea42453ef322290e2b9e"

log() { printf '[%s] %s\n' "$(basename "$0")" "$*" >&2; }
die() { log "error: $*"; exit 1; }

require() {
  local cmd
  for cmd in "$@"; do
    command -v "$cmd" >/dev/null 2>&1 || die "'$cmd' not found on PATH (see docs/DEV-ENVIRONMENT.md)"
  done
}

# Select the JDK from .sdkmanrc when SDKMAN is installed. Gradle's toolchain
# (with the foojay resolver) still provides Java 21 without it.
use_sdkman_java() {
  local init="${SDKMAN_DIR:-$HOME/.sdkman}/bin/sdkman-init.sh"
  [[ -s "$init" ]] || return 0
  set +u
  # shellcheck disable=SC1090
  source "$init"
  (cd "$repo_root" && sdk env >/dev/null) || log "sdk env failed; using the current JDK"
  set -u
}

# Delete old run folders: entries of DIR named PREFIX* whose last change is more
# than COUNTERSTRAIN_RUNS_KEEP_DAYS days ago (default 7), always keeping the
# COUNTERSTRAIN_RUNS_KEEP newest by name (default 30; names are UTC times) and
# the one DIR/latest points to. 0 in either variable turns pruning off.
prune_runs() {
  local dir=$1 prefix=$2
  local days="${COUNTERSTRAIN_RUNS_KEEP_DAYS:-7}" keep="${COUNTERSTRAIN_RUNS_KEEP:-30}"
  if [[ ! "$days" =~ ^[0-9]+$ || ! "$keep" =~ ^[0-9]+$ ]]; then
    log "warning: COUNTERSTRAIN_RUNS_KEEP_DAYS and COUNTERSTRAIN_RUNS_KEEP must be whole numbers; not pruning"
    return 0
  fi
  ((days > 0 && keep > 0)) && [[ -d "$dir" ]] || return 0
  local latest name count=0 removed=0
  latest="$(readlink "$dir/latest" 2>/dev/null || true)"
  while IFS= read -r name; do
    count=$((count + 1))
    ((count > keep)) || continue
    [[ "$name" != "$latest" ]] || continue
    [[ -n "$(find "$dir/$name" -maxdepth 0 -mmin "+$((days * 1440))")" ]] || continue
    rm -rf -- "${dir:?}/$name"
    removed=$((removed + 1))
  done < <(find "$dir" -mindepth 1 -maxdepth 1 -type d -name "$prefix*" -printf '%f\n' | LC_ALL=C sort -r)
  ((removed == 0)) || log "Pruned $removed run folder(s) older than $days days from $dir (the newest $keep are always kept)"
}

# Take the exclusive GPU lock (~/.cache/counterstrain/gpu.lock) on file
# descriptor 9, waiting up to WAIT seconds, or die. Child scripts inherit the
# held lock through fd 9 and COUNTERSTRAIN_GPU_LOCK, so tools/run-scenes.sh can
# hold it for a whole scene list while each tools/run-client-headless.sh run
# skips taking it again (reopening the file would wait on its own parent).
gpu_lock() {
  local wait=$1
  local lock_file="${XDG_CACHE_HOME:-$HOME/.cache}/counterstrain/gpu.lock"
  if [[ "${COUNTERSTRAIN_GPU_LOCK:-}" == "$lock_file" && "$(readlink /proc/$$/fd/9 2>/dev/null)" == "$lock_file" ]]; then
    return 0
  fi
  mkdir -p "$(dirname "$lock_file")"
  exec 9>"$lock_file"
  if ! flock -n 9; then
    log "GPU busy (another lane or CI job holds $lock_file); waiting up to ${wait}s"
    flock -w "$wait" 9 || die "timed out waiting for the GPU lock"
  fi
  export COUNTERSTRAIN_GPU_LOCK="$lock_file"
}
