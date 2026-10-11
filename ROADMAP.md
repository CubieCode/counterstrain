# Roadmap

Counterstrain (pack) and Remission (mod) are built in phases. Phase 0 is complete. Every later item needs an explicit implementation assignment; a scaffold or docs request does not authorise feature work. See [DESIGN](docs/DESIGN.md) for the concept and glossary and [ARCHITECTURE](docs/ARCHITECTURE.md) for systems and contracts.

Statuses: `todo` → `in-progress` → `review` → `done`, or `blocked` with reason. Done means accepted and merged on main. Workers own only their item's Status/Owner/PR fields; the coordinator marks done after merge. One item, one Orca worktree (lane), one branch `feat/<id-lower>-<slug>`, one PR `<ID>: <title>`. Each item uses the [roadmap-workflow](.agents/skills/roadmap-workflow/SKILL.md) skill; coordinating parallel lanes and reviews uses [roadmap-orchestrate](.agents/skills/roadmap-orchestrate/SKILL.md). Run only when dependencies are done on main. Waves below are candidates, subject to real file overlap and the serialization gates in roadmap-orchestrate.

Each phase lists goals, deliverables (items) and exit criteria. Phases with visual output name the **harness scenes** and screenshots that prove them; those images come from a GPU run on foundry (renderer verified, never llvmpipe) and are reviewed per the visual-checks skill. Scenes live in `mod/src/harness/resources/harness/scenes/` and run with `tools/run-client-headless.sh --scene <id>` (visual-checks).

Rules for every item after P4.4:
- **API events:** a system item emits the public API events and queries for the state it owns (`com.cubiecode.remission.api`, DESIGN "Public API and events") and documents them in remission-data-formats in the same PR.
- **Content provenance:** an item that adds or changes player-facing content (textures, models, GUI art, sounds, lang, quest text, lore, store copy) updates [CONTENT-PROVENANCE](docs/CONTENT-PROVENANCE.md) and marks placeholders per the [content-provenance](.agents/skills/content-provenance/SKILL.md) skill. Its acceptance includes "ledger updated; placeholders marked".
- **Vocabulary:** names follow the DESIGN glossary and naming rule.

## Safe waves

All lanes run on foundry, sharing CPU, RAM and one GPU (client runs queue on a lock), so keep waves at four lanes or fewer. One owner per wave for build/version files, the registry and network spine, contracts (remission-data-formats) and pack membership. Generated files (`mod/src/generated/`, `pack/index.toml`) are regenerated after rebasing, never hand-merged.

- A: P1.1 + P2.1 + P1.4. P2.1 owns the registry and network spine; P1.1 owns the dev-only harness and `tools/run-client-headless.sh`; P1.4 owns the provenance check script and its `docs.yml` step. P1.4 can also run in any later early wave.
- B: P2.2 + P2.3 + P1.2 + P1.3, after A. P1.2 owns comparison scripts; P1.3 owns `gpu-smoke.yml`; P2.2 owns client rendering; P2.3 owns commands.
- C: P3.1 + P3.3 + P4.1 + P6.1, after P2.1. Agree the `mob_profile` record (P3.3 owns the contract) before P3.1 starts; registry and lang edits append-only; one registry-spine owner.
- D: P4.4 + P3.2 + P6.2. P4.4 owns the `api/` package and the API section of remission-data-formats.
- E: P4.2 + P4.3. P4.2 owns new blocks, textures and biome data; P4.3 owns surge scheduling and miasma clouds. Each adds its own API events; append-only edits in `api/`, one remission-data-formats owner (P4.2).
- F: P5.1 + P5.2 + P7.1. P5.1 owns the player attachment and HUD; one remission-data-formats owner (P5.2).
- G: P7.2 → P7.3 serially (recipe types and KubeJS schemas are contracts), with P8.1 in parallel (owns triggers and stats contracts).
- H: P8.2 + P9.1, then P8.3 + P9.2. P8.2 owns command syntax; P8.3 owns the KubeJS binding contract.
- I: P10.1 alone (owns `pack/pack.toml`, `pack/mods/` and the build-file entries for every mod-side integration).
- J: P10.2 + P10.3 + P10.4 + P10.5, after P10.1. Each owns its `integration/<modid>/` packages and its pack scripts (`pack/kubejs/**/integrations/<modid>.js`); one remission-data-formats owner per wave (P10.2); none touches build files or `pack/mods/`.
- K: P10.6 + P10.7. P10.6 extends `integration/create/` after P10.2 has merged.
- L: P11.1 → P11.2 → P11.3 serially (one pack-content owner).
- M: P12.1 + P12.2, then P12.3.

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

