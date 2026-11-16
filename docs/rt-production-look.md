# Production Look Notes: NVIDIA RTX Remix for Minecraft vs. our RT pass

Research on [NVIDIAGameWorks/rtx-remix-minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft)
(Apache-2.0, Fabric, MC 1.21.1, path tracing through the RTX Remix runtime / dxvk-remix).
Goal: understand what makes its picture look "production" rather than "tech demo", and
how to reproduce that in our hybrid RT pass (`src/main/resources/assets/vesu/shaders/rt/`,
`src/main/java/net/vesu/vulkan/rt/`).

The mod's own docs that matter most: `docs/RESOURCE_PACKS.md` (material/light semantics),
`docs/TUNING.md`, `packaging/rtx.conf` (renderer baseline), and
`packaging/rtx-remix/mods/minecraft-logic/confs/` (time/weather/water looks).
Source paths below refer to that repository.

## TL;DR

The NVIDIA look is not a better path tracer. It is three things on top of one:

1. **A deliberate light rig** — tiny, very bright emitters, a sun with angular size and
   atmosphere extinction, a physically-based sky dome as the only ambient, and a big
   contrast range between light pools and shadow.
2. **Energy-consistent transport** — light through colored glass is *multiplied* by the
   transmittance, never renormalized; reflections are denoised radiance with
   hit-distance-aware blur, not 1-spp speckle.
3. **A film-style post chain** — ACES tonemap, −0.75 EV exposure bias, local contrast
   (shadows 1.4 / highlights 2.5), auto-exposure with tight EV bounds, and selective
   high-threshold bloom that only the hottest emitters trigger.

Our three visible problems map to these one-to-one:

| Symptom | Root cause in our code | Fix (section) |
|---|---|---|
| Light too soft / diffuse | Vanilla lightmap falloff + ambient floors + block-sized emitters | §2.1 |
| Reflections "crumble" into noise | 1 spp jittered reflection, lightmap-shaded, no denoiser | §2.2 |
| Colored glass shadows = bright color patches | Hue *normalized and saturated* in shadow any-hit | §2.3 |

---

## 1. What the NVIDIA mod actually is (architecture in one paragraph)

A Fabric mod that replaces the renderer: it re-meshes chunk sections itself
(`terrain/capture/`), converts the block atlas + LabPBR sidecars into PBR textures
(`texture/LabPBRConverter.java`: `_s` alpha = emission, smoothness → roughness
`(1−s)²`, `_n` blue = AO, alpha = height for 0.25-block parallax), and submits
geometry, analytic lights and a procedural sky to the RTX Remix runtime through its C
API (`rtxremix-java/`). The runtime path-traces with RTXDI (ReSTIR DI) + NEE cache for
direct light, ReSTIR GI / SHaRC / NRC for indirect, and **DLSS Ray Reconstruction as
the denoiser**, then ACES-tonemaps. Layered `rtx.conf` profiles fade looks by time of
day, weather, biome, water and map zones (`packaging/rtx-remix/mods/minecraft-logic/confs/`).

So it is a full path tracer, but the transferable parts — the light rig, the
energy-consistency rules, the post chain — are renderer-agnostic and cheap compared to
path tracing.

---

## 2. Symptom-by-symptom

### 2.1 "Light is too soft and diffuse"

**What we do now** (`lighting.rgen`):
- Block-light falloff is the *vanilla lightmap curve* `brightness(l) = l/(4−3l)`
  (`lightTarget()`), spread over a whole block with a random target point in a
  0.6-block cube — every light is a fuzzy ball about a meter across.
- Sun shadow is one binary hard ray; there is no penumbra, no atmosphere, so direct
  sun reads flat.
- Ambient floors everywhere: `* 0.95 + 0.05`, face shading 0.5/0.6/0.8, DDGI smooth
  irradiance — nothing ever gets truly dark, so contrast dies.

**What NVIDIA does** — a *lighting rig*, authored like a film set:

