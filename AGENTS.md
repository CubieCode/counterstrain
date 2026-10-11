# Agent conventions

Read README.md, docs/DESIGN.md, docs/ARCHITECTURE.md, docs/DEV-ENVIRONMENT.md and the target ROADMAP.md item. Counterstrain is a quest-driven tech modpack for Minecraft 1.21.1 (NeoForge); Remission is its custom mod. Phase 0 is scaffold only: every gameplay feature is a roadmap item and needs an explicit implementation assignment. A scaffold or docs request never authorises feature work.

Naming: display "Counterstrain" (pack) and "Remission" (mod); identifiers use `counterstrain` and `remission`. Mod ID `remission`, Java package `com.cubiecode.remission`, Maven group `com.cubiecode.remission`.

Vocabulary: use the glossary in docs/DESIGN.md (the Strain, nidus/nidi, hosts, festering, miasma, viral load, quarantine pylons, mendroot, remission zones, variants, the Counterstrain) and never reuse terms from the pack's inspirations or from Thaumcraft. Player-facing text, content registry IDs, lang keys, quest text and IDs, lore, triggers, stats and commands use the glossary; internal Java system names and public API types may keep the generic word "corruption" (`CorruptionState`, the `corruption` attachment, `remission:is_corrupted`). Don't "fix" either side. New concepts get a glossary entry in the same change.

## Structure and commands

Canonical skills are `.agents/skills/`; `.claude/skills` is a committed symlink to it (worktui convention). CLAUDE.md references this file. Project skills take precedence over the vendored `minecraft-*` skills where they overlap ([VENDORED](.agents/skills/VENDORED.md)).

- `mod/`: Remission, a ModDevGradle project. Versions and mod metadata live in `mod/gradle.properties`. Sources in `src/main/java/com/cubiecode/remission/<area>`; datagen output in `src/generated/resources` (committed, never hand-edited).
- `pack/`: Counterstrain, a packwiz pack (`pack.toml`, `index.toml`, `mods/*.pw.toml`), plus `kubejs/`, `config/ftbquests/` and the pack datapack under `kubejs/data/`.
- `tools/`: bash scripts that need no root. `docs/`: design (with the glossary), architecture, lore, content provenance, environment. `ROADMAP.md`: phased items and status.
- Mod: from `mod/`, `./gradlew build`, `./gradlew runData`, `./gradlew runGameTestServer`. Client: `tools/run-client-headless.sh --timeout 180` only, never a bare `runClient` on foundry.
- Pack: `tools/pack.sh refresh|check|export`; never hand-edit hashes in `index.toml`.
- Distribution (docs/DESIGN.md): Remission publishes to Modrinth and CurseForge; Counterstrain is CurseForge-first, with the CurseForge zip as the release artifact and the `.mrpack` for local testing. Add pack mods from CurseForge (`packwiz cf add`); a Modrinth-only mod is added only if it is confirmed redistributable in a CurseForge pack. A Modrinth pack release is a revisit item, not a plan.
- Local test instance: `tools/build-mod.sh [instance]`. Versions: `tools/bump-version.sh mod|pack X.Y.Z`.

## Mod and pack boundary

Remission never depends on Counterstrain. It ships generic defaults that work with any mob and any pack; it never references pack-only mods without an optional, guarded integration. Story, balance, recipes, quest content and worldgen tweaks belong in `pack/` (KubeJS, FTB Quests, datapacks). Pack-facing surfaces (tags, per-mob JSON, recipe types, advancement triggers, stats, commands, public API events in `com.cubiecode.remission.api`) are contracts: document schema changes in the remission-data-formats skill and docs/ARCHITECTURE.md in the same change. Integrations with other mods are pack-side (KubeJS, datapacks on those surfaces) by default; mod-side code only in `integration/<modid>/`, `compileOnly`, guarded by `ModList`. No climate mods.

## Content provenance

All AI-generated player-facing content (textures, models, GUI art, particles, sounds, icons, lang, quest text, lore, journal entries, store copy) is recorded in docs/CONTENT-PROVENANCE.md in the same PR that adds or changes it, and placeholder art is visibly marked (magenta 2×2 corner on textures, "PLACEHOLDER" label on GUI/promo images). Follow the [content-provenance](.agents/skills/content-provenance/SKILL.md) skill. Code, build files and internal docs are out of scope.

