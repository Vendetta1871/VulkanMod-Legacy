# How Bedrock's Vibrant Visuals water works

Status: 2026-11-04. Reverse-engineered from Bedrock 1.26.52.3 (Android, `versions/26.52.03`):
the compiled materials in `assets/renderer/materials/*.material.bin` (they embed their GLSL 3.10 ES
source in plain text), `renderer/caustics.png`, `resource_packs/vanilla_1.21.120/__brarchive/water.brarchive`
and strings in `libminecraftpe.so`. Every formula below is quoted from that source.

**The headline: the water surface is flat.** Bedrock never displaces water geometry. There is no
Gerstner vertex animation, no tessellation and no refraction distortion of what lies under the
surface. "Water waves" is an optional *fragment-shader normal perturbation*: the vanilla preset
data ships it off, yet the shipped game evaluates the wave normal anyway at default VV quality, so
in practice the surface is a flat sheet that shimmers with fine, pixel-sized ripples — sky and sun
specular on top, the undistorted scene through it, tinted by depth absorption.

## Data: `water.json`

`water.brarchive` is a trivial container (magic + name table + raw bytes) holding one file, the
vanilla preset:

```json
{ "format_version": "1.21.120",
  "minecraft:water_settings": {
    "description": { "identifier": "minecraft:default_water" },
    "particle_concentrations": { "chlorophyll": 0.0, "suspended_sediment": 0.0, "cdom": 0.0 },
    "caustics": { "enabled": true, "frame_length": 0.09, "scale": 0.5, "power": 1 },
    "waves":    { "enabled": false, "frequency": 1, "octaves": 28, "depth": 1, "speed": 2,
                  "shape": 1.5, "pull": 0.38, "mix": 0.2,
                  "frequency_scaling": 1.2, "speed_scaling": 1.03 } } }
```

`waves.enabled` is **false** in the data, but the shipped game turns wave normals on anyway at
default VV quality: the reference instance shows fine, pixel-sized ripples over every surface.
There is no `gfx_*` video-settings key for it in this build; waves are driven by this data, by the
per-biome override and by the scripting API (`ScriptModuleServerGraphics::ScriptBiomeWater::setWavesDepth/Shape/Speed/Pull/Mix/Octaves`),
plus editor sliders (`editor.agfx.water.waves.*`). A preset is bound to biomes through
`minecraft:water_identifier`; `minecraft:water_appearance` supplies `surface_color` /
`surface_opacity`, and `biomes_client.json` still carries the classic `water_surface_color` /
`water_surface_transparency`.

The settings become per-frame uniforms and a per-biome storage buffer (`zBiomeInfoBuffer`):

| JSON field                              | Uniform / buffer field                                   |
|-----------------------------------------|----------------------------------------------------------|
| `waves.frequency`, `octaves`, `depth`   | `WaterSurfaceParameters` = (frequency, octaves, depth)    |
| `waves.speed`, `shape`                  | `WaterSurfaceWaveParameters` = (speed, shape)             |
| `waves.pull`, `mix`, `frequency_scaling`, `speed_scaling` | `WaterSurfaceOctaveParameters` = (pull, mix, frequency_scaling, speed_scaling) |
| waves on/off, biome `water_extinction_distance_shift` | `WaterSurfaceEnabledAndExtinctionDistShift` = (enabled, shift, …) |
| `particle_concentrations` (per biome)   | `BiomeInfo.waterExtinctionCoefficients`, `waterAlbedoExtinction` |
| `caustics.scale`, `power`, frame time   | `CausticsParameters` = (enabled, scale, power, …)         |

Biome values are bilinearly blended across a `s_BiomeBlendingMap` index texture when
`BiomeBlendingParameters.x > 0`, so extinction and wave parameters fade between biomes.

## Passes

Five materials are involved. Order per frame:

1. **`WaterPrepass`** — water quads are drawn into the G-buffer (this is a deferred renderer).
   The vertex shader is a plain model transform: **no displacement**. The fragment shader decodes
   the block's material/normal texture, then, if `WaterSurfaceEnabledAndExtinctionDistShift.x > 0`,
   replaces the normal with the analytic wave normal (next section). The result is written as an
   octahedral normal plus motion vectors into the prepass targets; water depth goes to its own
   depth buffer (`s_WaterDepth`). The reference screenshots show the wave pattern as visible
   squares, so this evaluation runs at reduced resolution (the renderer's other passes carry
   "MixedResolution" in their names for exactly this reason); the shading pass then reads those
   coarse normals per pixel.
2. **`WaterExtinction`** — fullscreen pass. For every pixel it unprojects both the scene depth and
   the water depth and writes the Beer-Lambert absorption of the water column *behind* the surface:
   `exp(-coeff.rgb * (min(sceneViewDist, fogFar) - min(waterViewDist, fogFar) + shift))`.
   The air segment from the camera to the surface is deliberately not absorbed. Downstream passes
   multiply this into the scene color, which is what makes deep water fade to the biome color.
3. **`DeferredMixedResolutionCaustics`** — fullscreen (mixed resolution) pass. It reconstructs the
   world position from scene depth, decodes the G-buffer normal and samples the caustics texture
   array at `worldXZ * caustics.scale` and the current animation frame:
   `pow(2 * texel.r * clamp(N.y, 0, 1), power) * (power + 1)`. The texture is
   `renderer/caustics.png`, a 32x2048 stack = 64 frames of 32x32, advanced every `frame_length`
   (0.09 s) seconds. The output modulates scene lighting on submerged floors — caustics belong to
   the *floor*, not to the surface. `DeferredShading` carries the same snippet inline and
   multiplies the directional light source by it (`light *= modulation`), leaving ambient alone.
