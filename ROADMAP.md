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

- P1.5 (follow-up to P1.4) is next: it can run in any wave or alone, so content can be replaced as features land, and must land before the art pass (P12.2), which uses it.
- A: P1.1 + P2.1 + P1.4. P2.1 owns the registry and network spine; P1.1 owns the dev-only harness and `tools/run-client-headless.sh`; P1.4 owns the provenance check script and its `docs.yml` step. P1.4 can also run in any later early wave.
- B: P2.2 + P2.3 + P1.2 + P1.3, after A. P1.2 owns comparison scripts; P1.3 owns `gpu-smoke.yml`; P2.2 owns client rendering; P2.3 owns commands.
- P2.4 (follow-up to P2.2) can join any wave after B, or run alone. It owns `client/` host rendering and the `corruption/*` scenes and their references.
- C: P3.1 + P3.3 + P4.1 + P6.1, after P2.1. Agree the `mob_profile` record (P3.3 owns the contract) before P3.1 starts; registry and lang edits append-only; one registry-spine owner.
- D: P4.4 + P3.2 + P6.2. P4.4 owns the `api/` package and the API section of remission-data-formats.
- P4.5 (follow-up to P4.1) runs before P4.3 or alone; it owns `world/SpreadEngine`.
- P6.3 (follow-up to P6.2) can join any wave or run alone; it owns `research/Bestiary` and the bestiary scene reference.
- E: P4.2 (done), then P4.3 (after P4.5 and P8.1, both done). P4.3 owns surge scheduling, miasma clouds, its surge API events and the surge survived trigger and stat, and is the remission-data-formats owner while it runs; append-only edits in `api/` and `quest/`.
- P4.8 (follow-up to P4.6 and P4.7) runs alone or before wave F; it owns the vein block and the contact source and must merge before P5.2.
- P4.6 and P4.7 (follow-ups to P4.2 and P3.3) can join wave E with P4.3 or run alone. P4.6 owns `world/FesteringStages` and `world/FesterMaps` and runs before P5.2; P4.7 owns `infection/`. With P4.3 in the same wave, appoint one owner for config specs and one for remission-data-formats.
- F: P5.1 + P5.2 + P7.1. P5.1 owns the player attachment and HUD; one remission-data-formats owner (P5.2).
- P5.3 → P5.4 serially, after P5.1. P5.3 owns the revenant entity, `integration/curios/` and its build-file entries; P5.4 owns its payload and coordinates `integration/journeymap/` with P8.2 (never in the same wave).
- G: P7.2 → P7.3 serially (recipe types and KubeJS schemas are contracts), after P8.1 (done; later items append their triggers and stats to its contracts).
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

- Status: `done`
- Owner: feat/p1.2-motion-capture
- PR: https://github.com/CubieCode/counterstrain/pull/10, https://github.com/CubieCode/counterstrain/pull/13
- Validation: PRs #10 and #13; compare self-test and GPU runs (unchanged run exit 0, changed scene exit 1 with diff image), `--record` clip 1280x720 from the RTX 4070; reference `smoke/mob_lineup` reviewed.
- Depends on: P1.1
- Scope: Decide where reviewed reference images live (a small committed set, or workflow artifacts plus `.local/`) and keep the repository small. Add a tolerant comparison script (per-pixel diff threshold, diff image output) using no root-only tools. Optional ffmpeg clip capture of the Xvfb display for motion checks, only if ffmpeg is available on foundry without root; report if not.
- Acceptance: Changing the scene produces a reported diff with a diff image; an unchanged run passes. Motion capture works, or is documented as unavailable with the reason.
- Skills: visual-checks, headless-client
- Parallel: After P1.1

### P1.3: GPU workflow runs scenes

- Status: `done`
- Owner: feat/p1.3-gpu-workflow-scenes
- PR: https://github.com/CubieCode/counterstrain/pull/11
- Validation: PR #11; gpu-smoke runs 38121198473 (pass, compare match), 38120934939 (broken scene fails), 38121021980 (llvmpipe fails).
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

### P1.5: Local content editor

