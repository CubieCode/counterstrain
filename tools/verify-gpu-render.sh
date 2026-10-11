#!/usr/bin/env bash
# Check a client log for the OpenGL renderer and fail unless it is the GPU.
#
#   tools/verify-gpu-render.sh <log> [expected-renderer-pattern]
#
# Default pattern: "RTX 4070" (override with EXPECTED_RENDERER). llvmpipe,
# softpipe or a missing renderer line all fail.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

log_file="${1:?Usage: verify-gpu-render.sh LOG [PATTERN]}"
expected="${2:-${EXPECTED_RENDERER:-RTX 4070}}"
[[ -f "$log_file" ]] || die "log not found: $log_file"

# NeoForge's early display logs "GL info: <renderer> GL version ...";
# vanilla's debug screen data uses "GL_RENDERER". Accept either.
renderer_line="$(grep -a -m1 -E 'GL info: |GL_RENDERER|OpenGL renderer' "$log_file" || true)"
if [[ -z "$renderer_line" ]]; then
  die "no OpenGL renderer line in $log_file (the client may not have created a window)"
fi
renderer="$(sed -E 's/.*(GL info: |GL_RENDERER[:=] ?|OpenGL renderer[:=] ?)//' <<<"$renderer_line")"
echo "renderer: $renderer"
if grep -q -i -E 'llvmpipe|softpipe|software rasterizer|swrast' <<<"$renderer"; then
  die "software rendering detected; the client did not run through vglrun (see headless-client skill)"
fi
if ! grep -q -i -F "$expected" <<<"$renderer"; then
  die "renderer does not match '$expected'"
fi
echo "ok: GPU rendering via '$expected'"
