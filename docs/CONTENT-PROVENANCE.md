# Content provenance

Ledger of player-facing content in Remission and Counterstrain: where each asset or text block came from and whether it is final. Policy: [DESIGN](DESIGN.md#ai-generated-content-policy). How to add, mark and replace rows: [content-provenance](../.agents/skills/content-provenance/SKILL.md). Every PR that adds or changes player-facing content updates this file in the same PR; `tools/check-provenance.sh` (CI: `docs.yml`) checks that every image, sound and model has a row, that texture markers match statuses and that rows point at existing files.

Columns:

- **Path / ID:** repository path, or `file:key` / `file:quest_id` for text inside a larger file.
- **Type:** texture, model, gui, particle, sound, icon, lang, quest, lore, journal, book, store-copy, store-image, metadata.
- **Origin:** `ai` (tool/model if known), `procedural` (script that generates it), `human`, or `licensed` (source and license).
- **Status:** `placeholder`, `draft` or `final`; `removed` once the file is deleted.
- **Notes:** what the art or writing pass should aim for, and any review record.

Replacing content changes the row's origin and status; rows are never deleted, so history stays visible. A removed asset keeps its row with status `removed` and a note saying when and why.

## Textures

Placeholder block, item and entity textures carry the 2×2 `#FF00FF` marker in the top-left corner.

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |
| `mod/src/main/resources/assets/remission/textures/entity/host/stage_1.png` | texture | ai (Claude Opus 5.5, 2026-10-11; value-noise script, not kept) | placeholder | Host overlay, stage 1: bruise-violet blotches over about a third of the surface. Tiles at one texel per mob texel over any mob's UVs (P2.2), so it must stay a seamless 32×32 repeat without features tied to a body part. |
| `mod/src/main/resources/assets/remission/textures/entity/host/stage_2.png` | texture | ai (Claude Opus 5.5, 2026-10-11; value-noise script, not kept) | placeholder | Host overlay, stage 2: blotches over about half the surface plus dark veins. Same tiling rules as stage 1; should read as clearly worse than stage 1 at a glance. |
| `mod/src/main/resources/assets/remission/textures/entity/host/stage_3.png` | texture | ai (Claude Opus 5.5, 2026-10-11; value-noise script, not kept) | placeholder | Host overlay, stage 3 (and any higher stage): about four fifths coverage, heavy veins. Same tiling rules. |
| `mod/src/main/resources/assets/remission/textures/entity/host/veins_glow.png` | texture | ai (Claude Opus 5.5, 2026-10-11; value-noise script, not kept) | placeholder | Full-bright vein pass drawn from stage 3: thin sickly-green lines, transparent elsewhere. Same tiling rules; the art pass should settle the Strain's glow colour. |
| `mod/src/main/resources/assets/remission/textures/item/scanner.png` | texture | ai (Claude Opus 5.5, 2026-10-11; pixel-map script, not kept) | placeholder | Scanner (P6.1): handheld unit with an antenna, a green readout screen and two buttons. Should read as field lab equipment, not a weapon or magic item. |
| `mod/src/main/resources/assets/remission/textures/item/host_scan.png` | texture | ai (Claude Opus 5.5, 2026-10-11; pixel-map script, not kept) | placeholder | Host scan data item (P6.1): a violet data card with green readout lines. Shares a silhouette with the block scan; the art pass should keep the two distinguishable by colour at inventory size. |
| `mod/src/main/resources/assets/remission/textures/item/block_scan.png` | texture | ai (Claude Opus 5.5, 2026-10-11; pixel-map script, not kept) | placeholder | Block scan data item (P6.1): the host scan's card in slate teal with pale readout lines. |
| `mod/src/main/resources/assets/remission/textures/block/nidus.png` | texture | ai (Claude Opus 5.5, 2026-10-11; noise script, not kept) | placeholder | Nidus block (P4.1), all faces: dark bruise-violet organic mass with green veins and a sickly glowing core. The art pass should make it read as the living source of the Strain from a distance, matching the host overlay palette. |
| `mod/src/main/resources/assets/remission/textures/item/host_tissue.png` | texture | ai (Claude Opus 5.5, 2026-10-11; pixel script, not kept) | placeholder | Host tissue sample item (P3.3): a ragged lump of flesh-pink tissue with violet Strain veins. Should read as a biological sample, not food, and sit alongside nidus cores and festered soil as the sample family. |

## Models

Datagen block and item models are `procedural (datagen)` and follow their textures.

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |
| `mod/src/generated/resources/assets/remission/models/item/scanner.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:scanner` (`RemissionItemModelProvider`, P6.1). |
| `mod/src/generated/resources/assets/remission/models/item/host_scan.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:host_scan` (`RemissionItemModelProvider`, P6.1). |
| `mod/src/generated/resources/assets/remission/models/item/block_scan.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:block_scan` (`RemissionItemModelProvider`, P6.1). |
| `mod/src/generated/resources/assets/remission/models/block/nidus.json` | model | procedural (datagen) | draft | Nidus block model: `cube_all` with the nidus texture. A custom shape (pulsing growth, roots) can replace it in the art pass. |
| `mod/src/generated/resources/assets/remission/models/item/nidus.json` | model | procedural (datagen) | draft | Nidus block item model, inherits the block model. |
| `mod/src/generated/resources/assets/remission/models/item/host_tissue.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:host_tissue` (`RemissionItemModelProvider`, P3.3). |

## GUI art, icons and logos

None yet. Placeholders carry a visible "PLACEHOLDER" label.

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |

## Particles

None yet.

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |

## Sounds

None yet.

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |

## Lang strings

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.title` | lang | ai (scaffold agent, model not recorded) | draft | Config screen title; the mod name, likely accepted as final in the writing pass. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.hosts` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Config screen section title for host settings. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.stageThresholds` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Config screen label for the host stage thresholds list. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.stageThresholds.tooltip` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Tooltip explaining Strain level and host stage; keep it short and in glossary terms. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.infect.success.single` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | `/remission infect` feedback for one target (name, Strain level, host stage). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.infect.success.multiple` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | `/remission infect` feedback for several targets (count, Strain level). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.cleanse.success.single` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | `/remission cleanse` feedback for one target (name). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.cleanse.success.multiple` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | `/remission cleanse` feedback for several targets (count). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.inspect.success` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | `/remission inspect` report for a target carrying the Strain (name, level, stage). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.inspect.clean` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | `/remission inspect` report for a clean target (name). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.failed.not_living` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Command error: the target cannot carry the Strain (name). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.failed.no_living_targets` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Command error: no selected target can carry the Strain. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.overlayIntensity*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Client config label and tooltip for the host overlay opacity (P2.2). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.scanner*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Scanner name and tooltip hint (P6.1). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.host_scan*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Host scan name and its tooltip line (entity name, host stage) (P6.1). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.block_scan*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Block scan name and its tooltip line (block name) (P6.1). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:message.remission.scanner*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Action bar feedback after a scan: host scan recorded, block scan recorded, no Strain detected (P6.1). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.nidus` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Nidus block and block item name; the glossary term, likely final. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.spread` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Config screen section title for the spread settings (P4.1). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.intervalTicks*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Spread config label and tooltip: ticks between spread passes. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.nidusGrowth*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Spread config label and tooltip: growth of the nidus chunk. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.spreadStep*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Spread config label and tooltip: growth of chunks near a nidus. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.falloff*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Spread config label and tooltip: level falloff per chunk of distance. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.spreadThreshold*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Spread config label and tooltip: level needed to pass the Strain on. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.chunkBudget*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Spread config label and tooltip: chunk visits per tick. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:remission.configuration.catchUpBudget*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Spread config label and tooltip: catch-up chunks per tick. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.failed.immune` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Command error: every target of `/remission infect` is immune (P3.3). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.host_tissue` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Item name for the host tissue sample (P3.3). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:key.categories.remission` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Controls screen category for Remission key mappings (P6.2). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:key.remission.bestiary` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Key mapping name for opening the bestiary (P6.2). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:gui.remission.bestiary*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Bestiary screen title, progress line (scanned of total host types) and per-row status (highest host stage scanned, not scanned) (P6.2). |

## Mod and pack metadata

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |
| `mod/gradle.properties:mod_description` | metadata | ai (scaffold agent, model not recorded; reworded to Strain terms 2026-10-11) | draft | One-line description shown in the mods list; becomes the basis of the Modrinth summary. |

## Quests

No quest chapters yet: `pack/config/ftbquests/quests/chapters/` holds only an authoring README (internal, out of scope).

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |

## Lore, field journal and in-world text

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |
| `docs/LORE.md` (premise, arc, open questions) | lore | ai (Claude, model not recorded; rewritten to the glossary by Claude Opus 5.5, 2026-10-11) | draft | Story outline; the source for quest and journal text. The writing pass should settle who built the payload and the survivor's voice. |

## Store pages

No store copy or gallery images yet: Remission's Modrinth and CurseForge pages and Counterstrain's CurseForge page ([distribution](DESIGN.md#distribution)). Publishing is P12.3; final store art is P12.2.

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |
