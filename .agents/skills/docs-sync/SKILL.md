---
name: docs-sync
description: Verify that README, AGENTS, docs/, ROADMAP.md, changelogs and project skills still match the current code, pack and tooling, and fix stale statements. Use before opening any PR, when closing a roadmap item, after dependency or version bumps, and for periodic staleness audits.
---

# Docs in sync with code

Two passes: a mechanical check that CI enforces, then a judgment pass that only a reader can do. Neither may be skipped when a change touches code, build files, the pack, tools, workflows or skills.

**1. Mechanical check:** `python3 -I tools/check-docs.py` (CI: `docs.yml`, every push and PR). It fails on:
- versions quoted in any project doc that differ from the build: NeoForge, ModDevGradle, Gradle wrapper, Parchment, `.sdkmanrc`, pack mods such as KubeJS/JEI/JourneyMap;
- `pack.toml` disagreeing with `mod/gradle.properties`, and README companion mods missing from `pack/mods/`;
- broken relative links; backticked repository paths that do not exist (gitignored runtime paths and lines marked planned are allowed);
- `Remission*` class names and `-P`/Gradle properties that do not exist;
- tools and workflows nobody documents;
- skill frontmatter, the `.claude/skills` symlink and the VENDORED precedence list;
- ROADMAP structure: unique IDs, valid statuses, PRs on review/done items, known dependencies and skills, every item in a safe wave.

`tools/check-provenance.sh` runs in the same workflow: every image, sound and model needs a [content provenance](../../../docs/CONTENT-PROVENANCE.md) row and texture markers must match their status (content-provenance skill). Fix the ledger or the asset, not the check.

Fix the doc, not the check. If a check is wrong, fix `tools/check-docs.py` in the same PR and explain why. When you document a new kind of checkable fact (a new version pin, config key, command, schema path), extend the script so it cannot go stale silently.

**2. Judgment pass:** list what changed (`git diff --stat origin/main...HEAD`, or since the last docs commit for an audit) and read the docs that describe each changed area:

| Changed | Re-read and update |
| --- | --- |
| `mod/src/**` systems, registries, packages | docs/ARCHITECTURE.md (layout table, planned→current), neoforge-mod-dev, DESIGN.md if behaviour differs from the concept |
| Data formats, recipe types, triggers, stats, commands, KubeJS schemas | remission-data-formats (stub → current, fields, example), ARCHITECTURE contracts table, kubejs-scripting, ftb-quests-authoring |
| `mod/build.gradle`, `gradle.properties`, wrapper, dev runtime mods | README stack/commands, ARCHITECTURE runtime and decisions, neoforge-mod-dev, DEV-ENVIRONMENT |
| `pack/mods`, `pack.toml`, kubejs, quests | README stack, packwiz-pack, kubejs-scripting, ftb-quests-authoring, `pack/CHANGELOG.md` |
| `tools/*`, `orca.yaml` | README commands/Contributing, DEV-ENVIRONMENT, headless-client, playtesting, roadmap-workflow/orchestrate |
| `.github/workflows/*` | README CI and releases, DEV-ENVIRONMENT runners, release, headless-client |
| Roadmap progress | ROADMAP item fields, phase exit criteria, README status table (one row per phase; keep details in ROADMAP), safe waves |
| User-visible mod or pack change | `mod/CHANGELOG.md` / `pack/CHANGELOG.md` under Unreleased |
| Player-facing content (textures, models, sounds, lang, quests, lore, store copy) | docs/CONTENT-PROVENANCE.md rows and markers (content-provenance); `tools/check-provenance.sh` |
| New concept or name | DESIGN glossary; no reused terms from the pack's inspirations |

For each statement ask: is it still true, is it complete (new commands, files, options, mods), and does it describe what the code does rather than what was planned? Remove "planned"/"stub" wording once something is built, and do not describe unbuilt features as present. Check counts and lists by looking (number of mods, scenes, triggers), not from memory. Keep worktui's voice: short, specific, no filler.

**When code and docs disagree:** if the doc records an intended contract or decision (ARCHITECTURE, remission-data-formats, ROADMAP acceptance), the code may be wrong: report it rather than rewriting the doc to match. Otherwise update the doc in the same PR as the code.

**Audit mode** (no code change, e.g. "check the docs"): run both passes across the whole repo, fix what is clearly stale in one `Docs: Reconcile ...` PR, and list anything needing an owner decision. Vendored `minecraft-*` skills are audited against upstream via VENDORED.md, not here.

Finish by stating which docs you checked and what changed. `tools/check-docs.py` passing alone does not mean the docs are current.
