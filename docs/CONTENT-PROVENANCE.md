# Content provenance

Ledger of player-facing content in Remission and Counterstrain: where each asset or text block came from and whether it is final. Policy: [DESIGN](DESIGN.md#ai-generated-content-policy). How to add, mark and replace rows: [content-provenance](../.agents/skills/content-provenance/SKILL.md). Every PR that adds or changes player-facing content updates this file in the same PR; `tools/check-provenance.sh` (CI: `docs.yml`) checks that every image, sound and model has a row, that texture markers match statuses and that rows point at existing files.

Columns:

- **Path / ID:** repository path, or `file:key` / `file:quest_id` for text inside a larger file. A key ending in `*` covers every key with that prefix; an exact `file:key` row overrides it for that key (the most specific row wins).
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
| `mod/src/main/resources/assets/remission/textures/block/miasma_still.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Miasma fluid surface (P7.1), 16×16: murky dark violet with lighter swirls and sparse sickly-green flecks. Static (no `.mcmeta`) so harness scenes stay byte-identical; the art pass should add a slow, thick animation (and then keep it out of fixed scenes or freeze it) and match the host overlay palette. |
| `mod/src/main/resources/assets/remission/textures/block/miasma_flow.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Miasma flowing texture (P7.1), 32×32 like vanilla's flow textures: the still palette with diagonal streaks. Static for the same reason as the still texture. |
| `mod/src/main/resources/assets/remission/textures/item/miasma_bucket.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Miasma bucket (P7.1): a vanilla-style iron bucket brimming with violet miasma and green flecks. Should read as a bucket of the miasma fluid at inventory size. |
| `mod/src/main/resources/assets/remission/textures/item/nidus_core.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Nidus core sample (P7.1): a dark violet shell around a glowing sickly-green heart. The rarest sample; should read as the living centre of a nidus, matching the nidus block. |
| `mod/src/main/resources/assets/remission/textures/item/festered_soil.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Festered soil sample (P7.1): a clump of dirt shot through with violet veins. Third of the sample family with host tissue and nidus cores. |
| `mod/src/main/resources/assets/remission/textures/block/festering_grass_top.png` | texture | ai (Claude Opus 5.5, 2026-10-12; seeded pixel script, not kept) | placeholder | Festering grass top (P4.2): mottled bruise-mauve with dark violet blotches and a few sickly green vein pixels. Untinted (no biome colour); the art pass should keep it reading as diseased turf at a distance and tile without seams. |
| `mod/src/main/resources/assets/remission/textures/block/festering_grass_side.png` | texture | ai (Claude Opus 5.5, 2026-10-12; seeded pixel script, not kept) | placeholder | Festering grass side (P4.2): a ragged mauve fringe over brown earth; the bottom face uses vanilla `block/dirt`. The art pass should match the top's palette and vanilla's grass-side silhouette. |
| `mod/src/main/resources/assets/remission/textures/block/festering_log.png` | texture | ai (Claude Opus 5.5, 2026-10-12; seeded pixel script, not kept) | placeholder | Festering log bark (P4.2): grey-violet vertical bark with dark seams and green vein flecks. Used for every festered log and wood, so it should read as a generic diseased trunk, not one wood type. |
| `mod/src/main/resources/assets/remission/textures/block/festering_log_top.png` | texture | ai (Claude Opus 5.5, 2026-10-12; seeded pixel script, not kept) | placeholder | Festering log end grain (P4.2): violet rings with a dark rim and green flecks. |
| `mod/src/main/resources/assets/remission/textures/block/festering_leaves.png` | texture | ai (Claude Opus 5.5, 2026-10-12; seeded pixel script, not kept) | placeholder | Festering leaves (P4.2): dark mauve foliage with transparent gaps (cutout, untinted). The art pass should keep the hole density close to vanilla leaves so canopies read the same with fancy and fast graphics. |
| `mod/src/main/resources/assets/remission/textures/block/festered_stone.png` | texture | ai (Claude Opus 5.5, 2026-10-12; seeded pixel script, not kept) | placeholder | Festered stone (P4.2): vanilla-grey stone with violet cracks and green flecks; currently reads as stripes when tiled. The art pass should make the cracks irregular across tiles. |
| `mod/src/main/resources/assets/remission/textures/block/festered_deepslate.png` | texture | ai (Claude Opus 5.5, 2026-10-12; seeded pixel script, not kept) | placeholder | Festered deepslate side (P4.6): dark slate grey in horizontal bands with bruise-violet seams and blotches and a few yellow-green flecks. Must read as violet festering, never as sculk (no teal or cyan); the art pass should keep deepslate's layered feel. |
| `mod/src/main/resources/assets/remission/textures/block/festered_deepslate_top.png` | texture | ai (Claude Opus 5.5, 2026-10-12; seeded pixel script, not kept) | placeholder | Festered deepslate end (P4.6): the side palette in rough concentric bands with wandering violet seams. |
| `mod/src/main/resources/assets/remission/textures/block/festering_vein.png` | texture | ai (Claude Opus 5.5, 2026-10-12; seeded pixel script, not kept) | placeholder | Festering vein (P4.8), also the item icon: crimson and wine branching veins with lighter highlights and a few yellow-green pustules on a transparent background, drawn as one flat face over any block. Must read clearly differently from vanilla sculk veins (no teal, cyan or black-green); the art pass may animate it (sculk veins pulse) and should keep it legible over grass, stone, planks and glass. |
| `mod/src/main/resources/assets/remission/textures/entity/miasma_cloud.png` | texture | ai (Claude Opus 5.5, 2026-10-12; value-noise script, not kept) | placeholder | Miasma cloud puff (P4.3), 32×32 with a 4×4 marker: a soft round blot of murky violet with sickly-green patches, alpha falling to nothing at the edge. The cloud renderer draws 18 camera-facing copies at fixed offsets and rotations, so the art should read as one drifting cloud of the same miasma as the fluid, not as separate sprites; static (no animation) so harness scenes stay byte-identical. The marker shows as small magenta squares on every puff. |
| `mod/src/main/resources/assets/remission/textures/block/machine_side.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Shared machine casing side (P7.2): dark steel panel with rivets and a violet band. Should read as lab equipment built to contain the Strain; one family with the three fronts. |
| `mod/src/main/resources/assets/remission/textures/block/machine_top.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Shared machine casing top and bottom (P7.2): steel panel with a vent grille. |
| `mod/src/main/resources/assets/remission/textures/block/analyzer_front.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Analyzer front (P7.2): a teal sensor lens in a dark recess. Should say "inspects samples". |
| `mod/src/main/resources/assets/remission/textures/block/sequencer_front.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Sequencer front (P7.2): a display of green and violet bars, a readout of the Strain being sequenced. |
| `mod/src/main/resources/assets/remission/textures/block/synthesizer_front.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Synthesizer front (P7.2): a glass vial of violet fluid. Should say "makes things from the Strain". |
| `mod/src/main/resources/assets/remission/textures/item/strain_sequence.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Strain sequence item (P7.2): a dark data card with a violet and green double helix. Should read as recorded Strain data, distinct from the host and block scans. |
| `mod/src/main/resources/assets/remission/textures/item/handheld_purifier.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel-map script, not kept) | placeholder | Handheld purifier (P5.2): a steel sprayer held diagonally with a glass canister of pale mint cleansing fluid and a bright nozzle. Should read as field lab equipment like the scanner, and its cleansing colour (pale mint and white) as the opposite of the Strain's violet and crimson. |
| `mod/src/main/resources/assets/remission/textures/block/quarantine_pylon_side.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel-map script, not kept) | placeholder | Quarantine pylon side (P5.2): a dark steel column with a glowing mint-white core strip in bands. The art pass may make it taller than a block or animate the core; keep the cleansing palette shared with the purifier. |
| `mod/src/main/resources/assets/remission/textures/block/quarantine_pylon_top.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel-map script, not kept) | placeholder | Quarantine pylon top (P5.2): a steel cap with a round glowing mint emitter. |
| `mod/src/main/resources/assets/remission/textures/block/mendroot.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel-map script, not kept) | placeholder | Mendroot (P5.2), also the item icon: a cross plant of pale green stems with white root tendrils and small pale blue-white flowers on a transparent background. Should read as a healing plant, clearly not a festering one, and sit on grass or festering grass. |
| `mod/src/main/resources/assets/remission/textures/item/countermeasure_protocol.png` | texture | ai (Claude Opus 5.5, 2026-10-13; pixel-map script, not kept) | placeholder | Countermeasure protocol (P5.7): a cream protocol sheet with a dark outline, a folded corner, two ruled lines and a mint ward emblem. Should read as a synthesized research document, paired with the strain sequence card, its cleansing mint the opposite of the Strain's violet. |

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
| `mod/src/generated/resources/assets/remission/models/block/miasma.json` | model | procedural (datagen) | draft | Miasma liquid block model: particle texture only (the fluid renderer draws the fluid), as vanilla's water and lava models (P7.1). |
| `mod/src/generated/resources/assets/remission/models/item/miasma_bucket.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:miasma_bucket` (P7.1). |
| `mod/src/generated/resources/assets/remission/models/item/nidus_core.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:nidus_core` (P7.1). |
| `mod/src/generated/resources/assets/remission/models/item/festered_soil.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:festered_soil` (P7.1). |
| `mod/src/generated/resources/assets/remission/models/block/festering_grass.json` | model | procedural (datagen) | draft | `cube_bottom_top` for `remission:festering_grass` (`RemissionBlockStateProvider`, P4.2): festering grass top and side, vanilla dirt bottom. |
| `mod/src/generated/resources/assets/remission/models/block/festering_log.json` | model | procedural (datagen) | draft | `cube_column` for `remission:festering_log` (`logBlock`, P4.2). |
| `mod/src/generated/resources/assets/remission/models/block/festering_log_horizontal.json` | model | procedural (datagen) | draft | `cube_column_horizontal` for `remission:festering_log` on the x and z axes (`logBlock`, P4.2). |
| `mod/src/generated/resources/assets/remission/models/block/festering_leaves.json` | model | procedural (datagen) | draft | `cube_all` for `remission:festering_leaves`, render type `cutout_mipped` (P4.2). |
| `mod/src/generated/resources/assets/remission/models/block/festered_stone.json` | model | procedural (datagen) | draft | `cube_all` for `remission:festered_stone` (P4.2). |
| `mod/src/generated/resources/assets/remission/models/block/festered_deepslate.json` | model | procedural (datagen) | draft | `cube_column` for `remission:festered_deepslate` (`axisBlock`, P4.6). |
| `mod/src/generated/resources/assets/remission/models/block/festered_deepslate_horizontal.json` | model | procedural (datagen) | draft | `cube_column_horizontal` for `remission:festered_deepslate` on the x and z axes (`axisBlock`, P4.6). |
| `mod/src/generated/resources/assets/remission/models/item/festering_grass.json` | model | procedural (datagen) | draft | Festering grass block item model, inherits the block model. |
| `mod/src/generated/resources/assets/remission/models/item/festering_log.json` | model | procedural (datagen) | draft | Festering log block item model, inherits the block model. |
| `mod/src/generated/resources/assets/remission/models/item/festering_leaves.json` | model | procedural (datagen) | draft | Festering leaves block item model, inherits the block model. |
| `mod/src/generated/resources/assets/remission/models/item/festered_stone.json` | model | procedural (datagen) | draft | Festered stone block item model, inherits the block model. |
| `mod/src/generated/resources/assets/remission/models/item/festered_deepslate.json` | model | procedural (datagen) | draft | Festered deepslate block item model, inherits the block model. |
| `mod/src/generated/resources/assets/remission/models/block/festering_vein.json` | model | procedural (datagen) | draft | One flat face 0.1 px off the block side, no ambient occlusion, `cutout`, rotated onto each covered face by the multipart blockstate (as vanilla `sculk_vein`); `remission:festering_vein` (P4.8). |
| `mod/src/generated/resources/assets/remission/models/item/festering_vein.json` | model | procedural (datagen) | draft | Festering vein item model: `item/generated` from the block texture (as vanilla sculk veins). |
| `mod/src/generated/resources/assets/remission/models/block/analyzer.json` | model | procedural (datagen) | draft | `orientable` model for `remission:analyzer` (`RemissionBlockStateProvider`, P7.2): machine casing side and top, analyzer front; the blockstate turns it to face the player who placed it. |
| `mod/src/generated/resources/assets/remission/models/item/analyzer.json` | model | procedural (datagen) | draft | Analyzer block item model, inherits the block model (P7.2). |
| `mod/src/generated/resources/assets/remission/models/block/sequencer.json` | model | procedural (datagen) | draft | `orientable` model for `remission:sequencer` (`RemissionBlockStateProvider`, P7.2): machine casing side and top, sequencer front; the blockstate turns it to face the player who placed it. |
| `mod/src/generated/resources/assets/remission/models/item/sequencer.json` | model | procedural (datagen) | draft | Sequencer block item model, inherits the block model (P7.2). |
| `mod/src/generated/resources/assets/remission/models/block/synthesizer.json` | model | procedural (datagen) | draft | `orientable` model for `remission:synthesizer` (`RemissionBlockStateProvider`, P7.2): machine casing side and top, synthesizer front; the blockstate turns it to face the player who placed it. |
| `mod/src/generated/resources/assets/remission/models/item/synthesizer.json` | model | procedural (datagen) | draft | Synthesizer block item model, inherits the block model (P7.2). |
| `mod/src/generated/resources/assets/remission/models/item/strain_sequence.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:strain_sequence` (P7.2). |
| `mod/src/generated/resources/assets/remission/models/block/quarantine_pylon.json` | model | procedural (datagen) | draft | `cube_column` from the pylon side and top textures; `remission:quarantine_pylon` (P5.2). |
| `mod/src/generated/resources/assets/remission/models/item/quarantine_pylon.json` | model | procedural (datagen) | draft | Quarantine pylon item model: the block model. |
| `mod/src/generated/resources/assets/remission/models/block/mendroot.json` | model | procedural (datagen) | draft | Vanilla `cross` from the mendroot texture, `cutout`, offset per position like grass; `remission:mendroot` (P5.2). |
| `mod/src/generated/resources/assets/remission/models/item/mendroot.json` | model | procedural (datagen) | draft | Mendroot item model: `item/generated` from the block texture (as saplings). |
| `mod/src/generated/resources/assets/remission/models/item/handheld_purifier.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:handheld_purifier` (P5.2). |
| `mod/src/generated/resources/assets/remission/models/item/countermeasure_protocol.json` | model | procedural (datagen) | draft | `item/generated` model for `remission:countermeasure_protocol` (P5.7). |

## GUI art, icons and logos

Placeholders carry a visible "PLACEHOLDER" label. HUD sprites too small for a label (9×9 on screen) carry the texture marker instead and are typed `texture`, so `tools/check-provenance.sh` checks the marker.

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |
| `mod/src/main/resources/assets/remission/textures/gui/sprites/hud/viral_load.png` | texture | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Viral load meter icon (P5.1), GUI sprite `remission:hud/viral_load`, 16×16 drawn at 9×9 at the right end of the meter row above the hunger bar: a violet virion with spikes and a sickly-green core, 2×2 marker (about one GUI pixel on screen). The bar beside it is drawn in code (dark frame; fill pale green, yellow, orange, then Strain violet by quarter; a pale chevron while rising or falling), so the art pass may replace both with a sprite set; keep the row 9 pixels tall and 81 wide like vanilla's, and the icon readable on bright and dark ground. |
| `mod/src/main/resources/assets/remission/textures/gui/machine.png` | gui | ai (Claude Opus 5.5, 2026-10-12; pixel script, not kept) | placeholder | Shared machine screen (P7.2), 176x166 plus sprites to the right: vanilla-style grey panel with an energy bar, input and output tanks with gauge marks, output slots, progress arrow and the player inventory; sprites for input slot frames, the filled arrow, the energy fill and the gauge marks. Magenta "PLACEHOLDER" label top right. The art pass may give each machine its own look but must keep the layout (`MachineMenu`, `MachineScreen`). |

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
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.miasma` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Miasma liquid block and fluid type name (P7.1); the glossary term, likely final. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.miasma_bucket` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Miasma bucket item name (P7.1). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.nidus_core` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Nidus core sample item name (P7.1); the glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.festered_soil` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Festered soil sample item name (P7.1). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:chemical.remission.miasma` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Mekanism chemical name for miasma (P7.1); shown only when Mekanism is installed. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:key.categories.remission` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Controls screen category for Remission key mappings (P6.2). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:key.remission.bestiary` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Key mapping name for opening the bestiary (P6.2). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:gui.remission.bestiary*` | lang | ai (Claude Opus 5.5, 2026-10-11) | draft | Bestiary screen title, progress line (scanned of total host types) and per-row status (highest host stage scanned, not scanned) (P6.2). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:stat.remission.*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Statistics screen names for the custom stats `nidi_destroyed`, `hosts_scanned` and `blocks_scanned` (P8.1). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.festering_grass` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Festering grass block and item name (P4.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.festering_log` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Festering log block and item name (P4.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.festering_leaves` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Festering leaves block and item name (P4.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.festered_stone` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Festered stone block and item name (P4.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.festered_deepslate` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Festered deepslate block and item name (P4.6); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.festering_vein` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Festering vein block and item name (P4.8); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:biome.remission.fester` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Name of the `remission:fester` biome (P4.2), shown in F3 and by biome-aware mods; glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:entity.remission.miasma_cloud` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Entity name of the miasma cloud (P4.3), shown by debug screens, `/summon` and entity-aware mods; glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:stat.remission.surges_survived` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Statistics screen name for `remission:surges_survived` (P4.3). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:event.remission.surge.warning.*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Surge warning title ("The Strain stirs") and subtitle ("A surge is coming"), shown to players in the surge's area when the warning begins (P4.3). Should feel ominous and short; titles are large on screen. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:event.remission.surge.bar.*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Surge boss bar names: "Surge incoming" during the warning and "Surge" while it runs (P4.3). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:event.remission.surge.started` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Action bar line when a surge starts: hosts are rising (P4.3). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:event.remission.surge.ended` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Action bar line when a surge ends (P4.3). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:death.attack.remission.viral_load*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Death messages for viral load damage (P5.1): "%1$s succumbed to the Strain", and "... while fighting %2$s" when a killer is credited. Should match vanilla's death message tone. |
| `mod/src/generated/resources/data/remission/damage_type/viral_load.json` | metadata | procedural (datagen, `RemissionDamageTypeProvider`) | draft | Damage type `remission:viral_load` (P5.1): message id `remission.viral_load`, which selects the death messages above; no scaling, no exhaustion. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.surge.*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | `/remission surge start`, `stop` and `status` feedback and errors (P4.3): origin, intensity and ticks until start or end; a surge already running, no nidus reaching the position, no surge to stop. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.analyzer` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Analyzer block and item name (P7.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.sequencer` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Sequencer block and item name (P7.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.synthesizer` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Synthesizer block and item name (P7.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.strain_sequence` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Strain sequence item name (P7.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:gui.remission.machine.*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Machine screen tooltips (P7.2): energy (stored of capacity, FE), input and output tank names, tank contents (fluid, amount of capacity, mB) and "Empty". |
| `mod/src/main/resources/assets/remission/lang/en_us.json:gui.remission.recipe.*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Recipe viewer category titles "Analyzing", "Sequencing", "Synthesizing" and the cost line (FE and seconds per craft) (P7.2). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:tag.item.remission.scans` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Name of the `#remission:scans` item tag, shown by recipe viewers (P7.2). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.handheld_purifier*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Handheld purifier name and tooltip hint (P5.2): cleanse festering ground, weaken the Strain in a host. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.quarantine_pylon` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Quarantine pylon block and item name (P5.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.mendroot` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Mendroot block and item name (P5.2); glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:stat.remission.chunks_cleansed` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Statistics screen name for `remission:chunks_cleansed` (P5.2). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:entity.remission.revenant` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Entity name of a revenant without an owner (P5.3), shown by debug screens, `/summon` and entity-aware mods; glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:entity.remission.revenant.named` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | A revenant's name, "Revenant of %s" with its owner's name (P5.3): shown in death messages ("was slain by Revenant of Steve") and by entity-aware mods. Should stay short and read naturally after "slain by". |
| `mod/src/main/resources/assets/remission/lang/en_us.json:stat.remission.revenants_reclaimed` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | Statistics screen name for `remission:revenants_reclaimed` (P5.3). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.nidus.*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | `/remission nidus seed` feedback and errors (P8.2): the seeded position; a nidus already there; a block a nidus cannot replace (unbreakable or a block entity); a placed nidus that did not take root (block restored). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.region.*` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | `/remission region advance` feedback and error (P8.2): chunks advanced, radius and centre chunk; no loaded chunk could fester further. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:map.remission.festering` | lang | ai (Claude Opus 5.5, 2026-10-12) | draft | JourneyMap hover title of a festering chunk overlay (P8.2): "Festering land, stage %s". Short; it shows over the map. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:map.remission.remission_zone` | lang | ai (Claude Opus 5.5, 2026-10-13) | draft | JourneyMap hover title of a remission zone overlay (P8.2): "Remission zone"; glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:item.remission.countermeasure_protocol*` | lang | ai (Claude Opus 5.5, 2026-10-13) | draft | Countermeasure protocol name and tooltip (P5.7): "Countermeasure: %s" with the hazard name, a use hint, and the line for a protocol naming no hazard; glossary term. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:hazard.remission.contact` | lang | ai (Claude Opus 5.5, 2026-10-13) | draft | Display name of the Strain hazard `remission:contact` (P5.7): "Contact viral load", shown in protocol tooltips, messages and commands. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:message.remission.countermeasure.*` | lang | ai (Claude Opus 5.5, 2026-10-13) | draft | Action bar feedback when a protocol is used (P5.7): countermeasure gained, or already held by the team. |
| `mod/src/main/resources/assets/remission/lang/en_us.json:stat.remission.countermeasures_gained` | lang | ai (Claude Opus 5.5, 2026-10-13) | draft | Statistics screen name for `remission:countermeasures_gained` (P5.7). |
| `mod/src/main/resources/assets/remission/lang/en_us.json:commands.remission.countermeasure.*` | lang | ai (Claude Opus 5.5, 2026-10-13) | draft | `/remission countermeasure` grant, revoke and list feedback (P5.7): players changed, held list and its entries, none held. |

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
