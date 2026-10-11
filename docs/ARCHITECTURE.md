# Architecture and decisions

## Current boundary

Phase 0 was scaffold only: deferred registers, configs, payload registrar, datagen providers and one smoke gametest. Phase 2 has started: P2.1 adds the `corruption` entity attachment, its server API and the host stage thresholds config; P2.2 adds the host render layer and its client intensity option; P2.3 adds the `/remission` debug commands (below). Phase 3 has started: P3.1 adds host goals and attack attributes (Behaviour, below). Phase 6 has started: P6.1 adds the scanner and scan data items (research, below). Everything below marked *planned* is a roadmap item.

## Repository and products

```text
                 ┌───────────────────────────────┐
                 │ Counterstrain (pack/)         │
                 │ KubeJS · FTB Quests · datapack│
                 │ story, recipes, balance       │
                 └──────────────┬────────────────┘
          data/scripts only     │  (tags, per-mob JSON, recipes, triggers,
          never Java imports    │   stats, commands, API events via KubeJS)
                 ┌──────────────▼────────────────┐
                 │ Remission (mod/)              │
                 │ generic defaults, no pack deps│
                 └──────────────┬────────────────┘
                                │ optional, guarded integrations
              Mekanism · AE2 · Create · Ars Nouveau · EMI/JEI · JourneyMap
              and the phase 10 mod-side tie-ins (DESIGN: Pack integrations)
```

- Remission compiles against NeoForge only. Integrations with other mods are optional (`compileOnly` API + runtime checks via `ModList`) and live in `integration/<modid>/`. Remission never hard-depends on an integrated mod.
- Integrations and addons build on the public API (`com.cubiecode.remission.api`, below). Pack-side integration (KubeJS, datapacks) is preferred; mod-side code is used only where the other mod's content can be registered or hooked only from Java (DESIGN, Pack integrations).
- The pack talks to the mod only through data and script surfaces (below). If the pack needs something the mod cannot express, extend the surface generically rather than special-casing the pack.

## Mod layout

`mod/src/main/java/com/cubiecode/remission/`:

| Package | Contents |
| --- | --- |
| `Remission` | `@Mod` common entrypoint, `MOD_ID`, logger, `id()` |
| `client/` | `@Mod(dist = CLIENT)` entrypoint, client config screen, `HostOverlayLayer` and `HostRenderTypes` (host rendering, below); planned: renderers, client payload handling |
| `server/` | `@Mod(dist = DEDICATED_SERVER)` entrypoint; dedicated-server-only setup |
| `config/` | `RemissionCommonConfig` (gameplay: `hosts.stageThresholds`), `RemissionClientConfig` (presentation: `hosts.overlayIntensity`) |
| `registry/` | One `DeferredRegister` holder per kind: blocks, items, data components, attachments (`corruption`, transient `host_goals`), loot conditions, loot modifier codecs, recipe types/serializers, advancement triggers; `RemissionRegistries.register(modBus)` wires them |
| `network/` | `RegisterPayloadHandlersEvent` registrar, `PROTOCOL_VERSION`; no payloads yet (corruption uses attachment sync) |
| `data/` | `GatherDataEvent` wiring and providers (blockstates, item models, tags, recipes, loot tables, global loot modifiers) |
| `corruption/` | `CorruptionState` record and codecs, `Corruption` server API (`get`, `set`, `isHost`) |
| `commands/` | `RemissionCommands`: the `/remission` command tree, registered on `RegisterCommandsEvent` |
| `behaviour/` | `MobBehaviour` (the `mob_profile` behaviour enum), `HostBehaviours` (category defaults, goal stripping and injection), `HostBehaviourEvents` (join, attribute patching, host-on-host target veto) |
| `gametest/` | `@GameTestHolder` classes; `remission:empty` is a 3x3x3 air template, `remission:host_arena` a 7x5x7 stone-floored glass box (5x3x5 inside) for fights |
| *planned* `api/` | Public events and queries for integrations, addons and KubeJS (P4.4) |
| `research/` | Scanner item and behaviour (`ScannerItem`, `Scanning`), scan data items (`ScanItem`) and the `scan_data` component (`ScanData`); P6.1 |
| *planned* `world/`, `purification/`, `tech/`, `cure/`, `quest/`, `integration/` | Feature systems by roadmap phase |

