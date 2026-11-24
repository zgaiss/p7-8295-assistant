#!/bin/sh
# Build only: no adb or device operation.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$HERE/build.py" "$@"
