#!/usr/bin/env bash
# Run harness scenes one after another in one game session (repeated --scene
# of tools/run-client-headless.sh), compare their screenshots with
# tools/compare-screenshots.sh and write a summary. Used by gpu-smoke.yml;
# works the same locally.
#
#   tools/run-scenes.sh [--scenes "area/a area/b"] [--list] [--isolated]
#                       [--resolution 1920x1080] [--timeout 600] [--gpu-wait 1800]
#                       [--record [seconds]]
#
# Without --scenes every scene under mod/src/harness/resources/harness/scenes/
# runs, in sorted order (--scenes takes IDs separated by spaces or commas).
# --isolated gives every scene its own game launch instead (slower: Gradle and
# game start-up per scene; for telling a leak between scenes from a real
# change). If the game dies in a scene (crash, timeout), that scene fails and
# the scenes after it run in a new launch.
# --list prints the scene IDs and exits. --record is passed to every client run
# (each launch's clip lands in its run's capture.mp4).
# The GPU lock is taken once and held for the whole list (--gpu-wait as in
# tools/run-client-headless.sh), so another lane or CI job cannot slip in
# between scenes. SIGINT or SIGTERM stops the running scene's client, skips
# the rest and fails the run (an "interrupted" summary row).
# Every scene runs even after a failure. A scene fails if its harness status
# is not ok, the game stopped in it, the GPU renderer was not found, or its
# comparison exits 1 (differs) or 2 (error); exit 3 (no reference) is reported,
# not failed. A launch that fails after all its scenes passed (leftover
# processes, a bad clip) adds a failing "(launch)" row.
# Exit status: 0 if every scene and launch passed, 1 otherwise.
# Output: .local/scene-runs/<UTC time>/ (and .local/scene-runs/latest):
#   summary.tsv      scene, verdict, run, compare result per scene
#   summary.md       the same as a Markdown table (also appended to
#                    $GITHUB_STEP_SUMMARY when set)
#   <scene>/         compare output (<shot>-diff.png, compare-result.txt),
#                    with "/" in the scene ID replaced by "__"
# Each launch keeps its own .local/runs/client-<UTC>/ directory. Summaries
# older than 7 days are pruned like client runs (tools/run-client-headless.sh).
set -euo pipefail
source "$(dirname "$0")/lib.sh"

scenes_root="$mod_dir/src/harness/resources/harness/scenes"
scenes=()
list=0
isolated=0
gpu_wait=1800
client_args=()
while (($#)); do
  case "$1" in
    --scenes) read -r -a scenes <<<"${2//,/ }"; shift 2 ;;
    --list) list=1; shift ;;
    --isolated) isolated=1; shift ;;
    --resolution|--timeout) client_args+=("$1" "${2:?}"); shift 2 ;;
    --gpu-wait) gpu_wait="${2:?}"; shift 2 ;;
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

[[ "$gpu_wait" =~ ^[0-9]+$ ]] || die "gpu-wait must be whole seconds"
require flock
gpu_lock "$gpu_wait"
# SIGINT/SIGTERM (Ctrl+C, a cancelled CI job): pass SIGTERM to the running
# client script, which stops the game, then skip the remaining scenes. The
# client runs in the background so the trap fires at once; background jobs of
# a script ignore SIGINT, hence SIGTERM.
interrupted=0
child=
trap 'interrupted=1; [[ -n "$child" ]] && kill -TERM "$child" 2>/dev/null' INT TERM

prune_runs "$local_dir/scene-runs" 2
out="$local_dir/scene-runs/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$out"
ln -sfn "$(basename "$out")" "$local_dir/scene-runs/latest"
compare="$repo_root/tools/compare-screenshots.sh"

printf 'scene\tverdict\trun\tcompare\n' >"$out/summary.tsv"
passed=0
recorded=0
launch_failures=0
launches=0

# Records one scene's verdict, comparing its screenshots when it passed.
record_scene() {
  local scene=$1 verdict=$2 run=$3 actual=$4 comparison=skipped code=0
  if [[ "$verdict" == pass ]]; then
    local scene_out="$out/${scene//\//__}"
    mkdir -p "$scene_out"
    "$compare" --scene "$scene" --actual "$actual" --out "$scene_out" || code=$?
    case "$code" in
      0) comparison=match ;;
      1) comparison=differs; verdict=fail ;;
      3) comparison=no-reference ;;
      *) comparison="error($code)"; verdict=fail ;;
    esac
  fi
  [[ "$verdict" == pass ]] && passed=$((passed + 1))
  recorded=$((recorded + 1))
  printf '%s\t%s\t%s\t%s\n' "$scene" "$verdict" "$run" "$comparison" >>"$out/summary.tsv"
  log "Scene $scene: $verdict (run $run, compare $comparison)"
}

