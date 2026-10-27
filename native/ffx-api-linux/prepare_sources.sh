#!/usr/bin/env bash
#
# Prepares the source tree used by the build:
#   1. copies the required (unmodified) FidelityFX SDK sources into ./src
#   2. applies every patch from ./patches (sorted by name) to ./src
#
# The SDK checkout itself is never modified.
#
# Usage: ./prepare_sources.sh [-f]
#        -f   wipe ./src first (full re-copy)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${FFX_SDK_ROOT:-$SCRIPT_DIR/ffx-sdk-1.1.4}"
SRC_DIR="$SCRIPT_DIR/src"
PATCH_DIR="$SCRIPT_DIR/patches"

[ -d "$SDK_ROOT/sdk" ] || { echo "error: $SDK_ROOT/sdk not found" >&2; exit 1; }

if [ "${1:-}" = "-f" ]; then
    rm -rf "$SRC_DIR"
fi

# --- 1. copy the source subsets the library needs -------------------------
copy() {
    local rel="$1"
    mkdir -p "$SRC_DIR/$(dirname "$rel")"
    rm -rf "$SRC_DIR/$rel"
    cp -a "$SDK_ROOT/$rel" "$SRC_DIR/$rel"
}

copy ffx-api/include
copy ffx-api/src
copy sdk/include/FidelityFX
copy sdk/src/shared
copy sdk/src/backends/shared
copy sdk/src/backends/vk
copy sdk/src/components/fsr2
copy sdk/src/components/fsr3upscaler
copy sdk/src/components/frameinterpolation
copy sdk/src/components/opticalflow

# --- 2. apply patches -----------------------------------------------------
shopt -s nullglob
patches=("$PATCH_DIR"/*.patch)
shopt -u nullglob

if [ ${#patches[@]} -eq 0 ]; then
    echo "no patches found in $PATCH_DIR"
else
    for p in "${patches[@]}"; do
        echo "applying $(basename "$p")"
        patch -p1 -d "$SRC_DIR" --no-backup-if-mismatch <"$p"
    done
fi

echo "sources ready in $SRC_DIR"
