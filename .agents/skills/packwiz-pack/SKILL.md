---
name: packwiz-pack
description: Add, remove, update or pin mods in the Counterstrain packwiz pack (pack/), refresh and export it, and include the locally built Remission jar for testing.
---

# Counterstrain pack (packwiz)

Install packwiz with `tools/install-packwiz.sh` (pinned commit in `tools/lib.sh`, no root). Run packwiz from `pack/`. Pack metadata is `pack.toml` (Minecraft 1.21.1, NeoForge 21.1.259; keep it equal to `neo_version` in `mod/gradle.properties`), `index.toml` is generated, each mod is `mods/<slug>.pw.toml`.

Distribution (docs/DESIGN.md): the pack is CurseForge-first. The CurseForge zip is the release artifact; the `.mrpack` is for local testing only. `packwiz curseforge export` lists only mods with CurseForge metadata in `manifest.json` and embeds every other jar under `overrides/mods/`. CurseForge only accepts embedded jars from its approved non-CurseForge mods list, and packwiz cannot match metadata across sites.

Adding: source new mods from CurseForge, `packwiz cf add <slug> -y` (FTB Quests, FTB Library, FTB Teams and Architectury already are), so every mod lands in the CurseForge manifest. When a mod is on both platforms, use the CurseForge source: it gives a clean CurseForge export, and the `.mrpack` still works for local testing because packwiz embeds those jars there. A Modrinth-only mod is added only if it is confirmed redistributable in a CurseForge pack; record the permission in the PR, otherwise do not add it. Accept the dependencies packwiz proposes, then check the mod's `META-INF/neoforge.mods.toml` for required mods the platform does not list (Ars Nouveau needs Curios and GeckoLib). packwiz picks the newest file, including betas; pin a specific file when that matters (`packwiz cf add --addon-id <id> --file-id <id>`, or for an existing Modrinth source `packwiz mr add --project-id <id> --version-id <id>`; JEI is pinned to its 19.51.0.418 NeoForge release).

Existing sources: most current pack mods were added from Modrinth and are embedded in the CurseForge zip. They all have the same file on CurseForge except Better Advanced Tooltips, which is Modrinth-only and is also bundled inside KubeJS. Re-sourcing them is pack membership work for P10.1 (or a dedicated pack item), not a drive-by change. EMI and JEI are both installed on purpose: EMI is the visible viewer and loads JEI plugins; do not remove JEI. Verify every new file name says neoforge/1.21.1. Keep pack and `mod/gradle.properties` dev-runtime versions aligned when you change a shared mod.

Sides and options: client-only mods get `side = "client"` in their `.pw.toml` (JourneyMap stays `both`: its server side enables server settings and the server overlay API); dev tools are optional, default off (`[option] optional = true`, `default = false`, `description`), like ProbeJS. Updating: `packwiz update <slug>` or `packwiz update --all`, then review every changed `.pw.toml` diff for loader, game version and release type before committing. Removing: `packwiz remove <slug>`, then remove orphaned dependencies manually.

Pack files: everything under `pack/` except ignored paths is indexed and shipped (`kubejs/`, `config/`, `defaultconfigs/`). Use `.packwizignore` for repo-only files. After any change run `tools/pack.sh refresh` and commit `index.toml` with it; CI runs `tools/pack.sh check` and fails on drift or untracked pack files.

Exports: `tools/pack.sh export` writes `.local/dist/counterstrain-<version>.zip` (CurseForge, the release artifact) and `.mrpack` (local testing). Before a release, the CurseForge zip must embed nothing under `overrides/mods/` that CurseForge would reject; the export log lists every jar "added to zip". The `.mrpack` embeds CurseForge-hosted jars (FTB) and is never published.

Remission is **not** in the index. For testing, build and copy it into an instance with `tools/build-mod.sh <instance-dir>` (default `.local/instance`); see playtesting. For a PC instance, `tools/pack-sync.sh` copies the jar alongside the served pack (`tools/pack.sh serve`). Remission is published on Modrinth and CurseForge. After its first release, add it from CurseForge with `packwiz cf add remission`, pinned to the released file, in a pack PR that also bumps the pack version. Never commit a local jar into `pack/mods/`.
