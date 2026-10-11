#!/usr/bin/env bash
# check-build.sh [project-dir]
# Verifies a NeoForge 1.21.1 ModDevGradle build environment and runs ./gradlew build.
# project-dir defaults to ./mod if it exists (Counterstrain layout), else the current directory.
# Adapted from jahrome907/minecraft-codex-skills (MIT); Fabric/Forge/Architectury paths removed.

set -euo pipefail

PASS="[PASS]"
FAIL="[FAIL]"
WARN="[WARN]"

if [[ $# -ge 1 ]]; then
    PROJECT_DIR="$1"
elif [[ -f "mod/gradlew" ]]; then
    PROJECT_DIR="mod"
else
    PROJECT_DIR="."
fi
cd "$PROJECT_DIR"

read_gradle_property() {
    [[ -f gradle.properties ]] && sed -n "s/^$1=//p" gradle.properties | head -1 || true
}

echo "=== NeoForge 1.21.1 Build Environment Check ($PWD) ==="

MINECRAFT_VERSION=$(read_gradle_property "minecraft_version")
NEO_VERSION=$(read_gradle_property "neo_version")
echo "$PASS minecraft_version=${MINECRAFT_VERSION:-?} neo_version=${NEO_VERSION:-?}"
[[ "$MINECRAFT_VERSION" == "1.21.1" ]] || echo "$WARN expected minecraft_version=1.21.1"

if grep -qs "net.neoforged.moddev" build.gradle build.gradle.kts settings.gradle settings.gradle.kts; then
    echo "$PASS ModDevGradle plugin (net.neoforged.moddev) found"
else
    echo "$WARN net.neoforged.moddev plugin not found in build/settings scripts"
fi

# Java 21 is required for Minecraft 1.21.1
if ! command -v java &>/dev/null; then
    echo "$FAIL java not found. Install JDK 21."
    exit 1
fi
JAVA_VERSION=$(java -version 2>&1 | head -1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')
JAVA_MAJOR=${JAVA_VERSION%%.*}
if [[ "$JAVA_MAJOR" =~ ^[0-9]+$ && "$JAVA_MAJOR" -ge 21 ]]; then
    echo "$PASS Java $JAVA_VERSION (the Gradle toolchain should target 21)"
else
    echo "$FAIL Java ${JAVA_VERSION:-unknown}: Minecraft 1.21.1 requires Java 21"
    exit 1
fi

if [[ ! -f gradlew ]]; then
    echo "$FAIL gradlew not found in $PWD"
    exit 1
fi
echo "$PASS gradlew found"

for f in src/main/resources/META-INF/neoforge.mods.toml gradle.properties; do
    [[ -f "$f" ]] && echo "$PASS $f" || echo "$WARN $f not found"
done
[[ -f src/main/resources/META-INF/mods.toml ]] && echo "$WARN META-INF/mods.toml found; NeoForge 1.21.1 reads neoforge.mods.toml"

echo ""
echo "Running ./gradlew build..."
./gradlew build --console=plain

mapfile -t JARS < <(find build/libs -maxdepth 1 -type f -name "*.jar" ! -name "*-sources.jar" 2>/dev/null)
if [[ "${#JARS[@]}" -gt 0 ]]; then
    echo "$PASS Build completed. Candidate jar(s):"
    printf '  -> %s\n' "${JARS[@]}"
else
    echo "$FAIL Build did not produce a jar in build/libs/"
    exit 1
fi
echo "=== Build environment check complete ==="
