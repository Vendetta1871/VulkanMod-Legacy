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
| RTXDI (direct light ReSTIR), RR preset | rtx_rtxdi_rayquery, RtxOptions::updatePathTracerPreset | pathtrace.rgen blockLight (own ReSTIR) | history 4, 2 spatial samples, permuted temporal reuse every frame; 8 initial candidates from the light grid cell (Remix: 3 from the global pool) |
| ReSTIR GI | rtx_restir_gi_rayquery | none | to port |
| Integrator (bounces, roulette, lobe probabilities) | rtx_pathtracer_integrate_* | pathtrace.rgen hitRadiance | bounce hits: SHaRC, else sun + block light NEE and a second bounce (High preset: 2 bounces); lightmap only as last resort. lobe probabilities >= 0.05 (RR preset); no roulette needed with 2 bounces |
| NEE cache | rtx_nee_cache | none | to port |
| Radiance cache | NRC (rtx.conf) / SHaRC | SHaRC | NRC needs tensor-core training; align SHaRC settings |
| Materials (blocks, LabPBR, thin opaque leaves/plants) | mod texture/BlockTextureManager, LabPBRConverter | RtMaterials, surfaceOf | default roughness 180/255, LabPBR read as Remix ((1-s)^2 perceptual, F0 0.04, metal G>=230); mod baseline _s maps bundled under 1.12 names. LabPBR normal maps (_n, DirectX Y, AO into the albedo) and thin subsurface transmission (_s blue) in the path tracer. RTX Remix Materials pack: tools/remix_materials.py converts the release zip to a 1.12 resource pack (307 block textures; the rest the pack does not have). Not yet: _sss albedo maps, height (POM), entity/item textures |
| Block lights (shapes, power) | mod light/BlockLightDefaults, LightManager | RtLights, lights.glsl | torch matches (radiance 1575, r 0.08 -> 31.7 per unit colour); other shapes not compared |
| View distance fade | rtx.viewDistance.* | rt_pt_resolve sky fade | to compare |

## Materials pack

    python3 tools/remix_materials.py RTX-Remix-Materials-<version>.zip "run/client/resourcepacks/RTX Remix Materials 1.12"

then enable it in Options > Resource Packs. The pack is CC BY 4.0 (NVIDIA); the converter keeps its
LICENSE.txt and README.txt.
