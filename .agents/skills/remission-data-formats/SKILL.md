---
name: remission-data-formats
description: Define, change or consume Remission's pack-facing contracts (entity tags, per-mob override JSON, loot condition, recipe types, KubeJS schemas, advancement triggers, stats, commands, public API events and their KubeJS bindings). Keep these schemas current with the code.
---

# Remission data formats

These are contracts between the mod and any pack. Schemas marked `planned` are stubs until the roadmap item named implements it; then update its status, fields and example in this file and docs/ARCHITECTURE.md in the same PR. Never ship a reader for a format that is not documented here. Paths use 1.21.1 singular folders; `<ns>` is any datapack namespace (the pack uses `counterstrain` or overrides `remission`).

Names follow the glossary and naming rule in docs/DESIGN.md: content IDs, triggers, stats and commands use Strain terms (`nidus`, `host`, `miasma`, `fester`); system names such as `CorruptionState`, the `corruption` attachment and `remission:is_corrupted` keep "corruption". Do not rename either.

## Entity attachment `remission:corruption` (built, P2.1)

`CorruptionState` on any entity, read and written through `com.cubiecode.remission.corruption.Corruption` (`get`, `set(entity, level)`, `isHost`, `isImmune`; server writes only). `set` on an entity in `#remission:immune` ignores any level above 0 and returns the entity's current state; level 0 always cleanses. When `set` turns an entity into a host or back, it applies or removes its mob profile `attributes` (below). Saved inside the entity's `neoforge:attachments` compound, omitted at level 0:

```json
{ "level": 50, "stage": 2 }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `level` | int 0–100 | How far the Strain has taken the entity. 0 removes the attachment. |
| `stage` | int ≥ 0 | Host stage: how many `hosts.stageThresholds` entries `level` met when it was set. 0 = not a host; `isHost` is `stage >= 1`. Readers use the stored stage, never recompute it. |
| `variant` | reserved | Resistant mutation (P9.1). Not written yet; unknown keys are ignored on load, so adding it is backward compatible. |

Config `config/remission-common.toml`, `[hosts] stageThresholds = [1, 34, 67]`: 1–8 ints in 1–100, order-independent. Changes apply the next time a level is set.

Sync: NeoForge attachment sync, wire format `CorruptionState.STREAM_CODEC` (var-int `level`, var-int `stage`), to players tracking the entity and to the entity itself if it is a player, on change, on start-tracking and on the player's login, respawn and dimension change. Clients read it with `Corruption.get`. Not copied on death or by vanilla conversions (a zombie villager cured, a pig struck by lightning); infection's own `convert_to` sets it on the replacement (below). `set` posts `HostCorruptedEvent` or `HostCleansedEvent` (Public API, below) when the state changes; `set(entity, level, cause)` names the `CorruptionCause` (infection and the debug commands pass theirs). The debug commands below drive it.

## Scanner and data items (built, P6.1)

Items: `remission:scanner` (stacks to 1; listed in the vanilla Tools and Utilities creative tab) and the data items `remission:host_scan` and `remission:block_scan`. Code: `research/Scanning` (behaviour), `research/ScanData` (component), `registry/RemissionDataComponents`.

Scanning, server side (the client only claims the interaction):

| Use the scanner on | Result |
| --- | --- |
| A host (any living entity with host stage ≥ 1, players included) | One `remission:host_scan` with `{"entity": <entity type id>, "stage": <stored host stage>}` into the inventory (dropped if full). Action bar `message.remission.scanner.host` (name, stage). The host is unchanged. |
| A living entity that is not a host (stage 0, including a latent level below the first threshold) | No item. Action bar `message.remission.scanner.no_strain` (name). |
| A non-living entity (item frame, boat, minecart) | Not a scan: the entity's own interaction runs as usual. |
| Any block | One `remission:block_scan` with `{"block": <block id>}` (block id only, no state or block entity data). Action bar `message.remission.scanner.block` (name). |

Every scan attempt (including the no-Strain case) puts the scanner on a 20-tick item cooldown; attempts during the cooldown do nothing. Entity scans are taken in `PlayerInteractEvent.EntityInteract` and `EntityInteractSpecific`, before the entity's own interaction, so scanning a villager does not open trading and scanning a horse does not mount it. Block scans run in `onItemUseFirst`, before the block's own use, so scanning a chest does not open it. No sneaking needed; spectators cannot scan.

Data component `remission:scan_data`, saved with the stack (`components` in item NBT/JSON) and synced to clients for the tooltip. Exactly one of two shapes:

```json
{ "entity": "minecraft:cow", "stage": 2 }
{ "block": "minecraft:stone" }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `entity` | resource location | Scanned host's entity type. |
| `stage` | int ≥ 1 | The host's stored stage at scan time (host scans only; never recomputed). |
| `block` | resource location | Scanned block. |

