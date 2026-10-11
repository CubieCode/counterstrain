#!/usr/bin/env bash
# Launch the Remission dev client on foundry's virtual display with the GPU
# redirected through VirtualGL, then verify the renderer and tear it down.
#
#   tools/run-client-headless.sh [--resolution 1920x1080] [--timeout 300]
#                                [--no-framebuffer] [--gpu-wait 1800]
#                                [-- extra gradle args]
#
# --timeout 0 waits for the game to exit by itself. Without a test harness
# (roadmap phase 1) the client never quits, so a timeout is normally needed;
# reaching it is the expected way for a smoke run to end.
# Parallel Orca lanes and CI jobs share one RTX 4070, so runs take an
# exclusive lock (~/.cache/counterstrain/gpu.lock) and wait up to --gpu-wait
# seconds for it; the timeout starts once the lock is held.
# Output: .local/runs/client-<UTC time>/ (and .local/runs/latest):
#   console.log      Gradle and game stdout/stderr
#   latest.log       the game's log (mod/run/client/logs/latest.log)
#   framebuffer.png  last frame of the virtual display, when captured
#   result.txt       renderer verdict and exit reason
set -euo pipefail
source "$(dirname "$0")/lib.sh"

resolution=1920x1080
timeout_s=300
framebuffer=1
gpu_wait=1800
gradle_args=()
while (($#)); do
  case "$1" in
    --resolution) resolution="${2:?}"; shift 2 ;;
    --timeout) timeout_s="${2:?}"; shift 2 ;;
    --no-framebuffer) framebuffer=0; shift ;;
    --gpu-wait) gpu_wait="${2:?}"; shift 2 ;;
    --) shift; gradle_args=("$@"); break ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ "$resolution" =~ ^[0-9]+x[0-9]+$ ]] || die "resolution must look like 1920x1080"
[[ "$timeout_s" =~ ^[0-9]+$ ]] || die "timeout must be whole seconds"
[[ "$gpu_wait" =~ ^[0-9]+$ ]] || die "gpu-wait must be whole seconds"

vglrun="${VGLRUN:-/opt/VirtualGL/bin/vglrun}"
require xvfb-run setsid flock
[[ -x "$vglrun" ]] || die "VirtualGL not found at $vglrun"
use_sdkman_java

lock_file="${XDG_CACHE_HOME:-$HOME/.cache}/counterstrain/gpu.lock"
mkdir -p "$(dirname "$lock_file")"
exec 9>"$lock_file"
if ! flock -n 9; then
  log "GPU busy (another lane or CI job holds $lock_file); waiting up to ${gpu_wait}s"
  flock -w "$gpu_wait" 9 || die "timed out waiting for the GPU lock"
fi

run_dir="$local_dir/runs/client-$(date -u +%Y%m%dT%H%M%SZ)"
fb_dir="$run_dir/.xvfb"
mkdir -p "$fb_dir"
ln -sfn "$(basename "$run_dir")" "$local_dir/runs/latest"
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

log "Launching client at $resolution (timeout ${timeout_s}s); output in $run_dir"
# --no-daemon is mandatory: a daemon started outside vglrun would launch the
# game without the GPU redirect (llvmpipe). setsid gives the whole tree one
# process group so teardown reaches Xvfb, Gradle and the game JVM.
(
  cd "$mod_dir"
  exec setsid xvfb-run -a -s "$xvfb_server_args" \
    "$vglrun" -d egl ./gradlew runClient --no-daemon --console=plain \
    "-Pclient_width=${resolution%x*}" "-Pclient_height=${resolution#*x}" "${gradle_args[@]}"
) >"$run_dir/console.log" 2>&1 &
pid=$!
pgid=$pid

reason=exited
start=$SECONDS
while kill -0 "$pid" 2>/dev/null; do
  if ((timeout_s > 0 && SECONDS - start >= timeout_s)); then
    reason=timeout
    break
  fi
  sleep 2
done

if [[ "$reason" == timeout ]]; then
  log "Timeout reached; capturing and stopping the client"
  if ((framebuffer)) && [[ -f "$fb_dir/Xvfb_screen0" ]]; then
    python3 -I "$repo_root/tools/xwd-to-png.py" "$fb_dir/Xvfb_screen0" "$run_dir/framebuffer.png" \
      || log "framebuffer conversion failed"
  fi
  kill -TERM -- "-$pgid" 2>/dev/null || true
  for _ in $(seq 30); do
    pgrep -g "$pgid" >/dev/null || break
    sleep 1
  done
  if pgrep -g "$pgid" >/dev/null; then
    log "Processes still alive after 30s; sending SIGKILL"
    kill -KILL -- "-$pgid" 2>/dev/null || true
    sleep 1
  fi
fi
wait "$pid" 2>/dev/null && exit_code=0 || exit_code=$?
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

if [[ "$reason" == exited && "$exit_code" -ne 0 ]]; then
  log "error: client exited with code $exit_code (see $run_dir/console.log)"
  status=1
fi
if grep -q -E 'Crash report saved|---- Minecraft Crash Report ----' "$run_dir/console.log"; then
  log "error: the game crashed (see $run_dir/console.log)"
  status=1
fi
((leftover)) && status=1

{
  echo "reason=$reason"
  echo "exit_code=$exit_code"
  echo "leftover_processes=$leftover"
  echo "status=$status"
} >"$run_dir/result.txt"
log "Done: reason=$reason status=$status; logs in $run_dir"
exit "$status"
