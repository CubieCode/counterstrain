# Datapack data examples (Minecraft 1.21.1 + NeoForge 21.1)

Read the section for the requested registry. All shapes are for 1.21.1
(data pack format 48). Newer shapes (1.21.2+) fail to decode in 1.21.1.

## Advancements

### `data/<namespace>/advancement/my_advancement.json`
```json
{
  "display": {
    "icon": { "id": "minecraft:diamond" },
    "title": { "text": "Diamond Hunter" },
    "description": { "text": "Obtain your first diamond" },
    "frame": "task",
    "show_toast": true,
    "announce_to_chat": true,
    "hidden": false
  },
  "criteria": {
    "obtained_diamond": {
      "trigger": "minecraft:inventory_changed",
      "conditions": {
        "items": [ { "items": "minecraft:diamond" } ]
      }
    }
  },
  "rewards": {
    "function": "mypack:on_diamond_obtained",
    "experience": 10
  }
}
```

Item predicates (`items`) accept an id, a `#tag`, or a list (since 1.20.5).
Common triggers: `minecraft:impossible` (manual grants), `minecraft:tick`,
`minecraft:player_killed_entity`, `minecraft:entity_killed_player`,
`minecraft:placed_block`, `minecraft:inventory_changed`,
`minecraft:changed_dimension`, `minecraft:consume_item`, `minecraft:location`,
`minecraft:recipe_unlocked`.

---

## Recipes (1.21.1 format)

In 1.21.1 every ingredient is an **object**: `{"item": "<id>"}` or
`{"tag": "<tag id>"}` (or a list of those for alternatives). Bare strings like
`"minecraft:diamond"` / `"#minecraft:planks"` as ingredients are the 1.21.2+
format and **not in 1.21.1**. Results use `{"id": ..., "count": ...}` with
optional `components` (since 1.20.5).

### Shaped crafting (`data/<namespace>/recipe/shaped.json`)
```json
{
  "type": "minecraft:crafting_shaped",
  "category": "misc",
  "pattern": ["DDD", "D D", "DDD"],
  "key": {
    "D": { "item": "minecraft:diamond" }
  },
  "result": { "id": "minecraft:diamond_block", "count": 1 }
}
```

### Shapeless crafting
```json
{
  "type": "minecraft:crafting_shapeless",
  "ingredients": [
    { "item": "minecraft:wheat" },
    { "item": "minecraft:wheat" },
    { "tag": "c:crops/wheat" }
  ],
  "result": { "id": "minecraft:bread", "count": 2 }
}
```

### Smelting / blasting / smoking / campfire_cooking
```json
{
  "type": "minecraft:smelting",
  "ingredient": { "item": "minecraft:beef" },
  "result": { "id": "minecraft:cooked_beef" },
  "experience": 0.35,
  "cookingtime": 200
}
```

### Smithing transform
```json
{
  "type": "minecraft:smithing_transform",
  "template": { "item": "minecraft:netherite_upgrade_smithing_template" },
  "base": { "item": "minecraft:diamond_sword" },
  "addition": { "item": "minecraft:netherite_ingot" },
  "result": { "id": "minecraft:netherite_sword", "count": 1 }
}
```

### Disable or condition a recipe (NeoForge)

An empty `{}` is not a valid recipe. On NeoForge, override the recipe at the
same path (`data/minecraft/recipe/piston.json`) with:

```json
{ "neoforge:conditions": [ { "type": "neoforge:false" } ] }
```

Vanilla alternative: a `filter` block in the overriding pack's `pack.mcmeta`
hides matching files from packs loaded below it:

```json
{
  "pack": { "pack_format": 48, "description": "..." },
  "filter": {
    "block": [ { "namespace": "minecraft", "path": "recipe/piston\\.json" } ]
  }
}
```

For pack-level recipe edits, prefer KubeJS (`kubejs-scripting`) where the
project already uses it.

---

## NeoForge conditions

`neoforge:conditions` (root of any conditional JSON: recipes, advancements,
loot tables, tags entries via datagen, data maps, biome modifiers...) is a
list; all must pass. Built-ins: `neoforge:true`, `neoforge:false`,
`neoforge:not` (`value`), `neoforge:and` / `neoforge:or` (`values`),
`neoforge:mod_loaded` (`modid`), `neoforge:item_exists` (`item`),
`neoforge:tag_empty` (`tag`).

