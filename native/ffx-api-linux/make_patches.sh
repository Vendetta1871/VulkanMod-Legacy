#!/usr/bin/env bash
#
# Regenerates ./patches/*.patch from the current content of ./src.
#
# Only needed when the port is being changed: the patches are the source of
# truth and prepare_sources.sh re-applies them to a pristine SDK copy.
#
# The diff is taken against the unmodified SDK checkout ($FFX_SDK_ROOT), with
# paths relative to ./src so that `patch -p1 -d src` applies them.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${FFX_SDK_ROOT:-$SCRIPT_DIR/ffx-sdk-1.1.4}"
SRC_DIR="$SCRIPT_DIR/src"
PATCH_DIR="$SCRIPT_DIR/patches"

mkdir -p "$PATCH_DIR"

# make_patch <patch file name> <files...>
make_patch() {
    local out="$PATCH_DIR/$1"
    shift
    : >"$out"
    local rel
    for rel in "$@"; do
        if ! diff -u --label "a/$rel" --label "b/$rel" "$SDK_ROOT/$rel" "$SRC_DIR/$rel" >>"$out"; then
            :
        fi
    done
    echo "wrote $out ($(grep -c '^@@' "$out") hunks)"
}

make_patch 0001-ffx-api-export-visibility.patch \
    ffx-api/include/ffx_api/ffx_api.h

make_patch 0002-ffx-api-vk-only-provider.patch \
    ffx-api/src/ffx_provider.cpp \
    ffx-api/src/ffx_provider.h \
    ffx-api/src/backends.h

make_patch 0003-ffx-api-msvc-only-syntax.patch \
    ffx-api/src/ffx_provider_framegeneration.cpp \
    ffx-api/src/ffx_provider_fsr2.cpp \
    ffx-api/src/ffx_provider_fsr3upscale.cpp \
    ffx-api/src/vk/ffx_provider_framegenerationswapchain_vk.cpp \
    ffx-api/src/dx12/ffx_provider_framegenerationswapchain_dx12.cpp

make_patch 0004-sdk-context-size-4-byte-wchar.patch \
    sdk/include/FidelityFX/host/ffx_types.h

make_patch 0005-sdk-ffx-message-ffx-unused.patch \
    sdk/src/shared/ffx_message.cpp

make_patch 0006-vk-backend-portability.patch \
    sdk/src/backends/vk/ffx_vk.cpp

make_patch 0007-vk-swapchain-win32-portability.patch \
    sdk/src/backends/vk/FrameInterpolationSwapchain/FrameInterpolationSwapchainVK.h \
    sdk/src/backends/vk/FrameInterpolationSwapchain/FrameInterpolationSwapchainVK_Helpers.h \
    sdk/src/backends/vk/FrameInterpolationSwapchain/FrameInterpolationSwapchainVK.cpp

make_patch 0008-fsr3upscaler-luma-history-format.patch \
    sdk/include/FidelityFX/gpu/fsr3upscaler/ffx_fsr3upscaler_callbacks_glsl.h