Resources: `src/main/resources` (hand-written: lang, textures, structures), `src/generated/resources` (datagen, committed), `src/main/templates/META-INF/neoforge.mods.toml` (expanded from `gradle.properties`).

## Naming in code

Internal Java system names and public API types may keep the generic word "corruption" (`CorruptionState`, the `corruption` attachment, `remission:is_corrupted`); content IDs, lang keys, triggers, stats and commands use the Strain glossary in [DESIGN](DESIGN.md#glossary). Do not rename either side to match the other.

## Planned systems and data flow

### Corruption state (phase 2, built in P2.1)

- `CorruptionState(level, stage)` in `corruption/`: `level` 0–100 (`MAX_LEVEL`), `stage` the number of `hosts.stageThresholds` entries the level met when it was set (default `[1, 34, 67]`, so stages 1–3; 0 means not a host). `variant` (the resistant mutation a host carries) is reserved for P9.1 and not stored yet. Field-level contract: remission-data-formats.
- Attachment `remission:corruption` (`RemissionAttachments.CORRUPTION`) on any entity; the API takes `LivingEntity`. Codec-serialized with the entity (`neoforge:attachments`), skipped at level 0, so it survives save/reload and dimension change (non-player entities are recreated with `restoreFrom`, which copies saved data; players keep their entity). Not copied on death or living conversion; P3.3 decides whether infection carries through conversion.
- Server API `Corruption.get(entity)` (never creates the attachment), `Corruption.set(entity, level)` (clamps, restages from config, removes the attachment at 0, no-op if unchanged, throws on the client), `Corruption.isHost(entity)`. P4.4 adds the host corrupted / cleansed events, fired from `set`.
- Stages are computed when a level is set, never on load or on the client, because common config is not synced and may differ per side. Changing the thresholds restages a host only when its level next changes.
- **Sync decision: NeoForge attachment sync** (`AttachmentType.Builder#sync(StreamCodec)`, present in 21.1.259), not a custom payload. NeoForge's `AttachmentSync` sends `setData`/`removeData` changes to `chunkMap.getPlayersWatching(entity)` plus the entity itself when it is a player, sends the full state in the start-tracking pairing data (`ServerEntity.sendPairingData`), and resends a player's own attachments on login, respawn and dimension change. That covers every case P2.1 requires with no Remission code, removal included. Synced attachment types are a synced registry, so client and server must both have Remission (already required: `side="BOTH"`). `RemissionNetwork.PROTOCOL_VERSION` is unchanged; the wire format is `CorruptionState.STREAM_CODEC` (two var-ints), so changing it is a protocol change for the synced registry. A custom payload would only be worth it for batching or per-player filtering beyond tracking, which nothing needs yet.
- Client: `HostOverlayLayer` draws hosts from the synced state (host rendering, below).

### Host rendering (phase 2, built in P2.2)

- `HostOverlayLayer` is added in `EntityRenderersEvent.AddLayers` to every renderer that is a `LivingEntityRenderer`: each `getEntityTypes()` renderer plus every player skin renderer. No per-entity-type code, so modded mobs get it too. Renderers that are not living-entity renderers (the ender dragon's, GeckoLib-style renderers) cannot take layers and are skipped; the count is logged at startup. At render time the layer also skips a null model or texture, invisible entities and non-hosts.
- The layer re-renders the parent model after the mob's own pass, so it inherits that frame's pose, baby scaling and model-part visibility. It calls `setupAnim` again with the frame's arguments before each pass, because some models keep per-draw state and clear it after drawing (Mekanism's robit model drops its entity and would draw nothing); vanilla `setupAnim` is idempotent. Armour and held items are separate layers drawn over it.
- Render type `remission_host_overlay` (`HostRenderTypes`, memoised per mob texture and pattern): shader `remission:host_overlay` (vanilla `rendertype_entity_translucent` vertex stage, own fragment stage), translucent blending, no culling, lightmap and hurt overlay, colour writes only. It binds the mob's texture to sampler 0 and the Strain pattern to sampler 3, and discards fragments where the mob texture is transparent, so empty outer-layer boxes (hats, jackets, the drowned's outer layer) stay invisible. The pattern repeats at one pattern texel per mob texel whatever the mob texture's size.
- Stage drives the look: `textures/entity/host/stage_1..3.png` (stages above 3, from extra thresholds, use stage 3), plus a full-bright `veins_glow.png` pass from stage 3. The client reads only the synced `stage`; it never restages. All four textures are placeholders (CONTENT-PROVENANCE).
- Client config `hosts.overlayIntensity` (0–1, default 1) is the overlay's vertex alpha; 0 turns it off. Presentation only.
- Limits: layers that draw their own model (armour, slime gel, the drowned's outer layer, sheep wool, saddles drawn by other layers) are not overlaid, so hosts under them look muted (roadmap item P2.4); renderers that are not `LivingEntityRenderer`s get nothing. Stage particles (DESIGN) are not built yet.
- Harness scenes `corruption/stages_vanilla` and `corruption/stages_modded` (visual-checks) show stages 0–3 on a vanilla lineup (baby, armoured, player in third person) and on the Mekanism robit with non-standard vanilla renderers (armour stand, slimes, guardian, wither; the ender dragon as a renderer without the layer).

### Research (phase 6, built in P6.1)

- `remission:scanner` makes data items: a host gives `remission:host_scan`, a block gives `remission:block_scan`, a living non-host gives nothing. The data item carries the `remission:scan_data` component (`ScanData`: host entity type id and stored host stage, or block id), registered in `RemissionDataComponents` as persistent and network-synchronized. Field contract: remission-data-formats ("Scanner and data items").
- The server decides and writes; the client only claims the interaction (arm swing). Entity scans run from `PlayerInteractEvent.EntityInteract` / `EntityInteractSpecific` so they come before the target's own interaction (villager trading, mounting); block scans run from `onItemUseFirst`, before the block's use. A 20-tick item cooldown follows every attempt and also stops a double scan when both entity events fire.
- Feedback is a vanilla action-bar message; no payloads. The scan reads host state through `Corruption.get` and never changes it.
- No timestamp or player is stored, so identical scans stack and recipe ingredients can match the full component value (NeoForge components ingredient).
- Later: `host_scanned` trigger and `hosts_scanned` stat (P8.1), per-player scan progress and the bestiary (P6.2), machines that consume scans (P7.2). The scanner sits in the vanilla Tools and Utilities tab until Remission has its own creative tab.
- Harness scene `research/scanner` (visual-checks): the three items in item frames, and an armour stand pointing the scanner at a stage 2 host cow.

### Behaviour (phase 3, goals and attributes built in P3.1)

- **When:** `HostBehaviours.update(mob)` brings a `Mob`'s goals in line with its host state. It runs on `EntityJoinLevelEvent` (server, low priority so other mods' goals are in first) and from `Corruption.set`, so `/remission infect`, `cleanse` and any later infection source act on loaded mobs at once. Nothing runs per tick. It is idempotent: a host that already has host goals, or a non-host without them, is left alone. P4.4 may move the `Corruption.set` call behind its host corrupted / cleansed events.
- **State:** what was removed and added (goal, priority) lives in the transient attachment `remission:host_goals` (`AppliedHostGoals`): never saved, synced or copied. Goals are not saved either, so a mob loaded from disk rebuilds its own goals and gets host goals again on join if it is still a host; a mob cleansed before saving loads as a normal mob. Cleansing a loaded mob restores its stripped goals at their old priorities and removes the added ones in-session, and clears its target.
- **Behaviour seam:** `HostBehaviours.behaviourFor(mob)` returns the mob's `MobBehaviour` and is the only place goal application asks. P3.1 returns the category default; P3.3 puts the `remission:mob_profile` lookup in front of it. Defaults, first match wins: `none` for brain-based mobs (any registered memory: villagers, piglins, axolotls, goats, frogs, allays...); `flying` for `FlyingMob`, `FlyingAnimal`, `AmbientCreature`, category `ambient` or flying navigation (bats, parrots, bees, ghasts, phantoms); `swimming` for `WaterAnimal`, the water categories or water-bound navigation (fish, squid, dolphins, guardians); `ranged` for `RangedAttackMob` (skeletons, snow golems, llamas, witches); `melee` for everything else (cows, sheep, pigs, zombies, spiders, slimes, modded goal-based mobs).
- **What each value does** to a goal-based host:
  - `melee`: strips passive goals (breed, tempt, avoid/flee, panic, follow parent/owner/mob/boat/caravan, sit, beg, shoulder, run around, trading) and the whole target selector, then adds `HurtByTargetGoal` (priority 1, pathfinders only, no alerting), `NearestAttackableTargetGoal<Player>` (2) and `NearestAttackableTargetGoal<LivingEntity>` (3), both line-of-sight and limited to attackable non-hosts. If the mob has no attack goal (`MeleeAttackGoal`, `RangedAttackGoal`, bow or crossbow goal) it gets `MeleeAttackGoal` (priority 1, speed 1.2) when it is a `PathfinderMob`; other mobs (slimes) keep attacking through their own goals.
  - `ranged`: as `melee`, but a mob without an attack goal gets `RangedAttackGoal` (speed 1.0, 40 ticks, radius 12) when it is a `RangedAttackMob`.
  - `passive_hostile`: strips the same goals, adds only `HurtByTargetGoal` and an attack goal: it never picks fights but fights back against anything except hosts.
  - `flying`, `swimming`: no goal changes until P3.2 adds movement-appropriate fallbacks; the mob keeps its own AI.
  - `none`: no goal changes (brain-based mobs until P3.2, and an explicit opt-out for profiles).
