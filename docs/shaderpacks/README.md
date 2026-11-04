# Shader packs: design

Status: 2026-10-31, updated after the OptiFine reference run. M0 to M7 have code; see "Implementation status" for what each milestone
reached and which exit criteria are still open. Paths use the AUDIT conventions:
`J/` = `src/main/java/net/vesu/`, `S/` = `src/main/resources/assets/vesu/shaders/`.

## Goals

1. **OptiFine parity.** Every shader-pack feature of OptiFine HD U G5 (the last 1.12.2 build)
   is either supported or has a documented replacement. A 1.12.2 OptiFine pack dropped into
   `shaderpacks/` runs without manual porting.
2. **Modern packs.** Minecraft 26.2 `post_effect` chains (the format Vulcade / Vulkan PostFX
   packs use) and Bedrock PBR packs (Vibrant Visuals: `texture_set.json` and the lighting,
   atmospherics, water and color-grading JSON) load as well.
3. **Faster than OptiFine on the same pack**, measured the README way (fixed path, fixed
   seed, Avg / 1% / 0.1% low). Average FPS must not be lower than OptiFine on any corpus
   pack, and the 1% low must not be worse either.
4. **Zero cost without a pack.** With no pack selected the frame is the one we render today.
   That means no extra vertex attributes, no extra targets, no extra passes and no HDR
   target.

## Non-goals (v1)

- Bedrock shaders (`.material.bin`, RenderDragon). They are compiled binaries, and Mojang
  does not support custom Bedrock shaders either.
- Iris **Aperture** packs (a TypeScript pipeline description with Slang shaders). They would
  need a JS runtime and `slangc`, and the format is still moving. Revisit after v1.
