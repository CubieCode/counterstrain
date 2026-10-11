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

None yet (2026-10-11). Placeholder block, item and entity textures carry the 2×2 `#FF00FF` marker in the top-left corner.

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |

## Models

None yet; there are no hand-written or generated block or item models.

| Path / ID | Type | Origin | Status | Notes |
| --- | --- | --- | --- | --- |

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