Ids are stored as plain ids, not registry holders, so a scan of a since-removed mod's entity or block still loads; its tooltip then shows the raw id. The component holds no timestamp or player, so equal scans stack and a recipe can match the whole component. Wire format (`ScanData.STREAM_CODEC`): var-int kind (0 host, 1 block), resource location, var-int stage (0 for blocks).

Matching scans in pack recipes (1.21.1 ingredients): any scan of a kind with `{"item": "remission:host_scan"}`; a specific scan with NeoForge's components ingredient, e.g. `{"type": "neoforge:components", "items": "remission:host_scan", "components": {"remission:scan_data": {"entity": "minecraft:cow", "stage": 2}}}` (stage is part of the value, so list one ingredient per stage that should count). A data item without the component (from `/give`) is valid but blank: no tooltip line, and it matches only plain item ingredients.

Not part of this contract yet: the `remission:host_scanned` trigger and `remission:hosts_scanned` stat (P8.1) and scanning samples (unassigned). Per-player scan progress: Bestiary, below.

## Bestiary (P6.2)

Host types are derived, not listed: every registered entity type that has default attributes (that is, is living), is not `minecraft:player` and is not in `#remission:immune` (`Bestiary.isHostType`, `Bestiary.hostTypes()`). Each installed mod's living mobs count without data; to drop a type from the bestiary (and from infection), tag it immune. Players can still carry the Strain and be scanned as hosts, but are never a bestiary entry.

Player attachment `remission:bestiary` (`BestiaryProgress`): the highest host stage each host type has been scanned at by this player. Written only on the server, when the scanner gives a host scan of a host type (`Bestiary.recordScan`, keeps the maximum); block scans, non-host scans, scans of types the bestiary does not list (a host player, an immune type) and `/give`n scans record nothing. Saved in the player's `neoforge:attachments` NBT only when non-empty and copied on death. Synced through NeoForge attachment sync: updates go to the owning player only, but NeoForge's initial sync when a client starts tracking an entity ignores the sync predicate, so clients tracking the player also receive its current map. Treat the map as visible to nearby clients; Remission's client reads only the local player's. Shape:

```json
{ "minecraft:cow": 2, "mekanism:robit": 1 }
```

| Key / value | Type | Meaning |
| --- | --- | --- |
| key | resource location | Scanned host's entity type. Kept even if the type is no longer registered (not listed while missing). |
| value | int ≥ 1 | Highest host stage scanned for that type. |

Wire format (`BestiaryProgress.STREAM_CODEC`): var-int entry count, then per entry a resource location and a var-int stage. The client opens the bestiary with the key mapping `key.remission.bestiary` (category `key.categories.remission`, default M). Not part of this contract: the `remission:host_scanned` trigger and `remission:hosts_scanned` stat (P8.1).

## Infection (built, P3.3)

