# Architecture and decisions

## Current boundary

Phase 0 was scaffold only: deferred registers, configs, payload registrar, datagen providers and one smoke gametest. Phase 2 is complete: P2.1 adds the `corruption` entity attachment, its server API and the host stage thresholds config; P2.2 adds the host render layer and its client intensity option; P2.3 adds the `/remission` debug commands (below). Phase 3 has started: P3.1 adds host goals and attack attributes (Behaviour, below); P3.3 adds infection on kill, `#remission:immune`, the `remission:mob_profile` datapack registry, the `remission:is_corrupted` loot condition and the host tissue loot modifier. Phase 4 has started: P4.1 adds the `chunk_corruption` chunk attachment, the `remission:nidus` block and its saved index, and the budgeted spread engine with its `spread` config (festering land, below). Phase 6 has started: P6.1 adds the scanner and scan data items; P6.2 adds the `bestiary` player attachment and the bestiary screen (research, below). Everything below marked *planned* is a roadmap item.

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
| `client/` | `@Mod(dist = CLIENT)` entrypoint, client config screen, `HostOverlayLayer`, `HostLayerOverlay` and `HostRenderTypes` (host rendering, below); planned: renderers, client payload handling |
| `server/` | `@Mod(dist = DEDICATED_SERVER)` entrypoint; dedicated-server-only setup |
| `config/` | `RemissionCommonConfig` (gameplay: `hosts.stageThresholds`, the `spread` rates and budgets), `RemissionClientConfig` (presentation: `hosts.overlayIntensity`) |
| `registry/` | One `DeferredRegister` holder per kind: blocks, items (scanner, scans, `host_tissue`), block entities, data components, attachments (`corruption`, transient `host_goals`, `chunk_corruption`, `bestiary`), loot conditions, loot modifier codecs, recipe types/serializers, advancement triggers; `RemissionRegistries.register(modBus)` wires them |
| `network/` | `RegisterPayloadHandlersEvent` registrar, `PROTOCOL_VERSION`; no payloads yet (corruption uses attachment sync) |
| `data/` | `GatherDataEvent` wiring and providers (blockstates, item models, tags, recipes, loot tables, global loot modifiers) |
| `corruption/` | `CorruptionState` record and codecs, `Corruption` server API (`get`, `set`, `isHost`, `isImmune`, tag key `IMMUNE`) |
| `infection/` | `Infection` (infection on kill, `convert_to`, festering land eligibility stub), `HostAttributes` (mob profile attribute modifiers) |
| `loot/` | `IsCorruptedCondition` (`remission:is_corrupted`), `AddItemModifier` (`remission:add_item` loot modifier codec) |
| `world/` | Festering land: `ChunkCorruption` (chunk attachment record), `NidusBlock`/`NidusBlockEntity`, `NidusIndex` (saved index), `SpreadEngine`/`SpreadSettings` (spread and catch-up), `FesteringLand` (per-level engines and event wiring), `SpreadHooks` (where P4.4 posts its events) |
| `commands/` | `RemissionCommands`: the `/remission` command tree, registered on `RegisterCommandsEvent` |
| `behaviour/` | `MobBehaviour` (the `mob_profile` behaviour enum), `MobProfile` record and codec, `MobProfiles` (datapack registry key and lookup), `HostBehaviours` (profile-first behaviour, category defaults, goal stripping and injection), `HostBehaviourEvents` (join, attribute patching, host-on-host target veto) |
| `gametest/` | `@GameTestHolder` classes; `remission:empty` is a 3x3x3 air template, `remission:host_arena` a 7x5x7 stone-floored glass box (5x3x5 inside) for fights |
| *planned* `api/` | Public events and queries for integrations, addons and KubeJS (P4.4) |
| `research/` | Scanner item and behaviour (`ScannerItem`, `Scanning`), scan data items (`ScanItem`) and the `scan_data` component (`ScanData`); P6.1. Host type rule and per-player progress (`Bestiary`, `BestiaryProgress`); P6.2 |
| *planned* `purification/`, `tech/`, `cure/`, `quest/`, `integration/` | Feature systems by roadmap phase |

