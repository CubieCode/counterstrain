# Counterstrain

Counterstrain is a quest-driven, tech-focused Minecraft 1.21.1 (NeoForge) modpack. You crash-land on a world overrun by the Strain, an engineered organism spreading through mobs and land, and climb a tech tree toward the Counterstrain, a self-spreading cure. **Remission** is the custom mod at its core: any mob can become a host, land festers outward from nidi, and you push back with purification, research and finally the Counterstrain. Remission ships generic defaults and a public API, and never depends on the pack; Counterstrain adds the story, quests, recipes, balance and mod integrations. Terms are defined in the [glossary](docs/DESIGN.md#glossary).

Inspired by Blightfall.

Status: phases 0 (scaffold), 1 (visual test harness) and 2 (corruption state) are complete, including follow-up P2.4 (overlay on armour, wool and other separately modelled layers). The dev-only scene harness, screenshot comparison and its GPU workflow, the entity corruption attachment, the host render layer (with armour, wool, slime gel, drowned and horse/wolf armour layers, P2.4), the `/remission` debug commands, the scanner with its host and block scans (P6.1), the bestiary of host types with per-player scan progress (P6.2; hidden types tag, P6.3), the provenance check and the local content editor (P1.5, `tools/content-editor.sh`) are built. Phase 3 has started: hosts fight with injected goals (P3.1), with fallbacks for brain-based, flying and swimming mobs (P3.2), infect what they kill and drop host tissue (P3.3); the first hosts in normal play are mobs that stay too long in festering land (P4.7). Phase 4's core is built: nidi spread the Strain through per-chunk levels with bounded catch-up (P4.1), the public API (`com.cubiecode.remission.api`) posts host, chunk stage, nidus and cancellable spread events (P4.4), and festering chunks turn their grass, trees and stone into festering blocks by stage and finally flip to the `remission:fester` biome, with data maps for modded blocks and per-biome fester variants (P4.2), and from stage 3 their caves fester as a skin on walls, floors and ceilings, sparsely below Y=0 (P4.6); festering veins creep over every solid face in festering chunks, player builds included, and mobs that touch festering blocks or swim in festering water become hosts by contact (P4.8); live spread waits for catch-up on reloaded chunks (P4.5). Surges come from nidi on a schedule or by command: after a warning they speed up that nidus's spread, spawn hosts on the surface and in caves under festering land, and release drifting miasma clouds, with surge started and ended events and a surge survived trigger and stat (P4.3). Phase 5 has started: players build up viral load in festering land (less high above it, in full underground), in water there and in miasma clouds, with escalating debuffs, a HUD meter, a viral load changed event and query and a `remission:viral_load_changed` trigger (P5.1); a handheld purifier, quarantine pylons (whose ward stops spread, festering, veins and contact infection and drains its land) and plantable mendroot cleanse festering land, purified chunks enter remission with configurable bonuses, a block purified and chunk entered remission event, a remission query and a `remission:region_cleansed` trigger and `remission:chunks_cleansed` stat, and touching festered blocks adds viral load (P5.2). Players who die to the Strain rise as revenants: hosts with their skin, armour and weapon that hold their items (Curios slots included) and experience, stay near where they fell and can be hurt only by players; their items go back to the owner and the owner's teammates (the team query; Counterstrain's teams are FTB Teams parties) until a timeout frees them, with risen and slain events, a saved index and a `remission:revenant_reclaimed` trigger and stat (P5.3). Upkeep hazards are curable: a synthesized countermeasure protocol protects a whole team from a Strain hazard for good, starting with the viral load from touching festered blocks, and every hazard asks one query that packs can hold back with a cancellable event (P5.7). Phase 7 has started: the miasma fluid (and Mekanism chemical when Mekanism is present), nidus cores and festered soil exist with their default loot (P7.1), and the analyzer, sequencer and synthesizer process them into miasma, scans and strain sequences, with recipe categories in JEI and EMI (P7.2). Phase 8 has started: advancement triggers `remission:nidus_destroyed`, `remission:host_scanned`, `remission:block_scanned` and matching stats let quest mods gate on nidi and scans (P8.1), joined by `remission:surge_survived` (P4.3); `/remission nidus seed` and `/remission region advance` let quest rewards and scripts start a source or advance a region, and with JourneyMap installed its maps show festering chunks by stage and remission zones (P8.2). Rules involving teammates ask one team query, answered from FTB Teams when it is present (P4.12). See the [roadmap](ROADMAP.md), [design](docs/DESIGN.md) and [architecture](docs/ARCHITECTURE.md). Player-facing content and its origin (AI, procedural, human, licensed) is tracked in [content provenance](docs/CONTENT-PROVENANCE.md).

