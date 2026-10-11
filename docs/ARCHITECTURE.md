# Architecture and decisions

## Current boundary

Phase 0 is scaffold only. Remission registers its deferred registers, configs, payload registrar, datagen providers and one smoke gametest, all with no entries. Everything below marked *planned* is a roadmap item.

## Repository and products

```text
                 ┌───────────────────────────────┐
                 │ Counterstrain (pack/)         │
                 │ KubeJS · FTB Quests · datapack│
                 │ story, recipes, balance       │
                 └──────────────┬────────────────┘
          data/scripts only     │  (tags, per-mob JSON, recipes,
          never Java imports    │   advancement triggers, stats, events)
                 ┌──────────────▼────────────────┐
                 │ Remission (mod/)              │
                 │ generic defaults, no pack deps│
                 └──────────────┬────────────────┘
                                │ optional, guarded integrations
              Mekanism · AE2 · Create · Ars Nouveau · JEI · map mods
```

- Remission compiles against NeoForge only. Integrations with other mods are optional (`compileOnly` API + runtime checks via `ModList`) and live in `integration/<modid>/`.
- The pack talks to the mod only through data and script surfaces (below). If the pack needs something the mod cannot express, extend the surface generically rather than special-casing the pack.

## Mod layout

`mod/src/main/java/com/cubiecode/remission/`:

| Package | Contents |
| --- | --- |
| `Remission` | `@Mod` common entrypoint, `MOD_ID`, logger, `id()` |
| `client/` | `@Mod(dist = CLIENT)` entrypoint; renderers, layers, client payload handling, client config screen |
| `server/` | `@Mod(dist = DEDICATED_SERVER)` entrypoint; dedicated-server-only setup |
| `config/` | `RemissionCommonConfig` (gameplay), `RemissionClientConfig` (presentation); no options yet |
| `registry/` | One `DeferredRegister` holder per kind: blocks, items, attachments, loot conditions, loot modifier codecs, recipe types/serializers, advancement triggers; `RemissionRegistries.register(modBus)` wires them |
| `network/` | `RegisterPayloadHandlersEvent` registrar, `PROTOCOL_VERSION` |
| `data/` | `GatherDataEvent` wiring and providers (blockstates, item models, tags, recipes, loot tables, global loot modifiers) |
| `gametest/` | `@GameTestHolder` classes; `remission:empty` is a 3x3x3 air template |
| *planned* `corruption/`, `world/`, `purification/`, `research/`, `tech/`, `cure/`, `quest/`, `integration/` | Feature systems by roadmap phase |

Resources: `src/main/resources` (hand-written: lang, textures, structures), `src/generated/resources` (datagen, committed), `src/main/templates/META-INF/neoforge.mods.toml` (expanded from `gradle.properties`).

## Planned systems and data flow

### Corruption state (phase 2)

- `AttachmentType<CorruptionState>` on entities: level/stage (later strain), codec-serialized, copied on death only where intended.
- Sync: server is authoritative. On change and on start-tracking, send to tracking players. Option A: custom `CustomPacketPayload` registered in `RemissionNetwork`. Option B: NeoForge 21.1.259's `AttachmentType.Builder#sync(StreamCodec)`, which exists in this version. Choose in phase 2 and record the decision here.
- Client: `CorruptionLayer` added to every `LivingEntityRenderer` in `EntityRenderersEvent.AddLayers` (`getEntityTypes()` plus player skins), rendering the parent model with an overlay render type. No per-entity-type code.

### Behaviour (phase 3)

`EntityJoinLevelEvent` (server) → if corrupted and `Mob`: profile lookup (per-mob JSON, else defaults by mob category) → strip passive goals, inject target/attack goals. Brain-based mobs get a brain-memory or wrapper fallback; flying and swimming mobs get movement-appropriate attack goals. `EntityAttributeModificationEvent` adds `ATTACK_DAMAGE` (and others as needed) to all entity types missing them. Conversion: `LivingDeathEvent` (killer corrupted) and exposure ticks flip state or replace via `convert_to`.

### World corruption (phase 4)

Chunk attachment `{level, last_update_game_time}`; hearts are block entities registered in a level-wide saved index. A server tick budgeted spread engine moves corruption between chunks; `ChunkEvent.Load` fast-forwards from the stored time with a bounded amount of work. Visual stages are applied as block transformations in bounded batches. Biome flip uses a mutable biome container write and chunk resend. Surges are scheduled per level. The same engine runs in reverse for the cure (phase 9).

### Contracts for the pack (phases 3–8)

| Surface | Location | Owner skill |
| --- | --- | --- |
| Immune mobs | `data/<ns>/tags/entity_type/immune.json` (`#remission:immune`) | remission-data-formats |
| Per-mob overrides | `data/<ns>/remission/mob_profile/<name>.json` (datapack registry `remission:mob_profile`) | remission-data-formats |
| Corrupted loot | `data/<ns>/loot_modifiers/*.json` + `data/neoforge/loot_modifiers/global_loot_modifiers.json`, condition `remission:is_corrupted` | remission-data-formats |
| Machine recipes | recipe types `remission:analyzing`, `remission:sequencing`, `remission:synthesizing` | remission-data-formats, kubejs-scripting |
| Quest gates | advancement triggers `remission:*`, custom stats | remission-data-formats, ftb-quests-authoring |
| Quest-fired events | commands or a KubeJS binding (decided in phase 8) | ftb-quests-authoring |

All schemas start as stubs in the remission-data-formats skill and change only with the code that reads them.

## Runtime and test infrastructure

- Run configs: `client` (`run/client`), `server` (`run/server`), `gameTestServer` (`run/gametest`, exits on completion), `data` (`run/data`, writes `src/generated/resources`).
- Dev runtime mods (`localRuntime`, never published): KubeJS + Rhino + Better Advanced Tooltips, ProbeJS, Architectury, FTB Library/Teams/Quests (FTB Maven, not on Modrinth), JEI, Mekanism, AE2 + GuideME. Toggle `dev_runtime_mods`. CI disables them.
- Headless client: `tools/run-client-headless.sh` (Xvfb + VirtualGL EGL on the RTX 4070). Phase 1 adds a dev-only harness that loads fixed scenes, screenshots them and quits.

## Decisions

- **Gradle 9.8.1** wrapper instead of the MDK's 9.2.1: current at scaffold time, builds with no deprecation warnings.
- **Modrinth Maven pins by version ID** where a project reuses a version number across loaders (JEI resolved to its Fabric jar by number).
- **FTB mods from FTB Maven / CurseForge:** FTB Quests, Library and Teams are not on Modrinth. The pack adds them with `packwiz cf add`.
- **Pack datapack in `pack/kubejs/data/`:** KubeJS loads it as a datapack in every world without another mod.
- **Performance:** spread, catch-up and visual stage changes must be budgeted per tick and measured on foundry (phase 11 includes client FPS under heavy spread).
