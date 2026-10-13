#!/usr/bin/env bash
# Copy in-game screenshots (F2 or the phase 1 harness) out of a run directory
# into a timestamped review folder, plus the last headless framebuffer.
#
#   tools/collect-screenshots.sh [RUN_GAME_DIR] [--clear]
#
# RUN_GAME_DIR defaults to mod/run/client. --clear removes the copied
# screenshots from the run directory so the next run starts empty.
# Output: .local/screenshots/<UTC time>/ and .local/screenshots/latest
# -h or --help prints this text.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

game_dir="$mod_dir/run/client"
clear=0
for arg in "$@"; do
  case "$arg" in
    --clear) clear=1 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    -*) die "unknown option: $arg" ;;
    *) game_dir="$arg" ;;
  esac
done

src="$game_dir/screenshots"
out="$local_dir/screenshots/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$out"

count=0
if [[ -d "$src" ]]; then
  while IFS= read -r -d '' file; do
    cp -p "$file" "$out/"
    count=$((count + 1))
    ((clear)) && rm -f "$file"
  done < <(find "$src" -maxdepth 1 -type f -name '*.png' -print0 | sort -z)
fi
if [[ -f "$local_dir/runs/latest/framebuffer.png" ]]; then
  cp -p "$local_dir/runs/latest/framebuffer.png" "$out/headless-framebuffer.png"
fi
ln -sfn "$(basename "$out")" "$local_dir/screenshots/latest"
log "Collected $count screenshot(s) into $out"
