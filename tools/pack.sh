#!/usr/bin/env bash
# Refresh, check or export the Counterstrain packwiz pack.
#
#   tools/pack.sh refresh     rehash and rewrite index.toml
#   tools/pack.sh check       fail if refresh would change anything (CI)
#   tools/pack.sh export      write Modrinth (.mrpack) and CurseForge (.zip)
#                             files to .local/dist/
#
# Remission is never part of the packwiz index; exports do not contain it
# until it is published and added with `packwiz mr add` / `packwiz cf add`.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
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
  *)
    sed -n '2,10p' "$self" >&2
    exit 2
    ;;
esac
