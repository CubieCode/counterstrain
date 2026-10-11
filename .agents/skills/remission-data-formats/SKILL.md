---
name: remission-data-formats
description: Define, change or consume Remission's pack-facing contracts (entity tags, per-mob override JSON, loot condition, recipe types, KubeJS schemas, advancement triggers, stats, commands, public API events and their KubeJS bindings). Keep these schemas current with the code.
---

# Remission data formats

These are contracts between the mod and any pack. Schemas marked `planned` are stubs until the roadmap item named implements it; then update its status, fields and example in this file and docs/ARCHITECTURE.md in the same PR. Never ship a reader for a format that is not documented here. Paths use 1.21.1 singular folders; `<ns>` is any datapack namespace (the pack uses `counterstrain` or overrides `remission`).

Names follow the glossary and naming rule in docs/DESIGN.md: content IDs, triggers, stats and commands use Strain terms (`nidus`, `host`, `miasma`, `fester`); system names such as `CorruptionState`, the `corruption` attachment and `remission:is_corrupted` keep "corruption". Do not rename either.

## Entity attachment `remission:corruption` (built, P2.1)

`CorruptionState` on any entity, read and written through `com.cubiecode.remission.corruption.Corruption` (`get`, `set(entity, level)`, `isHost`; server writes only). Saved inside the entity's `neoforge:attachments` compound, omitted at level 0:

```json
{ "level": 50, "stage": 2 }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `level` | int 0–100 | How far the Strain has taken the entity. 0 removes the attachment. |
| `stage` | int ≥ 0 | Host stage: how many `hosts.stageThresholds` entries `level` met when it was set. 0 = not a host; `isHost` is `stage >= 1`. Readers use the stored stage, never recompute it. |
| `variant` | reserved | Resistant mutation (P9.1). Not written yet; unknown keys are ignored on load, so adding it is backward compatible. |

Config `config/remission-common.toml`, `[hosts] stageThresholds = [1, 34, 67]`: 1–8 ints in 1–100, order-independent. Changes apply the next time a level is set.

Sync: NeoForge attachment sync, wire format `CorruptionState.STREAM_CODEC` (var-int `level`, var-int `stage`), to players tracking the entity and to the entity itself if it is a player, on change, on start-tracking and on the player's login, respawn and dimension change. Clients read it with `Corruption.get`. Not copied on death or conversion. P4.4 adds the host corrupted / cleansed events, fired from `set`; the debug commands below drive it.

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

Not part of this contract yet: the `remission:host_scanned` trigger and `remission:hosts_scanned` stat (P8.1), per-player scan progress for the bestiary (P6.2) and scanning samples (unassigned).

## Entity tag `#remission:immune` (planned, P3.3)

`data/<ns>/tags/entity_type/immune.json`, standard tag JSON (`{"replace": false, "values": ["minecraft:wither", "#c:bosses", {"id": "othermod:x", "required": false}]}`). Immune mobs never become hosts or convert. Defaults: Remission ships bosses and players-only types; the pack extends it.

## Mob profile (planned, P3.3)

Datapack registry `remission:mob_profile`, files `data/<ns>/remission/mob_profile/<name>.json`, reloaded with datapacks (registry: requires world reload; decide in P3.3 and record).

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

`entities`: id, tag or list (HolderSet). `behaviour`: `melee` | `ranged` | `flying` | `swimming` | `passive_hostile` | `none` (planned enum). `attributes`: added as modifiers while the mob is a host. `convert_to`: optional entity id replacing the victim on conversion. `conversion`: whether a kill by this host (`on_kill`) and time in festering chunks (`festering_land`) infect. Highest `priority` wins per entity; mobs with no profile use category defaults.

## Loot condition `remission:is_corrupted` (planned, P3.3)

`{"condition": "remission:is_corrupted", "entity": "this", "min_stage": 1}`: true when the entity is a host at or above the stage. Used in loot tables and global loot modifiers. GLMs: `data/<ns>/loot_modifiers/<name>.json`, listed in `data/neoforge/loot_modifiers/global_loot_modifiers.json` (`{"replace": false, "entries": ["remission:host_samples"]}`). Remission's own defaults are datagen (`RemissionLootModifierProvider`).

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
| `/remission infect <targets> <level>` | `targets`: entity selector, any number; `level`: int 1–100. Sets every living target to `level` (may lower it) and restages it from `hosts.stageThresholds`. Non-living targets are skipped. | Living targets set | `commands.remission.infect.success.single` (name, level, stage) / `.multiple` (count, level); broadcast to ops |
| `/remission cleanse <targets>` | Sets every living target to level 0 (removes the attachment). Already clean targets count. | Living targets cleansed | `commands.remission.cleanse.success.single` (name) / `.multiple` (count); broadcast to ops |
| `/remission inspect <target>` | One entity. Reports its stored level and stage; changes nothing. | Strain level (0 if clean) | `commands.remission.inspect.success` (name, level, stage) or `commands.remission.inspect.clean` (name) |

