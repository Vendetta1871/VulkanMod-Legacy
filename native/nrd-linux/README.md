# NRD for Vesu (Linux)

Builds `libvesu_nrd.so`: NVIDIA's NRD denoiser (with its NRI integration layer)
behind a small C API (`shim/vm_nrd.cpp`) that the RT lighting pass calls
(`net.vesu.vulkan.NrdDenoiser`).

```
native/nrd-linux/build.sh native/nrd-linux/nrd-sdk <natives-dir>
./gradlew build -PffxNatives=<natives-dir>
```

The script clones NRD (tag in `build.sh`); NRD's own CMake fetches NRI, MathLib,
ShaderMake and DXC and compiles the denoiser shaders to SPIR-V. Nothing from the SDK is
committed here: NRD is under the NVIDIA RTX SDKs license, which allows distributing it
only as object code within an application. The built library is shipped in the jar
together with `NVIDIA-RTX-SDKs-LICENSE.txt`. With mingw-w64 installed, `build.sh` also
cross-builds `vesu_nrd.dll` for Windows (`windows/CMakeLists.txt`): NRD's own CMake cannot
cross-build its shaders, so that project compiles NRD against the SPIR-V headers of the
Linux build in `NRD/_Shaders`. Checked under wine on the V100: NRI comes up on Vulkan 1.3
and both integrations are created and destroyed. Without the library the RT passes fall back
to their own temporal and spatial filter.