- **Small bright emitters.** Torch light: a sphere of radius **0.08** blocks at
  `(0.5, 0.7, 0.5)`, color `(1.0, 0.35, 0.1)`, intensity `lightLevel(14) × 5 × 3 × 7.5`
  = **1575** (`light/BlockLightDefaults.java`). The "thin sphere" trick: shrink the
  radius ×0.4 and raise intensity ×3 — power stays constant but the source is a point,
  which gives defined pools of light, crisp specular highlights, and visible falloff.
  Everything else: intensity = vanilla level × 5, ×5 baseline (×7.5 torches/campfires).
  Emissive *surfaces* (lanterns, sea pickles) deliberately do **not** cast analytic
  lights; they glow via `_s` alpha at emission intensity 100. Hundreds of weak lights
  are avoided on purpose ("dilute the light sampling", `docs/RESOURCE_PACKS.md`).
- **Sun with angular size + extinction.** Sun is a distant disc light, angular diameter
  **0.53°** (moon 0.52°), path tilted **30°** like BSL/Complementary, base luminance
  **30** (moon 0.06, slightly blue), weather-scaled (rain ×0.075, thunder ×0.03), and
  extinguished per-channel by a Kasten-Young air mass with Rayleigh `(0.145, 0.34,
  0.58)` + Mie `0.08` (`light/LightManager.java`). Sunsets get their color from physics,
  not a texture.
- **Sky is the only ambient.** A Rayleigh+Mie compute-shader dome (256×128 RGBA16F,
  β_R `(5.5,13,22.4)e-6`, β_M `21e-6`, multiple-scattering scale 0.65, atmosphere
  scale 16 day / 70 night, `sky/AtmosphericSkyGenerator.java` +
  `assets/rtx-remix-renderer/shaders/sky_atmosphere.comp`) *is* the environment light;
  a huge downward rect at y=325 (half-size 8000) approximates sky ambient, with a
  **night ambient boost of 2.5** and day boost 0.0 (`sky/SkyRenderer.java`).
  There is no flat "0.05 everywhere" floor: dark places are dark, and night is lifted
  by the dome, not by a constant.
- **Post does the contrast.** Local tonemap shadows **1.4**, highlights **2.5**
  (`settings/PostProcessingSettings.java`) — shadow regions are re-lifted with
  structure, not a global gamma.

**To reproduce (in order of visual impact):**
1. Shrink our analytic emitters (random target point in a 0.6 cube → ~0.15 cube or a
   small sphere), raise intensity to compensate (×3 for the area loss × however far we
   push the baseline), switch falloff from the vanilla curve to inverse-square with a
   small clamp radius. Keep vanilla levels only as the *intensity seed*, exactly like
   their `level × 5`.
2. Give the sun an angular disc (jitter the shadow direction within ~0.5° and
   accumulate) and a per-channel extinction curve — cheap, transforms the daytime look.
3. Lower ambient floors (drop the `+0.05` floors, let night ambient come from one
   sky-term source), then recover shadow detail in the post chain instead (§3).

### 2.2 "Reflections crumble into speckle"

**What we do now** (`reflections.rgen`):
- One reflection ray per pixel per frame, with a *uniform-box* jittered microfacet
  normal of magnitude `roughness × 0.7` (line 119), shaded by the **vanilla lightmap**
  of the hit (`shade()`) — so the reflected "radiance" is albedo × blocky lightmap,
  i.e. the noise of the texture/lightmap itself, re-raytraced every frame.
- The result is written straight into `sceneColor`; there is no reflection history and
  no reflection denoiser (we only feed NRD from `lighting.rgen`). Temporal averaging is
  whatever FSR's upscaler happens to give.

**What NVIDIA does:**
- Reflections are path-traced specular radiance — the hit is shaded with the same
  direct+indirect lighting as everything else, importance-sampled GGX, no lightmap
  involved.
