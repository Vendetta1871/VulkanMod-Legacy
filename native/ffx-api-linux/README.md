# ffx-api for Linux

Builds `libamd_fidelityfx_vk.so`, the Linux x86_64 counterpart of AMD's
`amd_fidelityfx_vk.dll` from FidelityFX SDK 1.1.4 (MIT): the ffx-api entry
points, the Vulkan backend, the FSR 3.1 upscaler, frame interpolation,
optical flow and the frame-interpolation swapchain.

## Build

Needs `git`, `cmake`, a C++17 compiler, Vulkan headers and `wine` (the SDK's
shader compiler `FidelityFX_SC.exe` is a Windows tool; it runs fine under wine).

```sh
./fetch_sdk.sh          # clones the SDK 1.1.4 into ./ffx-sdk-1.1.4
./prepare_sources.sh    # copies the needed sources into ./src and applies ./patches
./gen_shaders.sh        # SPIR-V permutation headers into ./generated (wine, a few minutes)
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
```

`FFX_SDK_ROOT` points the scripts at an existing SDK checkout instead.
`tests/smoke_test.cpp` creates and destroys the upscaler and frame-generation
contexts on a headless device.

To bundle the library into the mod jar:

```sh
mkdir -p natives/linux-x64 && cp -L build/libamd_fidelityfx_vk.so natives/linux-x64/
../../gradlew -p ../.. build -PffxNatives=$PWD/natives
```

The jar extracts it to `config/vesu/natives/` at runtime;
`VESU_FFX_LIB=<path>` overrides the location during development.
## Windows

`./build_windows.sh <natives-dir>` cross-builds `amd_fidelityfx_vk.dll` with
mingw-w64 (Arch: `mingw-w64-gcc`) from the same `./src` and `./generated` and
copies it to `<natives-dir>/windows-x64/`. AMD's prebuilt DLL in the SDK
(`PrebuiltSignedDLL/`) is not used: it keeps `FFX_MAX_QUEUED_FRAMES` at 4 and
lacks the luma history fix (patches 0004 and 0008). The toolchain file and the
import library source for `vulkan-1.dll` live in `../windows/`;
`compat/ffx_mingw_compat.h` adds the includes MSVC gets through `<windows.h>`.
The DLL depends only on `KERNEL32`, the UCRT and `vulkan-1.dll`.

Checked under wine (winevulkan): the FSR 3.1 upscaler creates its context and
dispatches with the expected output. Frame generation on Windows (the
interpolation swapchain) was not run: it needs a window and a real present.

## Patches

The SDK checkout is never modified; `prepare_sources.sh` applies these to the
copy in `./src`, and `make_patches.sh` regenerates them after editing `./src`.
`compat/ffx_linux_compat.h` is force-included and provides the Win32 pieces
(events, threads, critical sections, QPC, `*_s` CRT functions) on top of the
C++ standard library.

| Patch | Why |
|---|---|
| 0001 export-visibility | export the ffx-api entry points from the shared object |
| 0002 vk-only-provider | build the providers without the DX12 backend |
| 0003 msvc-only-syntax | MSVC `ui64` literal suffixes -> `ULL` |
| 0004 context-size, queued frames | 4-byte `wchar_t` in context size math; `FFX_MAX_QUEUED_FRAMES` 4 -> 8 (views live that many `UnregisterResources` calls, and the mod runs two dispatches per frame with up to three frames in flight) |
| 0005 ffx-message | missing include for `FFX_UNUSED` |
| 0006 vk-backend-portability | `windows.h`/wide-string APIs; scratch sub-arrays aligned to 32 bytes (GCC emits aligned SSE stores into them, the SDK aligned to 4) |
| 0007 vk-swapchain-portability | `CreateThread`/events/`WaitForSingleObject`/QPC in the FG swapchain; SDK bugs: the per-image present semaphores overwrote the ones created just before (3 leaked per swapchain creation); destroying the swapchain waited its queues idle while the presenter thread could still present (now: drain, stop the thread, then wait) |
| 0008 luma-history-format | SDK bug: `rw_luma_history` declared `rgba8` but the image is RGBA16F |

Known SDK quirk handled on the mod side: the auto-reactive output is declared
`r32f`, so the mod creates its reactive mask as R32F.
