---
name: remission-data-formats
description: Define, change or consume Remission's pack-facing contracts (entity tags, per-mob override JSON, data maps, loot condition, recipe types, KubeJS schemas, advancement triggers, stats, commands, public API events and their KubeJS bindings). Keep these schemas current with the code.
---

# Remission data formats

These are contracts between the mod and any pack. Schemas marked `planned` are stubs until the roadmap item named implements it; then update its status, fields and example in this file and docs/ARCHITECTURE.md in the same PR. Never ship a reader for a format that is not documented here. Paths use 1.21.1 singular folders; `<ns>` is any datapack namespace (the pack uses `counterstrain` or overrides `remission`).

Names follow the glossary and naming rule in docs/DESIGN.md: content IDs, triggers, stats and commands use Strain terms (`nidus`, `host`, `miasma`, `fester`); system names such as `CorruptionState`, the `corruption` attachment and `remission:is_corrupted` keep "corruption". Do not rename either.

## Entity attachment `remission:corruption` (built, P2.1)

`CorruptionState` on any entity, read and written through `com.cubiecode.remission.corruption.Corruption` (`get`, `set(entity, level)`, `isHost`, `isImmune`; server writes only). `set` on an entity in `#remission:immune` ignores any level above 0 and returns the entity's current state; level 0 always cleanses. When `set` turns an entity into a host or back, it applies or removes its mob profile `attributes` (below). Saved inside the entity's `neoforge:attachments` compound, omitted at level 0:

```json
{ "level": 50, "stage": 2 }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `level` | int 0–100 | How far the Strain has taken the entity. 0 removes the attachment. |
| `stage` | int ≥ 0 | Host stage: how many `hosts.stageThresholds` entries `level` met when it was set. 0 = not a host; `isHost` is `stage >= 1`. Readers use the stored stage, never recompute it. |
| `variant` | reserved | Resistant mutation (P9.1). Not written yet; unknown keys are ignored on load, so adding it is backward compatible. |

Config `config/remission-common.toml`, `[hosts] stageThresholds = [1, 34, 67]`: 1–8 ints in 1–100, order-independent. Changes apply the next time a level is set.

Sync: NeoForge attachment sync, wire format `CorruptionState.STREAM_CODEC` (var-int `level`, var-int `stage`), to players tracking the entity and to the entity itself if it is a player, on change, on start-tracking and on the player's login, respawn and dimension change. Clients read it with `Corruption.get`. Not copied on death or by vanilla conversions (a zombie villager cured, a pig struck by lightning); infection's own `convert_to` sets it on the replacement (below). `set` posts `HostCorruptedEvent` or `HostCleansedEvent` (Public API, below) when the state changes; `set(entity, level, cause)` names the `CorruptionCause` (infection and the debug commands pass theirs). The debug commands below drive it.

## Scanner and data items (built, P6.1)

Items: `remission:scanner` (stacks to 1; listed in the vanilla Tools and Utilities creative tab) and the data items `remission:host_scan` and `remission:block_scan`. Code: `research/Scanning` (behaviour), `research/ScanData` (component), `registry/RemissionDataComponents`.

Scanning, server side (the client only claims the interaction):

| Use the scanner on | Result |
| --- | --- |
| A host (any living entity with host stage ≥ 1, players included) | One `remission:host_scan` with `{"entity": <entity type id>, "stage": <stored host stage>}` into the inventory (dropped if full). Action bar `message.remission.scanner.host` (name, stage). The host is unchanged. |
| A living entity that is not a host (stage 0, including a latent level below the first threshold) | No item. Action bar `message.remission.scanner.no_strain` (name). |
| A non-living entity (item frame, boat, minecart) | Not a scan: the entity's own interaction runs as usual. |
| Any block | One `remission:block_scan` with `{"block": <block id>}` (block id only, no state or block entity data). Action bar `message.remission.scanner.block` (name). |

Every scan attempt (including the no-Strain case) puts the scanner on a 20-tick item cooldown; attempts during the cooldown do nothing. Entity scans are taken in `PlayerInteractEvent.EntityInteract` and `EntityInteractSpecific`, before the entity's own interaction, so scanning a villager does not open trading and scanning a horse does not mount it. Block scans run in `onItemUseFirst`, before the block's own use, so scanning a chest does not open it. No sneaking needed; spectators cannot scan.

Data component `remission:scan_data`, saved with the stack (`components` in item NBT/JSON) and synced to clients for the tooltip. Exactly one of two shapes:

```json
{ "entity": "minecraft:cow", "stage": 2 }
{ "block": "minecraft:stone" }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `entity` | resource location | Scanned host's entity type. |
| `stage` | int ≥ 1 | The host's stored stage at scan time (host scans only; never recomputed). |
| `block` | resource location | Scanned block. |

Ids are stored as plain ids, not registry holders, so a scan of a since-removed mod's entity or block still loads; its tooltip then shows the raw id. The component holds no timestamp or player, so equal scans stack and a recipe can match the whole component. Wire format (`ScanData.STREAM_CODEC`): var-int kind (0 host, 1 block), resource location, var-int stage (0 for blocks).

Matching scans in pack recipes (1.21.1 ingredients): any scan of a kind with `{"item": "remission:host_scan"}`; a specific scan with NeoForge's components ingredient, e.g. `{"type": "neoforge:components", "items": "remission:host_scan", "components": {"remission:scan_data": {"entity": "minecraft:cow", "stage": 2}}}` (stage is part of the value, so list one ingredient per stage that should count). A data item without the component (from `/give`) is valid but blank: no tooltip line, and it matches only plain item ingredients.

Each host scan fires `remission:host_scanned` and counts `remission:hosts_scanned`, each block scan fires `remission:block_scanned` and counts `remission:blocks_scanned` (Advancement triggers and stats, below). Not part of this contract yet: scanning samples (unassigned). Per-player scan progress: Bestiary, below.

## Bestiary (P6.2; hidden types P6.3)

Host types are derived, not listed: every registered entity type that has default attributes (that is, is living), is not `minecraft:player`, and is in neither `#remission:immune` nor `#remission:bestiary_hidden` (`Bestiary.isHostType`, `Bestiary.hostTypes()`). Each installed mod's living mobs count without data; to drop a type from infection and the bestiary, tag it immune; to keep it infectable but out of the bestiary, tag it hidden. Players can still carry the Strain and be scanned as hosts, but are never a bestiary entry.

Player attachment `remission:bestiary` (`BestiaryProgress`): the highest host stage each host type has been scanned at by this player. Written only on the server, when the scanner gives a host scan of a host type (`Bestiary.recordScan`, keeps the maximum); block scans, non-host scans, scans of types the bestiary does not list (a host player, an immune or hidden type) and `/give`n scans record nothing. Saved in the player's `neoforge:attachments` NBT only when non-empty and copied on death. Synced through NeoForge attachment sync: updates go to the owning player only, but NeoForge's initial sync when a client starts tracking an entity ignores the sync predicate, so clients tracking the player also receive its current map. Treat the map as visible to nearby clients; Remission's client reads only the local player's. Shape:

```json
{ "minecraft:cow": 2, "mekanism:robit": 1 }
```

| Key / value | Type | Meaning |
| --- | --- | --- |
| key | resource location | Scanned host's entity type. Kept even if the type is no longer registered (not listed while missing). |
| value | int ≥ 1 | Highest host stage scanned for that type. |

Entity tag `#remission:bestiary_hidden` (`Bestiary.HIDDEN`), `data/<ns>/tags/entity_type/bestiary_hidden.json`, standard tag JSON: living types that can still become hosts but that survival players cannot normally meet as hosts. Tagged types are left out of the bestiary list and its total, and their scans record no progress (the scan item is still given). Remission ships `minecraft:giant`, `minecraft:illusioner` and `minecraft:armor_stand`; a pack adds types with `"replace": false` or re-shows a default with `"replace": true` and its own list. There is no spawn-egg or other heuristic, so modded mobs without eggs (the Mekanism robit) stay listed. Tags are read on every check, so `/reload` applies at once on the server and, after the tag sync, on clients; progress already recorded for a newly hidden type is kept but not listed or counted.

Wire format (`BestiaryProgress.STREAM_CODEC`): var-int entry count, then per entry a resource location and a var-int stage. The client opens the bestiary with the key mapping `key.remission.bestiary` (category `key.categories.remission`, default M). The `host_scanned` trigger's `bestiary_types` condition counts this map's entries.

## Infection (built, P3.3)