Resources: `src/main/resources` (hand-written: lang, textures, structures), `src/generated/resources` (datagen, committed), `src/main/templates/META-INF/neoforge.mods.toml` (expanded from `gradle.properties`).

## Naming in code

Internal Java system names and public API types may keep the generic word "corruption" (`CorruptionState`, the `corruption` attachment, `remission:is_corrupted`); content IDs, lang keys, triggers, stats and commands use the Strain glossary in [DESIGN](DESIGN.md#glossary). Do not rename either side to match the other.

## Planned systems and data flow

### Corruption state (phase 2, built in P2.1)

- `CorruptionState(level, stage)` in `corruption/`: `level` 0–100 (`MAX_LEVEL`), `stage` the number of `hosts.stageThresholds` entries the level met when it was set (default `[1, 34, 67]`, so stages 1–3; 0 means not a host). `variant` (the resistant mutation a host carries) is reserved for P9.1 and not stored yet. Field-level contract: remission-data-formats.
- Attachment `remission:corruption` (`RemissionAttachments.CORRUPTION`) on any entity; the API takes `LivingEntity`. Codec-serialized with the entity (`neoforge:attachments`), skipped at level 0, so it survives save/reload and dimension change (non-player entities are recreated with `restoreFrom`, which copies saved data; players keep their entity). Not copied on death or by vanilla living conversions; infection's own `convert_to` sets it on the replacement (below).
- Server API `Corruption.get(entity)` (never creates the attachment), `Corruption.set(entity, level)` (clamps, restages from config, removes the attachment at 0, no-op if unchanged, throws on the client), `Corruption.isHost(entity)`, `Corruption.isImmune(entity)` (`#remission:immune`; `set` ignores levels above 0 for immune entities). `set` applies or removes mob profile attribute modifiers when host status changes. P4.4 adds the host corrupted / cleansed events, fired from `set`.
- Stages are computed when a level is set, never on load or on the client, because common config is not synced and may differ per side. Changing the thresholds restages a host only when its level next changes.
- **Sync decision: NeoForge attachment sync** (`AttachmentType.Builder#sync(StreamCodec)`, present in 21.1.259), not a custom payload. NeoForge's `AttachmentSync` sends `setData`/`removeData` changes to `chunkMap.getPlayersWatching(entity)` plus the entity itself when it is a player, sends the full state in the start-tracking pairing data (`ServerEntity.sendPairingData`), and resends a player's own attachments on login, respawn and dimension change. That covers every case P2.1 requires with no Remission code, removal included. Synced attachment types are a synced registry, so client and server must both have Remission (already required: `side="BOTH"`). `RemissionNetwork.PROTOCOL_VERSION` is unchanged; the wire format is `CorruptionState.STREAM_CODEC` (two var-ints), so changing it is a protocol change for the synced registry. A custom payload would only be worth it for batching or per-player filtering beyond tracking, which nothing needs yet.
- Client: `HostOverlayLayer` draws hosts from the synced state (host rendering, below).

### Host rendering (phase 2, built in P2.2, layers in P2.4)

- `HostOverlayLayer` is added in `EntityRenderersEvent.AddLayers` to every renderer that is a `LivingEntityRenderer`: each `getEntityTypes()` renderer plus every player skin renderer. No per-entity-type code, so modded mobs get it too. Renderers that are not living-entity renderers (the ender dragon's, GeckoLib-style renderers) cannot take layers and are skipped; the count is logged at startup. At render time the layer also skips a null model or texture, invisible entities and non-hosts.
- The layer re-renders the parent model after the mob's own pass, so it inherits that frame's pose, baby scaling and model-part visibility. It calls `setupAnim` again with the frame's arguments before each pass, because some models keep per-draw state and clear it after drawing (Mekanism's robit model drops its entity and would draw nothing); vanilla `setupAnim` is idempotent. Armour, wool and other own-model layers get their own companion (below); held items are not overlaid.
- Render type `remission_host_overlay` (`HostRenderTypes`, memoised per mob texture, pattern and layering state): shader `remission:host_overlay` (vanilla `rendertype_entity_translucent` vertex stage, own fragment stage), translucent blending, no culling, lightmap and hurt overlay, colour writes only. It binds the mob's texture to sampler 0 and the Strain pattern to sampler 3, and discards fragments where the mob texture is transparent, so empty outer-layer boxes (hats, jackets, the drowned's outer layer) stay invisible. The pattern repeats at one pattern texel per mob texel whatever the mob texture's size.
- Stage drives the look: `textures/entity/host/stage_1..3.png` (stages above 3, from extra thresholds, use stage 3), plus a full-bright `veins_glow.png` pass from stage 3. The client reads only the synced `stage`; it never restages. All four textures are placeholders (CONTENT-PROVENANCE).
- Client config `hosts.overlayIntensity` (0–1, default 1) is the overlay's vertex alpha; 0 turns it off. Presentation only.
- Layers with their own model or texture (P2.4): `HostLayerOverlay` adds, in `AddLayers` at `EventPriority.LOWEST` (so layers other mods add in the event are seen), one companion per `HumanoidArmorLayer`, `SheepFurLayer`, `SlimeOuterLayer`, `DrownedOuterLayer`, `HorseArmorLayer` and `WolfArmorLayer` (or subclass) in every living-entity and player skin renderer, vanilla or modded, appended with `addLayer` to the end of that renderer's layer list (after eyes, held items and the other layers), not next to the layer it wraps. For a host it runs the wrapped layer again (once per pattern, plus the glow pass from stage 3) with a buffer source that sends each `NEW_ENTITY`-format draw into `remission_host_overlay` for that draw's own texture and layering state (armour is drawn with `VIEW_OFFSET_Z_LAYERING`, nudged towards the camera, and an un-offset overlay would lose the depth test), with the overlay colour instead of the layer's dye; glint (other vertex format), the armour-trim atlas and outlines are dropped. The layer's own logic therefore picks the pieces, models and textures (NeoForge custom armour models and textures included), and the overlay follows each layer texture's cutout. Eye and emissive layers (`EyesLayer`, energy swirls) are never wrapped. Reading the renderer's `layers` list and a render type's texture uses reflection (`ObfuscationReflectionHelper`, Mojang names at runtime) rather than a mixin or access transformer. Failures degrade without crashing: if the reflective handles cannot be resolved, one error is logged and no companions are added; if a renderer's `layers` list cannot be read, one warning per such renderer and it gets none; if a render type's texture cannot be read, one warning per failing render type (the empty result is cached) and draws of that type get no overlay. Nothing else changes. Where two draws of one layer cover the same texels (leather's undyed overlay, dyed or cracked wolf armour) the pattern is applied twice there and looks slightly stronger. A wrapped layer must not hold two entity-format buffers open at once (vanilla's do not); the overlay buffers would end each other's batch.
- Limits: other layers (saddles on pigs and striders, llama decor, villager and horse markings, held items, heads, elytra, capes) are not overlaid; renderers that are not `LivingEntityRenderer`s (GeckoLib-style) get nothing. Stage particles (DESIGN) are not built yet.
- Harness scene `corruption/stages_layers` (P2.4) shows stages 0–3 on armoured zombies (iron, dyed leather), a skeleton, a fully armoured armour stand, a drowned, white and red sheep, a slime, a wolf in wolf armour and a horse in iron horse armour, plus the armoured player at stage 3 in third person.
- Harness scenes `corruption/stages_vanilla` and `corruption/stages_modded` (visual-checks) show stages 0–3 on a vanilla lineup (baby, armoured, player in third person) and on the Mekanism robit with non-standard vanilla renderers (armour stand, slimes, guardian). The wither and the ender dragon are immune bosses (`#c:bosses` in `#remission:immune`, P3.3): the scene still corrupts them, but they stay clean beside the hosts, so the `ender_dragon` shot (a renderer without the layer) and the wither now show an immune boss rendering normally.

### Research (phase 6, built in P6.1)

- `remission:scanner` makes data items: a host gives `remission:host_scan`, a block gives `remission:block_scan`, a living non-host gives nothing. The data item carries the `remission:scan_data` component (`ScanData`: host entity type id and stored host stage, or block id), registered in `RemissionDataComponents` as persistent and network-synchronized. Field contract: remission-data-formats ("Scanner and data items").
- The server decides and writes; the client only claims the interaction (arm swing). Entity scans run from `PlayerInteractEvent.EntityInteract` / `EntityInteractSpecific` so they come before the target's own interaction (villager trading, mounting); block scans run from `onItemUseFirst`, before the block's use. A 20-tick item cooldown follows every attempt and also stops a double scan when both entity events fire.
- Feedback is a vanilla action-bar message; no payloads. The scan reads host state through `Corruption.get` and never changes it.
- No timestamp or player is stored, so identical scans stack and recipe ingredients can match the full component value (NeoForge components ingredient).
- Later: `host_scanned` trigger and `hosts_scanned` stat (P8.1), machines that consume scans (P7.2). The scanner sits in the vanilla Tools and Utilities tab until Remission has its own creative tab.
- Harness scene `research/scanner` (visual-checks): the three items in item frames, and an armour stand pointing the scanner at a stage 2 host cow.

### Research: bestiary (built in P6.2)

- Host types are not a list: `Bestiary.isHostType` takes every registered entity type that has default attributes (every living type must register them, so this needs no entity instance), is not `minecraft:player` and is not in `#remission:immune`. Installed mods widen it at once (82 types in dev runs with Mekanism: vanilla plus the robit). Players can still carry the Strain and be scanned as hosts; they are just not a bestiary entry. Armour stands are living and listed; a pack that wants them out tags them immune.
- Progress is the player attachment `remission:bestiary` (`BestiaryProgress`: entity type id → highest host stage scanned). `Scanning.scanEntity` calls `Bestiary.recordScan` on the server whenever it gives a host scan; only bestiary host types are recorded (a scanned host player, for one, is not), and a lower stage never lowers the record. Saved with the player (only when non-empty) and copied on death. Synced through NeoForge attachment sync, so no custom payload and no `PROTOCOL_VERSION` change: updates go to the owning player only (`Bestiary.syncsTo`), but NeoForge's initial sync when a client starts tracking an entity ignores that predicate, so clients tracking the player also receive the current map. That is harmless: the screen reads only the local player's progress. Field contract: remission-data-formats ("Bestiary").
- Client: `client/research/BestiaryKeys` (a `Dist.CLIENT` subscriber, not `RemissionClient`) registers the key mapping `key.remission.bestiary` (category `key.categories.remission`, default M, in game only) and opens `BestiaryScreen`, a read-only vanilla list: header "X of N host types scanned" (lang `gui.remission.bestiary.progress`), one row per host type (spawn egg icon if the type has one, name, mod name, highest stage scanned or "Not scanned"), scanned types first, then by mod (vanilla first) and name. The client computes the host types itself from its registry and the synced entity type tags. No custom GUI art.
- Harness scene `research/bestiary` (visual-checks): shot `hosts` shows the scanned hosts in the world, shot `list` opens the bestiary through the key mapping.

### Behaviour (phase 3, goals and attributes built in P3.1)

- **When:** `HostBehaviours.update(mob)` brings a `Mob`'s goals in line with its host state. It runs on `EntityJoinLevelEvent` (server, low priority so other mods' goals are in first) and from `Corruption.set`, so `/remission infect`, `cleanse` and any later infection source act on loaded mobs at once. Nothing runs per tick. It is idempotent: a host that already has host goals, or a non-host without them, is left alone. P4.4 may move the `Corruption.set` call behind its host corrupted / cleansed events.
- **State:** what was removed and added (goal, priority) lives in the transient attachment `remission:host_goals` (`AppliedHostGoals`): never saved, synced or copied. Goals are not saved either, so a mob loaded from disk rebuilds its own goals and gets host goals again on join if it is still a host; a mob cleansed before saving loads as a normal mob. Cleansing a loaded mob restores its stripped goals at their old priorities and removes the added ones in-session, and clears its target.
- **Behaviour seam:** `HostBehaviours.behaviourFor(mob)` returns the mob's `MobBehaviour` and is the only place goal application asks. Profile first: the `behaviour` of the mob's `remission:mob_profile` (`MobProfiles.find`) when it sets one, else `HostBehaviours.categoryDefault(mob)`. Overloads `behaviourFor(mob, profile)` and `update(mob, profile)` take an explicit profile (tests). Because goals are applied once per host transition and on join, a changed profile reaches loaded hosts on the world reload that loads it. Category defaults, first match wins: `none` for brain-based mobs (any registered memory: villagers, piglins, axolotls, goats, frogs, allays...); `flying` for `FlyingMob`, `FlyingAnimal`, `AmbientCreature`, category `ambient` or flying navigation (bats, parrots, bees, ghasts, phantoms); `swimming` for `WaterAnimal`, the water categories or water-bound navigation (fish, squid, dolphins, guardians); `ranged` for `RangedAttackMob` (skeletons, snow golems, llamas, witches); `melee` for everything else (cows, sheep, pigs, zombies, spiders, slimes, modded goal-based mobs).
- **What each value does** to a goal-based host:
  - `melee`: strips passive goals (breed, tempt, avoid/flee, panic, follow parent/owner/mob/boat/caravan, sit, beg, shoulder, run around, trading) and the whole target selector, then adds `HurtByTargetGoal` (priority 1, pathfinders only, no alerting), `NearestAttackableTargetGoal<Player>` (2) and `NearestAttackableTargetGoal<LivingEntity>` (3), both line-of-sight and limited to attackable non-hosts. If the mob has no attack goal (`MeleeAttackGoal`, `RangedAttackGoal`, bow or crossbow goal) it gets `MeleeAttackGoal` (priority 1, speed 1.2) when it is a `PathfinderMob`; other mobs (slimes) keep attacking through their own goals.
  - `ranged`: as `melee`, but a mob without an attack goal gets `RangedAttackGoal` (speed 1.0, 40 ticks, radius 12) when it is a `RangedAttackMob`.
  - `passive_hostile`: strips the same goals, adds only `HurtByTargetGoal` and an attack goal: it never picks fights but fights back against anything except hosts.
  - `flying`, `swimming`: no goal changes until P3.2 adds movement-appropriate fallbacks; the mob keeps its own AI.
  - `none`: no goal changes (brain-based mobs until P3.2, and an explicit opt-out for profiles).
