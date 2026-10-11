---
name: kubejs-scripting
description: Write or review Counterstrain KubeJS scripts (KubeJS 2101.x on NeoForge 1.21.1) for recipes, tags, gating, tooltips and Remission recipe types, using ProbeJS declarations as the API reference.
---

# KubeJS scripting

The pack uses KubeJS 2101.7 (Rhino 2101.2) on 1.21.1. Its API differs from KubeJS 6 (1.20.1) and older: `onEvent(...)`, NBT-based item matching, `ItemEvents.tooltip`, Architectury-era classes and many wiki/forum snippets are wrong here. The kubejs.com wiki is partly stale too. Source of truth, in order: ProbeJS declarations for this pack, the KubeJS jar (`~/.gradle/caches/modules-2/files-2.1/maven.modrinth/kubejs/`), then current docs. When in doubt, run it and read `logs/kubejs/*.log`.

ProbeJS: in a dev client (`tools/run-client-headless.sh`, or a playtest instance) with ProbeJS enabled, enter a world and run `/probejs dump`; it writes TypeScript declarations under the game directory's `.probe/` / `kubejs/probe/` (gitignored). Open the game directory's `kubejs/` in VS Code for completion. Regenerate after adding mods. Never commit generated declarations.

Locations, in `pack/kubejs/`:
- `startup_scripts/`: `StartupEvents.registry('item'|'block'|'fluid', e => e.create(...))`, `StartupEvents.modifyCreativeTab`. Runs once at launch, before registries freeze, and needs a restart.
- `server_scripts/`: `ServerEvents.recipes`, `ServerEvents.tags('item'|'block'|'entity_type', ...)`, `ServerEvents.recipeSchemaRegistry` and gating logic. Reload with `/reload`.
- `client_scripts/`: `ItemEvents.modifyTooltips`, `RecipeViewerEvents.*` (one API for both viewers; server-driven in 2101; EMI support is partial: adding/removing entries and hiding recipes, so check results in EMI, which is what players see), `ClientEvents.lang`. Applied on resource reload (F3+T) or restart.
- `data/` and `assets/`: datapack and resource pack loaded automatically (the pack datapack, e.g. `kubejs/data/remission/tags/entity_type/immune.json`).

Name files by topic (`server_scripts/recipes/mekanism.js`, `server_scripts/gating/chapter1.js`); scripts load alphabetically. Start each file with a header comment saying what it changes and why.

Recipes: `event.shaped(Item.of('x', n), [rows], {A: 'id'})`, `event.shapeless`, `event.smelting`, `event.remove({output: 'id'})` / `{id: 'mod:recipe'}` / `{mod: 'x', type: 'y'}`, `event.replaceInput(filter, from, to)`; give added recipes stable ids with `.id('counterstrain:...')`. Mod recipe types: use the typed methods ProbeJS shows (`event.recipes.mekanism...`, `event.recipes.create...`); fall back to `event.custom({type: ..., ...})` with the mod's 1.21.1 JSON.

Remission recipe types (`remission:analyzing`, `sequencing`, `synthesizing`) get KubeJS methods from JSON schemas the mod ships in `data/remission/kubejs/recipe_schema/` (remission-data-formats). Once P7 lands: `event.recipes.remission.analyzing(...)`, plus `event.remove({type: 'remission:analyzing'})`. If ProbeJS does not show them, the schema is missing or invalid; fix the mod, do not hand-write `event.custom` workarounds in the pack.

Remission API events (planned, P8.3): Remission's public API events (host corrupted, nidus destroyed, Strain spread attempt, chunk entered remission and the rest listed in remission-data-formats) are exposed to server scripts through an optional binding. Use them for pack-side integrations (for example a Serene Seasons spread multiplier) instead of polling or patching; event group and method names are fixed in P8.3 and recorded here.

Integrations: pack-side tie-ins from DESIGN's Pack integrations table live in `server_scripts/integrations/<modid>.js` (one file per mod, header naming the gate it creates), loaded only when the mod is present (`Platform.isLoaded('<modid>')`).

Names: item, block and fluid IDs, tooltips and lang use the DESIGN glossary (`remission:miasma`, `remission:nidus_core`, "festering"). Anything a script adds that players see (startup-registered items, tooltips, lang, textures under `kubejs/assets/`) is player-facing content: add ledger rows to docs/CONTENT-PROVENANCE.md and mark placeholder textures (content-provenance skill).

Gating: lock items behind Remission data items and samples by replacing inputs or removing and re-adding recipes; record each gate's quest in the script header. Balance numbers live in the pack, never in the mod. Test with a playtest instance: `/reload`, then check JEI and `logs/kubejs/server.log` for errors.
