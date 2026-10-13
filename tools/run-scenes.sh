#!/usr/bin/env bash
# Run harness scenes one after another through tools/run-client-headless.sh
# --scene, compare their screenshots with tools/compare-screenshots.sh and
# write a summary. Used by gpu-smoke.yml; works the same locally.
#
#   tools/run-scenes.sh [--scenes "area/a area/b"] [--list]
#                       [--resolution 1920x1080] [--timeout 600] [--gpu-wait 1800]
#                       [--record [seconds]]
#
# Without --scenes every scene under mod/src/harness/resources/harness/scenes/
# runs, in sorted order (--scenes takes IDs separated by spaces or commas).
# --list prints the scene IDs and exits. --record is passed to every client run
# (each clip lands in that run's capture.mp4).
# Every scene runs even after a failure. A scene fails if the client run fails
# (GPU renderer not found, crash, timeout, harness status other than ok) or its
# comparison exits 1 (differs) or 2 (error); exit 3 (no reference) is reported,
# not failed. Exit status: 0 if every scene passed, 1 otherwise.
# Output: .local/scene-runs/<UTC time>/ (and .local/scene-runs/latest):
#   summary.tsv      scene, verdict, run, compare result per scene
#   summary.md       the same as a Markdown table (also appended to
#                    $GITHUB_STEP_SUMMARY when set)
#   <scene>/         compare output (<shot>-diff.png, compare-result.txt),
#                    with "/" in the scene ID replaced by "__"
# Each client run keeps its own .local/runs/client-<UTC>/ directory. Summaries
# older than 7 days are pruned like client runs (tools/run-client-headless.sh).
set -euo pipefail
source "$(dirname "$0")/lib.sh"

scenes_root="$mod_dir/src/harness/resources/harness/scenes"
scenes=()
list=0
client_args=()
while (($#)); do
  case "$1" in
    --scenes) read -r -a scenes <<<"${2//,/ }"; shift 2 ;;
    --list) list=1; shift ;;
    --resolution|--timeout|--gpu-wait) client_args+=("$1" "${2:?}"); shift 2 ;;
    --record)
      client_args+=(--record)
      if [[ "${2:-}" =~ ^[0-9]+$ ]]; then client_args+=("$2"); shift; fi
      shift ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

if ((${#scenes[@]} == 0)); then
  [[ -d "$scenes_root" ]] || die "no scene directory at $scenes_root"
  while IFS= read -r file; do
    id="${file#"$scenes_root"/}"
    scenes+=("${id%.json}")
  done < <(find "$scenes_root" -type f -name '*.json' | LC_ALL=C sort)
  ((${#scenes[@]})) || die "no scenes found under $scenes_root"
fi
if ((list)); then
  printf '%s\n' "${scenes[@]}"
  exit 0
fi

prune_runs "$local_dir/scene-runs" 2
out="$local_dir/scene-runs/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$out"
ln -sfn "$(basename "$out")" "$local_dir/scene-runs/latest"
compare="$repo_root/tools/compare-screenshots.sh"

printf 'scene\tverdict\trun\tcompare\n' >"$out/summary.tsv"
failed=0
index=0
for scene in "${scenes[@]}"; do
  index=$((index + 1))
  log "Scene $index/${#scenes[@]}: $scene"
  before="$(readlink "$local_dir/runs/latest" 2>/dev/null || true)"
  verdict=pass
  "$repo_root/tools/run-client-headless.sh" --scene "$scene" "${client_args[@]}" || verdict=fail
  run="$(readlink "$local_dir/runs/latest" 2>/dev/null || true)"
  # The client script exits before creating a run directory on argument or
  # lock errors; don't attribute an older run to this scene.
  [[ -n "$run" && "$run" != "$before" ]] || run=-

  comparison=skipped
  if [[ "$verdict" == pass ]]; then
    scene_out="$out/${scene//\//__}"
    mkdir -p "$scene_out"
    code=0
    "$compare" --scene "$scene" --actual "$local_dir/runs/$run/screenshots" --out "$scene_out" || code=$?
    case "$code" in
      0) comparison=match ;;
      1) comparison=differs; verdict=fail ;;
      3) comparison=no-reference ;;
      *) comparison="error($code)"; verdict=fail ;;
    esac
  fi
  [[ "$verdict" == pass ]] || failed=$((failed + 1))
  printf '%s\t%s\t%s\t%s\n' "$scene" "$verdict" "$run" "$comparison" >>"$out/summary.tsv"
  log "Scene $scene: $verdict (run $run, compare $comparison)"
done

{
  echo "### Harness scenes: $(( ${#scenes[@]} - failed ))/${#scenes[@]} passed"
  echo
  echo "| Scene | Verdict | Run | Compare |"
  echo "| --- | --- | --- | --- |"
  tail -n +2 "$out/summary.tsv" | while IFS=$'\t' read -r scene verdict run comparison; do
    echo "| \`$scene\` | $verdict | \`$run\` | $comparison |"
  done
} >"$out/summary.md"
[[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && cat "$out/summary.md" >>"$GITHUB_STEP_SUMMARY"
column -t -s $'\t' "$out/summary.tsv" >&2 || cat "$out/summary.tsv" >&2
log "Done: $failed of ${#scenes[@]} scene(s) failed; summary in $out"
((failed == 0))
