# Roadmap

Counterstrain (pack) and Remission (mod) are built in phases. Phase 0 is complete. Every later item needs an explicit implementation assignment; a scaffold or docs request does not authorise feature work. See [DESIGN](docs/DESIGN.md) for the concept and [ARCHITECTURE](docs/ARCHITECTURE.md) for systems and contracts.

Statuses: `todo` → `in-progress` → `review` → `done`, or `blocked` with reason. Done means accepted and merged on main. Workers own only their item's Status/Owner/PR fields; the coordinator marks done after merge. One item, one branch `feat/<id-lower>-<slug>`, one PR `<ID>: <title>`. Use the [roadmap-workflow](.agents/skills/roadmap-workflow/SKILL.md) skill.

Each phase lists goals, deliverables (items) and exit criteria. Phases with visual output name the **harness scenes** and screenshots that prove them; those images come from a GPU run on foundry (renderer verified, never llvmpipe) and are reviewed per the visual-checks skill. Until P1.1 lands, a scene means a manual setup documented in the PR plus collected screenshots.

## Phase 0: Scaffold

Goals: repository, build tooling, docs, roadmap, agent skills and boilerplate; no gameplay.

### P0.1: Repository scaffold

- Status: `done`
- Owner: scaffold
- PR: initial main bootstrap
- Depends on: none
- Scope: Monorepo layout following worktui conventions; ModDevGradle project with empty registries, configs, client/server entrypoints, datagen and a smoke gametest; packwiz pack with companion mods and empty KubeJS/FTB Quests/datapack folders; tools scripts (build, pack, versions, headless client, GPU verification, screenshots); CI, GPU smoke and release stub workflows; project skills plus vendored minecraft-codex-skills adapted for 1.21.1 NeoForge.
- Acceptance: `./gradlew build`, `runData` and `runGameTestServer` pass; `packwiz refresh` is clean; `.claude/skills` resolves; the headless client reaches the title screen with the RTX 4070 named as renderer and tears down cleanly.
- Skills: roadmap-workflow
- Parallel: Scaffold only

Exit criteria: met. The headless client was verified on foundry at 1920x1080: the title screen showed NeoForge 21.1.259 with 15 mods, the renderer was the RTX 4070, and no processes were left after the timeout.

## Phase 1: Visual test harness

Goals: reproducible, self-terminating visual runs, so every later visual feature has comparable evidence and CI can check rendering on foundry.

### P1.1: Dev-only scene harness

