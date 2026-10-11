#!/usr/bin/env bash
# Build packwiz at the pinned commit into ~/.local/bin (or $PACKWIZ_BIN_DIR).
# packwiz publishes no releases, so it is built from source. Go comes from
# PATH if present, otherwise the official tarball is unpacked into a per-user
# cache. Nothing here needs root.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

bin_dir="${PACKWIZ_BIN_DIR:-$HOME/.local/bin}"
go_version="${GO_VERSION:-1.27.2}"
cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/counterstrain"

if command -v go >/dev/null 2>&1; then
  go_bin="$(command -v go)"
else
  go_root="$cache_dir/go-$go_version"
  if [[ ! -x "$go_root/bin/go" ]]; then
    log "Downloading Go $go_version to $go_root"
    mkdir -p "$cache_dir"
    tmp="$(mktemp -d)"
    curl -fsSL "https://go.dev/dl/go$go_version.linux-amd64.tar.gz" | tar -xz -C "$tmp"
    mv "$tmp/go" "$go_root"
    rmdir "$tmp"
  fi
  go_bin="$go_root/bin/go"
fi

mkdir -p "$bin_dir"
log "Building packwiz@$PACKWIZ_COMMIT into $bin_dir"
GOBIN="$bin_dir" "$go_bin" install "github.com/packwiz/packwiz@$PACKWIZ_COMMIT"
"$bin_dir/packwiz" --help >/dev/null
log "packwiz installed: $bin_dir/packwiz"
