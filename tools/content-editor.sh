#!/usr/bin/env bash
# Local content editor (P1.5): review the provenance ledger and replace AI-generated
# lang strings, textures, models and sounds in a content/<date>-<slug> worktree.
#
#   tools/content-editor.sh [--port N] [--lan] [--worktree PATH] [--reviewer NAME]
#   tools/content-editor.sh --test        run its unit tests (tools/tests/content_editor/)
#
# Binds 127.0.0.1:8765 by default (port: --port or COUNTERSTRAIN_EDITOR_PORT); from a
# PC, tunnel with `ssh -L 8765:127.0.0.1:8765 foundry`. --lan binds every interface
# and needs the printed token on every request. Standard library Python only.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
require python3 git
if [[ "${1:-}" == "--test" ]]; then
  exec python3 -I -m unittest discover -s "$repo_root/tools/tests/content_editor" \
    -t "$repo_root/tools/tests/content_editor" "${@:2}"
fi
exec python3 -I "$repo_root/tools/content-editor/server.py" "$@"
