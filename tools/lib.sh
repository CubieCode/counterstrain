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
    command -v "$cmd" >/dev/null 2>&1 || die "'$cmd' not found on PATH (see docs/dev-environment.md)"
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
