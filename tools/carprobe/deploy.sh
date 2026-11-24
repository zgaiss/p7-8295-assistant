#!/bin/sh
# Explicit entrypoint: no build, connect, start, forward or watcher side effects.
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$SCRIPT_DIR/deploy.py" "$@"
