---
name: visual-checks
description: Capture and review screenshots of hosts, festering blocks, effects and UI on foundry, and decide whether visual exit criteria are met. Covers harness scenes (tools/run-client-headless.sh --scene), their JSON format and the manual fallbacks.
---

# Visual checks

Visual evidence is only valid from a GPU run. The log must name the RTX 4070 (headless-client); llvmpipe output is never evidence. No live view (VNC) exists, so every review works from image files.

**Sources:**
- Harness scenes (preferred): `tools/run-client-headless.sh --scene <id>` builds the scene, saves fixed-name screenshots to `.local/runs/latest/screenshots/<shot>.png` and quits (headless-client). Current scenes: `smoke/mob_lineup` (shots `front`, `oblique`: vanilla lineup plus the Mekanism robit as a dev-runtime modded mob), `corruption/stages_vanilla` and `corruption/stages_modded` (host overlay stages, P2.2; below), `corruption/stages_layers` (overlay on armour, wool, slime gel, the drowned's outer layer, horse and wolf armour, P2.4; below), `research/scanner` (shots `items`: scanner, host scan and block scan in item frames; `in_hand`: an armour stand pointing the scanner at a stage 2 host cow; P6.1), `research/bestiary` (shots `hosts`: stage 1–3 hosts including the Mekanism robit, plus an unscanned host pig; `list`: the bestiary opened with its key, four scanned types first; P6.2), `behaviour/fallbacks` (shots `brain`, `flying`, `swimming`: stage 2 hosts of each fallback category: brain mobs on a floor and flyers in the air beside an uninfected pig, swimmers on a dry floor; P3.2), `quest/triggers` (shot `after_actions`: the player scans a nidus and a stage 2 host cow and breaks a second nidus through `harness player`, then `tick step 40` lets quest mods poll stats; evidence is the log, not the image, so it has no reference; P8.1).
- `framebuffer.png` from a timeout run of `tools/run-client-headless.sh`: the last frame, good for "reaches title/world, no error screen".
- In-game screenshots: F2 writes to `mod/run/client/screenshots/`. foundry has no input device, so these come only from a client run elsewhere with the same pack and scene.
- `tools/collect-screenshots.sh [game-dir] [--clear]` copies PNGs (and the last framebuffer) into `.local/screenshots/<UTC>/`; review from `.local/screenshots/latest/`.
- Motion clips: add `--record [seconds]` to `tools/run-client-headless.sh` (or `tools/run-scenes.sh`) to get `.local/runs/latest/capture.mp4` (H.264, at most 1280 px wide, 30 fps; from window creation, so a scene clip starts with the loading screen and ends with the scene). It uses the host's system `/usr/bin/ffmpeg` (`x11grab`, `libx264`; headless-client). Review a clip from extracted frames, e.g. `ffmpeg -ss 30 -i capture.mp4 -frames:v 1 frame.png`, plus `ffprobe` duration and size; clips are review evidence only, never compared or committed. Stills stay the reference evidence; record motion when the item's exit criteria involve animation or movement.

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
- `{"corrupt": <entity selector>, "level": 0-100}` (P2.2) sets the Strain level of every living entity the selector matches through the server API (`Corruption.set`, staged by the run's common config), players included; it fails when nothing matches. Tag spawned mobs (`Tags:["stage_2"]` in `nbt`) and select them by tag.
- `harness scan <targets>` (a harness-only command, registered only while a scene runs) records a host scan of every host among the targets in every player's bestiary, as the scanner would. Non-hosts are skipped; it fails if no target is a host.
- `harness player break <pos>`, `harness player scan_host <targets>` and `harness player scan_block <pos>` make every player break the block (`ServerPlayerGameMode.destroyBlock`) or scan with the scanner (put in the main hand, cooldown cleared; `Scanning`), the server code a real break or scan runs, so stats, advancement triggers and quest tasks fire. Ticks are frozen, so add `tick step <n>` after them when something polls on ticks (FTB Quests checks stat tasks every few ticks).
- A shot's optional `press` names a key mapping (e.g. `"press": "key.remission.bestiary"`). Once the camera is in place the harness clicks that mapping's key, and the shot waits for a screen to be open instead of for none; the screen is closed before the next shot. The scene fails if another mapping active in game shares the key, since the click would reach both. The GUI is hidden only for the HUD: a screen renders, and JourneyMap may draw its minimap behind it.
- A shot's optional `view` sets the camera type: `first_person` (default), `third_person_back` or `third_person_front`, e.g. to see the player. The scene's player is a spectator unless setup changes it (`gamemode creative @a`; a spectator renders only its head); a non-spectator falls, so its camera positions must stand on the ground (y = -60).
- `corruption/stages_vanilla` (shots `stage_0`..`stage_3`, `stage_3_oblique`, `player`) and `corruption/stages_modded` (same stage shots plus `ender_dragon`) are the host overlay scenes (P2.2). In `stages_modded` the wither and ender dragon are immune bosses (P3.3) and stay clean at every stage; references from P3.3 on show them without the overlay. Both scenes use one lineup per stage, 30 blocks apart on z, levels 0, 20, 50, 90. `corruption/stages_layers` (P2.4; same stage shots and spacing plus `stage_3_oblique` and `player`, the player in iron, diamond and gold armour at stage 3) checks that the overlay follows each layer's own cutout (no enchanted items: glint moves with wall-clock time and would break byte-identical runs). Players keep ticking under `/tick freeze`, so while shooting the harness pins the local player's `tickCount` to 0; its idle animation (arm bob) is then the same every run.

**Scene discipline**, so images are comparable (the harness enforces the first four and the default 1920x1080; scenes add `NoAI`):
- Superflat world, fixed seed.
- `/time set 6000`, `/weather clear`, `/gamerule doDaylightCycle false`, `doMobSpawning false`.
- GUI hidden (F1-equivalent option) unless reviewing HUD.
- Fixed camera position and rotation via `/tp @s x y z yaw pitch`.
- Mobs spawned with `NoAI:1` for static lineups.
- 1920x1080. Default resource pack and video settings (record any change).
- Nothing in frame may animate on wall-clock time or depend on entity ids, which `/tick freeze` does not pin: no visible water, lava or other animated block textures (their frame advances with client frames), and no mobs whose model animation is offset by entity id (phantom wings, `getUniqueFlapTickOffset`). Confirm a new scene with two consecutive runs that compare equal (P3.2 found both in `behaviour/fallbacks`).

Name scenes `<area>/<subject>` as in ROADMAP (`corruption/stages_vanilla`, `world/stages`, `purification/pylon_radius`, `integration/create_fan_purify`).

**What to look for:**
- **Hosts:** the Strain overlay follows the mob's own model, including animated parts, baby mobs, armour and held items. Stages are distinguishable at a glance. No z-fighting, black/magenta missing-texture checkerboards, or missing layers on modded models. Compare with an uninfected lineup in the same scene.
- **Festering land:** stage textures tile without seams. Biome colour flips cleanly at chunk borders. No lighting holes. Particles are on screen but don't flood it.
- **Effects and UI:** HUD and viral load meter legible at 1920x1080 and at GUI scale 2/3. Text fits. Colours hold up in the dark.

- **Provenance markers:** placeholder textures show the 2×2 magenta corner marker and placeholder GUI/promo art the "PLACEHOLDER" label; art whose CONTENT-PROVENANCE row is `final` shows neither. A mismatch is a bug (content-provenance skill).

Always compare against the accepted reference for the same scene (below), and against vanilla for "does it still look like Minecraft".

**References and comparison:** reviewed reference images are committed in `tools/visual-references/<scene>/<shot>.png`, one directory per harness scene, so local runs and the GPU workflow compare against the same set. Keep the set small: only scenes named in exit criteria or item acceptance (now `smoke/mob_lineup`, `corruption/stages_vanilla`, `corruption/stages_modded`, `corruption/stages_layers`, `research/scanner`, `research/bestiary` and `behaviour/fallbacks`, about 11 MB together), the harness's 1920x1080 PNGs unchanged (no re-encoding, so unchanged runs hit the byte-identical fast path), and replace files instead of adding variants. They are screenshots of the running game used as test fixtures, never shipped, and sit outside the provenance check's scan roots, so they take no CONTENT-PROVENANCE rows. Compare a run with:

```sh
tools/compare-screenshots.sh --scene <id> --actual .local/runs/latest/screenshots --out .local/compare/<id>
```

It writes `<shot>-diff.png` (the actual image dimmed to grey, pixels over the threshold red, smaller differences yellow) and `compare-result.txt` (per shot: status, differing pixels, sub-threshold pixels, largest channel delta, ratio). A pixel differs when any RGBA channel moves by more than `--threshold` (default 24 of 255); a shot passes when at most `--max-diff-ratio` of its pixels differ (default 0.0005, about 1000 pixels at 1920x1080). Exit 0 = all shots within tolerance, 1 = a shot differs, is missing, has no reference or changed size, 2 = usage or tool error, 3 = no reference for the scene. `tools/compare-screenshots.sh` wraps `tools/compare-screenshots.py` (standard library Python, PNGs decoded with zlib): about 2 s for two identical 1920x1080 shots and longer for real differences. `--self-test` runs synthetic cases. On foundry, repeated runs of a frozen scene are byte-identical, so any difference is real; the tolerance absorbs driver or minor rendering noise.

**Updating references:** only for an intentional, reviewed change. Run the scene from a GPU-verified run, look at every shot and its diff image, then rerun the comparison with `--accept` (copies the actual shots over the references and drops shots the scene no longer takes) and commit the new PNGs in the same PR as the change that caused them, with the reason in the PR body. A new scene's first reference is made the same way (exit 3, then `--accept`). Never accept to make a failing check pass without looking.

**Recording:** list in the PR each scene, the image paths or artifact name, the renderer line, the comparison result and what you checked. Attach or upload images; don't commit run output (only reviewed references, as above). Say plainly when a check could not be done.

**Acceptance:** A visual exit criterion is met only when:
- the listed scenes run in the GPU workflow (`gpu-smoke.yml` runs every scene file; dispatch it on your branch or label the PR `gpu-smoke` (headless-client has the command), and link the run),
- diffs are within tolerance or intentional changes have been reviewed and the references updated,
- an agent or human has looked at the images.

An image diff alone never accepts new visuals.