## Stack and decisions

| | |
| --- | --- |
| Minecraft / loader | 1.21.1, NeoForge 21.1.259 |
| Mod build | ModDevGradle 2.0.148, Gradle 9.8.1 wrapper, Parchment 2024.11.17, Java 21 (Temurin) |
| Mod ID / package | `remission` / `com.cubiecode.remission` |
| Pack tooling | packwiz (pinned commit, built by `tools/install-packwiz.sh`) |
| Pack content | KubeJS 2101.7 (+ ProbeJS, optional), FTB Quests 2101.1, datapacks via `kubejs/data/` |
| Companion mods | Mekanism, Applied Energistics 2, Create, Ars Nouveau, EMI + JEI, JourneyMap |

Development, builds, tests and visual checks run on the headless home server `foundry` (RTX 4070 through Xvfb + VirtualGL). See [development environment](docs/DEV-ENVIRONMENT.md).

## Local setup

```sh
sdk env install                  # Temurin 21 from .sdkmanrc (SDKMAN, per user)
tools/install-packwiz.sh         # packwiz into ~/.local/bin, no root
cd mod && ./gradlew build        # first run downloads Minecraft and NeoForge
```

Without SDKMAN, Gradle's Java 21 toolchain (foojay resolver) downloads a JDK itself.

## Commands