`com.cubiecode.remission.infection.Infection`, server side. A `Mob` that dies (`LivingDeathEvent`, listened at `LOW` priority so other mods' death protection wins) while the damage source's responsible entity (`DamageSource.getEntity()`, so a host skeleton's arrow counts) is a host does not die; the event is cancelled and it becomes a host at the first host stage (level = the lowest `hosts.stageThresholds` entry):

- With no mob profile `convert_to`, or `convert_to` naming its own type, it converts in place: same entity, healed to full health, no death loot or XP.
- Otherwise `Mob.convertTo(type, true)` replaces it (position, age, AI flag, name, persistence, invulnerability, equipment with drop chances, vehicle), `LivingConversionEvent.Pre` can veto it and `.Post` fires. The replacement joins the level first and is infected right after.

The victim dies normally when it is immune, already a host, its profile sets `conversion.on_kill` to false, `convert_to` names an immune type or a non-mob type (logged), or another mod vetoes the conversion. Players and other non-`Mob` living entities are never infected on kill.

Festering land is the other infection source (Festering land infection, below), and contact with infectious blocks or festering water a third (Festering veins and contact infection, below). Both convert the same way (in place or through `convert_to`) but do not heal an in-place host.

## Festering land infection (built, P4.7)

`com.cubiecode.remission.infection`, server side. A `Mob` builds up **exposure** while it is in a festering chunk at or above `[infection] festeringLandMinStage`, at any height (caves under a festering chunk count in full; stages are per chunk), and becomes a host once its exposure reaches `festeringLandExposureTicks`:

- Eligible (`Infection.canInfectFromFesteringLand(Mob)`): not `#remission:immune`, not already a host, and its mob profile does not set `conversion.festering_land` to false. An ineligible mob in festering land has its exposure cleared. Players and other non-`Mob` entities never build exposure (viral load is the player system, P5.1).
- Cadence: one `ExposureTracker` per server level, on `LevelTickEvent.Post`. Every 20 ticks a pass snapshots the level's mobs and checks up to 256 of them per tick, ending the tick early once 32 have become hosts (`ExposureSettings.INTERVAL_TICKS`, `MOB_BUDGET`, `INFECTION_BUDGET`; not configurable), so a pass over more mobs, or many mobs reaching the threshold together, continues on later ticks. Each checked mob gains, in qualifying land, or loses, anywhere else, the game ticks since the previous pass started (nothing on the first pass after the server starts or the source is turned back on). Mobs in chunks that are not entity-ticking are skipped and keep their exposure.
- Infection: at the threshold the exposure is cleared and the mob becomes a host at the first host stage through `Corruption.set` with `CorruptionCause.FESTERING_LAND`, in place (not healed), or replaced through its profile's `convert_to` exactly as infection on kill does (vetoable by `LivingConversionEvent.Pre`; a vetoed or immune `convert_to` leaves the mob clean, and it builds exposure again). `HostCorruptedEvent` carries `FESTERING_LAND`.

Entity attachment `remission:festering_exposure` (`RemissionAttachments.FESTERING_EXPOSURE`): int ticks ≥ 0, codec-serialized with the mob (`neoforge:attachments`, skipped at 0), server only, not synced or copied on conversion. `ExposureTracker.exposure(mob)` and `setExposure(mob, ticks)` read and write it (internal, not API).

Config `config/remission-common.toml`, section `[infection]` (read at the start of each pass):

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `festeringLand` | bool | true | Festering land infection on or off. Off freezes every mob's exposure. |
| `festeringLandMinStage` | int 1–8 | 2 | Lowest chunk festering stage (`[festering] stageThresholds`) that builds exposure. Above the number of stages, no land does. |
| `festeringLandExposureTicks` | int 20–1728000 | 6000 | Exposure (game ticks in qualifying land, net of decay) at which a mob becomes a host. |

Cost (foundry, `ExposureBenchmark`, `REMISSION_EXPOSURE_BENCHMARK=1`): see docs/ARCHITECTURE.md (Festering land infection).

## Viral load (built, P5.1)

`com.cubiecode.remission.viralload`, server authoritative. A player's **viral load** is a float from 0 to 100 (`CorruptionQueries.MAX_VIRAL_LOAD`). Every 20 ticks of a living player in survival or adventure (`ViralLoadSettings.INTERVAL_TICKS`; not while the level's ticks are frozen by `/tick freeze`, and never for fake players) an update adds the sum of these rates for the 20 ticks, or, when they add nothing, takes off `decay`:

- **Land:** in a chunk at festering stage 1 or higher, `landGain` for that stage, times the altitude factor.
- **Altitude factor:** the height above the ground is the player's feet Y minus the top of the highest block in their column that blocks motion, leaves excluded and fluids included (`Heightmap.Types.MOTION_BLOCKING_NO_LEAVES`). At or below `falloffStart` (so always on the ground, in water at the surface, in caves, tunnels and bases below the surface, and under any roof) the factor is 1; at or above `falloffEnd` it is `falloffMinimum`; in between it follows `falloffCurve` from 1 down to the minimum. With `altitudeFalloff` off it is always 1.
- **Water:** feet or eyes in water in a chunk at festering stage 1 or higher (swimming, wading, diving): `waterGain`, on top of land gain, no altitude factor.
- **Miasma clouds:** the player's bounding box overlaps a `remission:miasma_cloud`'s bounding box (Surges and miasma clouds, below): `cloudGain`, at any height and over any land.
- **Drinking:** finishing a water bottle (`minecraft:potion` holding `minecraft:water`) while standing in a chunk at festering stage 1 or higher adds `drinkDose` at once. Where the bottle was filled is not tracked (bottle origin and contaminated items are P10.6).

After the gain or decay, each `debuffs` entry whose viral load is reached applies its effect (highest amplifier per effect) for 240 ticks as an ambient effect without particles, refreshed every update; at or above `damageAt` the player takes `damage` per second of damage type `remission:viral_load`. Players in creative or spectator gain nothing and take no debuffs; their viral load still decays. Death resets it: a respawned player starts at 0.

Config `config/remission-common.toml`, section `[viral_load]` (read at every update and drink):

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `enabled` | bool | true | Off freezes every player's viral load and stops its debuffs and damage. |
| `landGain` | list of 1–8 numbers 0–6000 | `[1.0, 3.0, 6.0, 12.0]` | Viral load per minute in festering land by festering stage: the first entry is stage 1; stages past the end use the last entry. |
| `waterGain` | number 0–6000 | 20.0 | Per minute in water in festering land. |
| `drinkDose` | number 0–100 | 10.0 | At once per water bottle drunk in festering land. |
| `cloudGain` | number 0–6000 | 30.0 | Per minute inside a miasma cloud. |
| `decay` | number 0–6000 | 5.0 | Lost per minute while nothing adds viral load. |
| `altitudeFalloff` | bool | true | Whether land gain falls off with height above the ground. |
| `falloffStart` | number 0–512 | 4.0 | Height above the ground where the falloff starts. |
| `falloffEnd` | number 0–512 | 40.0 | Height where it reaches `falloffMinimum`. |
| `falloffMinimum` | number 0–1 | 0.1 | Share of land gain left at and above `falloffEnd`. |
| `falloffCurve` | `LINEAR`, `QUADRATIC`, `SMOOTH` | `LINEAR` | Shape between start and end, with `t` from 0 at the start to 1 at the end: `1 - t`, `(1 - t)²` (drops fast, then levels out), `1 - t²(3 - 2t)` (eases in and out). |
| `debuffs` | list of 0–64 strings `"<viral load> <effect id> <amplifier>"` | `["25 minecraft:hunger 0", "50 minecraft:weakness 0", "50 minecraft:mining_fatigue 0", "75 minecraft:slowness 0", "75 minecraft:hunger 1"]` | Effect while viral load is at or above the value; amplifier 0 is level I. Unknown effect ids are skipped with one warning. |
| `damageAt` | number 0–100 | 100.0 | Viral load at and above which players take viral load damage. |
| `damage` | number 0–100 | 1.0 | Damage per second there (2 is one heart); 0 turns it off. |

With the defaults, standing on stage 4 land fills the meter in about 8 minutes (hunger after about 2), stage 1 land in about 100 minutes, and a full meter decays in 20.

Player attachment `remission:viral_load` (`RemissionAttachments.VIRAL_LOAD`, `ViralLoadState`): the load is codec-serialized with the player (`neoforge:attachments`, a float, skipped at 0); the trend (`STEADY`, `RISING`, `FALLING`, what the last update did) is not saved. Not copied on death. Not attachment-synced: the server sends payload `remission:viral_load` (`ViralLoadPayload`: float load, var-int trend ordinal) to its own player only, when the state changes and on login, respawn and dimension change (`RemissionNetwork.PROTOCOL_VERSION` 2). The client stores it on the local player for the HUD meter and the query.

Damage type `remission:viral_load` (`data/remission/damage_type/viral_load.json`, datagen `RemissionDamageTypeProvider`): message id `remission.viral_load` (lang `death.attack.remission.viral_load`, `.player` when a killer is credited), scaling `never`, exhaustion 0; in `#minecraft:bypasses_armor` (datagen `RemissionDamageTypeTagsProvider`). Packs and revenants (P5.3) recognise viral load deaths by this type.

Client config `config/remission-client.toml`, `[hud] showViralLoad` (default true): the meter (an icon, a bar whose colour steps up every quarter, an arrow while rising or falling) shows one row above the hunger bar, stacked like vanilla's rows, while viral load is above 0, in survival and adventure. Presentation only.

Trigger `remission:viral_load_changed` (Advancement triggers and stats, below) and `ViralLoadChangedEvent` and `CorruptionQueries.viralLoad` (Public API, below). Gametests: `ViralLoadTests`.

## Entity tag `#remission:immune` (built, P3.3)

`data/<ns>/tags/entity_type/immune.json`, standard tag JSON (`{"replace": false, "values": ["minecraft:wither", "#c:bosses", {"id": "othermod:x", "required": false}]}`); Java `Corruption.IMMUNE`. An immune entity never becomes a host by any route: `Corruption.set` ignores levels above 0, infection on kill and `convert_to` skip it, and `/remission infect` skips it. A type added to the tag after it already carries the Strain keeps its state until cleansed. Remission ships only `#c:bosses` (ender dragon, wither, and whatever other mods tag); the pack extends it. Players are deliberately not immune: they can carry the Strain (`/remission infect`, rendered by the host overlay, scannable), but infection on kill never takes them because it only converts `Mob`s.

## Entity tag `#remission:surge_hosts` (built, P4.3)

`data/<ns>/tags/entity_type/surge_hosts.json`, standard tag JSON; Java `SurgeHosts.SURGE_HOSTS`. The entity types a surge spawns as hosts, each wave picking uniformly from the tag (sorted by id). Remission ships `minecraft:zombie`, `minecraft:spider`, `minecraft:cow`, `minecraft:pig`, `minecraft:sheep`; packs replace or extend it (modded mobs work). Types that are not `Mob`s are skipped; immune types spawn but stay clean, so keep them out. Every type keeps its own spawn placement, and in covered spots (no sky above) its own spawn rules: with the defaults, caves get zombies and spiders, and cows, pigs and sheep spawn only in the open. An empty tag turns surge hosts off.

## Mob profile (built, P3.3; defaults P3.2)

Datapack registry `remission:mob_profile` (`MobProfiles.KEY`), files `data/<ns>/remission/mob_profile/<name>.json`, server side only (not synced). **Reload:** datapack registries load with the world, so adding, editing or removing a profile needs a world reload (rejoin a singleplayer world, restart a server); `/reload` does not re-read profiles, but tags a profile names in `entities` do rebind on `/reload`. Existing hosts pick up changed `attributes` the next time they join a level after the reload.

**Default profiles (P3.2):** Remission ships `remission:unsupported` (`data/remission/remission/mob_profile/unsupported.json`, datagen `RemissionMobProfileProvider`): `entities` villager, allay and frog, `behaviour: none`, priority `-1000` (`RemissionMobProfileProvider.DEFAULT_PRIORITY`). Those brain-based mobs still become hosts but cannot fight a chosen target (villagers and allays have no fight activity, a frog's tongue only takes frog food), so they are explicitly marked unsupported. Every other mob uses its category default (docs/ARCHITECTURE.md, Behaviour). Because profiles never merge, a pack profile for one of these mobs at the default priority 0 replaces the default whole: it falls back to the category default (`melee` for frogs, `none` for villagers and allays) unless it sets `behaviour` itself. A datapack can also replace the file by its id.

```json
{
  "entities": "#minecraft:undead",
  "priority": 0,
  "behaviour": "melee",
  "attributes": { "minecraft:generic.attack_damage": 4.0, "minecraft:generic.movement_speed": 0.3 },
  "convert_to": "minecraft:zombie",
  "conversion": { "on_kill": true, "festering_land": true }
}
```

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `entities` | entity type id, `#tag` or list (HolderSet) | required | Types the profile applies to. |
| `priority` | int | 0 | Where several profiles match a type, the highest wins; ties go to the profile with the lexicographically smallest id. Profiles never merge. |
| `behaviour` | `melee` \| `ranged` \| `flying` \| `swimming` \| `passive_hostile` \| `none` (`MobBehaviour`, built) | absent | How the host fights; `HostBehaviours.behaviourFor` uses it ahead of the category default when present. Value meanings and category defaults: docs/ARCHITECTURE.md, Behaviour. `flying` and `swimming` add a pursuit attack (monsters keep their own); on brain-based mobs every value except `none` and `passive_hostile` means "the brain fights Remission's chosen prey"; `none` opts the mob out of host goals. |
| `attributes` | map attribute id → number | `{}` | Added (`add_value`) as permanent modifiers with id `remission:mob_profile` when the mob becomes a host, removed when it stops being one, reconciled with the current profile whenever a host joins a level. Attributes the type lacks are skipped. |
| `convert_to` | entity type id | absent | Mob type that replaces this mob when a host kills it (Infection above). Must be a non-immune `Mob` type. |
| `conversion.on_kill` | bool | true | Whether this mob becomes a host when a host kills it. |
| `conversion.festering_land` | bool | true | Whether prolonged time in festering land can make this mob a host (Festering land infection, P4.7). |

All `conversion` flags describe the profiled mob as the one being infected. A profile that fails to decode (unknown `behaviour`, attribute or entity id) is a datapack registry error, which stops the world from loading. Lookup: `MobProfiles.find(Entity)` → `Optional<MobProfile>`; `MobProfiles.select(candidates, type)` is the same rule over a given list.

## Loot condition `remission:is_corrupted` (built, P3.3)

`{"condition": "remission:is_corrupted", "entity": "this", "min_stage": 1}`: true when the `entity` loot target (`this`, `attacker`, `direct_attacker`, `attacking_player`; default `this`) is a living entity at host stage `min_stage` (int ≥ 1, default 1) or higher; false when the target is absent or not living. Java `IsCorruptedCondition`. Usable in loot tables, predicates and global loot modifiers.

## Global loot modifiers (built, P3.3)

Codec `remission:add_item` (`AddItemModifier`): `{"type": "remission:add_item", "conditions": [...], "item": {"id": "remission:host_tissue", "count": 1}}` adds a copy of `item` (an item stack: `id`, optional `count`, `components`) when every condition passes. Remission's default, `data/remission/loot_modifiers/host_samples.json` (datagen, `RemissionLootModifierProvider`), listed in `data/neoforge/loot_modifiers/global_loot_modifiers.json` (`{"replace": false, "entries": ["remission:host_samples", "remission:festered_soil_samples"]}`; the second is P7.1, below):

```json
{
  "type": "remission:add_item",
  "conditions": [
    { "condition": "remission:is_corrupted" },
    { "condition": "minecraft:damage_source_properties" }
  ],
  "item": { "id": "remission:host_tissue", "count": 1 }
}
```

One `remission:host_tissue` from the death loot of any host (the damage source condition limits it to death drops, not bartering or other tables that set `this`). A host converted on kill does not die, so it drops nothing. Packs change it by overriding the same file, drop it by overriding `global_loot_modifiers.json` with `"replace": true` and relisting the modifiers they keep, and add their own modifiers in their namespace.

## Miasma and samples (built, P7.1)

IDs: fluid `remission:miasma` (source) and `remission:flowing_miasma`, fluid type `remission:miasma`, liquid block `remission:miasma` (no loot table), item `remission:miasma_bucket`; sample items `remission:host_tissue` (P3.3, above), `remission:nidus_core`, `remission:festered_soil`. Fluid tags are not set; packs tag miasma themselves (e.g. `#c:miasma`) when a machine or mod needs one. With Mekanism installed, Remission also registers the chemical `remission:miasma` in Mekanism's chemical registry (`mekanism:chemical`; Mekanism's generic texture tinted violet, lang key `chemical.remission.miasma`); without Mekanism nothing is registered. Recipes converting between the fluid and the chemical are pack-side (P10.2).

**Nidus core:** the block loot table `data/remission/loot_table/blocks/nidus.json` (datagen) drops one `remission:nidus_core` (survives explosions; never the nidus itself). Packs change it by overriding that file.

**Festered soil:** loot condition `remission:is_festering`, Java `IsFesteringCondition`:

```json
{ "condition": "remission:is_festering", "min_stage": 2, "blocks": "#remission:festered_soil_sources" }
```

True when the chunk holding the loot `origin` is at festering stage `min_stage` (int ≥ 1, default 1) or higher (`CorruptionQueries.stageAt`, so `[festering] stageThresholds` applies) and, when `blocks` (a block id, list or `#tag`; optional) is set, the loot `block_state` is one of them. False without an origin, or with `blocks` set and no block state. Usable in block loot tables, predicates and global loot modifiers. Remission's default, `data/remission/loot_modifiers/festered_soil_samples.json` (datagen, codec `remission:add_item`):

```json
{
  "type": "remission:add_item",
  "conditions": [
    { "condition": "remission:is_festering", "min_stage": 2, "blocks": "#remission:festered_soil_sources" },
    { "condition": "minecraft:random_chance", "chance": 0.25 }
  ],
  "item": { "id": "remission:festered_soil", "count": 1 }
}
```

A 25% chance of one `remission:festered_soil` on top of the normal drops when a block in `#remission:festered_soil_sources` breaks (by hand, tool or explosion) in a chunk at festering stage 2 or higher. Block tag `data/<ns>/tags/block/festered_soil_sources.json` defaults to `#minecraft:dirt` (dirt, grass, podzol, mycelium, mud, moss and the rest of vanilla's tag, plus `remission:festering_grass`, which is in that tag); packs extend it (other festering blocks, modded soils) or replace it with `"replace": true`, and override or drop the modifier as for host samples.

## Recipe types (planned, P7.2)

`remission:analyzing`, `remission:sequencing`, `remission:synthesizing`, files `data/<ns>/recipe/<name>.json`:

```json
{ "type": "remission:analyzing", "ingredient": {"item": "remission:host_tissue"}, "fluid": {"id": "remission:miasma", "amount": 250}, "results": [{"id": "remission:data_fragment", "count": 1}], "energy": 2000, "time": 100 }
```

1.21.1 ingredient objects are `{"item": ...}`/`{"tag": ...}`; results `{"id", "count"}`. Final fields are fixed in P7.2.

**KubeJS schemas:** Remission ships `data/remission/kubejs/recipe_schema/<type>.json` (keys with `name`, `role` input/output/other, `type` such as `ingredient`, `item_stack`, `ticks`, `optional`, `function_names`) so KubeJS 2101 can create and edit these recipes without a Java dependency on KubeJS. Model them on KubeJS's own `data/minecraft/kubejs/recipe_schema/cooking.json`. Recipe schema changes are contract changes.

## Advancement triggers and stats (built, P8.1)

Criterion triggers registered in `RemissionTriggers` (`quest/` classes), fired from `quest/QuestProgress` on the server after the action succeeded. Every trigger takes the vanilla optional `player` (entity predicate or loot conditions); the rest are optional and an empty `conditions` (or none) matches every firing. Fake players (machines acting as players) fire nothing and earn no stats.

| Trigger | Fires when | Conditions |
| --- | --- | --- |
| `remission:nidus_destroyed` | A player breaks a `remission:nidus` (survival or creative, harvested or not; `onDestroyedByPlayer`). Other removals (explosions, `/setblock`, pistons, edits) post `NidusDestroyedEvent` but fire no trigger. | `location`: vanilla location predicate, tested at the centre of the nidus's block after removal (a `block` or `fluid` predicate sees what replaced it). |
| `remission:host_scanned` | A player takes a host scan with the scanner (a host; a non-host gives no scan and no trigger). | `entity`: entity predicate (or loot conditions) on the scanned host; `stage`: int range, the host stage scanned; `bestiary_types`: int range, the number of host types in the player's bestiary after this scan (`Bestiary`; types it does not list are not counted). |
| `remission:block_scanned` | A player takes a block scan with the scanner. | `location`: vanilla location predicate at the centre of the scanned block (its `block` predicate sees the scanned block). |
| `remission:viral_load_changed` (P5.1) | The player's viral load changed (Viral load, above): at most once per update (once a second) plus once per drink, never when only the trend changed, nor for the reset on death. | `viral_load`: float range, the new viral load (0–100): `{"min": 50}` for a player who reached 50, `{"max": 0}` for one whose viral load fell back to 0. |
| `remission:surge_survived` (P4.3) | A surge ends (any reason) and the player was caught in it: inside its area at one of its 20-tick checks while it ran, not dead since, and online and alive at the end (Surges and miasma clouds, below). A surge stopped during its warning never started and awards nothing. | `intensity`: int range, the surge's intensity (1–10). |

```json
{"criteria": {"nidus": {"trigger": "remission:nidus_destroyed"}}}
{"criteria": {"cow": {"trigger": "remission:host_scanned", "conditions": {"entity": {"type": "minecraft:cow"}, "stage": {"min": 2}}}}}
{"criteria": {"ten": {"trigger": "remission:host_scanned", "conditions": {"bestiary_types": {"min": 10}}}}}
{"criteria": {"found": {"trigger": "remission:block_scanned", "conditions": {"location": {"block": {"blocks": "remission:nidus"}}}}}}
{"criteria": {"storm": {"trigger": "remission:surge_survived", "conditions": {"intensity": {"min": 2}}}}}
{"criteria": {"exposed": {"trigger": "remission:viral_load_changed", "conditions": {"viral_load": {"min": 50}}}}}
```

Custom stats (registry `minecraft:custom_stat`, holder `RemissionStats`, formatter `DEFAULT`), awarded at the same moments as the triggers; lang keys `stat.remission.<name>`:

| Stat | Counts |
| --- | --- |
| `remission:nidi_destroyed` | nidi the player broke (as `nidus_destroyed`) |
| `remission:hosts_scanned` | host scans taken (as `host_scanned`) |
| `remission:blocks_scanned` | block scans taken (as `block_scanned`) |
| `remission:surges_survived` | surges survived (as `surge_survived`; P4.3) |

Read them with `/scoreboard objectives add <name> minecraft.custom:remission.nidi_destroyed`, FTB Quests stat tasks (`stat: "remission:nidi_destroyed"`) or any stat reader. Packs define the advancements (Counterstrain: `data/counterstrain/advancement/` in its KubeJS datapack); Remission ships triggers only, no advancements. Gametests: `QuestTriggerTests` (each trigger and stat, conditions parsed from the JSON above); `SurgeTests` for `surge_survived` and its stat; `ViralLoadTests` for `viral_load_changed`.

"Variant" is reserved for resistant mutations; triggers and stats for systems that are not built yet arrive with them. P8.1 deferred surge survived to P4.3 and viral load to P5.1, which built them (P5.1 adds a trigger and no stat: viral load is a level, not a count); region % cleansed (P5.2) is still to come.

## Debug commands `/remission` (built, P2.3)

Registered in `commands/RemissionCommands` on `RegisterCommandsEvent`. Every `/remission` subcommand needs permission level 2 (command blocks, functions, server console, ops). Hosts change only through `Corruption.set`, so staging, sync and (from P4.4) API events behave exactly as for gameplay writes.

| Syntax | Effect | Result (`execute store result`) | Feedback |
| --- | --- | --- | --- |
| `/remission infect <targets> <level>` | `targets`: entity selector, any number; `level`: int 1–100. Sets every living target to `level` (may lower it) and restages it from `hosts.stageThresholds`. Non-living and `#remission:immune` targets are skipped. | Living, non-immune targets set | `commands.remission.infect.success.single` (name, level, stage) / `.multiple` (count, level); broadcast to ops |
| `/remission cleanse <targets>` | Sets every living target to level 0 (removes the attachment). Already clean targets count. | Living targets cleansed | `commands.remission.cleanse.success.single` (name) / `.multiple` (count); broadcast to ops |
| `/remission inspect <target>` | One entity. Reports its stored level and stage; changes nothing. | Strain level (0 if clean) | `commands.remission.inspect.success` (name, level, stage) or `commands.remission.inspect.clean` (name) |

Errors: `commands.remission.failed.no_living_targets` when `infect` or `cleanse` selects no living entity; `commands.remission.failed.immune` when every living target of `infect` is immune; `commands.remission.failed.not_living` (name) when `inspect` targets a non-living entity. Vanilla parse errors cover an out-of-range level and a multi-entity `inspect` selector. Example: `/execute store result score @s strain run remission inspect @e[type=minecraft:cow,limit=1,sort=nearest]`.

## Surge commands `/remission surge` (built, P4.3)

Registered with the `/remission` tree (`commands/SurgeCommands`), same permission level 2, acting on the source's dimension. Surges and their rules: Surges and miasma clouds, below.

| Syntax | Effect | Result | Feedback |
| --- | --- | --- | --- |
| `/remission surge start [pos] [intensity]` | `pos`: block position, default the source's position; `intensity`: int 1–10, default 1. Begins a surge's warning from the indexed nidus nearest `pos` among those whose area (spread reach) holds `pos`. Works whether or not `[surges] scheduled` is on. | 1 | `commands.remission.surge.start.success` (origin x, y, z, intensity, warning ticks); broadcast to ops |
| `/remission surge stop` | Ends the surge warning or running in this dimension now (`SurgeEndedEvent` reason `STOPPED` and survivors awarded if it had started; nothing if still warning). The next scheduled surge is due `intervalTicks` later. | 1 | `commands.remission.surge.stop.success`; broadcast to ops |
| `/remission surge status` | Reports the current surge or when the next is due; changes nothing. | the surge's intensity, or 0 with none | `commands.remission.surge.status.warning` / `.running` (origin x, y, z, intensity, ticks until it starts / ends), `.none` (ticks until due), `.unscheduled` (scheduled surges off) |

Errors: `commands.remission.surge.failed.running` when `start` finds a surge already warning or running in the dimension; `commands.remission.surge.failed.no_nidus` when no indexed nidus reaches `pos`; `commands.remission.surge.failed.none` when `stop` finds no surge. Example (an FTB Quests command reward, run as server): `/execute in minecraft:overworld run remission surge start 120 64 -40 2`.

## Quest-fired events (planned, P8.2)

Commands (`/remission nidus seed <pos>`, `/remission region advance <id>`) under the same `/remission` root, usable from FTB Quests command rewards and KubeJS. Starting a surge is P4.3's `/remission surge start` (above); P8.2 builds on it rather than replacing it. Document exact syntax here when built.

## Festering land: chunk attachment, nidus, spread config (built, P4.1)

Chunk attachment `remission:chunk_corruption` (`ChunkCorruption` in `com.cubiecode.remission.world`), saved inside the chunk's `neoforge:attachments` compound, omitted at level 0. Server only, not synced. Internal: packs read it through `CorruptionQueries` (Public API, below).

```json
{ "level": 42, "last_update_game_time": 1234000 }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `level` | int 0–100 | Strain level of the land. Only rises in phase 4; festering stages are derived from it (Festering stages, below). |
| `last_update_game_time` | long | Level game time of the last change; catch-up credits the passes missed since then (or since the nidus was indexed, if later). |

Block `remission:nidus` with block entity `remission:nidus` (no saved fields yet) and block item `remission:nidus`. Drops one `remission:nidus_core`, never itself (loot table `remission:blocks/nidus`; Miasma and samples, above), mineable with a hoe (`#minecraft:mineable/hoe`), cannot be pushed. Placing it (or loading a chunk that has one) indexes it; removing it in any way stops its spread and leaves its land as it is. Index: `data/remission_nidi.dat` in the dimension folder, `{ "nidi": [ { "pos": <BlockPos.asLong>, "since": <game time> }, ... ] }`. Packs place nidi as ordinary blocks (structures, `/setblock`, features); `/remission nidus seed` is P8.2.

Config `config/remission-common.toml`, section `[spread]` (all ints, applied from the next pass; budgets per server tick and level):

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `intervalTicks` | 400 | 20–72000 | Game ticks between passes (passes start at multiples of level game time). |
| `nidusGrowth` | 5 | 1–100 | Level the nidus chunk gains per pass, up to 100. |
| `spreadStep` | 2 | 1–100 | Level another chunk in reach gains per pass from its neighbour toward the nidus. |
| `falloff` | 10 | 1–100 | A chunk `d` chunks (Manhattan) from a nidus reaches at most `100 - d * falloff` through it. |
| `spreadThreshold` | 40 | 1–100 | Level a chunk needs before it passes the Strain outward. Reach is the largest `d` with target(`d`) > 0 and target(`d - 1`) ≥ threshold: 7 by default. |
| `chunkBudget` | 256 | 1–65536 | Chunk visits per tick for the live pass; a longer pass continues next tick. |
| `catchUpBudget` | 32 | 1–4096 | Reloaded chunks fast-forwarded per tick; the rest wait in a queue. |

Rules: spread runs chunk to chunk from the parent neighbour (one step toward the nidus along the larger offset axis), so cancelling one step blocks everything behind it while the blocked chunk is loaded (live passes and catch-up both hold a step whose loaded parent is below the threshold); overlapping nidi each add their step; unloaded chunks are never loaded or written, and an unloaded parent counts as at its target (so a firebreak chunk that unloads stops holding). Catch-up of a region that loads at once runs from the nidus outward whatever the load order: a chunk whose loaded, below-threshold parent is still queued catches that parent up first (at most radius deep, stopping at cycles between nidi), and each chunk is caught up once. Live passes skip a chunk until its queued catch-up has run, so a reload ends at the same level whether or not a pass came due first. Default pace: a nidus chunk reaches the threshold after 8 passes (about 2.7 minutes), its neighbours reach 90 after about 45 more, and the seventh ring starts festering after about 128 passes (about 43 minutes of play, two in-game days). These are first-pass numbers for balancing in the pack; Counterstrain ships its own values in `pack/config/remission-common.toml` (about 4× faster spread, near-immediate festering land infection).

Events: the spread engine posts `ChunkStageChangedEvent`, `NidusDestroyedEvent` and the cancellable `StrainSpreadAttemptEvent` (Public API, below).

## Festering stages, blocks and the fester biome (built, P4.2; caves P4.6)

A chunk's festering stage (`[festering] stageThresholds`, Public API below) turns its land, in `world/FesteringStages`, server side:

- **Blocks:** when a chunk's stage rises, every block in it with a `remission:festers_into` entry (below) whose `stage` is at or below the chunk's stage is replaced by the entry's block. Stages are cumulative: a chunk that jumps from 0 to 3 gets every entry up to 3 at once. The surface pass scans each column from its highest block (`WORLD_SURFACE` heightmap) down through trees, plants and water to 5 blocks into the ground, counting the first block that blocks motion and is not in `#minecraft:logs` or `#minecraft:leaves` as the first ground block. Block states keep every property both blocks share (log `axis`, leaf `distance`, `persistent` and `waterlogged`, grass `snowy`). Blocks with block entities are never replaced. Replacement uses block update flags 2 and 16 (sent to clients, no neighbour updates), so plants on festering grass stay and nothing drops.
- **Caves (P4.6):** after the surface pass, a cave pass replaces every block in the chunk, at any height, that faces air on at least one of its six sides (air, cave air or void air: cave walls, floors and ceilings, overhangs and the lower parts of cliff faces) and whose entry festers underground at the chunk's stage: `stage` and `underground_stage` (below) both at or below it. Rock with no air beside it and blocks without an entry (ores, dirt, gravel, sculk) stay, so mining stays viable. At or above Y=0 every such block festers; below Y=0 one in four does, picked by a hash of the block position (the same blocks in every world and on every rescan). The depth (Y=0) and fraction (1 in 4) are constants, not config. A side in a chunk that is not loaded counts as solid.
- **Biome flip:** at the last stage (the number of `stageThresholds`; 4 by default), after its blocks, every biome cell of the chunk (all heights) flips to its fester biome: the source biome's `remission:fester_variant` entry (below), else `remission:fester`. `remission:fester` and every biome a `fester_variant` entry names never flip again. Players who have the chunk get vanilla's `ClientboundChunksBiomesPacket` (as `/fillbiome` sends), so grass, foliage and water colours, fog and sky change without a relog; no Remission payload.
- **Budget and timing:** a stage rise (`ChunkStageChangedEvent`, live or catch-up) or a chunk load queues the chunk; up to 64 columns (or queued chunks skipped) are done per tick and level, so a chunk takes 4 ticks; the cave pass then does up to 8 block layers (or sections with no block that could fester skipped) per tick and level, so a chunk with stone in every section takes about 48 ticks. Chunk attachment `remission:festered_stage` (int, saved with the chunk, never at 0, server only) records the stage a chunk was last brought up to, written when its cave pass finishes: a queued chunk at or below it is skipped, and a chunk that unloaded or a server that stopped mid-way is redone (both passes change nothing twice) when the chunk loads again. Blocks placed later in a festered chunk stay until its stage next rises. Stages never fall in phase 4; nothing reverts (purification is P5.2).
- **Events:** `BlockFesteredEvent` once per replaced block (Public API, below). The biome flip posts no event; `ChunkStageChangedEvent` already marks the stage.

Blocks (block items too, no creative tab yet), `data/remission/loot_table/blocks/<id>.json`:

| Block | Replaces by default (stage) | Behaviour | Drops | Tool tag |
| --- | --- | --- | --- | --- |
| `remission:festering_grass` | `minecraft:grass_block` (1) | snowy state like grass; never spreads or decays; in `#minecraft:dirt`, so plants stay on it | dirt, itself with silk touch; festered soil chance at stage 2+ (P7.1) | `mineable/shovel` |
| `remission:festering_log` | `#minecraft:logs` (2) | pillar with `axis`; in `#minecraft:logs`, so leaves around it do not decay | itself | `mineable/axe` |
| `remission:festering_leaves` | `#minecraft:leaves` (2) | vanilla leaves (decay, `distance`, `persistent`); in `#minecraft:leaves` | sticks (vanilla leaf chances), itself with shears or silk touch | `mineable/hoe` |
| `remission:festered_stone` | `#minecraft:stone_ore_replaceables` (3): stone, granite, diorite, andesite | plain stone-like block | itself | `mineable/pickaxe` |
| `remission:festered_deepslate` (P4.6) | `#minecraft:deepslate_ore_replaceables` (3): deepslate, tuff | pillar with `axis` like deepslate (deepslate's hardness and sounds) | itself | `mineable/pickaxe` |

**Data map `remission:festers_into`** (on `minecraft:block`), `data/<ns>/data_maps/block/festers_into.json`, NeoForge data map file format (keys are block ids or `#tags`; `replace`, `remove` and conditions work as for any data map). Server side only, not synced, reloaded with `/reload` (already festered chunks are not rescanned; the next stage rise uses the new map). Remission's default (datagen `RemissionDataMapProvider`):

```json
{
  "values": {
    "minecraft:grass_block": { "block": "remission:festering_grass" },
    "#minecraft:logs": { "block": "remission:festering_log", "stage": 2 },
    "#minecraft:leaves": { "block": "remission:festering_leaves", "stage": 2 },
    "#minecraft:stone_ore_replaceables": { "block": "remission:festered_stone", "stage": 3 },
    "#minecraft:deepslate_ore_replaceables": { "block": "remission:festered_deepslate", "stage": 3 }
  }
}
```

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `block` | block id | required | What the block turns into. A block that maps to itself (festering logs and leaves are in the tags they replace) is left alone. |
| `stage` | int ≥ 1 | 1 | Lowest chunk festering stage at which it turns. Entries above the configured number of stages never apply. |
| `underground_stage` | int ≥ 0 | 3 | Lowest chunk festering stage at which it also turns in the cave pass (where it faces air), never below `stage` (the higher of the two applies). `0` turns cave festering off for the entry; the surface pass still applies. (P4.6) |

A pack adds modded blocks (`"biomesoplenty:origin_grass_block": {"block": "remission:festering_grass"}`), points a block at its own festering block, removes an entry (`"remove": ["#minecraft:deepslate_ore_replaceables"]`), or tunes caves: `{"block": "remission:festered_stone", "stage": 3, "underground_stage": 0}` on `#minecraft:stone_ore_replaceables` keeps caves in stone clean, and `"underground_stage": 4` delays them to the last stage.

Changed in P4.6: P4.2 shipped one `#minecraft:base_stone_overworld` entry (stone, granite, diorite, andesite, tuff and deepslate to festered stone). It is now the two ore-replaceable tags above, so deepslate gets its own block, tuff festers into festered deepslate, and a modded stone that is only in `#minecraft:base_stone_overworld` no longer festers by default (add it to one of the two tags or give it an entry). A pack that removed `#minecraft:base_stone_overworld` removes the two new keys instead. Where several entries cover a block, the one in the file loaded last (datapack order) wins, whether it names the block or a tag; within one file, list a block only once (directly or through one tag), because the order of a file's values is not defined.

**Biome `remission:fester`** (`data/remission/worldgen/biome/fester.json`, datagen `RemissionBiomeProvider`; lang `biome.remission.fester`): land only flips to it after generating, so it has no features or carvers. Rain, temperature 0.8, grass colour `#B0A07E`, foliage `#A08898`, water `#5E4A6E` (fog `#2B1F36`), sky `#8F84A3`, fog `#A597B0`, cave mood sound; spawns as plains (farm animals and the common monsters). Packs may override the file (same id), but must keep the id: it is the fallback.

**Data map `remission:fester_variant`** (on `minecraft:worldgen/biome`), `data/<ns>/data_maps/worldgen/biome/fester_variant.json`: the fester biome a source biome flips to. Remission ships no entries (every biome flips to `remission:fester`); the pack uses it for Biomes O' Plenty and Terralith (P10.7). Same reload rules as `festers_into` (chunks already flipped keep their biome).

```json
{
  "values": {
    "#minecraft:is_badlands": { "biome": "counterstrain:fester_badlands" },
    "biomesoplenty:lavender_field": { "biome": "counterstrain:fester_lavender" }
  }
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `biome` | biome id | The fester biome for this source biome. It need not exist when the data loads; a flip into a biome that is not registered logs one warning and uses `remission:fester`. Every biome named here counts as a fester biome and never flips again. |

Fester biomes are ordinary datapack biomes (`data/<ns>/worldgen/biome/<id>.json`). A biome a chunk flips into only changes colours, sky, fog, music, spawns and precipitation; its features and carvers never run on flipped land.

## Festering veins and contact infection (built, P4.8)

**Block `remission:festering_vein`** (`world/FesteringVeinBlock`, a vanilla `MultifaceBlock` like glow lichen and sculk veins; block item too): one boolean state property per face (`north`, `east`, `south`, `west`, `up`, `down`; true where the vein covers the neighbouring block's face on that side). No collision, replaceable (blocks can be placed into it), hardness 0.2, `mineable/hoe`, destroyed when pushed by a piston. A face drops off when the block behind it stops holding it, and a vein with no face turns to air. Drops one `remission:festering_vein` per face with silk touch and nothing otherwise (`data/remission/loot_table/blocks/festering_vein.json`, vanilla `createMultifaceBlockDrops`). It never spreads on its own.

**Growth** (`world/FesteringVeins`, `VeinGrowth`, server side): in a chunk at or above `[festering] veinMinStage`, every air block (air, cave air) of the chunk, at any height, gets a vein face on each side whose neighbour holds a multiface block (`MultifaceBlock.canAttachTo`: a full support or collision face toward it, so stone, dirt, planks, glass and leaves do, slabs from below, fences and torches do not), is not air or a vein, and is not in `#remission:vein_resistant`, where the face is **selected** for the chunk's coverage. Nothing is replaced: veins only go into air, so player builds, plants and water are never overwritten, and block entities are never touched. A neighbour in a chunk that is not loaded is left for a later visit.

- **Coverage:** `15 * (stage - veinMinStage + 1)` percent of eligible faces, at most 60 (constants `COVERAGE_PER_STAGE`, `MAX_COVERAGE`, not config): with the defaults 15% at stage 2, 30% at 3, 45% at 4. Selection is a hash of the block position and side, scaled by a per-4×4×4-cell weight (0–2, by the cell's hash) so veins grow in patches; the same blocks at the same stage always get the same veins, in any world, scan order or reload, and a higher stage keeps every face of a lower one.
- **Budget and timing:** a chunk load or a stage rise (`ChunkStageChangedEvent`) queues the chunk; each tick the level's pass walks up to 8 block layers of the current chunk bottom to top (`LAYER_BUDGET`), and a section that holds no air, or only air with only air above, below and beside it, costs one unit and is skipped (a typical overworld chunk takes about 20 ticks). A chunk that finished is visited again 1200 ticks later (`REVISIT_TICKS`) while it stays loaded, so a wall a player builds or a face mined open in a festering chunk is overgrown within about a minute. A chunk that unloads mid-way starts again from its load event; one that only drops below full status resumes when it is back. Nothing is saved: a chunk that loads at a higher stage gets that stage's veins at once (catch-up). Frozen ticks (`/tick freeze`) grow nothing.
- **Events:** `BlockFesteredEvent` once per block that gained faces (old state air or a vein, new state the vein; Public API, below).

Block tags, `data/<ns>/tags/block/<name>.json`:

| Tag | Default | Meaning |
| --- | --- | --- |
| `#remission:vein_resistant` | empty | Blocks veins never grow on (a face toward one is never added). The pack fills it, for example with a ward block or a sealed base material. Veins already on a block stay if it is added later. |
| `#remission:infectious` | `festering_grass`, `festering_log`, `festering_leaves`, `festered_stone`, `festered_deepslate`, `festering_vein` | Blocks that infect mobs by contact (below), anywhere, festering chunk or not. |

**Contact infection** (`infection/Contact`, `ContactTracker`, `ContactInfection`, server side): a `Mob` is in **contact** when a block in `#remission:infectious` reaches its bounding box grown by 0.001 blocks (standing on it, inside it, or pressed against it; the block's outline shape counts, so a floor vein touches what stands in its block), or when water (`#minecraft:water` fluid) reaches into its bounding box and the mob's chunk is at or above `[infection] festeringLandMinStage` (fish, squid, drowned, axolotls, anything swimming or wading; the water itself is not replaced). The query `Contact.of(entity)` (`Kind.NONE`, `BLOCK`, `WATER`) and `Contact.inContact(entity)` work for any entity, players included, read loaded chunks only and are what a later item (viral load) can reuse; they are internal, not API.

- Cadence: one `ContactTracker` per server level on `LevelTickEvent.Post`. Every 10 ticks a pass snapshots the level's mobs and checks up to 64 per tick, ending the tick early once 32 have become hosts (`ContactSettings.INTERVAL_TICKS`, `MOB_BUDGET`, `INFECTION_BUDGET`; not configurable), so a touch shorter than 10 ticks can go unseen. Each checked mob in contact gains the game ticks since the previous pass started (nothing on the first pass); one without contact loses them. Mobs in chunks that are not entity-ticking are skipped.
- Eligibility is festering land infection's (`Infection.canInfectFromFesteringLand`): not immune, not a host, and its profile does not set `conversion.festering_land` to false; an ineligible mob's contact is cleared. **Players never take contact infection** (only `Mob`s are checked); viral load (P5.1) is the player system.
- Infection: at `contactTicks` the contact is cleared and the mob becomes a host at the first host stage through `Infection.infectFromContact`, in place (not healed) or through its profile's `convert_to`, with `CorruptionCause.CONTACT` on `HostCorruptedEvent`. Festering land exposure (above) is unchanged and runs alongside: the two counters are separate.

Entity attachment `remission:contact_exposure` (`RemissionAttachments.CONTACT_EXPOSURE`): int ticks ≥ 0, saved with the mob (skipped at 0), server only, not synced or copied on conversion.

Config `config/remission-common.toml` (read at the start of each pass):

| Section | Key | Type | Default | Meaning |
| --- | --- | --- | --- | --- |
| `[festering]` | `veins` | bool | true | Vein growth on or off. Off grows nothing more; grown veins stay. |
| `[festering]` | `veinMinStage` | int 1–8 | 2 | Lowest chunk festering stage that grows veins. Above the number of stages, none do. |
| `[infection]` | `contact` | bool | true | Contact infection on or off. Off freezes every mob's contact. |
| `[infection]` | `contactTicks` | int 0–72000 | 100 | Contact (game ticks, net of decay) at which a mob becomes a host; 0 infects on the first pass that finds it in contact. Water uses `festeringLandMinStage` as its minimum stage. |

Cost (foundry, `VeinBenchmark`, `REMISSION_VEIN_BENCHMARK=1`): see docs/ARCHITECTURE.md (Festering veins and contact infection).

## Surges and miasma clouds (built, P4.3)

A surge belongs to one indexed nidus (its origin); its **area** is the chunks within that nidus's spread reach (Manhattan, `SpreadSettings.radius()`, 7 with the default `[spread]` config). At most one surge per dimension. Phases: **warning** (`warningTicks`; title, subtitle, raid horn and a yellow boss bar for players in the area), then **running** (`durationTicks`; purple boss bar), then ended by its duration (`ELAPSED`), its nidus leaving the index (`NIDUS_DESTROYED`) or a stop (`STOPPED`). While running: the origin nidus's live spread steps and its own chunk's growth are multiplied by `spreadMultiplier × intensity` before `StrainSpreadAttemptEvent` (catch-up is never multiplied), a wave of hosts comes every 200 ticks near each player in the area, and when it starts it releases miasma clouds. Players in the area while it runs are caught in it; dying drops a player out of that surge for good; at the end the caught players still online earn `remission:surge_survived` and `remission:surges_survived`.

Config `config/remission-common.toml`, section `[surges]` (applied when next read; counts per unit of intensity):

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `scheduled` | true | bool | Whether surges come due on their own. Commands and the API start them either way. |
| `intervalTicks` | 24000 | 1200–2400000 | Game ticks from the end of a surge (or the first tick of the level) until the next is due. A due surge starts once a player (alive, not a spectator) is in some nidus's area, checked every 20 ticks; the origin is chosen among those nidi by a hash of the game time. |
| `warningTicks` | 600 | 0–12000 | Warning before a surge starts. |
| `durationTicks` | 2400 | 200–72000 | How long a started surge runs. |
| `spreadMultiplier` | 4 | 1–100 | Factor on the origin's spread steps, times the intensity. |
| `hostsPerWave` | 2 | 0–32 | Hosts tried near each player in the area per wave (every 200 ticks, the first when the surge starts), times the intensity. None on Peaceful. Spawned at most 2 per tick. |
| `hostLevel` | 50 | 1–100 | Strain level of surge hosts (stage 2 with the default `[hosts] stageThresholds`). |
| `maxHosts` | 16 | 0–256 | Most hosts one surge keeps alive (and loaded) at once; waves spawn none while it has this many. |
| `clouds` | 3 | 0–32 | Miasma clouds released when a surge starts, times the intensity, at most 32. |

Host spots: a column 8–20 blocks from the player in a loaded chunk inside the area at festering stage 1 or higher; in it the spot nearest the player's height within 16 blocks with a sturdy floor, two blocks of room, no other living entity in the way and the type's spawn placement. A covered spot (no sky above) also needs the type's own spawn rules for an event spawn (`MobSpawnType.EVENT`): dark enough for monsters, grass and light for animals. So a player on the surface meets hosts on the surface and a player in a cave meets them on the cave floor. The mob is finalised as an event spawn, then set to `hostLevel` with `CorruptionCause.SURGE` (riders too). Types: `#remission:surge_hosts` (above).

Entity `remission:miasma_cloud` (misc category; `/summon remission:miasma_cloud ~ ~8 ~ {Radius:5.0f,Heading:90.0f}` works; saved with its chunk):

| NBT | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Radius` | float 0.5–16 | 4 | Horizontal half-size; the area is the bounding box, `2 × Radius` wide and 3 blocks tall from its position (the base). Synced. |
| `Heading` | float degrees | 0 | Drift direction as entity yaw: 0 south (+z), 90 west, 180 north, 270 east. |
| `Age` | int | 0 | Ticks lived. |
| `Lifetime` | int ≥ 1 | 3600 | It dissipates (is removed) when `Age` reaches this. |

Released clouds sit over a random column of a random loaded festering chunk within 3 chunks of a player in the area (or of the origin when none is), 8 blocks above the highest motion-blocking block, radius 4–6, lifetime 3600, random heading. A cloud drifts 0.04 blocks per tick along its heading, eases toward 8 blocks above the ground beneath it (0.05 blocks per tick at most), and never drifts over land that is not festering: if the next step would leave a loaded chunk at stage 1 or higher it turns right, left or back (first that stays), and holds still if none does. It cannot be attacked, pushed or collided with and ignores explosions. Players whose bounding box overlaps its bounding box gain viral load (`[viral_load] cloudGain`, P5.1); it does nothing to mobs.

Saved data `data/remission_surges.dat` in the dimension folder (internal; read surges through the events and `/remission surge status`): `next_due` (game time) and `current` (`origin`, `intensity`, `warning_time`, `start_time`, `end_time`, `started`, and UUID lists `participants`, `fallen`, `hosts`).

Events: `SurgeStartedEvent`, `SurgeEndedEvent` (Public API, below). Commands: Surge commands, above.

## Item contamination (planned, P10.6)

Data component `remission:contaminated` on item stacks carried through festering chunks, a tag for containers that protect their contents, and a decontamination recipe type or KubeJS hook. Fields fixed in P10.6.

## Dispersal payload (planned, P9.2)

Block `remission:dispersal_payload` deploys the Counterstrain. The planned API is a dispersal source (level, position, radius, rate) ticked by its holder. The placed block is one holder; the optional Create integration (P10.6) ticks it from a moving contraption. The API must not assume a block. Fields are fixed in P9.2.

## Public API events and queries (built, P4.4)

Package `com.cubiecode.remission.api`; nothing outside it is public (the `corruption`, `world` and other packages are internal, even where their classes are `public`). Java names may use "corruption".

Conventions, for every event here and every event later items add:

- NeoForge events on `NeoForge.EVENT_BUS`, posted on the logical server, server thread only. Nothing is posted on the client.
- Posted after the state change is applied, so `CorruptionQueries` in a listener already returns the new state. The one exception is the cancellable `StrainSpreadAttemptEvent`, posted before its step.
- Batched changes (catch-up of a reloaded chunk, later bounded stage batches) post one event per chunk, never one per block or per missed pass, unless the event is block-scoped.
- Base classes are abstract and cannot be listened to: `HostEvent` (extends NeoForge `LivingEvent`; `getEntity()` is the `LivingEntity`) and `FesteringLandEvent` (extends `Event`; `getLevel()` is the `ServerLevel`). Listen to the concrete events. `ViralLoadChangedEvent` (P5.1) extends NeoForge's `PlayerEvent` directly.
- Levels are ints 0–100 (`CorruptionQueries.MAX_LEVEL`) for entities and chunks; viral load is a float 0–100 (`CorruptionQueries.MAX_VIRAL_LOAD`). Host stages come from `[hosts] stageThresholds`, chunk (festering) stages from `[festering] stageThresholds`.

| Event | Fields (getters) | Fired | Cancellable |
| --- | --- | --- | --- |
| `HostCorruptedEvent extends HostEvent` | `getEntity()`, `getOldLevel()`, `getOldStage()`, `getNewLevel()`, `getNewStage()`, `getCause()`; `wasHost()`, `isHost()`, `becameHost()` | After `Corruption.set` raises an entity's level (or, at the same level, its stage after a threshold change), with attachment, host attributes and host goals already applied. Every increase, not only the first; `becameHost()` marks the non-host to host change. Never for immune entities (their state does not change) or no-op sets. | no |
| `HostCleansedEvent extends HostEvent` | as above; `isFullyCleansed()` (new level 0) | After `Corruption.set` lowers an entity's level (or stage), including partial cleansing. | no |
| `ChunkStageChangedEvent extends FesteringLandEvent` | `getLevel()`, `getChunk()` (`ChunkPos`), `getOldLevel()`, `getNewLevel()`, `getOldStage()`, `getNewStage()` | After the spread engine stores a chunk level whose festering stage differs from the old one: live passes and catch-up. A catch-up posts once per chunk, so stages can jump by more than one; changes within a stage post nothing. | no |
| `NidusDestroyedEvent extends FesteringLandEvent` | `getLevel()`, `getPos()` (the nidus block) | After a nidus leaves the level's index: its block was broken, replaced or removed, or a spread pass found an index entry without its block. Its land keeps its level. No player is passed; quests use the `remission:nidus_destroyed` trigger (Advancement triggers and stats, above). To protect a nidus, cancel the block break. When the block was removed, it is posted from the block's `onRemove` inside `setBlockState`: listeners must not set the block at `getPos()` in the listener (defer it). | no |
| `BlockFesteredEvent extends FesteringLandEvent` (P4.2) | `getLevel()`, `getPos()`, `getOldState()`, `getNewState()` | After a block in a festering chunk is replaced through `remission:festers_into` (Festering stages, above), or gains festering vein faces (P4.8; old state air or a vein), once per block: it is block-scoped, so a chunk's stage batch posts one per replaced block, spread over the ticks the batch takes. The biome flip posts nothing. Listeners must not set the block at `getPos()` in the listener. | no |
| `SurgeStartedEvent extends FesteringLandEvent` (P4.3) | `getLevel()`, `getOrigin()` (the nidus), `getIntensity()` (1–10), `getEndTime()` (game time it is due to end) | When a surge's warning runs out and it starts, after its clouds are released and before its first wave (Surges and miasma clouds, above). Never for a surge stopped during its warning. | no |
| `SurgeEndedEvent extends FesteringLandEvent` (P4.3) | `getLevel()`, `getOrigin()`, `getIntensity()`, `getReason()` (`ELAPSED`, `NIDUS_DESTROYED`, `STOPPED`; treat unknown values as `STOPPED`), `getSurvivors()` (immutable list of the `ServerPlayer`s awarded `surge_survived`) | When a started surge ends, after acceleration and waves stopped and survivors were awarded. Its clouds keep drifting. | no |
| `ViralLoadChangedEvent extends PlayerEvent` (P5.1) | `getEntity()` (the `ServerPlayer`), `getOldLoad()`, `getNewLoad()` (floats 0–100), `getCause()` (`EXPOSURE`: land, water or a miasma cloud; `DRINK`; `DECAY`; `OTHER`; treat unknown values as `OTHER`), `isRising()` | After a player's viral load changed and was stored (Viral load, above): at most once per player per update (once a second) plus once per drink. Not when only the trend changed, nor for the reset to 0 on death. | no |
| `StrainSpreadAttemptEvent extends FesteringLandEvent` | `getLevel()`, `getFrom()`, `getTo()` (`ChunkPos`), `getAmount()` (level gain at multiplier 1, capped at the target), `isCatchUp()`, `getRateMultiplier()`/`setRateMultiplier(double)` (default 1; negative or NaN become 0) | Before every chunk-to-chunk step: each live step into a loaded chunk below its target whose parent passed the threshold, and once per nidus in reach when a reloaded chunk catches up (`isCatchUp()`, `getAmount()` covers every missed pass, `getFrom()` may be unloaded). The growth of a nidus's own chunk is not a spread attempt, and a step from a loaded parent below the threshold is held without an event (live and catch-up). | yes: cancelling (or multiplier 0) stops this step; chunks reached through it stay clean while the cancelled chunk is loaded. A step from an unloaded parent is not held (the parent counts as at its target), so keep a firebreak chunk loaded or cancel the steps behind it too |

Spread rate: the gain is `amount * multiplier`, capped at the chunk's target. A fractional part is added as one more level with that probability, drawn from a hash of the two chunks and the game time, so slowed spread still advances on average and the same state and ticks give the same levels. Listeners compose by multiplying (`event.setRateMultiplier(event.getRateMultiplier() * 0.5)`). `last_update_game_time` moves to now only when a catch-up changed the chunk's level: passes shortened by a multiplier below 1 are then not credited again (the chunk ends below where full-rate spread would have taken it and continues from there), while a catch-up whose steps were all cancelled, scaled to 0 or held keeps the old time, so a later reload credits that history again, still capped at the target. A parent caught up first (see the rules above) contributes its level after its own catch-up, which converges on the same targets as live spread rather than replaying exact history.

Re-entrancy: a host event listener that changes the same entity's level (`Corruption.set`) posts a nested event at once; later listeners of the outer event then see its stale values while queries return the nested change. Defer such changes to a later tick.

`CorruptionCause` (enum, `HostEvent.getCause()`): `INFECTION` (killed by a host and infected in place or converted), `COMMAND` (`/remission infect` or `/remission cleanse`), `FESTERING_LAND` (prolonged exposure to festering land, P4.7), `SURGE` (spawned as a host by a surge; P4.3), `CONTACT` (touched an infectious block or swam in festering water; P4.8), `OTHER` (any other caller). Later items insert their causes before `OTHER` (purification, P5.2), so never persist a cause by ordinal; treat unknown values as `OTHER`.

Queries, `CorruptionQueries` (static):

| Method | Returns | Side |
| --- | --- | --- |
| `isHost(Entity)` | whether a living entity's stored host stage is ≥ 1; false for non-living entities | both (hosts are synced) |
| `hostLevel(Entity)`, `hostStage(Entity)` | stored level and host stage; 0 for clean or non-living entities | both |
| `chunkLevel(ServerLevel, ChunkPos)`, `levelAt(ServerLevel, BlockPos)` | Strain level of a loaded chunk (of the chunk holding the position); 0 if clean or not loaded (never loads a chunk) | server |
| `chunkStage(ServerLevel, ChunkPos)`, `stageAt(ServerLevel, BlockPos)` | festering stage of that level (every block in a chunk shares its chunk's stage) | server |
| `chunkStageFor(int level)`, `maxChunkStage()` | stage of a chunk level under the current config; the number of chunk stages | server config |
| `viralLoad(Player)` (P5.1) | the player's viral load, 0–100 (`MAX_VIRAL_LOAD`) | server for any player; a client knows only its own player's (others read 0) |

Config `config/remission-common.toml`, `[festering] stageThresholds = [1, 25, 50, 75]`: 1–8 ints in 1–100, order-independent. A chunk's stage is the number of thresholds its level meets (0 clean, 1–4 with the defaults; the default `festers_into` map turns grass at 1, trees and foliage at 2 and stone at 3, and the last stage flips the biome; Festering stages, above). Read whenever a level changes or a stage is queried; changing it posts no events for chunks whose stage moved.

Planned by later items, added here when built: block purified and chunk entered remission (P5.2).

| Event | Fired by | Payload (planned) | Cancellable |
| --- | --- | --- | --- |
| block purified | P5.2 | level, pos, old and new block state, cause | no |
| chunk entered remission | P5.2 | level, chunk pos, player (optional) | no |

## KubeJS event bindings (planned, P8.3)

The API events above exposed to KubeJS server scripts through an optional `integration/kubejs/` plugin (no hard KubeJS dependency), with cancellation and the spread multiplier writable from script. Event group and method names are fixed in P8.3 and mirrored in kubejs-scripting.
