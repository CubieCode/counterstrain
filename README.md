# Counterstrain

Counterstrain is a quest-driven, tech-focused Minecraft 1.21.1 (NeoForge) modpack. You crash-land on a world overrun by the Strain, an engineered organism spreading through mobs and land, and climb a tech tree toward the Counterstrain, a self-spreading cure. **Remission** is the custom mod at its core: any mob can become a host, land festers outward from nidi, and you push back with purification, research and finally the Counterstrain. Remission ships generic defaults and a public API, and never depends on the pack; Counterstrain adds the story, quests, recipes, balance and mod integrations. Terms are defined in the [glossary](docs/DESIGN.md#glossary).

Inspired by Blightfall.

Status: phases 0 (scaffold), 1 (visual test harness) and 2 (corruption state) are complete, including follow-up P2.4 (overlay on armour, wool and other separately modelled layers). The dev-only scene harness, screenshot comparison and its GPU workflow, the entity corruption attachment, the host render layer (with armour, wool, slime gel, drowned and horse/wolf armour layers, P2.4), the `/remission` debug commands, the scanner with its host and block scans (P6.1), the bestiary of host types with per-player scan progress (P6.2; hidden types tag, P6.3) and the provenance check are built. Phase 3 has started: hosts fight with injected goals (P3.1), with fallbacks for brain-based, flying and swimming mobs (P3.2), infect what they kill and drop host tissue (P3.3), but nothing creates the first host in normal play until festering land infects mobs. Phase 4 has started: nidi spread the Strain through per-chunk levels with bounded catch-up (P4.1), the public API (`com.cubiecode.remission.api`) posts host, chunk stage, nidus and cancellable spread events (P4.4), and festering chunks turn their grass, trees and stone into festering blocks by stage and finally flip to the `remission:fester` biome, with data maps for modded blocks and per-biome fester variants (P4.2). Phase 7 has started: the miasma fluid (and Mekanism chemical when Mekanism is present), nidus cores and festered soil exist with their default loot (P7.1); nothing processes them yet (P7.2). See the [roadmap](ROADMAP.md), [design](docs/DESIGN.md) and [architecture](docs/ARCHITECTURE.md). Player-facing content and its origin (AI, procedural, human, licensed) is tracked in [content provenance](docs/CONTENT-PROVENANCE.md).

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
| `tools/build-mod.sh [INSTANCE]` | Build and copy the jar into a test instance's `mods/` |
| `tools/pack.sh refresh` / `check` / `export` | packwiz index refresh, CI drift check, CurseForge zip (release artifact) + `.mrpack` (local testing) to `.local/dist/` |
| `tools/bump-version.sh mod\|pack X.Y.Z` | Set the mod or pack version (independent) |

Dev runs include KubeJS, ProbeJS, FTB Quests, EMI + JEI, Mekanism, AE2 and JourneyMap from Modrinth/FTB Maven for integration testing; disable with `-Pdev_runtime_mods=false`. `-Pdev_jbr=true` runs the client/server on JetBrains Runtime with enhanced hot-swap (opt-in).

## Repository layout

```text
mod/                 Remission NeoForge project (Gradle wrapper, gradle.properties)
pack/                Counterstrain packwiz pack, kubejs/, config/ftbquests/
tools/               Build, pack, release, headless-client, screenshot comparison, worktree setup and docs/provenance check scripts (bash, no root); visual-references/ holds reviewed scene screenshots
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

The mod and the pack are versioned independently. CI (`ci.yml`) builds, runs datagen and gametests and checks the pack on every push and PR that touches more than Markdown, and its `ci-ok` job and `docs.yml` are the required checks for `main`; `docs.yml` runs `tools/check-docs.py` and `tools/check-provenance.sh`; `gpu-smoke.yml` runs every harness scene on foundry's GPU (`tools/run-scenes.sh`) on manual dispatch or when a same-repo PR carries the `gpu-smoke` label, and uploads logs, screenshots and diffs. Tags `mod-v<version>` and `pack-v<version>` build draft GitHub releases (`release-mod.yml`, `release-pack.yml`). Platform publishing is roadmap P12.3: Remission goes to Modrinth and CurseForge; Counterstrain is CurseForge-first (the CurseForge zip is the release artifact; see [distribution](docs/DESIGN.md#distribution)). See the release skill.

## License

[MIT](LICENSE), covering Remission, the Counterstrain pack content in this repository (scripts, quests, configs, datapacks) and the tooling. Third-party mods referenced by the pack keep their own licenses and are downloaded from their authors' pages, not redistributed here. Vendored skills keep their upstream MIT license (`.agents/skills/LICENSE.minecraft-codex-skills`).
