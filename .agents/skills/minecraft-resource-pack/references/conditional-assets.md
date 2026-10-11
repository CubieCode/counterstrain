# Conditional Resource-Pack Assets (1.21.1)

Read only when the task involves item model variants, sound aliases, fonts, OptiFine, or
shader packs. Everything here targets Minecraft 1.21.1 (pack format 34) with NeoForge.

## Item model variants (`overrides`)

1.21.1 has no `assets/<ns>/items/` item definitions (those, the `minecraft:select` /
string `custom_model_data` system, are 1.21.4+). Use the item model's `overrides` array.
Predicates are float thresholds; an entry applies when every value is at or above its
threshold, and the **last** matching entry wins, so list ascending.

```json
{
  "parent": "minecraft:item/handheld",
  "textures": { "layer0": "minecraft:item/stick" },
  "overrides": [
    { "predicate": { "custom_model_data": 1001 }, "model": "remission:item/magic_wand" }
  ]
}
```

On 1.21.1 `custom_model_data` is an integer item component:

```mcfunction
/give @s minecraft:stick[minecraft:custom_model_data=1001]
```

For the mod's own items prefer a dedicated item (own `models/item/<id>.json`) or a custom
predicate registered with `ItemProperties.register(item, ResourceLocation, ClampedItemPropertyFunction)`
during `FMLClientSetupEvent` (`event.enqueueWork`). NeoForge allows predicate values outside 0–1.

## Sounds

The event namespace is the namespace containing `sounds.json`; the `name` namespace locates
the `.ogg`. Unnamespaced names mean `minecraft`.

```json
{
  "ui.click": {
    "sounds": [ { "name": "remission:ui/click", "volume": 1.0 } ]
  },
  "ui.click_alias": {
    "sounds": [ { "name": "remission:ui.click", "type": "event" } ]
  }
}
```

`type` defaults to `file`; `event` references another event. Optional per-sound fields:
`volume`, `pitch`, `weight`, `stream`, `attenuation_distance`, `preload`. `sounds.json` does
not choose the `SoundSource`; the caller does. Valid sources on 1.21.1: `master`, `music`,
`record`, `weather`, `block`, `hostile`, `neutral`, `player`, `ambient`, `voice` (no `ui`).

## Fonts

`assets/<ns>/font/<name>.json`; a bitmap provider's `file` is a texture location **including**
`.png`, relative to `textures/`.

```json
{
  "providers": [
    {
      "type": "bitmap",
      "file": "remission:font/icons.png",
      "ascent": 8,
      "height": 9,
      "chars": [""]
    }
  ]
}
```

Each `chars` string is one row of the grid; all rows must have equal length. Use private-use
characters (U+E000–U+F8FF). Reference a non-default font from a text component with
`"font": "remission:icons"`.

## Client-mod and shader boundaries

- OptiFine CIT (`assets/minecraft/optifine/`) is OptiFine-only and OptiFine does not run on
  NeoForge 1.21.1. Do not use it.
- Iris shader packs are separate ZIPs with a top-level `shaders/` dir, loaded through Iris
  (Iris ships NeoForge builds for 1.21.1). They are not part of a resource pack.
- Vanilla core shader overrides (`assets/minecraft/shaders/core/`) work on 1.21.1 but are
  unsupported, and Sodium/Iris may bypass them (e.g. terrain); test with the exact modpack.

## Sources

- https://docs.neoforged.net/docs/1.21.1/resources/client/models/
- https://docs.neoforged.net/docs/1.21.1/resources/client/sounds/
- https://minecraft.wiki/w/Shader