`com.cubiecode.remission.infection.Infection`, server side. A `Mob` that dies (`LivingDeathEvent`, listened at `LOW` priority so other mods' death protection wins) while the damage source's responsible entity (`DamageSource.getEntity()`, so a host skeleton's arrow counts) is a host does not die; the event is cancelled and it becomes a host at the first host stage (level = the lowest `hosts.stageThresholds` entry):

- With no mob profile `convert_to`, or `convert_to` naming its own type, it converts in place: same entity, healed to full health, no death loot or XP.
- Otherwise `Mob.convertTo(type, true)` replaces it (position, age, AI flag, name, persistence, invulnerability, equipment with drop chances, vehicle), `LivingConversionEvent.Pre` can veto it and `.Post` fires. The replacement joins the level first and is infected right after.

The victim dies normally when it is immune, already a host, its profile sets `conversion.on_kill` to false, `convert_to` names an immune type or a non-mob type (logged), or another mod vetoes the conversion. Players and other non-`Mob` living entities are never infected on kill.

Festering land: `Infection.canInfectFromFesteringLand(Mob)` (not immune, not a host, profile `conversion.festering_land` not false) is the eligibility check for the phase 4 source; nothing calls it yet and no exposure is tracked.

## Entity tag `#remission:immune` (built, P3.3)

`data/<ns>/tags/entity_type/immune.json`, standard tag JSON (`{"replace": false, "values": ["minecraft:wither", "#c:bosses", {"id": "othermod:x", "required": false}]}`); Java `Corruption.IMMUNE`. An immune entity never becomes a host by any route: `Corruption.set` ignores levels above 0, infection on kill and `convert_to` skip it, and `/remission infect` skips it. A type added to the tag after it already carries the Strain keeps its state until cleansed. Remission ships only `#c:bosses` (ender dragon, wither, and whatever other mods tag); the pack extends it. Players are deliberately not immune: they can carry the Strain (`/remission infect`, rendered by the host overlay, scannable), but infection on kill never takes them because it only converts `Mob`s.

## Mob profile (built, P3.3)

Datapack registry `remission:mob_profile` (`MobProfiles.KEY`), files `data/<ns>/remission/mob_profile/<name>.json`, server side only (not synced). **Reload:** datapack registries load with the world, so adding, editing or removing a profile needs a world reload (rejoin a singleplayer world, restart a server); `/reload` does not re-read profiles, but tags a profile names in `entities` do rebind on `/reload`. Existing hosts pick up changed `attributes` the next time they join a level after the reload. Remission ships no profiles.

```json
{
  "entities": "#minecraft:undead",
  "priority": 0,
  "behaviour": "melee",
  "attributes": { "minecraft:generic.attack_damage": 4.0, "minecraft:generic.movement_speed": 0.3 },
  "convert_to": "minecraft:zombie",
  "conversion": { "on_kill": true, "festering_land": true }
}
```

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `entities` | entity type id, `#tag` or list (HolderSet) | required | Types the profile applies to. |
| `priority` | int | 0 | Where several profiles match a type, the highest wins; ties go to the profile with the lexicographically smallest id. Profiles never merge. |
| `behaviour` | `melee` \| `ranged` \| `flying` \| `swimming` \| `passive_hostile` \| `none` (`MobBehaviour`, built) | absent | How the host fights; `HostBehaviours.behaviourFor` uses it ahead of the category default when present. Value meanings and category defaults: docs/ARCHITECTURE.md, Behaviour. `flying` and `swimming` change no goals until P3.2; `none` opts the mob out of host goals. |
| `attributes` | map attribute id → number | `{}` | Added (`add_value`) as permanent modifiers with id `remission:mob_profile` when the mob becomes a host, removed when it stops being one, reconciled with the current profile whenever a host joins a level. Attributes the type lacks are skipped. |
| `convert_to` | entity type id | absent | Mob type that replaces this mob when a host kills it (Infection above). Must be a non-immune `Mob` type. |
| `conversion.on_kill` | bool | true | Whether this mob becomes a host when a host kills it. |
| `conversion.festering_land` | bool | true | Whether prolonged time in festering land can make this mob a host (phase 4). |

All `conversion` flags describe the profiled mob as the one being infected. A profile that fails to decode (unknown `behaviour`, attribute or entity id) is a datapack registry error, which stops the world from loading. Lookup: `MobProfiles.find(Entity)` → `Optional<MobProfile>`; `MobProfiles.select(candidates, type)` is the same rule over a given list.

## Loot condition `remission:is_corrupted` (built, P3.3)

`{"condition": "remission:is_corrupted", "entity": "this", "min_stage": 1}`: true when the `entity` loot target (`this`, `attacker`, `direct_attacker`, `attacking_player`; default `this`) is a living entity at host stage `min_stage` (int ≥ 1, default 1) or higher; false when the target is absent or not living. Java `IsCorruptedCondition`. Usable in loot tables, predicates and global loot modifiers.

## Global loot modifiers (built, P3.3)

Codec `remission:add_item` (`AddItemModifier`): `{"type": "remission:add_item", "conditions": [...], "item": {"id": "remission:host_tissue", "count": 1}}` adds a copy of `item` (an item stack: `id`, optional `count`, `components`) when every condition passes. Remission's default, `data/remission/loot_modifiers/host_samples.json` (datagen, `RemissionLootModifierProvider`), listed in `data/neoforge/loot_modifiers/global_loot_modifiers.json` (`{"replace": false, "entries": ["remission:host_samples"]}`):

```json
{
  "type": "remission:add_item",
  "conditions": [
    { "condition": "remission:is_corrupted" },
    { "condition": "minecraft:damage_source_properties" }
  ],
  "item": { "id": "remission:host_tissue", "count": 1 }
}
```

One `remission:host_tissue` from the death loot of any host (the damage source condition limits it to death drops, not bartering or other tables that set `this`). A host converted on kill does not die, so it drops nothing. Packs change it by overriding the same file, drop it by overriding `global_loot_modifiers.json` with `"replace": true` and relisting the modifiers they keep, and add their own modifiers in their namespace.

## Recipe types (planned, P7.2)

`remission:analyzing`, `remission:sequencing`, `remission:synthesizing`, files `data/<ns>/recipe/<name>.json`:

```json
{ "type": "remission:analyzing", "ingredient": {"item": "remission:host_tissue"}, "fluid": {"id": "remission:miasma", "amount": 250}, "results": [{"id": "remission:data_fragment", "count": 1}], "energy": 2000, "time": 100 }
```

1.21.1 ingredient objects are `{"item": ...}`/`{"tag": ...}`; results `{"id", "count"}`. Final fields are fixed in P7.2.

**KubeJS schemas:** Remission ships `data/remission/kubejs/recipe_schema/<type>.json` (keys with `name`, `role` input/output/other, `type` such as `ingredient`, `item_stack`, `ticks`, `optional`, `function_names`) so KubeJS 2101 can create and edit these recipes without a Java dependency on KubeJS. Model them on KubeJS's own `data/minecraft/kubejs/recipe_schema/cooking.json`. Recipe schema changes are contract changes.

## Advancement triggers (planned, P8.1)

All `remission:<id>` criterion triggers with an optional `player` predicate plus:

| Trigger | Conditions |
| --- | --- |
| `region_cleansed` | `region` (string id, optional), `min_percent` (0–100) |
| `nidus_destroyed` | `nidus` (block or tag, optional) |
| `host_scanned` | `entity` (entity predicate), `min_unique` (int, optional) |
| `surge_survived` | `min_intensity` (int, optional) |
| `viral_load` | `min` / `max` (viral load thresholds, optional) |

Example: `{"criteria": {"nidus": {"trigger": "remission:nidus_destroyed", "conditions": {}}}}`. "Variant" is reserved for resistant mutations; a trigger for sequencing one may follow in P9.1.

## Stats (planned, P8.1)

Custom stats under `minecraft:custom`: `remission:nidi_destroyed`, `remission:chunks_cleansed`, `remission:hosts_scanned`, `remission:surges_survived`. FTB Quests stat tasks read them.

## Debug commands `/remission` (built, P2.3)

Registered in `commands/RemissionCommands` on `RegisterCommandsEvent`. Every `/remission` subcommand needs permission level 2 (command blocks, functions, server console, ops). Hosts change only through `Corruption.set`, so staging, sync and (from P4.4) API events behave exactly as for gameplay writes.

| Syntax | Effect | Result (`execute store result`) | Feedback |
| --- | --- | --- | --- |
| `/remission infect <targets> <level>` | `targets`: entity selector, any number; `level`: int 1–100. Sets every living target to `level` (may lower it) and restages it from `hosts.stageThresholds`. Non-living and `#remission:immune` targets are skipped. | Living, non-immune targets set | `commands.remission.infect.success.single` (name, level, stage) / `.multiple` (count, level); broadcast to ops |
| `/remission cleanse <targets>` | Sets every living target to level 0 (removes the attachment). Already clean targets count. | Living targets cleansed | `commands.remission.cleanse.success.single` (name) / `.multiple` (count); broadcast to ops |
| `/remission inspect <target>` | One entity. Reports its stored level and stage; changes nothing. | Strain level (0 if clean) | `commands.remission.inspect.success` (name, level, stage) or `commands.remission.inspect.clean` (name) |

Errors: `commands.remission.failed.no_living_targets` when `infect` or `cleanse` selects no living entity; `commands.remission.failed.immune` when every living target of `infect` is immune; `commands.remission.failed.not_living` (name) when `inspect` targets a non-living entity. Vanilla parse errors cover an out-of-range level and a multi-entity `inspect` selector. Example: `/execute store result score @s strain run remission inspect @e[type=minecraft:cow,limit=1,sort=nearest]`.

## Quest-fired events (planned, P8.2)

Commands (`/remission surge start <pos> [intensity]`, `/remission nidus seed <pos>`, `/remission region advance <id>`) under the same `/remission` root, usable from FTB Quests command rewards and KubeJS. Document exact syntax here when built.

## Festering land: chunk attachment, nidus, spread config (built, P4.1)

Chunk attachment `remission:chunk_corruption` (`ChunkCorruption` in `com.cubiecode.remission.world`), saved inside the chunk's `neoforge:attachments` compound, omitted at level 0. Server only, not synced. Internal: packs read it through `CorruptionQueries` (Public API, below).

```json
{ "level": 42, "last_update_game_time": 1234000 }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `level` | int 0–100 | Strain level of the land. Only rises in phase 4; festering stages (P4.2) are derived from it. |
| `last_update_game_time` | long | Level game time of the last change; catch-up credits the passes missed since then (or since the nidus was indexed, if later). |

Block `remission:nidus` with block entity `remission:nidus` (no saved fields yet) and block item `remission:nidus`. Drops nothing (loot table `remission:blocks/nidus`), mineable with a hoe (`#minecraft:mineable/hoe`), cannot be pushed. Placing it (or loading a chunk that has one) indexes it; removing it in any way stops its spread and leaves its land as it is. Index: `data/remission_nidi.dat` in the dimension folder, `{ "nidi": [ { "pos": <BlockPos.asLong>, "since": <game time> }, ... ] }`. Packs place nidi as ordinary blocks (structures, `/setblock`, features); `/remission nidus seed` is P8.2.

Config `config/remission-common.toml`, section `[spread]` (all ints, applied from the next pass; budgets per server tick and level):

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `intervalTicks` | 400 | 20–72000 | Game ticks between passes (passes start at multiples of level game time). |
| `nidusGrowth` | 5 | 1–100 | Level the nidus chunk gains per pass, up to 100. |
| `spreadStep` | 2 | 1–100 | Level another chunk in reach gains per pass from its neighbour toward the nidus. |
| `falloff` | 10 | 1–100 | A chunk `d` chunks (Manhattan) from a nidus reaches at most `100 - d * falloff` through it. |
| `spreadThreshold` | 40 | 1–100 | Level a chunk needs before it passes the Strain outward. Reach is the largest `d` with target(`d`) > 0 and target(`d - 1`) ≥ threshold: 7 by default. |
| `chunkBudget` | 256 | 1–65536 | Chunk visits per tick for the live pass; a longer pass continues next tick. |
| `catchUpBudget` | 32 | 1–4096 | Reloaded chunks fast-forwarded per tick; the rest wait in a queue. |

Rules: spread runs chunk to chunk from the parent neighbour (one step toward the nidus along the larger offset axis), so cancelling one step blocks everything behind it while the blocked chunk is loaded (live passes and catch-up both hold a step whose loaded parent is below the threshold); overlapping nidi each add their step; unloaded chunks are never loaded or written, and an unloaded parent counts as at its target (so a firebreak chunk that unloads stops holding). Catch-up of a region that loads at once runs from the nidus outward whatever the load order: a chunk whose loaded, below-threshold parent is still queued catches that parent up first (at most radius deep, stopping at cycles between nidi), and each chunk is caught up once. Default pace: a nidus chunk reaches the threshold after 8 passes (about 2.7 minutes), its neighbours reach 90 after about 45 more, and the seventh ring starts festering after about 128 passes (about 43 minutes of play, two in-game days). These are first-pass numbers for balancing in the pack.

Events: the spread engine posts `ChunkStageChangedEvent`, `NidusDestroyedEvent` and the cancellable `StrainSpreadAttemptEvent` (Public API, below).

## Fester variants (planned, P4.2)

Per-source-biome fester target so modded biomes fester into their own variant, falling back to `remission:fester`. Shape (data map on `minecraft:worldgen/biome` or a datapack registry) is fixed in P4.2; the pack uses it for Biomes O' Plenty and Terralith (P10.7).

## Item contamination (planned, P10.6)

Data component `remission:contaminated` on item stacks carried through festering chunks, a tag for containers that protect their contents, and a decontamination recipe type or KubeJS hook. Fields fixed in P10.6.

## Dispersal payload (planned, P9.2)

Block `remission:dispersal_payload` deploys the Counterstrain. The planned API is a dispersal source (level, position, radius, rate) ticked by its holder. The placed block is one holder; the optional Create integration (P10.6) ticks it from a moving contraption. The API must not assume a block. Fields are fixed in P9.2.

## Public API events and queries (built, P4.4)

Package `com.cubiecode.remission.api`; nothing outside it is public (the `corruption`, `world` and other packages are internal, even where their classes are `public`). Java names may use "corruption".

Conventions, for every event here and every event later items add:

- NeoForge events on `NeoForge.EVENT_BUS`, posted on the logical server, server thread only. Nothing is posted on the client.
- Posted after the state change is applied, so `CorruptionQueries` in a listener already returns the new state. The one exception is the cancellable `StrainSpreadAttemptEvent`, posted before its step.
- Batched changes (catch-up of a reloaded chunk, later bounded stage batches) post one event per chunk, never one per block or per missed pass, unless the event is block-scoped.
- Base classes are abstract and cannot be listened to: `HostEvent` (extends NeoForge `LivingEvent`; `getEntity()` is the `LivingEntity`) and `FesteringLandEvent` (extends `Event`; `getLevel()` is the `ServerLevel`). Listen to the concrete events.
- Levels are ints 0–100 (`CorruptionQueries.MAX_LEVEL`) for entities and chunks. Host stages come from `[hosts] stageThresholds`, chunk (festering) stages from `[festering] stageThresholds`.

| Event | Fields (getters) | Fired | Cancellable |
| --- | --- | --- | --- |
| `HostCorruptedEvent extends HostEvent` | `getEntity()`, `getOldLevel()`, `getOldStage()`, `getNewLevel()`, `getNewStage()`, `getCause()`; `wasHost()`, `isHost()`, `becameHost()` | After `Corruption.set` raises an entity's level (or, at the same level, its stage after a threshold change), with attachment, host attributes and host goals already applied. Every increase, not only the first; `becameHost()` marks the non-host to host change. Never for immune entities (their state does not change) or no-op sets. | no |
| `HostCleansedEvent extends HostEvent` | as above; `isFullyCleansed()` (new level 0) | After `Corruption.set` lowers an entity's level (or stage), including partial cleansing. | no |
| `ChunkStageChangedEvent extends FesteringLandEvent` | `getLevel()`, `getChunk()` (`ChunkPos`), `getOldLevel()`, `getNewLevel()`, `getOldStage()`, `getNewStage()` | After the spread engine stores a chunk level whose festering stage differs from the old one: live passes and catch-up. A catch-up posts once per chunk, so stages can jump by more than one; changes within a stage post nothing. | no |
| `NidusDestroyedEvent extends FesteringLandEvent` | `getLevel()`, `getPos()` (the nidus block) | After a nidus leaves the level's index: its block was broken, replaced or removed, or a spread pass found an index entry without its block. Its land keeps its level. No player is passed; quests use the `nidus_destroyed` trigger (planned, P8.1). To protect a nidus, cancel the block break. When the block was removed, it is posted from the block's `onRemove` inside `setBlockState`: listeners must not set the block at `getPos()` in the listener (defer it). | no |
| `StrainSpreadAttemptEvent extends FesteringLandEvent` | `getLevel()`, `getFrom()`, `getTo()` (`ChunkPos`), `getAmount()` (level gain at multiplier 1, capped at the target), `isCatchUp()`, `getRateMultiplier()`/`setRateMultiplier(double)` (default 1; negative or NaN become 0) | Before every chunk-to-chunk step: each live step into a loaded chunk below its target whose parent passed the threshold, and once per nidus in reach when a reloaded chunk catches up (`isCatchUp()`, `getAmount()` covers every missed pass, `getFrom()` may be unloaded). The growth of a nidus's own chunk is not a spread attempt, and a step from a loaded parent below the threshold is held without an event (live and catch-up). | yes: cancelling (or multiplier 0) stops this step; chunks reached through it stay clean while the cancelled chunk is loaded. A step from an unloaded parent is not held (the parent counts as at its target), so keep a firebreak chunk loaded or cancel the steps behind it too |

Spread rate: the gain is `amount * multiplier`, capped at the chunk's target. A fractional part is added as one more level with that probability, drawn from a hash of the two chunks and the game time, so slowed spread still advances on average and the same state and ticks give the same levels. Listeners compose by multiplying (`event.setRateMultiplier(event.getRateMultiplier() * 0.5)`). A catch-up step scaled below 1 (or cancelled) still moves the chunk's `last_update_game_time` to now, so the passes it shortened are not credited again: the chunk ends below where full-rate spread would have taken it and continues from there.

Re-entrancy: a host event listener that changes the same entity's level (`Corruption.set`) posts a nested event at once; later listeners of the outer event then see its stale values while queries return the nested change. Defer such changes to a later tick.

`CorruptionCause` (enum, `HostEvent.getCause()`): `INFECTION` (killed by a host and infected in place or converted), `COMMAND` (`/remission infect` or `/remission cleanse`), `OTHER` (any other caller). Later items append causes (purification, P5.2); treat unknown values as `OTHER`.

Queries, `CorruptionQueries` (static):

| Method | Returns | Side |
| --- | --- | --- |
| `isHost(Entity)` | whether a living entity's stored host stage is ≥ 1; false for non-living entities | both (hosts are synced) |
| `hostLevel(Entity)`, `hostStage(Entity)` | stored level and host stage; 0 for clean or non-living entities | both |
| `chunkLevel(ServerLevel, ChunkPos)`, `levelAt(ServerLevel, BlockPos)` | Strain level of a loaded chunk (of the chunk holding the position); 0 if clean or not loaded (never loads a chunk) | server |
| `chunkStage(ServerLevel, ChunkPos)`, `stageAt(ServerLevel, BlockPos)` | festering stage of that level (every block in a chunk shares its chunk's stage) | server |
| `chunkStageFor(int level)`, `maxChunkStage()` | stage of a chunk level under the current config; the number of chunk stages | server config |

Config `config/remission-common.toml`, `[festering] stageThresholds = [1, 25, 50, 75]`: 1–8 ints in 1–100, order-independent. A chunk's stage is the number of thresholds its level meets (0 clean, 1–4 with the defaults; P4.2 maps them to festering grass, trees and foliage, festered stone and the biome flip). Read whenever a level changes or a stage is queried; changing it posts no events for chunks whose stage moved.

Planned by later items, added here when built: block festered (P4.2), surge started / ended (P4.3), viral load changed and a player's viral load query (P5.1), block purified and chunk entered remission (P5.2).

| Event | Fired by | Payload (planned) | Cancellable |
| --- | --- | --- | --- |
| block festered | P4.2 | level, pos, old and new block state | no |
| surge started / ended | P4.3 | level, origin, intensity | no |
| viral load changed | P5.1 | player, old and new value, cause | no |
| block purified | P5.2 | level, pos, old and new block state, cause | no |
| chunk entered remission | P5.2 | level, chunk pos, player (optional) | no |

## KubeJS event bindings (planned, P8.3)

The API events above exposed to KubeJS server scripts through an optional `integration/kubejs/` plugin (no hard KubeJS dependency), with cancellation and the spread multiplier writable from script. Event group and method names are fixed in P8.3 and mirrored in kubejs-scripting.
