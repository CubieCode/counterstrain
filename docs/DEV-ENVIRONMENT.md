# Development environment

All development, builds, tests and visual checks run on the home server `foundry`.

## Machine

- Host `foundry`, Ubuntu 26.04, headless: no monitor, no desktop, no Xorg running.
- GPU: NVIDIA RTX 4070, driver 595.91.07 (headless server branch, open kernel module) plus `libnvidia-gl-595-server` for OpenGL/EGL.
- Work runs as user `lab`, which has GPU device access but **no sudo**. Never attempt system package installs or anything needing root. If something is missing, stop and report it.
- Self-hosted GitHub Actions runners on the same machine (labels `self-hosted`, `foundry`, `gpu`) run as `lab`, so the same commands work in CI.
- The runners are in the CubieCode org runner group "Foundry", which must grant this repository access (and allow public repositories). The org variable `RUNNER` (`["self-hosted","foundry"]`) sends CI there. Because the repository is public, CI routes pull requests from forks to `ubuntu-latest`. That guard lives in the workflow files, which a fork can edit, so the repository also requires maintainer approval before any outside contributor's workflows run; review a fork PR's `.github/` changes before approving. Release and GPU workflows can only be started by people with write access; `gpu-smoke.yml` also runs on the `gpu-smoke` label, but only for same-repo PRs, never forks.

## Java

- **SDKMAN** (per user, no root). Eclipse Temurin 21 is the default JDK for builds and CI; `.sdkmanrc` pins `java=21.0.12+1.1-tem`, so `sdk env` (or `sdk env install` on a new machine) selects it. The tools scripts call `sdk env` automatically when SDKMAN exists.
- The Gradle build declares a Java 21 toolchain with the foojay resolver as fallback, so it works without SDKMAN. Never assume a system JDK.
- **Optional:** JetBrains Runtime 21 for enhanced hot-swap during interactive runs: `sdk install java <21.x>-jbr` (see `sdk list java`), then `./gradlew runClient -Pdev_jbr=true`. Gradle finds the SDKMAN-installed JBR (or downloads one via foojay) and adds `-XX:+AllowEnhancedClassRedefinition`. Never required; builds, datagen, gametests and CI always use Temurin.

## Other user-level tools

- **packwiz:** no releases upstream; `tools/install-packwiz.sh` builds the pinned commit into `~/.local/bin`. It uses `go` from PATH or downloads the official Go tarball into `~/.cache/counterstrain/`.
- `python3` (standard library only) runs `tools/xwd-to-png.py`, which converts Xvfb framebuffer dumps to PNG, `tools/check-docs.py` and `tools/check-provenance.py` (which decodes PNGs with zlib, no Pillow).

## Rendering stack

- **Xvfb** provides a virtual X display; **VirtualGL 3.1.5** (`/opt/VirtualGL/bin`, EGL back end) redirects OpenGL to the 4070.
- Verified with `xvfb-run -a /opt/VirtualGL/bin/vglrun -d egl glxinfo -B`, which reports "NVIDIA GeForce RTX 4070".
- Nothing runs when idle: `xvfb-run` starts a display per command and tears it down afterwards.

## Launching the dev client

Use the wrapper:

```sh
tools/run-client-headless.sh --timeout 180 [--resolution 1920x1080]
tools/run-client-headless.sh --scene smoke/mob_lineup     # harness scene: screenshots, then exits
tools/run-scenes.sh [--scenes "a/b c/d"]                  # every scene in turn, as gpu-smoke.yml does
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
- Without `--scene` the client never quits by itself; the timeout is the normal end of a smoke run. With `--scene <id>` the dev-only harness builds the scene, saves its screenshots to `.local/runs/latest/screenshots/` and exits; the run passes only on exit code 0 and `status=ok` in `harness-result.txt`, and the timeout (600 s by default) is only a safety bound. A whole scene run takes about 40 s on foundry.
- Never run `./gradlew --stop` while another lane may be running a client: it also stops the single-use daemon behind `--no-daemon`, killing that lane's game mid-run ("Gradle build daemon has been stopped: stop command received").
- One GPU serves every Orca lane and CI job: the script takes an exclusive lock (`~/.cache/counterstrain/gpu.lock`, `flock`) and waits up to `--gpu-wait` seconds (default 1800) before failing. The timeout starts once the lock is held.

## Visual checks

No live viewing (VNC etc.) exists. Visual evidence comes from:
- harness scenes: `tools/run-client-headless.sh --scene <id>` writes fixed-name screenshots to `.local/runs/client-<UTC>/screenshots/` (the dev-only `harness` source set; see the visual-checks skill for the scene format);
- the final-frame `framebuffer.png` of a headless run;
- in-game screenshots (F2, saved to `mod/run/client/screenshots/`), collected with `tools/collect-screenshots.sh`.

Scene screenshots are compared with the reviewed references committed in `tools/visual-references/<scene>/` by `tools/compare-screenshots.sh --scene <id> --actual <screenshots dir> --out <dir>` (bash and standard-library Python; diff images and `compare-result.txt`; thresholds, exit codes and the reference update rule in the visual-checks skill).

Motion clips: `tools/run-client-headless.sh --record [seconds]` (also accepted by `tools/run-scenes.sh`) records the Xvfb display to `capture.mp4` in the run directory with ffmpeg (`x11grab`, H.264, at most 1280 px wide, 30 fps), from the game window's creation until the client exits or the duration ends. It uses foundry's system ffmpeg (`/usr/bin/ffmpeg` 8.0.1 with `x11grab` and `libx264`), installed by the host; the project needs no root and downloads no binaries. `FFMPEG=/path` overrides the binary. Without a usable ffmpeg, `--record` fails before the GPU lock is taken; runs without `--record` never call ffmpeg.

## Run and output locations

| Path | Contents |
| --- | --- |
| `mod/run/{client,server,gametest,data}/` | Game directories per run type (gitignored) |
| `.local/runs/client-<UTC>/` | `console.log`, `latest.log`, `debug.log`, `framebuffer.png`, `renderer.txt`, `result.txt`; with `--scene`, `screenshots/<shot>.png` and `screenshots/harness-result.txt`; with `--record`, `capture.mp4` and `capture.log` |
| `.local/screenshots/<UTC>/` | Collected screenshots for review |
| `.local/compare/<scene>/` | Suggested `--out` for `tools/compare-screenshots.sh`: `<shot>-diff.png`, `compare-result.txt` |
| `.local/dist/` | Pack exports |
| `.local/instance/` | Default local test instance for `tools/build-mod.sh` |