- **Hosts never target hosts:** a `LivingChangeTargetEvent` listener clears any target a host would set on another host, for every host whatever its behaviour (brain `StartAttacking` included). Target goals re-set their target each tick they continue, so a target that becomes a host mid-fight is dropped on the next tick.
- **Attributes:** `EntityAttributeModificationEvent` adds `attack_damage` (default 2) and `follow_range` (default 32) to every entity type with attributes that lacks them, so attack and target goals work on any mob. Profile attribute modifiers are P3.3.
- **Tests:** `HostBehaviourTests` (own batch, `remission:host_arena`): host cow, zombie and Mekanism robit (dev runtime only; skipped without Mekanism) hurt an uninfected pig; a host sheep hurts a survival player; hosts ignore hosts (including retaliation and a forced `setTarget`); a target that becomes a host is dropped; cleanse restores goals in-session; reload re-applies host goals on join and a cleansed save loads normal; category defaults; every living type has the attributes.
- Planned: brain-based, flying and swimming fallbacks (P3.2). Infection: `LivingDeathEvent` (killer is a host) and time in festering chunks flip state or replace via `convert_to` (P3.3).

### Festering land (phase 4)

Chunk attachment `{level, last_update_game_time}`; nidi (`remission:nidus`) are block entities registered in a level-wide saved index. A server tick budgeted spread engine moves corruption between chunks; `ChunkEvent.Load` fast-forwards from the stored time with a bounded amount of work. Each chunk-to-chunk step posts the cancellable spread attempt event (rate multiplier) before applying. Festering stages are applied as block transformations in bounded batches. Biome flip to `remission:fester` (or a per-source-biome fester variant from data) uses a mutable biome container write and chunk resend. Surges are scheduled per level. The same engine runs in reverse for the Counterstrain (phase 9).

