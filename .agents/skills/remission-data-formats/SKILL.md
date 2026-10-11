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
