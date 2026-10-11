---
name: visual-checks
description: Capture and review screenshots of hosts, festering blocks, effects and UI on foundry, and decide whether visual exit criteria are met. Covers the manual flow now and the P1 harness scenes later.
---

# Visual checks

Visual evidence is only valid from a GPU run. The log must name the RTX 4070 (headless-client); llvmpipe output is never evidence. No live view (VNC) exists, so every review works from image files.

**Sources now (before P1.1):**
- `framebuffer.png` from `tools/run-client-headless.sh`: the last frame, good for "reaches title/world, no error screen".
- In-game screenshots: F2 writes to `mod/run/client/screenshots/`. foundry has no input device, so before P1.1 these come only from a client run elsewhere with the same pack and scene; on foundry the framebuffer is the only capture.
- `tools/collect-screenshots.sh [game-dir] [--clear]` copies PNGs (and the last framebuffer) into `.local/screenshots/<UTC>/`; review from `.local/screenshots/latest/`.

**Scene discipline**, so images are comparable:
- Superflat world, fixed seed.
- `/time set 6000`, `/weather clear`, `/gamerule doDaylightCycle false`, `doMobSpawning false`.
- GUI hidden (F1-equivalent option) unless reviewing HUD.
- Fixed camera position and rotation via `/tp @s x y z yaw pitch`.
- Mobs spawned with `NoAI:1` for static lineups.
- 1920x1080. Default resource pack and video settings (record any change).

Name scenes `<area>/<subject>` as in ROADMAP (`corruption/stages_vanilla`, `world/stages`, `purification/pylon_radius`, `integration/create_fan_purify`).

**What to look for:**
- **Hosts:** the Strain overlay follows the mob's own model, including animated parts, baby mobs, armour and held items. Stages are distinguishable at a glance. No z-fighting, black/magenta missing-texture checkerboards, or missing layers on modded models. Compare with an uninfected lineup in the same scene.
- **Festering land:** stage textures tile without seams. Biome colour flips cleanly at chunk borders. No lighting holes. Particles are on screen but don't flood it.
- **Effects and UI:** HUD and viral load meter legible at 1920x1080 and at GUI scale 2/3. Text fits. Colours hold up in the dark.

- **Provenance markers:** placeholder textures show the 2×2 magenta corner marker and placeholder GUI/promo art the "PLACEHOLDER" label; art whose CONTENT-PROVENANCE row is `final` shows neither. A mismatch is a bug (content-provenance skill).

Always compare against the previous accepted image for the same scene, and against vanilla for "does it still look like Minecraft".

**Recording:** list in the PR each scene, the image paths or artifact name, the renderer line, and what you checked. Attach or upload images; don't commit run output. Committed references, if any, follow the P1.2 decision. Say plainly when a check could not be done.

**After P1.1:** `tools/run-client-headless.sh --scene <id>` loads the scene, takes named screenshots and quits, and P1.2 adds a tolerant image diff against the references. A visual exit criterion is then met only when:
- the listed scenes run in the GPU workflow,
- diffs are within tolerance or intentional changes have been reviewed and the references updated,
- an agent or human has looked at the images.

An image diff alone never accepts new visuals.