- The denoiser is **DLSS Ray Reconstruction**, fed with linear-depth, normals,
  roughness, and — critically — `rtx.rayreconstruction.combineSpecularAlbedo = true`
  (`packaging/rtx.conf`): primary+secondary specular albedo are combined so the
  network can separate surface color from lighting; without it "water reflects blotchy
  sky-coloured smears" (their comment). Noise is killed by a spatiotemporal model
  trained to preserve highlights, not by more samples.

**To reproduce (pragmatic ladder):**
1. **Cheap, huge win:** shade the reflection hit with our *existing* stable signals
   instead of the raw lightmap — the accumulated block-light history and DDGI
   irradiance (both already computed in `lighting.rgen`). Reflected light stops being
   per-frame noise; speckle drops immediately.
2. **Denoise reflections for real.** We already integrate NRD for diffuse
   (`blockRaw`/`giRaw`/`viewZ`); NRD's REBLUR specular mode is the same integration
   with a specular payload (radiance + hit distance + roughness from `gbufferNormals.a`,
   checkerboarded like the GI path). Alternatively a small custom temporal reflection
   buffer: reproject with disocclusion rejection (we already have the machinery in
   `lighting.rgen`'s history taps) and clamp history to the new sample ± variance.
3. **Sample smarter:** GGX/hemisphere importance sampling instead of uniform-box
   jitter, jitter scaled by `roughness²` (matches our `(1−s)²` roughness), and fade
   reflections out with the same range fade we use for shadows so distant rough
   surfaces don't shimmer at the fade edge.

### 2.3 "Colored glass shadows are bright color patches"

**What we do now** (`shadow.rahit` lines 27–32 + `lighting.rgen` line 389):
- The any-hit shader *normalizes* the glass tint to full saturation and saturates it
  further: `color /= maxComponent; color = pow(color, 1.5)`, with the explicit comment
  "glass colors light more than it dims it". Then `lighting.rgen` derives `through =
  maxComponent` — a red glass pane with transmittance (0.9, 0.1, 0.1) darkens the
  surface by only ~10% but paints it in fully saturated red. Hue is decoupled from
  energy, so shadows look like stained-glass-window color blobs.

**What NVIDIA does:**
- Physically consistent multiplicative transmittance. A shadow ray through glass
  accumulates the *actual* transmittance color (Beer–Lambert style product), with
  `rtx.enableDirectTranslucentShadows = true` / `rtx.enableIndirectTranslucentShadows =
  true` (`packaging/rtx.conf`). A pane that passes 30% of red light darkens the surface
  to 30% and tints it red — the shadow is dim *and* colored, never brighter than the
  unoccluded light, hue saturation proportional to how much light is actually filtered.
- Their baseline glass: IOR 1.52, transmittance `(0.97, 0.97, 0.97)` at 0.35 m — nearly
  clear glass barely tints; deeply colored glass needs deep tint to read.

**To reproduce:** in `shadow.rahit`, drop the normalization and the `pow(1.5)`:
multiply `shadow.rgb *= mix(vec3(1.0), tex.rgb, alpha)` (possibly with a mild
`pow(color, 0.8)` to compensate display gamma if a single pane reads too weak).
In `lighting.rgen`, replace the `through`-normalization with the raw transmittance:
`sunFactor = mix(1, shadowTransmittance, strength · fade)` — occlusion *is* the
transmittance product. Same for the reflection glint shadow. One pane will tint
subtly; a stack of panes goes dark and deeply colored, which is exactly what a
production picture does.

---

## 3. The post chain (this is where "production" actually happens)

NVIDIA's numbers (`packaging/rtx.conf`, `settings/PostProcessingSettings.java`, and
the conf profiles), directly transferable as a tuning target:

