#!/usr/bin/env bash
# Set the mod or pack version. The two are versioned independently.
#
#   tools/bump-version.sh mod 0.2.0     edits mod/gradle.properties (mod_version)
#   tools/bump-version.sh pack 0.2.0    edits pack/pack.toml (version) and refreshes
#
# It does not commit or tag; see the release skill for the tag step
# (mod-v<version> / pack-v<version>). -h or --help prints this text.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
if [[ "${1:-}" == -h || "${1:-}" == --help ]]; then
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 0
fi

target="${1:-}"
version="${2:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "version must be semver, e.g. 0.2.0 or 0.2.0-beta.1"

case "$target" in
  mod)
    file="$mod_dir/gradle.properties"
    old="$(sed -n 's/^mod_version=//p' "$file")"
    sed -i "s/^mod_version=.*/mod_version=$version/" "$file"
    ;;
  pack)
    file="$pack_dir/pack.toml"
    old="$(sed -n 's/^version = "\(.*\)"/\1/p' "$file")"
    sed -i "s/^version = \".*\"/version = \"$version\"/" "$file"
    require packwiz
    (cd "$pack_dir" && packwiz refresh)
    ;;
  *)
    die "usage: bump-version.sh mod|pack VERSION"
    ;;
esac
log "$target: $old -> $version"
log "Next: add a $target changelog entry, commit, then tag $target-v$version"