```json
{
  "neoforge:conditions": [ { "type": "neoforge:mod_loaded", "modid": "create" } ],
  "type": "minecraft:crafting_shapeless",
  "ingredients": [ { "item": "create:zinc_ingot" } ],
  "result": { "id": "remission:zinc_nugget", "count": 9 }
}
```

---

## Loot Tables

### `data/<namespace>/loot_table/custom_chest.json`
```json
{
  "type": "minecraft:chest",
  "pools": [
    {
      "rolls": { "type": "minecraft:uniform", "min": 3, "max": 8 },
      "entries": [
        {
          "type": "minecraft:item",
          "name": "minecraft:diamond",
          "weight": 5,
          "functions": [
            {
              "function": "minecraft:set_count",
              "count": { "type": "minecraft:uniform", "min": 1, "max": 3 }
            }
          ]
        },
        { "type": "minecraft:item", "name": "minecraft:gold_ingot", "weight": 20 },
        { "type": "minecraft:empty", "weight": 30 }
      ]
    }
  ]
}
```

### NeoForge global loot modifiers

Inject into existing loot tables without replacing them.

`data/neoforge/loot_modifiers/global_loot_modifiers.json` (always the
`neoforge` namespace):
```json
{ "replace": false, "entries": [ "remission:extra_dungeon_loot" ] }
```

`data/remission/loot_modifiers/extra_dungeon_loot.json`:
```json
{
  "type": "neoforge:add_table",
  "conditions": [
    { "condition": "neoforge:loot_table_id", "loot_table_id": "minecraft:chests/simple_dungeon" }
  ],
  "table": "remission:chests/extra_dungeon"
}
```

`neoforge:add_table` is the only built-in modifier type; others come from mods.

---

## Predicates

### `data/<namespace>/predicate/is_daytime.json`
```json
{
  "condition": "minecraft:time_check",
  "value": { "min": 0, "max": 12000 },
  "period": 24000
}
```

### `data/<namespace>/predicate/player_has_diamond.json`
Inventory checks use the `slots` entity sub-predicate (1.20.5+):
```json
{
  "condition": "minecraft:entity_properties",
  "entity": "this",
  "predicate": {
    "slots": {
      "container.*": { "items": "minecraft:diamond" }
    }
  }
}
```

```mcfunction
execute if predicate mypack:is_daytime run say It is daytime!
```

---

## Tags

### `data/minecraft/tags/block/climbable.json` (append to vanilla)
```json
{
  "replace": false,
  "values": [ "minecraft:ladder", "#minecraft:wool", { "id": "othermod:rope", "required": false } ]
}
```

### `data/<namespace>/tags/item/my_fuel.json`
```json
{ "replace": false, "values": [ "minecraft:coal", "minecraft:charcoal" ] }
```

`"replace": true` overrides the whole tag (avoid for vanilla tags). Use
`required: false` for entries from optional mods. NeoForge common tags use the
`c` namespace (e.g. `c:ingots/iron`).

---

## NeoForge data maps

Path: `data/<map namespace>/data_maps/<registry>/<map path>.json`, where
`<registry>` omits the `minecraft` namespace (e.g. `item`, `block`,
`entity_type`, `worldgen/biome`).

`data/neoforge/data_maps/item/furnace_fuels.json`:
```json
{
  "values": {
    "remission:peat": { "burn_time": 800 },
    "#c:rods/wooden": { "burn_time": 100 }
  }
}
```

Built-in maps: `neoforge:compostables` (`chance`), `neoforge:furnace_fuels`
(`burn_time`), `neoforge:monster_room_mobs` (`weight`), `neoforge:oxidizables`
(`next_oxidation_stage`), `neoforge:parrot_imitations` (`sound`),
`neoforge:raid_hero_gifts` (`loot_table`), `neoforge:vibration_frequencies`
(`frequency`), `neoforge:villager_types` (`villager_type`),
`neoforge:waxables` (`waxed`). Optional top-level `replace`, `remove`, and
`neoforge:conditions`. Furnace fuel is a data map in 1.21.1 (not an item
property or tag).