- Status: `done`
- Owner: `feat/p1.5-local-content-editor`
- PR: https://github.com/CubieCode/counterstrain/pull/60
- Depends on: P1.4
- Scope: A small local web app for replacing AI-generated player-facing content without hand-editing files: `tools/content-editor.sh [--port N] [--lan]`, a Python standard library HTTP server (no new Python dependencies, no root, no build step; plain HTML, CSS and JavaScript served from `tools/content-editor/`, plus one vendored viewer, below). It is built on the provenance ledger: it lists `docs/CONTENT-PROVENANCE.md` rows, filterable by status (`placeholder`, `draft`, `final`), type and roadmap item, and edits the files they point at.
  - **Text:** for `lang` rows, shows each matching key's current English string (rows with `*` expand to every matching key) with the row's notes as guidance, and saves edits to `en_us.json`, keeping key order and formatting and refusing invalid JSON or a changed set of `%s` arguments. The same page can accept a string unchanged as final. Markdown text rows (`docs/LORE.md` now; store copy, field journal entries and books once their files exist) open in a source and live preview editor with a heading outline, a diff against main and warnings for broken relative links; saving or accepting updates the row the same way. `file.properties:key` rows (the mod description) get a one-line in-place editor. Text pages warn about words the glossary replaces and, when a local Ollama model is running, can ask it to point out problems against the glossary and the row's notes; the model never writes text, so saved text stays `human`.
  - **Textures:** previews the current PNG at pixel scale, tiled 3×3 (host overlays and block faces must tile) and next to the upload; accepts a PNG upload and checks it before writing: a valid PNG, the same size as the original or the same aspect ratio at a power-of-two scale, an animation strip only with a matching `.mcmeta`, and the 2×2 `#FF00FF` marker present exactly when the chosen status is `placeholder`. Textures used by a block model also render on that block and on a 3×3 wall in the 3D preview. Host overlay patterns (`textures/entity/host/`) get a reference host preview instead: the server composites the pattern onto a mob texture with the same rules as `host_overlay.fsh` (one pattern texel per mob texel, masked where the mob texture's alpha is below 0.1, at `overlayIntensity`, `veins_glow` on top from stage 3) and the page draws it on a humanoid or quadruped box model, with stages 1–3 side by side. Base textures are a built-in procedural mannequin or vanilla mob textures read at run time from the local NeoForge client resources jar; they are never copied into the repository.
  - **Models and sounds:** uploads hand-authored block or item model JSON (must parse; every texture it names must exist) and `.ogg` sounds for existing rows. Datagen output under `mod/src/generated/` is never written: those rows are shown read-only with a note that the change belongs in the datagen provider or a hand-authored model under `mod/src/main/resources/`. Block and item models, generated ones included, render in a 3D preview with the textures they name, both as on disk and with replacements pending in the batch.
  - **3D viewer:** deepslate (misode/deepslate, MIT) renders the 3D previews. Its single UMD bundle is vendored at an exact version in `tools/content-editor/vendor/` with its licence file; nothing loads from a CDN. It is a dev-tool dependency, not shipped in the mod or pack, and the mannequin texture is a generated dev-tool asset with no ledger row.
  - **Ledger:** every save updates its row in the same change: origin (`human`, or `licensed` with source and licence), status, and for an accepted draft the reviewer and date in the notes, as P12.2 requires. Rows are never deleted. Replacing or accepting one key of a `*` lang row adds an exact-key row for it and leaves the `*` row unchanged; the most specific row wins. Accepting every key of a `*` row updates that row. This precedence is documented in `docs/CONTENT-PROVENANCE.md` and the content-provenance skill, and `tools/check-provenance.sh` rejects two exact rows for the same key.
  - **Git:** edits go to a dedicated content worktree and branch (`content/<date>-<slug>`), never to `main` or another lane's worktree. A "Save batch" step runs `tools/check-provenance.sh`, `python3 -I tools/check-docs.py` and `git diff --check` (plus `tools/pack.sh refresh` and `check` when the batch changes files under `pack/`), shows the results, commits with a plain message and can open a PR with `gh pr create --body-file` listing every changed row (or push to the branch's open PR); it never pushes to `main`, merges or publishes. **Update from main** rebases a committed batch onto `origin/main`, aborting on a conflict, and **Finish batch** removes a batch's worktree and branch once its PR is merged or closed.
  - **Access:** binds to `127.0.0.1` by default (from a PC, `ssh -L`, as for `tools/pack.sh serve`). `--lan` binds every interface and requires a random token printed at start in every request. Writes only to paths the ledger names (or new rows the user adds under the mod's `assets/` and `pack/`), with an upload size limit.
  - Quest text and field journal entries are out of scope until their files exist (P11.1, P10.7); the row model must not rule them out.
- Acceptance: Python `unittest` tests (no network, temporary repository fixture) for lang editing (order, formatting, argument check), texture validation (size, scale, marker against status, `.mcmeta`), refusing generated paths and paths outside the ledger, ledger row updates including exact-key row precedence, Markdown and properties saves, vocabulary warnings and the advisory review (against a fake Ollama), updating from main (rebase and conflict) and finishing a batch, and the host composite (texel repeat at 1:1, the alpha mask, intensity, the glow layer from stage 3) against small fixture PNGs; `tools/check-provenance.sh` passes after a scripted edit session. A manual session on foundry, reached from a PC through `ssh -L`, replaces one lang string and one placeholder texture, accepts one draft string, and opens a draft PR from the content branch; the PR is recorded and closed unmerged. Screenshots of the list, text and texture pages, a 3D model preview and the host preview in the PR (the GUI is a dev tool, not player-facing, so no ledger rows). Documented in README, docs/DEV-ENVIRONMENT.md and the content-provenance skill.
- Skills: content-provenance, docs-sync
- Parallel: Any wave; owns `tools/content-editor.sh`, `tools/content-editor/` (including `tools/content-editor/vendor/`) and its tests. Content branches it creates follow the serialization gates: a batch that touches `en_us.json` or texture references rebases and reruns its checks before merge.

Exit criteria: harness scene `smoke/mob_lineup` runs headlessly, quits by itself, and its screenshots are reviewed and stored as the first reference. The GPU workflow runs it. The provenance check runs in CI. P1.5, a follow-up added after phase 1 closed, is not part of these exit criteria.

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

- Status: `done`
- Owner: feat/p2.2-host-render-layer
- PR: https://github.com/CubieCode/counterstrain/pull/12
- Validation: PR #12; gpu-smoke run 38122453882 passes `corruption/stages_vanilla`, `corruption/stages_modded` and `smoke/mob_lineup` with compare match on the RTX 4070; screenshots reviewed; references committed.
- Depends on: P2.1, P1.1
- Scope: Layer added to every living-entity renderer (including players) in `EntityRenderersEvent.AddLayers`, re-rendering the parent model with a stage-dependent Strain overlay; client config for intensity. Placeholder art.
- Acceptance: Works on vanilla and dev-runtime modded mobs (including non-standard models), baby mobs, armour; no crash with renderers lacking a model. Harness scenes `corruption/stages_vanilla`, `corruption/stages_modded`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance
- Parallel: After P2.1

### P2.3: Debug commands

- Status: `done`
- Owner: feat/p2.3-debug-commands
- PR: https://github.com/CubieCode/counterstrain/pull/9
- Validation: PR #9; 6 command gametests (12/12 total) in CI.
- Depends on: P2.1
- Scope: `/remission infect <targets> <level>`, `/remission cleanse <targets>`, `/remission inspect <target>`; permission level 2.
- Acceptance: Commands work with selectors; gametests drive them. Ledger updated for command feedback lang strings.
- Skills: neoforge-mod-dev, content-provenance
- Parallel: With P2.2

### P2.4: Host overlay on separately modelled layers

- Status: `done`
- Owner: feat/p2.4-layer-overlay
- PR: https://github.com/CubieCode/counterstrain/pull/27
- Validation: PR #27; companion overlay on armour, wool, slime gel, drowned, horse and wolf armour layers (35 in the dev runtime, no mixin); `corruption/stages_layers` references reviewed, `stages_vanilla`/`stages_modded` updated only on layered mobs; gpu-smoke green on RTX 4070; CI green.
- Depends on: P2.2
- Scope: Extend the Strain overlay to render layers that draw their own model or texture over the body, which P2.2's layer cannot reach: humanoid armour (players, zombies, skeletons, armour stands), sheep wool, slime outer gel, the drowned's outer layer, horse and wolf armour, and the same vanilla layer helpers when modded renderers use them. Emissive and eye layers (spider and enderman eyes) stay untouched. Prefer wrapping or re-registering those layers from `EntityRenderersEvent.AddLayers` without mixins; if a mixin turns out to be necessary, report it first, since adding a mixin config changes the mod metadata and build files (serialization gates). Reuse P2.2's render type, patterns and `hosts.overlayIntensity`; no new art unless a layer needs it. GeckoLib-style and other non-`LivingEntityRenderer` renderers remain out of scope.
- Acceptance: At stages 1–3, armoured zombies and players, sheep, slimes, drowned and an armour stand show the overlay on those layers, following each layer's own texture cutout. Eye layers are unchanged, non-hosts render exactly as before (`smoke/mob_lineup` still matches its reference) and nothing crashes with modded layers in the dev runtime. New harness scene `corruption/stages_layers` with reviewed references. `corruption/stages_vanilla` and `stages_modded` references are updated only for intended changes, explained in the PR. Ledger updated if new art is added; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance
- Parallel: Any wave after B; owns `client/` host rendering and its harness scenes. Its GPU runs compete with other visual lanes.

Exit criteria: gametests pass; harness screenshots show stages 0–N on a vanilla and a modded lineup, reviewed. P2.4, a follow-up added after the phase was accepted, is not part of these exit criteria.

## Phase 3: Host behaviour

Goals: hosts of any kind behave as hostile Strain-infected creatures, infect others, drop samples and are tunable by data.

### P3.1: Goal injection and attribute patching

- Status: `done`
- Owner: feat/p3.1-host-goals
- PR: https://github.com/CubieCode/counterstrain/pull/21
- Validation: PR #21; host goal gametests (cow, zombie, sheep and Mekanism robit attacks, host-on-host veto, cleanse in-session and after reload) in the dev runtime and `-Pdev_runtime_mods=false`; CI green.
- Depends on: P2.1
- Scope: `EntityJoinLevelEvent` stripping of passive goals and injection of target/attack goals; `EntityAttributeModificationEvent` adding attack damage to all types; default profiles by mob category.
- Acceptance: Host cows, sheep, zombies and modded goal-based mobs attack players and uninfected mobs; cleansing restores normal behaviour after reload. Gametests.
- Skills: neoforge-mod-dev
- Parallel: With P3.3

### P3.2: Brain, flying and swimming fallbacks

- Status: `done`
- Owner: feat/p3.2-behaviour-fallbacks
- PR: https://github.com/CubieCode/counterstrain/pull/26
- Validation: PR #26; brain (incl. warden, breeze), flying and swimming host fallbacks with `remission:unsupported` default profile (villager, allay, frog); HostFallbackTests 73/73 gametests; deterministic `behaviour/fallbacks` scene reviewed, gpu-smoke green on RTX 4070; CI green.
- Depends on: P3.1
- Scope: Fallbacks for brain-based mobs (villager, piglin, axolotl, allay, frog), flyers (bat, phantom-like, parrot, modded) and swimmers (fish, squid, dolphin).
- Acceptance: Each category either attacks sensibly or is explicitly marked unsupported in its default profile. Harness scene `behaviour/fallbacks`.
- Skills: neoforge-mod-dev, visual-checks

### P3.3: Infection, loot and data-driven overrides

- Status: `done`
- Owner: feat/p3.3-infection-loot-profiles
- PR: https://github.com/CubieCode/counterstrain/pull/19
- Validation: PR #19; 50/50 gametests (kill-by-host infection, convert_to, immune, is_corrupted, host_samples, profile priority and behaviour override); gpu-smoke run 38126737395 passes every scene, with `corruption/stages_modded` stages 1–3 re-accepted because the wither is an immune boss.
- Depends on: P2.1
- Scope: Infection on kill by a host and on prolonged time in festering land (that source stubbed until phase 4); `remission:is_corrupted` loot condition and default global loot modifier (host tissue); `#remission:immune` tag; `remission:mob_profile` datapack registry (attributes, behaviour profile, `convert_to`).
- Acceptance: Schemas in remission-data-formats match the code; immune mobs never become hosts; overrides reload with `/reload` where possible; gametests for each.
- Skills: neoforge-mod-dev, remission-data-formats, minecraft-datapack

Exit criteria: gametests pass; harness scene `behaviour/conversion` shows a host infecting a lineup, reviewed.

## Phase 4: Festering land

Goals: the Strain spreads through land from nidi, visibly, and keeps progressing while chunks are unloaded; other mods and scripts can observe and steer it.

### P4.1: Chunk corruption levels and spread engine

- Status: `done`
- Owner: feat/p4.1-chunk-spread
- PR: https://github.com/CubieCode/counterstrain/pull/20
- Validation: PR #20; 8 spread gametests (determinism, bounded catch-up, nidus removal); foundry SpreadBenchmark: 0.9 µs/tick mean, 0.13 ms max for 16 nidi over 1,024 chunks, 0.29 ms max per catch-up tick.
- Depends on: P2.1
- Scope: Chunk attachment, `remission:nidus` block entity and level index, tick-budgeted spread, catch-up on load from stored game time, config for rates and budgets.
- Acceptance: Deterministic spread in gametests; catch-up bounded per tick; server tick cost measured on foundry and recorded in the PR.
- Skills: neoforge-mod-dev, minecraft-world-generation
- Validation: PR #20; 8 spread gametests (36/36 total after rebasing on P3.1 and P6.1); `SpreadBenchmark` on foundry, 1,024 loaded chunks: 16 nidi at default config add 0.9 µs/tick mean, 0.13 ms max; 64 nidi with a pass every second 27 µs mean, 0.09 ms max; catch-up of 1,024 chunks takes 32 ticks at 0.29 ms max.

### P4.2: Festering stages and biome conversion

- Status: `done`
- Owner: feat/p4.2-festering-stages
- PR: https://github.com/CubieCode/counterstrain/pull/41
- Validation: PR #41; four festering blocks, `festers_into` and `fester_variant` data maps, biome flip to `remission:fester` via vanilla biome resend, `BlockFesteredEvent`; 101/101 gametests; GPU scenes `world/stages` and `world/biome_flip` (RTX 4070) reviewed and deterministic; about 0.12 ms per tick at 64 columns; CI and gpu-smoke green.
- Depends on: P4.1, P4.4
- Scope: `remission:festering_grass`, `remission:festering_log`, `remission:festering_leaves`, `remission:festered_stone` (blocks + placeholder textures + datagen), staged transformation, biome flip to `remission:fester` with client resend, and a data-driven per-source-biome fester variant (contract in remission-data-formats) so modded biomes can fester into their own variant. Emits the block festered and chunk stage changed events.
- Acceptance: Harness scenes `world/stages` (one chunk per stage) and `world/biome_flip`, reviewed; no lighting or chunk-border artefacts. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, minecraft-resource-pack, minecraft-world-generation, visual-checks, content-provenance

### P4.3: Surges

- Status: `done`
- Owner: `feat/p4.3-surges`
- PR: https://github.com/CubieCode/counterstrain/pull/48
- Validation: PR #48; scheduled and commanded surges (warning, spread factor before the spread attempt event, budgeted host waves from `#remission:surge_hosts` with `CorruptionCause.SURGE`, `remission:miasma_cloud` with no viral load), surge started/ended events, `surge_survived` trigger and `surges_survived` stat, `/remission surge`; 122/122 gametests; SpreadBenchmark within ARCHITECTURE ranges, a whole surge 1.2 µs mean per tick (max 1.1 ms on the start tick); GPU scenes `world/surge` and `world/miasma_cloud` (RTX 4070) reviewed and deterministic; CI and gpu-smoke green.
- Depends on: P4.1, P3.1, P4.4, P8.1
- Scope: Periodic surges that accelerate spread, spawn hosts (on the surface and in caves and other covered spaces under festering chunks) and release miasma clouds (drifting airborne areas above festering land; their viral load effect is added by P5.1), with warnings; config and command to trigger. Emits surge started and surge ended events. Adds the surge survived advancement trigger and stat, following P8.1's trigger and stat conventions.
- Acceptance: Gametests for scheduling, host spawning on the surface and underground, and cloud spawning and drift; harness scenes `world/surge` and `world/miasma_cloud`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

### P4.4: Public API and events

- Status: `done`
- Owner: feat/p4.4-public-api
- PR: https://github.com/CubieCode/counterstrain/pull/25
- Validation: PR #25; `com.cubiecode.remission.api` events (host corrupted/cleansed, chunk stage changed, nidus destroyed, cancellable spread attempt with multiplier, firebreaks honoured in catch-up) and queries; PublicApiTests 70/70 gametests; spread benchmark recorded in ARCHITECTURE; CI green.
- Depends on: P2.1, P4.1
- Scope: Create `com.cubiecode.remission.api`: event base classes and conventions (server side, `NeoForge.EVENT_BUS`, posted after the change; batched changes post per chunk), and the hooks for systems already built: host corrupted, host cleansed, chunk stage changed, nidus destroyed, a cancellable Strain spread attempt with a rate multiplier, and queries for corruption stage at a position or chunk and whether an entity is a host. Document every event and query (fields, side, when fired, cancellable or not) in remission-data-formats and the ARCHITECTURE contracts table. The remaining DESIGN hooks (block festered, block purified, chunk entered remission, surge started/ended, viral load changed and its query) are added by the items that build those systems.
- Acceptance: Gametests fire each event and assert payloads, cancellation of the spread attempt stops spread, and the multiplier changes its rate; queries return correct values for host and non-host entities and every stage; nothing outside `api/` is documented as public.
- Skills: neoforge-mod-dev, remission-data-formats, minecraft-testing
- Parallel: Owns `api/` and the API contract section in its wave

### P4.5: Live spread waits for catch-up

- Status: `done`
- Owner: feat/p4.5-catchup-ordering
- PR: https://github.com/CubieCode/counterstrain/pull/35
- Validation: PR #35; live passes skip chunks still queued for catch-up; gametest `livePassWaitsForCatchUp` plus 88/88 gametests; SpreadBenchmark live and catch-up figures within ARCHITECTURE's recorded ranges (catch-up still 32 ticks); CI green.
- Depends on: P4.1, P4.4
- Scope: Fix a P4.1 ordering bug found during P4.4: a live pass (`SpreadEngine.visit`) can step a chunk that has just loaded and is still queued for catch-up, which writes `last_update_game_time = now` so the queued catch-up then credits almost none of the time the chunk was away. Live passes must not advance a chunk (or its time) before its pending catch-up has run, for example by skipping queued chunks in `visit` until they are caught up, or by catching them up first within the tick budget. Firebreak and multiplier behaviour (P4.4) and the catch-up budget are unchanged. Update the catch-up rules in docs/ARCHITECTURE.md and remission-data-formats if their wording changes.
- Acceptance: A gametest reloads a chunk while a live pass is due and shows it ends at the same level as when no live pass runs before its catch-up; existing spread, catch-up and PublicApiTests still pass; `SpreadBenchmark` live and catch-up figures recorded in the PR with no meaningful regression.
- Skills: neoforge-mod-dev, minecraft-testing, remission-data-formats
- Parallel: Owns `world/SpreadEngine` and its tests; run it before P4.3 (surges also change spread) or alone.

### P4.6: Underground festering

- Status: `done`
- Owner: feat/p4.6-underground-festering
- PR: https://github.com/CubieCode/counterstrain/pull/45
- Validation: PR #45; cave pass festers air-facing blocks from stage 3 (complete at Y>=0, 1 in 4 below), optional `underground_stage` in `festers_into`, ore-replaceable tag defaults with new `festered_deepslate`; 106/106 gametests (5 new); cave pass 0.14-0.17 ms/tick mean at 8 layers; GPU scene `world/caves` (RTX 4070) reviewed, all scenes match references; CI green.
- Depends on: P4.2
- Scope: Festering reaches caves. P4.2 festers only the surface (down to 5 blocks into the ground), so a player can tunnel under a stage 4 chunk and see untouched rock. From a late stage (default stage 3), blocks with a `remission:festers_into` entry that face cave air become a festered skin on cave walls, floors and ceilings; rock inside the ground and ores (no default entry) are left alone, so mining stays viable. Festering is complete down to a depth limit (default about Y=0) and sparse, by a deterministic hash, below it; the limit is a constant, not config. Adds default entries for deepslate and tuff (with a festered deepslate block if festered stone does not read well there) and an optional per-entry underground stage in the data map (contract in remission-data-formats), so packs can tune or turn cave festering off. The cave skin must read clearly differently from sculk in the Deep Dark. The scan walks the chunk section by section, skips sections with no festerable block in their palette, runs on its own per-tick budget and resumes after an unload. Posts `BlockFesteredEvent` per replaced block. The biome flip already covers every height and is unchanged.
- Acceptance: Gametests for exposed-only replacement (rock inside the ground and ores untouched), the stage gate, the depth limit and sparse festering below it, determinism, turning it off through the data map, and resuming after an unload; per-tick cost measured on foundry and recorded in the PR next to P4.2's 0.12 ms; harness scene `world/caves` (a carved cave under chunks at each stage, including one in deepslate next to sculk), reviewed, with no lighting artefacts. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, minecraft-world-generation, remission-data-formats, visual-checks, content-provenance
- Parallel: Owns `world/FesteringStages`, `world/FesterMaps` and the festering section of remission-data-formats; do not run alongside P5.2, whose purification undoes festering.

### P4.7: Festering land infects mobs

- Status: `done`
- Owner: `feat/p4.7-festering-land-infection`
- PR: https://github.com/CubieCode/counterstrain/pull/46
- Validation: PR #46; budgeted exposure tracker (passes every 20 ticks, 256 checks and 32 conversions per tick), `remission:festering_exposure` attachment, `CorruptionCause.FESTERING_LAND`, `[infection]` config; 112/112 gametests; 400 mobs cost 0.24-0.50 ms per second steady, mass conversion capped at 1.2 ms ticks; CI green.
- Depends on: P3.3, P4.1, P4.4
- Scope: Build the festering land infection source that P3.3 stubbed (`Infection.canInfectFromFesteringLand`, profile flag `conversion.festering_land`). Mobs that pass that check build up exposure while inside a festering chunk at or above a minimum stage, at any height, underground included; exposure decays outside festering land and is saved with the mob. Past a threshold the mob becomes a host through the corruption API with a new `CorruptionCause` (festering land), and its `convert_to` profile entry applies as it does for infection on kill. Config: on/off, minimum stage and exposure time. Exposure is checked on an interval with a per-tick budget, not for every mob every tick. This is what creates the first hosts in normal play. Documents the source, the new cause and the config in remission-data-formats and docs/ARCHITECTURE.md.
- Acceptance: Gametests: an eligible mob in a festering chunk becomes a host after the exposure time, on the surface and in a cave; immune mobs, hosts and mobs whose profile sets `conversion.festering_land` to false never do; nothing happens below the minimum stage or with the source turned off; exposure decays after the mob leaves; `convert_to` applies; `HostCorruptedEvent` carries the new cause. Server tick cost with a few hundred mobs in festering land measured on foundry and recorded in the PR.
- Skills: neoforge-mod-dev, remission-data-formats, minecraft-testing
- Parallel: Owns `infection/` and its config section; appends to `api/CorruptionCause`.

### P4.8: Festering veins and contact infection

- Status: `done`
- Owner: feat/p4.8-festering-veins
- PR: https://github.com/CubieCode/counterstrain/pull/64
- Validation: PR #64; 135 gametests incl. FesteringVeinTests and ContactInfectionTests; vein and contact per-tick costs recorded in the PR; `world/veins` scene from the RTX 4070 reviewed, references committed.
- Depends on: P4.2, P4.6, P4.7
- Scope: Festering veins: a `remission:festering_vein` multiface block (as vanilla glow lichen and sculk veins) that creeps over the solid faces of blocks in festering chunks from a minimum stage, on the surface and in caves, without replacing them. Veins grow over any solid face, player-built blocks and bases included, so festering land threatens what the player builds; packs keep them off chosen blocks with a block tag (`#remission:vein_resistant`, empty by default). Growth is deterministic, budgeted per tick like the cave pass, resumes after an unload and catches up with the chunk's stage; a vein drops nothing without Silk Touch. Veins must read clearly differently from sculk veins. Contact infection: a `Mob` touching a block in `#remission:infectious` (standing on it, inside it, or against it; Remission ships festering grass, the festered blocks and festering veins) or in water in a festering chunk at or above the minimum stage (fish, squid, drowned, axolotls; the water itself is not replaced, the biome flip already tints it), becomes a host after `[infection] contactTicks` of contact (0 = on first touch), with a new `CorruptionCause` (contact), through the same eligibility and `convert_to` path as P4.7. Festering land exposure (P4.7) is unchanged and stays the slow background source. Players never take contact infection; P5.2 turns contact into viral load gain. Config: veins on/off and minimum stage, contact infection on/off and `contactTicks`. Posts `BlockFesteredEvent` per vein placed. Documents the block, tags, cause and config in remission-data-formats and docs/ARCHITECTURE.md.
- Acceptance: Gametests: veins grow only from the minimum stage, onto any solid face including placed blocks, never onto `#remission:vein_resistant`; growth is deterministic and resumes after an unload; an eligible mob on, in or against an infectious block, or swimming in water in a festering chunk, becomes a host after `contactTicks` (and on first touch at 0); immune mobs, hosts, players and mobs whose profile sets `conversion.festering_land` to false never do; nothing happens with either source turned off; `convert_to` applies; `HostCorruptedEvent` carries the new cause. Per-tick cost of vein growth and contact checks measured on foundry next to P4.6's cave pass and P4.7's tracker and recorded in the PR. Harness scene `world/veins` (veins over terrain, a cave and a small build, next to sculk veins), reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, minecraft-world-generation, remission-data-formats, minecraft-testing, visual-checks, content-provenance
- Parallel: Owns the vein block and its growth pass, and the contact source in `infection/`; appends to `api/CorruptionCause`, the registry and the `[festering]` and `[infection]` config sections. Do not run alongside P5.2, which must also clear veins.

Exit criteria: gametests pass; spread and catch-up timings recorded; scenes reviewed; the API is documented. P4.5, P4.6, P4.7 and P4.8, follow-ups added after P4.4, are not part of these exit criteria.

## Phase 5: Viral load and early purification

Goals: the player can survive the Strain and push it back locally.

### P5.1: Viral load

- Status: `done`
- Owner: feat/p5.1-viral-load
- PR: https://github.com/CubieCode/counterstrain/pull/63
- Validation: PR #63; 144 gametests incl. ViralLoadTests (gain from land, drinking, swimming, miasma clouds; altitude falloff on and off; full gain underground; decay); `survival/hud` scene from the RTX 4070 reviewed, references committed.
- Depends on: P4.1, P4.4, P4.3, P8.1
- Scope: Player attachment `viral_load`; gain in festering chunks, from drinking water from (or swimming in) water in festering chunks, and from flying through miasma clouds; land gain falls off with height above the ground (config: on/off, falloff curve), so airborne players and vehicles avoid the worst of it, while underground (caves, tunnels, bases below a festering chunk) gain is at full strength, so digging in is no refuge; decay; escalating debuffs; HUD element; sync. Emits viral load changed and adds the viral load query. Adds any viral load advancement triggers and stats the pack needs, following P8.1's conventions.
- Acceptance: Gametests for gain (land, drinking, swimming, miasma clouds), altitude falloff (on and off), full gain underground and decay; harness scene `survival/hud`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

### P5.2: Handheld purifier, quarantine pylons and mendroot

- Status: `todo`
- Owner:
- PR:
- Depends on: P4.1, P4.2, P4.4, P4.8, P8.1
- Scope: Handheld purifier item, `remission:quarantine_pylon` block with radius (inside it, spread, festering, vein growth and contact infection stop and festered blocks and veins are cleansed; it is the ward a base or the crash site is built around), `remission:mendroot` cleansing plant (plantable, so automation can place it); purified chunks enter remission and grant remission zone bonuses (config). Contact viral load: a player touching a block in `#remission:infectious` (standing on it, inside it or against it, through P4.8's `Contact.of(entity)`) gains viral load at a configurable rate (`[viral_load]`: on/off and rate), on top of P5.1's land gain; water contact is not counted again, since P5.1's water gain already covers swimming in festering water. Emits block purified and chunk entered remission. Adds the region-cleansed advancement trigger (share of chunks in remission around a point) and a chunks-cleansed stat, following P8.1's conventions.
- Acceptance: Gametests for radius, cleansing and remission entry, and for contact viral load (gain on, in and against an infectious block; none with it off; no extra gain from water contact); harness scene `purification/pylon_radius` (before/after). Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

### P5.3: Revenants

- Status: `todo`
- Owner:
- PR:
- Depends on: P2.2, P3.1, P4.4, P5.1, P8.1
- Scope: Entity `remission:revenant`: when a player dies to the Strain (killer is a host or revenant, death by viral load, or death anywhere in a festering chunk at stage 1 or above, underground included), their items (main inventory, armour, offhand, and Curios slots through a guarded `integration/curios/`) and experience go into a revenant at the death position instead of dropping; other deaths and `keepInventory` behave as vanilla. The revenant is a hostile host rendered with the player's skin and the host overlay (P2.2), wearing their armour and holding their main-hand item, which it fights with; it never despawns, stays within a short radius of where the player fell, survives unloads, and takes damage only from players and their projectiles (fire, lava, falling, drowning and other mobs cannot destroy it; in the void it returns to its death position). Killing it drops its items and experience at its position; item pickup is restricted to the owner, widened by a public API event (packs add teammates), and freed for anyone after a timeout (config). A saved per-level index keeps each revenant's owner, dimension and last position, so P5.4 can track unloaded revenants. A player can have several revenants, one per death. Pack side: a KubeJS handler on that event (NeoForge event listening) lets FTB Teams members collect too; if FTB Teams' team lookup is not reachable from KubeJS, a guarded `integration/ftbteams/` does it instead. Emits revenant risen and revenant slain events; adds the revenant reclaimed trigger and a revenants reclaimed stat, following P8.1's conventions. Glossary entry: revenant (DESIGN).
- Acceptance: Gametests: each Strain death creates a revenant holding exactly the dropped items (Curios included when Curios is present) and the experience; non-Strain deaths and `keepInventory` drop as vanilla; the revenant ignores non-player damage and returns from the void; it persists across unload and reload and stays leashed; owner-only pickup, the API event widening it, and the timeout; the index is updated on rise, move and death. Harness scene `survival/revenant` (a revenant with a player skin, armour and weapon, at two host stages), reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, remission-data-formats, kubejs-scripting, minecraft-testing, visual-checks, content-provenance
- Parallel: Owns the revenant entity, its renderer and index, `integration/curios/` and its build-file entries (one build-file owner per wave).

### P5.4: Revenant tracker

- Status: `todo`
- Owner:
- PR:
- Depends on: P5.3
- Scope: Craftable item `remission:revenant_tracker` (default recipe in the mod, pack may replace it), active when worn in a Curios charm slot (guarded `integration/curios/`) or carried anywhere in the inventory. While active, the server sends the holder the dimension and position of their own revenants (and those of anyone the P5.3 pickup event allows them to reclaim) from the revenant index, so unloaded revenants show too, on an interval. With JourneyMap present, each revenant is a JourneyMap waypoint or marker on the full-screen map and minimap (guarded `integration/journeymap/`, `compileOnly`), removed when the revenant is slain or the tracker is no longer carried; without JourneyMap, a HUD pointer shows direction and distance to the nearest revenant. Client code under `client/`.
- Acceptance: Gametests for activation (Curios slot, inventory, neither) and for the positions sent (own, allowed, unloaded, removed on death); harness scenes `survival/revenant_tracker` (HUD pointer) and, with JourneyMap in the dev runtime, `survival/revenant_map`, reviewed. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance
- Parallel: Adds a network payload (one registry and network spine owner per wave) and the JourneyMap build-file entry; P8.2 also uses `integration/journeymap/`, so whichever runs second appends to that package rather than replacing it.

Exit criteria: a player can enter, survive and cleanse festering land in a dev world, leaving chunks in remission; scenes reviewed. P5.3 and P5.4, added later, are not part of these exit criteria.

## Phase 6: Research

Goals: knowledge is gathered from the world and recorded.

### P6.1: Scanner and data items

- Status: `done`
- Owner: `feat/p6.1-scanner`
- PR: https://github.com/CubieCode/counterstrain/pull/18
- Validation: PR #18; 6 scanner gametests; harness scene `research/scanner` reviewed and referenced; gpu-smoke run 38125186265 passes.
- Depends on: P2.1
- Scope: Scanner item scanning hosts and blocks into data items (data components).
- Acceptance: Gametests; harness scene `research/scanner`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, content-provenance

### P6.2: Bestiary

- Status: `done`
- Owner: `feat/p6.2-bestiary`
- PR: https://github.com/CubieCode/counterstrain/pull/24
- Validation: PR #24; bestiary key mapping (default M) listing 82 host types in the dev runtime incl. Mekanism robit, `remission:bestiary` player attachment; BestiaryTests; `research/bestiary` references reviewed, gpu-smoke green on RTX 4070; CI green.
- Depends on: P6.1
- Scope: Bestiary UI listing host types derived from installed entity types; scan progress per player.
- Acceptance: Host type count scales with dev-runtime mods; harness scene `research/bestiary`. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

### P6.3: Hidden bestiary types

- Status: `done`
- Owner: feat/p6.3-bestiary-hidden
- PR: https://github.com/CubieCode/counterstrain/pull/31
- Validation: PR #31; `#remission:bestiary_hidden` (giant, illusioner, armour stand by default) hides types from the bestiary list and total (79 host types in the dev runtime, robit still listed); BestiaryTests incl. live tag rebind, 87/87 gametests; `research/bestiary` list reference updated and reviewed, gpu-smoke green on RTX 4070; CI green.
- Depends on: P6.2
- Scope: Entity type tag `#remission:bestiary_hidden`: tagged types are left out of the bestiary list and its host type total, and scans of them are not recorded as bestiary progress. Remission ships it with types survival players cannot normally meet as hosts (`minecraft:giant`, `minecraft:illusioner`, `minecraft:armor_stand`); packs extend or replace it. No spawn-egg or other heuristic, so modded mobs without eggs (the Mekanism robit) stay listed. Contract in remission-data-formats (Bestiary section) and docs/ARCHITECTURE.md.
- Acceptance: Gametests show tagged types absent from the list and total, a pack tag hiding another type and `replace` re-showing a default, and the robit still listed in the dev runtime; `research/bestiary` reference updated for the new total and reviewed from a GPU run.
- Skills: neoforge-mod-dev, remission-data-formats, minecraft-testing, visual-checks

Exit criteria: scanning works for vanilla and modded hosts; bestiary screenshots reviewed. P6.3, a follow-up added after P6.2, is not part of these exit criteria.

## Phase 7: Tech chain

Goals: the Strain becomes a resource processed by machines into tools for the fight.

### P7.1: Miasma and samples

- Status: `done`
- Owner: feat/p7.1-miasma-samples
- PR: https://github.com/CubieCode/counterstrain/pull/36
- Validation: PR #36; `remission:miasma` fluid, bucket and liquid block, optional Mekanism chemical, `nidus_core` and `festered_soil` loot (`remission:is_festering` condition); 98/98 gametests with and without Mekanism; GPU scene `tech/miasma` (RTX 4070) reviewed; CI and gpu-smoke green. Open for balancing: festered soil 25% at stage 2+, nidus drops its core.
- Depends on: P3.3
- Scope: `remission:miasma` fluid (and Mekanism chemical `remission:miasma` via optional integration in `integration/mekanism/`), `remission:host_tissue`, `remission:nidus_core` and `remission:festered_soil` sample items and their loot.
- Acceptance: Fluid renders and flows; Mekanism integration loads only when Mekanism is present. Ledger updated; placeholders marked.
- Skills: neoforge-mod-dev, visual-checks, content-provenance

### P7.2: Analyzer, sequencer and synthesizer

- Status: `done`
- Owner: feat/p7.2-machines
- PR: https://github.com/CubieCode/counterstrain/pull/65
- Validation: PR #65; 154 gametests incl. MachineTests (each machine crafts); recipe schemas in remission-data-formats; `tech/machines` and `tech/recipe_viewer` scenes from the RTX 4070 reviewed (EMI with JEI, JEI alone), gpu-smoke green.
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

- Status: `done`
- Owner: feat/p8.1-triggers-stats
- PR: https://github.com/CubieCode/counterstrain/pull/38
- Validation: PR #38; triggers `nidus_destroyed`, `host_scanned`, `block_scanned` and matching stats; QuestTriggerTests plus 91/91 gametests; GPU dev run (RTX 4070, scene `quest/triggers`) completed all six FTB Quests advancement and stat tasks; CI green.
- Depends on: P4.1, P6.1
- Scope: Triggers and stats for region % cleansed, nidus destroyed (`remission:nidus_destroyed`, stat `remission:nidi_destroyed`), host scanned, surge survived, and others the pack needs.
- Acceptance: Schemas in remission-data-formats; FTB Quests tasks gate on them in a dev run.
- Skills: neoforge-mod-dev, remission-data-formats, ftb-quests-authoring
- Built: triggers `remission:nidus_destroyed`, `remission:host_scanned`, `remission:block_scanned` and stats `remission:nidi_destroyed`, `remission:hosts_scanned`, `remission:blocks_scanned`. Deferred to the systems they observe (coordinator decision): region % cleansed (`region_cleansed`, stat `chunks_cleansed`) to P5.2 and viral load to P5.1; surge survived went to P4.3, which built `remission:surge_survived` and the stat `remission:surges_survived`.

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
- Scope: Starting structure/map (the crash site holds a working quarantine pylon, so the player starts inside a warded radius), KubeJS progression gating across Mekanism/AE2/Create/Ars Nouveau and the phase 10 integration gates (kinetics before holding a frontier, pressure tech before automated reclamation, shielded cabling for the research network, sealed storage for expeditions, Mekanism for the late-game lab, boss-gated spells), datapack worldgen tweaks for nidi.
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
- Depends on: P1.5, P11.3
- Scope: Using the content editor (P1.5), replace every `placeholder` and `draft` row in CONTENT-PROVENANCE with human-made or licensed content, or accept a draft as final with a recorded human review (reviewer and date in the row's notes). Remove placeholder markers from replaced textures. Store page copy and gallery images for Modrinth and CurseForge.
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
