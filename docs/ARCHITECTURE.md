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
          data/scripts only     │  (tags, per-mob JSON, recipes, triggers,
          never Java imports    │   stats, commands, API events via KubeJS)
                 ┌──────────────▼────────────────┐
                 │ Remission (mod/)              │
                 │ generic defaults, no pack deps│
                 └──────────────┬────────────────┘
                                │ optional, guarded integrations
              Mekanism · AE2 · Create · Ars Nouveau · EMI/JEI · JourneyMap
              and the phase 10 mod-side tie-ins (DESIGN: Pack integrations)
```

- Remission compiles against NeoForge only. Integrations with other mods are optional (`compileOnly` API + runtime checks via `ModList`) and live in `integration/<modid>/`. Remission never hard-depends on an integrated mod.
- Integrations and addons build on the public API (`com.cubiecode.remission.api`, below). Pack-side integration (KubeJS, datapacks) is preferred; mod-side code is used only where the other mod's content can be registered or hooked only from Java (DESIGN, Pack integrations).
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
| *planned* `api/` | Public events and queries for integrations, addons and KubeJS (P4.4) |
| *planned* `corruption/`, `world/`, `purification/`, `research/`, `tech/`, `cure/`, `quest/`, `integration/` | Feature systems by roadmap phase |

Resources: `src/main/resources` (hand-written: lang, textures, structures), `src/generated/resources` (datagen, committed), `src/main/templates/META-INF/neoforge.mods.toml` (expanded from `gradle.properties`).

## Naming in code

Internal Java system names and public API types may keep the generic word "corruption" (`CorruptionState`, the `corruption` attachment, `remission:is_corrupted`); content IDs, lang keys, triggers, stats and commands use the Strain glossary in [DESIGN](DESIGN.md#glossary). Do not rename either side to match the other.

## Planned systems and data flow

### Corruption state (phase 2)

- `AttachmentType<CorruptionState>` (attachment `corruption`) on entities: level/stage (later `variant`, the resistant mutation a host carries), codec-serialized, copied on death only where intended.
- Sync: server is authoritative. On change and on start-tracking, send to tracking players. Option A: custom `CustomPacketPayload` registered in `RemissionNetwork`. Option B: NeoForge 21.1.259's `AttachmentType.Builder#sync(StreamCodec)`, which exists in this version. Choose in phase 2 and record the decision here.
- Client: `CorruptionLayer` added to every `LivingEntityRenderer` in `EntityRenderersEvent.AddLayers` (`getEntityTypes()` plus player skins), rendering the parent model with an overlay render type. No per-entity-type code.

### Behaviour (phase 3)

`EntityJoinLevelEvent` (server) → if a host and `Mob`: profile lookup (per-mob JSON, else defaults by mob category) → strip passive goals, inject target/attack goals. Brain-based mobs get a brain-memory or wrapper fallback; flying and swimming mobs get movement-appropriate attack goals. `EntityAttributeModificationEvent` adds `ATTACK_DAMAGE` (and others as needed) to all entity types missing them. Infection: `LivingDeathEvent` (killer is a host) and time in festering chunks flip state or replace via `convert_to`.

### Festering land (phase 4)

Chunk attachment `{level, last_update_game_time}`; nidi (`remission:nidus`) are block entities registered in a level-wide saved index. A server tick budgeted spread engine moves corruption between chunks; `ChunkEvent.Load` fast-forwards from the stored time with a bounded amount of work. Each chunk-to-chunk step posts the cancellable spread attempt event (rate multiplier) before applying. Festering stages are applied as block transformations in bounded batches. Biome flip to `remission:fester` (or a per-source-biome fester variant from data) uses a mutable biome container write and chunk resend. Surges are scheduled per level. The same engine runs in reverse for the Counterstrain (phase 9).

### Public API (P4.4, extended by each system item)

`com.cubiecode.remission.api` is the only package other mods and KubeJS bindings may rely on; everything else is internal.

- Events, posted server side on `NeoForge.EVENT_BUS`, fired by the system that owns the state change: host corrupted / cleansed (P2.1, P4.4), chunk stage changed and nidus destroyed (P4.1, P4.4), Strain spread attempt (cancellable, rate multiplier; P4.4), block festered (P4.2), surge started / ended (P4.3), viral load changed (P5.1), block purified and chunk entered remission (P5.2).
- Queries: corruption stage at a position or chunk, `isHost(entity)`, a player's viral load (P5.1).
- Events are posted after the state change is applied, except the cancellable spread attempt. Batched changes (catch-up, bounded stage batches) post one event per chunk, not per block, unless the event is block-scoped.
- Schemas, names and firing rules live in remission-data-formats ("Public API events") and change only with the code. KubeJS bindings for them are P8.3.

### Viral load (phase 5)

Player attachment `viral_load`, synced to its own player. Gain per tick in festering chunks (scaled down by height above the ground when the altitude falloff config is on), plus drinking festering water, swimming in water in festering chunks and flying through miasma clouds (spawned by surges, P4.3); decay elsewhere; serums and gear reduce gain.

### Dispersal payload (phase 9)

The Counterstrain is deployed from a dispersal source: a position, radius and rate, ticked by whatever holds it. `remission:dispersal_payload` (a placed block) is the only holder Remission needs to finish the game (P9.2). The source API must not assume a block, so `integration/create/` (P10.6) can tick it from a moving Create contraption, Aeronautics airships included.

