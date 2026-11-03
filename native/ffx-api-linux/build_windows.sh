#!/usr/bin/env bash
#
# Cross-builds amd_fidelityfx_vk.dll (Windows x86_64) with mingw-w64 from the same
# patched sources and shader headers as the Linux library (run ./prepare_sources.sh
# and ./gen_shaders.sh first). AMD's prebuilt DLL is not used: it keeps
# FFX_MAX_QUEUED_FRAMES at 4 and lacks the luma history fix (patches 0004, 0008).
#
# Usage: ./build_windows.sh [natives-dir]   (copies the DLL to <natives-dir>/windows-x64/)
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
B=build-win
WIN=../windows
mkdir -p "$B/include"
# Only the Vulkan headers of the host: the rest of /usr/include must stay out of a cross build.
ln -sfn /usr/include/vulkan "$B/include/vulkan"
ln -sfn /usr/include/vk_video "$B/include/vk_video"
# The SDK includes <Windows.h>; mingw ships it lower case.
printf '#include <windows.h>\n' > "$B/include/Windows.h"
x86_64-w64-mingw32-dlltool -d "$WIN/vulkan-1.def" -l "$B/libvulkan-1.a"
cmake -S . -B "$B" -DCMAKE_TOOLCHAIN_FILE="$PWD/$WIN/mingw-w64.cmake" -DCMAKE_BUILD_TYPE=Release \
    -DVESU_VULKAN_INCLUDE="$PWD/$B/include" -DVESU_VULKAN_IMPORT_LIB="$PWD/$B/libvulkan-1.a"
cmake --build "$B" -j"$(nproc)"
if [ -n "${1:-}" ]; then
    mkdir -p "$1/windows-x64"
    cp "$B/amd_fidelityfx_vk.dll" "$1/windows-x64/"
fi