| Command | Purpose |
| --- | --- |
| `cd mod && ./gradlew build` | Compile and package Remission (`mod/build/libs/`) |
| `cd mod && ./gradlew runData` | Regenerate `mod/src/generated/resources` |
| `cd mod && ./gradlew runGameTestServer` | Run gametests headlessly, exit non-zero on failure |
| `cd mod && ./gradlew runServer` | Dedicated dev server (`mod/run/server`, accept the EULA there first) |
| `tools/run-client-headless.sh --timeout 180` | Dev client on foundry's GPU; logs in `.local/runs/latest/` |
| `tools/run-client-headless.sh --scene smoke/mob_lineup` | Dev-only harness scene: fixed world, screenshots in `.local/runs/latest/screenshots/`, then exits |
| `tools/run-client-headless.sh --scene smoke/mob_lineup --record` | The same, plus an H.264 clip of the display in `.local/runs/latest/capture.mp4` (system ffmpeg with `x11grab`) |
| `tools/run-scenes.sh [--scenes "a/b c/d"] [--record]` | Run every harness scene (or the listed ones) headlessly, compare their screenshots with the references; summary in `.local/scene-runs/latest/` |
| `tools/verify-gpu-render.sh LOG` | Fail unless a client log names the RTX 4070 |
| `tools/compare-screenshots.sh --scene <id> --actual DIR --out DIR` | Diff a scene run's screenshots against the reviewed references in `tools/visual-references/` (diff images, exit 1 on a difference); `--self-test` runs synthetic cases |
| `tools/cleanup-worktrees.sh [--dry-run]` | Remove lane worktrees and local branches whose PR is merged (clean tree, tip = merged head); keeps everything else and says why |
| `tools/collect-screenshots.sh` | Copy screenshots to `.local/screenshots/latest/` |
| `python3 -I tools/check-docs.py` | Check docs, roadmap, READMEs and skills against the code (docs-sync skill) |
| `tools/check-provenance.sh` | Fail if an image, sound or model lacks a [content provenance](docs/CONTENT-PROVENANCE.md) row or a texture's placeholder marker disagrees with its status; `--self-test` runs the fixtures in `tools/tests/provenance/` |
| `tools/content-editor.sh [--port N] [--lan]` | Local web app over the provenance ledger: replace lang strings, textures, models and sounds in a `content/<date>-<slug>` worktree, with 3D and host overlay previews, then check, commit and open a draft PR ([content editor](docs/DEV-ENVIRONMENT.md#content-editor)); `--test` runs its unit tests |
| `tools/build-mod.sh [INSTANCE]` | Build and copy the jar into a test instance's `mods/` |
| `tools/pack.sh refresh` / `check` / `export` | packwiz index refresh, CI drift check, CurseForge zip (release artifact) + `.mrpack` (local testing) to `.local/dist/` |
| `tools/pack.sh serve [PORT]` | Serve the pack over HTTP (default port 8080) for `tools/pack-sync.sh` |
| `tools/pack-sync.sh [INSTANCE]` | On your PC, as a Prism pre-launch command: sync the instance from the served pack and copy Remission's jar from foundry ([playing the pack from a PC](docs/DEV-ENVIRONMENT.md#playing-the-pack-from-a-pc)) |
| `tools/bump-version.sh mod\|pack X.Y.Z` | Set the mod or pack version (independent) |

Dev runs include KubeJS, ProbeJS, FTB Library, FTB Teams, FTB Quests, EMI + JEI, Mekanism, AE2, JourneyMap and Curios from Modrinth/FTB Maven for integration testing; disable with `-Pdev_runtime_mods=false`, or drop only EMI (to see JEI's own screens) with `-Pdev_runtime_emi=false`. `-Pdev_jbr=true` runs the client/server on JetBrains Runtime with enhanced hot-swap (opt-in).

## Repository layout

```text
mod/                 Remission NeoForge project (Gradle wrapper, gradle.properties)
pack/                Counterstrain packwiz pack, kubejs/, config/ftbquests/
tools/               Build, pack, release, headless-client, screenshot comparison, worktree setup and docs/provenance check scripts (bash, no root); content-editor/ is the local content editor; visual-references/ holds reviewed scene screenshots
orca.yaml            Orca setup hook (runs tools/worktree-setup.sh per worktree)
docs/                Design, architecture, lore, content provenance and development environment
ROADMAP.md           Phases, items, status
.agents/skills/      Canonical agent skills (project and vendored)
.claude/skills       Symlink to .agents/skills
.github/workflows/   CI, GPU smoke test, release stubs
.local/              Gitignored runs, screenshots, test instances and exports
```

Read [AGENTS](AGENTS.md) before changing anything. `CLAUDE.md` references `AGENTS.md`.

## Orca

Roadmap work runs in Orca worktrees on foundry, one lane per item. `orca.yaml` runs `tools/worktree-setup.sh` on each new worktree: it selects Java 21, installs packwiz if missing, gives the lane its own `runServer` port (`mod/run/server/server.properties`), then runs `./gradlew build` and `tools/pack.sh check`. Nothing is copied from the root checkout or other lanes; each lane has its own `mod/run/` and `.local/`, while Gradle and packwiz caches in `$HOME` are shared. All lanes share one GPU: `tools/run-client-headless.sh` holds an exclusive lock (`~/.cache/counterstrain/gpu.lock`) and queues other lanes for up to `--gpu-wait` seconds.

Use `roadmap-workflow` for one item and `roadmap-orchestrate` for supervised parallel work and review. ROADMAP lists candidate waves.

## Remission in the pack

Remission is not in the packwiz index until it is published. For development, `tools/build-mod.sh <instance>` copies the freshly built jar into a test instance next to the packwiz mods (see the playtesting skill). Remission is published on both Modrinth and CurseForge. After its first release it is added to the pack from CurseForge (`packwiz cf add remission`) and pinned to the released version, so the CurseForge pack references it.

## Releases

The mod and the pack are versioned independently. CI (`ci.yml`) builds, runs datagen and gametests and checks the pack on every push and PR that touches more than Markdown, and its `ci-ok` job and `docs.yml` are the required checks for `main`; `docs.yml` runs `tools/check-docs.py`, `tools/check-provenance.sh` and the content editor's tests; `gpu-smoke.yml` runs every harness scene on foundry's GPU (`tools/run-scenes.sh`) on manual dispatch or when a same-repo PR carries the `gpu-smoke` label, and uploads logs, screenshots and diffs. Tags `mod-v<version>` and `pack-v<version>` build draft GitHub releases (`release-mod.yml`, `release-pack.yml`). Platform publishing is roadmap P12.3: Remission goes to Modrinth and CurseForge; Counterstrain is CurseForge-first (the CurseForge zip is the release artifact; see [distribution](docs/DESIGN.md#distribution)). See the release skill.

## License

[MIT](LICENSE), covering Remission, the Counterstrain pack content in this repository (scripts, quests, configs, datapacks) and the tooling. Third-party mods referenced by the pack keep their own licenses and are downloaded from their authors' pages, not redistributed here. Vendored skills keep their upstream MIT license (`.agents/skills/LICENSE.minecraft-codex-skills`).
