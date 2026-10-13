#!/usr/bin/env bash
# Prepare an Orca worktree (one roadmap lane) on foundry, then check it.
#
#   tools/worktree-setup.sh ROOT_PATH WORKTREE_PATH
#
# Idempotent. ROOT_PATH is accepted for parity with Orca's hook but nothing is
# copied from it: no run directories, worlds, configs, instances or tokens.
# Each lane gets its own mod/run/ and .local/; the Gradle and packwiz caches in
# $HOME are shared safely. Client runs share one GPU through the lock in
# tools/run-client-headless.sh. -h or --help prints this text.
set -euo pipefail
if [[ "${1:-}" == -h || "${1:-}" == --help ]]; then
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 0
fi
root_path="${1:?Usage: worktree-setup.sh ROOT_PATH WORKTREE_PATH}"
worktree_path="${2:?Usage: worktree-setup.sh ROOT_PATH WORKTREE_PATH}"
cd "$worktree_path"
source tools/lib.sh
use_sdkman_java

command -v packwiz >/dev/null 2>&1 || tools/install-packwiz.sh

# A stable, lane-specific port so parallel `runServer`s do not collide.
# Generated once; never overwrite a lane's manual settings.
props="$mod_dir/run/server/server.properties"
if [[ ! -f "$props" ]]; then
  slot=$(( 16#$(printf '%s' "$(realpath "$worktree_path")" | sha256sum | cut -c1-6) % 10000 ))
  port=$(( 30000 + slot ))
  mkdir -p "$(dirname "$props")"
  printf 'server-port=%s\nmotd=Remission dev (%s)\n' "$port" "$(basename "$worktree_path")" >"$props"
  log "runServer port for this lane: $port (edit $props if it is taken)"
fi
mkdir -p "$local_dir"

(cd "$mod_dir" && ./gradlew build --console=plain)
tools/pack.sh check
log "Worktree ready: $worktree_path (root $root_path)"
