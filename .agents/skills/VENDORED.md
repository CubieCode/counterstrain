# Vendored skills

Project skills (`neoforge-mod-dev`, `remission-data-formats`, `packwiz-pack`, `kubejs-scripting`, `ftb-quests-authoring`, `roadmap-workflow`, `release`, `playtesting`, `headless-client`, `visual-checks`) take precedence over these wherever they overlap.

| | |
| --- | --- |
| Upstream | https://github.com/jahrome907/minecraft-codex-skills |
| Commit | `dd57c5a97741cdc0eb5bb3a8f876581a4f09eeb0` (2026-09-12) |
| License | MIT, copied to [LICENSE.minecraft-codex-skills](LICENSE.minecraft-codex-skills) |
| Copied from | `.agents/skills/<name>/` only (not the repo's `AGENTS.md`, `.claude/`, `.codex/` or `plugins/` variants) |
| Vendored on | 2026-10-11 |
| Target | Minecraft 1.21.1, NeoForge 21.1.259, ModDevGradle 2.0.148, Java 21 (upstream targets 26.x and several loaders) |

Each `SKILL.md` carries a header with this source, the commit, the license and the adaptation note. Every file was read in full and audited against the NeoForge 1.21.1 sources and docs, the 1.21.1 MDK and the Minecraft Wiki's version history.

## Updating

1. Clone upstream and diff `.agents/skills/<name>/` between the commit above and the new one.
2. Apply only changes that hold for 1.21.1 NeoForge, keeping the local modifications listed below.
3. Re-run each skill's scripts against this repo:
   - `minecraft-modding/scripts/check-build.sh`
   - `minecraft-testing/scripts/validate-test-layout.sh --root mod`
   - `minecraft-datapack/scripts/validate-datapack.sh --root mod/src/generated/resources --no-mcmeta`
   - `minecraft-resource-pack/scripts/validate-resource-pack.sh --root mod/src/main/resources --mod-resources`
4. Update the commit here and in each header.

## Local modifications

### minecraft-modding

- **Deleted:** `references/forge-1.20.1-api.md`, `references/fabric-api.md`, and every link to them.
- **Rewritten:** `SKILL.md`, `scripts/check-build.sh` (NeoForge/MDG only; defaults to `mod/`).
- **Edited:** `references/neoforge-api.md`, `references/common-patterns.md`.
- Removed the Fabric, Forge 1.20.1, Architectury and Paper layouts. Added a list of post-1.21.1 APIs: `ToolMaterial`/equipment assets, `assets/<ns>/items/` item models, the split `GatherDataEvent`, data-driven gametests, `ValueInput/ValueOutput`, `Identifier`.
- Data folders singular; ingredients use the 1.21.1 object form (`{"item": ...}`); tool tags moved to the `minecraft` namespace.
- APIs corrected to 1.21.1:
  - block entity `saveAdditional/loadAdditional(CompoundTag, HolderLookup.Provider)`
  - `Capabilities.ItemHandler.BLOCK`
  - one payload registration per type (`playBidirectional` for both directions); handlers run on the main thread
  - entity types via `DeferredRegister.create(Registries.ENTITY_TYPE, ...)`
  - `SimpleTier` and registered `ArmorMaterial`
  - `DirectionalBlock#codec()`
- Added data component and Data Attachment sections. Attachment `sync(...)` is confirmed present in NeoForge 21.1.259.
- `@EventBusSubscriber` examples omit the `bus` attribute, which NeoForge 21.1.259 deprecates for removal (changed after the audit, to match the code).

### minecraft-testing

- **Rewritten:** `SKILL.md`, `references/test-layouts.md`.
- **Trimmed:** `scripts/validate-test-layout.sh`. It now also accepts `src/main/templates/META-INF/neoforge.mods.toml`, the MDK layout this repo uses.
- Removed MockBukkit, Fabric GameTest, Fabric Loader JUnit and 26.x `test_instance` content.
- `@GameTestHolder`/`@PrefixGameTestTemplate` are documented as the 1.21.1 method; upstream had labelled them "1.21.3 only". Covered:
  - template namespace and prefix rules, from `GameTestHooks`
  - `RegisterGameTestsEvent`
  - `neoforge.enabledGameTestNamespaces`
  - the crash when no tests are registered
- Added MDG `unitTest` setup. Not verified: the `testframework` artifact for 21.1.259, and the exact `GameTestHelper` example signatures.

### minecraft-datapack

- **Edited:** `SKILL.md`, `references/data-examples.md`, `scripts/validate-datapack.sh`.
- Pack metadata is `pack_format: 48` only. `supported_formats`, `filter` and `overlays` are kept; `min_format`/`max_format` and the 1.21.2+ tables are removed.
- Recipes use 1.21.1 ingredient objects and `{"id","count"}` results. Recipes are disabled with `neoforge:conditions`.
- Inventory predicates use `slots`. Macro call syntax fixed.
- NeoForge data documented:
  - conditions
  - global loot modifiers: `data/neoforge/loot_modifiers/global_loot_modifiers.json` plus `data/<ns>/loot_modifiers/`
  - data maps
  - `required: false` tag entries and `c:` tags
- The validator requires an integer `pack_format`, flags pre-1.21 plural folders, and has a `--no-mcmeta` option.

### minecraft-world-generation

- **Renamed and rewritten:** `references/legacy-1.21-worldgen-json.md` → `references/worldgen-json-1.21.1.md`, now the main reference.
- **Edited:** `SKILL.md`, `scripts/validate-worldgen-json.sh`.
- Removed 26.x and Fabric content.
- 1.21.1 shapes:
  - biome `carvers` is a map keyed by step
  - jigsaw structures need `start_height`/`project_start_to_heightmap`
  - `random_patch` example
  - folder `worldgen/configured_carver`
- Marked as later than 1.21.1: `music_volume`, `dry_foliage_color` and environment attributes.
- Added the NeoForge biome modifier table and structure modifiers. Structure modifier field names are flagged "confirm against NeoForge 21.1 source".
- Some of the validator's filters need real `jq` (installed on foundry); the Node shim does not cover them.

### minecraft-resource-pack

- **Rewritten:** `SKILL.md`, `references/conditional-assets.md`.
- **Edited:** `scripts/validate-resource-pack.sh`, `scripts/jq-shim.mjs`.
- `pack_format: 34`. Mods don't ship `pack.mcmeta`; NeoForge generates it.
- Item models are `models/item/<id>.json` with legacy `overrides` and integer `custom_model_data`. These are removed or marked not in 1.21.1:
  - `assets/<ns>/items/` definitions (1.21.4+)
  - equipment assets (1.21.2+)
  - `post_effect/` (1.21.2+)
  - object texture entries (26.1)
- Core shaders can be overridden, with a 1.21.1-vs-1.21.2 warning; mods use `RegisterShadersEvent`. The blocks atlas covers `textures/item/` in 1.21.1. Valid sound sources listed. NeoForge model extensions added (`render_type`, loaders). OptiFine guidance removed.
- Validator:
  - new options `--target-format` (default 34) and `--mod-resources`
  - unnamespaced references now resolve to `minecraft:` (an upstream bug)
  - warns on `items/` files

### minecraft-ci-release

- **Rewritten:** `SKILL.md`, `references/publishing-gradle.md`.
- **Unchanged:** `scripts/validate-workflow-snippets.sh` and `scripts/vendor/` (js-yaml with its MIT `LICENSE.js-yaml`).
- Removed the Fabric/NeoForge matrix, Paper CI, Loom and Java 25.
- Targets Temurin 21, ModDevGradle in `mod/` and packwiz in `pack/`.
- Separate `mod-v*` / `pack-v*` tag flows, packwiz installed from a pinned commit, GPU self-hosted runner rules (no sudo, no secrets, no fork PRs).
- Action versions refreshed 2026-10-11. mc-publish moved to `Kira-NT/mc-publish`. Minotaur 2.10.0 needs `loaders = ['neoforge']`.
- Notes that packwiz exports embed cross-platform jars. Not verified: whether mc-publish can upload modpacks.

## Not vendored

Upstream skills not copied: `minecraft-commands-scripting`, `minecraft-essentials-ops`, `minecraft-imagegen`, `minecraft-multiloader`, `minecraft-plugin-dev`, `minecraft-server-admin`, `minecraft-worldedit-ops`.
