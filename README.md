# Counterstrain

Counterstrain is a quest-driven, tech-focused Minecraft 1.21.1 (NeoForge) modpack. You crash-land on a world overrun by the Strain, an engineered organism spreading through mobs and land, and climb a tech tree toward the Counterstrain, a self-spreading cure. **Remission** is the custom mod at its core: any mob can become a host, land festers outward from nidi, and you push back with purification, research and finally the Counterstrain. Remission ships generic defaults and a public API, and never depends on the pack; Counterstrain adds the story, quests, recipes, balance and mod integrations. Terms are defined in the [glossary](docs/DESIGN.md#glossary).

Inspired by Blightfall.

Status: phase 0 (scaffold) is complete; phases 1 (visual test harness) and 2 (corruption state) are in progress. The entity corruption attachment and the provenance check are built; no player-visible gameplay yet. See the [roadmap](ROADMAP.md), [design](docs/DESIGN.md) and [architecture](docs/ARCHITECTURE.md). Player-facing content and its origin (AI, procedural, human, licensed) is tracked in [content provenance](docs/CONTENT-PROVENANCE.md).

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
| `tools/verify-gpu-render.sh LOG` | Fail unless a client log names the RTX 4070 |
| `tools/collect-screenshots.sh` | Copy screenshots to `.local/screenshots/latest/` |
| `python3 -I tools/check-docs.py` | Check docs, roadmap, READMEs and skills against the code (docs-sync skill) |
| `tools/check-provenance.sh` | Fail if an image, sound or model lacks a [content provenance](docs/CONTENT-PROVENANCE.md) row or a texture's placeholder marker disagrees with its status; `--self-test` runs the fixtures in `tools/tests/provenance/` |
| `tools/build-mod.sh [INSTANCE]` | Build and copy the jar into a test instance's `mods/` |
| `tools/pack.sh refresh` / `check` / `export` | packwiz index refresh, CI drift check, CurseForge zip (release artifact) + `.mrpack` (local testing) to `.local/dist/` |
| `tools/bump-version.sh mod\|pack X.Y.Z` | Set the mod or pack version (independent) |

Dev runs include KubeJS, ProbeJS, FTB Quests, EMI + JEI, Mekanism, AE2 and JourneyMap from Modrinth/FTB Maven for integration testing; disable with `-Pdev_runtime_mods=false`. `-Pdev_jbr=true` runs the client/server on JetBrains Runtime with enhanced hot-swap (opt-in).

## Repository layout

```text
mod/                 Remission NeoForge project (Gradle wrapper, gradle.properties)
pack/                Counterstrain packwiz pack, kubejs/, config/ftbquests/
tools/               Build, pack, release, headless-client, worktree setup and docs/provenance check scripts (bash, no root)
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

The mod and the pack are versioned independently. CI (`ci.yml`) builds, runs datagen and gametests and checks the pack on every push and PR that touches more than Markdown, and its `ci-ok` job and `docs.yml` are the required checks for `main`; `docs.yml` runs `tools/check-docs.py` and `tools/check-provenance.sh`; `gpu-smoke.yml` is the manual GPU client check. Tags `mod-v<version>` and `pack-v<version>` build draft GitHub releases (`release-mod.yml`, `release-pack.yml`). Platform publishing is roadmap P12.3: Remission goes to Modrinth and CurseForge; Counterstrain is CurseForge-first (the CurseForge zip is the release artifact; see [distribution](docs/DESIGN.md#distribution)). See the release skill.

## License

[MIT](LICENSE), covering Remission, the Counterstrain pack content in this repository (scripts, quests, configs, datapacks) and the tooling. Third-party mods referenced by the pack keep their own licenses and are downloaded from their authors' pages, not redistributed here. Vendored skills keep their upstream MIT license (`.agents/skills/LICENSE.minecraft-codex-skills`).
