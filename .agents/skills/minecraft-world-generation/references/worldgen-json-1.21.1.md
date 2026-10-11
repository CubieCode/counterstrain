# Minecraft 1.21.1 Worldgen JSON (+ NeoForge 21.1)

Shapes below are for Minecraft 1.21.1 (data pack format 48). For anything not
shown, copy from the vanilla 1.21.1 jar (`data/minecraft/worldgen/...`) or from
NeoForge datagen output, not from newer-version docs.

## Biome and feature chain

Biome `features` has 11 arrays, one per `GenerationStep.Decoration` index
(`raw_generation`, `lakes`, `local_modifications`, `underground_structures`,
`surface_structures`, `strongholds`, `underground_ores`,
`underground_decoration`, `fluid_springs`, `vegetal_decoration`,
`top_layer_modification`). Ores go at index 6. Entries are placed-feature ids.

`data/<namespace>/worldgen/biome/my_biome.json`:

```json
{
  "has_precipitation": true,
  "temperature": 0.7,
  "downfall": 0.8,
  "effects": {
    "sky_color": 7907327,
    "fog_color": 12638463,
    "water_color": 4159204,
    "water_fog_color": 329011
  },
  "spawners": {
    "creature": [
      { "type": "minecraft:sheep", "weight": 12, "minCount": 4, "maxCount": 4 }
    ]
  },
  "spawn_costs": {},
  "carvers": {
    "air": ["minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon"]
  },
  "features": [
    [], [], [], [], [], [],
    ["<namespace>:my_ore_placed"],
    [], [], [], []
  ]
}
```

1.21.1 `carvers` is a map keyed by carving step (`air`, `liquid`). The flat
list form is 1.21.2+ (not in 1.21.1). `effects.music_volume` (1.21.4),
`effects.dry_foliage_color` (1.21.5) and the 1.21.11 environment `attributes`
are not in 1.21.1. `spawn_costs` entries look like
`"minecraft:zombie": { "energy_budget": 0.12, "charge": 0.7 }`.

`data/<namespace>/worldgen/configured_feature/my_ore.json`:

```json
{
  "type": "minecraft:ore",
  "config": {
    "targets": [
      {
        "target": { "predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables" },
        "state": { "Name": "minecraft:emerald_ore" }
      },
      {
        "target": { "predicate_type": "minecraft:tag_match", "tag": "minecraft:deepslate_ore_replaceables" },
        "state": { "Name": "minecraft:deepslate_emerald_ore" }
      }
    ],
    "size": 4,
    "discard_chance_on_air_exposure": 0.0
  }
}
```

`data/<namespace>/worldgen/placed_feature/my_ore_placed.json`:

```json
{
  "feature": "<namespace>:my_ore",
  "placement": [
    { "type": "minecraft:count", "count": 8 },
    { "type": "minecraft:in_square" },
    {
      "type": "minecraft:height_range",
      "height": {
        "type": "minecraft:trapezoid",
        "min_inclusive": { "above_bottom": 0 },
        "max_inclusive": { "absolute": 64 }
      }
    },
    { "type": "minecraft:biome" }
  ]
}
```

## Vegetation patch (`minecraft:random_patch`)

`random_patch` exists in 1.21.1 (it is removed only in 26.1). Configured
feature with an inline placed feature, as vanilla `patch_berry_bush` does:

```json
{
  "type": "minecraft:random_patch",
  "config": {
    "tries": 96,
    "xz_spread": 7,
    "y_spread": 3,
    "feature": {
      "feature": {
        "type": "minecraft:simple_block",
        "config": {
          "to_place": {
            "type": "minecraft:simple_state_provider",
            "state": { "Name": "minecraft:sweet_berry_bush", "Properties": { "age": "3" } }
          }
        }
      },
      "placement": [
        {
          "type": "minecraft:block_predicate_filter",
          "predicate": {
            "type": "minecraft:all_of",
            "predicates": [
              { "type": "minecraft:matching_blocks", "blocks": "minecraft:air" },
              { "type": "minecraft:matching_blocks", "blocks": "minecraft:grass_block", "offset": [0, -1, 0] }
            ]
          }
        }
      ]
    }
  }
}
```

Place it with a placed feature such as
`[{"type":"minecraft:rarity_filter","chance":32},{"type":"minecraft:in_square"},{"type":"minecraft:heightmap","heightmap":"WORLD_SURFACE_WG"},{"type":"minecraft:biome"}]`.

## Dimension and dimension type

`data/<namespace>/dimension_type/my_type.json`:

