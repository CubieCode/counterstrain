#!/usr/bin/env bash
# Build Remission and copy the jar into a local test instance's mods folder.
#
#   tools/build-mod.sh [INSTANCE_DIR]
#
# INSTANCE_DIR defaults to $COUNTERSTRAIN_INSTANCE or .local/instance; point it
# at a launcher instance's game directory (the one containing mods/). Older
# remission-*.jar files there are replaced. -h or --help prints this text.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
if [[ "${1:-}" == -h || "${1:-}" == --help ]]; then
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 0
fi

instance="${1:-${COUNTERSTRAIN_INSTANCE:-$local_dir/instance}}"
use_sdkman_java

(cd "$mod_dir" && ./gradlew build --console=plain)

version="$(sed -n 's/^mod_version=//p' "$mod_dir/gradle.properties")"
jar="$mod_dir/build/libs/remission-$version.jar"
[[ -f "$jar" ]] || die "expected jar not found: $jar"

mkdir -p "$instance/mods"
find "$instance/mods" -maxdepth 1 -name 'remission-*.jar' -delete
cp "$jar" "$instance/mods/"
log "Installed $(basename "$jar") into $instance/mods"
