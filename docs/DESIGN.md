# Counterstrain and Remission design

## Overview

Inspired by Blightfall. The player crash-lands on a world overrun by a spreading corruption and works up a tech tree toward an endgame, self-spreading cure. Corruption is never a static biome to avoid: it grows from hearts, converts mobs and land, adapts to the player's tools, and is finally beaten by turning its own spread engine against it.

Two products, one repository:

- **Remission** (mod): generic corruption, purification, research and cure systems. Works with vanilla and modded mobs, ships sane defaults, exposes data and scripting surfaces, and never depends on the pack.
- **Counterstrain** (pack): the story, quest chapters, crash-site start, recipes, progression gating and balance, built with KubeJS, FTB Quests and datapacks on top of Mekanism, AE2, Create and Ars Nouveau.

## Pillars

1. **Any mob can be corrupted.** Corruption is state on the entity, not a new entity type, so every installed mod widens the bestiary.
2. **The land fights back.** Corruption is visible, spreads from destroyable sources, and keeps moving while you are away.
3. **Progress is reclaimed ground.** Cleansed land is the reward and the score; quests and bonuses key off it.
4. **Science, not magic missiles.** You win by sampling, analysing and engineering, using the pack's tech mods.
5. **Mod generic, pack specific.** Every number and story beat the pack might want to change is data or script.

## Remission systems

### Corrupted mobs (state, not entities)

- A Data Attachment on `LivingEntity` holds corruption level/stage (and later, strain). Synced to tracking clients (custom payload, or NeoForge 21.1's built-in attachment sync; decided in phase 2).
- **Visuals:** a render layer added to every living-entity renderer in `EntityRenderersEvent.AddLayers` re-renders the mob's own model with a corruption overlay (tint, emissive veins, particles by stage).
- **Behaviour:** on `EntityJoinLevelEvent`, corrupted `Mob`s lose passive goals (breed, tempt, flee, follow parent) and gain targeting and melee/ranged attack goals. `Attributes.ATTACK_DAMAGE` (and anything else attacks need) is added to every entity type via `EntityAttributeModificationEvent`. Fallbacks for brain-based mobs (villagers, piglins, axolotls), flying and swimming mobs.
- **Conversion:** a kill by a corrupted mob, or prolonged exposure in corrupted land, flips the victim's state. The `convert_to` override can replace the victim with another entity type.
- **Drops:** a Global Loot Modifier gated on a custom `remission:is_corrupted` loot condition adds samples and blight drops.
- **Overrides:** `#remission:immune` entity tag; per-mob JSON loaded from datapacks (attributes, behaviour profile, convert-to entity). Schemas: remission-data-formats skill.

### World corruption

- Per-chunk corruption level (chunk Data Attachment) driving visible stages: blighted grass, dead trees and foliage, corrupted stone, and finally a biome flip.
- Spread originates from **hearts/nodes** (blocks or structures). Destroying a heart stops its spread but does not cleanse land.
- **Catch-up:** unloaded chunks record a timestamp; on load they fast-forward deterministically, bounded per tick.
- **Surges:** periodic events that accelerate spread and spawn corrupted mobs, telegraphed to players.

### Survival and purification

- Player exposure meter (attachment) with escalating debuffs; serums and gear reduce it.
- Tools by scale: handheld purifier, ward totems with a radius, large powered purifiers, cleansing plants.
- Reclaimed land grants bonuses (spawn safety, growth, resource yields; tunable).

### Research

- A scanner turns mobs, blocks and samples into data items.
- A bestiary of corrupted variants that grows with installed mods, because variants are derived from entity types at runtime.

### Tech chain

- Blight as a fluid (and Mekanism chemical when Mekanism is present).
- Samples: tissue, heart cores, soil.
- Machines: analyzer, sequencer, synthesizer, each a recipe type KubeJS can edit.
- Products: personal serums, purifier fuel, unstable prototype strains.

### Endgame cure

- A counter-strain uses the same chunk spread engine in reverse.
- Corruption adapts: resistant strains appear in response to the player's methods.
- A final deployment defence event, after which the world visibly heals.

### Quest hooks

- Advancement triggers and stats: region % cleansed, heart destroyed, variant scanned, surge survived (and more as systems land), usable by FTB Quests or any quest mod.
- Hooks for quests to fire corruption events (start a surge, seed a heart, advance a region).
- Optional JourneyMap/Xaero overlay of corruption levels.

## Counterstrain pack

- **Story:** crash landing, find the first heart, learn what the blight is, build toward the cure ([lore](LORE.md)).
- **Quest loop per region:** find heart, research, gear up, destroy, purify, move on; the last region ends in the cure deployment.
- **Start:** a crash-site map/structure with a guided first chapter.
- **Progression:** KubeJS recipe gating through Mekanism, AE2, Create and Ars Nouveau tiers, using Remission samples and data items as keys.
- **Worldgen:** datapack and KubeJS tweaks for heart placement, biome corruption and pacing.

## Out of scope (for now)

Multiplayer team mechanics beyond what FTB Teams provides, custom dimensions, new mob entity types (corruption is state), and Fabric or other loaders.