- **Hosts never target hosts:** a `LivingChangeTargetEvent` listener clears any target a host would set on another host, for every host whatever its behaviour (brain `StartAttacking` included). Target goals re-set their target each tick they continue, so a target that becomes a host mid-fight is dropped on the next tick.
- **Attributes:** `EntityAttributeModificationEvent` adds `attack_damage` (default 2) and `follow_range` (default 32) to every entity type with attributes that lacks them, so attack and target goals work on any mob. Profile attribute modifiers (`HostAttributes`, P3.3) are applied in `Corruption.set` before host goals.
- **Tests:** `HostBehaviourTests` (own batch, `remission:host_arena`): host cow, zombie and Mekanism robit (dev runtime only; skipped without Mekanism) hurt an uninfected pig; a host sheep hurts a survival player; hosts ignore hosts (including retaliation and a forced `setTarget`); a target that becomes a host is dropped; cleanse restores goals in-session; reload re-applies host goals on join and a cleansed save loads normal; category defaults; every living type has the attributes.
- Planned: brain-based, flying and swimming fallbacks (P3.2). Infection: below (P3.3).

### Infection, mob profiles and host loot (phase 3, built in P3.3)

- `Infection` listens to `LivingDeathEvent` at `LOW` priority. A dying `Mob` whose damage source's responsible entity is a host is not killed: the event is cancelled and it becomes a host at the lowest stage threshold, in place (healed, same entity, no death loot) or, with a profile `convert_to`, replaced through `Mob.convertTo` with NeoForge's `LivingConversionEvent` pre/post. In-place keeps the entity's data (trades, owner, inventory); the replacement joins the level before it is infected, and `Corruption.set` then applies its attributes and host goals.
- `#remission:immune` (default `#c:bosses` only; players stay infectable for the overlay, scanning and commands, and infection on kill never takes them because it only converts `Mob`s) is enforced in `Corruption.set`, so every route (infection, conversion, commands, future sources) respects it.
- Festering land infection is a phase 4 source: `Infection.canInfectFromFesteringLand(Mob)` checks eligibility (immune, host, profile flag); exposure tracking and the call site arrive with festering chunks.
- `remission:mob_profile` is a server-only datapack registry (`DataPackRegistryEvent.NewRegistry`, no network codec). **Reload decision:** datapack registries are read when a world loads and are frozen afterwards, so profile changes need a world reload; `/reload` only rebinds the tags profiles name. Making profiles `/reload`-able would mean a `SimpleJsonResourceReloadListener` with its own tag resolution; the registry was kept because HolderSet entity matching, error reporting and KubeJS/datapack tooling come for free, and profiles are tuning data rather than something changed mid-session. `MobProfiles.find` sorts by id and picks the highest priority on every call (no cache; it runs on infection and host transitions, not per tick).
- `HostAttributes` applies a profile's `attributes` as permanent `add_value` modifiers (`remission:mob_profile`), so they save with the entity and a host's health above its base maximum survives reload; it removes them on cleanse and reconciles them when a host joins a level.
- Loot: `remission:is_corrupted` reads the stored stage of a loot context entity. The default global loot modifier `remission:host_samples` (codec `remission:add_item`) adds one `remission:host_tissue` to a host's death loot; `minecraft:damage_source_properties` with no predicate keeps it to death drops.
- Gametests: `InfectionTests`, `MobProfileTests` (including a profile `behaviour` overriding the category default in applied goals), `HostLootTests`. The phase exit scene `behaviour/conversion` was outside P3.3's assigned scope and is still open.

