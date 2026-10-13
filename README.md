# Counterstrain

[![CI](https://github.com/CubieCode/counterstrain/actions/workflows/ci.yml/badge.svg)](https://github.com/CubieCode/counterstrain/actions/workflows/ci.yml)
[![Docs](https://github.com/CubieCode/counterstrain/actions/workflows/docs.yml/badge.svg)](https://github.com/CubieCode/counterstrain/actions/workflows/docs.yml)

Counterstrain is a quest-driven, tech-focused Minecraft 1.21.1 (NeoForge) modpack, inspired by Blightfall. You crash-land on a world overrun by the Strain, an engineered organism spreading through mobs and land, and climb a tech tree toward the Counterstrain, a self-spreading cure.

**Remission** is the custom mod at its core: any mob can become a host, land festers outward from nidi, and you push back with purification, research and finally the Counterstrain. Remission ships generic defaults and a public API and never depends on the pack; Counterstrain adds the story, quests, recipes, balance and mod integrations. Terms are defined in the [glossary](docs/DESIGN.md#glossary).

## Documentation

- [ROADMAP](ROADMAP.md): phases, items and their status.
- [Design](docs/DESIGN.md): the concept, game rules, glossary and distribution.
- [Architecture](docs/ARCHITECTURE.md): code layout, systems and the pack-facing contracts.
- [Lore](docs/LORE.md): the story behind the Strain and the Counterstrain.
- [Development environment](docs/DEV-ENVIRONMENT.md): foundry, headless client runs, visual checks, playing the pack from a PC, the content editor.
- [Content provenance](docs/CONTENT-PROVENANCE.md): where each piece of player-facing content came from (AI, procedural, human, licensed).
- [AGENTS](AGENTS.md): conventions for anyone changing the repository, human or agent.

## Status

Phases 0 to 3 and 6 are complete, 4, 5, 7 and 8 are in progress, and 9 to 12 have not started. [ROADMAP](ROADMAP.md) lists every item with its status and scope.

| Phase | State | Built so far |
| --- | --- | --- |
| 0. Scaffold | done | Monorepo, ModDevGradle mod, packwiz pack, tools, CI, skills |
| 1. Visual test harness | done | Dev-only scene harness, screenshot comparison, GPU workflow, provenance check, content editor |
| 2. Corruption state | done | Corruption attachment, host render layer (armour, wool and other layers), `/remission` debug commands |
| 3. Host behaviour | done | Injected combat goals with brain, flying and swimming fallbacks; infection on kill; host tissue |
| 4. Festering land | in progress | Nidi and per-chunk spread, staged block and biome conversion, underground festering, veins and contact infection, surges and miasma, nidus fights, ambience, team query, public API events |
| 5. Viral load and early purification | in progress | Viral load and its HUD meter; purifier, quarantine pylons and mendroot; remission zones; revenants; countermeasures |
| 6. Research | done | Scanner, bestiary with hidden types, team-shared research |
| 7. Tech chain | in progress | Miasma, nidus cores, analyzer, sequencer and synthesizer, serums, purifier fuel, Counterstrain prototypes, KubeJS recipe methods |
| 8. Quest integration | in progress | Advancement triggers and stats, quest-fired commands, JourneyMap overlay |
| 9 to 12 | todo | Endgame cure, mod integrations, pack assembly, polish and release |

## Stack and decisions

| Area | Choice |
| --- | --- |
| Minecraft / loader | 1.21.1, NeoForge 21.1.259 |
| Mod build | ModDevGradle 2.0.148, Gradle 9.8.1 wrapper, Parchment 2024.11.17, Java 21 (Temurin) |
| Mod ID / package | `remission` / `com.cubiecode.remission` |
| Mod dependencies | GeckoLib 4.9 (required: the animated nidus) |
| Pack tooling | packwiz (pinned commit, built by `tools/install-packwiz.sh`) |
| Pack content | KubeJS 2101.7 (+ ProbeJS, optional), FTB Quests 2101.1, datapacks via `kubejs/data/` |
| Companion mods | Mekanism, Applied Energistics 2, Create, Ars Nouveau, EMI + JEI, JourneyMap |

## Local setup

```sh
sdk env install                  # Temurin 21 from .sdkmanrc (SDKMAN, per user)
tools/install-packwiz.sh         # packwiz into ~/.local/bin, no root
cd mod && ./gradlew build        # first run downloads Minecraft and NeoForge
```

Without SDKMAN, Gradle's Java 21 toolchain (foojay resolver) downloads a JDK itself.

## Commands

Development, builds, tests and visual checks run on the headless home server `foundry` (RTX 4070 through Xvfb + VirtualGL). Details for each tool are in [development environment](docs/DEV-ENVIRONMENT.md) and the skills under `.agents/skills/`.

### Build and test

| Command | Purpose |
| --- | --- |
| `cd mod && ./gradlew build` | Compile and package Remission (`mod/build/libs/`) |
| `cd mod && ./gradlew runData` | Regenerate `mod/src/generated/resources` |
| `cd mod && ./gradlew runGameTestServer` | Run gametests headlessly; non-zero exit on failure |
| `cd mod && ./gradlew runServer` | Dedicated dev server in `mod/run/server` (accept the EULA there first) |
| `tools/build-mod.sh [INSTANCE]` | Build and copy the jar into a test instance's `mods/` |

### Client and visual checks

| Command | Purpose |
| --- | --- |
| `tools/run-client-headless.sh --timeout 180` | Dev client on foundry's GPU; logs in `.local/runs/latest/` |
| `tools/run-client-headless.sh --scene smoke/mob_lineup` | Run one harness scene and save its screenshots, then exit |
| `tools/run-client-headless.sh --scene items/held --scene-arg item=remission:scanner` | A scene with arguments |
| `tools/run-client-headless.sh --scene smoke/mob_lineup --record` | The same, plus an H.264 clip (`capture.mp4`) |
| `tools/run-scenes.sh [--scenes "a/b c/d"] [--record]` | Run every harness scene (or the listed ones) and compare with the references |
| `tools/compare-screenshots.sh --scene <id> --actual DIR --out DIR` | Diff a scene's screenshots against `tools/visual-references/` |
| `tools/verify-gpu-render.sh LOG` | Fail unless a client log names the RTX 4070 |
| `tools/collect-screenshots.sh` | Copy screenshots to `.local/screenshots/latest/` |

### Pack

| Command | Purpose |
| --- | --- |
| `tools/pack.sh refresh` / `check` / `export` | Refresh the packwiz index, check for drift, or export the CurseForge zip and `.mrpack` to `.local/dist/` |
| `tools/pack.sh serve [PORT]` | Serve the pack over HTTP (default port 8080) |
| `tools/pack-sync.sh [INSTANCE]` | On your PC, as a Prism pre-launch command: sync the served pack and Remission's jar ([playing the pack from a PC](docs/DEV-ENVIRONMENT.md#playing-the-pack-from-a-pc)) |
| `tools/bump-version.sh mod\|pack X.Y.Z` | Set the mod or pack version (independent) |

### Repository tooling

| Command | Purpose |
| --- | --- |
| `python3 -I tools/check-docs.py` | Check docs, roadmap, READMEs and skills against the code |
| `tools/check-provenance.sh` | Check that every image, sound and model has a provenance row and the right placeholder marker |
| `tools/content-editor.sh [--port N] [--lan]` | Local web app for replacing player-facing content ([content editor](docs/DEV-ENVIRONMENT.md#content-editor)) |
| `python3 -I tools/export-bbmodel.py MODEL.bbmodel --geo OUT.geo.json --animations OUT.animation.json` | Export a GeckoLib Blockbench model without Blockbench |
| `tools/cleanup-worktrees.sh [--dry-run]` | Remove merged lane worktrees and their local branches |

`check-provenance.sh` and `compare-screenshots.sh` take `--self-test`; `content-editor.sh` takes `--test`.

### Dev runtime mods

Dev runs include KubeJS, ProbeJS, FTB Library, FTB Teams, FTB Quests, EMI + JEI, Mekanism, AE2, JourneyMap and Curios for integration testing.

| Gradle flag | Effect |
| --- | --- |
| `-Pdev_runtime_mods=false` | Drop all dev runtime mods (CI always does) |
| `-Pdev_runtime_emi=false` | Drop only EMI, to see JEI's own screens |
| `-Pdev_jbr=true` | Run the client or server on JetBrains Runtime with enhanced hot-swap |

## Repository layout

```text
mod/                 Remission NeoForge project (Gradle wrapper, gradle.properties)
pack/                Counterstrain packwiz pack, kubejs/, config/ftbquests/
tools/               Bash and Python scripts (no root; see Commands); content-editor/ and visual-references/
orca.yaml            Orca setup hook (runs tools/worktree-setup.sh per worktree)
docs/                Design, architecture, lore, content provenance and development environment
ROADMAP.md           Phases, items, status
.agents/skills/      Canonical agent skills (project and vendored)
.claude/skills       Symlink to .agents/skills
.github/workflows/   CI, docs checks, GPU smoke test, release stubs
.local/              Gitignored runs, screenshots, test instances and exports
```

## Remission in the pack

Remission is not in the packwiz index until it is published. For development, `tools/build-mod.sh <instance>` copies the freshly built jar into a test instance next to the packwiz mods (see the playtesting skill). Remission is published on both Modrinth and CurseForge. After its first release it is added to the pack from CurseForge (`packwiz cf add remission`) and pinned to the released version, so the CurseForge pack references it.

## CI and releases

The mod and the pack are versioned independently.

- `ci.yml` builds the mod, runs datagen and gametests and checks the pack on every push and PR that touches more than Markdown. Its `ci-ok` job is a required check for `main`.
- `docs.yml` (also required) runs `tools/check-docs.py`, `tools/check-provenance.sh`, the content editor's tests, the run-folder pruning test and the GPU lock test.
- `gpu-smoke.yml` runs every harness scene on foundry's GPU (`tools/run-scenes.sh`) on manual dispatch or when a same-repo PR has the `gpu-smoke` label, and uploads logs, screenshots and diffs. A new push cancels that PR's older run.
- `release-mod.yml` and `release-pack.yml` build draft GitHub releases from `mod-v<version>` and `pack-v<version>` tags.

Platform publishing is roadmap P12.3: Remission goes to Modrinth and CurseForge; Counterstrain is CurseForge-first, with the CurseForge zip as the release artifact (see [distribution](docs/DESIGN.md#distribution)). See the release skill.

## Contributing

Read [AGENTS](AGENTS.md) before changing anything; `CLAUDE.md` references it.

Roadmap work runs in Orca worktrees on foundry, one lane per item. `orca.yaml` runs `tools/worktree-setup.sh` on each new worktree: it selects Java 21, installs packwiz if missing, gives the lane its own `runServer` port, then runs `./gradlew build` and `tools/pack.sh check`. Each lane has its own `mod/run/` and `.local/`; Gradle and packwiz caches in `$HOME` are shared. All lanes share one GPU: `tools/run-client-headless.sh` holds an exclusive lock (`~/.cache/counterstrain/gpu.lock`) and queues other lanes for up to `--gpu-wait` seconds.

Use the `roadmap-workflow` skill for one item and `roadmap-orchestrate` for supervised parallel work and review. ROADMAP lists candidate waves.

## License

[MIT](LICENSE), covering Remission, the Counterstrain pack content in this repository (scripts, quests, configs, datapacks) and the tooling. Third-party mods referenced by the pack keep their own licenses and are downloaded from their authors' pages, not redistributed here. Vendored skills keep their upstream MIT license (`.agents/skills/LICENSE.minecraft-codex-skills`).
