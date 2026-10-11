---
name: release
description: Version, changelog, tag and publish Remission (mod) and Counterstrain (pack) independently with mod-v* and pack-v* tags. Use only when a release is explicitly assigned.
---

# Releases

Two independent products. Remission version: `mod_version` in `mod/gradle.properties`. Counterstrain version: `version` in `pack/pack.toml`. Both semver (`0.y.z` before 1.0; `-beta.N` prereleases). A pack release that changes the Remission version it ships needs the mod released first.

Steps for each product:
1. On a branch, `tools/bump-version.sh mod|pack X.Y.Z` (it edits the file, refreshes the pack index for pack bumps, and never commits or tags).
2. Add a section to `mod/CHANGELOG.md` or `pack/CHANGELOG.md` (Keep a Changelog style: Added/Changed/Fixed/Removed, newest first, roadmap IDs in parentheses).
3. Run the full checks (`./gradlew build runData runGameTestServer`, `tools/pack.sh check`, a GPU headless run for anything visual), open the PR `Release: Remission X.Y.Z` / `Release: Counterstrain X.Y.Z`, merge when authorised.
4. Tag the merge commit on main: `git tag mod-vX.Y.Z` or `pack-vX.Y.Z`, push only that tag.

Workflows: `release-mod.yml` checks the tag matches `mod_version`, builds with gametests and creates a **draft** GitHub release with the jar. `release-pack.yml` checks the tag matches `pack.toml`, runs the packwiz drift check, exports `.mrpack` and CurseForge zip, and attaches them to a draft. A human reviews and publishes drafts. Never delete or move a pushed tag; fix forward with a new patch version.

Platform publishing is roadmap P11.2. Both products are MIT (root `LICENSE`, `mod_license=MIT`); third-party mods keep their own licenses, which is why exports must reference rather than embed them where possible. It needs Modrinth/CurseForge project IDs as repo variables, tokens as secrets, and a chosen tool (mc-publish action or Minotaur/CurseForgeGradle; see the vendored minecraft-ci-release skill). Pack exports currently embed cross-platform jars (FTB from CurseForge in the `.mrpack`, Modrinth jars in the CurseForge zip). Before publishing, make each export reference only its platform's files or confirm redistribution rights, and add Remission to the pack from its published listing (packwiz-pack). Publishing jobs never run on self-hosted runners with secrets from fork PRs.
