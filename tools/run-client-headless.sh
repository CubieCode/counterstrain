#!/usr/bin/env bash
# Launch the Remission dev client on foundry's virtual display with the GPU
# redirected through VirtualGL, then verify the renderer and tear it down.
#
#   tools/run-client-headless.sh [--scene <area/subject> [--scene-arg name=value]...]
#                                [--resolution 1920x1080]
#                                [--timeout 300] [--no-framebuffer]
#                                [--gpu-wait 1800] [--record [seconds]]
#                                [-- extra gradle args]
#
# Without --scene the client never quits by itself: reaching --timeout is the
# expected end of a smoke run (--timeout 0 waits for a manual exit).
# --scene runs a dev-only harness scene (mod/src/harness/resources/harness/
# scenes/<id>.json): the game builds the fixed superflat world, takes the
# scene's screenshots and quits; the run passes only if it exits 0 and the
# harness reports status=ok. --timeout (default 600 with --scene) is then only a
# safety bound, and reaching it fails the run. --scene-arg sets an argument
# the scene declares (its "args"; values in [a-z0-9_.:/-]), e.g.
# --scene items/held --scene-arg item=remission:scanner.
# Parallel Orca lanes and CI jobs share one RTX 4070, so runs take an
# exclusive lock (~/.cache/counterstrain/gpu.lock) and wait up to --gpu-wait
# seconds for it; the timeout starts once the lock is held. A lock inherited
# from tools/run-scenes.sh is used as is (gpu_lock in tools/lib.sh).
# SIGINT or SIGTERM stops the client (SIGTERM to its process group, SIGKILL
# after 5 s) and fails the run with reason=interrupted.
# --record captures the virtual display with ffmpeg (x11grab, H.264, at most
# 1280 px wide, 30 fps) from the moment the game window exists until the
# client exits, or for the given number of seconds. ffmpeg is stopped with
# SIGINT before Xvfb goes away, so the clip is always finalised. It needs an
# ffmpeg with x11grab and libx264 (foundry's system /usr/bin/ffmpeg; override
# with FFMPEG=/path); without one the script fails before taking the GPU lock.
# Recording does not touch the game's screenshots.
# If FML's early display fails (e.g. "Crash during font initialization"), its
# error dialog falls back to a console "y/n:" prompt that never gets an answer
# and repeats endlessly; the run is stopped as soon as that prompt appears
# (reason=early-display-error) instead of filling console.log until --timeout.
# Output: .local/runs/client-<UTC time>/ (and .local/runs/latest):
#   console.log      Gradle and game stdout/stderr
#   latest.log       the game's log (mod/run/client/logs/latest.log)
#   framebuffer.png  last frame of the virtual display, when captured
#   screenshots/     --scene only: <shot>.png and harness-result.txt
#   capture.mp4      --record only: the clip (capture.log holds ffmpeg output)
#   result.txt       renderer verdict and exit reason
# Run folders older than 7 days are deleted at the start of each run, keeping
# the 30 newest and latest (COUNTERSTRAIN_RUNS_KEEP_DAYS, COUNTERSTRAIN_RUNS_KEEP;
# 0 keeps everything). It happens under the GPU lock, so no run is writing one.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

resolution=1920x1080
timeout_s=
scene=
scene_args=()
framebuffer=1
gpu_wait=1800
record=0
record_s=0
gradle_args=()
while (($#)); do
  case "$1" in
    --scene) scene="${2:?}"; shift 2 ;;
    --scene-arg)
      [[ "${2:-}" =~ ^[a-z_][a-z0-9_]*=[a-z0-9_.:/-]+$ ]] || die "--scene-arg must look like name=value (value in [a-z0-9_.:/-])"
      scene_args+=("$2"); shift 2 ;;
    --resolution) resolution="${2:?}"; shift 2 ;;
    --timeout) timeout_s="${2:?}"; shift 2 ;;
    --no-framebuffer) framebuffer=0; shift ;;
    --gpu-wait) gpu_wait="${2:?}"; shift 2 ;;
    --record)
      record=1
      if [[ "${2:-}" =~ ^[0-9]+$ ]]; then record_s="$2"; shift; fi
      shift ;;
    --) shift; gradle_args=("$@"); break ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ "$resolution" =~ ^[0-9]+x[0-9]+$ ]] || die "resolution must look like 1920x1080"
