---
name: minecraft-world-generation
description: "Create and debug Minecraft 1.21.1 world generation as datapack JSON or NeoForge 21.1 mod data/datagen, including biomes, dimensions, configured/placed features, structures, and NeoForge biome/structure modifiers. Use for worldgen data or registration, not general gameplay systems."
---

> Vendored from https://github.com/jahrome907/minecraft-codex-skills (commit
> `dd57c5a97741cdc0eb5bb3a8f876581a4f09eeb0`), MIT license — see
> `../LICENSE.minecraft-codex-skills`. Adapted for Minecraft 1.21.1 + NeoForge for
> Counterstrain/Remission. Project skills (remission-data-formats,
> kubejs-scripting, packwiz-pack, etc.) take precedence where they overlap.

# Minecraft World Generation (1.21.1)

Use this skill for biome, dimension, feature, or structure data and their
registration. Use `minecraft-datapack` for non-worldgen data and
`minecraft-modding` for non-worldgen gameplay code.

Target: Minecraft **1.21.1**, NeoForge 21.1.x, Java 21. Worldgen JSON changed
repeatedly after 1.21.1 (1.21.2 carvers list, 1.21.4/1.21.5 biome effect
fields, 1.21.11 environment attributes, 26.1 removal of `random_patch`). Never
copy newer-version shapes; start from vanilla 1.21.1 data or NeoForge datagen
output.

## Choose the delivery path

| Approach | Best when |
|----------|-----------|
| Mod datagen (`remission`, output in `mod/src/generated/resources`) | New biomes, features, structures, dimensions owned by the mod |
| Biome / structure modifier JSON | Adding or removing features/spawns in existing (vanilla or other mods') biomes |
| Pack datapack / `pack/kubejs/data/` | Pack-level overrides without mod code |

Worldgen registries are datapack registries: files load at world load and the
registry path determines the data path. For mod datagen use
`DatapackBuiltinEntriesProvider` with a `RegistrySetBuilder`
(https://docs.neoforged.net/docs/1.21.1/concepts/registries/#data-driven-registries).

---

## Data layout and reference graph

```
data/<namespace>/
├── worldgen/
│   ├── biome/  configured_feature/  placed_feature/  noise_settings/
│   ├── structure/  structure_set/  template_pool/  processor_list/
│   └── configured_carver/
├── dimension/
├── dimension_type/
├── structure/                 *.nbt templates (singular since 1.21)
├── tags/worldgen/biome/       e.g. has_structure/my_structure.json
└── neoforge/
    ├── biome_modifier/
    └── structure_modifier/
```

Build and review the graph from its leaves upward:

1. Configured feature → placed feature.
2. Placed features referenced from a biome (`features[step]`) or a biome
   modifier at the intended decoration step.
3. Structure → structure set; a jigsaw structure also needs a template pool,
   processor list, and `.nbt` structure template.
4. Dimension type and noise settings before the dimension that references them.

Use fully qualified identifiers. External `minecraft:` or dependency references
are fine when that dependency supplies the entry; don't copy them locally just
to satisfy static checking.

Read [references/worldgen-json-1.21.1.md](references/worldgen-json-1.21.1.md)
for 1.21.1 JSON examples: biome, ore feature, `random_patch`, dimension and
dimension type, biome/structure modifier types, jigsaw structure, template
pool, and structure set.

---

## NeoForge biome modifiers (summary)

Biome modifiers load from `data/<modid>/neoforge/biome_modifier/<path>.json`
(also from pack datapacks). Built-ins: `neoforge:add_features` (`step`
singular), `neoforge:remove_features` (`steps`), `neoforge:add_spawns`,
`neoforge:remove_spawns`, `neoforge:add_spawn_costs`,
`neoforge:remove_spawn_costs`, `neoforge:add_carvers`,
`neoforge:remove_carvers`, `neoforge:none`. Docs:
https://docs.neoforged.net/docs/1.21.1/worldgen/biomemodifier/

- Don't both list a vanilla placed feature in biome JSON and inject it with a
  modifier: feature-order cycles crash world loading. Use a copy in your own
  namespace when needed.
- For biomes from optional mods, target a biome tag whose entries use
  `{"id": "...", "required": false}`.
- Biome modifiers accept `neoforge:conditions`.
- Prefer NeoForge `c:` biome tags (e.g. `#c:is_desert`) over listing biome ids.

---

## Development Workflow

1. Edit worldgen JSON (or datagen providers, then run `runData`).
2. Validate references:
   ```bash
   ./scripts/validate-worldgen-json.sh --root /path/to/datapack-or-resources   # e.g. mod/src/generated/resources
   ./scripts/validate-worldgen-json.sh --root ... --strict                     # warnings fail
   ```
   It checks JSON validity under `worldgen/`, `dimension/`, `dimension_type/`,
   `tags/worldgen/`, `neoforge/biome_modifier/`, and local references
   (`dimension → dimension_type/noise_settings`, `placed_feature →
   configured_feature`, `structure_set → structure`, jigsaw `start_pool →
   template_pool`, pool element → `.nbt` template / `processor_list`, biome and
   biome modifier → placed feature). It does not run Mojang codecs, so field
   shapes must still match 1.21.1. Its jq fallback shim (`jq-shim.mjs`) does
   not cover every filter; install `jq`.
3. In-game:
   ```mcfunction
   /locate structure <namespace>:my_structure
   /locate biome <namespace>:my_biome
   /place feature <namespace>:my_ore
   /place structure <namespace>:my_structure
   /execute in <namespace>:my_dimension run tp @s 0 100 0
   ```
   `place feature` takes a configured-feature id, not the placed feature.
4. Check `logs/latest.log` for registry/codec errors.
5. `/reload` does not regenerate existing chunks or add dimensions; test in a
   fresh world or newly generated chunks.

---

## References

- Custom world generation: https://minecraft.wiki/w/Custom_world_generation (check History sections for 1.21.1)
- Biome definition: https://minecraft.wiki/w/Biome_definition_(Java_Edition)
- Structure definition: https://minecraft.wiki/w/Structure_definition
- NeoForge 1.21.1 biome modifiers: https://docs.neoforged.net/docs/1.21.1/worldgen/biomemodifier/
- misode worldgen generators (set version to 1.21.1): https://misode.github.io/worldgen/
