# NRD for VulkanMod (Linux)

Builds `libvulkanmod_nrd.so`: NVIDIA's NRD denoiser (with its NRI integration layer)
behind a small C API (`shim/vm_nrd.cpp`) that the RT lighting pass calls
(`net.vulkanmod.vulkan.NrdDenoiser`).

```
native/nrd-linux/build.sh /storage/Projects/nrd-sdk <natives-dir>
./gradlew build -PffxNatives=<natives-dir>
```

The script clones NRD (tag in `build.sh`); NRD's own CMake fetches NRI, MathLib,
ShaderMake and DXC and compiles the denoiser shaders to SPIR-V. Nothing from the SDK is
committed here: NRD is under the NVIDIA RTX SDKs license, which allows distributing it
only as object code within an application. The built library is shipped in the jar
together with `NVIDIA-RTX-SDKs-LICENSE.txt`. Without the library the RT passes fall back
to their own temporal and spatial filter.
