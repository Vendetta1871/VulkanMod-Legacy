# Porting RTX Remix's renderer into Vesu

Sources (cloned to /tmp/remix on the host; re-clone if gone):
- https://github.com/NVIDIAGameWorks/rtx-remix-minecraft — the 1.21.1 Fabric mod. It only captures
  the scene (terrain, entities, particles, sky, lights, materials) and hands it to the Remix runtime
  through the Remix API. `packaging/rtx.conf` holds every runtime setting it ships;
  `forge-1.12.2/` is NVIDIA's unfinished 1.12.2 bootstrap (runtime init only, no scene capture).
- https://github.com/NVIDIAGameWorks/dxvk-remix — the runtime that renders (Windows build, Vulkan
  inside): path tracer, RTXDI, ReSTIR GI, NEE cache, NRC/SHaRC, DLSS-RR/NRD, post chain.

Decision (2026-11-16): port Remix's algorithms and the mod's settings into Vesu's own Vulkan
renderer (native Linux, our RR2 runtime), not run the Remix runtime under Wine.

## Status

| Remix component | Remix source | Vesu | Status |
|---|---|---|---|
| Bloom | rtx_bloom.cpp, pass/bloom | PostProcess, rx_bloom_*.comp | ported (steps 8, burn 0.1, threshold 250) |
| Auto-exposure | rtx_auto_exposure.cpp, pass/tonemap/auto_exposure* | rx_histogram/rx_exposure.comp | ported (mean, speed 10, center 0.75, EV -2.5..1.5) |
| Local tonemapper + ACES | rtx_local_tone_mapping.cpp, pass/local_tonemap | rx_lt_*.comp | ported (exposure 0.214, shadows 1.4, highlights 2.5, sigma 3.5, offset -0.1) |
| Distant light (sun/moon) | concept/light/distant_light.slangh | RtLighting.directLight | irradiance = pi x mod radiance |
| Sky dome | mod sky/AtmosphericSkyGenerator | rt/atmosphere.glsl | same scattering scale (16 day / 70 night); dome algorithm not yet compared |
| RR guide preparation | pass/ray_reconstruction/prepare_ray_reconstruction | pathtrace.rgen storeGuides | roughness demodulation of specular albedo, black albedo cleared; no specular hit distance (RR2 port has no input for it) |
| RTXDI (direct light ReSTIR), RR preset | rtx_rtxdi_rayquery, RtxOptions::updatePathTracerPreset | pathtrace.rgen blockLight (own ReSTIR) | to port: initial 3, spatial 2, permutation every frame |
| ReSTIR GI | rtx_restir_gi_rayquery | none | to port |
| Integrator (bounces, roulette, lobe probabilities) | rtx_pathtracer_integrate_* | pathtrace.rgen (1 bounce + SHaRC) | to port |
| NEE cache | rtx_nee_cache | none | to port |
| Radiance cache | NRC (rtx.conf) / SHaRC | SHaRC | NRC needs tensor-core training; align SHaRC settings |
| Materials (blocks, LabPBR, thin opaque leaves/plants) | mod block/, texture/, asset/ | RtMaterials | to compare |
| Block lights (shapes, power) | mod light/ | RtLights | to compare |
| View distance fade | rtx.viewDistance.* | rt_pt_resolve sky fade | to compare |