| Parameter | Value | Notes |
|---|---|---|
| Tonemap | ACES (non-legacy) | `rtx.useLegacyACES = false`; "legacy skews colors" |
| Exposure | **0.21405728** | includes an authored **−0.75 EV** bias |
| Local tonemap shadows / highlights | **1.4 / 2.5** | shadow lift, highlight rolloff |
| Auto-exposure EV range | **−2.5 … 1.5** | night caps at −0.5 so a dark sky never exposes down |
| Auto-exposure speed / metering | 10 / center 0.75 | |
| Exposure preference σ / offset | 3.5 / −0.1 | biases adaptation darker |
| Bloom (day) | 8 steps, threshold **250** (pre-tonemap), burn 0.1 | only the hottest emitters bloom — "film camera halation" |
| Bloom (night) | threshold **1**, burn **1.5** | deliberate authored pair with the night exposure cap |
| Rain/thunder looks | fog 0.996/0.97 white @ 10 m, shadows 1.75/2.0, EV max −1.0 | conf-layer blend by vanilla weather gradient |
| Underwater | fog transmittance (0.6, 0.9, 0.89) @ 20 m, SSA (0.206, 0.999, 0.903), anisotropy 0.4 | separate from the surface material (IOR 1.33, transmittance @ 1 m) |

And the stability side, so the picture stays clean while moving:
- Denoising is per-signal with proper inputs (RR + ReSTIR DI/GI), and the sparse
  renderer keeps RTXDI reuse on inactive pixels to avoid direct-light flicker
  (`rtx.sparseRendering.*` in `rtx.conf`).
- Temporal caches are bounded and distance-validated like ours, but with big
  accumulation budgets (SHaRC 1000 frames) and position *wrapping* instead of
  recentering to avoid resets.

Our composite (`rt_composite.comp`) + existing FSR/NRD stage is the place to land the
ACES + exposure-bias + local-contrast + selective-bloom stack; none of it needs more
rays.

## 4. Reference: NVIDIA's authored light cheat-sheet

From `light/BlockLightDefaults.java`, `light/LightManager.java`, `sky/SkyRenderer.java`:

| Item | Value |
|---|---|
| Radiance per vanilla light level | 5.0 |
| Torch | sphere r=0.08 @ (0.5, 0.7, 0.5), color (1.0, 0.35, 0.1), intensity = level·5·3·**7.5** ≈ 1575 |
| Soul torch | same, color (0.05, 0.2, 1.0) |
| Campfire | sphere r=0.25 @ center, (1.0, 0.1, 0.05), ×7.5 |
| Candles 1–4 | thin spheres r=0.024 at authored per-count offsets, ×5 |
| Amethyst cluster | sphere r=0.18, (0.65, 0.35, 1.0), level·5·2 |
| Default intensity baseline | ×5 (×7.5 torch/campfire); thin lights shrink r ×0.4, boost ×3 |
| Emissive surfaces | intensity 100 (terrain/entity), 40 (particles), 4 (end portal) |
| Sun / moon | luminance 30 / 0.06, angular size 0.53° / 0.52°, path tilt 30°, weather ×0.075 / ×0.03 |
| Atmosphere extinction | Rayleigh (0.145, 0.34, 0.58) + Mie 0.08, Kasten-Young air mass |
| Sky dome | Rayleigh+Mie compute shader, MS scale 0.65, scale 16 day / 70 night, Mie g 0.9 |
| Sky ambient | rect light y=325, half-size 8000; night boost 2.5, day 0.0 |
| Sun/moon billboards | emissive quads at 100 blocks, radiance 8.0 / 0.2; stars radiance 1.0 |
| Water | IOR 1.33, transmittance (0.6, 0.9, 0.89) @ 1 m, normal flipbook waves |
| Glass | IOR 1.52, transmittance 0.97 @ 0.35 m |
| Beacon beam | 30° cone, intensity level·5·150 placed ·45 held ·15000 display |

## 5. Suggested order of work

1. **Glass shadows** (§2.3) — smallest diff, instantly removes the "color patches".
2. **Reflection shading + temporal/denoiser** (§2.2 step 1, then 2) — kills the speckle.
3. **Post chain** (§3) — ACES + −0.75 EV + shadows 1.4/highlights 2.5 + selective bloom;
   this alone moves the picture from "demo" to "film".
4. **Light rig** (§2.1) — small bright emitters, sun disc + extinction, ambient floor
   reduction. Biggest artistic change; do it after the post chain so contrast has
   somewhere to go.
