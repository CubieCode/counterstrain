# Development environment

All development, builds, tests and visual checks run on the home server `foundry`.

## Machine

- Host `foundry`, Ubuntu 26.04, headless: no monitor, no desktop, no Xorg running.
- GPU: NVIDIA RTX 4070, driver 595.91.07 (headless server branch, open kernel module) plus `libnvidia-gl-595-server` for OpenGL/EGL.
- Work runs as user `lab`, which has GPU device access but **no sudo**. Never attempt system package installs or anything needing root. If something is missing, stop and report it.
- Self-hosted GitHub Actions runners on the same machine (labels `self-hosted`, `foundry`, `gpu`) run as `lab`, so the same commands work in CI.

## Java

- **SDKMAN** (per user, no root). Eclipse Temurin 21 is the default JDK for builds and CI; `.sdkmanrc` pins `java=21.0.12+1.1-tem`, so `sdk env` (or `sdk env install` on a new machine) selects it. The tools scripts call `sdk env` automatically when SDKMAN exists.
- The Gradle build declares a Java 21 toolchain with the foojay resolver as fallback, so it works without SDKMAN. Never assume a system JDK.
- **Optional:** JetBrains Runtime 21 for enhanced hot-swap during interactive runs: `sdk install java <21.x>-jbr` (see `sdk list java`), then `./gradlew runClient -Pdev_jbr=true`. Gradle finds the SDKMAN-installed JBR (or downloads one via foojay) and adds `-XX:+AllowEnhancedClassRedefinition`. Never required; builds, datagen, gametests and CI always use Temurin.

## Other user-level tools

- **packwiz:** no releases upstream; `tools/install-packwiz.sh` builds the pinned commit into `~/.local/bin`. It uses `go` from PATH or downloads the official Go tarball into `~/.cache/counterstrain/`.
- `python3` (standard library only) converts Xvfb framebuffer dumps to PNG.

## Rendering stack

- **Xvfb** provides a virtual X display; **VirtualGL 3.1.5** (`/opt/VirtualGL/bin`, EGL back end) redirects OpenGL to the 4070.
- Verified with `xvfb-run -a /opt/VirtualGL/bin/vglrun -d egl glxinfo -B`, which reports "NVIDIA GeForce RTX 4070".
- Nothing runs when idle: `xvfb-run` starts a display per command and tears it down afterwards.

## Launching the dev client

Use the wrapper:

```sh
tools/run-client-headless.sh --timeout 180 [--resolution 1920x1080]
```

It runs exactly:

```sh
xvfb-run -a -s "-screen 0 1920x1080x24" /opt/VirtualGL/bin/vglrun -d egl ./gradlew runClient --no-daemon
```

and adds the following:
- It runs in its own process group so teardown is complete.
- It sizes the game window to the display and skips the first-launch accessibility prompt.
- It dumps the final frame (Xvfb `-fbdir`) to `framebuffer.png`.
- It copies logs to `.local/runs/client-<UTC>/` (`.local/runs/latest` links to the newest run).
- It runs `tools/verify-gpu-render.sh` on the game log.

Rules:

- Always `--no-daemon` for client runs (or start the Gradle daemon itself under `vglrun`). A daemon started without `vglrun` launches the game without the GPU redirect: llvmpipe software rendering, or failure.
- Confirm GPU use from the game log: NeoForge's early display logs `GL info: NVIDIA GeForce RTX 4070/PCIe/SSE2 GL version 4.6.0 NVIDIA 595.91.07`. llvmpipe, or no renderer line, is a failed run.
- Server-side work (`build`, `runData`, `runGameTestServer`, `runServer`) needs no display wrapper.
- Until the phase 1 harness exists the client never quits by itself; the timeout is the normal end of a smoke run.

## Visual checks

No screenshot automation or live viewing (VNC etc.) exists yet. Visual evidence comes from:
- the final-frame `framebuffer.png` of a headless run;
- in-game screenshots (F2, saved to `mod/run/client/screenshots/`), collected with `tools/collect-screenshots.sh`;
- the phase 1 test harness, which will load fixed scenes, take screenshots and quit. See the visual-checks skill.

## Run and output locations

| Path | Contents |
| --- | --- |
| `mod/run/{client,server,gametest,data}/` | Game directories per run type (gitignored) |
| `.local/runs/client-<UTC>/` | `console.log`, `latest.log`, `debug.log`, `framebuffer.png`, `renderer.txt`, `result.txt` |
| `.local/screenshots/<UTC>/` | Collected screenshots for review |
| `.local/dist/` | Pack exports |
| `.local/instance/` | Default local test instance for `tools/build-mod.sh` |