### Public API (P4.4, extended by each system item)

`com.cubiecode.remission.api` is the only package other mods and KubeJS bindings may rely on; everything else is internal.

- Events, posted server side on `NeoForge.EVENT_BUS`, fired by the system that owns the state change: host corrupted / cleansed (P2.1, P4.4), chunk stage changed and nidus destroyed (P4.1, P4.4), Strain spread attempt (cancellable, rate multiplier; P4.4), block festered (P4.2), surge started / ended (P4.3), viral load changed (P5.1), block purified and chunk entered remission (P5.2).
- Queries: corruption stage at a position or chunk, `isHost(entity)`, a player's viral load (P5.1).
- Events are posted after the state change is applied, except the cancellable spread attempt. Batched changes (catch-up, bounded stage batches) post one event per chunk, not per block, unless the event is block-scoped.
- Schemas, names and firing rules live in remission-data-formats ("Public API events") and change only with the code. KubeJS bindings for them are P8.3.

### Viral load (phase 5)

Player attachment `viral_load`, synced to its own player. Gain per tick in festering chunks (scaled down by height above the ground when the altitude falloff config is on), plus drinking festering water, swimming in water in festering chunks and flying through miasma clouds (spawned by surges, P4.3); decay elsewhere; serums and gear reduce gain.

