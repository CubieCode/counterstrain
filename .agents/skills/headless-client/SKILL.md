---
name: headless-client
description: Launch the Remission dev client on the headless foundry server with GPU rendering (Xvfb + VirtualGL EGL on the RTX 4070), verify the renderer, find logs and fix llvmpipe fallbacks. Use for any client run, locally or in the GPU workflow.
---

# Headless client on foundry

foundry has no monitor, desktop or running Xorg. The RTX 4070 (driver 595.91.07, server branch) is reached through Xvfb plus VirtualGL 3.1.5 (`/opt/VirtualGL/bin`, EGL back end). User `lab` has GPU access and **no sudo**: never install packages or touch system config; if a tool is missing, stop and report it.

Always launch with the wrapper:

```sh
tools/run-client-headless.sh --timeout 180 [--resolution 1920x1080] [--no-framebuffer] [-- <extra gradle args>]
tools/run-client-headless.sh --scene <area/subject> [--timeout 600]
```

It runs `xvfb-run -a -s "-screen 0 WxHx24" /opt/VirtualGL/bin/vglrun -d egl ./gradlew runClient --no-daemon` from `mod/` in its own process group. It sizes the window to the display (`-Pclient_width/-Pclient_height`), sets `onboardAccessibility:false` in `mod/run/client/options.txt`, saves the last frame, stops the whole group at the timeout (TERM, then KILL after 30 s), checks nothing is left, and runs `tools/verify-gpu-render.sh`. It first takes the shared GPU lock (`~/.cache/counterstrain/gpu.lock`), waiting up to `--gpu-wait` seconds (default 1800), because parallel Orca lanes and CI jobs share the one RTX 4070; never bypass the lock or run `vglrun` clients outside the script while another run may be active. The exit status is 0 only if the renderer is the GPU, nothing crashed, and teardown was clean. Without `--scene` the client never quits by itself, so reaching the timeout is the normal successful end.

`--scene <id>` runs a dev-only harness scene (`mod/src/harness/resources/harness/scenes/<id>.json`, format in visual-checks). It passes `-Pharness_scene`/`-Pharness_output` to `runClient`, which set `remission.harness.scene`/`remission.harness.output`; the game builds the scene, saves the screenshots and exits by itself. The run passes only if the game exits 0 and `screenshots/harness-result.txt` says `status=ok`; reaching `--timeout` (default 600 with `--scene`) fails it, and the harness has its own 300 s watchdog. A scene run takes about 40 s once the GPU lock is held.

Outputs go to `.local/runs/client-<UTC>/`, with `.local/runs/latest` pointing to the newest run:
- `console.log`: Gradle and game output.
- `latest.log` / `debug.log`: copied from `mod/run/client/logs/`.
- `framebuffer.png`: last Xvfb frame (timeout runs only; a scene run exits before capture).
- `screenshots/`: `--scene` only, `<shot>.png` per shot plus `harness-result.txt` (scene, status, shots, failure message).
- `renderer.txt`: renderer verdict.
- `capture.mp4` / `capture.log`: `--record` only.
- `result.txt`: reason, exit code, leftover processes, capture and status.

