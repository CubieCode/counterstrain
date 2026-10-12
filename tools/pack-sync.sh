#!/usr/bin/env bash
# Sync a launcher instance on your own PC with the pack served from foundry.
#
#   tools/pack-sync.sh [--url URL] [--remote [HOST:]CHECKOUT] [--build]
#                      [--no-jar] [--no-gui] [INSTANCE_DIR]
#
# Meant as a Prism Launcher pre-launch command, so every launch updates the
# instance: Prism sets INST_MC_DIR (the default INSTANCE_DIR) and INST_JAVA.
# On foundry, `tools/pack.sh serve` must be running.
#
#   1. Runs packwiz-installer (through the pinned bootstrap jar, cached in
#      ~/.cache/counterstrain/) against the served pack.toml: changed mods,
#      configs, KubeJS scripts and quests are downloaded, removed files are
#      deleted. Files it did not install are left alone.
#   2. Copies Remission's jar (mod/build/libs/remission-<mod_version>.jar) from
#      the checkout over ssh into mods/, replacing older remission-*.jar.
#      --build first runs tools/build-mod.sh there. A failed copy only warns,
#      so you can still play offline with the jar you have.
#
#   --url URL      pack URL (default $COUNTERSTRAIN_PACK_URL or
#                  http://foundry:8080/pack.toml)
#   --remote R     checkout holding the jar, as HOST:PATH for ssh or a local
#                  PATH (default $COUNTERSTRAIN_REMOTE or
#                  lab@foundry:development/CubieCode/counterstrain)
#   --build        build Remission in that checkout before copying the jar
#   --no-jar       skip the Remission jar
#   --no-gui       no installer window (the window is where packwiz-installer
#                  asks you to download mods CurseForge will not serve to it)
#
# Self-contained (no tools/lib.sh): it runs on the PC, where copying this one
# file is enough.
set -euo pipefail

BOOTSTRAP_URL="https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar"
BOOTSTRAP_SHA256="a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c"

log() { printf '[pack-sync] %s\n' "$*" >&2; }
die() { log "error: $*"; exit 1; }
usage() { sed -n '2,29p' "$0" >&2; exit 2; }

url="${COUNTERSTRAIN_PACK_URL:-http://foundry:8080/pack.toml}"
remote="${COUNTERSTRAIN_REMOTE:-lab@foundry:development/CubieCode/counterstrain}"
build=0 jar=1 gui=1 instance="${INST_MC_DIR:-}"
while (($#)); do
  case "$1" in
    --url) url="${2:?--url needs a value}"; shift 2 ;;
    --remote) remote="${2:?--remote needs a value}"; shift 2 ;;
    --build) build=1; shift ;;
    --no-jar) jar=0; shift ;;
    --no-gui) gui=0; shift ;;
    -h|--help) usage ;;
    -*) die "unknown option $1" ;;
    *) instance="$1"; shift ;;
  esac
done
[[ -n "$instance" ]] || die "no instance directory (pass one, or run from Prism, which sets INST_MC_DIR)"
[[ -d "$instance" ]] || die "instance directory not found: $instance"
instance="$(cd "$instance" && pwd)"
java="${INST_JAVA:-java}"
command -v "$java" >/dev/null 2>&1 || die "'$java' not found; install Java 21 or run from Prism"

# 1. Pack files through packwiz-installer.
cache="${XDG_CACHE_HOME:-$HOME/.cache}/counterstrain"
bootstrap="$cache/packwiz-installer-bootstrap.jar"
if ! echo "$BOOTSTRAP_SHA256  $bootstrap" | sha256sum -c --status 2>/dev/null; then
  mkdir -p "$cache"
  log "Downloading packwiz-installer-bootstrap"
  curl -fsSL -o "$bootstrap.part" "$BOOTSTRAP_URL" || die "download failed: $BOOTSTRAP_URL"
  echo "$BOOTSTRAP_SHA256  $bootstrap.part" | sha256sum -c --status || die "bootstrap checksum mismatch"
  mv "$bootstrap.part" "$bootstrap"
fi
args=(-s client)
((gui)) || args=(-g "${args[@]}")
log "Syncing $instance from $url"
(cd "$instance" && "$java" -jar "$bootstrap" "${args[@]}" "$url") ||
  die "packwiz-installer failed (is tools/pack.sh serve running on foundry, and is $url reachable?)"

# 2. Remission jar from the checkout.
((jar)) || exit 0
if [[ "$remote" == *:* ]]; then
  host="${remote%%:*}" checkout="${remote#*:}"
  run() { ssh -o BatchMode=yes "$host" "$1"; }
  fetch() { scp -q -o BatchMode=yes "$host:$1" "$2"; }
else
  checkout="$remote"
  run() { bash -c "$1"; }
  fetch() { cp "$1" "$2"; }
fi
q="$(printf %q "$checkout")"
if ((build)); then
  log "Building Remission in $remote"
  run "cd $q && tools/build-mod.sh .local/pack-sync >&2" || { log "warning: build failed; keeping the current jar"; exit 0; }
fi
version="$(run "sed -n 's/^mod_version=//p' $q/mod/gradle.properties")" || version=""
[[ -n "$version" ]] || { log "warning: cannot read mod_version from $remote; keeping the current jar"; exit 0; }
name="remission-$version.jar"
mkdir -p "$instance/mods"
tmp="$(mktemp "$instance/mods/.remission.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
if ! fetch "$checkout/mod/build/libs/$name" "$tmp"; then
  log "warning: $name not found in $remote (build it with --build or ./gradlew build); keeping the current jar"
  exit 0
fi
if cmp -s "$tmp" "$instance/mods/$name"; then
  log "$name is up to date"
  exit 0
fi
find "$instance/mods" -maxdepth 1 -name 'remission-*.jar' -delete
mv "$tmp" "$instance/mods/$name"
log "Installed $name"
