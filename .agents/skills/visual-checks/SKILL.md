---
name: visual-checks
description: Capture and review screenshots of hosts, festering blocks, effects and UI on foundry, and decide whether visual exit criteria are met. Covers harness scenes (tools/run-client-headless.sh --scene), their JSON format and the manual fallbacks.
---

# Visual checks

Visual evidence is only valid from a GPU run. The log must name the RTX 4070 (headless-client); llvmpipe output is never evidence. No live view (VNC) exists, so every review works from image files.

**Sources:**
- Harness scenes (preferred): `tools/run-client-headless.sh --scene <id>` builds the scene, saves fixed-name screenshots to `.local/runs/latest/screenshots/<shot>.png` and quits (headless-client). Current scenes: `smoke/mob_lineup` (shots `front`, `oblique`: vanilla lineup plus the Mekanism robit as a dev-runtime modded mob).
- `framebuffer.png` from a timeout run of `tools/run-client-headless.sh`: the last frame, good for "reaches title/world, no error screen".
- In-game screenshots: F2 writes to `mod/run/client/screenshots/`. foundry has no input device, so these come only from a client run elsewhere with the same pack and scene.
- `tools/collect-screenshots.sh [game-dir] [--clear]` copies PNGs (and the last framebuffer) into `.local/screenshots/<UTC>/`; review from `.local/screenshots/latest/`.

**Harness scenes** live in `mod/src/harness/resources/harness/scenes/<area>/<subject>.json` (dev-only `harness` source set, never in the jar). The harness fixes the world: it deletes and recreates the superflat save `remission_harness` (seed 1210101, no structures, spectator, mob spawning, daylight and weather cycles off) and pins image options in memory (GUI hidden, clouds off, render distance 8, FOV 70, fancy graphics, smooth lighting, brightness 0.5). Then it sets time and weather, runs `/tick freeze`, runs `setup` in order, and for each shot teleports the camera, waits until chunks around it are loaded and no section compiles are queued for 40 ticks, and saves the frame. Format:

```json
{
  "time": 6000,
  "weather": "clear",
  "setup": [
    "fill -13 -61 -2 13 -61 2 minecraft:smooth_stone",
    {"spawn": "minecraft:zombie", "pos": [-11.5, -60.0, 0.5], "yaw": 0.0, "nbt": "{NoAI:1b,Silent:1b}"},
    {"spawn": "mekanism:robit", "pos": [10.5, -60.0, 0.5], "nbt": "{NoAI:1b}", "requires_mod": "mekanism"}
  ],
  "shots": [
    {"name": "front", "camera": {"pos": [-0.5, -60.0, 9.5], "yaw": 180.0, "pitch": 4.0}}
  ]
}
```

- `time` (default 6000) and `weather` (`clear`, `rain`, `thunder`; default `clear`).
- `setup`: a string is a server command without the slash (permission 4); `{"command": ..., "requires_mod": ...}` is its object form. `{"spawn": <entity id>, "pos", "yaw", "nbt"}` adds the entity directly: no `finalizeSpawn`, so no random equipment, babies or variants, and it works for types `/summon` refuses (the robit). `requires_mod` skips a step when that mod is absent (`-Pdev_runtime_mods=false`). Any failing step fails the scene.
- `shots`: `name` (`[a-z0-9_]+`, saved as `<name>.png`) and `camera`, the player's feet position and rotation as for `/tp x y z yaw pitch`. The flat surface is y = -60.
- Ticks stay frozen for the whole scene, so mobs, fluids and animations do not move between shots.

**Scene discipline**, so images are comparable (the harness enforces the first four and the default 1920x1080; scenes add `NoAI`):
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

**Acceptance:** P1.2 adds a tolerant image diff against reviewed references and decides where those references live; until then, compare repeated runs and the previous PR's images by eye (on foundry repeated runs of a frozen scene are byte-identical). A visual exit criterion is met only when:
- the listed scenes run in the GPU workflow,
- diffs are within tolerance or intentional changes have been reviewed and the references updated,
- an agent or human has looked at the images.

An image diff alone never accepts new visuals.