### Dispersal payload (phase 9)

The Counterstrain is deployed from a dispersal source: a position, radius and rate, ticked by whatever holds it. `remission:dispersal_payload` (a placed block) is the only holder Remission needs to finish the game (P9.2). The source API must not assume a block, so `integration/create/` (P10.6) can tick it from a moving Create contraption, Aeronautics airships included.

### Contracts for the pack (phases 2–8)

| Surface | Location | Owner skill |
| --- | --- | --- |
| Immune mobs | `data/<ns>/tags/entity_type/immune.json` (`#remission:immune`) | remission-data-formats |
| Per-mob overrides | `data/<ns>/remission/mob_profile/<name>.json` (datapack registry `remission:mob_profile`) | remission-data-formats |
| Host loot | `data/<ns>/loot_modifiers/*.json` + `data/neoforge/loot_modifiers/global_loot_modifiers.json`, condition `remission:is_corrupted` | remission-data-formats |
| Fester variants | per-source-biome fester biome (data map, shape fixed in P4.2) | remission-data-formats, minecraft-world-generation |
| Machine recipes | recipe types `remission:analyzing`, `remission:sequencing`, `remission:synthesizing` | remission-data-formats, kubejs-scripting |
| Scan data items | items `remission:host_scan`, `remission:block_scan`, data component `remission:scan_data` (P6.1, built) | remission-data-formats, kubejs-scripting |
| Quest gates | advancement triggers `remission:*` (`nidus_destroyed`, `host_scanned`, ...), custom stats (`remission:nidi_destroyed`, ...) | remission-data-formats, ftb-quests-authoring |
| Debug commands | `/remission infect <targets> <level>`, `/remission cleanse <targets>`, `/remission inspect <target>` (permission 2; P2.3, built) | remission-data-formats |
| Quest-fired events | commands (`/remission surge start`, `/remission nidus seed`, `/remission region advance`; P8.2) | ftb-quests-authoring |
| API events and queries | `com.cubiecode.remission.api` (P4.4) | remission-data-formats |
| Dispersal payload | `remission:dispersal_payload` and its dispersal source API (P9.2; contraption holder P10.6) | remission-data-formats |
| KubeJS event bindings | Remission events exposed to KubeJS scripts (P8.3) | kubejs-scripting, remission-data-formats |

All schemas start as stubs in the remission-data-formats skill and change only with the code that reads them.

## Runtime and test infrastructure

- Run configs: `client` (`run/client`; also loads the dev-only scene harness), `server` (`run/server`), `gameTestServer` (`run/gametest`, exits on completion), `data` (`run/data`, writes `src/generated/resources`).
- Dev runtime mods (`localRuntime`, never published): KubeJS + Rhino + Better Advanced Tooltips, ProbeJS, Architectury, FTB Library/Teams/Quests (FTB Maven, not on Modrinth), JEI + EMI, Mekanism, AE2 + GuideME, JourneyMap. Toggle `dev_runtime_mods`. CI disables them.
- Headless client: `tools/run-client-headless.sh` (Xvfb + VirtualGL EGL on the RTX 4070).
- GPU workflow: `gpu-smoke.yml` (manual dispatch or the `gpu-smoke` PR label, self-hosted foundry runner) runs `tools/run-scenes.sh`, which runs every harness scene through the headless client and compares their screenshots with `tools/compare-screenshots.sh`, then uploads logs, screenshots and diffs.
- Scene harness (dev only): the `harness` source set (`mod/src/harness/`) is a separate client-only mod, `remission_harness`, that only the `client` run loads (`loadedMods`); the jar and the other runs never contain it, and `build` compiles it so it cannot rot. It does nothing unless `-Dremission.harness.scene=<id>` is set (`tools/run-client-headless.sh --scene <id>` sets it). Then it recreates a fixed superflat world, runs the scene JSON from `mod/src/harness/resources/harness/scenes/`, saves the named screenshots and exits. See the visual-checks skill for the scene format.

