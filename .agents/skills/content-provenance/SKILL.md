---
name: content-provenance
description: Record, mark and replace player-facing content (textures, models, GUI art, particles, sounds, icons, lang, quest text, lore, journal entries, store copy) in docs/CONTENT-PROVENANCE.md. Use whenever a change adds or edits player-facing content, or replaces a placeholder.
---

# Content provenance

Policy: docs/DESIGN.md, "AI-generated content policy". Ledger: `docs/CONTENT-PROVENANCE.md`. All AI-generated player-facing content must be documented and replaceable in the art and writing pass (P12.2).

**In scope:** textures, models, GUI art, particles, sounds, icons and logos, lang strings and item/block descriptions, quest titles and text, lore and field journal entries, in-world signs or books, mod/pack metadata shown to players, and store page copy and images. **Out of scope:** code, build files, scripts' comments and internal docs (ROADMAP, ARCHITECTURE, skills, READMEs for developers).

**When to add or update rows:** in the same PR that adds, changes, moves or removes in-scope content, whoever made it. One row per asset file; for text inside larger files, one row per lang key, quest ID or text block (a group of keys for one item or chapter is fine when they share origin and status, e.g. `mod/src/main/resources/assets/remission/lang/en_us.json:block.remission.nidus*`; write text IDs as `file:key` so the docs check can resolve the path). Use the section for the content type.

**Row format:** `| Path / ID | Type | Origin | Status | Notes |`.
- Origin: `ai (<tool/model>)` when known, else `ai (model not recorded)`; `procedural (<script path>)`; `human (<who>)`; `licensed (<source>, <license>)`.
- Status: `placeholder` (stand-in, must be replaced), `draft` (usable, needs review or rework), `final` (accepted).
- Notes: what the art or writing pass should aim for (palette, mood, scale, voice), plus review records.

**Placeholder markers:**
- Block, item and entity textures: a 2×2 pixel `#FF00FF` square in the top-left corner of a 16×16 texture, scaled proportionally for larger ones (4×4 at 32×32, 8×8 at 64×64). Draw it last, on top of the art; keep alpha 255. It is not the black/magenta missing-texture checkerboard.
- GUI art, icons, logos, gallery and promo images: a visible "PLACEHOLDER" label or watermark.
- Sounds: no audible marker; the ledger row is the record.
- AI-written text: status `draft`, no in-game marker.
- A marker on a `final` row, or no marker on a `placeholder` row, is a bug. The P1.4 check (`tools/check-provenance.sh`, planned) enforces this for textures once it exists; until then check by eye in harness screenshots (visual-checks).

**Replacing a placeholder or draft:** replace the file in place (same path) or update the text, remove the marker from final art, then edit the existing row: new origin, new status, a note with what changed. Never delete the row. Accepting AI text or art unchanged as final needs a recorded human review in the notes (`accepted as final by <who>, <date>`). Moved or deleted assets keep their row with a note.

**Checks before the PR:** every new in-scope file or key has a row; statuses and markers agree; paths exist; harness screenshots of placeholder art show the marker. Mention the ledger rows in the PR body.
