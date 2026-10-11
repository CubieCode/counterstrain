---
name: playtesting
description: Run a full Counterstrain playtest on foundry with the current pack and a fresh Remission build, record findings and evidence. Use for pack assembly, balancing, quest checks and integration bugs.
---

# Playtesting

A playtest uses the real pack, not the mod's dev runtime. Two setups:

**Dev-runtime quick test** (mod plus a subset of pack mods): `tools/run-client-headless.sh` runs `runClient` with KubeJS, FTB Quests, JEI, Mekanism and AE2 already present. To try pack scripts there, copy or symlink `pack/kubejs` and `pack/config/ftbquests` into `mod/run/client/` (gitignored), then remove them afterwards. Good for Remission ↔ KubeJS/FTB integration, not for balance.

**Full pack instance:**
1. `tools/pack.sh export`, then install `.local/dist/counterstrain-<version>.mrpack` into an instance directory, e.g. `.local/instance/`. Without a launcher, unzip the mrpack, download the files listed in `modrinth.index.json` into the instance, and copy `overrides/` over it. Install the NeoForge 21.1.259 client or server into that instance with the NeoForge installer jar, run as `lab` (no root).
2. `tools/build-mod.sh .local/instance` builds Remission and replaces `mods/remission-*.jar`.
3. Server-side checks: run the instance as a dedicated server headlessly (`java -jar` the NeoForge server launcher, `nogui`). Client checks on foundry need the display wrapper: `xvfb-run -a -s "-screen 0 1920x1080x24" /opt/VirtualGL/bin/vglrun -d egl <launch command>`, then `tools/verify-gpu-render.sh <instance>/logs/latest.log`. Never start a client outside vglrun.
4. Use a fixed seed and record it, plus pack version, mod commit and config changes.

What to check: startup without errors (`logs/latest.log`, `logs/kubejs/*.log`), JEI recipe gates as the scripts intend, quest tasks tick and rewards fire once, Remission spread/purification behaves at pack balance values, TPS/FPS under load (note numbers on foundry).

Report: a short write-up in the PR or item: setup, seed, what was played, issues with log excerpts, screenshots collected with `tools/collect-screenshots.sh <instance>` and reviewed per visual-checks. Do not commit instances, worlds or logs; attach artifacts or keep them in `.local/`.
