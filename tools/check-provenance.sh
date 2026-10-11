#!/usr/bin/env bash
# Fail if an image, sound or model lacks a row in docs/CONTENT-PROVENANCE.md, or
# a texture's placeholder marker disagrees with its row (content-provenance skill).
# Usage: tools/check-provenance.sh [--verbose]
#        tools/check-provenance.sh --self-test   # fixtures in tools/tests/provenance/
set -euo pipefail
source "$(dirname "$0")/lib.sh"
require python3 git
exec python3 -I "$repo_root/tools/check-provenance.py" "$@"