- Status: `todo`
- Owner:
- PR:
- Depends on: P0.1
- Scope: A client hook enabled only by a system property or program argument (never in normal play or release jars' default path). It creates or loads a fixed superflat test world, runs a named scene (spawn a lineup of vanilla and dev-runtime modded mobs, place blocks, set time/weather), positions the camera, waits for chunks and resources, takes screenshots at fixed names, then quits the game. Scenes are data (JSON) or small Java classes in a dev source set. `tools/run-client-headless.sh --scene <id>` passes the flag, uses the exit code instead of a timeout, and collects images.
- Acceptance: `--scene smoke/mob_lineup` exits 0 on its own within a bounded time and produces identically framed screenshots on repeated runs (same camera, seed, time, weather, GUI hidden). The renderer is verified. Harness code cannot run unless enabled.
- Skills: neoforge-mod-dev, headless-client, visual-checks
- Parallel: Serial gate for phase 2 visual exit criteria

### P1.2: Image comparison and motion capture

- Status: `todo`
- Owner:
- PR:
- Depends on: P1.1
- Scope: Decide where reviewed reference images live (a small committed set, or workflow artifacts plus `.local/`) and keep the repository small. Add a tolerant comparison script (per-pixel diff threshold, diff image output) using no root-only tools. Optional ffmpeg clip capture of the Xvfb display for motion checks, only if ffmpeg is available as a user-level install; report if not.
- Acceptance: Changing the scene produces a reported diff with a diff image; an unchanged run passes. Motion capture works, or is documented as unavailable with the reason.
- Skills: visual-checks, headless-client
- Parallel: After P1.1

### P1.3: GPU workflow runs scenes

- Status: `todo`
- Owner:
- PR:
- Depends on: P1.1
- Scope: `gpu-smoke.yml` runs the harness scene list instead of a timeout, uploads screenshots, diffs and logs; optional PR trigger by label.
- Acceptance: A manual run on `[self-hosted, foundry, gpu]` passes, uploads artifacts and fails on llvmpipe or a crashed scene.
- Skills: headless-client, release
- Parallel: With P1.2

Exit criteria: harness scene `smoke/mob_lineup` runs headlessly, quits by itself, and its screenshots are reviewed and stored as the first reference. The GPU workflow runs it.

## Phase 2: Corruption state

Goals: any living entity can carry corruption state, which is saved, synced and visible, with debug commands to drive it.

### P2.1: Corruption attachment and sync

- Status: `todo`
- Owner:
- PR:
- Depends on: P0.1
- Scope: `CorruptionState` record (level, stage; strain reserved), codec, entity attachment, server API (`get/set/isCorrupted`), sync to tracking clients on change and start-tracking (choose custom payload vs NeoForge attachment sync and record it in ARCHITECTURE). Common config for stage thresholds.
- Acceptance: State survives save/reload and dimension change; clients see changes for tracked entities only; gametests cover set/get/persist and stage thresholds.
- Skills: neoforge-mod-dev, remission-data-formats
- Parallel: With P2.3

### P2.2: Generic corruption render layer

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1, P1.1
- Scope: Layer added to every living-entity renderer (including players) in `EntityRenderersEvent.AddLayers`, re-rendering the parent model with a stage-dependent overlay; client config for intensity. Placeholder art.
- Acceptance: Works on vanilla and dev-runtime modded mobs (including non-standard models), baby variants, armour; no crash with renderers lacking a model. Harness scenes `corruption/stages_vanilla`, `corruption/stages_modded`.
- Skills: neoforge-mod-dev, visual-checks
- Parallel: After P2.1

### P2.3: Debug commands

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1
- Scope: `/remission corrupt <targets> <level>`, `/remission cleanse <targets>`, `/remission inspect <target>`; permission level 2.
- Acceptance: Commands work with selectors; gametests drive them.
- Skills: neoforge-mod-dev
- Parallel: With P2.2

Exit criteria: gametests pass; harness screenshots show stages 0–N on a vanilla and a modded lineup, reviewed.

## Phase 3: Corrupted mob behaviour

Goals: corrupted mobs of any kind behave as hostile corrupted creatures, convert others, drop samples and are tunable by data.

### P3.1: Goal injection and attribute patching

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1
- Scope: `EntityJoinLevelEvent` stripping of passive goals and injection of target/attack goals; `EntityAttributeModificationEvent` adding attack damage to all types; default profiles by mob category.
- Acceptance: Corrupted cows, sheep, zombies and modded goal-based mobs attack players and uncorrupted mobs; cleansing restores normal behaviour after reload. Gametests.
- Skills: neoforge-mod-dev
- Parallel: With P3.3

### P3.2: Brain, flying and swimming fallbacks

- Status: `todo`
- Owner:
- PR:
- Depends on: P3.1
- Scope: Fallbacks for brain-based mobs (villager, piglin, axolotl, allay, frog), flyers (bat, phantom-like, parrot, modded) and swimmers (fish, squid, dolphin).
- Acceptance: Each category either attacks sensibly or is explicitly marked unsupported in its default profile. Harness scene `behaviour/fallbacks`.
- Skills: neoforge-mod-dev, visual-checks

### P3.3: Conversion, loot and data-driven overrides

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1
- Scope: Conversion on kill by corrupted mob and on prolonged exposure (exposure source stubbed until phase 4); `remission:is_corrupted` loot condition and default global loot modifier; `#remission:immune` tag; `remission:mob_profile` datapack registry (attributes, behaviour profile, `convert_to`).
- Acceptance: Schemas in remission-data-formats match the code; immune mobs never convert; overrides reload with `/reload` where possible; gametests for each.
- Skills: neoforge-mod-dev, remission-data-formats, minecraft-datapack

Exit criteria: gametests pass; harness scene `behaviour/conversion` shows a corrupted mob converting a lineup, reviewed.

## Phase 4: World corruption

Goals: corruption spreads through land from hearts, visibly, and keeps progressing while chunks are unloaded.

### P4.1: Chunk corruption levels and spread engine

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1
- Scope: Chunk attachment, heart/node block entity and level index, tick-budgeted spread, catch-up on load from stored game time, config for rates and budgets.
- Acceptance: Deterministic spread in gametests; catch-up bounded per tick; server tick cost measured on foundry and recorded in the PR.
- Skills: neoforge-mod-dev, minecraft-world-generation

### P4.2: Visual stages and biome conversion

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1
- Scope: Blighted grass, dead trees/foliage, corrupted stone (blocks + placeholder textures + datagen), staged transformation, biome flip to `remission:blighted` with client resend.
- Acceptance: Harness scenes `world/stages` (one chunk per stage) and `world/biome_flip`, reviewed; no lighting or chunk-border artefacts.
- Skills: neoforge-mod-dev, minecraft-resource-pack, minecraft-world-generation, visual-checks

### P4.3: Surges

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P3.1
- Scope: Periodic surges that accelerate spread and spawn corrupted mobs, with warnings; config and command to trigger.
- Acceptance: Gametest for scheduling; harness scene `world/surge`.
- Skills: neoforge-mod-dev, visual-checks

Exit criteria: gametests pass; spread and catch-up timings recorded; scenes reviewed.

## Phase 5: Survival and early purification

Goals: the player can survive and push back corruption locally.

### P5.1: Exposure meter

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1
- Scope: Player attachment, exposure gain in corrupted chunks, escalating debuffs, HUD element, sync.
- Acceptance: Gametests for gain/decay; harness scene `survival/hud`.
- Skills: neoforge-mod-dev, visual-checks

### P5.2: Handheld purifier, wards and cleansing plants

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P4.2
- Scope: Handheld purifier item, ward totem block with radius, cleansing plant; reclaimed-land bonuses (config).
- Acceptance: Gametests for radius and cleansing; harness scene `purification/ward_radius` (before/after).
- Skills: neoforge-mod-dev, visual-checks

Exit criteria: a player can enter, survive and cleanse a corrupted area in a dev world; scenes reviewed.

## Phase 6: Research

Goals: knowledge is gathered from the world and recorded.

### P6.1: Scanner and data items

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1
- Scope: Scanner item scanning mobs/blocks into data items (data components).
- Acceptance: Gametests; harness scene `research/scanner`.
- Skills: neoforge-mod-dev

### P6.2: Bestiary

- Status: `todo`
- Owner:
- PR:
- Depends on: P6.1
- Scope: Bestiary UI listing corrupted variants derived from installed entity types; scan progress per player.
- Acceptance: Variant count scales with dev-runtime mods; harness scene `research/bestiary`.
- Skills: neoforge-mod-dev, visual-checks

Exit criteria: scanning works for vanilla and modded mobs; bestiary screenshots reviewed.

## Phase 7: Tech chain

Goals: corruption becomes a resource processed by machines into tools for the fight.

### P7.1: Blight fluid and samples

- Status: `todo`
- Owner:
- PR:
- Depends on: P3.3
- Scope: Blight fluid (and Mekanism chemical via optional integration), tissue/heart core/soil sample items and their loot.
- Acceptance: Fluid renders and flows; Mekanism integration loads only when Mekanism is present.
- Skills: neoforge-mod-dev, visual-checks

### P7.2: Analyzer, sequencer and synthesizer

- Status: `todo`
- Owner:
- PR:
- Depends on: P7.1
- Scope: Three machines with recipe types `remission:analyzing`, `remission:sequencing`, `remission:synthesizing`, energy, JEI categories, default recipes via datagen.
- Acceptance: Recipe schemas documented; JEI shows them; gametests run each machine.
- Skills: neoforge-mod-dev, remission-data-formats

### P7.3: Serums, purifier fuel, prototype strains and KubeJS integration

- Status: `todo`
- Owner:
- PR:
- Depends on: P7.2, P5.2
- Scope: Products and their effects; verify KubeJS can add/remove/replace Remission recipes (schema registration if needed).
- Acceptance: A sample KubeJS script edits each recipe type in a dev run; documented in kubejs-scripting.
- Skills: neoforge-mod-dev, kubejs-scripting

Exit criteria: full chain from sample to serum works in a dev world; machine screenshots reviewed.

## Phase 8: Quest integration

Goals: quests can observe and drive Remission.

### P8.1: Advancement triggers and stats

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P6.1
- Scope: Triggers and stats for region % cleansed, heart destroyed, variant scanned, surge survived, and others the pack needs.
- Acceptance: Schemas in remission-data-formats; FTB Quests tasks gate on them in a dev run.
- Skills: neoforge-mod-dev, remission-data-formats, ftb-quests-authoring

### P8.2: Quest-fired events and map overlay

- Status: `todo`
- Owner:
- PR:
- Depends on: P8.1, P4.3
- Scope: Commands/KubeJS bindings for quests to start surges, seed hearts or advance regions; optional JourneyMap/Xaero overlay of corruption.
- Acceptance: A quest reward fires an event in a dev run; overlay screenshot reviewed (harness scene `quest/map_overlay`).
- Skills: neoforge-mod-dev, ftb-quests-authoring, visual-checks

Exit criteria: a sample quest chain gates on and fires Remission events.

## Phase 9: Endgame cure

Goals: the counter-strain heals the world.

### P9.1: Counter-strain spread and adaptive resistance

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P7.3
- Scope: Reverse spread on the same engine; resistant strains reacting to the player's methods.
- Acceptance: Gametests for reverse spread and adaptation; timings recorded.
- Skills: neoforge-mod-dev

### P9.2: Final deployment event and world healing

- Status: `todo`
- Owner:
- PR:
- Depends on: P9.1, P4.3
- Scope: Defence event, then visible healing back to original blocks/biomes.
- Acceptance: Harness scenes `cure/before`, `cure/after`, reviewed.
- Skills: neoforge-mod-dev, visual-checks

Exit criteria: a dev world can be fully cured; scenes reviewed.

## Phase 10: Pack assembly

Goals: Counterstrain becomes a playable pack.

### P10.1: Story and quest chapters

- Status: `todo`
- Owner:
- PR:
- Depends on: P8.1
- Scope: Lore, chapters (find heart → research → gear up → destroy → purify → next region → cure).
- Acceptance: Full quest line completable in a playtest.
- Skills: ftb-quests-authoring, playtesting

### P10.2: Crash-site start, recipe gating and worldgen

- Status: `todo`
- Owner:
- PR:
- Depends on: P10.1
- Scope: Starting structure/map, KubeJS progression gating across Mekanism/AE2/Create/Ars Nouveau, datapack worldgen tweaks for hearts.
- Acceptance: New world starts at the crash site; gated recipes verified in JEI; harness scene `pack/crash_site`.
- Skills: kubejs-scripting, minecraft-datapack, minecraft-world-generation, playtesting, visual-checks

### P10.3: Balancing pass

- Status: `todo`
- Owner:
- PR:
- Depends on: P10.2
- Scope: Spread rates, exposure, recipe costs, quest rewards.
- Acceptance: A documented playtest from start to cure.
- Skills: playtesting

Exit criteria: complete playthrough recorded; screenshots of each chapter milestone reviewed.

## Phase 11: Polish and release

Goals: performant, compatible, published.

### P11.1: Performance and compatibility

- Status: `todo`
- Owner:
- PR:
- Depends on: P10.3
- Scope: Server tick and client FPS under heavy spread measured on foundry; compatibility with the pack's mods and common extras.
- Acceptance: Numbers recorded against targets set in the item; harness scene `perf/heavy_spread` with FPS overlay.
- Skills: headless-client, visual-checks, playtesting

### P11.2: Publishing

- Status: `todo`
- Owner:
- PR:
- Depends on: P11.1, license decision
- Scope: Modrinth/CurseForge projects, publishing steps in release workflows, Remission added to the pack via packwiz.
- Acceptance: Tagged releases publish; pack references the released mod.
- Skills: release, minecraft-ci-release

Exit criteria: mod and pack 1.0 released.
