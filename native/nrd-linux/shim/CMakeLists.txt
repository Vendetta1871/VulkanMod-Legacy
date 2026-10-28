cmake_minimum_required(VERSION 3.22)
project(vulkanmod_nrd CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

set(NRD_SDK "" CACHE PATH "NRD checkout, built in _Build")
set(NRI_SRC "${NRD_SDK}/_Build/_deps/nri-src")

add_library(vulkanmod_nrd SHARED vm_nrd.cpp)
target_include_directories(vulkanmod_nrd PRIVATE
    "${NRD_SDK}/Include" "${NRD_SDK}/Integration" "${NRI_SRC}/Include")
target_link_directories(vulkanmod_nrd PRIVATE "${NRD_SDK}/_Bin" "${NRD_SDK}/_Build/_deps/nri-build"
    "${NRD_SDK}/_Build/_deps/shadermake-build")
# NRI front end and its Vulkan back end, NRD with embedded SPIR-V.
target_link_libraries(vulkanmod_nrd PRIVATE
    -Wl,--start-group NRD ShaderMakeBlob NRI NRI_VK NRI_Shared -Wl,--end-group dl pthread)
target_link_options(vulkanmod_nrd PRIVATE -static-libstdc++ -static-libgcc -Wl,--no-undefined)