- 26.2 `core/` shader overrides. They target 26.2 vertex formats and render types that do
  not exist on 1.12.2. Our own core shaders get resource-pack overrides instead (see
  [runtime.md](runtime.md#resource-pack-overrides-of-our-own-shaders)).
- OptiFine features that are not shader features: CTM, CIT, emissive textures, custom sky,
  random mobs and custom colors. That is a separate track. Several of these exist as
  standalone 1.12.2 mods that work at the model level and should already work with our
  renderer.

## Architecture in one picture

```
             pack-compiler (gradle subproject, no Minecraft dependency)
 ┌──────────────────────────────────────────────────────────────────────────┐
 │  PackSource (dir / zip / jar)                                            │
 │     │                                                                    │
 │     ├─ OptiFine frontend ──┐                                             │
 │     ├─ post_effect frontend┼──► IR pack (pack.json v1 + GLSL 450) ──┐    │
 │     ├─ Bedrock frontend ───┤        validate, reflect, hash          │    │
 │     └─ native (identity) ──┘                                        │    │
 │                                                    CLI: vkpack convert/check
 └─────────────────────────────────────────────────────────────────────┼────┘
                                                                       ▼
   mod runtime:  PackManager ─► PackCompiler cache ─► FrameGraph + ProgramTable
                    (GUI, options)   (IR + SPV + pipeline keys)   (FrameTargets, Drawer,
                                                                   TerrainRenderer, ...)
```

The **IR is our own native pack format**. Every external format is lowered into it, and the
renderer only knows how to execute IR. That is the central decision: the renderer stays
format-agnostic, and each frontend is a translator that can be tested offline.

## Documents

| File | Contents |
|---|---|
| [pack-format.md](pack-format.md) | IR / native pack format v1: layout, `pack.json` schema, stages, textures, options |
| [host-interface.md](host-interface.md) | GLSL contract between pack programs and the host: conventions, uniform blocks, samplers, per-draw data |
| [runtime.md](runtime.md) | How the renderer executes a pack: frame graph inside `FrameTargets`, program routing per draw category, resources, shadow pass, compilation, performance rules |
| [frontends.md](frontends.md) | `pack-compiler` module, the OptiFine translator, the `post_effect` and Bedrock frontends |

## Decisions

| ID | Decision | Why |
|---|---|---|
| D1 | One IR (native format). Frontends lower into it, the runtime executes only IR. | Keeps the renderer format-agnostic, and frontends become testable without a GPU. |
| D2 | The converter is a library (`pack-compiler`) used both by the mod at load time and by a CLI. | OptiFine options are `#define`s, so the GLSL rewrite has to run on preprocessed source for the current option set, which only the running game knows. The CLI uses the same code for inspection, CI and authors who want to hand-port a pack. |
| D3 | Pack programs never declare bindings, sets or locations of host resources. The host injects a prelude with all declarations. | Lets us renumber descriptors, merge blocks and drop unused resources without breaking packs. This is the 26.2 lesson: the variants and uniform blocks are owned by the engine. |
| D4 | **Pack space uses GL conventions:** image row 0 is the bottom row, `gl_FragCoord` has a bottom-left origin, and depth is GL window depth in [0,1]. The host converts once, at the final pass. | OptiFine code reconstructs positions from `gl_FragCoord`, texcoords and depth using GL rules everywhere, and these cannot be patched reliably per expression. Depth already matches: `VRenderSystem.DEPTH_REMAP` stores GL window depth. Only the y layout differs (see host-interface.md). Vanilla 26.2 uses reversed depth, so the `post_effect` frontend un-reverses its depth inputs. |
| D5 | Uniforms are grouped by update frequency into fixed blocks: per-frame `Frame`, per-pass `Pass`, pack-defined `PackUniforms`, per-draw push constants. | One upload per frame instead of per draw. The same idea as 26.2's `Globals` / `Projection` / `DynamicTransforms` split. |
| D6 | The pass schedule is static. Ping-pong flips, barriers, layouts, descriptor sets and transient memory aliasing are resolved when the pack loads. | Nothing about the pack graph is decided per frame, and this is where we beat OptiFine's GL driver path. |
| D7 | Resources a pack never reads after preprocessing are not allocated, cleared or written. This is decided by SPIR-V reflection. | OptiFine allocates and clears every declared buffer. On the UHD 620 that bandwidth is the main cost. |
| D8 | The shadow pass is **recorded late and submitted early**. It is recorded after the main pass has captured this frame's entities, into a command buffer submitted before the scene. | Same-frame entity shadows without running vanilla entity rendering twice (OptiFine does run it twice). Terrain shadows come straight from the `ChunkBatcher` arena, with our own light-frustum culling. |
| D9 | Extended vertex attributes (normal, tangent, mid texcoord, block id) exist only while a pack requires them. Chunks rebuild on switch. | This is goal 4. |
| D10 | Compilation is asynchronous. The old pack (or vanilla) keeps rendering until the new pipelines exist. **Not done:** programs compile on all cores into a disk cache, but the first frame waits. | No hitch on pack or option changes. |
| D11 | The material atlas gets a **neutral internal layout**, decoded from LabPBR or Bedrock MER(S) at stitch time. | `RtMaterials` is LabPBR-native today, and Bedrock's continuous metalness does not fit LabPBR's metal ids. |

## Roadmap

Each milestone ends with: `./gradlew build`, then a `VESU_VALIDATION=1 VESU_AUTOTEST=1`
run clean, and the README benchmark for the no-pack path within ±1 % of the previous release.

| M | Scope | Exit criteria |
|---|---|---|
| M0 | Foundations, no visible change: `pack-compiler` subproject (moves `ShaderPack` and `PackFiles` into it), PackManager, pack list GUI, `#line` mapping for errors, the `Frame` block, a pack-space (GL-layout) render mode in `FrameTargets` | No-pack FPS unchanged, autotest clean |
| M1 | Native post packs: `POST_SCENE` and `combination`, host textures `sceneColor`, `sceneDepth`, `motion`, an opt-in HDR scene target | Sample pack (bloom, tonemap, grading) runs. GPU cost per pass is reported under `VESU_PROFILE=1`. |
| M2 | 26.2 `post_effect` frontend | Vulcade-style chains render the same on Vulkan 26.2 and here (screenshots) |
| M3 | Geometry stages: program routing per draw category, MRT union, the `DEFERRED` hook at `snapshotOpaque`, the hand inside the scene, chunk format variants with ID attributes | Feature test packs `testpacks/gbuffers-*` pass |
| M4 | Shadow pass (D8): light-frustum culling over the arena, captured entities, `SHADOW_COMP` (native packs; G5 has no `shadowcomp`) | Shadow test pack; shadow cost lower than OptiFine on the same pack |
| M5 | OptiFine frontend v1: options, profiles, screens, custom uniforms, ID maps, GLSL lowering | The corpus (frontends.md) compiles and passes visual comparison against OptiFine reference shots |
| M6 | Bedrock frontend and neutral materials: `texture_set.json` (P1), normal maps (P2), Vibrant Visuals settings (P3) | A Vibrant Visuals pack renders with its materials and lighting settings |
| M7 | Beyond OptiFine: Iris extensions (compute stages, images, `begin` / `setup`) | Compute stages run in a native pack |

M2 is placed early on purpose: it is cheap once M1 exists, and it gives users packs right away.

## Implementation status

**M0 (2026-10-29).**

| Item | Where |
|---|---|
| `pack-compiler` subproject (Java 25, Gson compile-only, JUnit tests), merged into the mod jar through `vulkanBundle`. In dev runs it goes to the LaunchClassLoader with the mod's classes. | `pack-compiler/`, root `build.gradle` |
| IR parser (`ShaderPack` → `IrPack`): `format` required, all stages parsed, history / scale / size / blend / mipmaps / workGroups, `vkm` prefix reserved, host texture names reserved | `pack-compiler/.../pack/ir/IrPack.java`, `TextureFormat.java` |
| Pack sources (directory, zip, classpath) and include expansion with `#line` + `GL_GOOGLE_cpp_style_line_directive` (`PackFiles` → `PackSource`, `IncludeExpander`) | `pack-compiler/.../pack/source/` |
| Format detection: native / OptiFine / post_effect / Bedrock | `pack-compiler/.../pack/PackKind.java` |
| std140 layout builder and the `Frame` block (one source for the GLSL declaration and the host offsets) | `pack-compiler/.../pack/host/` |
| Filling the `Frame` block, only with a selected pack or `VESU_FRAME_DUMP=1` | `J/shader/FrameUniforms.java` |
| PackManager (scan of `shaderpacks/`, selection in `config.shaderPack`, full parse and program existence check of native packs) | `J/shader/PackManager.java` |
| Pack list (Video Settings → Shader Packs page) | `J/config/gui/option/VesuOptionPages.java` |
| Pack-space render mode: positive scene viewport, clockwise front face (pipeline key bit 37), one flipping blit into the display | `J/vulkan/FrameTargets.java`, `Pipeline.java`, `Drawer.java` |

Debug knobs:
- `VESU_PACK_SPACE=1` renders the SCENE phase in pack space (D4). The picture must be
  identical to a normal run. The knob forces the blit upscaler and is ignored with RT on.
- `VESU_FRAME_DUMP=1` fills the `Frame` block every frame and logs it every 120 frames.

**M1 (2026-10-30): native post packs render.**

| Item | Where |
|---|---|
| Static schedule: ping-pong main/alt images, history (`<name>Prev`), host inputs, skipped stages with warnings | `pack-compiler/.../pack/plan/FramePlan.java` |
| Host prelude: `Frame` block, `Pass` push constants, samplers, storage images, `vkm_texCoord`, `vkm_FragCoord`, depth helpers; the host vertex triangle | `pack-compiler/.../pack/host/Prelude.java`, `IncludeExpander.expandProgram(rel, prelude)` |
| Runtime: pack images (scaled / fixed size, mips, clears, history copies), fragment and compute pipelines, descriptor sets per pass × frame, a per-image barrier tracker, per-pass GPU timing | `J/vulkan/PackRuntime.java` |
| Hooks: the pack runs between the scene and the upscale; the scene renders in pack space while a pack runs; the combination writes the scene color in Vulkan row order | `J/vulkan/FrameTargets.java` |
| `motion` in pack texcoords (`PACK_SPACE` variant of the motion-vector shader) | `J/vulkan/MotionVectorPass.java`, `motion_vectors.comp` |
| `Frame` block filled at the head of the terrain pass (world fog and camera), uploaded per frame in flight | `J/shader/FrameUniforms.java`, `TerrainRenderer` |
| Built-in sample pack `builtin:sample_post`: bright pass with mips, ping-ponged blur (fragment + compute), eye adaptation over a history texture, combination with bloom, exposure, shoulder, grading, rain haze from depth, vignette | `src/main/resources/assets/vesu/shaderpacks/sample_post/` |

Debug knobs: `VESU_SHADERPACK=<id>` (or `off`) overrides the selection for one run
without saving; with `VESU_PROFILE=1` the log gets `[gpu] pack <id>: <pass>=<ms> ...`
every 120 frames, and `[gpu] ms/frame` has a `pack` column.

An opt-in HDR scene: `renderer.hdr: true` renders the scene to RGBA16F, with pipeline
variants for that format (runtime.md, "Scene format and HDR"). The sample pack uses it.

Measured on a Tesla V100, sample pack, 2560x1440 at render scale 0.8 (2048x1152), raster
bench scene, averaged over 120 frames:

| | Pack passes | GPU frame total |
|---|---|---|
| RGBA8 scene | 0.20 ms | 0.67 ms |
| RGBA16F scene | 0.25 ms | 0.77 ms |

The bench is CPU-limited at about 157 FPS, so FPS does not show this cost. Times from short
windowed runs vary by a factor of three with the GPU clock state; use the bench for
comparisons. The UHD 620 is not measured yet.

No-pack path against the last commit (3e02a37), three alternating runs each: 157.5 and
156.3 FPS average, -0.8 %, within the run-to-run spread of about 3 %. The working tree
also holds other uncommitted changes, and the baseline is a commit, not a release.

Autotests with validation are clean for: RGBA16F and RGBA8 scenes, render scale 0.5 with
FSR 1, 0.8 with the blit, 1 with the shared scene and display image, the `motion` input,
a pack paused by RT, switching packs at runtime (HDR, LDR, off, HDR) and resizing with a
pack active. `VESU_AUTOTEST_RESIZE=1` with `VESU_AUTOTEST_PACKSWITCH=<id>` runs
the last two.

Limits of this part:
- Ray tracing and a pack with its own lighting exclude each other: with RT on the pack is paused
  (logged, and the pack and RT screens say so). A post-only pack with `renderer.rt` keeps running
  over the RT picture. FSR 3 is off while a pack runs (it reads the scene in Vulkan row order); FSR 1
  and the blit work.
- The GUI stays in the DISPLAY phase, after the pack.
- `enabled` expressions and custom vertex programs are ignored with a warning (M5).
- Compilation of this milestone was synchronous; D10 made it asynchronous (see M5).

**M2 (2026-10-30): 26.2 `post_effect` packs render.** Vulcade 1.0.0-beta runs in all four
profiles and scenes.

| Item | Where |
|---|---|
| Frontend: manifest, profiles, scenes, chains in sequence, `main` tracking, reversed-depth pass, generated programs overlaid on the source | `pack-compiler/.../pack/postfx/PostEffectFrontend.java` |
| GLSL 330 → 450: blocks to per-pass constants by position, `SamplerInfo`, engine blocks to `Frame`, varying locations | `pack-compiler/.../pack/postfx/PostShaderLowering.java` |
| IR additions, usable by native packs: `scenes`, per-pass `filters`, a pass's own `vertex` program, `file` textures (PNG), `renderer.frameInstance` | `IrPack`, `Prelude`, `FramePlan`, `PackRuntime` |
| One runtime per scene variant, all compiled at load; only the running one holds images | `J/vulkan/PackRuntime.java` |

Debug knobs: `VESU_PACK_PROFILE=<name>`, `VESU_PACK_SCENE=<scene>|cycle`, and
`VESU_AUTOTEST_PACKSCENES=1`, which puts water at eye level and goes to the Nether and
the End.

Checked on the V100, with validation clean in every run:
- `invert`, computed from the no-pack frame of the same scene: mean error 0.25 of 255.
- `creeper`, same way: mean error 0.49. Its mosaic samples on a texel boundary, so either
  neighbour counts as correct. Its grid does not tell the two row orders apart at this height.
- Vulcade `composite` on one sky pixel, by hand: (211, 224, 213) against (210, 221, 211),
  without the vignette.
- Variants chosen by the real conditions: water at the eyes, Nether, End. Forced variants
  and a cycle through them render too.

Pale daytime skies under Vulcade are the pack's own math on this input: sky luminance 0.68
stays under its bloom threshold of 0.78, so the sun probe reads 0 and the ore glow is not
suppressed.

Vulcade per profile, on the bench of the M1 section (GPU pack time / whole frame):
low 0.11 / 0.59 ms, medium 0.42 / 0.89 ms, high 1.48 / 1.96 ms, ultra 1.89 / 2.36 ms.

Compared with a real 26.2 client (`minecraft-26.2-ref`, flat world, 2560x1440): mean difference 15.0 by
day, 30.8 at sunset, 11.8 at night, 29.8 in rain, of 255. The bloom, the grading and the sky of the
pack match by eye. What is left comes from the base game: 1.12 has another lightmap (darker ground at
sunset and at night), another cloud height and other mobs in view, and 26.2 fogs rain more.
Not seen: the Nether chain on Nether terrain (the test's camera ended up in the dark, without a pack
too), and a `file` texture, which no chain at hand uses and which has no run on the GPU.

Limits are listed in frontends.md, "Differences from 26.2". The mod does not ship Vulcade.

**M3 (2026-10-31): geometry programs run.** `gbuffers` in the IR with the G5 fallback chains, the
scene MRT union, `DEFERRED`, the depth copies behind `depthtex1` and `depthtex2`, the hand inside the
scene before `DEFERRED`, chunk attributes from `requires.vertex`, the `Draw` push constants, every
G5 category. A geometry target can also be sampled, through a copy taken after `DEFERRED`.
`testpacks/gbuffers-*` hold the checks with stored shots (`tools/testpacks.sh`).

**M4 (2026-10-31): the shadow map runs** (`J/vulkan/ShadowPass.java`, runtime.md): its own command
buffer, submitted first; terrain as indirect draws over the arena; entities mirrored at their
draw; `shadowtex0`, `shadowtex1`, `shadowcolor0`, `shadowcolor1`, comparison samplers, color mipmaps.
On the V100, 2560x1440, 2048² map, about 120 sections and 830 draws: 0.40 ms of GPU.
The criterion "cheaper than OptiFine" is measured as a whole frame only (the M5 table): OptiFine has no
per-pass timing here. Depth mipmaps are missing (no pack of the corpus enables them). `SHADOW_COMP`
runs in native packs (`testpacks/shadow-comp`): it can read the shadow map and write the pack's own textures.

**M5 (2026-10-31): OptiFine packs run** (frontends.md, "OptiFine: what differs"). All 1,384 stage
sources of the corpus compile offline with glslang.

Checked against OptiFine HD U G5 on 2026-11-02, with every feature of this document in: the Prism
instance `optifine-1.12.2-ref` runs the same packs, world copy and scenes as `local/ref` (a small Forge
mod on that side, `VESU_AUTOTEST_SCENES` here). V100, 2560x1440, render distance 20, one headless
gamescope display for both sides, with nothing crossing to the iGPU. FPS is the mean of five scenes;
"worst" is the scene where the lead is smallest. "Diff" is the mean absolute difference of the frames,
0 to 255. The figures of 2026-10-31 used a window through Xwayland and are not comparable.

| Pack | OptiFine FPS | This mod FPS | Lead | Worst scene | Diff, mean of scenes |
|---|---|---|---|---|---|
| No pack | 344.8 | 482.7 | +40% | +33% overlook | 5.5 |
| BSL 10.1.8 | 72.3 | 101.6 | +40% | +26% night | 5.4 |
| Complementary Reimagined r5.9.3 | 73.7 | 77.2 | +5% | -4% day | 5.4 |
| Sildur's Vibrant 2.02 Extreme | 73.1 | 125.7 | +72% | +57% day | 9.5 |
| Sildur's Enhanced Default 1.20 | 107.2 | 110.1 | +3% | -19% sunset | 13.5 |
| Pastel 1.200 | 66.5 | 72.4 | +9% | +3% night | 15.8 |
| E-Lite 5.1.3 | 93.0 | 118.6 | +28% | +6% rain | 8.2 |
| Potato 1.0d | 126.7 | 236.2 | +86% | +69% overlook | 10.2 |

The mean leads on every pack. Two single scenes do not: Complementary by day and Enhanced Default at
sunset. Vibrant Visuals (`vv_vanilla`, no OptiFine side) runs at 100 FPS on the same scenes, 83.6 to
123.0.

Diff is not zero even for a perfect match: entities move, the hand skin differs (the accounts differ)
and clouds drift. All seven packs pass the visual comparison by eye. Pastel's rain differs because the
reference is broken: on the NVIDIA driver OptiFine fails to compile its `deferred1`, `gbuffers_textured`
and `gbuffers_water` (`error C1038`, `fogColor` declared twice), so its fog never runs there. Here those
programs run. For the same reason OptiFine's FPS for Pastel measures less work. A Wayland window caps this mod near 140 FPS
at the compositor's pace; the numbers above use `SDL_VIDEODRIVER=x11`, as OptiFine goes through Xwayland.

What the comparison found: `gl_Color` of the sky was white, custom `vec3`/`vec4` uniforms repeated their
first component, pack textures clamped instead of repeating, `colortex0` did not clear to the fog color,
OptiFine's horizon octagon was missing, the hand sat in the wrong depth range and without alpha test,
uninitialized GLSL locals were undefined in SPIR-V, the sun quad ignored `sunPathRotation`, and more than
32 varying locations made the GPU fault (`Xid 13`, device lost). Each is fixed.

Compilation runs on a thread of its own (D10): the frame keeps its look until the pack is in the SPIR-V
disk cache, then the switch takes about 2 s for pipelines. Autotests compile in place.

**M6 (2026-11-01): Bedrock packs render close to Vibrant Visuals** (frontends.md, "Bedrock frontend"):
a deferred PBR pipeline parameterized by the pack's settings JSON and texture sets. Compared
with Bedrock 26.52 on the same scene (11 views): about 24/255 mean, sky within about 7/255. Settings
follow the camera's biome, and bloom follows the game's pyramid.

**Bedrock fidelity/performance pass (2026-11-04):** the built-in pack is now the vanilla preset
(biomes, settings and MERS texture sets). Default shadows are blocky; sky uses sun/moon Mie and
glare shape; water uses opacity, absorption, analytic Bedrock waves and a direct GGX glint;
MERS subsurface participates in directional and point diffuse; medium fog, temperature grading and
highlight/midtone/shadow grading ranges are honored. The light clusterer reads a compact
active-light list, exposure uses a parallel 256-bin histogram, and opaque SSR runs at half
resolution with motion-vector history before its fullscreen apply. New visual parity/performance
figures still need a fresh reference run.

**M7 (2026-10-31): `SETUP`, `BEGIN` and `PREPARE` run** before the scene, compute included
(`testpacks/stages-compute`). The OptiFine frontend maps them too, for packs that ship them for Iris.
`renderer.rt` keeps a post-only pack running after the RT composite (`testpacks/post-over-rt`).
A pack with geometry programs and RT exclude each other by design: such a pack does its own light and
shadows, so there is nothing to mix (`PackManager.pausedByRt`, `FramePlan` rejects `renderer.rt` with
geometry). Host textures of RT lighting were removed for that reason.

**Iris superset (2026-11-02):** compute programs, custom images, SSBOs, raw textures, geometry and
tessellation stages, `shadowcolor2` to `7`, writes to shadow colors after the shadow pass.
Storage images from fragment and geometry programs, middle stages on full-screen passes, a `.tes`
without `.tcs`, interface blocks. `testpacks/resources`, `geometry`, `shadow-colors` and
`pass-stages` cover them. Complementary `COLORED_LIGHTING` runs.

**Vibrant Visuals effects (2026-11-02):** waves, caustics, absorption, screen-space reflections,
volumetric fog, blocky shadows, cubemaps, point lights, item and entity texture sets. Against the
game: mean difference 24.0 with the arm masked out, 23.8 before them. `frontends.md` lists the
differences.

## Open questions

These are tracked here until resolved. An open one is marked *(verify)* where it appears.
Items 1–3 and 6 were checked on 2026-10-29 against the OptiFine HD U G5 jar (decompiled),
the 26.2 client jar, the license files and `vulkaninfo`; the docs now state the results.

1. **Resolved: OptiFine 1.12.2 pass order.** In G5 the opaque hand renders before
   `deferred` and the translucent layer, and the translucent hand after it. `depthtex2` is
   copied before weather and `depthtex1` before the translucent layer. Program per category:
   runtime.md. G5 has no `prepare`, `shadowcomp` or `renderStage`.
2. **Resolved: `mc_Entity` on 1.12.2.** (id, render layer 0–3, metadata, 1); runtime.md,
   "Vertex data".
3. **Resolved: licenses.** Iris is LGPL-3.0, so its code may be reused with attribution.
   glsl-transformer is AGPL-3.0 and is not used; the parser is our own (frontends.md).
4. **RT outputs in pack space.** RT images are in Vulkan layout. Flip in the raygen launch
   index mapping or at sampling, decided in M7.
5. **TAA ownership.** When a pack does its own TAA, host jitter and FSR 3 are off. Should a
   pack be able to opt into FSR 3 instead?
6. **Resolved: Vulkan 1.2 baseline** (AUDIT M2) and optional features:
   - geometry shaders are missing on MoltenVK (upstream 1.4.3, issue #1524 open);
   - `VK_EXT_depth_clip_control`, push descriptors, dynamic rendering, `independentBlend`
     and `fragmentStoresAndAtomics` are present on the UHD 620 (Mesa 26.2.5) and the V100
     (NVIDIA 580), and depth clip control is on MoltenVK since 1.3.0.

   Packs that need a missing feature must fail with a clear message, not a crash. For
   reference, the 26.2 Vulkan backend requires dynamic rendering, push descriptors,
   synchronization2 and vertex attribute divisor.
7. **Bedrock MER(S) roughness:** the game's deferred shader computes alpha as roughness squared,
   so the G-buffer value is perceptual. We read MER(S) the same way; whether the game's prepass
   remaps it before the G-buffer was not checked.

## References

- OptiFine shader documentation: `OptiFineDoc/doc/shaders.txt` and `shaders.properties` in
  https://github.com/sp614x/optifine
- Iris documentation (OptiFine superset): https://shaders.properties and https://shaderlabs.org/wiki
- Minecraft 26.2 rendering changes: https://minecraft.wiki/w/Java_Edition_26.2
- Vibrant Visuals resource packs: https://learn.microsoft.com/en-us/minecraft/creator/documents/vibrantvisuals/vvresourcepacks
- Vibrant Visuals configurations (vanilla values): https://minecraft.wiki/w/Vibrant_Visuals/Configurations
