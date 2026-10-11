#!/usr/bin/env bash
# Compare a harness scene run's screenshots with the scene's reviewed references
# (tools/visual-references/<scene>/<shot>.png), writing <shot>-diff.png images and
# compare-result.txt into --out (visual-checks skill).
#
#   tools/compare-screenshots.sh --scene <id> --actual <run screenshots dir> --out <dir>
#                                [--threshold 24] [--max-diff-ratio 0.0005] [--accept]
#   tools/compare-screenshots.sh --self-test
#
# A pixel differs when any channel moves by more than --threshold (0-255); a shot
# passes when at most --max-diff-ratio of its pixels differ. --accept replaces the
# references with the actual shots: only for images someone has reviewed.
# Exit: 0 all shots within threshold, 1 at least one shot differs, 2 usage or
# tool error, 3 no reference exists for the scene.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
if [[ "${1:-}" == -h || "${1:-}" == --help ]]; then
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 0
fi
command -v python3 >/dev/null 2>&1 || { log "error: 'python3' not found on PATH"; exit 2; }
exec python3 -I "$repo_root/tools/compare-screenshots.py" "$@"
