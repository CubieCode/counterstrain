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
| `variant` | reserved | Resistant mutation (P7.5). Not written yet; unknown keys are ignored on load, so adding it is backward compatible. |

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

## Bestiary (P6.2; hidden types P6.3; team-shared P6.4)

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

Wire format (`BestiaryProgress.STREAM_CODEC`): var-int entry count, then per entry a resource location and a var-int stage. The client opens the bestiary with the key mapping `key.remission.bestiary` (category `key.categories.remission`, default M). The same codec carries the team copy below.

**Team-shared research (P6.4).** Research belongs to the team (DESIGN, Teams and multiplayer): the bestiary lists and counts the player's **team progress**, for each type the highest stage the player or any teammate (the team query, `TeamQueries`) has scanned. `remission:bestiary` stays each player's own record: a teammate's scan never writes it, so a player who leaves a team keeps exactly what they scanned and loses the rest. With nothing answering the team query the team progress is the player's own record, as in P6.2.

- **Offline teammates count.** `Bestiary` mirrors every player's own record into saved data `data/remission_bestiary.dat` in the overworld folder (`BestiaryRecords`: `{"players": {"<uuid>": <progress, shape above>}}`, empty records omitted) on each recorded scan and on login (which also adds players from before P6.4). Fake players are not mirrored. Internal, not a pack contract.
- **Synced copy.** Player attachment `remission:team_bestiary` (`BestiaryProgress`, same shape and wire format) holds the team progress and is present only while it differs from the player's own record, so a solo player has none. Not saved and not copied on death: the server recomputes it on login and respawn, for every online teammate when a member's record changes, and for every online player on the server tick after `TeamQueries.invalidate` or `invalidateAll` (a team source's join or leave). A team source that does not invalidate shows a change on the next of those refreshes. Synced to its owner only, as `remission:bestiary` (the initial tracking sync also reaches tracking clients). The client reads it through `Bestiary.team(player)`, falling back to the own record.
- **Totals** count only listed host types (`Bestiary.isHostType` now), so progress recorded for a since-hidden or removed type is kept but not counted.
- `remission:host_scanned` fires for the scanning player only; its `bestiary_types` condition is the team total after the scan (the number the player's bestiary header shows). Stat `remission:hosts_scanned` counts the scanner's own scans. Team queries for quests and scripts: `BestiaryQueries` (Public API, below).

Gametests: `BestiaryTests` (a teammate's scan in the other's bestiary and total, an offline teammate's total and login, separate teams, leaving a team, no team source).

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
- **Contact (P5.2):** while a block in `#remission:infectious` touches the player (standing on it, inside it or pressed against it; the contact query of Festering veins and contact infection, below), `contactGain`, anywhere, festering chunk or not, on top of land gain, no altitude factor. Water never counts as contact here: swimming in festering water is `waterGain` only. Since P5.7 this is the Strain hazard `remission:contact`: it adds nothing for a player whose team holds that countermeasure or for whom a `StrainHazardEvent` listener holds it back (Strain hazards and countermeasures, below).
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
| `contact` | bool | true | Whether touching an infectious block adds viral load (P5.2). |
| `contactGain` | number 0–6000 | 10.0 | Per minute while touching one (P5.2). |

With the defaults, standing on stage 4 land fills the meter in about 8 minutes (hunger after about 2), stage 1 land in about 100 minutes, and a full meter decays in 20.

Player attachment `remission:viral_load` (`RemissionAttachments.VIRAL_LOAD`, `ViralLoadState`): the load is codec-serialized with the player (`neoforge:attachments`, a float, skipped at 0); the trend (`STEADY`, `RISING`, `FALLING`, what the last update did) is not saved. Not copied on death. Not attachment-synced: the server sends payload `remission:viral_load` (`ViralLoadPayload`: float load, var-int trend ordinal) to its own player only, when the state changes and on login, respawn and dimension change (it bumped `RemissionNetwork.PROTOCOL_VERSION` to 2). The client stores it on the local player for the HUD meter and the query.

Damage type `remission:viral_load` (`data/remission/damage_type/viral_load.json`, datagen `RemissionDamageTypeProvider`): message id `remission.viral_load` (lang `death.attack.remission.viral_load`, `.player` when a killer is credited), scaling `never`, exhaustion 0; in `#minecraft:bypasses_armor` (datagen `RemissionDamageTypeTagsProvider`). Packs and revenants (P5.3) recognise viral load deaths by this type.

Client config `config/remission-client.toml`, `[hud] showViralLoad` (default true): the meter (an icon, a bar whose colour steps up every quarter, an arrow while rising or falling) shows one row above the hunger bar, stacked like vanilla's rows, while viral load is above 0, in survival and adventure. Presentation only.

Trigger `remission:viral_load_changed` (Advancement triggers and stats, below) and `ViralLoadChangedEvent` and `CorruptionQueries.viralLoad` (Public API, below). Gametests: `ViralLoadTests`.

## Revenants (built, P5.3)

`com.cubiecode.remission.revenant`, server authoritative. A **Strain death** is a player's death (not a spectator, `keepInventory` off) where the damage type is `remission:viral_load`, or the killer (the damage's responsible entity, else the player's kill credit) is a host or a revenant, or the player is in a chunk at festering stage 1 or higher at any height (`CorruptionQueries.stageAt`). On a Strain death Remission takes, at `EventPriority.LOWEST`, the player's experience drop (`LivingExperienceDropEvent`, vanilla's 7 per level up to 100) and every item drop in `LivingDropsEvent` (main inventory, armour and offhand, and whatever other mods add there first: Curios adds its slot items at `HIGHEST`, honouring each item's drop rule), cancels both, and raises a revenant holding exactly those stacks and that experience. A death with nothing to drop raises nothing; if another listener cancelled the drops (a grave mod), the experience is dropped as vanilla would. The drops are cancelled only once the revenant has joined the level: if it cannot (`addFreshEntity` refused it, such as a cancelled `EntityJoinLevelEvent`), the items drop as vanilla, the experience is awarded back, nothing is indexed and no risen event is posted. A Strain death is remembered only within the tick it happened, so a death a later listener cancelled never turns the player's next death into a revenant. Every other death, and every death with `keepInventory`, is vanilla.

Entity `remission:revenant` (`Revenant`, category `monster`, 0.6 × 1.8, fire immune, not a natural spawn, no spawn egg):
- **Spawn:** rises at the death position, facing as the player did. Below the world's minimum height it rises on the top block of that column (`MOTION_BLOCKING`); over an empty column, on the End's obsidian platform spawn (`ServerLevel.END_SPAWN_POINT`, 100 50 0) in the End, at the world spawn in the overworld, and otherwise one block above the minimum height in the death column (`Revenants.riseSpot`). A revenant put back home over a column with no block at all hovers there (no gravity) instead of falling into the void again.
- **Owner and gear:** Owner UUID and name are synced entity data; it is named "Revenant of <name>" (lang `entity.remission.revenant.named`; `entity.remission.revenant` when it has no owner). It wears copies of the player's armour pieces and main-hand item that are among its stacks (drop chance 0) and fights with them; the stacks it holds are separate, so what it drops is exactly what it took.
- **Strain level:** Its Strain level is the player's viral load at death, at least the first `[hosts] stageThresholds` level, set with `CorruptionCause.REVENANT`; host goals, attributes, overlay and infection on kill are the ordinary host ones.
- **Attributes:** max health 20, speed 0.25, attack damage 2 (plus its weapon's modifiers), follow range 16; armour from its worn pieces.
- **Damage and persistence:** It never despawns (persistent, also on Peaceful), never changes dimension, cannot drown, and takes damage only when the damage's responsible entity is a player (melee, projectiles, anything a player caused) or from `/kill` (`minecraft:generic_kill`): fire, lava, falls, drowning, suffocation, mobs, magic and the void cannot hurt it. Below the world (`onBelowWorld`) it is put back at its home.
- **Leash:** `restrictTo(home, 8)` (`Revenant.LEASH_RADIUS`), a target more than 12 blocks from home is dropped, and beyond 16 blocks (`RETURN_RADIUS`) it is put back home at once.
- **Death:** Killed, it drops its stacks at its position (whatever `doMobLoot` says; no loot table, so no host loot modifiers) and exactly its experience.

Saved fields (entity NBT, besides vanilla mob data and the `corruption` attachment): `Owner` (UUID int array), `OwnerName` (string), `Home` (int array block position), `Contents` (list of item stacks, vanilla 1.21.1 item stack format), `Experience` (int).

Pickup: each stack a slain revenant drops carries the item-entity attachment `remission:revenant_drop` (`RevenantDrop`: `owner` UUID, `dropped_at` and `free_at` game times; saved, server only) and gets vanilla's never-pickup delay (mobs and vanilla pickup skip it, it never merges) and unlimited lifetime. A player touching it may pick it up from `dropped_at + 10` ticks (`Revenants.PICKUP_DELAY_TICKS`) when they are the owner or one of the owner's teammates (`TeamQueries.areTeammates(player, owner)`, Team query, P4.12; the owner may be offline). Strangers cannot, until the timeout. At `free_at` (`dropped_at + pickupTimeoutTicks`) the item is freed, on the next touch or its next 20-tick check: the attachment is removed, the pickup delay cleared and the item despawns about 10 minutes later (vanilla's extended lifetime). Hoppers and other block collectors take item entities without any check and can take restricted items. Experience orbs are not restricted.

Config `config/remission-common.toml`, `[revenants]`:

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `pickupTimeoutTicks` | int 0–1728000 | 12000 | Ticks after a slain revenant's drop during which only its owner and the owner's teammates may pick its items up; then anyone can. Read when the revenant dies. |

Saved data `remission_revenants` (`RevenantIndex`, `data/remission_revenants.dat` in each dimension folder): list `revenants` of `{revenant: UUID, owner: UUID, owner_name: string, pos: long block position}` in rise order. A revenant adds itself when it rises, updates `pos` whenever its block position changes while loaded, and leaves when it dies or is discarded (not when its chunk unloads), so the index lists revenants in unloaded chunks. Entries leave only through the revenant's own removal; a revenant lost without one (its chunk deleted or regenerated by an external tool, a corrupted save) leaves a stale entry, so readers (the tracker, P5.4) must tolerate entries whose revenant never loads again. `RevenantIndex.all(server)` and `ofOwner(server, uuid)` read every loaded dimension; the revenant tracker (P5.4) reads it.

Curios (optional, `integration/curios/`, `compileOnly`): no Curios code is needed for Curios items to go into a revenant (above); the integration only gives gametests a slot to fill.

Trigger `remission:revenant_reclaimed` and stat `remission:revenants_reclaimed` (Advancement triggers and stats, below); `RevenantRisenEvent`, `RevenantSlainEvent`, `CorruptionCause.REVENANT` and `CorruptionQueries.isRevenant` (Public API, below). Who counts as a teammate is the team query's answer (FTB Teams parties through `integration/ftbteams/` when FTB Teams is loaded; packs answer `TeamLookupEvent` otherwise), so revenants need no pack script. Gametests: `RevenantTests`.

## Strain hazards and countermeasures (built, P5.7)

`com.cubiecode.remission.countermeasure` (internal) behind the API class `StrainHazards`; server authoritative. Rules: DESIGN "Hazards and countermeasures".

**Hazard IDs** are plain resource locations with no registry: any id works, and integrations use their own namespace. Remission's: `remission:contact` (contact viral load, P5.2; built), reserved `remission:shedding` (P5.6), `remission:fouling` and `remission:rot` (P7.7), `remission:containment` (P7.8), `remission:contamination` (P10.6). Display name: lang key `hazard.<namespace>.<path>`, falling back to the id (`StrainHazards.displayName`). Danger (surges, nidi, hosts, variants, budding) is never a hazard.

**Player attachment `remission:countermeasures`** (`CountermeasureSet`): a list of hazard ids, `["remission:contact"]`, sorted, inside the player's `neoforge:attachments`; omitted when empty; copied on death; synced to its owner through NeoForge attachment sync (wire format: a var-int count, then each id as a string; no custom payload). Saved data `remission_countermeasures` (`data/remission_countermeasures.dat` in the overworld folder): `players`, a map of player UUID (string) to the same list, a copy of every player's set written on each change and on login, so a team's countermeasures are known while its players are offline; `revoked` (omitted when empty), the same map of pending revocations: countermeasures revoked from a team while that member was offline, removed from their attachment at their next login unless the team holds them again by then, then cleared. Edit countermeasures with the command or the API, never the files.

**Team sharing** (the team query, P4.12): a grant reaches the player's online teammates; a player who logs in, or whose team changes (`TeamQueries.invalidate` or `invalidateAll`, which team sources call), pools countermeasures with their online teammates on the next server tick, both ways. A team **holds** a countermeasure when any player in the saved data who is a teammate (online or not) holds it.

**Queries** (`StrainHazards`, server thread unless noted):

| Method | Returns |
| --- | --- |
| `applies(ServerPlayer, ResourceLocation hazard)` | Player hazards: false when the player or their team holds the countermeasure; otherwise false when a `StrainHazardEvent` listener cancels; otherwise true. |
| `appliesAtBlock(ServerLevel, BlockPos, @Nullable UUID placer, ResourceLocation hazard)` | Block hazards: false unless the presence rule of `blockHazards` (below) is met; then false when the placer's team holds the countermeasure; then the event. A block with no known placer needs any non-spectator player in the level within 128 blocks (`Countermeasures.NEARBY_RADIUS`) whatever the mode, and the nearest such player's team stands in for the placer's. |
| `holds(Player, hazard)`, `countermeasures(Player)` | The player's own countermeasures (both sides; a client knows only its own). |
| `teamHolds(MinecraftServer, UUID, hazard)` | Whether that player (online or not) or a teammate holds it. |
| `grant(ServerPlayer, hazard)`, `revoke(ServerPlayer, hazard)` | Grant to the player and their online teammates (cause `OTHER`), or take it from their whole team (offline members at their next login, as the revoke command); the number of players changed. |
| `displayName(hazard)` | The hazard's name component (either side). |

**Events** (Public API, below): `StrainHazardEvent` (cancellable) and `CountermeasureGrantedEvent`.

Config `config/remission-common.toml`, `[countermeasures]`:

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `blockHazards` | `TEAM_ONLINE`, `PLAYER_NEARBY`, `ALWAYS` (case-insensitive) | `TEAM_ONLINE` | When block hazards may strike a placed block: while a player of the placer's team is online anywhere; while one is in the block's level within 128 blocks; or always. Read on every block query. |

**Item `remission:countermeasure_protocol`** with data component **`remission:hazard`** (a hazard id, saved and synced): using it grants that countermeasure to the user and their online teammates and uses one up; a protocol for a countermeasure the user's team already holds (offline members included), or without the component, does nothing. Its tooltip names the hazard. Give one with `/give @p remission:countermeasure_protocol[remission:hazard="remission:contact"]`. Default recipes (Machine recipes, above): `remission:synthesizing/countermeasure_protocol_<hazard path>`, one per built Remission hazard; packs override or remove them by id and add their own for any hazard:

```json
{
  "type": "remission:synthesizing",
  "ingredients": [{ "item": "remission:strain_sequence" }, { "item": "remission:festered_soil" }, { "item": "minecraft:paper" }],
  "fluid_ingredient": { "fluid": "remission:miasma", "amount": 1000 },
  "results": [{ "id": "remission:countermeasure_protocol", "count": 1, "components": { "remission:hazard": "remission:contact" } }],
  "energy": 8000,
  "time": 400
}
```

Trigger `remission:countermeasure_gained` and stat `remission:countermeasures_gained` (Advancement triggers and stats, below); `/remission countermeasure` (below).

## Hazard notices and the hazards tab (built, P5.8)

Every hazard is visible (DESIGN "Hazards and countermeasures"). `com.cubiecode.remission.notice` (internal) behind the API class `HazardNotices` and three methods appended to `StrainHazards`; server authoritative.

**Reporting a strike** (`HazardNotices`, server thread): `report(ServerLevel level, ResourceLocation hazard, BlockPos pos, @Nullable UUID placer)`, called by a hazard system after its hazard struck a block (fouling, rot and containment breaches report here). Recipients: the placer and their online teammates (the team query), so an offline placer's online team is still told; with no known placer, the nearest non-spectator player in the level within 128 blocks (`Countermeasures.NEARBY_RADIUS`) and their online teammates; nobody else. A report never asks whether the hazard applies (that is `StrainHazards.applies`/`appliesAtBlock`, asked before striking).

**Grouping and rate limit** (`notice.HazardNoticeTracker`, constants, no config): per recipient, a report joins the open group of the same hazard and dimension whose first strike is within `GROUP_RADIUS` (32) blocks, or opens one. A player has at most 32 open groups (`MAX_GROUPS`): past that, a report joins the nearest open group of its hazard (same dimension first), which then covers more than one area, and a hazard with no open group still opens one, so no report is dropped. A group is sent `GROUP_TICKS` (100, 5 s) after it opened, but never sooner than `COOLDOWN_TICKS` (600, 30 s) after that player's last notice of the same hazard; repeats inside the cooldown keep collecting. A notice names the group's first strike position, its strike count and its areas. At most `MAX_NOTICES_PER_HAZARD` (3) notices of one hazard go to a player in one flush: when more of its groups are due (a cooldown ending over a spread-out burst), the two largest are sent as they are and the rest as one summary notice with their summed strikes and areas, positioned at the largest of them. Nothing is saved: open groups are dropped when the player logs out or the server stops.

**Payload `remission:hazard_notice`** (server to client, `HazardNoticePayload`): the hazard id, the dimension key, the position (`BlockPos` as a long), the count and the areas (var-ints; areas is 1 except for a summary). The client shows it in chat (gold) unless its hazard is muted, keeps the last 8 for the hazards tab (`network/HazardClientCache`, cleared on logout) and, with JourneyMap, adds a temporary waypoint. Text: the hazard's own lang key `notice.<namespace>.<path>` when the language has one, else `notice.remission.hazard` (one strike) or `notice.remission.hazard.repeated`, and a summary of several areas always `notice.remission.hazard.areas`; every key gets the arguments hazard name (`StrainHazards.displayName`), count, x, y, z, areas, so a hazard item ships, for example, `"notice.remission.rot": "%2$s blocks rotted near %3$s, %4$s, %5$s"`. A notice from another dimension than the player's appends `notice.remission.hazard.dimension` with the dimension id.

**Hazards tab** (the bestiary screen's footer switches tabs): when the player switches to it, the client sends (once; rebuilding the open tab when new data arrives never asks again, and keeps the list's scroll position) **`remission:hazard_status_request`** (client to server, no body; answered at most every 250 ms per player) and the server answers **`remission:hazard_status`** (`HazardStatusPayload`: two lists of hazard ids, each a var-int count then the ids): `active`, the listed hazards for which `StrainHazards.applies(player, hazard)` is true (so a countermeasure the team holds or a `StrainHazardEvent` listener holding the hazard back hides it; block hazards are asked in the player form too), and `held`, every countermeasure the player's team holds (`teamCountermeasures`). Listed hazards: the declared ones (declarations last for the game session), every hazard reported on the running server (forgotten when it stops, so singleplayer worlds do not share them) and every hazard the team holds a countermeasure for. The tab also lists the recent notices, muted ones marked. These three payloads bumped `RemissionNetwork.PROTOCOL_VERSION` to 4.

**`StrainHazards` additions** (P5.8):

| Method | Returns |
| --- | --- |
| `declare(ResourceLocation hazard)` | Lists a hazard in the hazards tab while it applies; Remission declares `remission:contact`, later hazard items their own; integrations declare theirs once at startup. Any thread. |
| `declared()` | Every declared hazard and every hazard reported on the running server, sorted. Any thread. |
| `teamCountermeasures(MinecraftServer, UUID player)` | Every countermeasure that player (online or not) or a teammate holds, from the saved records, sorted. Server thread. |

Client config `config/remission-client.toml`, `[notices]` (per player, presentation only):

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `muted` | list of hazard ids (strings) | `[]` | Hazards whose notices stay out of chat and off the map; the hazards tab still lists them, marked "Muted". |
| `waypointSeconds` | int 0–3600 | 300 | How long a notice's JourneyMap waypoint stays (session only, never saved); 0 adds none. |

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

## Machine recipes (built, P7.2)

Three recipe types, one per machine, sharing one schema (Java `tech/MachineRecipe`, one serializer per type): `remission:analyzing` (analyzer, 1 input slot), `remission:sequencing` (sequencer, 2), `remission:synthesizing` (synthesizer, 3). Files `data/<ns>/recipe/<path>.json`; ordinary datapack recipes, so `/reload` applies them, and packs add recipes, override a default by its path, or remove one by overriding it with a `neoforge:false` load condition.

```json
{
  "type": "remission:sequencing",
  "ingredients": [{ "tag": "remission:scans", "count": 1 }, { "item": "remission:host_tissue" }],
  "fluid_ingredient": { "fluid": "remission:miasma", "amount": 250 },
  "results": [{ "id": "remission:strain_sequence", "count": 1 }],
  "fluid_result": { "id": "remission:miasma", "amount": 50 },
  "energy": 6000,
  "time": 200
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `ingredients` | list of 1 to (input slots) NeoForge flat sized ingredients: an ingredient object (`{"item"}`, `{"tag"}`, or a typed one such as `neoforge:components`) plus optional `count` (≥ 1, default 1) | Shapeless: each takes its own input slot holding at least `count`, in any order, and every filled input slot must be taken by one (no extra items). `count` items are used per craft. |
| `fluid_ingredient` | optional NeoForge flat sized fluid ingredient: `{"fluid"}` or `{"tag"}` plus `amount` in mB (default 1000) | Must be in the input tank, at least `amount`; used per craft. Without it the input tank is ignored. |
| `results` | list of 0–2 item stacks `{"id", "count", "components"}` | Put into the two output slots (stacking where they can). |
| `fluid_result` | optional fluid stack `{"id", "amount", "components"}` | Filled into the output tank. |
| `energy` | int ≥ 0, default 0 | FE used per craft, spread evenly over `time` (exactly `energy` in total). 0 runs without power. |
| `time` | int ≥ 1, required | Ticks per craft. |

A recipe needs `results` or a `fluid_result`; fluid amounts are at most the tank capacity (4000 mB). Errors are reported by the recipe loader and the recipe is skipped. Wire format (`MachineRecipe.streamCodec`): the same fields in order (sized ingredients, optional sized fluid ingredient, item stacks, optional fluid stack, var-int energy, var-int time); changing it is a protocol change for recipe sync.

**Machines** (blocks `remission:analyzer`, `remission:sequencer`, `remission:synthesizer`; block entities and menus with the same ids; Java `tech/MachineBlock`, `MachineBlockEntity`, `MachineMenu`): input slots as above, 2 output slots, an input and an output tank of 4000 mB each, and a 20,000 FE buffer that accepts up to 1,000 FE per call. A machine looks for a recipe when its inputs or input tank change, then advances one tick at a time while the outputs have room for every result and the buffer holds that tick's share of `energy`; otherwise it waits, keeping its progress. When the inputs stop matching, or match another recipe, progress restarts. Progress and the recipe in progress are saved with the block, so a reload continues the craft. Breaking a machine drops its items; fluids and energy are lost. Using it opens its menu; using it with a fluid container fills the input tank or empties the output tank. Recipe types, fixed slot counts and capacities are code constants, not config.

**Capabilities** (registered in `tech/MachineCapabilities`, the same handler on every side and the null side), so pipes, cables and storage networks (AE2, Mekanism, Create) can run them: `Capabilities.ItemHandler.BLOCK` (slots 0..n-1 are inputs: insert only; the last 2 are outputs: extract only), `Capabilities.FluidHandler.BLOCK` (tank 0 the input tank: fill only, tank 1 the output tank: drain only), `Capabilities.EnergyStorage.BLOCK` (receive only).

**Defaults** (datagen, `data/remission/recipe/`; generic and pack-overridable):

| Recipe | Inputs | Outputs | Energy, time |
| --- | --- | --- | --- |
| `analyzing/host_tissue` | `remission:host_tissue` | 100 mB miasma | 2000 FE, 100 ticks |
| `analyzing/festered_soil` | `remission:festered_soil` | `remission:block_scan` of `remission:festering_grass`, 50 mB miasma | 2000 FE, 100 ticks |
| `analyzing/nidus_core` | `remission:nidus_core` | `remission:block_scan` of `remission:nidus`, 1000 mB miasma | 8000 FE, 200 ticks |
| `sequencing/strain_sequence` | `#remission:scans`, `remission:host_tissue`, 250 mB miasma | `remission:strain_sequence` | 6000 FE, 200 ticks |
| `synthesizing/host_tissue` | `remission:strain_sequence`, `remission:host_tissue`, 500 mB miasma | 2 `remission:host_tissue` | 4000 FE, 200 ticks |
| `synthesizing/countermeasure_protocol_contact` (P5.7) | `remission:strain_sequence`, `remission:festered_soil`, `minecraft:paper`, 1000 mB miasma | `remission:countermeasure_protocol` with `remission:hazard` `remission:contact` | 8000 FE, 400 ticks |

The synthesizing default `host_tissue` stays beside the products added in P7.3 (Synthesizer products, below): no default recipe makes `remission:nidus_core` (cores stay a rare sample). Crafting recipes `remission:analyzer`, `remission:sequencer`, `remission:synthesizer` (iron, glass, redstone around a spyglass, comparator or brewing stand) make the machines from vanilla parts.

**Strain sequence:** item `remission:strain_sequence` (no data components), the sequencer's product and the synthesizer's input. Serums and the other products (P7.3) are `remission:synthesizing` recipes that take `remission:strain_sequence`.

**Item tag** `#remission:scans` (`data/<ns>/tags/item/scans.json`): `remission:host_scan` and `remission:block_scan`, for recipes that take any scan.

**Recipe viewers:** a JEI plugin (`integration/jei/RemissionJeiPlugin`) adds one category per type, with the same ids as the recipe types, the machine as catalyst and the machine screen's progress arrow opening it; EMI shows the same categories through its JEI compatibility. Remission does not require JEI or EMI.

**KubeJS schemas (built, P7.3):** see "Synthesizer products and KubeJS recipe schemas", below.

## Synthesizer products and KubeJS recipe schemas (built, P7.3)

Items (`RemissionItems`), each made by a default `remission:synthesizing` recipe (datagen, `data/remission/recipe/synthesizing/`; packs override or remove them like any machine recipe):

| Item | Recipe | Effect |
| --- | --- | --- |
| `remission:serum` (stacks to 16) | `synthesizing/serum`: `remission:strain_sequence`, 2 `minecraft:glass_bottle`, 250 mB miasma; 2 serums; 4000 FE, 200 ticks | Drunk like a potion (32 ticks; a glass bottle back). One dose (`Serums.apply`): a player loses `[serums] viralLoadDrop` viral load at once (`ViralLoadChangedEvent.Cause.SERUM`; no event at 0) and any living target gets the effect `remission:inoculated` for `inoculationTicks`. |
| `remission:purifier_fuel` | `synthesizing/purifier_fuel`: `remission:strain_sequence`, 4 `#minecraft:coals`, 250 mB miasma; 4 fuel; 4000 FE, 200 ticks | In item tag `#remission:purifier_fuel`, which repairs a handheld purifier in an anvil. The tag is the reclaimer's fuel (P7.4); packs add their own fuels to it. |
| `remission:counterstrain_prototype` (stacks to 16) | `synthesizing/counterstrain_prototype`: `remission:strain_sequence`, `remission:nidus_core`, `minecraft:glass_bottle`, 1000 mB miasma; 1 prototype; 12000 FE, 400 ticks | Thrown like a splash potion (entity `remission:counterstrain_prototype`; no dispenser behaviour). Where it hits a block or entity it backfires with chance `[prototypes] backfireChance`, releasing one miasma cloud (`remission:miasma_cloud`, default radius, 1200 ticks; it climbs and drifts like a surge's, so it is soon out of reach) and giving every exposed player (survival or adventure) within `radius` blocks of the impact `[prototypes] backfireViralLoad` viral load at once (`ViralLoadChangedEvent.Cause.PROTOTYPE`), cleansing nothing; otherwise it cleanses every festered block and vein in the cube `radius` around the block it landed in and lowers that block's chunk by `step`, both with `PurificationCause.PROTOTYPE`; `ChunkEnteredRemissionEvent.getPlayer()` names the thrower when that finishes the chunk. |

The P7.2 default `synthesizing/host_tissue` is unchanged. Mob effect `remission:inoculated` (beneficial; `RemissionMobEffects.INOCULATED`): while a player has it, every viral load gain (land, water, miasma clouds, contact) is multiplied by `[serums] inoculationFactor`, which is at least 0.1, so inoculation slows viral load and never stops it: an inoculated player in festering land still gains, never decays. Decay and the one-off doses (a water bottle drunk in festering land, a prototype backfire) are not scaled. Milk and other effect cures remove it. Packs can give it with `/effect` or any effect source.

Config (`remission-common.toml`):

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `[serums] viralLoadDrop` | 40.0 | 0–100 | Viral load a dose takes off at once. |
| `[serums] inoculationTicks` | 3600 | 0–72000 | Ticks of `remission:inoculated` a dose gives (0 gives none). |
| `[serums] inoculationFactor` | 0.5 | 0.1–1 | Fraction of viral load gain an inoculated player still takes (values below 0.1 are raised to it). |
| `[prototypes] radius` | 4 | 0–8 | Cube radius of blocks a prototype cleanses. |
| `[prototypes] step` | 40 | 0–100 | Strain level the landing chunk loses. |
| `[prototypes] backfireChance` | 0.25 | 0–1 | Chance a prototype backfires instead. |
| `[prototypes] backfireViralLoad` | 20.0 | 0–100 | Viral load a backfire gives each exposed player within `radius` blocks. |

Java for later items: `Serums.apply(LivingEntity, SerumSettings)` is the one dose (P7.6's injector); `CounterstrainPrototypes.land(ServerLevel, Vec3, ServerPlayer, PrototypeSettings, RandomSource)` is one landing. Neither is public API in `api/`.

**KubeJS recipe schemas:** `data/remission/kubejs/recipe_schema/analyzing.json`, `sequencing.json` and `synthesizing.json` (in the mod's resources, so any pack with KubeJS 2101 gets typed methods without a Java dependency). Keys, in order, with the same names as the recipe JSON (Machine recipes, above):

| Key | KubeJS component | Role | Optional (default) | Builder method |
| --- | --- | --- | --- | --- |
| `ingredients` | list of `flat_sized_ingredient`, 1 to the machine's input slots | input | required | |
| `results` | list of `item_stack`, 0–2 | output | `[]` | |
| `fluid_ingredient` | `flat_sized_fluid_ingredient` | input | absent | `fluidIngredient`, `fluid` |
| `fluid_result` | `fluid_stack` | output | absent | `fluidResult` |
| `energy` | `non_negative_int` | input | `0` | `energy` |
| `time` | `ticks` (always written) | input | `200` | `time` |

Constructors: `event.recipes.remission.<type>(results, ingredients)` and `(results, ingredients, fluidIngredient)`; sized ingredients as `'2x minecraft:coal'` or `'#remission:scans'`, fluids as `Fluid.of('remission:miasma', 250)`. `event.remove({type: 'remission:<type>'})`, `{id: ...}`, `{output: ...}`, `replaceInput` and `replaceOutput` work on these recipes. Example and verification: kubejs-scripting. Schema changes are contract changes.

## Advancement triggers and stats (built, P8.1)

Criterion triggers registered in `RemissionTriggers` (`quest/` classes), fired from `quest/QuestProgress` on the server after the action succeeded. Every trigger takes the vanilla optional `player` (entity predicate or loot conditions); the rest are optional and an empty `conditions` (or none) matches every firing. Fake players (machines acting as players) fire nothing and earn no stats.

| Trigger | Fires when | Conditions |
| --- | --- | --- |
| `remission:nidus_destroyed` | A player destroys a `remission:nidus`: the final blow of a fight (P4.10), a creative break, or a break while `[nidi] vitality` is 0. It fires for every contributor (each player who damaged it within `[nidi] contributorWindowTicks`, the destroyer included) who is online, in whatever dimension (Nidus encounters, below). Other removals (`/setblock`, edits, structure replacement) post `NidusDestroyedEvent` but fire no trigger. | `location`: vanilla location predicate, tested at the centre of the nidus's block after removal (a `block` or `fluid` predicate sees what replaced it). |
| `remission:host_scanned` | A player takes a host scan with the scanner (a host; a non-host gives no scan and no trigger). Fires for the scanner only. | `entity`: entity predicate (or loot conditions) on the scanned host; `stage`: int range, the host stage scanned; `bestiary_types`: int range, the number of host types in the player's bestiary after this scan: the team total since P6.4, equal to the player's own count without a team source (`Bestiary`; types it does not list are not counted). |
| `remission:block_scanned` | A player takes a block scan with the scanner. | `location`: vanilla location predicate at the centre of the scanned block (its `block` predicate sees the scanned block). |
| `remission:viral_load_changed` (P5.1) | The player's viral load changed (Viral load, above): at most once per update (once a second) plus once per drink, never when only the trend changed, nor for the reset on death. | `viral_load`: float range, the new viral load (0–100): `{"min": 50}` for a player who reached 50, `{"max": 0}` for one whose viral load fell back to 0. |
| `remission:region_cleansed` (P5.2) | A chunk entered remission (Purification and remission zones, below) while the player was alive, not a spectator, in the same level and within 8 chunks (Chebyshev) of it. Fires once per chunk entering remission. | `radius`: int 0–16, default 4: the square of chunks around the player's own chunk the region covers; `share`: float range, the share (0–1) of the region's affected loaded chunks (in remission or festering) that are in remission (0 when none are affected); `chunks`: int range, how many of its loaded chunks are in remission. |
| `remission:revenant_reclaimed` (P5.3) | A player killed a revenant whose items they may pick up: their own, or a teammate's (the team query). A kill by anyone else, or by a command, fires nothing. | `own`: boolean, whether the revenant was the player's own; false means a teammate's (absent matches both). |
| `remission:countermeasure_gained` (P5.7) | A player gained a countermeasure: by protocol, from a teammate (a grant, or pooling on login or a team change) or by command; once per player who gained it, never for one already held. | `hazard`: the hazard id (absent matches every hazard). |
| `remission:surge_survived` (P4.3) | A surge ends (any reason) and the player was caught in it: inside its area at one of its 20-tick checks while it ran, not dead since, and online and alive at the end (Surges and miasma clouds, below). A surge stopped during its warning never started and awards nothing. | `intensity`: int range, the surge's intensity (1–10). |

```json
{"criteria": {"nidus": {"trigger": "remission:nidus_destroyed"}}}
{"criteria": {"cow": {"trigger": "remission:host_scanned", "conditions": {"entity": {"type": "minecraft:cow"}, "stage": {"min": 2}}}}}
{"criteria": {"ten": {"trigger": "remission:host_scanned", "conditions": {"bestiary_types": {"min": 10}}}}}
{"criteria": {"found": {"trigger": "remission:block_scanned", "conditions": {"location": {"block": {"blocks": "remission:nidus"}}}}}}
{"criteria": {"storm": {"trigger": "remission:surge_survived", "conditions": {"intensity": {"min": 2}}}}}
{"criteria": {"cured": {"trigger": "remission:countermeasure_gained", "conditions": {"hazard": "remission:contact"}}}}
{"criteria": {"exposed": {"trigger": "remission:viral_load_changed", "conditions": {"viral_load": {"min": 50}}}}}
{"criteria": {"back": {"trigger": "remission:revenant_reclaimed", "conditions": {"own": true}}}}
{"criteria": {"reclaimed": {"trigger": "remission:region_cleansed", "conditions": {"radius": 6, "share": {"min": 0.75}}}}}
{"criteria": {"first": {"trigger": "remission:region_cleansed", "conditions": {"radius": 0}}}}
```

Custom stats (registry `minecraft:custom_stat`, holder `RemissionStats`, formatter `DEFAULT`), awarded at the same moments as the triggers; lang keys `stat.remission.<name>`:

| Stat | Counts |
| --- | --- |
| `remission:nidi_destroyed` | nidi the player destroyed or helped destroy (as `nidus_destroyed`, so every contributor counts one) |
| `remission:hosts_scanned` | host scans taken (as `host_scanned`) |
| `remission:blocks_scanned` | block scans taken (as `block_scanned`) |
| `remission:surges_survived` | surges survived (as `surge_survived`; P4.3) |
| `remission:revenants_reclaimed` | revenants reclaimed (as `revenant_reclaimed`, own or not; P5.3) |
| `remission:chunks_cleansed` | chunks that entered remission within 8 chunks of the player (as `region_cleansed`; P5.2) |
| `remission:countermeasures_gained` | countermeasures the player gained (as `countermeasure_gained`; P5.7) |

Read them with `/scoreboard objectives add <name> minecraft.custom:remission.nidi_destroyed`, FTB Quests stat tasks (`stat: "remission:nidi_destroyed"`) or any stat reader. Packs define the advancements (Counterstrain: `data/counterstrain/advancement/` in its KubeJS datapack); Remission ships triggers only, no advancements. Gametests: `QuestTriggerTests` (each trigger and stat, conditions parsed from the JSON above); `SurgeTests` for `surge_survived` and its stat; `ViralLoadTests` for `viral_load_changed`; `RevenantTests` for `revenant_reclaimed` and its stat; `PurificationTests` for `region_cleansed` and `chunks_cleansed`; `CountermeasureTests` for `countermeasure_gained` and `countermeasures_gained`.

"Variant" is reserved for resistant mutations; triggers and stats for systems that are not built yet arrive with them. P8.1 deferred surge survived to P4.3 and viral load to P5.1, which built them (P5.1 adds a trigger and no stat: viral load is a level, not a count); region % cleansed is P5.2's `region_cleansed`.

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

## Quest-fired events: `/remission nidus` and `/remission region` (built, P8.2)

Registered with the `/remission` tree (`commands/NidusCommands`, `commands/RegionCommands`), same permission level 2, acting on the source's dimension, for FTB Quests command rewards, KubeJS (`server.runCommandSilent`), functions and the console. Starting a surge is P4.3's `/remission surge start` (above). None of them loads a chunk.

| Syntax | Effect | Result | Feedback |
| --- | --- | --- | --- |
| `/remission nidus seed <pos>` | `pos`: a loaded block position. Places a `remission:nidus` there (`setBlockAndUpdate`, replacing the block), which indexes it like any placed nidus, so it spreads from the next pass and can be the origin of `surge start`. | 1 | `commands.remission.nidus.seed.success` (x, y, z); broadcast to ops |
| `/remission region advance <pos> <radius> [stages]` | `pos`: block position; `radius`: int 0–32, in chunks; `stages`: int ≥ 1, default 1; values above the number of stages count as that number. Every loaded chunk within `radius` of the chunk holding `pos` by Manhattan distance (the shape of a nidus's reach) rises `stages` festering stages, capped at the last stage, to the lowest Strain level of its new stage (`[festering] stageThresholds`; a clean chunk starts festering). Each changed chunk posts `ChunkStageChangedEvent` and its update time becomes now, so the land festers through the usual budgeted stage work (blocks, biome flip at the last stage, veins) and map overlays update. Chunks already at the target stage and unloaded chunks are skipped. | chunks advanced | `commands.remission.region.advance.success` (chunks, radius, chunk x, chunk z); broadcast to ops |

Errors: `commands.remission.nidus.seed.failed.present` when a nidus is already at `pos`; `commands.remission.nidus.seed.failed.blocked` on an unbreakable block (bedrock, barriers; destroy speed below 0) or a block entity, which a quest must not destroy; `commands.remission.nidus.seed.failed.not_indexed` when the placed nidus did not reach the index (something replaced it at once; the old block is put back); vanilla `argument.pos.unloaded` / `argument.pos.outofworld` for an unloaded or out-of-world `pos`; `commands.remission.region.advance.failed` when no loaded chunk in reach could rise.

FTB Quests command rewards run as the claiming player at the reward's `permission_level` (set 2), so relative coordinates are the player's position, and the command text may use `{p}`, `{x}`, `{y}`, `{z}`. Example rewards (verified in a dev run, P8.2): `/remission nidus seed 0 -60 -8`, `/remission region advance 0 -60 -8 1 2`, `/remission surge start 0 -60 -8 2`. To seed on the surface near the player: `/execute positioned ~24 ~ ~ positioned over world_surface run remission nidus seed ~ ~ ~`.

## Countermeasure commands `/remission countermeasure` (built, P5.7)

Registered with the `/remission` tree (`commands/CountermeasureCommands`), same permission level 2. `hazard` is any resource location (Remission's hazards and every hazard an online player holds are suggested). Strain hazards and countermeasures: above.

| Syntax | Effect | Result | Feedback |
| --- | --- | --- | --- |
| `/remission countermeasure grant <targets> <hazard>` | `targets`: player selector. Grants the countermeasure to each target and their online teammates (event cause `COMMAND` for targets, `TEAM` for teammates), firing the trigger and stat for each player who gained it. | players who gained it | `commands.remission.countermeasure.grant.success` (hazard name, count); broadcast to ops |
| `/remission countermeasure revoke <targets> <hazard>` | Takes it from each target's whole team: online members at once, offline members' records at once and their attachments at their next login (unless the team holds it again by then), so the team no longer holds it. | players who lost it, offline ones included | `commands.remission.countermeasure.revoke.success` (hazard name, count); broadcast to ops |
| `/remission countermeasure list <target>` | One player. Reports their countermeasures; changes nothing. | countermeasures held | `commands.remission.countermeasure.list.success` (name, count, list of `.list.entry` (hazard name, id)) or `.list.none` (name) |

Example (an FTB Quests command reward, run as the claiming player at permission level 2): `/remission countermeasure grant @s remission:contact`.

## Map overlay payload `remission:festering_map` (built, P8.2)

Server to client, for map overlays (`integration/journeymap/`).

- **State:** A chunk's state is its festering stage (computed from the current `[festering] stageThresholds`) in the low seven bits, with the top bit (`FesteringMapPayload.REMISSION`, 0x80) set when it is in remission (stage 0, `CorruptionQueries.isInRemission`); 0 is clean land never purified.
- **Sync:** `network/FesteringMapSync` queues a chunk's state for a player when the chunk is sent to them (`ChunkWatchEvent.Sent`, clean land included, so a client learns that land it knew as festering is clean again), and for every player watching it on `ChunkStageChangedEvent` (which also covers leaving remission, since only a level rise does that) and `ChunkEnteredRemissionEvent`; a later state for a chunk replaces an earlier one, and each player's queue is sent once per server tick.
- **Wire format** (`FesteringMapPayload`): the dimension key, a var-int entry count (at most 4096 per payload; larger batches are split), then per entry the chunk position as a long (`ChunkPos.toLong`) and the state as a byte. Adding it bumped `RemissionNetwork.PROTOCOL_VERSION` to 3.
- **Config changes:** A config change to the thresholds reaches clients as chunks are sent again.
- **Cost:** The payload goes to every client, whether or not it runs a map integration: about 9 bytes per chunk sent (a long and a byte), plus the dimension key per batch.
- **Client cache:** The client keeps the states (`network/FesteringMapCache`) only while a map integration uses them, for every chunk seen this session, and clears them on logout; nothing is saved.

## Festering land: chunk attachment, nidus, spread config (built, P4.1)

Chunk attachment `remission:chunk_corruption` (`ChunkCorruption` in `com.cubiecode.remission.world`), saved inside the chunk's `neoforge:attachments` compound, omitted at level 0. Server only, not synced. Internal: packs read it through `CorruptionQueries` (Public API, below).

```json
{ "level": 42, "last_update_game_time": 1234000 }
```

| Field | Type | Meaning |
| --- | --- | --- |
| `level` | int 0–100 | Strain level of the land. Only rises in phase 4; festering stages are derived from it (Festering stages, below). |
| `last_update_game_time` | long | Level game time of the last change; catch-up credits the passes missed since then (or since the nidus was indexed, if later). |

Block `remission:nidus` (block state `damage` 0–3, set by its fight; Nidus encounters below) with block entity `remission:nidus` (its fight) and block item `remission:nidus`. Drops one `remission:nidus_core`, never itself (loot table `remission:blocks/nidus`; Miasma and samples, above), mineable with a hoe (`#minecraft:mineable/hoe`) only while `[nidi] vitality` is 0 (otherwise it is fought down: Nidus encounters, below), cannot be pushed. Placing it (or loading a chunk that has one) indexes it; removing it in any way stops its spread and leaves its land as it is. Index: `data/remission_nidi.dat` in the dimension folder, `{ "nidi": [ { "pos": <BlockPos.asLong>, "since": <game time> }, ... ] }`. Packs place nidi as ordinary blocks (structures, `/setblock`, features); `/remission nidus seed` is P8.2.

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

## Nidus encounters (built, P4.10)

While `[nidi] vitality` is above 0, a `remission:nidus` cannot be mined or blown up: players fight it down. Its **nidus vitality** is fixed when its fight starts (the first damage): `vitality × growth × distance × players`, where growth is `1 + growthBonus × min(1, age / growthTicks)` (age since the nidus was indexed), distance is `1 + min(distanceMaxBonus, distanceFactor × blocks / 1000)` (horizontal blocks from the world spawn; off by default) and players is `1 + playerFactor × (min(n, playerCap) − 1)` for the `n` players (alive, survival or adventure, not fake) within `radius` blocks, at least 1. When more players come within range (checked every 20 ticks) the count rises and vitality with it, keeping the share already taken; it never falls while the fight lasts.

- **Strikes:** a survival player's left-click on it (the block's `attack`, after `PlayerInteractEvent.LeftClickBlock` and claim checks, so cancelling the left-click blocks the strike; an adventure player strikes only with an item whose `can_break` allows the nidus, as for any block) deals the player's attack damage attribute (the held item's modifiers included) times `0.2 + 0.8 × strength²`, where strength is vanilla attack strength (`getAttackStrengthScale(0.5)`), then resets attack strength and wears a damageable held item by 1. Holding the button strikes once; each click is a strike, but a click within `strikeCooldownTicks` (10) of that player's last strike on that nidus deals nothing and leaves attack strength alone, so clicking faster never beats fully charged swings (gametest: 20 clicks a second for 10 s with an iron sword, an iron axe and a bare hand each deal less than charged swings). Fake players (machines acting as players) never strike. Mining makes no progress, and a player break (survival mining, fake players) leaves it in place.
- **Projectiles:** an arrow or trident (`AbstractArrow`) that hits it deals `ceil(speed × base damage)` (vanilla arrow damage without the critical bonus) times `projectileFraction`, credited to its owner. Other projectiles deal nothing (explosive ones count through their explosion).
- **Explosions:** its explosion resistance is bedrock's, so no explosion removes it; an explosion within twice its radius deals the vanilla mob damage at the block's centre (no shielding) times `explosionFraction`, credited to the player behind it (who lit the TNT).
- **Only players count:** damage starts a fight or wears a nidus down only when a player (not a fake player) is behind it: the striker, a projectile's owner, or the explosion's source or causing entity (vanilla `Explosion#getIndirectSourceEntity`: TNT a player lit, a projectile a player fired). Host creepers, skeletons' arrows, ownerless TNT, wither skulls and other mobs' blasts do nothing to it, and no `NidusDamagedEvent` is posted for them.
- Only a strike can take the last point: projectile and explosion damage stops at 1 vitality. The wither and the ender dragon never break it (block tags `#minecraft:wither_immune`, `#minecraft:dragon_immune`, always).
- **Thresholds:** when vitality reaches or falls below a threshold (percent of full, highest first) it fights back, once per threshold per fight: it starts a surge from itself at once with no warning (`SurgeController.startNow`; a surge already in the dimension ends with reason `SUPERSEDED`: one from another nidus awards its survivors as usual, while one from the same nidus, the fight's previous threshold, awards nothing and hands its caught players, fallen players and hosts to the new surge, so a fight awards `remission:surge_survived` once, when its last surge ends) at intensity `round(index × players)` (index 1, 2, 3… for each threshold passed; clamped 1–10), and lashes every player within `lashRadius` (survival or adventure): `lashViralLoad` viral load (cause `NIDUS`; only while `[viral_load] enabled`) and knockback of `lashKnockback` away from it, with the warden's sonic boom and sculk soul particles and the action bar line `event.remission.nidus.lash`. A blow that destroys it passes no thresholds.
- **Regeneration:** after `regenDelayTicks` without damage it regains `regenRate` of its full vitality per second; once whole, the fight is over, its thresholds re-arm and the next damage starts a new fight (players counted again).
- **Shown:** players within `radius` (any game mode) see a red boss bar `event.remission.nidus.bar` ("Nidus vitality") with the vitality left while it fights (a vanilla packet, no Remission payload), and the block state `damage` (0–3, the quarters of vitality lost: 1 from 25% lost, 2 from 50%, 3 from 75%) shows its health stage to everyone: the animated model's healthy, lightly wounded, badly wounded and near-death geometry and skin (damage 0–3). Clients also see it enraged while a surge from it runs (block entity update tag, not a contract) and a hit reaction per damage (block event 1). It returns to 0 when the fight ends; placed and generated nidi start at 0.
- **The final blow:** the strike that takes the last point destroys it (`destroyBlock` with drops: the loot table, one `remission:nidus_core` by default). `NidusDestroyedEvent` names that player and its contributors: every player (not fake) who damaged it (struck, shot or blew it up) within `contributorWindowTicks` before, in the order they first did, the destroyer included. Every contributor online, in any dimension, earns `remission:nidus_destroyed` and `remission:nidi_destroyed`. A cancelled `NidusDamagedEvent` earns no credit.
- Creative players (any left-click) and edits (`/setblock`, `/fill`, structure placement, other mods setting the block) still remove it at once; a creative break credits the player and its recent contributors like a final blow. With `vitality = 0` it is mined as before (hoe, strength 5) and the miner is credited; nothing else here applies.

Block entity `remission:nidus` saves the fight under `fight` (omitted when there is none): `base` (float, vitality before the player factor), `players` (int, counted players), `damage` (float, vitality lost), `thresholds_passed` (int), `last_damaged` (long, game time), `contributors` (list of `{player: UUID int array, time: long}`). Internal; read fights through `NidusDamagedEvent` and `NidusDestroyedEvent`.

Config `config/remission-common.toml`, section `[nidi]` (read whenever used; vitality and the factors are fixed per fight when it starts or a player joins):

| Key | Default | Range | Meaning |
| --- | --- | --- | --- |
| `vitality` | 250.0 | 0–1000000 | Vitality of a new nidus fought by one player. 0 restores plain mining. |
| `growthTicks` | 24000 | 1–2400000 | Game ticks after it is indexed until a nidus is full-grown. |
| `growthBonus` | 0.5 | 0–100 | Extra vitality of a full-grown nidus as a fraction of `vitality` (1 = twice), linear over `growthTicks`. |
| `radius` | 24 | 1–128 | Blocks within which players count toward the fight and see its boss bar. |
| `playerFactor` | 0.5 | 0–10 | Extra vitality and surge intensity per counted player beyond the first. |
| `playerCap` | 4 | 1–64 | Most players counted. |
| `distanceFactor` | 0.0 | 0–100 | Extra vitality per 1000 blocks from world spawn, as a fraction; 0 is off. |
| `distanceMaxBonus` | 2.0 | 0–100 | Most extra vitality from distance, as a fraction. |
| `contributorWindowTicks` | 2400 | 0–72000 | How recently a player must have damaged the nidus to share the credit. |
| `regenDelayTicks` | 300 | 0–72000 | Game ticks without damage before it regenerates. |
| `regenRate` | 0.05 | 0–1 | Fraction of full vitality regained per second (0: never). |
| `thresholds` | `[75, 50, 25]` | 0–10 ints, 1–99 | Percent of vitality left at which it surges and lashes; order-independent, duplicates ignored. |
| `projectileFraction` | 0.5 | 0–1 | Fraction of arrow damage it takes. |
| `explosionFraction` | 0.25 | 0–1 | Fraction of mob explosion damage it takes. |
| `lashRadius` | 8.0 | 0–64 | Blocks a lash reaches. |
| `lashViralLoad` | 8.0 | 0–100 | Viral load a lash adds. |
| `lashKnockback` | 1.5 | 0–10 | Lash knockback strength (a strong mob hit is about 1). |
| `strikeCooldownTicks` | 10 | 0–200 | Game ticks after a player's strike during which their further strikes on that nidus deal nothing (a mob's invulnerability after a hit is 10). |

Default fight: a new nidus has 250 vitality and a full-grown one 375 for one player (×1.5 for two, ×2 for three, ×2.5 for four or more), each with three surges at intensity 1, 2 and 3 for one player (2, 3 and 5 for two: rounded half up from 1.5, 3 and 4.5) and 24 viral load from lashes on top of the land's. An iron sword deals 6 a fully charged strike, about every 0.6 s, so about 9.6 vitality per second at best; spam clicking deals less (strike cooldown). Measured with `NidusFightBenchmark` (P4.10 fix round 1; Normal, stage 4 land, default `[nidi]` and `[surges]`, one player in full iron armour with an iron sword against a full-grown nidus, 6 trials per policy): a player who only strikes the nidus and never fights the hosts dies in every trial, after 29–38 s, usually during the second surge; a player who also hits hosts that come within reach until the last threshold, then races the final surge, and eats a bread when hungry, destroys it in every trial in 53–67 s, ending at 6.7–11.7 of 20 health (11–27 health lost to hosts, regeneration included), 42–46 viral load (Weakness, at 50, never reached), 77–99 sword durability used and 0–1 bread. The server does not move players, so the benchmark models each lash's knockback as 1 s without striking. These are P8.4's starting point. Gametests: `NidusEncounterTests` (batches `nidi` and `nidi_plain`, each run on fixed settings installed for the batch).

Events: `NidusDamagedEvent` (cancellable) and `NidusDestroyedEvent` (Public API, below).

## Festering stages, blocks and the fester biome (built, P4.2; caves P4.6; plants and stage order P4.16)

A chunk's festering stage (`[festering] stageThresholds`, Public API below) turns its land, in `world/FesteringStages`, server side:

- **Blocks:** when a chunk's stage rises, every block in it with a `remission:festers_into` entry (below) whose `stage` is at or below the chunk's stage is replaced by the entry's block. Stages are cumulative: a chunk that jumps from 0 to 3 gets every entry up to 3 at once. The surface pass scans each column from its highest block (`WORLD_SURFACE` heightmap) down through trees, plants and water to 5 blocks into the ground, counting the first block that blocks motion and is not in `#minecraft:logs` or `#minecraft:leaves` as the first ground block. Block states keep every property both blocks share (log `axis`, leaf `distance`, `persistent` and `waterlogged`, grass `snowy`). Blocks with block entities are never replaced. Replacement uses block update flags 2 and 16 (sent to clients, no neighbour updates), so plants on festering grass stay and nothing drops.
- **Caves (P4.6):** after the surface pass, a cave pass replaces every block in the chunk, at any height, that faces air on at least one of its six sides (air, cave air or void air, or a festering vein, since veins fill cave air before the cave pass runs, P4.16: cave walls, floors and ceilings, overhangs and the lower parts of cliff faces) and whose entry festers underground at the chunk's stage: `stage` and `underground_stage` (below) both at or below it. Rock with no air beside it and blocks without an entry (ores, dirt, gravel, sculk) stay, so mining stays viable. At or above Y=0 every such block festers; below Y=0 one in four does, picked by a hash of the block position (the same blocks in every world and on every rescan). The depth (Y=0) and fraction (1 in 4) are constants, not config. A side in a chunk that is not loaded counts as solid.
- **Biome flip:** at the last stage (the number of `stageThresholds`; 4 by default), after its blocks, every biome cell of the chunk (all heights) flips to its fester biome: the source biome's `remission:fester_variant` entry (below), else `remission:fester`. `remission:fester` and every biome a `fester_variant` entry names never flip again. Players who have the chunk get vanilla's `ClientboundChunksBiomesPacket` (as `/fillbiome` sends), so grass, foliage and water colours, fog and sky change without a relog; no Remission payload.
- **Budget and timing:** a stage rise (`ChunkStageChangedEvent`, live or catch-up) or a chunk load queues the chunk; up to 64 columns (or queued chunks skipped) are done per tick and level, so a chunk takes 4 ticks; the cave pass then does up to 8 block layers (or sections with no block that could fester skipped) per tick and level, so a chunk with stone in every section takes about 48 ticks. Chunk attachment `remission:festered_stage` (int, saved with the chunk, never at 0, server only) records the stage a chunk was last brought up to, written when its cave pass finishes: a queued chunk at or below it is skipped, and a chunk that unloaded or a server that stopped mid-way is redone (both passes change nothing twice) when the chunk loads again. Blocks placed later in a festered chunk stay until its stage next rises. Stages never fall in phase 4; nothing reverts (purification is P5.2).
- **Events:** `BlockFesteredEvent` once per replaced block (Public API, below). The biome flip posts no event; `ChunkStageChangedEvent` already marks the stage.

Blocks (block items too, no creative tab yet), `data/remission/loot_table/blocks/<id>.json`:

| Block | Replaces by default (stage) | Behaviour | Drops | Tool tag |
| --- | --- | --- | --- | --- |
| `remission:festering_grass` | `minecraft:grass_block` (3) | snowy state like grass; never spreads or decays; in `#minecraft:dirt`, so plants stay on it | dirt, itself with silk touch; festered soil chance at stage 2+ (P7.1) | `mineable/shovel` |
| `remission:festering_short_grass`, `remission:festering_fern` (P4.16) | `minecraft:short_grass`, `minecraft:fern` (2) | plant like short grass (no collision, replaceable, stays on dirt and festering grass); bone meal does nothing | itself with shears, nothing otherwise | none (instant); `#minecraft:sword_efficient`, `replaceable`, `replaceable_by_trees` |
| `remission:festering_tall_grass`, `remission:festering_large_fern` (P4.16) | `minecraft:tall_grass`, `minecraft:large_fern` (2) | two-block plant (`half`) like tall grass | two of its short plant with shears, nothing otherwise | as above |
| `remission:festered_stone` | `#minecraft:stone_ore_replaceables` (3): stone, granite, diorite, andesite | plain stone-like block | itself | `mineable/pickaxe` |
| `remission:festered_deepslate` (P4.6) | `#minecraft:deepslate_ore_replaceables` (3): deepslate, tuff | pillar with `axis` like deepslate (deepslate's hardness and sounds) | itself | `mineable/pickaxe` |

**Data map `remission:festers_into`** (on `minecraft:block`), `data/<ns>/data_maps/block/festers_into.json`, NeoForge data map file format (keys are block ids or `#tags`; `replace`, `remove` and conditions work as for any data map). Server side only, not synced, reloaded with `/reload` (already festered chunks are not rescanned; the next stage rise uses the new map). Remission's default (datagen `RemissionDataMapProvider`; P4.16 set the stages so veins come first, then plants, then the ground, and dropped logs and leaves, which come in too many kinds to turn into one block and back):

```json
{
  "values": {
    "minecraft:grass_block": { "block": "remission:festering_grass", "stage": 3 },
    "minecraft:short_grass": { "block": "remission:festering_short_grass", "stage": 2 },
    "minecraft:tall_grass": { "block": "remission:festering_tall_grass", "stage": 2 },
    "minecraft:fern": { "block": "remission:festering_fern", "stage": 2 },
    "minecraft:large_fern": { "block": "remission:festering_large_fern", "stage": 2 },
    "#minecraft:stone_ore_replaceables": { "block": "remission:festered_stone", "stage": 3 },
    "#minecraft:deepslate_ore_replaceables": { "block": "remission:festered_deepslate", "stage": 3 }
  }
}
```

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `block` | block id | required | What the block turns into. A block that maps to itself (a festering block in a tag it replaces) is left alone. Both halves of a two-block plant (`half` property) turn together when the other half is the same block. |
| `stage` | int ≥ 1 | 1 | Lowest chunk festering stage at which it turns. Entries above the configured number of stages never apply. |
| `underground_stage` | int ≥ 0 | 3 | Lowest chunk festering stage at which it also turns in the cave pass (where it faces air), never below `stage` (the higher of the two applies). `0` turns cave festering off for the entry; the surface pass still applies. (P4.6) |

Logs, leaves and flowers have no default entry: trees keep their wood type and show festering through veins and, at the last stage, the fester biome's foliage colour. A pack that maps them should give each kind its own festering block with a `cleanses_into` entry back, or cleansing flattens them. A pack adds modded blocks (`"biomesoplenty:origin_grass_block": {"block": "remission:festering_grass"}`), points a block at its own festering block, removes an entry (`"remove": ["#minecraft:deepslate_ore_replaceables"]`), or tunes caves: `{"block": "remission:festered_stone", "stage": 3, "underground_stage": 0}` on `#minecraft:stone_ore_replaceables` keeps caves in stone clean, and `"underground_stage": 4` delays them to the last stage.

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

**Fester biome atmosphere (built, P4.11).** `remission:fester` (datagen `RemissionBiomeProvider`) sets these `effects`; a variant biome inherits none of them, so a pack copies the ones it wants into its own biome file:

```json
"effects": {
  "fog_color": 10852272, "sky_color": 9405603, "water_color": 6179438, "water_fog_color": 2826038,
  "grass_color": 11575422, "foliage_color": 10520728,
  "ambient_sound": "remission:ambient.fester.loop",
  "mood_sound": { "sound": "remission:ambient.fester.mood", "tick_delay": 3000, "block_search_extent": 8, "offset": 2.0 },
  "additions_sound": { "sound": "remission:ambient.fester.additions", "tick_chance": 0.008 },
  "music": { "sound": "remission:music.fester", "min_delay": 2400, "max_delay": 9600, "replace_current_music": true }
}
```

Sound events (`RemissionSounds`; files and subtitles in `assets/remission/sounds.json`, placeholders until P12.2): `remission:ambient.fester.loop` (looping drone), `remission:ambient.fester.mood` (cave-style mood, subtitle `subtitles.remission.ambient.fester.mood`), `remission:ambient.fester.additions` (wet, organic one-shots, subtitle `subtitles.remission.ambient.fester.additions`) and `remission:music.fester` (streamed). A resource pack replaces their sounds through `sounds.json` (`"replace": true`). The client's stage fog is not biome data: it follows the chunk's festering stage in any biome (variant or not) and the last stage in `remission:fester` itself; a variant biome gets the stage fog from its chunk's stage, which the server sends with the chunk (`remission:festering_map`, above). Client config `config/remission-client.toml`, `[atmosphere] festerFog` (0 to 1, default 1) scales how much the stage fog thickens; 0 turns it off, and the biome's own colours stay.

## Festering veins and contact infection (built, P4.8)

**Block `remission:festering_vein`** (`world/FesteringVeinBlock`, a vanilla `MultifaceBlock` like glow lichen and sculk veins; block item too): one boolean state property per face (`north`, `east`, `south`, `west`, `up`, `down`; true where the vein covers the neighbouring block's face on that side). No collision, replaceable (blocks can be placed into it), hardness 0.2, `mineable/hoe`, destroyed when pushed by a piston. A face drops off when the block behind it stops holding it, and a vein with no face turns to air. Drops one `remission:festering_vein` per face with silk touch and nothing otherwise (`data/remission/loot_table/blocks/festering_vein.json`, vanilla `createMultifaceBlockDrops`). It never spreads on its own.

**Growth** (`world/FesteringVeins`, `VeinGrowth`, server side): in a chunk at or above `[festering] veinMinStage`, every air block (air, cave air) of the chunk, at any height, gets a vein face on each side whose neighbour holds a multiface block (`MultifaceBlock.canAttachTo`: a full support or collision face toward it, so stone, dirt, planks, glass and leaves do, slabs from below, fences and torches do not), is not air or a vein, and is not in `#remission:vein_resistant`, where the face is **selected** for the chunk's coverage. Nothing is replaced: veins only go into air, so player builds, plants and water are never overwritten, and block entities are never touched. A neighbour in a chunk that is not loaded is left for a later visit.

- **Coverage:** `15 * (stage - veinMinStage + 1)` percent of eligible faces, at most 60 (constants `COVERAGE_PER_STAGE`, `MAX_COVERAGE`, not config): with the defaults (`veinMinStage` 1 since P4.16) 15% at stage 1, 30% at 2, 45% at 3, 60% at 4. Selection is a hash of the block position and side, scaled by a per-4×4×4-cell weight (0–2, by the cell's hash) so veins grow in patches; the same blocks at the same stage always get the same veins, in any world, scan order or reload, and a higher stage keeps every face of a lower one.
- **Budget and timing:** a chunk load or a stage rise (`ChunkStageChangedEvent`) queues the chunk; each tick the level's pass walks up to 8 block layers of the current chunk bottom to top (`LAYER_BUDGET`), and a section that holds no air, or only air with only air above, below and beside it, costs one unit and is skipped (a typical overworld chunk takes about 20 ticks). A chunk that finished is visited again 1200 ticks later (`REVISIT_TICKS`) while it stays loaded, so a wall a player builds or a face mined open in a festering chunk is overgrown within about a minute. A chunk that unloads mid-way starts again from its load event; one that only drops below full status resumes when it is back. Nothing is saved: a chunk that loads at a higher stage gets that stage's veins at once (catch-up). Frozen ticks (`/tick freeze`) grow nothing.
- **Events:** `BlockFesteredEvent` once per block that gained faces (old state air or a vein, new state the vein; Public API, below).

Block tags, `data/<ns>/tags/block/<name>.json`:

| Tag | Default | Meaning |
| --- | --- | --- |
| `#remission:vein_resistant` | empty | Blocks veins never grow on (a face toward one is never added). The pack fills it, for example with a ward block or a sealed base material. Veins already on a block stay if it is added later. |
| `#remission:infectious` | `festering_grass`, `festering_short_grass`, `festering_tall_grass`, `festering_fern`, `festering_large_fern`, `festered_stone`, `festered_deepslate`, `festering_vein` | Blocks that infect mobs by contact (below), anywhere, festering chunk or not. |

**Contact infection** (`infection/Contact`, `ContactTracker`, `ContactInfection`, server side): a `Mob` is in **contact** when a block in `#remission:infectious` reaches its bounding box grown by 0.001 blocks (standing on it, inside it, or pressed against it; the block's outline shape counts, so a floor vein touches what stands in its block), or when water (`#minecraft:water` fluid) reaches into its bounding box and the mob's chunk is at or above `[infection] festeringLandMinStage` (fish, squid, drowned, axolotls, anything swimming or wading; the water itself is not replaced). The query `Contact.of(entity)` (`Kind.NONE`, `BLOCK`, `WATER`) and `Contact.inContact(entity)` work for any entity, players included, read loaded chunks only; contact viral load (P5.2, Viral load above) reuses them for players. They are internal, not API. Inside a quarantine pylon's ward (P5.2, below) no mob counts as in contact.

- Cadence: one `ContactTracker` per server level on `LevelTickEvent.Post`. Every 10 ticks a pass snapshots the level's mobs and checks up to 64 per tick, ending the tick early once 32 have become hosts (`ContactSettings.INTERVAL_TICKS`, `MOB_BUDGET`, `INFECTION_BUDGET`; not configurable), so a touch shorter than 10 ticks can go unseen. Each checked mob in contact gains the game ticks since the previous pass started (nothing on the first pass); one without contact loses them. Mobs in chunks that are not entity-ticking are skipped.
- Eligibility is festering land infection's (`Infection.canInfectFromFesteringLand`): not immune, not a host, and its profile does not set `conversion.festering_land` to false; an ineligible mob's contact is cleared. **Players never take contact infection** (only `Mob`s are checked); viral load (P5.1) is the player system.
- Infection: at `contactTicks` the contact is cleared and the mob becomes a host at the first host stage through `Infection.infectFromContact`, in place (not healed) or through its profile's `convert_to`, with `CorruptionCause.CONTACT` on `HostCorruptedEvent`. Festering land exposure (above) is unchanged and runs alongside: the two counters are separate.

Entity attachment `remission:contact_exposure` (`RemissionAttachments.CONTACT_EXPOSURE`): int ticks ≥ 0, saved with the mob (skipped at 0), server only, not synced or copied on conversion.

Config `config/remission-common.toml` (read at the start of each pass):

| Section | Key | Type | Default | Meaning |
| --- | --- | --- | --- | --- |
| `[festering]` | `veins` | bool | true | Vein growth on or off. Off grows nothing more; grown veins stay. |
| `[festering]` | `veinMinStage` | int 1–8 | 1 | Lowest chunk festering stage that grows veins. Above the number of stages, none do. |
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

## Purification and remission zones (built, P5.2)

`com.cubiecode.remission.purification`, server side, loaded chunks only. Mechanics and costs: docs/ARCHITECTURE.md (Purification and remission zones).

**Lowering land and remission.** Every purification tool lowers a chunk's Strain level (`chunk_corruption`, Festering land above) through one path: the level is stored with the current game time, `ChunkStageChangedEvent` fires when the stage falls, and `festered_stage` drops to the new stage so the stage work festers the chunk again if the Strain returns. A chunk lowered from above 0 to 0 **enters remission**: chunk attachment `remission:chunk_remission` (a long, the game time it entered; saved with the chunk, server only, not synced) is set, its remaining festered blocks are queued for a cleanse, `ChunkEnteredRemissionEvent` fires and nearby players are credited (`remission:region_cleansed`, `remission:chunks_cleansed`, above). A chunk that never festered is never in remission. Any later rise of its level (spread) removes the attachment; there is no leave event. While in remission, catch-up after a reload counts missed spread passes from the remission time. A chunk in remission that loads is checked again and anything festered in it is cleansed. Read it with `CorruptionQueries.isInRemission` (Public API, below).

**Cleansing.** A block is cleansed into its `remission:cleanses_into` entry, keeping the state properties both blocks share; a `remission:festering_vein` is cleared to air; blocks with block entities and blocks without an entry are left alone. One `BlockPurifiedEvent` per changed block; flags `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE` (no neighbour updates). The other half of a two-block plant is cleansed with it (P4.16), so a cleanse that reaches only one half never leaves a broken plant. A whole-chunk cleanse also turns every fester biome cell (`remission:fester` and every `fester_variant` target) back into the biome the level's biome source generates there, and clears `festered_stage`.

Data map `data/<ns>/data_maps/block/cleanses_into.json` (`remission:cleanses_into`, NeoForge data map, server only, `/reload`-able; keys are block ids or tags):

```json
{"values": {"remission:festering_grass": {"block": "minecraft:grass_block"}, "#counterstrain:festered_planks": {"block": "minecraft:oak_planks"}}}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `block` | block id | The clean block it turns back into. |

Defaults: `festering_grass` → `minecraft:grass_block`, `festering_short_grass` → `minecraft:short_grass`, `festering_tall_grass` → `minecraft:tall_grass`, `festering_fern` → `minecraft:fern`, `festering_large_fern` → `minecraft:large_fern`, `festered_stone` → `minecraft:stone`, `festered_deepslate` → `minecraft:deepslate`. Which block festered is not recorded, so a pack that adds `festers_into` targets adds their `cleanses_into` entries too (a target without one is never cleansed), and festered granite cleanses into stone.

**Quarantine pylon** `remission:quarantine_pylon` (block and block item; pickaxe, hardness 5, light 10, pistons cannot move it; drops itself; default recipe: iron ingots around a lightning rod over a handheld purifier). Indexed per dimension in saved data `remission_pylons` (`data/remission_pylons.dat`, a long array of positions); its block entity holds no data and re-indexes a pylon a structure placed when it loads, so a pack's crash site can ship one. Its **ward** is every chunk within `[purification] pylonRadius` chunks of the pylon's chunk (Chebyshev, a square; 5 by 5 chunks by default). Inside a ward:

- the Strain does not spread into a chunk (live or catch-up, and a nidus there does not grow its own chunk); steps through the ward to chunks behind it hold once the ward's levels fall below `[spread] spreadThreshold`;
- the festering stage work (surface and caves) and festering vein growth skip it;
- contact infection does not count for mobs in it (their contact decays);
- placing the pylon queues every loaded chunk of the ward for a cleanse, and a warded chunk is cleansed again whenever it loads;
- every `[purification] intervalTicks` (aligned to the level's game time) each loaded warded chunk loses `pylonStep`, once however many wards overlap it, until it enters remission (cause `PYLON`).

Breaking or removing a pylon ends its ward at once; chunks no longer warded are queued for the stage work and vein growth, which bring them back to their current stage. Festering land exposure (P4.7) and land viral load are not stopped by the ward itself; they stop as the land drains.

**Handheld purifier** `remission:handheld_purifier` (item, 256 uses, 10-tick cooldown; default recipe: glass over a copper ingot between iron ingots). Used on a block: cleanses every festered block and vein in the cube `purifierRadius` around it (cause `PURIFIER`) and lowers that block's chunk by `purifierStep`; `ChunkEnteredRemissionEvent.getPlayer()` names the user when that finishes the chunk. Used on a host: lowers its Strain level by `purifierHostStep` through `Corruption.set` with `CorruptionCause.PURIFICATION`. A use that changes nothing costs no durability. Purifier fuel (`#remission:purifier_fuel`, P7.3) repairs it in an anvil.

**Mendroot** `remission:mendroot` (block and block item; a plant on `#minecraft:dirt` or farmland, festering grass included; no collision, breaks instantly, drops itself; default recipe: wheat seeds, bone meal and glow berries make two). Planting it schedules a block tick (saved with the chunk); every `mendrootIntervalTicks` it cleanses the cube `mendrootRadius` around it (cause `MENDROOT`) and lowers its chunk by `mendrootStep`. Anything that places blocks from an item can plant it.

**Remission zone bonuses** (chunks in remission): natural monster spawns fail (`MobSpawnEvent.PositionCheck`, spawn type `NATURAL`, category `monster`; spawners, structures, events and surges unaffected); a crop growth attempt that would fail succeeds with chance `growthChance` (`CropGrowEvent.Pre`); a block in `#remission:remission_yields` broken there drops one more of each item stack with chance `yieldChance` (`BlockDropsEvent`).

| Tag | Default | Meaning |
| --- | --- | --- |
| `#remission:remission_yields` | `#minecraft:crops` | Blocks whose drops the yield bonus raises. |

Config `config/remission-common.toml` (read when each pass, use or bonus runs):

| Section | Key | Type | Default | Meaning |
| --- | --- | --- | --- | --- |
| `[purification]` | `intervalTicks` | int 20–72000 | 200 | Game ticks between pylon passes. |
| `[purification]` | `pylonRadius` | int 0–8 | 2 | Chunks a pylon wards around its own chunk, in a square. |
| `[purification]` | `pylonStep` | int 1–100 | 5 | Strain level each warded chunk loses per pass. |
| `[purification]` | `purifierRadius` | int 0–8 | 2 | Blocks around the used block the handheld purifier cleanses (a cube). |
| `[purification]` | `purifierStep` | int 0–100 | 1 | Strain level that block's chunk loses per use. |
| `[purification]` | `purifierHostStep` | int 0–100 | 34 | Strain level a host loses per use (about one host stage). |
| `[purification]` | `mendrootRadius` | int 0–8 | 3 | Blocks around a mendroot it cleanses (a cube). |
| `[purification]` | `mendrootStep` | int 0–100 | 1 | Strain level its chunk loses per cleanse. |
| `[purification]` | `mendrootIntervalTicks` | int 20–72000 | 600 | Game ticks between a mendroot's cleanses. |
| `[remission_zones]` | `spawnSafety` | bool | true | Natural monster spawns fail in chunks in remission. |
| `[remission_zones]` | `growthChance` | number 0–1 | 0.25 | Chance a failing crop growth attempt succeeds there. |
| `[remission_zones]` | `yieldChance` | number 0–1 | 0.25 | Chance a `#remission:remission_yields` block broken there drops one more of each item. |

With the defaults a pylon brings a stage 4 chunk (level 100) into remission in 20 passes (about 3 minutes 20 seconds), a handheld purifier needs 100 uses for the same chunk, and one mendroot about 50 minutes. Lang keys: `item.remission.handheld_purifier` (and `.desc`), `block.remission.quarantine_pylon`, `block.remission.mendroot`, `stat.remission.chunks_cleansed`. Gametests: `PurificationTests`.

## Reclaimers (built, P7.4)

`com.cubiecode.remission.purification` (`ReclaimerBlock`, `ReclaimerBlockEntity`, `ReclaimerMenu`, `ReclaimerSettings`), server side, loaded chunks only. Mechanics, screen and measured cost: docs/ARCHITECTURE.md (Reclaimers).

**Block** `remission:reclaimer` (block and block item; pickaxe, hardness 5, light 10, pistons cannot move it; drops itself and the item in its fuel slot; default recipe `remission:reclaimer`: iron blocks, a lightning rod, two handheld purifiers, a quarantine pylon and a redstone block). Every `intervalTicks` of game time (staggered by block position, so reclaimers do not all run on one tick), with at least `energyPerOperation` FE and fuel, it lowers the nearest loaded festering chunk within `radius` chunks of its own (a square; squared distance between chunk positions, ties by z then x) by `drainStep` through the same path as every purification tool (Purification and remission zones, above), so a chunk at 0 enters remission and gets the full cleanse. A chunk holding a live nidus (one in the nidus index) is never drained. It does **not** ward: spread, festering, veins and contact infection go on in its reach, so a live nidus holds land against it. An operation with nothing to drain uses no energy or fuel.

**Events:** `ChunkEnteredRemissionEvent` and the cleanse's `BlockPurifiedEvent`s carry `PurificationCause.RECLAIMER` (no player; the chunk events' credit goes to nearby players as for any remission), so listeners (and P7.5's variants) can tell a reclaimer from the other tools. No new event.

**Fuel and capabilities.** Standard block capabilities on every side (and the null side): `Capabilities.ItemHandler.BLOCK` (one slot, accepts only `#remission:purifier_fuel` items, extracts nothing), `Capabilities.FluidHandler.BLOCK` (one 4000 mB tank, fills only with fluids in the fluid tag below and only while `fuelPerBucket` is above 0; valid fuel never drains out, but a fluid in the tank that is no longer fuel, because a `/reload` dropped it from the tag or `fuelPerBucket` was set to 0, is never burned and can be drained out by pipes or an empty bucket, so the tank never jams), `Capabilities.EnergyStorage.BLOCK` (receives up to `maxEnergyInput` per insert into a buffer of `energyCapacity`, extracts nothing). A fluid container used on the block pours into the tank. When the fuel left runs out an operation burns one fuel item, or, with the slot empty, 1000 mB of fuel fluid.

| Tag | Default | Meaning |
| --- | --- | --- |
| item `#remission:purifier_fuel` | `remission:purifier_fuel` (P7.3) | Items the reclaimer burns, `fuelPerItem` operations each. |
| fluid `#remission:purifier_fuel` (`data/<ns>/tags/fluid/purifier_fuel.json`) | empty | Fluids the reclaimer burns, `fuelPerBucket` operations per 1000 mB. Remission ships no fuel fluid; a pack adds one (Counterstrain: P10.2). |

**Comparator:** 0 while none of its reach is in remission, otherwise `1 + floor(14 * chunks in remission / chunks in reach)`, so 15 only when all of it is. Updated each operation; saved, so it holds across a reload, and refreshed by a survey on the first tick after loading; unloaded chunks count as not in remission.

**Saved fields** (block entity NBT, also settable with `/data merge block`): `items` (the fuel slot, `ItemStackHandler` format), `tank` (`FluidTank` format), `energy` (int FE), `fuel` (int, operations left from the last item or bucket burned), `signal` (int 0–15, the comparator output).

Config `config/remission-common.toml` (read on each operation or insert):

| Section | Key | Type | Default | Meaning |
| --- | --- | --- | --- | --- |
| `[reclaimers]` | `radius` | int 0–8 | 4 | Chunks drained around its own chunk, in a square (4 = 9 by 9). |
| `[reclaimers]` | `intervalTicks` | int 1–72000 | 20 | Game ticks between operations. |
| `[reclaimers]` | `drainStep` | int 1–100 | 10 | Strain level the drained chunk loses per operation. |
| `[reclaimers]` | `energyPerOperation` | int 0–10000000 | 4000 | FE one operation uses. |
| `[reclaimers]` | `energyCapacity` | int 1–100000000 | 200000 | FE it stores. |
| `[reclaimers]` | `maxEnergyInput` | int 1–10000000 | 2000 | FE it accepts per insert. |
| `[reclaimers]` | `fuelPerItem` | int 1–100000 | 16 | Operations one fuel item runs. |
| `[reclaimers]` | `fuelPerBucket` | int 0–100000 | 16 | Operations 1000 mB of fuel fluid runs; 0 refuses fluid fuel. |

With the defaults one reclaimer brings a 9 by 9 chunk valley at stage 4 (level 100, no nidus) into remission in 810 operations, about 13.5 minutes, using 3.24 million FE (200 FE per tick) and about 51 fuel items. Lang keys: `block.remission.reclaimer`, `gui.remission.reclaimer.*` (the screen also reuses `gui.remission.machine.energy`, `.tank` and `.tank_empty`).

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
- Base classes are abstract and cannot be listened to: `HostEvent` (extends NeoForge `LivingEvent`; `getEntity()` is the `LivingEntity`) and `FesteringLandEvent` (extends `Event`; `getLevel()` is the `ServerLevel`). Listen to the concrete events. `ViralLoadChangedEvent` (P5.1) extends NeoForge's `PlayerEvent` directly. `RevenantEvent` (P5.3; extends `LivingEvent`, `getEntity()` is the revenant, `getOwner()` its owner's UUID) is the abstract base of the revenant risen and slain events.
- Levels are ints 0–100 (`CorruptionQueries.MAX_LEVEL`) for entities and chunks; viral load is a float 0–100 (`CorruptionQueries.MAX_VIRAL_LOAD`). Host stages come from `[hosts] stageThresholds`, chunk (festering) stages from `[festering] stageThresholds`.

| Event | Fields (getters) | Fired | Cancellable |
| --- | --- | --- | --- |
| `HostCorruptedEvent extends HostEvent` | `getEntity()`, `getOldLevel()`, `getOldStage()`, `getNewLevel()`, `getNewStage()`, `getCause()`; `wasHost()`, `isHost()`, `becameHost()` | After `Corruption.set` raises an entity's level (or, at the same level, its stage after a threshold change), with attachment, host attributes and host goals already applied. Every increase, not only the first; `becameHost()` marks the non-host to host change. Never for immune entities (their state does not change) or no-op sets. | no |
| `HostCleansedEvent extends HostEvent` | as above; `isFullyCleansed()` (new level 0) | After `Corruption.set` lowers an entity's level (or stage), including partial cleansing. | no |
| `ChunkStageChangedEvent extends FesteringLandEvent` | `getLevel()`, `getChunk()` (`ChunkPos`), `getOldLevel()`, `getNewLevel()`, `getOldStage()`, `getNewStage()` | After the spread engine stores a chunk level whose festering stage differs from the old one: live passes and catch-up; since P5.2 also after purification lowers one (stages then fall). A catch-up posts once per chunk, so stages can jump by more than one; changes within a stage post nothing. | no |
| `NidusDestroyedEvent extends FesteringLandEvent` | `getLevel()`, `getPos()` (the nidus block), `getPlayer()` (`Optional<ServerPlayer>`: who destroyed it, P4.10), `getContributors()` (immutable `List<UUID>`, may include offline players: every player credited, in the order they first damaged it, the destroyer included) | After a nidus leaves the level's index: its block was broken, replaced or removed, or a spread pass found an index entry without its block. Its land keeps its level. A player and contributors only when a player destroyed it (the final blow of a fight, a creative break, or a break at `[nidi] vitality` 0; Nidus encounters, above); empty for other removals. To protect a nidus, cancel `NidusDamagedEvent` (and the block break for creative players). When the block was removed, it is posted from the block's `onRemove` inside `setBlockState`: listeners must not set the block at `getPos()` in the listener (defer it). | no |
| `BlockFesteredEvent extends FesteringLandEvent` (P4.2) | `getLevel()`, `getPos()`, `getOldState()`, `getNewState()` | After a block in a festering chunk is replaced through `remission:festers_into` (Festering stages, above), or gains festering vein faces (P4.8; old state air or a vein), once per block: it is block-scoped, so a chunk's stage batch posts one per replaced block, spread over the ticks the batch takes. The biome flip posts nothing. Listeners must not set the block at `getPos()` in the listener. | no |
| `SurgeStartedEvent extends FesteringLandEvent` (P4.3) | `getLevel()`, `getOrigin()` (the nidus), `getIntensity()` (1–10), `getEndTime()` (game time it is due to end) | When a surge's warning runs out and it starts, after its clouds are released and before its first wave (Surges and miasma clouds, above). Never for a surge stopped during its warning. | no |
| `SurgeEndedEvent extends FesteringLandEvent` (P4.3) | `getLevel()`, `getOrigin()`, `getIntensity()`, `getReason()` (`ELAPSED`, `NIDUS_DESTROYED`, `STOPPED`, `SUPERSEDED`: a nidus under attack started a surge in its place, P4.10, with no survivors when that nidus was its own origin; treat unknown values as `STOPPED`), `getSurvivors()` (immutable list of the `ServerPlayer`s awarded `surge_survived`) | When a started surge ends, after acceleration and waves stopped and survivors were awarded. Its clouds keep drifting. | no |
| `NidusDamagedEvent extends FesteringLandEvent` (P4.10) | `getLevel()`, `getPos()`, `getKind()` (`STRIKE`, `PROJECTILE`, `EXPLOSION`), `getPlayer()` (`Optional<ServerPlayer>`: the striker, the projectile's owner or the player behind the explosion), `getDirectEntity()` (the projectile or the explosion's direct source, or null), `getAmount()`/`setAmount(float)` (vitality lost, after the projectile or explosion fraction; negative or NaN become 0) | Before a nidus takes damage a player is behind (Nidus encounters, above; never for hosts' or unattributed damage, fake players, strikes within the strike cooldown, or while `[nidi] vitality` is 0). Projectile and explosion damage is then capped so it leaves at least 1 vitality. | yes: cancelling (or amount 0) means no damage and no credit for the player |
| `BlockPurifiedEvent extends FesteringLandEvent` (P5.2) | `getLevel()`, `getPos()`, `getOldState()` (the festered block or vein), `getNewState()` (its `cleanses_into` target, or air for a vein), `getCause()` (`PurificationCause`) | After a block is cleansed (Purification and remission zones, below), once per block: block-scoped, so a chunk's budgeted cleanse posts one per changed block over the ticks it takes. The biome restore posts nothing. Listeners must not set the block at `getPos()` in the listener. | no |
| `ChunkEnteredRemissionEvent extends FesteringLandEvent` (P5.2) | `getLevel()`, `getChunk()` (`ChunkPos`), `getCause()` (`PurificationCause`), `getPlayer()` (`Optional<ServerPlayer>`: the player whose handheld purifier finished the chunk; empty otherwise) | Once when purification lowers a chunk's Strain level from above 0 to 0, after the level and the remission mark are stored and before its remaining blocks are cleansed. A chunk that festers again leaves remission without an event and can enter it again. | no |
| `ViralLoadChangedEvent extends PlayerEvent` (P5.1) | `getEntity()` (the `ServerPlayer`), `getOldLoad()`, `getNewLoad()` (floats 0–100), `getCause()` (`EXPOSURE`: land, water or a miasma cloud; `DRINK`; `DECAY`; `NIDUS`: a nidus's lash, P4.10; `SERUM` (P7.3): a serum dose; `PROTOTYPE` (P7.3): a Counterstrain prototype backfiring nearby; `OTHER`; treat unknown values as `OTHER`), `isRising()` | After a player's viral load changed and was stored (Viral load, above): at most once per player per update (once a second) plus once per drink, serum dose or prototype backfire. Not when only the trend changed, nor for the reset to 0 on death. | no |
| `RevenantRisenEvent extends RevenantEvent` (P5.3) | `getEntity()` (the revenant), `getOwner()`, `getPlayer()` (the dead `ServerPlayer`, not yet respawned), `getSource()` (the killing `DamageSource`), `getDeathPos()`, `getItems()` (copies of the held stacks), `getExperience()` | After a player's Strain death raised a revenant (Revenants, above): it is in the level as a host and in the index. | no |
| `RevenantSlainEvent extends RevenantEvent` (P5.3) | `getEntity()`, `getOwner()`, `getSource()`, `getKiller()` (the `ServerPlayer`, or null for a command), `isReclaimed()` (the killer may pick its items up: the owner or a teammate by the team query) | After a revenant with an owner died: its items and experience are dropped, it left the index, and a reclaim was awarded. | no |
| `StrainHazardEvent extends Event` (P5.7) | `getServer()`, `getHazard()`, `getPlayer()` (`Optional<ServerPlayer>`: always present for a player hazard; for a block hazard the placer or an online teammate, or the nearest player for a block with no placer, empty under `ALWAYS` with nobody of the team online), `getLevel()`, `getPos()` (`Optional`s, present for a block hazard), `getPlacer()` (`Optional<UUID>`), `isBlockHazard()` | Before a hazard applies, from `StrainHazards.applies` and `appliesAtBlock`, only when nothing ruled it out already (the team holds no countermeasure; for a block, the presence rule is met). Hazard systems may ask every tick: keep listeners cheap. | yes: cancelling holds the hazard back (`applies` returns false). A listener cannot force a hazard past a countermeasure: it is not asked then |
| `CountermeasureGrantedEvent extends PlayerEvent` (P5.7) | `getEntity()` (the `ServerPlayer`), `getHazard()`, `getCause()` (`PROTOCOL`, `TEAM`, `COMMAND`, `OTHER`; treat unknown values as `OTHER`) | After a player gained a countermeasure and it was stored, once per player who gained it (a grant posts for the player, then for each online teammate with `TEAM`). Never for one already held. | no |
| `StrainSpreadAttemptEvent extends FesteringLandEvent` | `getLevel()`, `getFrom()`, `getTo()` (`ChunkPos`), `getAmount()` (level gain at multiplier 1, capped at the target), `isCatchUp()`, `getRateMultiplier()`/`setRateMultiplier(double)` (default 1; negative or NaN become 0) | Before every chunk-to-chunk step: each live step into a loaded chunk below its target whose parent passed the threshold, and once per nidus in reach when a reloaded chunk catches up (`isCatchUp()`, `getAmount()` covers every missed pass, `getFrom()` may be unloaded). The growth of a nidus's own chunk is not a spread attempt, and a step from a loaded parent below the threshold is held without an event (live and catch-up). | yes: cancelling (or multiplier 0) stops this step; chunks reached through it stay clean while the cancelled chunk is loaded. A step from an unloaded parent is not held (the parent counts as at its target), so keep a firebreak chunk loaded or cancel the steps behind it too |

Spread rate: the gain is `amount * multiplier`, capped at the chunk's target. A fractional part is added as one more level with that probability, drawn from a hash of the two chunks and the game time, so slowed spread still advances on average and the same state and ticks give the same levels. Listeners compose by multiplying (`event.setRateMultiplier(event.getRateMultiplier() * 0.5)`). `last_update_game_time` moves to now only when a catch-up changed the chunk's level: passes shortened by a multiplier below 1 are then not credited again (the chunk ends below where full-rate spread would have taken it and continues from there), while a catch-up whose steps were all cancelled, scaled to 0 or held keeps the old time, so a later reload credits that history again, still capped at the target. A parent caught up first (see the rules above) contributes its level after its own catch-up, which converges on the same targets as live spread rather than replaying exact history.

Re-entrancy: a host event listener that changes the same entity's level (`Corruption.set`) posts a nested event at once; later listeners of the outer event then see its stale values while queries return the nested change. Defer such changes to a later tick.

`CorruptionCause` (enum, `HostEvent.getCause()`): `INFECTION` (killed by a host and infected in place or converted), `COMMAND` (`/remission infect` or `/remission cleanse`), `FESTERING_LAND` (prolonged exposure to festering land, P4.7), `SURGE` (spawned as a host by a surge; P4.3), `CONTACT` (touched an infectious block or swam in festering water; P4.8), `PURIFICATION` (a handheld purifier used on a host; P5.2), `REVENANT` (rose as a revenant; P5.3), `OTHER` (any other caller). Later items insert their causes before `OTHER`, so never persist a cause by ordinal; treat unknown values as `OTHER`.

`PurificationCause` (enum, P5.2, `BlockPurifiedEvent.getCause()` and `ChunkEnteredRemissionEvent.getCause()`): `PURIFIER` (a handheld purifier), `PYLON` (a quarantine pylon's ward), `MENDROOT`, `PROTOTYPE` (a Counterstrain prototype landing, P7.3), `RECLAIMER` (a reclaimer's drain and the cleanse it queues, P7.4), `OTHER` (anything else, including the re-check of a chunk in remission when it loads). Same rules: inserted before `OTHER`, never persisted by ordinal.

Queries, `CorruptionQueries` (static):

| Method | Returns | Side |
| --- | --- | --- |
| `isHost(Entity)` | whether a living entity's stored host stage is ≥ 1; false for non-living entities | both (hosts are synced) |
| `hostLevel(Entity)`, `hostStage(Entity)` | stored level and host stage; 0 for clean or non-living entities | both |
| `chunkLevel(ServerLevel, ChunkPos)`, `levelAt(ServerLevel, BlockPos)` | Strain level of a loaded chunk (of the chunk holding the position); 0 if clean or not loaded (never loads a chunk) | server |
| `chunkStage(ServerLevel, ChunkPos)`, `stageAt(ServerLevel, BlockPos)` | festering stage of that level (every block in a chunk shares its chunk's stage) | server |
| `chunkStageFor(int level)`, `maxChunkStage()` | stage of a chunk level under the current config; the number of chunk stages | server config |
| `isInRemission(ServerLevel, ChunkPos)` (P5.2) | whether a loaded chunk is in remission (purified from festering to level 0 and not festering since); false if it never festered, is festering or is not loaded (never loads a chunk). P8.2's remission-zone map layer reads it | server |
| `isRevenant(Entity)` (P5.3) | whether the entity is a `remission:revenant` | both |
| `viralLoad(Player)` (P5.1) | the player's viral load, 0–100 (`MAX_VIRAL_LOAD`) | server for any player; a client knows only its own player's (others read 0) |

Config `config/remission-common.toml`, `[festering] stageThresholds = [1, 25, 50, 75]`: 1–8 ints in 1–100, order-independent. A chunk's stage is the number of thresholds its level meets (0 clean, 1–4 with the defaults; veins grow from 1 (`veinMinStage`), the default `festers_into` map turns grass and fern plants at 2 and grass blocks and stone at 3, and the last stage flips the biome; Festering stages, above). Read whenever a level changes or a stage is queried; changing it posts no events for chunks whose stage moved.

Team query, `TeamQueries` (static, P4.12): the one answer to "are these two players teammates?". Every Remission rule that involves teammates asks it (DESIGN, Teams and multiplayer); Remission has no teams of its own.

| Method | Returns | Side |
| --- | --- | --- |
| `areTeammates(MinecraftServer server, UUID a, UUID b)` | true when `a` equals `b`; otherwise the cached or freshly looked-up answer (`TeamLookupEvent`), false when nothing answers. Either player may be offline | server thread |
| `areTeammates(Player player, UUID other)` | the same for an online player and a UUID (for example an offline owner); on the client side, true only for the player's own UUID | server (client: self only) |
| `areTeammates(Player a, Player b)` | the same for two players | server (client: self only) |
| `invalidate(UUID player)` | drops cached answers involving that player; call it when the player's team changes | server thread |
| `invalidateAll()` | drops every cached answer; call it for bulk changes (a team deleted, teams reloaded) | server thread |
| `CACHE_TICKS` (`int`, 100) | how long an answer is reused, in server ticks (5 s) | |

`TeamLookupEvent extends Event` (P4.12, not cancellable): `getServer()`, `getFirst()`, `getSecond()` (`UUID`s, never equal), `hasAnswer()`, `areTeammates()` (false until answered), `setTeammates(boolean)` (replaces any earlier answer). Posted on `NeoForge.EVENT_BUS` from `areTeammates` for two different players when the pair has no fresh cached answer; never for a player and themselves (always teammates). Rules for sources:

- Answer from UUIDs: either player may be offline, so never require a `ServerPlayer`. Leave the event unanswered for players the source does not know; with no answer the players are not teammates, so without a source every player is their own team.
- Answers must be symmetric: the cache is per unordered pair, so the answer for `(a, b)` is also returned for `(b, a)`.
- The last `setTeammates` wins. A source that fills gaps checks `hasAnswer()`; one that overrides another listens at a lower priority (FTB Teams answers at `NORMAL`).
- Answers are cached per pair for `CACHE_TICKS` server ticks. A source whose teams change calls `TeamQueries.invalidate(player)` or `invalidateAll()` so the change shows at once; otherwise it shows within `CACHE_TICKS`. The cache is cleared when the server stops.

FTB Teams source (`integration/ftbteams/`, optional, FTB Teams 2101.1+): when FTB Teams is loaded, players it knows (anyone who has joined the world, online or not) are teammates exactly when FTB Teams puts them in the same team (`TeamManager.getTeamForPlayerID`: the same party, or each alone in their own personal team, which makes two solo players not teammates). Allies are not teammates. Players FTB Teams does not know are left unanswered. It invalidates a player's answers on FTB Teams' `PLAYER_CHANGED` event (every join, leave, party creation and disband) and all answers on team deletion and team manager load or unload. Packs without FTB Teams answer the event from KubeJS once P8.3's bindings exist.

Hazard queries, `StrainHazards` (P5.7): see Strain hazards and countermeasures, above.

Bestiary queries, `BestiaryQueries` (static, P6.4): the team's bestiary progress for quests and scripts (Bestiary, above). Player methods work on both sides (a client knows only its local player's team progress); UUID methods are server only and accept offline players.

| Method | Returns | Side |
| --- | --- | --- |
| `hostTypeCount()` | the number of host types the bestiary lists (the "N" of "X of N host types scanned") | both |
| `teamProgress(Player)` / `teamProgress(MinecraftServer, UUID)` | `Map<ResourceLocation, Integer>`: entity type id to the highest host stage any member of the player's team scanned | both / server |
| `teamHighestStage(Player, ResourceLocation type)` | that stage for one type; 0 when no member scanned it | both |
| `teamHostTypesScanned(Player)` / `teamHostTypesScanned(MinecraftServer, UUID)` | the team total: listed host types any member scanned (the "X") | both / server |
| `ownHostTypesScanned(Player)` | listed host types the player scanned themselves | both (client: local player) |

Later items append their events and queries here when built.

## KubeJS event bindings (planned, P8.3)

The API events above exposed to KubeJS server scripts through an optional `integration/kubejs/` plugin (no hard KubeJS dependency), with cancellation and the spread multiplier writable from script. Event group and method names are fixed in P8.3 and mirrored in kubejs-scripting.
