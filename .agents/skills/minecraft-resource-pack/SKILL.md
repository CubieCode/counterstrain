---
name: minecraft-resource-pack
description: "Create and debug Minecraft 1.21.1 (NeoForge) resource-pack assets: pack.mcmeta (pack_format 34), textures, block/item models, blockstates, sounds, fonts, lang, atlases, animations, and core shaders — for standalone packs and the remission mod's assets. Use for client-side visual or audio assets, not gameplay code."
---

> Vendored from https://github.com/jahrome907/minecraft-codex-skills (commit
> `dd57c5a97741cdc0eb5bb3a8f876581a4f09eeb0`), MIT license — see
> `../LICENSE.minecraft-codex-skills`. Adapted for Minecraft 1.21.1 + NeoForge for
> Counterstrain/Remission. Project skills (release, neoforge-mod-dev, visual-checks,
> etc.) take precedence where they overlap.

# Minecraft Resource Pack Skill (1.21.1)

A resource pack overrides or adds visual/audio assets: textures, models, blockstates,
sounds, lang, fonts, shaders. In this project the same asset layout appears in two places:

- **Mod assets**: `mod/src/main/resources/assets/remission/`. NeoForge collects all mod
  resources into the "Mod resources" pack and generates `pack.mcmeta` itself — do not add one.
  Prefer NeoForge datagen (`BlockStateProvider`, `ItemModelProvider`, `LanguageProvider`,
  `SoundDefinitionsProvider`) for repetitive JSON; see the project's `neoforge-mod-dev` skill.
- **Standalone resource pack** (e.g. shipped in `pack/` via packwiz): needs `pack.mcmeta`.

### Routing
- Use for asset JSON/PNG/OGG work. Gameplay logic, registration, and render code belong in
  the NeoForge modding skills; datapacks (`data/`) belong in `minecraft-datapack`.

---

## `pack.mcmeta` (standalone packs only)

Minecraft 1.21 / 1.21.1 uses **resource pack format 34**.

```json
{
  "pack": {
    "pack_format": 34,
    "description": "Counterstrain resources"
  }
}
```

Optional 1.20.2+ fields, valid on 1.21.1:

```json
{
  "pack": {
    "pack_format": 34,
    "supported_formats": [34, 34],
    "description": "..."
  },
  "overlays": {
    "entries": [
      { "formats": { "min_inclusive": 34, "max_inclusive": 34 }, "directory": "overlay_1_21_1" }
    ]
  }
}
```

- `supported_formats` (int, `[min, max]`, or `{min_inclusive, max_inclusive}`) must include `pack_format`.
- `overlays.entries[].formats` + `directory` (lowercase dir name). `filter.block` also exists.
- `min_format` / `max_format` are **1.21.9+** fields; 1.21.1 ignores them. Do not use them.
- Later formats for reference only: 1.21.2–3 = 42, 1.21.4 = 46.

---

## Directory Layout (1.21.1)

```
assets/<namespace>/
├── blockstates/<block>.json
├── models/
│   ├── block/<name>.json
│   └── item/<item_id>.json      ← item model, found automatically from the item id
├── textures/
│   ├── block/  item/  entity/  particle/  painting/
│   ├── gui/sprites/<path>.png    ← GUI sprites (1.20.2+), may have .png.mcmeta (scaling)
│   └── models/armor/<material>_layer_1.png / _layer_2.png   ← armor (1.21.1)
├── particles/<particle>.json
├── atlases/<atlas>.json          ← minecraft ns: blocks, particles, gui, paintings, mob_effects, …
├── sounds.json + sounds/**.ogg
├── font/<name>.json + textures/font/*.png
├── lang/en_us.json
└── shaders/core/*.json|.vsh|.fsh (minecraft namespace only; see shaders below)
```

**Not in 1.21.1** (do not create): `assets/<ns>/items/` item model definitions (1.21.4+),
`assets/<ns>/equipment/` and `textures/entity/equipment/` (1.21.2+), `post_effect/`
(1.21.2+), object-form model texture entries (26.1).

Client-mod formats (OptiFine CIT, Iris shader packs) are not vanilla features — see
[conditional assets](references/conditional-assets.md).

---

## Block Models

Use namespaced references. A reference without a namespace means `minecraft:`.

```json
{
  "parent": "minecraft:block/cube_all",
  "textures": { "all": "remission:block/my_block" }
}
```

