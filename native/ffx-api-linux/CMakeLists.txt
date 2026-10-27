# Linux x86_64 build of the FidelityFX SDK 1.1.4 "ffx-api" Vulkan library
# (the equivalent of the Windows amd_fidelityfx_vk.dll).
#
# Prerequisites (run once, from this directory):
#   ./prepare_sources.sh   - copies the SDK sources into ./src and applies ./patches
#   ./gen_shaders.sh       - compiles the shader permutations into ./generated
#
# Then:
#   cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
#   cmake --build build -j
#
cmake_minimum_required(VERSION 3.20)

project(amd_fidelityfx_vk LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

set(FFX_LINUX_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(FFX_SRC        ${FFX_LINUX_ROOT}/src)
set(FFX_GENERATED  ${FFX_LINUX_ROOT}/generated)

# ---------------------------------------------------------------- sanity check
if(NOT EXISTS "${FFX_SRC}/sdk/src/backends/vk/ffx_vk.cpp")
    message(FATAL_ERROR "Sources are missing. Run ./prepare_sources.sh first.")
endif()
if(NOT EXISTS "${FFX_GENERATED}/ffx_fsr3upscaler_accumulate_pass_permutations.h")
    message(FATAL_ERROR "Generated shader headers are missing. Run ./gen_shaders.sh first.")
endif()

# ---------------------------------------------------------------------- Vulkan
# System Vulkan headers (/usr/include/vulkan, 1.4.363 at the time of writing)
# are used; the SDK only requires 1.3.250+ for VK_EXT_swapchain_maintenance1.
find_package(Vulkan REQUIRED)

# -------------------------------------------------------------------- sources
set(FFX_API_SOURCES
    ${FFX_SRC}/ffx-api/src/ffx_api.cpp
    ${FFX_SRC}/ffx-api/src/backends.cpp
    ${FFX_SRC}/ffx-api/src/ffx_provider.cpp
    ${FFX_SRC}/ffx-api/src/ffx_provider_fsr2.cpp
    ${FFX_SRC}/ffx-api/src/ffx_provider_fsr3upscale.cpp
    ${FFX_SRC}/ffx-api/src/ffx_provider_framegeneration.cpp
    ${FFX_SRC}/ffx-api/src/validation.cpp
    ${FFX_SRC}/ffx-api/src/vk/ffx_provider_framegenerationswapchain_vk.cpp
)

set(FFX_COMPONENT_SOURCES
    ${FFX_SRC}/sdk/src/components/fsr2/ffx_fsr2.cpp
    ${FFX_SRC}/sdk/src/components/fsr3upscaler/ffx_fsr3upscaler.cpp
    ${FFX_SRC}/sdk/src/components/frameinterpolation/ffx_frameinterpolation.cpp
    ${FFX_SRC}/sdk/src/components/opticalflow/ffx_opticalflow.cpp
)

# sdk/src/shared (assert/message/breadcrumbs/object management)
file(GLOB FFX_SDK_SHARED_SOURCES "${FFX_SRC}/sdk/src/shared/*.cpp")

# Vulkan backend: ffx_vk.cpp + the frame interpolation swapchain
file(GLOB FFX_VK_BACKEND_SOURCES
    "${FFX_SRC}/sdk/src/backends/vk/*.cpp"
    "${FFX_SRC}/sdk/src/backends/vk/FrameInterpolationSwapchain/*.cpp")

# Backend shared code (shader blob dispatch) + the blob accessors of the
# components that are part of this library.
file(GLOB FFX_BACKEND_SHARED_SOURCES "${FFX_SRC}/sdk/src/backends/shared/*.cpp")
set(FFX_BLOB_ACCESSOR_SOURCES
    ${FFX_SRC}/sdk/src/backends/shared/blob_accessors/ffx_fsr2_shaderblobs.cpp
    ${FFX_SRC}/sdk/src/backends/shared/blob_accessors/ffx_fsr3upscaler_shaderblobs.cpp
    ${FFX_SRC}/sdk/src/backends/shared/blob_accessors/ffx_frameinterpolation_shaderblobs.cpp
    ${FFX_SRC}/sdk/src/backends/shared/blob_accessors/ffx_opticalflow_shaderblobs.cpp
)

# --------------------------------------------------------------------- target
add_library(amd_fidelityfx_vk SHARED
    ${FFX_API_SOURCES}
    ${FFX_COMPONENT_SOURCES}
    ${FFX_SDK_SHARED_SOURCES}
    ${FFX_VK_BACKEND_SOURCES}
    ${FFX_BACKEND_SHARED_SOURCES}
    ${FFX_BLOB_ACCESSOR_SOURCES}
)

target_include_directories(amd_fidelityfx_vk
    PUBLIC
        ${FFX_SRC}/ffx-api/include
    PRIVATE
        ${FFX_SRC}/sdk/include
        ${FFX_SRC}/sdk/src/shared
        ${FFX_SRC}/sdk/src/backends/shared
        ${FFX_SRC}/sdk/src/components
        ${FFX_SRC}/sdk/src/backends/vk
        ${FFX_GENERATED}
        ${FFX_LINUX_ROOT}/compat
)

target_compile_definitions(amd_fidelityfx_vk PRIVATE
    # ffx-api
    FFX_BACKEND_VK
    FFXDLL_VERSION_API=VK
    FFXDLL_VERSION_MAJOR=1
    FFXDLL_VERSION_MINOR=0
    FFXDLL_VERSION_PATCH=1
    CI_PIPELINE_ID=0
    # components compiled into this library (drives the shader blob dispatch)
    FFX_FSR2
    FFX_FSR3UPSCALER
    FFX_FI
    FFX_OF
)

# The Win32 compatibility layer is force-included so that the unmodified SDK
# sources (wcscpy_s, _countof, sprintf_s, HANDLE, ...) keep compiling.
target_compile_options(amd_fidelityfx_vk PRIVATE
    $<$<CXX_COMPILER_ID:GNU,Clang>:-include${FFX_LINUX_ROOT}/compat/ffx_linux_compat.h>
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wno-deprecated-declarations>
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wno-unused-variable>
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wno-unused-but-set-variable>
    $<$<CXX_COMPILER_ID:GNU,Clang>:-Wno-sign-compare>
)

target_link_libraries(amd_fidelityfx_vk PRIVATE Vulkan::Vulkan)

set_target_properties(amd_fidelityfx_vk PROPERTIES
    OUTPUT_NAME amd_fidelityfx_vk
    VERSION 1.1.4
    SOVERSION 1
)

include(GNUInstallDirs)
install(TARGETS amd_fidelityfx_vk LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR})
install(DIRECTORY ${FFX_SRC}/ffx-api/include/ DESTINATION ${CMAKE_INSTALL_INCLUDEDIR})
