#!/usr/bin/env bash
# Refresh, check, export or serve the Counterstrain packwiz pack.
#
#   tools/pack.sh refresh     rehash and rewrite index.toml
#   tools/pack.sh check       fail if refresh would change anything (CI)
#   tools/pack.sh export      write Modrinth (.mrpack) and CurseForge (.zip)
#                             files to .local/dist/
#   tools/pack.sh serve [PORT]
#                             serve the pack over HTTP for packwiz-installer
#                             (default port $COUNTERSTRAIN_PACK_PORT or 8080);
#                             a PC syncs from it with tools/pack-sync.sh
#
# Remission is never part of the packwiz index; exports do not contain it
# until it is published and added with `packwiz mr add` / `packwiz cf add`.
# -h or --help prints this text; no or an unknown command prints it and exits 2.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
if [[ "${1:-}" == -h || "${1:-}" == --help ]]; then
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 0
fi
require packwiz

self="$(realpath "$0")"
cmd="${1:-}"
cd "$pack_dir"
case "$cmd" in
  refresh)
    packwiz refresh
    ;;
  check)
    require git
    packwiz refresh
    if ! git -C "$repo_root" diff --exit-code -- pack; then
      die "pack/ is out of date; run tools/pack.sh refresh and commit the result"
    fi
    if [[ -n "$(git -C "$repo_root" ls-files --others --exclude-standard -- pack)" ]]; then
      git -C "$repo_root" ls-files --others --exclude-standard -- pack >&2
      die "untracked files in pack/; commit or ignore them"
    fi
    log "pack index is current"
    ;;
  export)
    packwiz refresh
    version="$(sed -n 's/^version = "\(.*\)"/\1/p' pack.toml)"
    dist="$local_dir/dist"
    mkdir -p "$dist"
    packwiz modrinth export -o "$dist/counterstrain-$version.mrpack"
    packwiz curseforge export -o "$dist/counterstrain-$version.zip"
    log "Exported to $dist"
    ;;
  serve)
    port="${2:-${COUNTERSTRAIN_PACK_PORT:-8080}}"
    log "Serving http://$(hostname):$port/pack.toml (index.toml refreshes on each request; Ctrl-C stops)"
    exec packwiz serve --port "$port"
    ;;
  *)
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$self" >&2
    exit 2
    ;;
esac
