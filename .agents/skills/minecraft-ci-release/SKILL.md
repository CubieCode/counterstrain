---
name: minecraft-ci-release
description: "Set up and review GitHub Actions CI, versioning, and releases for a Minecraft 1.21.1 NeoForge mod (ModDevGradle, Java 21) and a packwiz modpack, including Modrinth/CurseForge publishing. Use for pipelines and releases, not gameplay code or server operations."
---

> Vendored from https://github.com/jahrome907/minecraft-codex-skills (commit
> `dd57c5a97741cdc0eb5bb3a8f876581a4f09eeb0`), MIT license — see
> `../LICENSE.minecraft-codex-skills`. Adapted for Minecraft 1.21.1 + NeoForge for
> Counterstrain/Remission. Project skills (release, neoforge-mod-dev, visual-checks,
> etc.) take precedence where they overlap.

# Minecraft CI / Release Skill (1.21.1 NeoForge + packwiz)

## Project facts

- Mod `remission` in `mod/`: NeoForge 21.1.x, ModDevGradle 2.x, Gradle wrapper `mod/gradlew`,
  Java 21 (Temurin). Output: `mod/build/libs/remission-<version>.jar` (check `-sources` etc.
  are excluded when selecting files).
- Modpack in `pack/` managed by packwiz; exported to Modrinth (`.mrpack`) and CurseForge (`.zip`).
- Tags are independent: `mod-v1.2.3` releases the mod, `pack-v1.2.3` releases the pack.
- Keep existing release destinations and approval policy. Never commit tokens.

## Runners

- Default to `ubuntu-latest` for build, test, and release jobs.
- Self-hosted runners `[self-hosted, foundry, gpu]` have **no sudo**: never `apt-get`/`sudo`
  in jobs that may run there. `actions/setup-java` / `setup-go` install into the runner tool
  cache and work without sudo. Use these runners only for jobs that need the GPU (e.g. client
  visual checks), not for jobs that hold publishing secrets or run untrusted fork PR code.

## Pinned actions

Pin remote actions to full commit SHAs. Verified with `git ls-remote` on 2026-10-11 (tag →
dereferenced commit); refresh before upgrading:

```text
actions/checkout@v7.0.1             3d3c42e5aac5ba805825da76410c181273ba90b1
actions/setup-java@v6.0.1           de7274f081f381c8f8158605e0321c36c376e2e6
gradle/actions/setup-gradle@v6.4.0  3f5f9adaf7d9fecd50b5935e54106014257a94e6
actions/upload-artifact@v7.0.2      cf430e030ddbb5b0abf93d22962f4752f3646cd9
actions/setup-go@v7.0.0             b7ad1dad31e06c5925ef5d2fc7ad053ef454303e
softprops/action-gh-release@v3.0.3  efb35369e0ad2afab669f228072c1b0d510eae64
Kira-NT/mc-publish@v3.3.1           52307b03863581dec6b652b83e597aec02ebb075
```

For an annotated tag, use the `^{}` commit:
`git ls-remote https://github.com/OWNER/REPO refs/tags/vX 'refs/tags/vX^{}'`.
`mc-publish` moved from `Kir-Antipov/mc-publish` to `Kira-NT/mc-publish` (old name redirects).

---

## CI workflow

### `.github/workflows/build.yml`
```yaml
name: Build

on:
  push:
    branches: ["main"]
  pull_request:

permissions:
  contents: read

jobs:
  mod:
    name: Mod
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: mod
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6
        with:
          cache-read-only: ${{ github.event_name == 'pull_request' }}
      - name: Build and test
        run: ./gradlew build --no-daemon
      - uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9
        with:
          name: remission-${{ github.sha }}
          path: mod/build/libs/*.jar
          if-no-files-found: error

  pack:
    name: Pack
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
      - uses: actions/setup-go@b7ad1dad31e06c5925ef5d2fc7ad053ef454303e
        with:
          go-version: stable
      - name: Install packwiz
        run: go install github.com/packwiz/packwiz@ef87d964f8cbd52b3b13ea42453ef322290e2b9e
      - name: Check packwiz index is up to date
        working-directory: pack
        run: |
          packwiz refresh
          git diff --exit-code -- .
```

Notes:
- `setup-gradle` validates the Gradle wrapper checksum by default.
- packwiz publishes no releases; pin `go install` to a commit (above: main on 2026-09-06).
- `./gradlew build` runs `test`. Game tests / client runs (`runGameTestServer`, `runClient`)
  belong in separate jobs; GPU-dependent ones go on the self-hosted runners.
- Required checks after the first run: `Build / Mod`, `Build / Pack`.

---

## Mod release (`mod-v*`)

### `.github/workflows/release-mod.yml`
```yaml
name: Release mod

on:
  push:
    tags:
      - "mod-v*"

permissions:
  contents: write

jobs:
  release:
    name: Release mod
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: mod
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6
      - name: Verify tag matches mod_version
        run: |
          TAG_VERSION="${GITHUB_REF_NAME#mod-v}"
          MOD_VERSION="$(sed -n 's/^mod_version=//p' gradle.properties | tr -d '\r')"
          if [ "$TAG_VERSION" != "$MOD_VERSION" ]; then
            echo "::error::tag $GITHUB_REF_NAME does not match mod_version=$MOD_VERSION"
            exit 1
          fi
          echo "MOD_VERSION=$MOD_VERSION" >> "$GITHUB_ENV"
      - name: Build
        run: ./gradlew build --no-daemon
      - name: Publish
        uses: Kira-NT/mc-publish@52307b03863581dec6b652b83e597aec02ebb075
        with:
          modrinth-id: ${{ vars.MODRINTH_MOD_ID }}
          modrinth-token: ${{ secrets.MODRINTH_TOKEN }}
          curseforge-id: ${{ vars.CURSEFORGE_MOD_ID }}
          curseforge-token: ${{ secrets.CURSEFORGE_TOKEN }}
          github-token: ${{ github.token }}
          files: |
            mod/build/libs/!(*-@(dev|sources|javadoc)).jar
          version: ${{ env.MOD_VERSION }}
          version-type: release
          loaders: neoforge
          game-versions: "1.21.1"
          java: "21"
```