Goals: reproducible, self-terminating visual runs, so every later visual feature has comparable evidence and CI can check rendering on foundry; and a mechanical check that player-facing assets are tracked.

### P1.1: Dev-only scene harness

- Status: `done`
- Owner: feat/p1.1-scene-harness
- PR: https://github.com/CubieCode/counterstrain/pull/7
- Depends on: P0.1
- Scope: A client hook enabled only by a system property or program argument (never in normal play or release jars' default path). It creates or loads a fixed superflat test world, runs a named scene (spawn a lineup of vanilla and dev-runtime modded mobs, place blocks, set time/weather), positions the camera, waits for chunks and resources, takes screenshots at fixed names, then quits the game. Scenes are data (JSON) or small Java classes in a dev source set. `tools/run-client-headless.sh --scene <id>` passes the flag, uses the exit code instead of a timeout, and collects images.
- Acceptance: `--scene smoke/mob_lineup` exits 0 on its own within a bounded time and produces identically framed screenshots on repeated runs (same camera, seed, time, weather, GUI hidden). The renderer is verified. Harness code cannot run unless enabled.
- Skills: neoforge-mod-dev, headless-client, visual-checks
- Parallel: Serial gate for phase 2 visual exit criteria

### P1.2: Image comparison and motion capture

- Status: `review`
- Owner: feat/p1.2-motion-capture
- PR: https://github.com/CubieCode/counterstrain/pull/10, https://github.com/CubieCode/counterstrain/pull/13
- Depends on: P1.1
- Scope: Decide where reviewed reference images live (a small committed set, or workflow artifacts plus `.local/`) and keep the repository small. Add a tolerant comparison script (per-pixel diff threshold, diff image output) using no root-only tools. Optional ffmpeg clip capture of the Xvfb display for motion checks, only if ffmpeg is available as a user-level install; report if not.
- Acceptance: Changing the scene produces a reported diff with a diff image; an unchanged run passes. Motion capture works, or is documented as unavailable with the reason.
- Skills: visual-checks, headless-client
- Parallel: After P1.1

### P1.3: GPU workflow runs scenes

- Status: `review`
- Owner: feat/p1.3-gpu-workflow-scenes
- PR: https://github.com/CubieCode/counterstrain/pull/11
- Depends on: P1.1
- Scope: `gpu-smoke.yml` runs the harness scene list instead of a timeout, uploads screenshots, diffs and logs; optional PR trigger by label.
- Acceptance: A manual run on `[self-hosted, foundry, gpu]` passes, uploads artifacts and fails on llvmpipe or a crashed scene.
- Skills: headless-client, release
- Parallel: With P1.2

### P1.4: Provenance check

- Status: `done`
- Owner: feat/p1.4-provenance-check
- PR: https://github.com/CubieCode/counterstrain/pull/4
- Depends on: P0.1
- Scope: A `tools/check-provenance.sh` script (bash plus Python standard library, no root-only tools), run in `docs.yml`, that fails if any image, sound or model under the mod's main and generated `assets/` trees or under `pack/` lacks a row in `docs/CONTENT-PROVENANCE.md`, and that checks each texture's placeholder marker (the 2×2 `#FF00FF` top-left square, scaled) matches its row's status: marker present on `final`, or missing on `placeholder`, fails. Rows pointing at missing files fail too, unless their status says the asset was removed. Documented in README, the content-provenance skill and docs-sync.
- Acceptance: The check passes on main; a fixture texture without a row, a placeholder row without the marker and a final row with the marker each fail with a clear message; CI runs it.
- Skills: content-provenance, docs-sync, minecraft-ci-release
- Parallel: Any early wave; owns its script and the `docs.yml` step

Exit criteria: harness scene `smoke/mob_lineup` runs headlessly, quits by itself, and its screenshots are reviewed and stored as the first reference. The GPU workflow runs it. The provenance check runs in CI.

## Phase 2: Corruption state

Goals: any living entity can become a host: its corruption state is saved, synced and visible, with debug commands to drive it.

### P2.1: Corruption attachment and sync

- Status: `done`
- Owner: feat/p2.1-corruption-attachment
- PR: https://github.com/CubieCode/counterstrain/pull/5
- Depends on: P0.1
- Scope: `CorruptionState` record (level, stage; `variant` reserved for resistant mutations), codec, entity attachment `corruption`, server API (`get/set/isHost`), sync to tracking clients on change and start-tracking (choose custom payload vs NeoForge attachment sync and record it in ARCHITECTURE). Common config for stage thresholds.
- Acceptance: State survives save/reload and dimension change; clients see changes for tracked entities only; gametests cover set/get/persist and stage thresholds.
- Skills: neoforge-mod-dev, remission-data-formats
- Parallel: With P2.3

### P2.2: Generic host render layer

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1, P1.1
- Scope: Layer added to every living-entity renderer (including players) in `EntityRenderersEvent.AddLayers`, re-rendering the parent model with a stage-dependent Strain overlay; client config for intensity. Placeholder art.
- Acceptance: Works on vanilla and dev-runtime modded mobs (including non-standard models), baby mobs, armour; no crash with renderers lacking a model. Harness scenes `corruption/stages_vanilla`, `corruption/stages_modded`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance
- Parallel: After P2.1

### P2.3: Debug commands

- Status: `review`
- Owner: feat/p2.3-debug-commands
- PR: https://github.com/CubieCode/counterstrain/pull/9
- Depends on: P2.1
- Scope: `/remission infect <targets> <level>`, `/remission cleanse <targets>`, `/remission inspect <target>`; permission level 2.
- Acceptance: Commands work with selectors; gametests drive them. Ledger updated for command feedback lang strings.
- Skills: neoforge-mod-dev, content-provenance
- Parallel: With P2.2

Exit criteria: gametests pass; harness screenshots show stages 0–N on a vanilla and a modded lineup, reviewed.

## Phase 3: Host behaviour

Goals: hosts of any kind behave as hostile Strain-infected creatures, infect others, drop samples and are tunable by data.

### P3.1: Goal injection and attribute patching

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1
- Scope: `EntityJoinLevelEvent` stripping of passive goals and injection of target/attack goals; `EntityAttributeModificationEvent` adding attack damage to all types; default profiles by mob category.
- Acceptance: Host cows, sheep, zombies and modded goal-based mobs attack players and uninfected mobs; cleansing restores normal behaviour after reload. Gametests.
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

### P3.3: Infection, loot and data-driven overrides

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1
- Scope: Infection on kill by a host and on prolonged time in festering land (that source stubbed until phase 4); `remission:is_corrupted` loot condition and default global loot modifier (host tissue); `#remission:immune` tag; `remission:mob_profile` datapack registry (attributes, behaviour profile, `convert_to`).
- Acceptance: Schemas in remission-data-formats match the code; immune mobs never become hosts; overrides reload with `/reload` where possible; gametests for each.
- Skills: neoforge-mod-dev, remission-data-formats, minecraft-datapack

Exit criteria: gametests pass; harness scene `behaviour/conversion` shows a host infecting a lineup, reviewed.

## Phase 4: Festering land

Goals: the Strain spreads through land from nidi, visibly, and keeps progressing while chunks are unloaded; other mods and scripts can observe and steer it.

### P4.1: Chunk corruption levels and spread engine

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1
- Scope: Chunk attachment, `remission:nidus` block entity and level index, tick-budgeted spread, catch-up on load from stored game time, config for rates and budgets.
- Acceptance: Deterministic spread in gametests; catch-up bounded per tick; server tick cost measured on foundry and recorded in the PR.
- Skills: neoforge-mod-dev, minecraft-world-generation

### P4.2: Festering stages and biome conversion

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P4.4
- Scope: `remission:festering_grass`, `remission:festering_log`, `remission:festering_leaves`, `remission:festered_stone` (blocks + placeholder textures + datagen), staged transformation, biome flip to `remission:fester` with client resend, and a data-driven per-source-biome fester variant (contract in remission-data-formats) so modded biomes can fester into their own variant. Emits the block festered and chunk stage changed events.
- Acceptance: Harness scenes `world/stages` (one chunk per stage) and `world/biome_flip`, reviewed; no lighting or chunk-border artefacts. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, minecraft-resource-pack, minecraft-world-generation, visual-checks, content-provenance

### P4.3: Surges

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P3.1, P4.4
- Scope: Periodic surges that accelerate spread, spawn hosts and release miasma clouds (drifting airborne areas above festering land; their viral load effect is added by P5.1), with warnings; config and command to trigger. Emits surge started and surge ended events.
- Acceptance: Gametests for scheduling and cloud spawning and drift; harness scenes `world/surge` and `world/miasma_cloud`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

### P4.4: Public API and events

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1, P4.1
- Scope: Create `com.cubiecode.remission.api`: event base classes and conventions (server side, `NeoForge.EVENT_BUS`, posted after the change; batched changes post per chunk), and the hooks for systems already built: host corrupted, host cleansed, chunk stage changed, nidus destroyed, a cancellable Strain spread attempt with a rate multiplier, and queries for corruption stage at a position or chunk and whether an entity is a host. Document every event and query (fields, side, when fired, cancellable or not) in remission-data-formats and the ARCHITECTURE contracts table. The remaining DESIGN hooks (block festered, block purified, chunk entered remission, surge started/ended, viral load changed and its query) are added by the items that build those systems.
- Acceptance: Gametests fire each event and assert payloads, cancellation of the spread attempt stops spread, and the multiplier changes its rate; queries return correct values for host and non-host entities and every stage; nothing outside `api/` is documented as public.
- Skills: neoforge-mod-dev, remission-data-formats, minecraft-testing
- Parallel: Owns `api/` and the API contract section in its wave

Exit criteria: gametests pass; spread and catch-up timings recorded; scenes reviewed; the API is documented.

## Phase 5: Viral load and early purification

Goals: the player can survive the Strain and push it back locally.

### P5.1: Viral load

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P4.4, P4.3
- Scope: Player attachment `viral_load`; gain in festering chunks, from drinking water from (or swimming in) water in festering chunks, and from flying through miasma clouds; land gain falls off with height above the ground (config: on/off, falloff curve), so airborne players and vehicles avoid the worst of it; decay; escalating debuffs; HUD element; sync. Emits viral load changed and adds the viral load query.
- Acceptance: Gametests for gain (land, drinking, swimming, miasma clouds), altitude falloff (on and off) and decay; harness scene `survival/hud`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

### P5.2: Handheld purifier, quarantine pylons and mendroot

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P4.2, P4.4
- Scope: Handheld purifier item, `remission:quarantine_pylon` block with radius, `remission:mendroot` cleansing plant (plantable, so automation can place it); purified chunks enter remission and grant remission zone bonuses (config). Emits block purified and chunk entered remission.
- Acceptance: Gametests for radius, cleansing and remission entry; harness scene `purification/pylon_radius` (before/after). Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

Exit criteria: a player can enter, survive and cleanse festering land in a dev world, leaving chunks in remission; scenes reviewed.

## Phase 6: Research

Goals: knowledge is gathered from the world and recorded.

### P6.1: Scanner and data items

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.1
- Scope: Scanner item scanning hosts and blocks into data items (data components).
- Acceptance: Gametests; harness scene `research/scanner`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, content-provenance

### P6.2: Bestiary

- Status: `todo`
- Owner:
- PR:
- Depends on: P6.1
- Scope: Bestiary UI listing host types derived from installed entity types; scan progress per player.
- Acceptance: Host type count scales with dev-runtime mods; harness scene `research/bestiary`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

Exit criteria: scanning works for vanilla and modded hosts; bestiary screenshots reviewed.

## Phase 7: Tech chain

Goals: the Strain becomes a resource processed by machines into tools for the fight.

### P7.1: Miasma and samples

- Status: `todo`
- Owner:
- PR:
- Depends on: P3.3
- Scope: `remission:miasma` fluid (and Mekanism chemical `remission:miasma` via optional integration in `integration/mekanism/`), `remission:host_tissue`, `remission:nidus_core` and `remission:festered_soil` sample items and their loot.
- Acceptance: Fluid renders and flows; Mekanism integration loads only when Mekanism is present. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

### P7.2: Analyzer, sequencer and synthesizer

- Status: `todo`
- Owner:
- PR:
- Depends on: P7.1
- Scope: Three machines with recipe types `remission:analyzing`, `remission:sequencing`, `remission:synthesizing`, energy, standard item and fluid capabilities (so storage networks can feed them), recipe viewer categories (a JEI plugin, shown in EMI through its JEI compatibility; native EMI plugin only if needed), default recipes via datagen.
- Acceptance: Recipe schemas documented; EMI (with JEI installed) and JEI alone both show them; gametests run each machine. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, remission-data-formats, content-provenance

### P7.3: Serums, purifier fuel, Counterstrain prototypes and KubeJS integration

- Status: `todo`
- Owner:
- PR:
- Depends on: P7.2, P5.2
- Scope: Serums, purifier fuel and unstable Counterstrain prototypes and their effects; verify KubeJS can add/remove/replace Remission recipes (schema registration if needed).
- Acceptance: A sample KubeJS script edits each recipe type in a dev run; documented in kubejs-scripting. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, kubejs-scripting, content-provenance

Exit criteria: full chain from sample to serum works in a dev world; machine screenshots reviewed.

## Phase 8: Quest integration

Goals: quests and scripts can observe and drive Remission.

### P8.1: Advancement triggers and stats

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P6.1
- Scope: Triggers and stats for region % cleansed, nidus destroyed (`remission:nidus_destroyed`, stat `remission:nidi_destroyed`), host scanned, surge survived, and others the pack needs.
- Acceptance: Schemas in remission-data-formats; FTB Quests tasks gate on them in a dev run.
- Skills: neoforge-mod-dev, remission-data-formats, ftb-quests-authoring

### P8.2: Quest-fired events and map overlay

- Status: `todo`
- Owner:
- PR:
- Depends on: P8.1, P4.3
- Scope: Commands for quests and scripts to start surges, seed nidi (`/remission nidus seed <pos>`) or advance regions; optional JourneyMap overlay of festering chunks and remission zones via its polygon overlay API (`integration/journeymap/`, `compileOnly` API, guarded by `ModList`).
- Acceptance: A quest reward fires an event in a dev run; overlay screenshot reviewed (harness scene `quest/map_overlay`). Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, ftb-quests-authoring, visual-checks, content-provenance

### P8.3: KubeJS event bindings

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.4, P7.3
- Scope: Expose the public API events (and queries) to KubeJS scripts without a hard KubeJS dependency (an optional `integration/kubejs/` plugin registering an event group, guarded by `ModList`), with a sample server script under `pack/kubejs/server_scripts/` that reacts to a nidus being destroyed and cancels spread into a chunk; documented in kubejs-scripting and remission-data-formats.
- Acceptance: In a dev run the sample script receives each bound event, cancellation and the rate multiplier work from script, ProbeJS shows typed declarations, and Remission still loads without KubeJS.
- Skills: neoforge-mod-dev, kubejs-scripting, remission-data-formats
- Parallel: After P8.2 (one contract owner)

Exit criteria: a sample quest chain gates on and fires Remission events; a sample KubeJS script reacts to API events.

## Phase 9: Endgame cure

Goals: the Counterstrain heals the world.

### P9.1: Counterstrain spread and resistant variants

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P7.3
- Scope: Reverse spread on the same engine (emitting the purification and remission events); resistant variants (the `CorruptionState` `variant` field) reacting to the player's methods.
- Acceptance: Gametests for reverse spread and adaptation; timings recorded.
- Skills: neoforge-mod-dev

### P9.2: Final deployment event and world healing

- Status: `todo`
- Owner:
- PR:
- Depends on: P9.1, P4.3
- Scope: `remission:dispersal_payload` block that deploys the Counterstrain, the defence event around it, then visible healing back to original blocks/biomes. The final deployment works with a ground-based payload alone. The payload's API (a dispersal source with a position, ticked by its holder) must not assume a placed block, so a moving holder such as a Create contraption can carry it (P10.6); document it in remission-data-formats.
- Acceptance: A ground-based payload completes the final deployment in a gametest and a dev run with no optional mods; harness scenes `cure/before`, `cure/after`, reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

Exit criteria: a dev world can be fully cured; scenes reviewed.

## Phase 10: Mod integrations

Goals: the pack's mods take part in the fight against the Strain, each creating a progression gate (DESIGN "Pack integrations"). Pack-side tie-ins use KubeJS and datapacks on Remission's surfaces; mod-side tie-ins live in `integration/<modid>/` with `compileOnly` APIs, guarded by `ModList`, and have gametests. Mods considered and rejected for 1.21.1 are listed in DESIGN ("Considered and unavailable on 1.21.1"). Every mod added follows DESIGN's [distribution](docs/DESIGN.md#distribution) policy: available on CurseForge, and sourced from it.

### P10.1: Integration membership

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.4, P8.3
- Scope: Add the verified mods from CurseForge with `packwiz cf add` (PneumaticCraft: Repressurized, Occultism, Serene Seasons, Farmer's Delight, Productive Bees, Iron's Spells 'n Spellbooks, Waystones, Immersive Engineering, MineColonies with its required mods, Sophisticated Backpacks and Storage, Simple Voice Chat, Create Aeronautics with Sable, the chosen field journal mod, Biomes O' Plenty, Terralith) and their required dependencies; record versions and IDs; note beta-only files. Re-source the existing Modrinth-sourced pack mods from CurseForge (same files) and drop the separate Better Advanced Tooltips entry (Modrinth-only; KubeJS bundles it), so the CurseForge export embeds no jars it may not redistribute. Add `compileOnly` APIs and dev-runtime entries for every mod-side integration in phase 10 to `mod/build.gradle`/`mod/gradle.properties` and optional dependency entries to the `neoforge.mods.toml` template. Update README stack and the pack changelog.
- Acceptance: `tools/pack.sh check` passes; `tools/pack.sh export` adds every mod to the CurseForge manifest and embeds none under `overrides/mods/`; the full pack starts headlessly on foundry (dedicated server and client) with the renderer verified; every new file name says neoforge/1.21.1; `./gradlew build runGameTestServer` passes with and without dev-runtime mods.
- Skills: packwiz-pack, neoforge-mod-dev, playtesting, headless-client
- Parallel: Runs alone; owns `pack/pack.toml`, `pack/mods/` and build files for the phase

### P10.2: Tech integrations

- Status: `todo`
- Owner:
- PR:
- Depends on: P10.1, P4.4, P8.3, P7.3, P5.2
- Scope: Create (mod-side): fans with a purifying catalyst cleanse blocks in their stream, fan walls act as firebreaks through the spread attempt event, track in festering chunks degrades. PneumaticCraft (pack-side): pressurised miasma analysis recipes; drones planting mendroot. AE2: research data storage and sequencer feeding (pack-side), cable degradation in festering chunks without shielding (mod-side). Mekanism (pack-side): late-game sequencing and Counterstrain stabilisation processing on the P7.1 miasma chemical.
- Acceptance: Gametests for each mod-side hook; the pack-side recipes appear in EMI; harness scenes `integration/create_fan_purify` and `integration/ae2_cable_decay`, reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, kubejs-scripting, remission-data-formats, visual-checks, content-provenance

### P10.3: Magic integrations

- Status: `todo`
- Owner:
- PR:
- Depends on: P10.1, P4.4, P8.3, P5.2
- Scope: Ars Nouveau (mod-side): a Cleanse glyph, host summons, unstable source from festering land that can backfire. Occultism (pack-side): rituals harvesting spirits from hosts, bound spirits immune to the Strain working in festering zones.
- Acceptance: Gametests for the Ars Nouveau hooks; a dev run casts Cleanse and performs a host ritual; harness scene `integration/ars_cleanse`, reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, kubejs-scripting, visual-checks, content-provenance

### P10.4: World and survival integrations

- Status: `todo`
- Owner:
- PR:
- Depends on: P10.1, P4.4, P8.3, P4.3, P5.1, P5.2
- Scope: Serene Seasons (pack-side): seasonal spread multiplier via the spread attempt event, surges at season changes. Farmer's Delight (pack-side): festering crops, purifying meals granting viral load resistance. Productive Bees: Strain-resistant bee lines (pack-side) whose pollination cleanses land (pack-side if a data-driven bee effect exists, otherwise mod-side; record the decision). Waystones (mod-side): waystones in festering chunks go dark until purified; remission zones as fast-travel hubs.
- Acceptance: Gametests for each mod-side hook; seasonal rates measured in a dev run; harness scenes `integration/waystone_dark` and `integration/festering_crops`, reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, kubejs-scripting, minecraft-datapack, visual-checks, content-provenance

### P10.5: Combat integrations

- Status: `todo`
- Owner:
- PR:
- Depends on: P10.1, P4.4, P3.3
- Scope: Iron's Spells 'n Spellbooks: spellcasting host bosses (pack-side mob profiles and loot) and a Strain-themed spell school they drop (mod-side).
- Acceptance: Gametests for the school registration and drops; a dev run fights a host boss and casts a school spell; harness scene `integration/host_boss`, reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, remission-data-formats, visual-checks, content-provenance

### P10.6: Logistics, settlement and aerial integrations

- Status: `todo`
- Owner:
- PR:
- Depends on: P10.1, P4.4, P8.3, P5.2, P7.1, P9.2
- Scope: Generic item contamination in Remission (`remission:contaminated` data component, gain while carried through festering chunks, decontamination recipe; documented as a contract). Sophisticated Backpacks/Storage (mod-side): a sealed upgrade that protects contents. Immersive Engineering: a quarantine arc multiblock holding a large frontier (mod-side); excavator mineral mixes yielding nidus cores from festered deposits (pack-side). MineColonies (mod-side): colonies founded only in remission zones, guards target hosts, colonists vulnerable to the Strain and surges. Create and Create Aeronautics: a contraption-carried dispersal payload (mod-side in `integration/create/`, on the P9.2 payload API) that disperses the Counterstrain from any moving Create contraption, including airships; recipes and gating for aerial access (pack-side).
- Acceptance: Gametests for contamination and each mod-side hook, including a payload dispersing while its contraption moves; a dev run founds a colony in a remission zone and is refused in festering land; harness scenes `integration/quarantine_arc`, `integration/sealed_backpack` and `integration/airship_dispersal`, reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, kubejs-scripting, remission-data-formats, visual-checks, content-provenance

### P10.7: Atmosphere and lore integrations

- Status: `todo`
- Owner:
- PR:
- Depends on: P10.1, P4.4, P8.1, P4.2
- Scope: Simple Voice Chat (mod-side, server-configurable, off by default): hosts are drawn to speaking players in festering zones. Field journal (pack-side; choose Patchouli or Modonomicon and record why): entries unlocked by host scans, found logs and destroyed nidi. Biomes O' Plenty and Terralith (pack-side): per-biome fester variants.
- Acceptance: Gametest for the voice hook's config gate; journal entries unlock in a dev run; harness scenes `integration/field_journal` and `integration/fester_variants`, reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, minecraft-world-generation, visual-checks, content-provenance

Exit criteria: every available integration in DESIGN's table loads, its gate works in a dev run of the full pack, mod-side hooks pass gametests, and the listed scenes are reviewed. Remission still loads with none of these mods installed.

## Phase 11: Pack assembly

Goals: Counterstrain becomes a playable pack.

### P11.1: Story and quest chapters

- Status: `todo`
- Owner:
- PR:
- Depends on: P8.1, P10.7
- Scope: Lore, chapters (locate the nidus → research → gear up → destroy the nidus → purify → next region → Counterstrain), field journal text.
- Acceptance: Full quest line completable in a playtest. Ledger updated for every quest, lore and journal text block (status `draft`).
- Skills: ftb-quests-authoring, playtesting, content-provenance

### P11.2: Crash-site start, recipe gating and worldgen

- Status: `todo`
- Owner:
- PR:
- Depends on: P11.1, P10.2, P10.3, P10.4, P10.5, P10.6
- Scope: Starting structure/map, KubeJS progression gating across Mekanism/AE2/Create/Ars Nouveau and the phase 10 integration gates (kinetics before holding a frontier, pressure tech before automated reclamation, shielded cabling for the research network, sealed storage for expeditions, Mekanism for the late-game lab, boss-gated spells), datapack worldgen tweaks for nidi.
- Acceptance: New world starts at the crash site; gated recipes verified in EMI; each DESIGN integration gate is enforced in a playtest; harness scene `start/crash_site`. Ledger updated; placeholders marked.
- Skills: kubejs-scripting, minecraft-datapack, minecraft-world-generation, playtesting, visual-checks, content-provenance

### P11.3: Balancing pass

- Status: `todo`
- Owner:
- PR:
- Depends on: P11.2
- Scope: Spread rates, viral load, recipe costs, quest rewards, integration gate costs.
- Acceptance: A documented playtest from start to Counterstrain.
- Skills: playtesting

Exit criteria: complete playthrough recorded; screenshots of each chapter milestone reviewed.

## Phase 12: Polish and release

Goals: performant, compatible, with final art and writing, published.

### P12.1: Performance and compatibility

- Status: `todo`
- Owner:
- PR:
- Depends on: P11.3
- Scope: Server tick and client FPS under heavy spread measured on foundry; compatibility with the pack's mods and common extras.
- Acceptance: Numbers recorded against targets set in the item; harness scene `perf/heavy_spread` with FPS overlay.
- Skills: headless-client, visual-checks, playtesting

### P12.2: Art and writing pass

- Status: `todo`
- Owner:
- PR:
- Depends on: P11.3
- Scope: Replace every `placeholder` and `draft` row in CONTENT-PROVENANCE with human-made or licensed content, or accept a draft as final with a recorded human review (reviewer and date in the row's notes). Remove placeholder markers from replaced textures. Store page copy and gallery images for Modrinth and CurseForge.
- Acceptance: No `placeholder` rows remain for shipped content; every remaining `draft` has a recorded review or is replaced; the provenance check passes; store pages use final art; affected harness scenes re-reviewed.
- Skills: content-provenance, visual-checks, minecraft-resource-pack
- Parallel: With P12.1

### P12.3: Publishing

- Status: `todo`
- Owner:
- PR:
- Depends on: P12.1, P12.2
- Scope: Remission published to Modrinth and CurseForge; Counterstrain published to CurseForge (the CurseForge zip is the release artifact; a Modrinth pack release stays a DESIGN revisit item); publishing steps in release workflows; once Remission is published, add it to the pack via packwiz from its CurseForge release (`packwiz cf add`) so the CurseForge pack references it.
- Acceptance: A `mod-v*` tag publishes Remission to both platforms; a `pack-v*` tag publishes the CurseForge pack; the pack references the released mod from CurseForge; store copy and images are the P12.2 final rows.
- Skills: release, minecraft-ci-release, content-provenance

Exit criteria: mod and pack 1.0 released.
