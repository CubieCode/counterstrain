---
name: ftb-quests-authoring
description: Author or review Counterstrain FTB Quests chapters (FTB Quests 2101.x, NeoForge 1.21.1), including gating on Remission advancement triggers, stats and quest-fired events.
---

# FTB Quests authoring

Quest data lives in `pack/config/ftbquests/quests/`: `data.snbt` (book settings), `chapter_groups.snbt`, `chapters/<chapter>.snbt`, `reward_tables/`, and quest text in `lang/<locale>.snbt` (2101.x keeps titles/descriptions in lang files, not in chapter SNBT). Files are created by the in-game editor; do not invent SNBT by hand except small, reviewed edits.

Workflow: run a playtest instance (playtesting skill) with the current `pack/` and a fresh Remission build, open the quest book in edit mode (op + edit toggle), author, then copy the instance's `config/ftbquests/quests/` back into `pack/config/ftbquests/quests/`, run `tools/pack.sh refresh`, and review the SNBT diff (stable ids, no stray test quests). One chapter per PR where possible.

Chapter layout follows DESIGN: one group per region, each chapter structured locate the nidus → research → gear up → destroy the nidus → purify → next region; the last group is the Counterstrain. Keep ids readable (`chapter: region1_nidus`), put lore in descriptions sparingly (docs/LORE.md is the source), and use dependencies so the map reads left to right. Quest titles, descriptions and IDs use the DESIGN glossary (the Strain, nidus/nidi, hosts, festering, viral load, remission zones, the Counterstrain).

Quest text is player-facing content: add its lang keys to docs/CONTENT-PROVENANCE.md (status `draft` for AI-written text) in the same PR, per the content-provenance skill.

Gating on Remission (formats in remission-data-formats, built in P8):
- Advancement tasks on `remission:*` advancements whose criteria use Remission triggers (`nidus_destroyed`, `region_cleansed` with `min_percent`, `host_scanned`, `surge_survived`, `viral_load`). The pack defines those advancements in `kubejs/data/counterstrain/advancement/`; the mod only provides triggers.
- Stat tasks on `remission:nidi_destroyed`, `chunks_cleansed`, `hosts_scanned`, `surges_survived`.
- Item tasks on samples and data items; observation tasks for scanning moments.
- Command rewards for quest-fired events (`/remission surge start ...`, `/remission nidus seed ...`) once P8.2 documents them; run as server, never as player op.

Until P8 lands, quests may only use vanilla/companion-mod tasks; leave a `TODO(P8.1)` note in the quest description instead of faking Remission gates. Test every gate in a playtest: complete the action, confirm the task ticks, confirm rewards fire once.
