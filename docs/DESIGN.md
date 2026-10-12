# Counterstrain and Remission design

## Overview

Inspired by Blightfall; Counterstrain uses its own vocabulary and reuses none of Blightfall's or Thaumcraft's terms.

The player crash-lands on a world overrun by **the Strain**, an engineered organism spreading through mobs and land, and works up a tech tree toward **the Counterstrain**, a self-spreading cure. The Strain is never a static biome to avoid: it grows from nidi, turns mobs into hosts and land into festering ground, adapts to the player's tools, and is finally beaten by turning its own spread engine against it.

Two products, one repository:

- **Remission** (mod): generic Strain, purification, research and cure systems. Works with vanilla and modded mobs, ships sane defaults, exposes data, scripting and API surfaces, and never depends on the pack.
- **Counterstrain** (pack): the story, quest chapters, crash-site start, recipes, progression gating, balance and mod integrations, built with KubeJS, FTB Quests and datapacks on top of Mekanism, AE2, Create, Ars Nouveau and the [integrations](#pack-integrations) below.

## Glossary

Every doc, quest, lang string, lore entry, command and content ID uses these terms. When a new concept needs a name, add it here in the same change.

| Concept | Term | IDs / examples |
| --- | --- | --- |
| The corruption | **the Strain** | prose: "Strain-infected", "the Strain spreads" |
| Spread sources | **nidus** (plural **nidi**) | block `remission:nidus`, item `remission:nidus_core` |
| Infected mobs | **hosts** | "a host cow"; the bestiary lists host types |
| Mobs the Strain cannot take | **immune** | tag `#remission:immune` (bosses by default); "the wither is immune" |
| How far the Strain has taken a host | **Strain level** (0–100) and **host stage** | config `hosts.stageThresholds`; `CorruptionState` fields `level`, `stage` |
| Infected land and its stages | **festering** | `remission:festering_grass`, `remission:festering_log`, `remission:festering_leaves`, `remission:festered_stone`, `remission:festered_deepslate`, biome `remission:fester`; data map `remission:festers_into` |
| The fester biome a particular source biome turns into | **fester variant** | data map `remission:fester_variant`; "plains festers into `remission:fester`, a pack's lavender field into its own fester variant" |
| Growth that creeps over the faces of blocks in festering land, player builds included | **festering veins** | block `remission:festering_vein`; block tag `#remission:vein_resistant`; config `[festering] veins`, `veinMinStage`; "veins are creeping up the wall" |
| How much punishment a nidus takes before it is destroyed; it fights back as it falls | **nidus vitality** | config `[nidi]` (planned, P4.10); `NidusDamagedEvent`; "the nidus is at half vitality" |
| Large powered purifier that drains a wide area while fed energy and fuel | **reclaimer** | block `remission:reclaimer` (planned, P7.4); "run a reclaimer over the valley" |
| Wreck site loot that opens only once its chunk is in remission | **sealed caches** | pack feature (P11.4); "reclaim the valley to open the sealed cache" |
| Festering a new chunk starts with, from world generation | **initial festering** | config `[spread]` (planned, P4.9); "initial festering is off by default" |
| A mob turning into a host by touching festered blocks or swimming in water in festering land | **contact infection** | block tag `#remission:infectious`; config `[infection] contact`, `contactTicks`; cause `CorruptionCause.CONTACT`; entity attachment `remission:contact_exposure` |
| Boundary between festering land and reclaimed land | **frontier** | "hold the frontier" |
| Periodic spread bursts from one nidus, telegraphed by a warning | **surges** | config `[surges]`; trigger `remission:surge_survived`, stat `remission:surges_survived`; `/remission surge start`; entity tag `#remission:surge_hosts`; "the nidus surges at the season change", "survive the surge" |
| Extracted resource | **miasma** | fluid `remission:miasma`; Mekanism chemical `remission:miasma` when Mekanism is present |
| Player exposure meter | **viral load** | player attachment `viral_load`; config `[viral_load]` (including `contact`, `contactGain`: touching an infectious block); trigger `remission:viral_load_changed`; damage type `remission:viral_load` (death message "succumbed to the Strain"); "viral load is at 60", "the viral load meter" |
| Turning festered land, blocks or hosts back | **cleanse** / **purify** (noun **purification**) | data map `remission:cleanses_into`; event `BlockPurifiedEvent`; config `[purification]`; "cleanse the grove", "purify the chunk" |
| Handheld cleansing tool | **handheld purifier** | item `remission:handheld_purifier`; "use the purifier on the veins" |
| Radius wards | **quarantine pylons** | block `remission:quarantine_pylon`; its **ward** (the chunks it protects; config `pylonRadius`); "the base is inside the pylon's ward" |
| Cleansing plants | **mendroot** | block and item `remission:mendroot`; "plant mendroot along the frontier" |
| Reclaimed land | **remission zones** | "chunks in remission"; chunk attachment `remission:chunk_remission`; query `CorruptionQueries.isInRemission`; event `ChunkEnteredRemissionEvent`; trigger `remission:region_cleansed`; stat `remission:chunks_cleansed`; config `[remission_zones]` |
| Resistant mutations | **variants** | "sequence the variant"; `CorruptionState` field `variant`; registry `remission:variant`, trigger `remission:variant_emerged` (planned, P7.5) |
| Endgame cure | **the Counterstrain** | "unstable Counterstrain prototypes", "the final deployment" |
| Samples | host tissue, **nidus cores**, festered soil | `remission:host_tissue`, `remission:nidus_core`, `remission:festered_soil` |
| Machines that process the Strain | **analyzer**, **sequencer**, **synthesizer** | blocks `remission:analyzer`, `remission:sequencer`, `remission:synthesizer`; recipe types `remission:analyzing`, `remission:sequencing`, `remission:synthesizing`; "analyze the sample, sequence the Strain, synthesize the serum" |
| A sequenced record of the Strain, made by the sequencer from a scan and host tissue | **strain sequence** | item `remission:strain_sequence`; the synthesizer's input, from which serums are made (P7.3) |
| Items carried through festering land | **contaminated** (verb: decontaminate) | data component `remission:contaminated` (planned, P10.6) |
| Drifting airborne Strain | **miasma clouds** | entity `remission:miasma_cloud`; released by surges, drifting above festering land; players inside gain viral load |
| The Counterstrain's deployable carrier | **dispersal payload** | block `remission:dispersal_payload`; can also ride a moving Create contraption through the optional integration |
| Research records made by the scanner | **scans**: **host scans** and **block scans** | item `remission:scanner`; data items `remission:host_scan`, `remission:block_scan`; data component `remission:scan_data` |
| A player who died to the Strain, risen as a host carrying their items | **revenant** | entity `remission:revenant`; item `remission:revenant_tracker` (planned, P5.3 and P5.4); "kill your revenant to get your gear back" |
| Hand-built places between nidi: pieces of the survey ship, survivor camps, caches | **wreck sites** | pack structures (P11.4); "the lab wreck site" |
| Found story items that unlock field journal entries | **lab logs** | pack items (P11.4); "find the lab log in the wreck site" |
| The surviving part of the survey ship in orbit that answers supply requests (working concept, P11.1) | **the relay** | "call the relay" |
| A crate of supplies called from the relay | **supply drop** | pack feature (P11.5); "the supply drop is coming in" |
| How much the relay trusts the player or team; spent on supply drops, earned by progress | **standing** | per-team score (P11.5); "standing with the relay is low" |
| One of several start locations of different difficulty | **crash sites** | harness scene `start/crash_site`; "the hard crash site starts next to a nidus" |
| A player's record of host types, filled by host scans | **bestiary** | player attachment `remission:bestiary`; key mapping `key.remission.bestiary`; "12 of 79 host types scanned" |

"Variant" on its own always means a resistant mutation of the Strain, never a kind of host (a **fester variant** is a biome and is always called that in full): scanning a host is a *host scan* (trigger `remission:host_scanned`), not a variant scan.

### Naming rule

- **Player-facing text, content registry IDs (blocks, items, fluids, chemicals, biomes, effects), lang keys, quest text and IDs, lore, advancement triggers, stats and commands** use the glossary terms.
- **Internal Java system names and public API types may keep the generic word "corruption"**: `CorruptionState`, the `corruption` entity attachment, the chunk corruption level, the `remission:is_corrupted` loot condition, the `corruption/` package. Those names read naturally to other mod and pack developers who have never seen the glossary. Do not rename them to Strain terms, and do not "fix" player-facing text back to "corruption".

## Pillars

1. **Any mob can become a host.** Infection is state on the entity, not a new entity type, so every installed mod widens the bestiary.
2. **The land fights back.** Festering is visible, spreads from destroyable nidi (and in Counterstrain covers the world from the start), and keeps moving while you are away.
3. **Progress is reclaimed ground.** Remission zones are the reward and the score; quests and bonuses key off them.
4. **Science, not magic missiles.** You win by sampling, analysing and engineering, using the pack's tech mods.
5. **Mod generic, pack specific.** Every number and story beat the pack might want to change is data, script or an API hook.

## Remission systems

### Hosts (state, not entities)

- A Data Attachment on `LivingEntity` holds corruption level and stage (and later, `variant`). Synced to tracking clients through NeoForge 21.1's built-in attachment sync (decided in P2.1; ARCHITECTURE).
- **Visuals:** a render layer added to every living-entity renderer in `EntityRenderersEvent.AddLayers` re-renders the mob's own model with a Strain overlay (tint, emissive veins, particles by stage).
- **Behaviour:** on `EntityJoinLevelEvent`, host `Mob`s lose passive goals (breed, tempt, flee, follow parent) and gain targeting and melee/ranged attack goals. `Attributes.ATTACK_DAMAGE` (and anything else attacks need) is added to every entity type via `EntityAttributeModificationEvent`. Fallbacks for brain-based mobs (villagers, piglins, axolotls), flying and swimming mobs.
- **Infection:** a kill by a host, touching festered blocks (contact infection, quick), or prolonged time in festering land (exposure, slow) infects the victim. The `convert_to` override can replace the victim with another entity type.
- **Drops:** a Global Loot Modifier gated on the `remission:is_corrupted` loot condition adds host tissue and other samples.
- **Overrides:** `#remission:immune` entity tag; per-mob JSON loaded from datapacks (attributes, behaviour profile, convert-to entity). Schemas: remission-data-formats skill.

### Festering land

- Per-chunk corruption level (chunk Data Attachment) driving visible festering stages: festering grass, festering trees and foliage, festered stone, and finally a biome flip to `remission:fester` (or a per-biome fester variant set by data, so modded biomes fester too).
- **Underground:** a festering chunk is festering at every height. From a late stage its caves fester too, as a skin on the blocks facing cave air (rock inside the ground and ores are left alone, so mining stays viable), looking clearly different from sculk. Caves and underground bases are no refuge.
- **Festering veins** creep over the faces of blocks in festering chunks without replacing them, player builds included, so a base inside festering land is overgrown unless it is warded by a quarantine pylon or cleansed.
- The Strain spreads from **nidi** (blocks or structures). Destroying a nidus stops its spread but does not cleanse land.
- **Nidus encounters** (P4.10): a nidus cannot be mined. Players wear down its **nidus vitality** by striking it, and it fights back as it falls, starting surges from itself and lashing nearby players, so destroying one is the climax of a region.
- **Initial festering** (optional, off by default; P4.9): new chunks start at a configured level, rising with distance from world spawn, outside a safe radius. Land with no nidus in reach holds its level until purified; a live nidus takes purified land back, so nidi must still be destroyed.
- **Catch-up:** unloaded chunks record a timestamp; on load they fast-forward deterministically, bounded per tick.
- **Atmosphere** (P4.11): festering land has its own fog, sky, ambience and music, and fog thickens with the stage, so standing in it feels wrong.
- **Surges:** periodic events that accelerate spread, spawn hosts (in caves under festering land as well as on the surface) and release miasma clouds that drift above festering land, telegraphed to players.

### Viral load and purification

- **Viral load:** a player attachment that rises in festering chunks, with escalating debuffs; serums and gear reduce it. Drinking water from, or swimming in, water in festering chunks also adds viral load, and so does flying through a miasma cloud. Gain from festering land falls off with height above the ground (config: on/off and the falloff curve), so anything airborne, from elytra to airships, can cross festering zones above the worst of it. Below the ground there is no falloff: caves and tunnels under a festering chunk count in full. There are no climate mods: viral load is the only exposure system.
- **Tools by scale:** handheld purifier, quarantine pylons with a radius, large powered purifiers, mendroot. A quarantine pylon is the base ward: inside its radius the Strain does not spread, fester, grow veins or infect by contact, festered blocks and veins are cleansed and the land drains into remission, and the crash-site start is built around one (built in P5.2). The large powered purifier is the **reclaimer** (P7.4): it drains a wide area while fed energy and fuel, but does not ward, so a live nidus fights it.
- **Contact:** touching festered blocks or veins (standing on, in or against them) adds viral load as well, so walking a festering cave is worse than flying over it (P5.2).
- **Remission zones:** purified chunks enter remission and grant bonuses (spawn safety, growth, resource yields; tunable). Built in P5.2. Reclaimed land visibly comes back to life: passive wildlife returns and cleansed surfaces regrow plants (planned, P5.5).
- **Revenants:** a player who dies to the Strain (killed by a host or revenant, by viral load, or anywhere in a festering chunk) rises as a revenant: a host with their skin, wearing their armour and holding their weapon, that keeps their items and experience and stays near where they fell. Other deaths drop items as usual, and `keepInventory` turns revenants off. Anyone can fight a revenant, but its items can be picked up only by its owner, and by their team where the pack defines one (Counterstrain uses FTB Teams), until a timeout frees them. A craftable **revenant tracker**, worn in a Curios slot or carried anywhere in the inventory, shows your revenants as JourneyMap waypoints, or as a HUD pointer without JourneyMap.

### Research

- A scanner turns hosts and blocks into data items (host scans and block scans, built in P6.1). Scanning samples with the scanner is not assigned to a roadmap item; instead the analyzer (P7.2) turns samples into miasma and, for festered soil and nidus cores, block scans.
- A bestiary of host types that grows with installed mods, because host forms are derived from entity types at runtime (built in P6.2): each host scan records the type and its highest scanned host stage for that player, and a key (default M) opens the list.

### Tech chain

- Miasma as a fluid (and Mekanism chemical when Mekanism is present).
- Samples: host tissue, nidus cores, festered soil.
- Machines: analyzer (samples into miasma and scans), sequencer (a scan and host tissue in miasma into a strain sequence), synthesizer (strain sequences into products), each a recipe type packs and KubeJS can edit, fed by hand or through standard item, fluid and energy capabilities (built in P7.2).
- Products: personal serums, purifier fuel, unstable Counterstrain prototypes.
- **Variants** (P7.5): the Strain adapts to the methods used against it near each nidus, becoming resistant to an overused tool or damage type. Scanning and sequencing a variant lets the synthesizer make the serum or fuel that beats it, so research is a loop that returns every region.

### Endgame: the Counterstrain

- The Counterstrain uses the same chunk spread engine in reverse.
- The Strain adapts to the Counterstrain too: prototypes meet variants that resist them (P7.5's system) until the final formula.
- A final deployment defence event, after which the world visibly heals. The Counterstrain is deployed from a **dispersal payload**: a placed block is enough to finish the game. Its API treats the payload as a dispersal source with a position, so a moving holder (a Create contraption or Aeronautics airship, through the optional integration) can carry it instead.

### Quest hooks

- Advancement triggers and stats: region % cleansed, nidus destroyed, host scanned, surge survived (and more as systems land), usable by FTB Quests or any quest mod.
- Hooks for quests to fire Strain events (start a surge, seed a nidus, advance a region).
- Optional JourneyMap overlay of festering land and remission zones (polygon overlays on the full-screen map and minimap).

### Public API and events

Remission exposes generic hooks in `com.cubiecode.remission.api` so integrations, addons and KubeJS build on its systems instead of patching them. The package and its conventions are P4.4; every later system item adds and documents its own events.

- **Events** (NeoForge events on `NeoForge.EVENT_BUS`, server side): host corrupted, host cleansed, block festered, block purified, chunk stage changed, chunk entered remission, nidus destroyed, surge started, surge ended, viral load changed, and a cancellable Strain spread attempt (chunk to chunk, with a rate multiplier) so firebreaks, seasons and wards can slow or stop spread without patching the engine.
- **Queries:** corruption stage at a position or chunk, whether an entity is a host, a player's viral load.
- Types may use "corruption" (naming rule above), for example `HostCorruptedEvent`, `ChunkStageChangedEvent`.

Integrations prefer pack-side KubeJS and datapacks built on these hooks (KubeJS bindings: P8.3). Mod-side integration code lives only in `integration/<modid>/`, uses `compileOnly` APIs and is guarded by `ModList`, the pattern P8.2 uses for JourneyMap. Remission never hard-depends on an integrated mod.

## Counterstrain pack

- **Story:** crash landing, locate the first nidus, learn what the Strain is, engineer the Counterstrain ([lore](LORE.md)). The story deepens as the player explores: quests raise questions that lab logs found at wreck sites answer.
- **Quest loop per region:** locate the nidus → research → gear up → destroy the nidus → purify → next region; the last region ends in the Counterstrain deployment.
- **Exploration:** wreck sites, survivor camps and host-guarded caches between nidi, with loot and lab logs (P11.4), so travel between objectives has its own rewards. Sealed caches open only once their land is in remission, and the quest book rewards remission milestones across the world.
- **Supply drops and standing:** the relay sends supply drops on request, each costing standing; progress earns standing back, and standing changes some quest text and the ending (P11.5). Asking for help is always possible and always a trade-off.
- **Start:** a choice of crash sites of different difficulty, each a map/structure with a guided first chapter.
- **Progression:** KubeJS recipe gating through Mekanism, AE2, Create and Ars Nouveau tiers and the integration gates below, using Remission samples and data items as keys.
- **Worldgen:** datapack and KubeJS tweaks for nidus placement, festering and pacing.

### Pressure to reclaim

The world is generated, so without a rule a player could walk away from the Strain to find clean land and ignore it, undercutting the "progress is reclaimed ground" pillar. Counterstrain (decided 2026-10-12):

- **The world starts festered:** initial festering (P4.9) is on for the overworld. Only a safe radius around the crash sites starts clean, and the level rises with distance, so heading away makes things harder.
- **The world is finite:** a world border (P11.2, sized in P11.3) makes curing the world a finishable goal and gives "how much is in remission" a denominator, at the cost of finite resources the balancing pass must check.
- **Nidi still matter:** land without a nidus in reach holds its level; a live nidus takes purified land back, so the loop stays destroy the nidus, then purify.

## Pack integrations

Each tie-in creates a progression gate. **Pack-side** means KubeJS scripts and datapacks on Remission's data, recipe and API surfaces; **mod-side optional** means Java in `integration/<modid>/` against the other mod's `compileOnly` API, loaded only when that mod is present. Pack-side is the default; mod-side is used only where the other mod's content can be registered or hooked only from Java. Built in phase 10; pack membership is P10.1. Availability (a 1.21.1 NeoForge release, and a CurseForge listing per the [distribution](#distribution) policy) was checked on 2026-10-11.

| Mod | Tie-in | Gate | Side and reasoning |
| --- | --- | --- | --- |
| Create | Encased fans with a purifying catalyst cleanse blocks in their stream; fan walls act as Strain firebreaks; track in festering chunks degrades | Kinetics needed before you can hold a frontier | Mod-side optional: fan processing types and in-stream block effects are Java registrations. Firebreaks use the spread attempt event. |
| Create Aeronautics | Airships reach remote and cliff-top nidi and cross festering zones above the worst viral load; miasma clouds and flying hosts make flight risky; the final deployment can disperse the Counterstrain from an airship | Aerial access to remote nidi, and the airship dispersal option for the finale | Altitude falloff of viral load and miasma clouds are generic Remission mechanics (P5.1, P4.3), not Aeronautics-specific. Contraption dispersal: mod-side optional in `integration/create/`, because contraption behaviours are Java; it works on any moving Create contraption, airships included. Recipes and gating: pack-side. Requires Create 6.0.10 or newer and Sable 2.x. |
| Mekanism (with Mekanism Generators) | Miasma as a Mekanism chemical; late-game sequencing and Counterstrain stabilisation use Mekanism processing; Mekanism Generators is the pack's main FE source, powering reclaimers, with fusion as the late-game power goal | Late-game lab requires Mekanism infrastructure; running reclaimers requires FE generation | Chemical: mod-side optional (P7.1; chemicals are Java registrations). Processing chains and the reclaimer recipe and power gate: pack-side KubeJS recipes (P10.2). Generators added 2026-10-13: Create's power is kinetic and Immersive Engineering's generators sit behind later gates, so without it reclaimers had no mid-game FE source. |
| Oritech | A newer tech line beside the classic mods: player augments that resist viral load; the particle accelerator makes the exotic product the final Counterstrain formula needs; drone ports move cargo between bases without anyone carrying it through festering land | Augments as the gear path for long expeditions; the particle accelerator gates the final formula | Recipes and gates: pack-side (Oritech's recipes are data). The viral load augment: pack-side if Oritech's augments are data-driven, otherwise mod-side optional in `integration/oritech/`; decided in P10.2 against the 1.21.1 API. Drone cargo is never carried, so it is never contaminated (P10.6) without any extra code. Lasers cannot shortcut nidus encounters, because a nidus cannot be mined (P4.10). CurseForge file for 1.21.1 NeoForge checked 2026-10-13; its required libraries must also be on CurseForge (P10.1). |
| Ballistix | A throwback to ICBM: missiles reach remote nidi and clear hosts in surges; a cleansing warhead purifies land at range; anti-air defences shoot down miasma clouds where possible | Long-range answers to remote nidi and surges, mid- to late-game | Explosions only wear a nidus down at P4.10's explosion fraction, so a player still lands the final blow. Cleansing warhead: mod-side optional in `integration/ballistix/` if blast types are Java registrations, otherwise pack-side; decided in P10.8. World-destroying explosives disabled or endgame-gated for multiplayer. Requires Voltaic. CurseForge file for 1.21.1 NeoForge checked 2026-10-13. |
| MFFS (Modular Force Field Systems) | A throwback base defence: force fields keep hosts, surge waves and miasma clouds out, and veins never creep over field blocks | Holding a base through surges, mid-game | Pack-side: recipes and the `#remission:vein_resistant` tag. Distinct from quarantine pylons, which stop the Strain spreading but not what walks in. 1.21.1 NeoForge builds exist (checked 2026-10-13); the CurseForge file is confirmed in P10.1. Not the separate "Modular Forcefields" project. |
| PneumaticCraft: Repressurized | Miasma pressurised for analysis; drones patrol frontiers and plant mendroot | Automated reclamation locked behind pressure tech | Pack-side: pressure chamber and thermo plant recipes are data; drones plant mendroot with stock programs if mendroot is a plantable item. |
| Applied Energistics 2 | Research data stored in AE2; the sequencer reads from the network; AE2 cables in festering chunks degrade without shielding | Reliable research network needs shielded cabling | Storage and sequencer: pack-side (machines expose standard item capabilities). Cable degradation: mod-side optional, because cable parts live inside AE2's cable bus. |
| Ars Nouveau | A Cleanse glyph; host summons; source (mana) drawn from festering land is unstable and can backfire | Early, risky magical purification before tech catches up | Mod-side optional: glyphs and source generation hooks are Java registrations. |
| Occultism | Spirits harvested from hosts, bound to work in festering zones the player can't safely enter | Mid-game remote work in hostile zones | Pack-side: ritual recipes are data; host essence comes from the host loot modifier; spirits go in `#remission:immune`. |
| Serene Seasons | The Strain spreads faster in summer and goes dormant in winter; nidi surge at season changes | Winter becomes the season to push | Pack-side: a KubeJS handler on the spread attempt event sets the rate multiplier from the season and starts surges on season change. Its 1.21.1 NeoForge files are marked beta. |
| Farmer's Delight | Festering crops; purifying meals granting viral load resistance | Food becomes expedition prep | Pack-side: festering conversions and food items are data and KubeJS; the resistance effect is Remission's. |
| Productive Bees | Strain-resistant bee lines bred from purified hives that cleanse land they pollinate | Mid-game foreshadowing of the Counterstrain | Bee types and breeding: pack-side (data). The pollination cleanse is pack-side if Productive Bees exposes a data-driven bee effect, otherwise mod-side optional; decided in P10.4. |
| Theurgy | Alchemy as research: its spagyric processes break host tissue, festered soil and nidus cores into components, an early and safer way to study samples before the analyzer, and fit "science, not magic missiles" | Early sample research before the tech lab | Pack-side: its recipes are data. Its guide uses Modonomicon, which P10.7 weighs when choosing the field journal mod. A 1.21.1 NeoForge build exists (checked 2026-10-13); the CurseForge file is confirmed in P10.1. |
| L_Ender's Cataclysm | Boss dungeons as region climaxes: a festered Cataclysm dungeon guards a late-region nidus, and boss drops are ingredients of the Counterstrain | Late regions gated on a boss | Pack-side: structure placement near nidi, loot and recipes are data. Its bosses go in `#remission:immune` unless the pack chooses host bosses per boss. CurseForge file for 1.21.1 NeoForge checked 2026-10-13. |
| Mowzie's Mobs | Memorable hosts and mini-bosses (a host Ferrous Wroughtnaut or Umvuthi), widening the bestiary | Optional mid-game fights with better samples | Pack-side: mob profiles and loot only; its bosses' immunity decided per boss as for Cataclysm. CurseForge file for 1.21.1 NeoForge checked 2026-10-13. |
| Iron's Spells 'n Spellbooks | Spellcasting host bosses; a Strain-themed spell school dropped by them | Boss-gated combat progression | Bosses: pack-side (mob profiles, loot). Spell school: mod-side optional, because schools and spells are Java registrations. |
| Waystones | Waystones in festering chunks go dark until purified; remission zones become fast-travel hubs | Travel network grows with reclaimed land | Mod-side optional: blocking teleports and darkening the stone need Waystones' Java API. |
| Immersive Engineering | A multiblock "quarantine arc" that holds a large frontier; the excavator mines nidus cores out of festered deposits | Large-scale containment and late sample supply | Quarantine arc: mod-side optional (multiblocks are Java). Excavator: pack-side (mineral mixes are data). |
| MineColonies | Colonies can only be founded in remission zones; colonists are vulnerable to the Strain and guards fight hosts; surges threaten colonies | Rebuilding civilisation is the reward for reclaimed land | Mod-side optional: colony founding and guard targeting need MineColonies' Java API. Colonist infection and surges are generic. CurseForge-only, which the [distribution](#distribution) policy accepts. |
| Sophisticated Backpacks / Storage | Items carried through festering zones become contaminated unless stored in a sealed upgrade; contaminated items must be decontaminated before use | Expedition logistics and early sealed-storage goal | Contamination is a generic Remission mechanic any pack can use. The sealed upgrade is mod-side optional, because upgrades are Java registrations. |
| Simple Voice Chat | Hosts are drawn to players' voices, so talking in festering zones is dangerous (optional, server-configurable) | Atmosphere rather than a hard gate | Mod-side optional: Simple Voice Chat's server plugin API is Java only. |
| Patchouli or Modonomicon | An in-game field journal that fills in as you scan hosts, find logs and destroy nidi, carrying the story alongside quests | Lore delivery tied to research progress | Pack-side: books and unlocks are data keyed on Remission advancements. Which book mod is decided in P10.7. |
| Biomes O' Plenty / Terralith | More biome variety for the Strain to fester through, each with its own fester variant | Worldgen variety, no gate | Pack-side: per-biome fester variants are data. Biomes O' Plenty's 1.21.1 NeoForge files are marked beta. |

### Supporting mods

Mods that support a Counterstrain feature without a tie-in of their own: no Remission code and no progression gate. Availability (a 1.21.1 NeoForge file on CurseForge) was checked on 2026-10-13; added in P10.1.

| Mod | Supports | Notes |
| --- | --- | --- |
| Lootr | Wreck sites and sealed caches (P11.4): every player or team gets its own loot from the same container, so co-op players do not take each other's rewards | Pack-side: wreck site containers use Lootr's containers or its loot table replacement. |
| Chunk Pregenerator | World border and initial festering (P11.2, P4.9): generates the land inside the border ahead of play, so new land is not still catching up on festering when players reach it and its server cost is measured before release | Server/admin tool; the pack documents a pregeneration step rather than running it on first join. |
| Naturalist | Wildlife returning to remission zones (P5.5) and a wider bestiary: its animals spawn in the restored biomes and become hosts like any other mob | No tie-in needed; checks that its mobs work as hosts happen in P11.3. |
| Sound Physics Remastered | Festering land atmosphere (P4.11): echo and muffling in caves and festering land | Client-side; the CurseForge 1.21.1 NeoForge file is 1.4.10 (newer 1.21.1 builds are Modrinth-only alphas). |
| Artifacts | Wreck site and sealed cache loot (P11.4): unique trinkets rather than more of the same materials | Pack-side loot tables; a 1.21.1 NeoForge build exists, and its CurseForge file is confirmed in P10.1. |
| YUNG's Better Dungeons (with YUNG's API) | More places to explore beside the pack's own wreck sites; its dungeons fester like any other structure | Optional; wreck sites stay the main exploration content. |

Considered and not added: Apotheosis (on CurseForge for 1.21.1, but it reworks enchanting, gear and mob difficulty across the game; revisit if the P11.3 playtest finds loot dull), Explorer's Compass (no 1.21.1 file confirmed on CurseForge; P8.2's map overlay covers navigation), AmbientSounds (would suit P4.11, but its 1.21.1 NeoForge builds were found only on Modrinth, so the [distribution](#distribution) policy rules it out until a CurseForge file or redistribution permission is confirmed) and Chunky (no 1.21.1 file confirmed on CurseForge; Chunk Pregenerator covers it).

### Considered and unavailable on 1.21.1

Not proposed again unless an official 1.21.1 NeoForge release appears. Unofficial ports are not used.

- **Botania** (checked 2026-10-11): no official 1.21.1 NeoForge release; the official project stops at 1.20.1.
- **Create: Steam 'n' Rails** (checked 2026-10-11): no official 1.21.1 NeoForge release; the official project stops at 1.20.1. Create's own track degradation stays in the Create tie-in.
- **DireGoo** (checked 2026-10-13): a Forge mod whose last release (1.1.0) dates from 2021; no 1.21.1 NeoForge release. Its spreading goo and the tools that revert it would also duplicate Remission's own spread and purification.

## Distribution

- **Remission** (the mod) is published on both **Modrinth and CurseForge**.
- **Counterstrain** (the pack) is **CurseForge-first**: the CurseForge zip is the release artifact. Required mods are already CurseForge-only (FTB Quests, FTB Library, FTB Teams, MineColonies), a Modrinth `.mrpack` cannot download files from CurseForge, and embedding those jars needs redistribution permission.
- A **Modrinth release of the pack** is a revisit item, possible only if every dependency is on Modrinth or can legally be embedded. Nothing is planned around it. The `.mrpack` export is for local testing.
- **Adding pack mods:** prefer mods available on CurseForge, and source them from CurseForge so the CurseForge manifest references them instead of embedding the jar. A Modrinth-only mod is added only if it is also on CurseForge or confirmed redistributable in a CurseForge pack (CurseForge's approved non-CurseForge mods list, or explicit permission); otherwise it is not added.
- Remission joins the pack from its CurseForge release once published (P12.3).

## AI-generated content policy

All AI-generated **player-facing content** is documented and replaceable in a later art and writing pass (P12.2). The ledger is [CONTENT-PROVENANCE](CONTENT-PROVENANCE.md); the rules for agents are in the [content-provenance](../.agents/skills/content-provenance/SKILL.md) skill.

- **Scope:** textures, models, GUI art, particles, sounds, icons and logos, lang strings and item/block descriptions, quest titles and text, lore and field journal entries, in-world signs or books, and store page copy (Modrinth/CurseForge summaries, descriptions, gallery images). Code, build files and internal docs are out of scope.
- **Ledger:** every PR that adds or changes player-facing content adds or updates its rows in the same PR. Replacements change a row's origin and status instead of deleting it.
- **Placeholder images are obviously placeholders:** block, item and entity textures carry a 2×2 magenta (`#FF00FF`) square in the top-left corner (scaled for larger textures); GUI art, icons, logos, gallery and promo images carry a visible "PLACEHOLDER" label. Placeholder sounds are recorded in the ledger only. Final art never carries the marker.
- **AI-written text** is listed in the ledger with status `draft`; in-game text carries no visible marker.

## Out of scope (for now)

Multiplayer team mechanics beyond what FTB Teams provides, custom dimensions, new mob entity types (infection is state), climate and temperature mods, and Fabric or other loaders.
