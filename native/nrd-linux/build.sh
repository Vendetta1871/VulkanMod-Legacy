#!/bin/bash
# Usage: build.sh <sdk-dir> <natives-dir>
# Fetches NRD (with NRI) into <sdk-dir>, builds it and the VulkanMod shim, and puts
# libvulkanmod_nrd.so into <natives-dir>/linux-x64 (pass that dir to gradle as -PffxNatives).
set -euo pipefail
NRD_TAG=v4.17.3
SDK=$(realpath -m "${1:?sdk dir}")
OUT=$(realpath -m "${2:?natives dir}")
HERE=$(cd "$(dirname "$0")" && pwd)

if [ ! -d "$SDK/NRD" ]; then
    git clone --depth 1 --branch "$NRD_TAG" --recurse-submodules --shallow-submodules \
        https://github.com/NVIDIA-RTX/NRD.git "$SDK/NRD"
fi

# NRD + NRI, static. RGBA8 normals (n * 0.5 + 0.5) and linear roughness: what the RT
# lighting pass writes. GCC false positives must not fail NRI's -Werror build.
cmake -S "$SDK/NRD" -B "$SDK/NRD/_Build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DNRD_NRI=ON -DNRD_STATIC_LIBRARY=ON -DNRI_STATIC_LIBRARY=ON \
    -DNRI_ENABLE_VALIDATION_SUPPORT=OFF -DNRI_ENABLE_WAYLAND_SUPPORT=OFF -DNRI_ENABLE_XLIB_SUPPORT=OFF \
    -DNRD_NORMAL_ENCODING=0 -DNRD_ROUGHNESS_ENCODING=1 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_CXX_FLAGS="-Wno-error=maybe-uninitialized -Wno-error=uninitialized"
cmake --build "$SDK/NRD/_Build"

cmake -S "$HERE/shim" -B "$SDK/shim-build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DNRD_SDK="$SDK/NRD"
cmake --build "$SDK/shim-build"

mkdir -p "$OUT/linux-x64"
cp "$SDK/shim-build/libvulkanmod_nrd.so" "$OUT/linux-x64/"
cp "$SDK/NRD/LICENSE.txt" "$OUT/linux-x64/NVIDIA-RTX-SDKs-LICENSE.txt"
echo "built $OUT/linux-x64/libvulkanmod_nrd.so"