if [[ -n "$scene" ]]; then
  [[ "$scene" =~ ^[a-z0-9_]+(/[a-z0-9_]+)*$ ]] || die "scene must look like area/subject"
  [[ -f "$mod_dir/src/harness/resources/harness/scenes/$scene.json" ]] || die "no such scene: $scene"
elif ((${#scene_args[@]})); then
  die "--scene-arg needs --scene"
fi
: "${timeout_s:=$([[ -n "$scene" ]] && echo 600 || echo 300)}"
[[ "$timeout_s" =~ ^[0-9]+$ ]] || die "timeout must be whole seconds"
[[ "$gpu_wait" =~ ^[0-9]+$ ]] || die "gpu-wait must be whole seconds"

vglrun="${VGLRUN:-/opt/VirtualGL/bin/vglrun}"
require xvfb-run setsid flock
[[ -x "$vglrun" ]] || die "VirtualGL not found at $vglrun"
use_sdkman_java

ffmpeg="${FFMPEG:-ffmpeg}"
if ((record)); then
  command -v "$ffmpeg" >/dev/null || die "--record needs ffmpeg, but '$ffmpeg' was not found (set FFMPEG to override)"
  devices="$("$ffmpeg" -hide_banner -devices 2>/dev/null)" || die "'$ffmpeg -devices' failed"
  encoders="$("$ffmpeg" -hide_banner -encoders 2>/dev/null)" || die "'$ffmpeg -encoders' failed"
  grep -qw x11grab <<<"$devices" || die "--record needs an ffmpeg with x11grab; '$ffmpeg' has none"
  grep -qw libx264 <<<"$encoders" || die "--record needs an ffmpeg with libx264; '$ffmpeg' has none"
fi

# Runs inside xvfb-run, so DISPLAY is the virtual display: starts the client
# command, starts ffmpeg once the game log shows the window's GL context and
# stops it with SIGINT when the client exits, before xvfb-run kills Xvfb.
record_display() {
  local clip=$1 seconds=$2 size=$3 game_log=$4 ffmpeg=$5 pid_file=$6
  shift 6
  "$@" &
  local client=$! rec= code=0 duration=()
  ((seconds > 0)) && duration=(-t "$seconds")
  while kill -0 "$client" 2>/dev/null; do
    if grep -q -a 'GL info: ' "$game_log" 2>/dev/null; then
      "$ffmpeg" -nostdin -hide_banner -loglevel warning \
        -f x11grab -draw_mouse 0 -framerate 30 -video_size "$size" -i "$DISPLAY" "${duration[@]}" \
        -vf "scale='min(1280,iw)':-2" -c:v libx264 -preset veryfast -crf 28 -pix_fmt yuv420p \
        -threads 2 -movflags +faststart -y "$clip" >"${clip%.*}.log" 2>&1 9>&- &
      rec=$!
      echo "$rec" >"$pid_file"
      break
    fi
    sleep 1
  done
  wait "$client" || code=$?
  if [[ -n "$rec" ]]; then
    kill -INT "$rec" 2>/dev/null
    wait "$rec"
  fi
  return "$code"
}

gpu_lock "$gpu_wait"

prune_runs "$local_dir/runs" client-
run_dir="$local_dir/runs/client-$(date -u +%Y%m%dT%H%M%SZ)"
fb_dir="$run_dir/.xvfb"
mkdir -p "$fb_dir"
ln -sfn "$(basename "$run_dir")" "$local_dir/runs/latest"
shots_dir="$run_dir/screenshots"
harness_args=()
[[ -n "$scene" ]] && harness_args=("-Pharness_scene=$scene" "-Pharness_output=$shots_dir")
((${#scene_args[@]})) && harness_args+=("-Pharness_args=$(IFS=,; echo "${scene_args[*]}")")
game_log="$mod_dir/run/client/logs/latest.log"
rm -f "$game_log"
# Skip the first-launch accessibility prompt so runs land on the title menu.
options="$mod_dir/run/client/options.txt"
mkdir -p "$(dirname "$options")"
if [[ ! -f "$options" ]]; then
  printf 'onboardAccessibility:false\n' >"$options"
elif ! grep -q '^onboardAccessibility:' "$options"; then
  printf 'onboardAccessibility:false\n' >>"$options"
fi

xvfb_server_args="-screen 0 ${resolution}x24"
((framebuffer)) && xvfb_server_args+=" -fbdir $fb_dir"
clip="$run_dir/capture.mp4"
recorder=()
((record)) && recorder=(bash -c "$(declare -f record_display); record_display \"\$@\"" record_display
  "$clip" "$record_s" "$resolution" "$game_log" "$ffmpeg" "$fb_dir/ffmpeg.pid")

log "Launching client at $resolution${scene:+, scene $scene}$( ((record)) && echo ", recording") (timeout ${timeout_s}s); output in $run_dir"
# --no-daemon is mandatory: a daemon started outside vglrun would launch the
# game without the GPU redirect (llvmpipe). setsid gives the whole tree one
# process group so teardown reaches Xvfb, Gradle and the game JVM.
(
  cd "$mod_dir"
  exec setsid xvfb-run -a -s "$xvfb_server_args" "${recorder[@]}" \
    "$vglrun" -d egl ./gradlew runClient --no-daemon --console=plain \
    "-Pclient_width=${resolution%x*}" "-Pclient_height=${resolution#*x}" "${harness_args[@]}" "${gradle_args[@]}"
) >"$run_dir/console.log" 2>&1 &
pid=$!
pgid=$pid
# SIGINT/SIGTERM (Ctrl+C, a cancelled CI job via tools/run-scenes.sh): stop the
# client within seconds instead of leaving it running with the GPU lock.
interrupted=0
trap 'interrupted=1' INT TERM

reason=exited
start=$SECONDS
while kill -0 "$pid" 2>/dev/null; do
  if ((interrupted)); then
    reason=interrupted
    break
  fi
  if ((timeout_s > 0 && SECONDS - start >= timeout_s)); then
    reason=timeout
    break
  fi
  # The early display's unanswerable error prompt (see the header).
  if grep -a -q -m1 -E '\[EARLYDISPLAY/\]: ERROR DISPLAY|^y/n: ' "$run_dir/console.log" 2>/dev/null; then
    reason=early-display-error
    break
  fi
  # A trapped signal ends the wait at once, not after the sleep.
  sleep 2 &
  wait $! || true
done

if [[ "$reason" != exited ]]; then
  # An interrupted run gets a short grace period: GitHub's runner sends SIGTERM
  # 7.5 s after SIGINT and kills the step soon after.
  grace=30
  if [[ "$reason" == timeout ]]; then
    log "Timeout reached; capturing and stopping the client"
  elif [[ "$reason" == interrupted ]]; then
    grace=5
    log "Interrupted; stopping the client"
  else
    log "error: the early display failed and is waiting on an error prompt; stopping the client"
  fi
  if ((framebuffer)) && [[ -f "$fb_dir/Xvfb_screen0" ]]; then
    python3 -I "$repo_root/tools/xwd-to-png.py" "$fb_dir/Xvfb_screen0" "$run_dir/framebuffer.png" \
      || log "framebuffer conversion failed"
  fi
  # Finalise the clip while Xvfb is still up.
  if [[ -f "$fb_dir/ffmpeg.pid" ]]; then
    rec_pid="$(<"$fb_dir/ffmpeg.pid")"
    kill -INT "$rec_pid" 2>/dev/null || true
    for _ in $(seq 15); do
      kill -0 "$rec_pid" 2>/dev/null || break
      sleep 1
    done
  fi
  kill -TERM -- "-$pgid" 2>/dev/null || true
  for _ in $(seq "$grace"); do
    pgrep -g "$pgid" >/dev/null || break
    sleep 1
  done
  if pgrep -g "$pgid" >/dev/null; then
    log "Processes still alive after ${grace}s; sending SIGKILL"
    kill -KILL -- "-$pgid" 2>/dev/null || true
    sleep 1
  fi
fi
wait "$pid" 2>/dev/null && exit_code=0 || exit_code=$?
if [[ "$reason" == early-display-error ]]; then
  # Keep the log readable: the prompt repeats thousands of times per second.
  head -c 16M "$run_dir/console.log" >"$run_dir/console.log.tmp" && mv "$run_dir/console.log.tmp" "$run_dir/console.log"
  grep -a -m1 -A1 -E 'Failure details:' "$run_dir/console.log" >&2 || true
  log "error: early display failure (see the EARLYDISPLAY lines in $run_dir/console.log); usually transient, rerun"
fi
rm -rf "$fb_dir"

leftover=0
if pgrep -g "$pgid" >/dev/null; then
  leftover=1
  log "error: processes from this run are still running:"
  pgrep -a -g "$pgid" >&2 || true
fi

[[ -f "$game_log" ]] && cp "$game_log" "$run_dir/latest.log"
[[ -f "$mod_dir/run/client/logs/debug.log" ]] && cp "$mod_dir/run/client/logs/debug.log" "$run_dir/debug.log"

status=0
renderer_log="$run_dir/latest.log"
[[ -f "$renderer_log" ]] || renderer_log="$run_dir/console.log"
"$repo_root/tools/verify-gpu-render.sh" "$renderer_log" >"$run_dir/renderer.txt" 2>&1 || status=1
cat "$run_dir/renderer.txt" >&2

[[ "$reason" == early-display-error || "$reason" == interrupted ]] && status=1
if [[ "$reason" == exited && "$exit_code" -ne 0 ]]; then
  log "error: client exited with code $exit_code (see $run_dir/console.log)"
  status=1
fi
if grep -q -E 'Crash report saved|---- Minecraft Crash Report ----' "$run_dir/console.log"; then
  log "error: the game crashed (see $run_dir/console.log)"
  status=1
fi
((leftover)) && status=1
capture=
if ((record)); then
  capture=missing
  if [[ -s "$clip" ]]; then
    capture=capture.mp4
    if command -v "${FFPROBE:-ffprobe}" >/dev/null; then
      info="$("${FFPROBE:-ffprobe}" -v error -select_streams v:0 -show_entries stream=width,height:format=duration \
        -of default=noprint_wrappers=1 "$clip" 2>/dev/null)" || capture=unreadable
      [[ "$capture" == capture.mp4 ]] && log "Clip $clip: $(tr '\n' ' ' <<<"$info")$(du -h "$clip" | cut -f1)"
    fi
  fi
  if [[ "$capture" != capture.mp4 ]]; then
    log "error: --record produced no usable clip ($capture; see $run_dir/capture.log)"
    status=1
  fi
fi
if [[ -n "$scene" ]]; then
  if [[ "$reason" == timeout ]]; then
    log "error: scene $scene did not finish within ${timeout_s}s"
    status=1
  fi
  if ! grep -qx 'status=ok' "$shots_dir/harness-result.txt" 2>/dev/null; then
    log "error: scene $scene did not report success (see $shots_dir/harness-result.txt and latest.log)"
    status=1
  fi
  [[ -f "$shots_dir/harness-result.txt" ]] && cat "$shots_dir/harness-result.txt" >&2
fi

{
  [[ -n "$scene" ]] && echo "scene=$scene"
  echo "reason=$reason"
  echo "exit_code=$exit_code"
  echo "leftover_processes=$leftover"
  [[ -n "$capture" ]] && echo "capture=$capture"
  echo "status=$status"
} >"$run_dir/result.txt"
log "Done: reason=$reason status=$status; logs in $run_dir"
exit "$status"