`--record [seconds]` records the display to `capture.mp4` (plus `capture.log`). A wrapper inside `xvfb-run` starts ffmpeg (`x11grab` on the run's display, outside `vglrun`, H.264 `veryfast` CRF 28, at most 1280 px wide, 30 fps, 2 threads) once `latest.log` shows the `GL info` line, and stops it with SIGINT when the client exits, before Xvfb goes away, so the MP4 is finalised; a timeout also stops ffmpeg first. With a number, ffmpeg stops after that many seconds. ffmpeg is foundry's system `/usr/bin/ffmpeg` (8.0.1, host-installed; the project needs no root); `FFMPEG=/path` overrides it. Without ffmpeg, `x11grab` or `libx264` the script fails before taking the GPU lock; a requested clip that ends up missing or unreadable fails the run (`capture=` in `result.txt`). Recording does not change the game's screenshots. Compare stills with `tools/compare-screenshots.sh` (visual-checks).

A healthy run reaches the title screen about 10 s after the `GL info` line. Look at `framebuffer.png` to see where it stopped (loading warnings, crash screen, title).

Rules:
- `--no-daemon` always. A Gradle daemon started without `vglrun` launches the game without the GPU redirect, which gives llvmpipe or no GL context. Do not run `./gradlew --daemon runClient`, and do not reuse an IDE daemon. Starting the daemon itself under `vglrun` is the only alternative.
- Renderer proof is the game log line `GL info: NVIDIA GeForce RTX 4070/PCIe/SSE2 GL version 4.6.0 NVIDIA 595.91.07`. Treat llvmpipe, softpipe or a missing line as a failed run, whatever the screen shows. `tools/verify-gpu-render.sh LOG [pattern]` checks any log, including playtest instances.
- `build`, `runData`, `runGameTestServer` and `runServer` need no display wrapper.

Troubleshooting:
- **llvmpipe:** a daemon was involved (kill only that daemon's PID, found with `pgrep -af GradleDaemon`, then rerun; never `--stop`, below), or `vglrun` was missing from the command. Check with `xvfb-run -a /opt/VirtualGL/bin/vglrun -d egl glxinfo -B`, which must print the RTX 4070.
- **No GL info line:** check `console.log` for an early crash, a Java version mismatch (`sdk env`) or a dependency resolution failure.
- **Stuck on a warning screen:** read `framebuffer.png`. Example: a Modrinth artifact resolving to a Fabric jar, fixed by pinning a version ID in `gradle.properties`.
- **Leftover processes:** `pgrep -a -g <pgid>`. Kill only processes from your own run, never other users' Xvfb displays.
- **Display number clashes:** `xvfb-run -a` picks a free one.
- **Exit code 134 and `hs_err_pid*.log` naming `libEGL_nvidia.so.0`:** the NVIDIA driver segfaults during libc `exit()` under VirtualGL EGL, so any normal quit of the client crashes there. The harness avoids it by ending with libc `_exit` after flushing logs; a manual quit still shows it.
- **"Gradle build daemon has been stopped: stop command received":** someone ran `./gradlew --stop`, which also kills the single-use daemon behind `--no-daemon`. Rerun. Never run `./gradlew --stop` (or kill other Java/Gradle processes) on foundry: daemons are shared by every lane and CI job of the same user and Gradle version, so it kills their builds and client runs too.
- **Early display error (`reason=early-display-error`):** FML's early loading window failed (seen once as `Crash during font initialization` / `FileSystemNotFoundException` in `EARLYDISPLAY`) and its error dialog, with no dialog tool on foundry, fell back to a console `y/n:` prompt that repeats forever. The script stops the run within seconds, trims `console.log` to 16 MB and fails; the crash has been transient, so rerun.
- **`Disk quota exceeded` in `console.log` (harness `could not write …`):** `/tmp` (a tmpfs) has a per-user quota; free space there (large downloaded CI artifacts are the usual cause) and rerun.

Scene lists: `tools/run-scenes.sh [--scenes "a/b c/d"] [--list] [--resolution …] [--timeout …]` runs every scene under `mod/src/harness/resources/harness/scenes/` (or the given IDs) through the wrapper one after another, keeps going after a failure, and exits 1 if any scene failed. It compares each passing scene's screenshots with `tools/compare-screenshots.sh` (visual-checks): exit 1 (differs) or 2 (error) fails the scene, 3 (no reference) is only reported. Output: `.local/scene-runs/<UTC>/` (`summary.tsv`, `summary.md`, per-scene diff directories, `latest` symlink); each client run keeps its own `.local/runs/client-<UTC>/`.

In CI, `gpu-smoke.yml` (`runs-on: [self-hosted, foundry, gpu]`) runs `tools/run-scenes.sh` on manual dispatch (inputs `scenes`, `resolution`, `scene_timeout`; `gh workflow run gpu-smoke.yml --ref <branch>`) or on a same-repo PR labelled `gpu-smoke` (when labelled and on each push while the label stays). It fails on llvmpipe, a crashed or timed-out scene, a harness status other than ok, or a screenshot diff, writes the summary table to the job summary and uploads `.local/runs/client-*/` and `.local/scene-runs/` as the `gpu-smoke-<run id>-<attempt>` artifact. The job takes the same GPU lock as local lanes, so it may wait for their client runs.
