#!/usr/bin/env bash
# Clones FidelityFX SDK 1.1.4 (MIT) next to this script.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -d "$SCRIPT_DIR/ffx-sdk-1.1.4" ] && { echo "already present"; exit 0; }
git clone --depth 1 --branch v1.1.4 \
    https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK.git "$SCRIPT_DIR/ffx-sdk-1.1.4"