4. **`DeferredWater`** — the deferred lighting pass for water pixels (a `DeferredShadingBase`
   variant). It reads the G-buffer normal written in step 1 and shades it like any other surface:
   GGX sun specular (roughness 0.08), sky ambient, shadow cascades, point lights, volumetrics.
   The water-specific part is only the output alpha, a Schlick Fresnel:
   `alpha = 0.02 + 0.98 * (1 - dot(N, V))^5`, with `dot(N, V` replaced by the refracted view
   direction when the camera is underwater (`1.7689 * (1 - NdotV^2)` clamped into a sqrt).
   The pass then alpha-blends over the already-rendered opaque scene: looking down the surface is
   ~2% opaque (you see the floor, extinction-tinted), at grazing angles ~100% (you see the sky
   reflection). **The scene behind the water is sampled at the pixel's own screen position — there
   is no refraction offset.**
5. **`WaterSimulation`** — a separate screen-space dynamic ripple system: it initializes a Phillips
   wind spectrum (`WaterWindVelocity`, `WaterWaveDampening`, gravity `G`) and advances it with a
   Stockham FFT ping-pong. This is what adds live ripples under rain and moving perturbations;
   calm default water leaves it at rest. It is not part of the analytic wave normals above.

## The wave normal, verbatim

From `WaterPrepass` (decompiled names normalized; `time = ViewPositionAndTime.w * 0.5`,
`p = (worldPos - worldOrigin).xz`):

```glsl
vec2 slope = vec2(0.0);
float weight = 1.0, total = 0.0, angle = 0.0;
for (int i = 0; i < int(params.x /* octaves */); i++) {
    vec2 dir = vec2(sin(angle), cos(angle));
    float phase = dot(dir, p) * freq + time * speed;
    float c = cos(phase), s = sin(phase);
    slope += -dir * depth * weight * freq * shape * c * pow(0.5 * (s + 1.0), shape) / (s + 1.0001);
    total += weight;
    p     += -dir * weight * pow(0.5 * (s + 1.0), shape) * c * pull;   // domain warp
    weight = mix(weight, 0.0, mix);                                     // weight *= 1 - mix
    freq  *= frequency_scaling;
    speed *= speed_scaling;
    angle += 1.399;                                                     // ~80.16 degrees
}
slope /= total;
normal = normalize(mix(geomNormal, normalize(vec3(slope.x, 1.0, slope.y)), geomNormal.y));
```

Notes:

- It is a sum of directional sine octaves (a "sum of sines" height field) differentiated
  analytically — the accumulated `slope` is the gradient of the implied surface, so the perturbation
  is *normal-only*. The `pull` term warps the sample position per octave, which is what makes the
  pattern look like water instead of stripes.
- `shape` biases each octave toward sharp crests (`pow(0.5*(s+1), shape)` weights the slope by the
  wave height itself).
- The final `mix(..., geomNormal.y)` means the perturbation only applies to horizontal surfaces:
  water sides (the vertical quads of a waterfall edge, for instance) keep their plain normal.
- With `waves.enabled = false` the loop never runs and the surface normal is the geometric one:
  flat. The shipped game nevertheless evaluates it (see the note under `WaterPrepass`), and because
  that evaluation happens on a coarse grid the ripples read as pixels rather than ripples.

## What this means for our preset

- `vibrant_visuals/water/water.json` enables waves with the vanilla parameter block, because that
  is what the shipped game displays; `vv_waveSlope` in `BedrockPrograms.WATER` is a faithful port
  of the loop above. To reproduce the coarse evaluation each pixel quantizes its sample position
  to a screen-sized cell grid and averages the slope over a 3x3 stencil, and the resulting tilt is
  clamped - the raw loop nearly diverges at `s = -1` and would read as broken glass.
- The surface look to reproduce is: flat plane, Fresnel alpha blend `0.02 + 0.98(1-cos)^5` over the
  *undistorted* scene, Beer-Lambert extinction of the water column, sun specular at roughness 0.08,
  sky reflection by IBL, caustics on submerged floors.
- Caustics (`vv_causticModulation`, shared by `water.fsh` and `lighting.fsh`): the original
  multiplies only the floor's *directional* light, which dominates its shading there. Our floors are
  ambient-heavy, so multiplying the sun term alone is invisible and multiplying the raw modulation
  of the whole color crushes the web gaps to black; we therefore modulate the whole floor color
  with the strength blended toward one (`mix(1, m, 0.45 * N.y)`), which keeps vertical faces
  untouched. The strip is sampled with nearest filtering at one texel per block (the data scale is
  normalized, hence the doubling in `waterDefines`) and in absolute world XZ, so the pattern stays
  pinned to the world. Two mutually exclusive applications mirror the original's two passes: the
  water surface pass modulates the scene behind the surface while the camera is above water, and
  the deferred lighting pass modulates the floor itself while `isEyeInWater` - from under water no
  surface pixel exists to carry the caustics.
- The old refraction "bend" of the backbuffer had no counterpart in the original and produced a
  doubled image under the surface; it is gone, the scene is sampled at the pixel's own position.
  The SSR march over the sky reflection stays: Bedrock approximates the same information with its
  IBL records, so terrain reflections in the surface are not a deviation.
