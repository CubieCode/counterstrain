---
name: packwiz-pack
description: Add, remove, update or pin mods in the Counterstrain packwiz pack (pack/), refresh and export it, and include the locally built Remission jar for testing.
---

# Counterstrain pack (packwiz)

Install packwiz with `tools/install-packwiz.sh` (pinned commit in `tools/lib.sh`, no root). Run packwiz from `pack/`. Pack metadata is `pack.toml` (Minecraft 1.21.1, NeoForge 21.1.259; keep it equal to `neo_version` in `mod/gradle.properties`), `index.toml` is generated, each mod is `mods/<slug>.pw.toml`.

Adding: prefer Modrinth, `packwiz mr add <slug> -y`; accept the dependencies it proposes, then check the mod's `META-INF/neoforge.mods.toml` for required mods Modrinth does not list (Ars Nouveau needs Curios and GeckoLib). CurseForge only when the mod is not on Modrinth: `packwiz cf add <slug> -y` (FTB Quests, FTB Library, FTB Teams, Architectury come from CurseForge here). packwiz picks the newest file, including betas, and Modrinth version numbers can be shared across loaders: when that matters, pin with `packwiz mr add --project-id <id> --version-id <id>` (JEI is pinned to its 19.51.0.418 NeoForge release). EMI and JEI are both installed on purpose: EMI is the visible viewer and loads JEI plugins; do not remove JEI. Verify every new file name says neoforge/1.21.1. Keep pack and `mod/gradle.properties` dev-runtime versions aligned when you change a shared mod.

Sides and options: client-only mods get `side = "client"` in their `.pw.toml` (JourneyMap stays `both`: its server side enables server settings and the server overlay API); dev tools are optional, default off (`[option] optional = true`, `default = false`, `description`), like ProbeJS. Updating: `packwiz update <slug>` or `packwiz update --all`, then review every changed `.pw.toml` diff for loader, game version and release type before committing. Removing: `packwiz remove <slug>`, then remove orphaned dependencies manually.

Pack files: everything under `pack/` except ignored paths is indexed and shipped (`kubejs/`, `config/`, `defaultconfigs/`). Use `.packwizignore` for repo-only files. After any change run `tools/pack.sh refresh` and commit `index.toml` with it; CI runs `tools/pack.sh check` and fails on drift or untracked pack files.

Exports: `tools/pack.sh export` writes `.local/dist/counterstrain-<version>.mrpack` and `.zip` (CurseForge). The CurseForge zip embeds Modrinth-only jars and the `.mrpack` embeds CurseForge-hosted jars (FTB); that is fine for local testing but must be resolved before publishing (release skill).

Remission is **not** in the index. For testing, build and copy it into an instance with `tools/build-mod.sh <instance-dir>` (default `.local/instance`); see playtesting. After the first Modrinth release, add it with `packwiz mr add remission` pinned to the released version, in a pack PR that also bumps the pack version. Never commit a local jar into `pack/mods/`.
