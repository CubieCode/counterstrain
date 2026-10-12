# Development environment

All development, builds, tests and visual checks run on the home server `foundry`. Hands-on pack testing can also happen on your own PC, synced from foundry (playing the pack from a PC, below).

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

## Playing the pack from a PC

To play the current pack on your own computer with a fast edit loop, serve the pack from foundry and let the launcher sync from it before every launch. The PC needs Prism Launcher, ssh access to foundry, `curl` and `sha256sum`. Tested on Manjaro.

On foundry, leave this running (e.g. in tmux):

```sh
tools/pack.sh serve          # http://foundry:8080/pack.toml; pass a port or set COUNTERSTRAIN_PACK_PORT
```

`packwiz serve` refreshes `pack/index.toml` whenever the pack is requested, so edits under `pack/` are live at once. Commit the refreshed index with them, as usual.

On the PC (Manjaro):

1. `sudo pacman -S prismlauncher` (the native package: the Flatpak sandbox cannot run host scripts with your ssh keys). Make sure `ssh foundry` works without a password prompt (key auth), because the hook runs non-interactively.
2. Get `tools/pack-sync.sh`: clone the repository, or copy just that file (`scp lab@foundry:development/CubieCode/counterstrain/tools/pack-sync.sh ~/bin/`). It is self-contained.
3. In Prism: Add Instance → Minecraft 1.21.1, then Edit → Version → Install Loader → NeoForge 21.1.259 (match `pack/pack.toml`). Under Edit → Settings → Custom commands, set the pre-launch command to `~/bin/pack-sync.sh` (or the path inside your clone).

Each launch then runs `tools/pack-sync.sh`, which:
- runs packwiz-installer (the bootstrap jar is pinned by SHA-256 and cached in `~/.cache/counterstrain/`) against the served `pack.toml`: changed mods, configs, KubeJS scripts and quests are downloaded, removed ones deleted, files it did not install left alone. Its window asks once about optional mods (ProbeJS) and asks you to download any mod CurseForge will not serve to it.
- copies `mod/build/libs/remission-<mod_version>.jar` from the foundry checkout into `mods/` over ssh, replacing older `remission-*.jar`. With `--build` it first runs `tools/build-mod.sh` there (into `.local/pack-sync/`). If foundry is unreachable, it only warns, so you can still play with the jar you have.

Options: `--url` (or `COUNTERSTRAIN_PACK_URL`), `--remote HOST:PATH` (or `COUNTERSTRAIN_REMOTE`; default `lab@foundry:development/CubieCode/counterstrain`, point it at a lane worktree to test a branch), `--build`, `--no-jar`, `--no-gui`. A failed pack sync exits non-zero, which makes Prism abort the launch. If port 8080 is not reachable from the PC, tunnel it (`ssh -fNL 8080:localhost:8080 foundry`) and pass `--url http://localhost:8080/pack.toml`.

Inner loop: most pack edits need no relaunch. `/reload` applies recipes, tags, loot, `pack/kubejs/data/` and `server_scripts`; `/kubejs reload client_scripts` applies client scripts; `startup_scripts`, mod list changes and a new Remission jar need a relaunch (which syncs). Quests edited in game land in the instance's `config/ftbquests/`; copy them back into `pack/config/ftbquests/` before committing. For edit-and-`/reload` without the round trip, mount the checkout (`sshfs lab@foundry:development/CubieCode/counterstrain ~/cs`) and replace the instance's `kubejs/` and `config/ftbquests/` with symlinks into `~/cs/pack/`. Edits, including in-game quest edits, then write straight into the foundry checkout, so review the diff before committing.

## Run and output locations

| Path | Contents |
| --- | --- |
| `mod/run/{client,server,gametest,data}/` | Game directories per run type (gitignored) |
| `.local/runs/client-<UTC>/` | `console.log`, `latest.log`, `debug.log`, `framebuffer.png`, `renderer.txt`, `result.txt`; with `--scene`, `screenshots/<shot>.png` and `screenshots/harness-result.txt`; with `--record`, `capture.mp4` and `capture.log` |
| `.local/screenshots/<UTC>/` | Collected screenshots for review |
| `.local/compare/<scene>/` | Suggested `--out` for `tools/compare-screenshots.sh`: `<shot>-diff.png`, `compare-result.txt` |
| `.local/dist/` | Pack exports |
| `.local/instance/` | Default local test instance for `tools/build-mod.sh` |
| `.local/pack-sync/` | Throwaway instance target of `tools/pack-sync.sh --build` (only the jar in `mod/build/libs/` is used) |
