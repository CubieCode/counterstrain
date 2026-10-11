---
name: minecraft-datapack
description: "Create, edit, and debug Minecraft 1.21.1 (data pack format 48) datapacks and NeoForge 21.1 data files, including functions, advancements, recipes, loot tables, predicates, tags, NeoForge conditions, global loot modifiers, data maps, and pack metadata. Use when the deliverable is data files rather than Java code."
---

> Vendored from https://github.com/jahrome907/minecraft-codex-skills (commit
> `dd57c5a97741cdc0eb5bb3a8f876581a4f09eeb0`), MIT license — see
> `../LICENSE.minecraft-codex-skills`. Adapted for Minecraft 1.21.1 + NeoForge for
> Counterstrain/Remission. Project skills (remission-data-formats,
> kubejs-scripting, packwiz-pack, etc.) take precedence where they overlap.

# Minecraft Datapack Skill (1.21.1)

Target: Minecraft **1.21.1**, data pack format **48**, NeoForge 21.1.x.
Anything introduced in 1.21.2 or later (string-only recipe ingredients,
`min_format`/`max_format`, item model definitions, SNBT text components, 26.x
data) does **not** load in 1.21.1 — do not copy it from newer docs or misode
presets set to a newer version.

Where data lives in this project:
- Mod data: `mod/src/main/resources/data/remission/...`; NeoForge datagen output
  in `mod/src/generated/resources/data/...` (regenerate, don't hand-edit).
- Pack-level data: datapacks under `pack/`, or loose data files in
  `pack/kubejs/data/<ns>/...` (KubeJS loads them as a datapack; no
  `pack.mcmeta` needed there).

## Skill Scope

- `Use when`: the deliverable is datapack files (`pack.mcmeta`, `data/...`), `.mcfunction`, or NeoForge data JSON.
- `Do not use when`: the request requires Java code or runtime mod behavior (`minecraft-modding`), or KubeJS scripts (project `kubejs-scripting`).

---

## Pack Metadata (`pack.mcmeta`)

```json
{
  "pack": {
    "pack_format": 48,
    "description": "My Custom Datapack"
  }
}
```

- 48 = 1.21 / 1.21.1. `supported_formats` (int, `[min, max]`, or
  `{"min_inclusive", "max_inclusive"}`) is optional and valid since 1.20.2.
- `min_format` / `max_format` (1.21.9+) are **not in 1.21.1**; never use them.
- Optional top-level `filter` (see recipes reference) and `overlays`
  (`{"entries": [{"formats": ..., "directory": ...}]}`) are valid in 1.21.1.
- Mod resources (`mod/src/main/resources`) don't need a hand-written
  `pack.mcmeta`; NeoForge supplies the mod's pack metadata.

---

## Directory Layout (singular folder names since 1.21)

```
my-datapack/
├── pack.mcmeta
└── data/
    ├── <namespace>/
    │   ├── function/            *.mcfunction
    │   ├── advancement/
    │   ├── recipe/
    │   ├── loot_table/
    │   ├── predicate/
    │   ├── item_modifier/
    │   ├── structure/           *.nbt templates
    │   ├── loot_modifiers/      NeoForge GLM definitions
    │   ├── data_maps/           NeoForge data maps
    │   ├── neoforge/biome_modifier/   (see minecraft-world-generation)
    │   └── tags/
    │       ├── block/  item/  entity_type/  fluid/  function/
    │       └── worldgen/biome/ ...
    ├── neoforge/loot_modifiers/global_loot_modifiers.json
    └── minecraft/tags/function/
        ├── load.json             ← runs on load and /reload
        └── tick.json             ← runs every game tick
```

Plural names (`functions/`, `loot_tables/`, `recipes/`, `advancements/`,
`predicates/`, `item_modifiers/`, `structures/`, `tags/items/`, `tags/blocks/`,
`tags/entity_types/`, `tags/functions/`, ...) are pre-1.21 and silently ignored.

---

## Function Tags (load / tick)

Only `minecraft:load` and `minecraft:tick` run automatically, so these files must
be in the `minecraft` namespace.

`data/minecraft/tags/function/load.json`:
```json
{ "values": ["<namespace>:setup"] }
```

`data/<namespace>/function/setup.mcfunction`:
```mcfunction
scoreboard objectives add deaths deathCount
tellraw @a {"text":"[MyPack] Loaded!","color":"green"}
```

Keep `tick` functions short and targeted:
```mcfunction
execute as @a[scores={deaths=1..}] run function mypack:on_death_check
```

## Commands and Storage

```mcfunction
execute as @a[gamemode=!spectator] at @s if block ~ ~-1 ~ #minecraft:logs run give @s minecraft:apple
execute store result score @s mypack.health run data get entity @s Health
execute in minecraft:the_nether run say In the Nether

data modify storage mypack:data config.difficulty set value "hard"
data modify storage mypack:log last_player_pos set from entity @s Pos
```

Text components in 1.21.1 commands are JSON (SNBT text components are 1.21.5+).
Item stacks in commands use component syntax (`minecraft:diamond_sword[minecraft:damage=5]`, 1.20.5+).
See https://minecraft.wiki/w/Commands (check each page's history for 1.21.1).

## Macros (1.20.2+)

```mcfunction
# data/mypack/function/greet.mcfunction
$tellraw @a {"text":"Welcome $(name)!","color":"gold"}
```
```mcfunction
data modify storage mypack:tmp input set value {name:"Steve"}
function mypack:greet with storage mypack:tmp input
function mypack:greet with entity @p
```

---

## Registry data examples

Read [references/data-examples.md](references/data-examples.md) for 1.21.1
advancements, recipes, loot tables, predicates, tags, and NeoForge data
(conditions, global loot modifiers, data maps). Worldgen data (biomes,
features, structures, biome modifiers) is covered by `minecraft-world-generation`.

---

## Installation & Testing

- Standalone datapack: place the folder/ZIP in the world's `datapacks/`, then
  `/datapack list`, `/datapack enable "file/my-datapack"`, `/reload`.
- Mod data: run NeoForge datagen / `runClient`; pack data: refresh via packwiz
  or KubeJS as described in the project skills.

Workflow:
1. Edit `.mcfunction` / `.json` files.
2. Validate: `./scripts/validate-datapack.sh --root /path/to/datapack` (add `--strict` to fail on warnings;
   `--no-mcmeta` for roots without `pack.mcmeta`, e.g. `mod/src/main/resources` or `pack/kubejs`).
3. `/reload` in-game, run the target (`/function mypack:setup`, trigger an advancement).
4. Check `logs/latest.log` for parse errors (missing references, bad codecs).

## Validator Script

`scripts/validate-datapack.sh` (uses `jq`, falls back to `scripts/jq-shim.mjs` under Node) checks:
- JSON validity of `pack.mcmeta` and `data/**/*.json`
- `pack.mcmeta` uses integer `pack_format` (warns if not 48) and no 1.21.9+ `min_format`/`max_format`
- pre-1.21 plural folder names
- `minecraft:load`/`tick` references resolve to local `.mcfunction` files; warns on custom-namespace `load.json`/`tick.json`

It does not run Mojang codecs; a clean run does not prove the schema matches 1.21.1.

## Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `Unknown or invalid command` | Syntax error in function | Check whitespace, selector, trailing space |
| Data silently missing | Plural folder name or wrong namespace | Use 1.21 singular folders |
| `Couldn't parse data file` / codec error | 1.21.2+ JSON shape (e.g. string ingredients) | Use 1.21.1 shapes from the reference |
| Function not running on tick | Tag not under `data/minecraft/tags/function/` | Move `tick.json` |
| Macro error | `$` line but no `with` | Provide `with storage/entity/block` |

## References

- Data pack: https://minecraft.wiki/w/Data_pack
- Pack format history: https://minecraft.wiki/w/Pack_format
- Function: https://minecraft.wiki/w/Function_(Java_Edition)
- NeoForge 1.21.1 data docs: https://docs.neoforged.net/docs/1.21.1/resources/#data
- misode generators (set version to 1.21.1): https://misode.github.io/