Errors: `commands.remission.failed.no_living_targets` when `infect` or `cleanse` selects no living entity; `commands.remission.failed.not_living` (name) when `inspect` targets a non-living entity. Vanilla parse errors cover an out-of-range level and a multi-entity `inspect` selector. Example: `/execute store result score @s strain run remission inspect @e[type=minecraft:cow,limit=1,sort=nearest]`.

## Quest-fired events (planned, P8.2)

Commands (`/remission surge start <pos> [intensity]`, `/remission nidus seed <pos>`, `/remission region advance <id>`) under the same `/remission` root, usable from FTB Quests command rewards and KubeJS. Document exact syntax here when built.

## Festering land: chunk attachment, nidus, spread config (built, P4.1)

Chunk attachment `remission:chunk_corruption` (`ChunkCorruption` in `com.cubiecode.remission.world`), saved inside the chunk's `neoforge:attachments` compound, omitted at level 0. Server only, not synced. Internal until P4.4 adds the stage queries; packs read it through those.

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

Rules: spread runs chunk to chunk from the parent neighbour (one step toward the nidus along the larger offset axis), so cancelling one step blocks everything behind it; overlapping nidi each add their step; unloaded chunks are never loaded or written, and an unloaded parent counts as at its target. Default pace: a nidus chunk reaches the threshold after 8 passes (about 2.7 minutes), its neighbours reach 90 after about 45 more, and the seventh ring starts festering after about 128 passes (about 43 minutes of play, two in-game days). These are first-pass numbers for balancing in the pack.

Events: P4.4 posts the chunk stage changed, nidus destroyed and spread attempt events from the internal points in `SpreadHooks` (`chunkLevelChanged`, `nidusDestroyed`, `spreadAttempt`; the last runs before every live and catch-up step and its returned amount scales or cancels it). No player is passed to the nidus hook yet.

## Fester variants (planned, P4.2)

Per-source-biome fester target so modded biomes fester into their own variant, falling back to `remission:fester`. Shape (data map on `minecraft:worldgen/biome` or a datapack registry) is fixed in P4.2; the pack uses it for Biomes O' Plenty and Terralith (P10.7).

## Item contamination (planned, P10.6)

Data component `remission:contaminated` on item stacks carried through festering chunks, a tag for containers that protect their contents, and a decontamination recipe type or KubeJS hook. Fields fixed in P10.6.

## Dispersal payload (planned, P9.2)

Block `remission:dispersal_payload` deploys the Counterstrain. The planned API is a dispersal source (level, position, radius, rate) ticked by its holder. The placed block is one holder; the optional Create integration (P10.6) ticks it from a moving contraption. The API must not assume a block. Fields are fixed in P9.2.

## Public API events and queries (planned, P4.4)

Package `com.cubiecode.remission.api`; nothing outside it is public. Events are NeoForge events posted server side on `NeoForge.EVENT_BUS` after the change is applied, except the cancellable spread attempt, which is posted before. Batched changes (catch-up, stage batches) post one event per chunk unless the event is block-scoped. Java names may use "corruption".

| Event | Fired by | Payload (planned) | Cancellable |
| --- | --- | --- | --- |
| host corrupted | P2.1 setter, infection (P3.3) | entity, old and new state, cause | no |
| host cleansed | P2.1 setter, purification | entity, old state, cause | no |
| chunk stage changed | P4.1 | level, chunk pos, old and new stage | no |
| nidus destroyed | P4.1 | level, pos, player (optional) | no |
| Strain spread attempt | P4.1 | level, from and to chunk, rate multiplier (mutable) | yes |
| block festered | P4.2 | level, pos, old and new block state | no |
| surge started / ended | P4.3 | level, origin, intensity | no |
| viral load changed | P5.1 | player, old and new value, cause | no |
| block purified | P5.2 | level, pos, old and new block state, cause | no |
| chunk entered remission | P5.2 | level, chunk pos, player (optional) | no |

Queries: corruption stage at a position or chunk, `isHost(entity)`, a player's viral load (P5.1). Exact class names, fields and firing rules are recorded here by the item that builds them.

## KubeJS event bindings (planned, P8.3)

The API events above exposed to KubeJS server scripts through an optional `integration/kubejs/` plugin (no hard KubeJS dependency), with cancellation and the spread multiplier writable from script. Event group and method names are fixed in P8.3 and mirrored in kubejs-scripting.