### Festering land (phase 4; levels, nidi and spread built in P4.1)

- Chunk attachment `remission:chunk_corruption` (`RemissionAttachments.CHUNK_CORRUPTION`): `ChunkCorruption(level, last_update_game_time)`, level 0–100, saved with the chunk (never at level 0), server only (no sync: nothing on the client reads it yet; P4.2 decides how stages reach clients). Levels only rise in phase 4. Field-level contract and config: remission-data-formats.
- Nidi: block `remission:nidus` (`NidusBlock`, block entity `NidusBlockEntity`, no drops, pistons cannot move it). `NidusIndex` is a `SavedData` per level (`data/remission_nidi.dat` in the dimension folder) mapping each nidus position to the game time it was indexed. `onPlace` and the block entity's `onLoad` (covers nidi placed by worldgen or structures) add it; `onRemove` removes it, and a pass that finds a loaded index entry without its block drops it. Breaking a nidus stops its spread at once; its land keeps its level.
- Spread engine (`SpreadEngine`, one per server level, run from `LevelTickEvent.Post` by `FesteringLand`): a pass starts at each multiple of `spread.intervalTicks` of level game time and visits, nidus by nidus in index order, every chunk within the nidus's Manhattan radius, nearest first. The nidus chunk gains `nidusGrowth` per pass up to 100. Another loaded chunk at distance `d` gains `spreadStep` up to `100 - d * falloff` from its parent, the neighbour one step closer to the nidus along the larger offset axis, once the parent has reached `spreadThreshold` (an unloaded parent counts as at its own target). The radius follows from those numbers (7 chunks by default). A pass stops after `chunkBudget` chunk visits and resumes next tick. Nothing depends on iteration order of hash maps or on wall time, so the same state and the same ticks give the same levels (gametest `spreadIsDeterministic`). Overlapping nidi each add their step.
- Catch-up: `ChunkEvent.Load` only queues the chunk (the chunk is not yet in the level then); each tick up to `catchUpBudget` queued chunks are fast-forwarded in closed form: for each nidus in reach, the passes missed since `max(last_update_game_time, nidus indexed)` times the rate, capped at the target. That converges on the same targets as live spread, so land keeps festering while nobody is near, and a reload costs the same however long it was away. Unloaded chunks are never loaded by the engine.
- Hooks: `SpreadHooks` holds the three internal points where P4.4 posts the public events: `spreadAttempt` (before each chunk-to-chunk step, live and catch-up; returns the scaled amount, 0 to cancel), `chunkLevelChanged` (after each change; one per chunk for a catch-up) and `nidusDestroyed` (after removal from the index). The player who broke a nidus is not passed yet; P4.4 adds it if the event needs it.
- Measured cost (foundry, `SpreadBenchmark`, below): default config with 16 nidi over 1,024 loaded chunks adds 0.9 µs per tick on average and at most 0.13 ms in a tick (about 0.34 ms per 400-tick pass); a stress case of 64 nidi with a pass every second spends the full 256-visit budget every tick at 27 µs mean and 0.09 ms max. Catching up 1,024 chunks takes 32 ticks at most 0.18 ms (16 nidi) or 0.29 ms (64 nidi) each.