Other common parents: `minecraft:block/cube_column` (`end`, `side`), `minecraft:block/cube`
(`up`, `down`, `north`, `south`, `east`, `west`, `particle`), `minecraft:block/cross` (`cross`),
`minecraft:block/orientable` (`front`, `side`, `top`).

Custom geometry:
```json
{
  "ambientocclusion": true,
  "textures": { "0": "remission:block/panel", "particle": "remission:block/panel" },
  "elements": [
    {
      "from": [0, 0, 7],
      "to": [16, 16, 9],
      "faces": {
        "north": { "texture": "#0", "uv": [0, 0, 16, 16] },
        "south": { "texture": "#0", "uv": [0, 0, 16, 16] }
      }
    }
  ]
}
```

`from`/`to` are 0–16 (1/16 block); `uv` is `[x1, y1, x2, y2]` in 0–16 units. Texture entries are
plain strings on 1.21.1.

### NeoForge model extensions (1.21.1)
- `"render_type": "minecraft:cutout"` (or `cutout_mipped`, `translucent`, `solid`) — set this in
  the model instead of calling `ItemBlockRenderTypes` in code.
- `"loader": "neoforge:..."` custom loaders: `composite`, `elements`, `empty`, `fluid_container`,
  `item_layers`, `obj`, `separate_transforms`; root `"transform"`; per-element/face `"neoforge_data"` (`color`,
  `block_light`, `sky_light`, `ambient_occlusion`).
- Docs: https://docs.neoforged.net/docs/1.21.1/resources/client/models/

---

## Item Models (1.21.1)

The item `remission:wand` uses `assets/remission/models/item/wand.json` automatically:

```json
{
  "parent": "minecraft:item/generated",
  "textures": { "layer0": "remission:item/wand" }
}
```

Use `minecraft:item/handheld` for tools/weapons. A `BlockItem` usually just parents its block
model: `{ "parent": "remission:block/my_block" }`.

Variants use the model `overrides` array with float `predicate` thresholds; the **last**
matching entry wins. Built-in predicates include `custom_model_data` (integer component
`custom_model_data`), `damage`, `pull`, `pulling`, etc. Custom predicates are registered in code
with `ItemProperties.register` (client setup). See
[conditional assets](references/conditional-assets.md).

---

## Blockstates

```json
{ "variants": { "": { "model": "remission:block/my_block" } } }
```

```json
{
  "variants": {
    "facing=north": { "model": "remission:block/my_block" },
    "facing=south": { "model": "remission:block/my_block", "y": 180 },
    "facing=east":  { "model": "remission:block/my_block", "y": 90 },
    "facing=west":  { "model": "remission:block/my_block", "y": 270 }
  }
}
```

Random variants: a list of `{ "model", "weight" }` objects. Multipart:
`{ "multipart": [ { "when": { "type": "bottom" }, "apply": { "model": "..." } } ] }`.
Property names/values must match the block's registered `BlockState` properties exactly.

---

## Textures

- PNG; 16×16 standard for blocks/items, larger power-of-two sizes work.
- Animated: height = N × width plus `<texture>.png.mcmeta` next to it:
  ```json
  { "animation": { "frametime": 2, "interpolate": false } }
  ```
  `frametime` is in ticks (default 1); `frames` optional.
- Entity textures live under `textures/entity/` and are bound by the renderer in code
  (`ResourceLocation.fromNamespaceAndPath("remission", "textures/entity/foo.png")` — note the
  full path incl. `.png` in code, but no `textures/`/`.png` in model JSON).
- On 1.21.1 the `blocks` atlas sources `textures/block/` and `textures/item/` (all namespaces),
  so model textures belong there. Model textures elsewhere need a source in
  `assets/minecraft/atlases/blocks.json` (e.g. `{"sources":[{"type":"directory","source":"remission_extra","prefix":"remission_extra/"}]}`).

---

## Sounds

`assets/remission/sounds.json` defines events in the `remission` namespace; files are Vorbis
`.ogg` under `assets/remission/sounds/`.

```json
{
  "zap": {
    "subtitle": "subtitles.remission.zap",
    "sounds": [ { "name": "remission:zap", "volume": 1.0 } ]
  }
}
```

Entries are files by default; `"type": "event"` references another event. The mod must also
register the `SoundEvent` (`remission:zap`) via `DeferredRegister` to play it from code.
The `SoundSource` is chosen by the caller (`/playsound remission:zap master @s`, or code); the
`ui` source does **not** exist on 1.21.1 (added 1.21.6). `replace: true` replaces lower-priority
entries for that event.

