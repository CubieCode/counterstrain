---
name: headless-client
description: Launch the Remission dev client on the headless foundry server with GPU rendering (Xvfb + VirtualGL EGL on the RTX 4070), verify the renderer, find logs and fix llvmpipe fallbacks. Use for any client run, locally or in the GPU workflow.
---

# Headless client on foundry

foundry has no monitor, desktop or running Xorg. The RTX 4070 (driver 595.91.07, server branch) is reached through Xvfb plus VirtualGL 3.1.5 (`/opt/VirtualGL/bin`, EGL back end). User `lab` has GPU access and **no sudo**: never install packages or touch system config; if a tool is missing, stop and report it.

Always launch with the wrapper:

```sh
tools/run-client-headless.sh --timeout 180 [--resolution 1920x1080] [--no-framebuffer] [-- <extra gradle args>]
```

It runs `xvfb-run -a -s "-screen 0 WxHx24" /opt/VirtualGL/bin/vglrun -d egl ./gradlew runClient --no-daemon` from `mod/` in its own process group. It sizes the window to the display (`-Pclient_width/-Pclient_height`), sets `onboardAccessibility:false` in `mod/run/client/options.txt`, saves the last frame, stops the whole group at the timeout (TERM, then KILL after 30 s), checks nothing is left, and runs `tools/verify-gpu-render.sh`. The exit status is 0 only if the renderer is the GPU, nothing crashed, and teardown was clean. Until the P1.1 harness exists the client never quits by itself, so reaching the timeout is the normal successful end.

Outputs go to `.local/runs/client-<UTC>/`, with `.local/runs/latest` pointing to the newest run:
- `console.log`: Gradle and game output.
- `latest.log` / `debug.log`: copied from `mod/run/client/logs/`.
- `framebuffer.png`: last Xvfb frame.
- `renderer.txt`: renderer verdict.
- `result.txt`: reason, exit code, leftover processes and status.

A healthy run reaches the title screen about 10 s after the `GL info` line. Look at `framebuffer.png` to see where it stopped (loading warnings, crash screen, title).

Rules:
- `--no-daemon` always. A Gradle daemon started without `vglrun` launches the game without the GPU redirect, which gives llvmpipe or no GL context. Do not run `./gradlew --daemon runClient`, and do not reuse an IDE daemon. Starting the daemon itself under `vglrun` is the only alternative.
- Renderer proof is the game log line `GL info: NVIDIA GeForce RTX 4070/PCIe/SSE2 GL version 4.6.0 NVIDIA 595.91.07`. Treat llvmpipe, softpipe or a missing line as a failed run, whatever the screen shows. `tools/verify-gpu-render.sh LOG [pattern]` checks any log, including playtest instances.
- `build`, `runData`, `runGameTestServer` and `runServer` need no display wrapper.

Troubleshooting:
- **llvmpipe:** a daemon was involved (`./gradlew --stop`, then rerun), or `vglrun` was missing from the command. Check with `xvfb-run -a /opt/VirtualGL/bin/vglrun -d egl glxinfo -B`, which must print the RTX 4070.
- **No GL info line:** check `console.log` for an early crash, a Java version mismatch (`sdk env`) or a dependency resolution failure.
- **Stuck on a warning screen:** read `framebuffer.png`. Example: a Modrinth artifact resolving to a Fabric jar, fixed by pinning a version ID in `gradle.properties`.
- **Leftover processes:** `pgrep -a -g <pgid>`. Kill only processes from your own run, never other users' Xvfb displays.
- **Display number clashes:** `xvfb-run -a` picks a free one.

In CI, `gpu-smoke.yml` (manual dispatch, `runs-on: [self-hosted, foundry, gpu]`) runs the same script and uploads `.local/runs/client-*` and screenshots as artifacts.