```json
{
  "ultrawarm": false,
  "natural": true,
  "coordinate_scale": 1.0,
  "has_skylight": true,
  "has_ceiling": false,
  "ambient_light": 0.0,
  "monster_spawn_light_level": { "type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 7 },
  "monster_spawn_block_light_limit": 0,
  "piglin_safe": false,
  "bed_works": true,
  "respawn_anchor_works": false,
  "has_raids": true,
  "logical_height": 384,
  "height": 384,
  "min_y": -64,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "effects": "minecraft:overworld"
}
```

Optional: `fixed_time`. `min_y`/`height` must be multiples of 16.

`data/<namespace>/dimension/my_dimension.json`:

```json
{
  "type": "<namespace>:my_type",
  "generator": {
    "type": "minecraft:noise",
    "biome_source": { "type": "minecraft:fixed", "biome": "<namespace>:my_biome" },
    "settings": "minecraft:overworld"
  }
}
```

Use `minecraft:multi_noise` only with a complete 1.21.1 parameter list (or a
`preset`, e.g. `"minecraft:overworld"`). Dimensions are loaded at world
creation/load; `/reload` does not add them.

## NeoForge biome modifiers

`data/<namespace>/neoforge/biome_modifier/<name>.json`. `biomes`, `features`,
`carvers`, and `entity_types` accept an id, a list, or a `#tag`.

```json
{
  "type": "neoforge:add_features",
  "biomes": "#minecraft:is_overworld",
  "features": "<namespace>:my_ore_placed",
  "step": "underground_ores"
}
```

| Type | Fields |
|------|--------|
| `neoforge:none` | — |
| `neoforge:add_features` | `biomes`, `features`, `step` (single) |
| `neoforge:remove_features` | `biomes`, `features`, `steps` (optional; one or list; default all) |
| `neoforge:add_spawns` | `biomes`, `spawners` (object or list of `{type, weight, minCount, maxCount}`) |
| `neoforge:remove_spawns` | `biomes`, `entity_types` |
| `neoforge:add_spawn_costs` | `biomes`, `entity_types`, `spawn_cost` (`{energy_budget, charge}`) |
| `neoforge:remove_spawn_costs` | `biomes`, `entity_types` |
| `neoforge:add_carvers` | `biomes`, `carvers`, `step` (`air` or `liquid`) |
| `neoforge:remove_carvers` | `biomes`, `carvers`, `steps` |

```json
{
  "type": "neoforge:add_spawns",
  "biomes": "#c:is_desert",
  "spawners": { "type": "minecraft:husk", "weight": 20, "minCount": 1, "maxCount": 3 }
}
```

## NeoForge structure modifiers

`data/<namespace>/neoforge/structure_modifier/<name>.json` adjust structure
spawn overrides: `neoforge:none`, `neoforge:add_spawns` (`structures`,
`spawners`), `neoforge:remove_spawns` (`structures`, `entity_types`),
`neoforge:clear_spawns` (`structures`, optional `categories`). Confirm field
names against NeoForge 21.1 `StructureModifiers` before relying on them.

## Jigsaw structures

`data/<namespace>/worldgen/structure/my_structure.json`:

```json
{
  "type": "minecraft:jigsaw",
  "biomes": "#<namespace>:has_structure/my_structure",
  "step": "surface_structures",
  "terrain_adaptation": "beard_thin",
  "spawn_overrides": {},
  "start_pool": "<namespace>:my_structure/start",
  "size": 6,
  "start_height": { "absolute": 0 },
  "project_start_to_heightmap": "WORLD_SURFACE_WG",
  "max_distance_from_center": 80,
  "use_expansion_hack": false
}
```

`start_height` is required. `size` is 0–20; `max_distance_from_center` is an
int (1–128, or 1–116 with terrain adaptation). Optional in 1.21.1:
`start_jigsaw_name`, `pool_aliases`, `dimension_padding`, `liquid_settings`.
The horizontal/vertical object form of `max_distance_from_center` is 1.21.9+
(not in 1.21.1).

`data/<namespace>/worldgen/template_pool/my_structure/start.json`:

```json
{
  "fallback": "minecraft:empty",
  "elements": [
    {
      "weight": 1,
      "element": {
        "element_type": "minecraft:single_pool_element",
        "location": "<namespace>:my_structure/start",
        "projection": "rigid",
        "processors": "minecraft:empty"
      }
    }
  ]
}
```

`data/<namespace>/worldgen/structure_set/my_structure.json`:

```json
{
  "structures": [ { "structure": "<namespace>:my_structure", "weight": 1 } ],
  "placement": {
    "type": "minecraft:random_spread",
    "spacing": 32,
    "separation": 8,
    "salt": 12345678
  }
}
```

The pool `location` needs `data/<namespace>/structure/my_structure/start.nbt`
(singular `structure/` since 1.21). Use a processor list other than
`minecraft:empty` only when its JSON exists in
`worldgen/processor_list/`.