---

## Lang

`assets/remission/lang/en_us.json` (all namespaces' lang files merge; use the mod namespace):

```json
{
  "block.remission.my_block": "My Block",
  "item.remission.wand": "Wand",
  "entity.remission.my_mob": "My Mob",
  "subtitles.remission.zap": "Zap",
  "death.attack.remission.laser": "%1$s was zapped by %2$s"
}
```

Always provide `en_us.json`; other locales use codes like `de_de.json`.

---

## Fonts

See [conditional assets](references/conditional-assets.md) (bitmap providers, private-use icons).

---

## Core shaders (1.21.1)

Vanilla core shaders live in `assets/minecraft/shaders/core/` as a program JSON (`vertex`,
`fragment`, `samplers`, `uniforms`) plus `.vsh`/`.fsh`, with includes in
`shaders/include/`. A resource pack can override them, but this is unsupported by Mojang
and breaks across versions. 1.21.2 changed program paths, merged terrain programs, renamed
`ChunkOffset` → `ModelOffset`, and replaced the post-effect format (`post_effect/`) — so
**do not copy shader examples written for 1.21.2+**. Base edits on the extracted 1.21.1 client
jar. Mods should register their own shaders via NeoForge's `RegisterShadersEvent` rather than
overriding vanilla ones. Iris/OptiFine shader packs are a separate format.

---

## Installing / testing

- Standalone: `~/.minecraft/resourcepacks/<pack>/` or `.zip`; or include it in the packwiz pack.
- Mod assets: run the client (`./gradlew runClient` in `mod/`); `F3+T` reloads resources.
- Server-forced pack (`server.properties`): `resource-pack`, `resource-pack-sha1`,
  `resource-pack-id`, `require-resource-pack`, `resource-pack-prompt`.

## Common Issues

| Problem | Cause | Fix |
|---------|-------|-----|
| Purple/black texture | Wrong texture path, or `.png` in JSON ref | `remission:item/foo` → `assets/remission/textures/item/foo.png` |
| Item shows as missing model | No `models/item/<id>.json` (an `items/` file does nothing on 1.21.1) | Add the model under `models/item/` |
| Cutout block renders black/opaque | No render type | Add `"render_type": "minecraft:cutout"` to the block model |
| Blockstate not applying | Property name/value mismatch | Check F3 block state panel |
| Animation not working | `.mcmeta` misplaced | `<texture>.png.mcmeta` beside the PNG |
| Sound silent | Event missing from `sounds.json` or not registered | Match namespace; register `SoundEvent` |
| Pack "made for an older/newer version" | Wrong `pack_format` | Use `34` for 1.21.1 |

Check `logs/latest.log` for `Unable to load model` / `Missing textures` lines after a reload.

## Validator Script

```bash
# Standalone pack (pack.mcmeta required, default target pack_format 34):
.agents/skills/minecraft-resource-pack/scripts/validate-resource-pack.sh --root path/to/pack
# Mod assets (pack.mcmeta optional; NeoForge generates it):
.agents/skills/minecraft-resource-pack/scripts/validate-resource-pack.sh \
  --root mod/src/main/resources --mod-resources
# --strict turns warnings into failures; --target-format <n> changes the target.
```

Checks: JSON validity; `pack.mcmeta` shape and that `pack_format` matches 34; model/blockstate/
font/sounds references resolve (unnamespaced refs = `minecraft`; missing `minecraft:` assets are
assumed vanilla, other namespaces warn); warns on 1.21.4+ `items/` files; fails on 26.1 object
texture entries; orphan `.png.mcmeta`. Needs `jq`, or `node` (falls back to `scripts/jq-shim.mjs`).
It cannot check NeoForge custom loaders, code-registered sounds, or runtime rendering — verify in
the client (see the project's visual-checks skill).

## References

- Pack format history: https://minecraft.wiki/w/Pack_format
- pack.mcmeta: https://minecraft.wiki/w/Pack.mcmeta
- Shaders (version history): https://minecraft.wiki/w/Shader
- NeoForge 1.21.1 resources: https://docs.neoforged.net/docs/1.21.1/resources/
- NeoForge 1.21.1 models: https://docs.neoforged.net/docs/1.21.1/resources/client/models/
- NeoForge 1.21.1 sounds: https://docs.neoforged.net/docs/1.21.1/resources/client/sounds/
