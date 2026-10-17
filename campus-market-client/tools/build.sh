#!/bin/sh
set -eu
TOOLS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
NODE_BIN=${NODE_BIN:-/Applications/HBuilderX.app/Contents/HBuilderX/plugins/node/node}
if [ ! -x "$NODE_BIN" ]; then NODE_BIN=$(command -v node); fi
exec "$NODE_BIN" "$TOOLS_DIR/build.mjs" "${1:-all}"