Planned: festering stages are applied as block transformations in bounded batches. Biome flip to `remission:fester` (or a per-source-biome fester variant from data) uses a mutable biome container write and chunk resend. Surges are scheduled per level. The same engine runs in reverse for the Counterstrain (phase 9).

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
| Immune mobs | `data/<ns>/tags/entity_type/immune.json` (`#remission:immune`; P3.3, built) | remission-data-formats |
| Per-mob overrides | `data/<ns>/remission/mob_profile/<name>.json` (datapack registry `remission:mob_profile`, world reload; P3.3, built) | remission-data-formats |
| Host loot | `data/<ns>/loot_modifiers/*.json` + `data/neoforge/loot_modifiers/global_loot_modifiers.json`, codec `remission:add_item`, default `remission:host_samples`, condition `remission:is_corrupted` (P3.3, built) | remission-data-formats |
| Immune mobs | `data/<ns>/tags/entity_type/immune.json` (`#remission:immune`) | remission-data-formats |
| Per-mob overrides | `data/<ns>/remission/mob_profile/<name>.json` (datapack registry `remission:mob_profile`) | remission-data-formats |
| Host loot | `data/<ns>/loot_modifiers/*.json` + `data/neoforge/loot_modifiers/global_loot_modifiers.json`, condition `remission:is_corrupted` | remission-data-formats |
| Festering land | chunk attachment `remission:chunk_corruption`, block `remission:nidus`, config `[spread]` (P4.1, built) | remission-data-formats |
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
- **Performance:** spread, catch-up and visual stage changes must be budgeted per tick and measured on foundry (phase 12 includes client FPS under heavy spread). The spread engine is measured by `SpreadBenchmark`, a gametest generator registered only when `REMISSION_SPREAD_BENCHMARK` is set (`REMISSION_SPREAD_BENCHMARK=1 ./gradlew runGameTestServer`); it writes `run/gametest/spread-benchmark-<case>.txt`.
- **Spread is chunk to chunk along one parent path:** each chunk takes the Strain from a single neighbour toward each nidus, so a cancelled spread attempt (a firebreak) holds everything behind it, and catch-up can be closed form because the steady state is `100 - distance * falloff`.
