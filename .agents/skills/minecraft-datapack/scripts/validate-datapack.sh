#!/usr/bin/env bash
set -euo pipefail

PASS='[PASS]'
WARN='[WARN]'
FAIL='[FAIL]'

ROOT='.'
STRICT=0
REQUIRE_MCMETA=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root)
      ROOT="${2:-}"
      shift 2
      ;;
    --strict)
      STRICT=1
      shift
      ;;
    --no-mcmeta)
      REQUIRE_MCMETA=0
      shift
      ;;
    --help|-h)
      cat <<'USAGE'
Usage: validate-datapack.sh [--root <path>] [--strict] [--no-mcmeta]

--no-mcmeta: root has no pack.mcmeta (mod resources, kubejs/ data roots)

Checks datapack structure and JSON validity:
- pack.mcmeta and data/** JSON parse with jq
- pack.mcmeta targets Minecraft 1.21.1 (integer pack_format 48)
- 1.21 singular path conventions (no pre-1.21 plural folders such as loot_tables, recipes, tags/items)
- engine load/tick tags under data/minecraft/tags/function resolve local references
USAGE
      exit 0
      ;;
    *)
      echo "$FAIL unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

if ! command -v jq >/dev/null 2>&1; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  JQ_SHIM="$SCRIPT_DIR/jq-shim.mjs"
  if command -v node >/dev/null 2>&1 && [[ -f "$JQ_SHIM" ]]; then
    jq() {
      node "$JQ_SHIM" "$@"
    }
  else
    echo "$FAIL jq is required"
    exit 1
  fi
fi

if [[ ! -d "$ROOT" ]]; then
  echo "$FAIL root path does not exist: $ROOT"
  exit 1
fi

FAILURES=0
WARNINGS=0

pass() { echo "$PASS $*"; }
warn() { echo "$WARN $*"; WARNINGS=$((WARNINGS + 1)); }
fail() { echo "$FAIL $*"; FAILURES=$((FAILURES + 1)); }
strip_cr() { printf '%s' "${1%$'\r'}"; }

check_json() {
  local file="$1"
  if jq empty "$file" >/dev/null 2>&1; then
    pass "valid JSON: ${file#$ROOT/}"
  else
    fail "invalid JSON: ${file#$ROOT/}"
  fi
}

# Minecraft 1.21.1 uses data pack format 48 with an integer pack.pack_format.
# min_format/max_format only exist from 1.21.9 (format 82+) and are not valid targets here.
EXPECTED_PACK_FORMAT=48

check_pack_metadata() {
  local file="$1"
  local pack_format

  if jq -e '.pack.pack_format | type == "number" and . == floor' "$file" >/dev/null 2>&1; then
    pack_format="$(jq -r '.pack.pack_format | numbers' "$file")"
    if [[ "$pack_format" -eq "$EXPECTED_PACK_FORMAT" ]]; then
      pass "pack.mcmeta pack_format is $EXPECTED_PACK_FORMAT (Minecraft 1.21.1)"
    else
      warn "pack.mcmeta pack_format is $pack_format; Minecraft 1.21.1 expects $EXPECTED_PACK_FORMAT"
    fi
  else
    fail "pack.mcmeta must define integer .pack.pack_format ($EXPECTED_PACK_FORMAT for 1.21.1)"
  fi

  if jq -e '.pack | has("min_format")' "$file" >/dev/null 2>&1 || jq -e '.pack | has("max_format")' "$file" >/dev/null 2>&1; then
    warn "pack.mcmeta min_format/max_format are 1.21.9+ fields and are not used by 1.21.1"
  fi

  if jq -e '.pack | has("supported_formats")' "$file" >/dev/null 2>&1 \
    && ! jq -e '.pack.supported_formats | ((type == "number" and . == floor) or (type == "array" and length == 2 and all(.[]; type == "number" and . == floor)) or (type == "object" and (.min_inclusive | type == "number" and . == floor) and (.max_inclusive | type == "number" and . == floor)))' "$file" >/dev/null 2>&1; then
    fail "pack.mcmeta pack.supported_formats must be an integer, two-integer array, or object with integer min_inclusive and max_inclusive"
  fi
}

echo "=== Datapack Validator ==="

echo "Checking required root files..."
if [[ -f "$ROOT/pack.mcmeta" ]]; then
  check_json "$ROOT/pack.mcmeta"
  check_pack_metadata "$ROOT/pack.mcmeta"
elif [[ "$REQUIRE_MCMETA" -eq 1 ]]; then
  fail "missing pack.mcmeta (use --no-mcmeta for mod resources or kubejs/ roots)"
fi

if [[ ! -d "$ROOT/data" ]]; then
  fail "missing data/ directory"
else
  pass "found data/ directory"
fi

echo "Checking JSON files under data/..."
while IFS= read -r -d '' json_file; do
  check_json "$json_file"
done < <(find "$ROOT/data" -type f -name '*.json' -print0 2>/dev/null)

echo "Checking pre-1.21 plural folder names..."
LEGACY_DIR_RE='^data/[^/]+/(functions|loot_tables|recipes|advancements|predicates|item_modifiers|structures|tags/(blocks|items|functions|entity_types|fluids|game_events))/'
while IFS= read -r -d '' data_file; do
  rel_path="${data_file#"$ROOT/"}"
  if [[ "$rel_path" =~ $LEGACY_DIR_RE ]]; then
    fail "pre-1.21 plural folder (ignored by 1.21.1): $rel_path"
  fi
done < <(find "$ROOT/data" -type f -print0 2>/dev/null)

resolve_function_ref() {
  local tag_file="$1"
  local ref="$2"
  local required="$3"
  local ancestry="$4"
  local target_ns target_path resolved kind

  if [[ "$ref" == *:* ]]; then
    target_ns="${ref%%:*}"
    target_path="${ref#*:}"
  else
    fail "invalid function id (missing namespace): ${tag_file#$ROOT/} -> $ref"
    return
  fi

  if [[ "$ref" == \#* ]]; then
    target_ns="${target_ns#\#}"
    kind="function tag"
    resolved="$ROOT/data/$target_ns/tags/function/$target_path.json"
  else
    kind="function"
    resolved="$ROOT/data/$target_ns/function/$target_path.mcfunction"
  fi

  if [[ ! -d "$ROOT/data/$target_ns" ]]; then
    warn "external $kind reference not verified: ${tag_file#$ROOT/} -> $ref"
    return
  fi

  if [[ -f "$resolved" ]]; then
    pass "$kind target exists: $ref"
    if [[ "$ref" == \#* ]]; then
      if [[ "$ancestry" == *"|$resolved|"* ]]; then
        fail "cyclic function tag reference: ${tag_file#$ROOT/} -> $ref"
        return
      fi
      check_function_tag "$resolved" "$ancestry|$resolved|"
    fi
  elif [[ "$required" == "false" ]]; then
    pass "optional $kind reference is absent: $ref"
  else
    fail "missing $kind for tag reference: $ref (expected ${resolved#$ROOT/})"
  fi
}

check_function_tag() {
  local tag_file="$1"
  local ancestry="$2"
  local required ref

  if ! jq -e '.values | type == "array"' "$tag_file" >/dev/null 2>&1; then
    fail "tag file missing array .values: ${tag_file#$ROOT/}"
    return
  fi

  while IFS=$'\t' read -r required ref; do
    required="$(strip_cr "$required")"
    ref="$(strip_cr "$ref")"
    if [[ "$required" == "invalid" || -z "$ref" ]]; then
      fail "invalid function tag entry: ${tag_file#$ROOT/}"
      continue
    fi
    resolve_function_ref "$tag_file" "$ref" "$required" "$ancestry"
  done < <(jq -r '.values[]? | if type == "string" then "true\t" + . elif type == "object" and (.id | type == "string") and ((.required? // true) | type == "boolean") then ((if .required == false then "false" else "true" end) + "\t" + .id) else "invalid\t" end' "$tag_file")
}

echo "Checking custom namespace load/tick tag names..."
while IFS= read -r -d '' tag_file; do
  case "${tag_file#"$ROOT/data/"}" in
    minecraft/*) ;;
    *) warn "custom namespace load/tick tag has no automatic engine behavior: ${tag_file#$ROOT/} (use data/minecraft/tags/function/)" ;;
  esac
done < <(find "$ROOT/data" -type f \( -path '*/tags/function/load.json' -o -path '*/tags/function/tick.json' \) -print0 2>/dev/null)

echo "Checking engine load/tick function tag references..."
for tag_file in "$ROOT/data/minecraft/tags/function/load.json" "$ROOT/data/minecraft/tags/function/tick.json"; do
  [[ -f "$tag_file" ]] || continue
  check_function_tag "$tag_file" "|$tag_file|"
done

echo ""
if [[ "$FAILURES" -gt 0 ]]; then
  echo "$FAIL datapack validation failed with $FAILURES error(s) and $WARNINGS warning(s)"
  exit 1
fi

if [[ "$STRICT" -eq 1 && "$WARNINGS" -gt 0 ]]; then
  echo "$FAIL datapack validation strict mode failed on $WARNINGS warning(s)"
  exit 1
fi

echo "$PASS datapack validation passed with $WARNINGS warning(s)"