## Coding and data

Java 21, NeoForge 1.21.1 APIs only. Check APIs against the NeoForge 21.1 sources in `mod/build/moddev/artifacts/*-sources.jar` or docs.neoforged.net/docs/1.21.1, never against newer-version tutorials (1.21.2+ changed recipes, item models, datagen and more). Register through `DeferredRegister` classes in `registry/`; keep client-only code under `client/` and reach it only from `Dist.CLIENT` entrypoints or subscribers. Server state is authoritative; clients receive synced state. Datapack and resource paths use 1.21.1 singular folder names. Avoid speculative registries, config options, dependencies and placeholder content for unbuilt features.

KubeJS is the 2101.x line; KubeJS 6 and older examples are often wrong. Use ProbeJS declarations as the source of truth. Pin Modrinth Maven dependencies by version ID when a project shares version numbers across loaders.

## Development environment

All builds, tests and visual checks run on `foundry` (Ubuntu 26.04, headless, RTX 4070, user `lab`, no sudo). Never attempt system package installs or anything needing root; if something is missing, stop and report it. Java 21 comes from SDKMAN (`sdk env` reads `.sdkmanrc`); Gradle's toolchain plus foojay is the fallback. Client runs must go through `xvfb-run … vglrun -d egl … --no-daemon` (the tools script does this); a Gradle daemon started outside `vglrun` falls back to llvmpipe. A run whose log does not name the RTX 4070 has failed. Details: docs/DEV-ENVIRONMENT.md and the headless-client skill.

## Roadmap and Git

One item, one Orca worktree, one branch `feat/<id-lower>-<slug>`, one PR titled `<ID>: <title>`. Start only when dependencies are done on main. Update only your item's status/owner/PR fields. Statuses: todo, in-progress, review, done, blocked. `done` means merged and accepted, not merely coded; the coordinator updates it after merge. Report blocking design decisions rather than silently choosing contradictory rules.

Use `roadmap-workflow` for one item; use `roadmap-orchestrate` only when asked to coordinate lanes. Resolve the Orca binary (`orca-ide` on foundry, never the bare Linux screen reader `orca`) and read its live CLI guide (`skills get orca-cli`, `skills get orchestration`) before commands. `orca.yaml` runs `tools/worktree-setup.sh` for each new worktree: its own `mod/run/`, `.local/` and `runServer` port, nothing copied from other lanes. Lanes share foundry's GPU through the lock in `tools/run-client-headless.sh`; never bypass it. Gradle daemons are shared too: never run `./gradlew --stop` or kill Java/Gradle processes you did not start, since that kills other lanes' and CI's builds and client runs. Ready does not mean safe to edit overlapping files concurrently: build/version files, the registry and network spine, data contracts and pack membership are serialization gates. Regenerate `mod/src/generated/` and `pack/index.toml` after rebasing; never hand-merge them.

Commit plain, useful messages (`<ID>: <result>` for roadmap work), no AI attribution, no `--no-verify`, no force-push main. PR body explains result, validation, unresolved decisions and visual evidence (`.github/pull_request_template.md`). PRs squash-merge with the PR title as the whole commit message, and merged branches are deleted. `main` accepts changes only through PRs, so status updates also go through small PRs with auto-merge. Use `gh pr create --body-file` with real newlines. Never publish to Modrinth/CurseForge or push release tags without an explicit assignment.

## Definition of done

Acceptance criteria met within scope; `./gradlew build`, `runData` (no uncommitted drift) and `runGameTestServer` pass; `tools/pack.sh check` passes; `python3 -I tools/check-docs.py` passes and the docs-sync judgment pass is done; player-facing content has ledger rows and placeholders are marked; `git diff --check` passes; no secrets, run directories or build output staged; docs, schemas and skills reflect behaviour; ROADMAP updated. Anything with visual output needs the harness scenes and screenshots listed in its exit criteria, reviewed per the visual-checks skill, from a GPU run (not llvmpipe). State unavailable checks honestly; never mark untested gates complete.