## Decisions

- **Gradle 9.8.1** wrapper instead of the MDK's 9.2.1: current at scaffold time, builds with no deprecation warnings.
- **Modrinth Maven pins by version ID** where a project reuses a version number across loaders (JEI resolved to its Fabric jar by number).
- **FTB mods from FTB Maven / CurseForge:** FTB Quests, Library and Teams are not on Modrinth. The pack adds them with `packwiz cf add`.
- **Distribution:** Remission publishes to Modrinth and CurseForge; Counterstrain is CurseForge-first, and its CurseForge zip is the release artifact (DESIGN, Distribution). A Modrinth `.mrpack` cannot download CurseForge files, and the pack already needs CurseForge-only mods. `packwiz curseforge export` lists only CurseForge-sourced mods in `manifest.json` and embeds every other jar under `overrides/mods/`. That is why pack mods are sourced from CurseForge; P10.1 re-sources the current Modrinth-sourced ones. The `.mrpack` export is for local testing only.
- **EMI + JEI together:** EMI is the recipe viewer players see; it takes over JEI's runtime UI (its built-in JEI compatibility) and shows every mod's JEI plugin recipes. JEI stays installed so mods with only JEI plugins keep working. Remission's recipe viewer integration (P7.2) is a JEI plugin first, which reaches both; a native EMI plugin is added only for EMI-specific features.
- **JourneyMap for maps:** chosen over Xaero's (closed source, no overlay API) and FTB Chunks (icon-only API: chunk colouring would work on the full-screen map but not on the zoomed or rotated minimap). JourneyMap's API (`info.journeymap:journeymap-api-neoforge:2.0.0-1.21.1` from maven.blamejared.com, `compileOnly`) has `PolygonOverlay` on the full-screen map, minimap and web map, `PolygonHelper`/`MapPolygonWithHoles` to merge chunks into outlines with remission-zone holes, and a server-side overlay API. The P8.2 festering overlay uses it as an optional integration in `integration/journeymap/`.
- **No climate mods:** Tough As Nails and Cold Sweat are out; viral load is the only exposure system.
- **Integrations pack-side first:** each tie-in in DESIGN's Pack integrations table is pack-side (KubeJS, datapacks on Remission's surfaces) unless the other mod's content can be registered or hooked only from Java; then it is a guarded `integration/<modid>/` package. P10.1 owns membership (`pack/mods/`) and the `compileOnly`/dev-runtime entries for every mod-side integration, so later phase 10 lanes do not touch build files.
- **Content provenance:** player-facing content is tracked in [CONTENT-PROVENANCE](CONTENT-PROVENANCE.md); `tools/check-provenance.sh` (CI: `docs.yml`) fails when an image, sound or model has no row, and when a texture's magenta placeholder marker disagrees with its row's status.
- **Pack datapack in `pack/kubejs/data/`:** KubeJS loads it as a datapack in every world without another mod.
- **Scene harness determinism and exit:** scenes freeze the tick (`/tick freeze`) after time and weather (players keep ticking, so the harness pins the local player's `tickCount` while shooting), spawn mobs without `finalizeSpawn` (no random gear or variants), and pin the image-affecting options in memory (GUI hidden, clouds off, render distance 8, FOV 70, fancy graphics). Repeated runs give byte-identical PNGs on foundry. The harness ends the process with libc `_exit` after flushing logs, because under VirtualGL's EGL back end the NVIDIA driver segfaults in `libEGL_nvidia` during libc `exit()`: a normal quit, `System.exit` and `Runtime.halt` all end with exit code 134.
- **Performance:** spread, catch-up and visual stage changes must be budgeted per tick and measured on foundry (phase 12 includes client FPS under heavy spread).
