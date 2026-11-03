# Windows x86_64 build of vesu_nrd.dll, cross-compiled with mingw-w64 (see build.sh).
#
# NRD's own CMake is not used here: in a cross build it would build ShaderMake for the
# host but look for ShaderMake.exe, and download a Windows DXC. The SPIR-V headers in
# NRD/_Shaders come from the Linux build (same NRD tag, same encodings) and do not
# depend on the OS, so this project compiles the NRD sources against them, adds NRI
# (Vulkan only) as a subproject and links the shim with everything static.
cmake_minimum_required(VERSION 3.22)
project(vesu_nrd_windows CXX C)
set(CMAKE_CXX_STANDARD 17)
# GCC false positives must not fail NRI's -Werror build (as in the Linux build).
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-error=maybe-uninitialized -Wno-error=uninitialized")

set(NRD_SDK "" CACHE PATH "NRD checkout, already built for Linux in _Build")
set(DEPS "${NRD_SDK}/_Build/_deps")

# NRI: static, Vulkan only, no validation or vendor SDKs; its dependencies come from the
# Linux build's checkouts instead of downloads.
set(FETCHCONTENT_SOURCE_DIR_VULKAN_HEADERS "${DEPS}/vulkan_headers-src" CACHE PATH "")
set(FETCHCONTENT_SOURCE_DIR_VMA "${DEPS}/vma-src" CACHE PATH "")
set(NRI_STATIC_LIBRARY ON CACHE BOOL "")
set(NRI_ENABLE_VK_SUPPORT ON CACHE BOOL "")
set(NRI_ENABLE_D3D11_SUPPORT OFF CACHE BOOL "")
set(NRI_ENABLE_D3D12_SUPPORT OFF CACHE BOOL "")
set(NRI_ENABLE_VALIDATION_SUPPORT OFF CACHE BOOL "")
set(NRI_ENABLE_NONE_SUPPORT OFF CACHE BOOL "")
set(NRI_ENABLE_NVTX_SUPPORT OFF CACHE BOOL "")
set(NRI_ENABLE_AMDAGS OFF CACHE BOOL "")
set(NRI_ENABLE_NVAPI OFF CACHE BOOL "")
add_subdirectory("${DEPS}/nri-src" nri)
# NRI.rc includes "version.h", the file is Version.h: fine on Windows, not on Linux.
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/rcfix/version.h" "#include \"${DEPS}/nri-src/Resources/Version.h\"\n")
target_include_directories(NRI PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/rcfix")

add_library(ShaderMakeBlob STATIC "${DEPS}/shadermake-src/ShaderMake/ShaderBlob.cpp")
target_include_directories(ShaderMakeBlob PUBLIC "${DEPS}/shadermake-src")

# The defines of the Linux build (NRD's CMake options at their defaults).
add_library(NRD STATIC
    "${NRD_SDK}/Source/InstanceImpl.cpp" "${NRD_SDK}/Source/Reblur.cpp" "${NRD_SDK}/Source/Reference.cpp"
    "${NRD_SDK}/Source/Relax.cpp" "${NRD_SDK}/Source/Sigma.cpp" "${NRD_SDK}/Source/Timer.cpp"
    "${NRD_SDK}/Source/Wrapper.cpp")
target_include_directories(NRD PUBLIC "${NRD_SDK}/Include"
    PRIVATE "${NRD_SDK}/_Shaders" "${DEPS}/mathlib-src")
target_compile_definitions(NRD PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN _CRT_SECURE_NO_WARNINGS
    NRD_EMBEDS_SPIRV_SHADERS=1 NRD_EMBEDS_DXIL_SHADERS=0 NRD_EMBEDS_DXBC_SHADERS=0
    NRD_SUPPORTS_ANTIFIREFLY=1 NRD_SUPPORTS_CHECKERBOARD=1 NRD_SUPPORTS_DISOCCLUSION_THRESHOLD_MIX=1
    NRD_SUPPORTS_HISTORY_CONFIDENCE=1 NRD_SUPPORTS_VIEWPORT_OFFSET=0 REBLUR_PERFORMANCE_MODE=0
    SPIRV_SREG_OFFSET=0 SPIRV_BREG_OFFSET=2 SPIRV_UREG_OFFSET=3 SPIRV_TREG_OFFSET=20
    PUBLIC NRD_STATIC_LIBRARY=1)
target_compile_options(NRD PRIVATE -mssse3 -Wno-missing-field-initializers
    -include "${CMAKE_CURRENT_SOURCE_DIR}/nrd_mingw_compat.h")
target_link_libraries(NRD PRIVATE ShaderMakeBlob)

add_library(vesu_nrd SHARED "${CMAKE_CURRENT_SOURCE_DIR}/../shim/vm_nrd.cpp")
target_include_directories(vesu_nrd PRIVATE "${NRD_SDK}/Include" "${NRD_SDK}/Integration" "${DEPS}/nri-src/Include")
target_link_libraries(vesu_nrd PRIVATE NRD ShaderMakeBlob NRI)
# No mingw runtime DLLs next to it: libstdc++, libgcc and winpthread go in statically.
target_link_options(vesu_nrd PRIVATE -static -s)
set_target_properties(vesu_nrd PROPERTIES PREFIX "" OUTPUT_NAME vesu_nrd)
