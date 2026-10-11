---
name: remission-data-formats
description: Define, change or consume Remission's pack-facing data contracts (entity tags, per-mob override JSON, loot condition, recipe types, KubeJS schemas, advancement triggers, stats). Keep these schemas current with the code.
---

# Remission data formats

These are contracts between the mod and any pack. Every schema below is a **stub** (status `planned`) until the roadmap item named implements it; then update its status, fields and example in this file and docs/ARCHITECTURE.md in the same PR. Never ship a reader for a format that is not documented here. Paths use 1.21.1 singular folders; `<ns>` is any datapack namespace (the pack uses `counterstrain` or overrides `remission`).

## Entity tag `#remission:immune` (planned, P3.3)

`data/<ns>/tags/entity_type/immune.json`, standard tag JSON (`{"replace": false, "values": ["minecraft:wither", "#c:bosses", {"id": "othermod:x", "required": false}]}`). Immune mobs never become corrupted or convert. Defaults: Remission ships bosses and players-only types; the pack extends it.

## Mob profile (planned, P3.3)

Datapack registry `remission:mob_profile`, files `data/<ns>/remission/mob_profile/<name>.json`, reloaded with datapacks (registry: requires world reload; decide in P3.3 and record).

```json
{
  "entities": "#minecraft:undead",
  "priority": 0,
  "behaviour": "melee",
  "attributes": { "minecraft:generic.attack_damage": 4.0, "minecraft:generic.movement_speed": 0.3 },
  "convert_to": "minecraft:zombie",
  "conversion": { "on_kill": true, "exposure": true }
}
```

`entities`: id, tag or list (HolderSet). `behaviour`: `melee` | `ranged` | `flying` | `swimming` | `passive_hostile` | `none` (planned enum). `attributes`: added as modifiers while corrupted. `convert_to`: optional entity id replacing the victim on conversion. Highest `priority` wins per entity; mobs with no profile use category defaults.

## Loot condition `remission:is_corrupted` (planned, P3.3)

`{"condition": "remission:is_corrupted", "entity": "this", "min_stage": 1}`. Used in loot tables and global loot modifiers. GLMs: `data/<ns>/loot_modifiers/<name>.json`, listed in `data/neoforge/loot_modifiers/global_loot_modifiers.json` (`{"replace": false, "entries": ["remission:corrupted_samples"]}`). Remission's own defaults are datagen (`RemissionLootModifierProvider`).

## Recipe types (planned, P7.2)

`remission:analyzing`, `remission:sequencing`, `remission:synthesizing`, files `data/<ns>/recipe/<name>.json`:

```json
{ "type": "remission:analyzing", "ingredient": {"item": "remission:tissue_sample"}, "fluid": {"id": "remission:blight", "amount": 250}, "results": [{"id": "remission:data_fragment", "count": 1}], "energy": 2000, "time": 100 }
```

1.21.1 ingredient objects are `{"item": ...}`/`{"tag": ...}`; results `{"id", "count"}`. Final fields are fixed in P7.2.

**KubeJS schemas:** Remission ships `data/remission/kubejs/recipe_schema/<type>.json` (keys with `name`, `role` input/output/other, `type` such as `ingredient`, `item_stack`, `ticks`, `optional`, `function_names`) so KubeJS 2101 can create and edit these recipes without a Java dependency on KubeJS. Model them on KubeJS's own `data/minecraft/kubejs/recipe_schema/cooking.json`. Recipe schema changes are contract changes.

## Advancement triggers (planned, P8.1)

All `remission:<id>` criterion triggers with an optional `player` predicate plus:

| Trigger | Conditions |
| --- | --- |
| `region_cleansed` | `region` (string id, optional), `min_percent` (0–100) |
| `heart_destroyed` | `heart` (block or tag, optional) |
| `variant_scanned` | `entity` (entity predicate), `min_unique` (int, optional) |
| `surge_survived` | `min_intensity` (int, optional) |
| `corrupted` / `cleansed_self` | exposure thresholds (planned) |

Example: `{"criteria": {"heart": {"trigger": "remission:heart_destroyed", "conditions": {}}}}`.

## Stats (planned, P8.1)

Custom stats under `minecraft:custom`: `remission:hearts_destroyed`, `remission:chunks_cleansed`, `remission:variants_scanned`, `remission:surges_survived`. FTB Quests stat tasks read them.

## Quest-fired events (planned, P8.2)

Commands (`/remission surge start <pos> [intensity]`, `/remission heart seed <pos>`, `/remission region advance <id>`) usable from FTB Quests command rewards and KubeJS; a KubeJS binding may follow. Document exact syntax here when built.