- Do not override `mod_version` from the tag; a mismatch must fail.
- Delete the Modrinth or CurseForge inputs for destinations the project does not use.
- `changelog-file:` can point at a per-release notes file (extract the section for the version
  from the changelog in a prior step and fail if it is missing).
- mc-publish infers dependencies from `META-INF/neoforge.mods.toml`; check the result on the
  first release. Gradle-plugin alternative (Minotaur / CurseForgeGradle):
  [publishing reference](references/publishing-gradle.md).

---

## Pack release (`pack-v*`)

### `.github/workflows/release-pack.yml`
```yaml
name: Release pack

on:
  push:
    tags:
      - "pack-v*"

permissions:
  contents: write

jobs:
  release:
    name: Release pack
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
      - uses: actions/setup-go@b7ad1dad31e06c5925ef5d2fc7ad053ef454303e
        with:
          go-version: stable
      - name: Install packwiz
        run: go install github.com/packwiz/packwiz@ef87d964f8cbd52b3b13ea42453ef322290e2b9e
      - name: Verify tag matches pack.toml version
        run: |
          TAG_VERSION="${GITHUB_REF_NAME#pack-v}"
          PACK_VERSION="$(sed -n 's/^version *= *"\(.*\)"/\1/p' pack/pack.toml)"
          if [ "$TAG_VERSION" != "$PACK_VERSION" ]; then
            echo "::error::tag $GITHUB_REF_NAME does not match pack.toml version=$PACK_VERSION"
            exit 1
          fi
      - name: Export
        working-directory: pack
        run: |
          mkdir -p ../dist
          packwiz refresh
          git diff --exit-code -- .
          packwiz modrinth export -o "../dist/counterstrain-${GITHUB_REF_NAME#pack-v}.mrpack"
          packwiz curseforge export -o "../dist/counterstrain-${GITHUB_REF_NAME#pack-v}-curseforge.zip"
      - uses: softprops/action-gh-release@efb35369e0ad2afab669f228072c1b0d510eae64
        with:
          files: |
            dist/*.mrpack
            dist/*.zip
          fail_on_unmatched_files: true
          generate_release_notes: true
```

packwiz export caveats:
- `curseforge export` lists CurseForge-sourced mods in `manifest.json` and **embeds** every
  non-CurseForge mod JAR in the zip — check each embedded mod's license and CurseForge's
  third-party-file rules. Default `--side client`.
- `modrinth export` embeds files whose download host is not on Modrinth's allowlist
  (`--restrictDomains`, default true). CurseForge mods that block third-party downloads need a
  manual download and will break unattended CI.
- Uploading the `.mrpack`/zip to Modrinth/CurseForge is done manually (or via their APIs) unless
  verified otherwise: mc-publish documents mods/plugins/resource packs, not modpacks.
- If the pack includes `remission`, release the mod first, then `packwiz update` that entry,
  commit, and tag the pack.

---

## Secrets and variables

Repository secrets: `MODRINTH_TOKEN` (Modrinth PAT with version-create scope) and
`CURSEFORGE_TOKEN` (CurseForge upload API token). Repository variables (not secret):
`MODRINTH_MOD_ID`, `CURSEFORGE_MOD_ID`. `github.token` needs `contents: write` only in release
workflows. Do not expose secrets to `pull_request` jobs or to self-hosted runners.

## Versioning

- Mod: SemVer `mod_version` in `mod/gradle.properties`; append `+1.21.1` to the artifact
  version only if the project's build already does. Pre-releases: `1.0.0-beta.1`; set
  `version-type: beta`/`alpha` accordingly.
- Pack: `version` in `pack/pack.toml`, independent of the mod version.
- Keep a changelog with `## [x.y.z] — YYYY-MM-DD` headings per component if the project uses
  one; release notes must come from the matching section, never the whole file.
- Tag only from a clean, pushed, CI-green `main` commit; never retag a published release.

## Dependabot

### `.github/dependabot.yml`
```yaml
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/mod"
    schedule:
      interval: "weekly"
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
```

Do not let Dependabot bump NeoForge or Minecraft versions blindly; those are deliberate
upgrades (ignore `net.neoforged:neoforge` if it is proposed).

## Workflow Snippet Validator

```bash
.agents/skills/minecraft-ci-release/scripts/validate-workflow-snippets.sh \
  --root .agents/skills/minecraft-ci-release [--strict]
```

It parses only the YAML blocks in this `SKILL.md` (bundled js-yaml, Node required): workflow
keys, placeholders, documented secrets, SHA-pinned `uses:`. It does not validate the project's
`.github/workflows/`; use `actionlint` for that if available.

## References

- https://github.com/Kira-NT/mc-publish
- https://packwiz.infra.link/
- https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-your-deployments
- https://docs.github.com/en/actions/hosting-your-own-runners