### Contracts for the pack (phases 3–8)

| Surface | Location | Owner skill |
| --- | --- | --- |
| Immune mobs | `data/<ns>/tags/entity_type/immune.json` (`#remission:immune`) | remission-data-formats |
| Per-mob overrides | `data/<ns>/remission/mob_profile/<name>.json` (datapack registry `remission:mob_profile`) | remission-data-formats |
| Host loot | `data/<ns>/loot_modifiers/*.json` + `data/neoforge/loot_modifiers/global_loot_modifiers.json`, condition `remission:is_corrupted` | remission-data-formats |
| Fester variants | per-source-biome fester biome (data map, shape fixed in P4.2) | remission-data-formats, minecraft-world-generation |
| Machine recipes | recipe types `remission:analyzing`, `remission:sequencing`, `remission:synthesizing` | remission-data-formats, kubejs-scripting |
| Quest gates | advancement triggers `remission:*` (`nidus_destroyed`, `host_scanned`, ...), custom stats (`remission:nidi_destroyed`, ...) | remission-data-formats, ftb-quests-authoring |
| Quest-fired events | commands (`/remission surge start`, `/remission nidus seed`, `/remission region advance`; P8.2) | ftb-quests-authoring |
| API events and queries | `com.cubiecode.remission.api` (P4.4) | remission-data-formats |
| Dispersal payload | `remission:dispersal_payload` and its dispersal source API (P9.2; contraption holder P10.6) | remission-data-formats |
| KubeJS event bindings | Remission events exposed to KubeJS scripts (P8.3) | kubejs-scripting, remission-data-formats |

All schemas start as stubs in the remission-data-formats skill and change only with the code that reads them.

## Runtime and test infrastructure

- Run configs: `client` (`run/client`), `server` (`run/server`), `gameTestServer` (`run/gametest`, exits on completion), `data` (`run/data`, writes `src/generated/resources`).
- Dev runtime mods (`localRuntime`, never published): KubeJS + Rhino + Better Advanced Tooltips, ProbeJS, Architectury, FTB Library/Teams/Quests (FTB Maven, not on Modrinth), JEI + EMI, Mekanism, AE2 + GuideME, JourneyMap. Toggle `dev_runtime_mods`. CI disables them.
- Headless client: `tools/run-client-headless.sh` (Xvfb + VirtualGL EGL on the RTX 4070). Phase 1 adds a dev-only harness that loads fixed scenes, screenshots them and quits.

## Decisions

- **Gradle 9.8.1** wrapper instead of the MDK's 9.2.1: current at scaffold time, builds with no deprecation warnings.
- **Modrinth Maven pins by version ID** where a project reuses a version number across loaders (JEI resolved to its Fabric jar by number).
- **FTB mods from FTB Maven / CurseForge:** FTB Quests, Library and Teams are not on Modrinth. The pack adds them with `packwiz cf add`.
- **Distribution:** Remission publishes to Modrinth and CurseForge; Counterstrain is CurseForge-first, and its CurseForge zip is the release artifact (DESIGN, Distribution). A Modrinth `.mrpack` cannot download CurseForge files, and the pack already needs CurseForge-only mods. `packwiz curseforge export` lists only CurseForge-sourced mods in `manifest.json` and embeds every other jar under `overrides/mods/`. That is why pack mods are sourced from CurseForge; P10.1 re-sources the current Modrinth-sourced ones. The `.mrpack` export is for local testing only.
- **EMI + JEI together:** EMI is the recipe viewer players see; it takes over JEI's runtime UI (its built-in JEI compatibility) and shows every mod's JEI plugin recipes. JEI stays installed so mods with only JEI plugins keep working. Remission's recipe viewer integration (P7.2) is a JEI plugin first, which reaches both; a native EMI plugin is added only for EMI-specific features.
- **JourneyMap for maps:** chosen over Xaero's (closed source, no overlay API) and FTB Chunks (icon-only API: chunk colouring would work on the full-screen map but not on the zoomed or rotated minimap). JourneyMap's API (`info.journeymap:journeymap-api-neoforge:2.0.0-1.21.1` from maven.blamejared.com, `compileOnly`) has `PolygonOverlay` on the full-screen map, minimap and web map, `PolygonHelper`/`MapPolygonWithHoles` to merge chunks into outlines with remission-zone holes, and a server-side overlay API. The P8.2 festering overlay uses it as an optional integration in `integration/journeymap/`.
- **No climate mods:** Tough As Nails and Cold Sweat are out; viral load is the only exposure system.
- **Integrations pack-side first:** each tie-in in DESIGN's Pack integrations table is pack-side (KubeJS, datapacks on Remission's surfaces) unless the other mod's content can be registered or hooked only from Java; then it is a guarded `integration/<modid>/` package. P10.1 owns membership (`pack/mods/`) and the `compileOnly`/dev-runtime entries for every mod-side integration, so later phase 10 lanes do not touch build files.
- **Content provenance:** player-facing content is tracked in [CONTENT-PROVENANCE](CONTENT-PROVENANCE.md); placeholder textures carry a magenta marker that P1.4's check (planned) verifies against the ledger.
- **Pack datapack in `pack/kubejs/data/`:** KubeJS loads it as a datapack in every world without another mod.
- **Performance:** spread, catch-up and visual stage changes must be budgeted per tick and measured on foundry (phase 12 includes client FPS under heavy spread).
