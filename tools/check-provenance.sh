#!/usr/bin/env bash
# Fail if an image, sound, model or lang key lacks a row in docs/CONTENT-PROVENANCE.md, or
# a texture's marker or a lang value disagrees with its row's status (content-provenance skill).
# Usage: tools/check-provenance.sh [--verbose]
#        tools/check-provenance.sh --self-test   # fixtures in tools/tests/provenance/
#        tools/check-provenance.sh --help
# --root DIR and --ledger FILE check another tree or ledger. Exit: 0 clean,
# 1 at least one failure, 2 unknown argument.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
if [[ "${1:-}" == -h || "${1:-}" == --help ]]; then
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 0
fi
require python3 git
exec python3 -I "$repo_root/tools/check-provenance.py" "$@"