remaining=("${scenes[@]}")
while ((${#remaining[@]})) && ! ((interrupted)); do
  batch=("${remaining[@]}")
  ((isolated)) && batch=("${remaining[0]}")
  launches=$((launches + 1))
  log "Launch $launches: $(( ${#batch[@]} )) scene(s), $recorded/${#scenes[@]} done so far: ${batch[*]}"
  before="$(readlink "$local_dir/runs/latest" 2>/dev/null || true)"
  scene_flags=()
  for scene in "${batch[@]}"; do scene_flags+=(--scene "$scene"); done
  "$repo_root/tools/run-client-headless.sh" "${scene_flags[@]}" "${client_args[@]}" &
  child=$!
  code=0
  # wait returns early when a trapped signal arrives; keep waiting for the
  # client script to finish stopping the game.
  while :; do
    wait "$child" && code=0 || code=$?
    kill -0 "$child" 2>/dev/null || break
  done
  child=
  run="$(readlink "$local_dir/runs/latest" 2>/dev/null || true)"
  # The client script exits before creating a run directory on argument or
  # lock errors; don't attribute an older run to these scenes.
  if [[ -z "$run" || "$run" == "$before" ]]; then
    for scene in "${batch[@]}"; do record_scene "$scene" fail - -; done
    remaining=("${remaining[@]:${#batch[@]}}")
    continue
  fi
  renderer="$(sed -n 's/^renderer=//p' "$local_dir/runs/$run/result.txt" 2>/dev/null)"
  # The harness records a scene it times out before quitting (Gradle hides
  # its exit code 3).
  timed_out=0
  grep -rqs --include=harness-result.txt '^message=timed out after' "$local_dir/runs/$run/screenshots" && timed_out=1
  next=()
  died_in=
  scene_failed=0
  for scene in "${batch[@]}"; do
    actual="$local_dir/runs/$run/screenshots"
    ((${#batch[@]} > 1)) && actual="$actual/$scene"
    if [[ ! -f "$actual/harness-result.txt" ]]; then
      ((interrupted)) && break
      # Unless a scene timed out (it has its result), the first scene without
      # a result is the one the game stopped in (a crash); the rest run again
      # in a new launch.
      if [[ -z "$died_in" ]] && ! ((timed_out)); then
        died_in=$scene
        scene_failed=1
        record_scene "$scene" fail "$run" "$actual"
      else
        next+=("$scene")
      fi
      continue
    fi
    verdict=fail
    grep -qx 'status=ok' "$actual/harness-result.txt" && [[ "$renderer" == ok ]] && verdict=pass
    ((${#batch[@]} == 1 && code != 0)) && verdict=fail
    [[ "$verdict" == fail ]] && scene_failed=1
    record_scene "$scene" "$verdict" "$run" "$actual"
  done
  # A failed launch whose scenes all passed (leftover processes, a crash on
  # the way out, a bad --record clip) still fails the run.
  if ((code != 0 && !scene_failed && !interrupted)); then
    launch_failures=$((launch_failures + 1))
    printf '(launch)\tfail\t%s\tclient run failed, see result.txt\n' "$run" >>"$out/summary.tsv"
    log "Launch $launches failed after its scenes passed (see $local_dir/runs/$run/result.txt)"
  fi
  remaining=("${next[@]}")
done

if ((interrupted)); then
  printf 'interrupted\tfail\t-\tskipped %s scene(s)\n' "$(( ${#scenes[@]} - recorded ))" >>"$out/summary.tsv"
fi
{
  echo "### Harness scenes: $passed/${#scenes[@]} passed"
  echo
  echo "| Scene | Verdict | Run | Compare |"
  echo "| --- | --- | --- | --- |"
  tail -n +2 "$out/summary.tsv" | while IFS=$'\t' read -r scene verdict run comparison; do
    echo "| \`$scene\` | $verdict | \`$run\` | $comparison |"
  done
} >"$out/summary.md"
[[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && cat "$out/summary.md" >>"$GITHUB_STEP_SUMMARY"
column -t -s $'\t' "$out/summary.tsv" >&2 || cat "$out/summary.tsv" >&2
log "Done: $passed of ${#scenes[@]} scene(s) passed in $launches launch(es); summary in $out"
((passed == ${#scenes[@]} && launch_failures == 0))
