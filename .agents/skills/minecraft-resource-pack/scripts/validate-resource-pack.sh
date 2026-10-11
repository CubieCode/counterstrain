#!/usr/bin/env bash
set -euo pipefail

PASS='[PASS]'
WARN='[WARN]'
FAIL='[FAIL]'

ROOT='.'
STRICT=0
# Minecraft 1.21.1 resource pack format. Override with --target-format.
TARGET_FORMAT=34
MOD_RESOURCES=0

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
    --target-format)
      TARGET_FORMAT="${2:-}"
      shift 2
      ;;
    --mod-resources)
      MOD_RESOURCES=1
      shift
      ;;
    --help|-h)
      cat <<'USAGE'
Usage: validate-resource-pack.sh [--root <path>] [--strict] [--target-format <n>] [--mod-resources]

Checks resource pack integrity (defaults target Minecraft 1.21.1, pack_format 34):
- JSON validity for assets/** and pack.mcmeta
- pack.mcmeta pack_format / supported_formats cover the target format
- model/blockstate/font/sounds references point to existing files
- warns about 1.21.4+ item definitions (assets/<ns>/items/) that 1.21.1 ignores
- every .png.mcmeta has a matching .png

--mod-resources  root is a NeoForge mod resources dir (e.g. mod/src/main/resources);
                 pack.mcmeta is optional because NeoForge generates it.
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

check_pack_metadata() {
  local file="$1"
  local min_parts max_parts min_major min_minor max_major max_minor pack_format
  local has_pack_format=0
  local has_min_format=0
  local has_max_format=0
  local has_supported_formats=0

  if jq -e '.pack | has("pack_format")' "$file" >/dev/null 2>&1; then
    has_pack_format=1
  fi
  if jq -e '.pack | has("min_format")' "$file" >/dev/null 2>&1; then
    has_min_format=1
  fi
  if jq -e '.pack | has("max_format")' "$file" >/dev/null 2>&1; then
    has_max_format=1
  fi
  if jq -e '.pack | has("supported_formats")' "$file" >/dev/null 2>&1; then
    has_supported_formats=1
  fi

  if jq -e '.pack.pack_format | type == "number" and . == floor' "$file" >/dev/null 2>&1; then
    pass "pack.mcmeta uses integer pack.pack_format"
  elif [[ "$has_pack_format" -eq 1 ]]; then
    fail "pack.mcmeta pack.pack_format must be an integer when present"
  fi

  if jq -e '.pack.min_format | ((type == "number" and . == floor) or (type == "array" and (length == 1 or length == 2) and all(.[]; type == "number" and . == floor)))' "$file" >/dev/null 2>&1; then
    pass "pack.mcmeta uses valid pack.min_format"
  elif [[ "$has_min_format" -eq 1 ]]; then
    fail "pack.mcmeta pack.min_format must be an integer or a one/two-integer array"
  fi

  if jq -e '.pack.max_format | ((type == "number" and . == floor) or (type == "array" and (length == 1 or length == 2) and all(.[]; type == "number" and . == floor)))' "$file" >/dev/null 2>&1; then
    pass "pack.mcmeta uses valid pack.max_format"
  elif [[ "$has_max_format" -eq 1 ]]; then
    fail "pack.mcmeta pack.max_format must be an integer or a one/two-integer array"
  fi

  if [[ "$has_supported_formats" -eq 1 ]] && ! jq -e '.pack.supported_formats | ((type == "number" and . == floor) or (type == "array" and length == 2 and all(.[]; type == "number" and . == floor)) or (type == "object" and (.min_inclusive | type == "number" and . == floor) and (.max_inclusive | type == "number" and . == floor)))' "$file" >/dev/null 2>&1; then
    fail "pack.mcmeta pack.supported_formats must be an integer, integer range, or min_inclusive/max_inclusive object"
  fi

  if [[ "$has_min_format" -ne "$has_max_format" ]]; then
    fail "pack.mcmeta must define both .pack.min_format and .pack.max_format together"
    return
  fi

  if [[ "$has_min_format" -eq 1 ]]; then
    if ! jq -e '.pack.min_format | ((type == "number" and . == floor) or (type == "array" and (length == 1 or length == 2) and all(.[]; type == "number" and . == floor)))' "$file" >/dev/null 2>&1 || ! jq -e '.pack.max_format | ((type == "number" and . == floor) or (type == "array" and (length == 1 or length == 2) and all(.[]; type == "number" and . == floor)))' "$file" >/dev/null 2>&1; then
      return
    fi

    min_parts="$(jq -r '.pack.min_format | if type == "number" then "\(.)\t0" elif type == "array" and length == 1 then "\(.[0])\t0" else "\(.[0])\t\(.[1])" end' "$file")"
    max_parts="$(jq -r '.pack.max_format | if type == "number" then "\(.)\t2147483647" elif type == "array" and length == 1 then "\(.[0])\t2147483647" else "\(.[0])\t\(.[1])" end' "$file")"
    IFS=$'\t' read -r min_major min_minor <<<"$min_parts"
    IFS=$'\t' read -r max_major max_minor <<<"$max_parts"

    if (( min_major > max_major || (min_major == max_major && min_minor > max_minor) )); then
      fail "pack.mcmeta pack.min_format must not be greater than pack.max_format"
      return
    fi

    if (( min_major < 65 )) && ! jq -e '.pack.pack_format | type == "number" and . == floor' "$file" >/dev/null 2>&1; then
      fail "pack.mcmeta ranges that include legacy resource pack formats below 65 require integer pack.pack_format"
      return
    fi

    if (( min_major < 65 )) && [[ "$has_supported_formats" -ne 1 ]]; then
      fail "pack.mcmeta ranges that include legacy resource pack formats below 65 require pack.supported_formats"
      return
    fi

    if (( min_major >= 65 )) && [[ "$has_supported_formats" -eq 1 ]]; then
      fail "pack.mcmeta must not define pack.supported_formats for modern-only resource pack formats"
      return
    fi

    return
  fi

  if jq -e '.pack.pack_format | type == "number" and . == floor' "$file" >/dev/null 2>&1; then
    pack_format="$(jq -r '.pack.pack_format | numbers' "$file")"
    if (( pack_format < 65 )); then
      return
    fi
    fail "modern resource pack formats 65 and newer require both .pack.min_format and .pack.max_format"
    return
  fi

  fail "pack.mcmeta must define legacy integer .pack.pack_format or both .pack.min_format and .pack.max_format"
}

resolve_texture() {
  local current_ns="$1"
  local ref="$2"
  local ns path target

  [[ -z "$ref" || "$ref" == \#* ]] && return
  if [[ "$ref" == *:* ]]; then
    ns="${ref%%:*}"
    path="${ref#*:}"
  else
    ns="minecraft"
    path="$ref"
  fi

  target="$ROOT/assets/$ns/textures/$path.png"
  if [[ -f "$target" ]]; then
    pass "texture exists: $ns:$path"
  elif [[ "$ns" == "minecraft" && "$current_ns" != "minecraft" ]]; then
    return  # assumed vanilla texture
  elif [[ "$ns" != "$current_ns" ]]; then
    warn "external texture not locally verifiable: $ns:$path"
  else
    fail "missing texture: $ns:$path (expected ${target#$ROOT/})"
  fi
}

resolve_model() {
  local current_ns="$1"
  local ref="$2"
  local ns path target

  [[ -z "$ref" || "$ref" == builtin/* ]] && return
  if [[ "$ref" == *:* ]]; then
    ns="${ref%%:*}"
    path="${ref#*:}"
  else
    ns="minecraft"
    path="$ref"
  fi

  if [[ "$ns" == "minecraft" ]]; then
    return
  fi

  target="$ROOT/assets/$ns/models/$path.json"
  if [[ -f "$target" ]]; then
    pass "model exists: $ns:$path"
  elif [[ "$ns" != "$current_ns" ]]; then
    warn "external model not locally verifiable: $ns:$path"
  else
    fail "missing model: $ns:$path (expected ${target#$ROOT/})"
  fi
}

resolve_sound() {
  local current_ns="$1"
  local sound_type="$2"
  local ref="$3"
  local sounds_file="$4"
  local ns path target event

  if [[ -z "$ref" ]]; then
    fail "sounds.json entry is missing a name"
    return
  fi
  if [[ "$sound_type" == "event" ]]; then
    if [[ "$ref" == *:* ]]; then
      ns="${ref%%:*}"
      event="${ref#*:}"
    else
      ns="minecraft"
      event="$ref"
    fi
    if [[ "$ns" != "$current_ns" ]]; then
      warn "external sound event not locally verifiable: $ns:$event"
    elif jq -r 'keys[]' "$sounds_file" 2>/dev/null | grep -Fqx "$event"; then
      pass "sound event alias exists: $ns:$event"
    else
      fail "missing sound event alias target: $ns:$event"
    fi
    return
  fi
  if [[ "$sound_type" != "file" ]]; then
    fail "unsupported sounds.json entry type '$sound_type' for $ref (expected file or event)"
    return
  fi
  if [[ "$ref" == *:* ]]; then
    ns="${ref%%:*}"
    path="${ref#*:}"
  else
    ns="minecraft"
    path="$ref"
  fi

  target="$ROOT/assets/$ns/sounds/$path.ogg"
  if [[ -f "$target" ]]; then
    pass "sound exists: $ns:$path"
  elif [[ "$ns" == "minecraft" && "$current_ns" != "minecraft" ]]; then
    return  # assumed vanilla sound
  elif [[ "$ns" != "$current_ns" ]]; then
    warn "external sound not locally verifiable: $ns:$path"
  else
    fail "missing sound: $ns:$path (expected ${target#$ROOT/})"
  fi
}

echo "=== Resource Pack Validator ==="

check_target_format() {
  local file="$1" pf
  pf="$(jq -r '.pack.pack_format | numbers' "$file" 2>/dev/null || true)"
  if [[ -n "$pf" && "$pf" != "$TARGET_FORMAT" ]]; then
    if jq -e '.pack | has("supported_formats")' "$file" >/dev/null 2>&1; then
      warn "pack_format $pf is not the target $TARGET_FORMAT; confirm supported_formats includes $TARGET_FORMAT"
    else
      warn "pack_format $pf does not match target format $TARGET_FORMAT (Minecraft 1.21.1 = 34)"
    fi
  elif [[ "$pf" == "$TARGET_FORMAT" ]]; then
    pass "pack_format matches target $TARGET_FORMAT"
  fi
  if jq -e '.pack | has("min_format")' "$file" >/dev/null 2>&1 && (( TARGET_FORMAT < 65 )); then
    warn "min_format/max_format are 1.21.9+ fields; 1.21.1 reads pack_format/supported_formats only"
  fi
}

if [[ -f "$ROOT/pack.mcmeta" ]]; then
  check_json "$ROOT/pack.mcmeta"
  check_pack_metadata "$ROOT/pack.mcmeta"
  check_target_format "$ROOT/pack.mcmeta"
elif [[ "$MOD_RESOURCES" -eq 1 ]]; then
  pass "no pack.mcmeta (NeoForge generates mod pack metadata)"
else
  fail "missing pack.mcmeta (use --mod-resources for a NeoForge mod resources dir)"
fi

if [[ ! -d "$ROOT/assets" ]]; then
  fail "missing assets/ directory"
fi

echo "Checking JSON files under assets/..."
while IFS= read -r -d '' json_file; do
  check_json "$json_file"
done < <(find "$ROOT/assets" -type f -name '*.json' -print0 2>/dev/null)

echo "Checking model and blockstate references..."
while IFS= read -r -d '' model_file; do
  rel="${model_file#"$ROOT/assets/"}"
  ns="${rel%%/*}"

  while IFS= read -r tex; do
    IFS=$'\t' read -r texture_status texture_key texture_ref <<< "$(strip_cr "$tex")"
    if [[ "$texture_status" == 'valid' ]]; then
      resolve_texture "$ns" "$texture_ref"
    else
      fail "invalid texture entry for $texture_key in ${model_file#$ROOT/} (use a string or {sprite: string, force_translucent?: boolean})"
    fi
  done < <(jq -r '(.textures // {} | to_entries[]? | [(if (.value | type) == "string" then "valid" elif (.value | type) == "object" then if ((.value.sprite? | type) == "string" and ((.value.force_translucent? | type) == "null" or (.value.force_translucent | type) == "boolean")) then "valid" else "invalid" end else "invalid" end), .key, (if (.value | type) == "string" then .value elif (.value | type) == "object" then (.value.sprite? // empty) else empty end)] | @tsv)' "$model_file")

  if (( TARGET_FORMAT < 65 )); then
    while IFS= read -r obj_key; do
      obj_key="$(strip_cr "$obj_key")"
      [[ -n "$obj_key" ]] && fail "object texture entry for $obj_key in ${model_file#$ROOT/} is 26.1+ syntax; 1.21.1 needs a plain string"
    done < <(jq -r '.textures // {} | to_entries[]? | select((.value | type) == "object") | .key' "$model_file")
  fi

  while IFS= read -r parent; do
    parent="$(strip_cr "$parent")"
    resolve_model "$ns" "$parent"
  done < <(jq -r '.parent? // empty' "$model_file")

  while IFS= read -r over_model; do
    over_model="$(strip_cr "$over_model")"
    resolve_model "$ns" "$over_model"
  done < <(jq -r '.overrides[]?.model? // empty' "$model_file")
done < <(find "$ROOT/assets" -type f -path '*/models/*.json' -print0 2>/dev/null)

echo "Checking item definition model references..."
while IFS= read -r -d '' item_file; do
  rel="${item_file#"$ROOT/assets/"}"
  ns="${rel%%/*}"
  if (( TARGET_FORMAT < 46 )); then
    warn "${item_file#$ROOT/} is a 1.21.4+ item definition; 1.21.1 ignores assets/<ns>/items/ (use models/item/<id>.json)"
  fi
  while IFS= read -r model_ref; do
    model_ref="$(strip_cr "$model_ref")"
    resolve_model "$ns" "$model_ref"
  done < <(jq -r '.. | objects | select(.type? == "minecraft:model" and (.model? | type) == "string") | .model' "$item_file")

  while IFS= read -r custom_model_data_case; do
    custom_model_data_case="$(strip_cr "$custom_model_data_case")"
    [[ "$custom_model_data_case" == 'valid' ]] || fail "minecraft:custom_model_data select cases must use a string or a non-empty list of strings in ${item_file#$ROOT/}"
  done < <(jq -r '.. | objects | select(.type? == "minecraft:select" and .property? == "minecraft:custom_model_data") | .cases[]? | if ((.when? | type) == "string") or ((.when? | type) == "array" and (.when | length > 0) and all(.when[]?; type == "string")) then "valid" else "invalid" end' "$item_file")
done < <(find "$ROOT/assets" -type f -path '*/items/*.json' -print0 2>/dev/null)

while IFS= read -r -d '' blockstate_file; do
  rel="${blockstate_file#"$ROOT/assets/"}"
  ns="${rel%%/*}"
  while IFS= read -r model_ref; do
    model_ref="$(strip_cr "$model_ref")"
    resolve_model "$ns" "$model_ref"
  done < <(jq -r '(
      .variants? // {} | .. | objects | .model? // empty
    ), (
      .multipart[]?.apply? | if type == "array" then .[]?.model? // empty else .model? // empty end
    )' "$blockstate_file")
done < <(find "$ROOT/assets" -type f -path '*/blockstates/*.json' -print0 2>/dev/null)

echo "Checking sounds.json references..."
while IFS= read -r -d '' sounds_file; do
  rel="${sounds_file#"$ROOT/assets/"}"
  ns="${rel%%/*}"
  while IFS=$'\t' read -r sound_type sound_ref; do
    sound_type="$(strip_cr "$sound_type")"
    sound_ref="$(strip_cr "$sound_ref")"
    resolve_sound "$ns" "$sound_type" "$sound_ref" "$sounds_file"
  done < <(jq -r '.. | objects | select(has("sounds")) | .sounds[]? | if type == "string" then ["file", .] else [(.type // "file"), (.name // empty)] end | @tsv' "$sounds_file")
done < <(find "$ROOT/assets" -type f -name 'sounds.json' -print0 2>/dev/null)

echo "Checking font provider file references..."
while IFS= read -r -d '' font_file; do
  rel="${font_file#"$ROOT/assets/"}"
  ns="${rel%%/*}"
  while IFS= read -r font_ref; do
    font_ref="$(strip_cr "$font_ref")"
    [[ -z "$font_ref" ]] && continue
    if [[ "$font_ref" == *:* ]]; then
      target_ns="${font_ref%%:*}"
      target_path="${font_ref#*:}"
    else
      target_ns="minecraft"
      target_path="$font_ref"
    fi

    target="$ROOT/assets/$target_ns/textures/$target_path"
    if [[ -f "$target" ]]; then
      pass "font texture exists: $target_ns:$target_path"
    elif [[ "$target_ns" == "minecraft" && "$ns" != "minecraft" ]]; then
      :  # assumed vanilla font texture
    elif [[ "$target_ns" != "$ns" ]]; then
      warn "external font texture not locally verifiable: $target_ns:$target_path"
    else
      fail "missing font texture: $target_ns:$target_path (expected ${target#$ROOT/})"
    fi
  done < <(jq -r '.providers[]? | .file? // empty' "$font_file")
done < <(find "$ROOT/assets" -type f -path '*/font/*.json' -print0 2>/dev/null)

echo "Checking .png.mcmeta pairs..."
while IFS= read -r -d '' mcmeta_file; do
  check_json "$mcmeta_file"
  png_file="${mcmeta_file%.mcmeta}"
  if [[ -f "$png_file" ]]; then
    pass "animation pair exists: ${mcmeta_file#$ROOT/}"
  else
    fail "orphan .png.mcmeta: ${mcmeta_file#$ROOT/} (missing ${png_file#$ROOT/})"
  fi
done < <(find "$ROOT/assets" -type f -name '*.png.mcmeta' -print0 2>/dev/null)

echo ""
if [[ "$FAILURES" -gt 0 ]]; then
  echo "$FAIL resource-pack validation failed with $FAILURES error(s) and $WARNINGS warning(s)"
  exit 1
fi

if [[ "$STRICT" -eq 1 && "$WARNINGS" -gt 0 ]]; then
  echo "$FAIL resource-pack validation strict mode failed on $WARNINGS warning(s)"
  exit 1
fi

echo "$PASS resource-pack validation passed with $WARNINGS warning(s)"
