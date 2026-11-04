# Host interface (GLSL contract)

What a pack program can rely on. Programs are Vulkan GLSL 450. Before compiling, the host
puts a generated prelude in front of every program, so a program uses the names below
without declaring them (D3). Declaring a host name is a compile error with a clear message.

## Conventions ("pack space", D4)

| | Pack space | Why |
|---|---|---|
| Image rows | Row 0 is the **bottom** of the image (GL layout) | OptiFine code mixes `gl_FragCoord`, texcoords and NDC freely. |
| `gl_FragCoord` | Bottom-left origin, as in GL | Same. |
| Full-screen texcoord | (0,0) bottom-left, (1,1) top-right | Same. |
| NDC | x right, y up, z in [-1, 1] (GL) | Matrices are the GL matrices. |
| Stored depth | GL window depth, [0, 1], near = 0 | Already true today: `VRenderSystem.DEPTH_REMAP` maps z to `0.5z + 0.5w`. |
| Front face | CCW in pack space | GL default. |

How the host gets there:
- Pack-space passes and geometry draw with a **positive-height viewport**. The vanilla path
  uses a negative one (`Drawer.setViewport`, `FrameTargets.beginRendering`). A positive
  viewport stores row 0 at the GL bottom, which flips the winding, so pack pipelines use
  `VK_FRONT_FACE_CLOCKWISE` to keep GL's CCW culling.
- The **depth remap** stays as is. It is applied to the host-built clip matrix, not to the
  matrices packs see.
- **At the boundary,** `combination` draws into the host scene color (Vulkan layout) with
  the negative viewport. Its prelude defines `gl_FragCoord` through a flipped wrapper
  (`vkm_FragCoord`, which the OptiFine lowering substitutes in `final`). That makes the flip
  free, with no extra copy.
- **Host textures produced outside pack space** (motion vectors, RT outputs) are flipped
  when written, or sampled through `vkm_hostUV()` *(verify per texture, README Q4)*.

## Descriptor layout (fixed by the host, invisible to packs)

| Set | Contents | Changes |
|---|---|---|
| 0 | `Frame` UBO (binding 0), `PackUniforms` UBO (M5), every sampled pack and host texture of the current pass (bindings 1.., in `inputs` order), then the storage images of the pass (in `writes` order) | Per pass. Prebuilt for every pass × frame in flight at load (D6). |
| 1 | Per-draw material: `gtexture` (bound texture), `lightmap`, `normals`, `specular` (geometry stages, M3) | On texture bind (`VTextureSelector`) |
| push | `Pass` block in command-list passes; `Draw` block (below, at most 128 bytes) in geometry stages | Per pass / per draw |

Packs never see these numbers. The prelude declares, for example,
`layout(set = 0, binding = 7) uniform sampler2D colortex2;`.

## `Frame` block (per frame, one upload)

The names are OptiFine's, so translated packs need no renaming and native packs read
familiar names. Fields are std140-packed in this order. The table is the v1 coverage
target. The authoritative full list of OptiFine uniforms is `shaders.txt`.

| Group | Fields |
|---|---|
| Camera | `gbufferModelView`, `gbufferModelViewInverse`, `gbufferProjection`, `gbufferProjectionInverse`, `gbufferPreviousModelView`, `gbufferPreviousProjection`, `cameraPosition`, `previousCameraPosition`, `eyeAltitude`, `near`, `far`, `aspectRatio`, `viewWidth`, `viewHeight` |
| Shadow | `shadowModelView`, `shadowModelViewInverse`, `shadowProjection`, `shadowProjectionInverse`, `shadowLightPosition`, `shadowAngle` |
| Sky | `sunPosition`, `moonPosition`, `upPosition`, `sunAngle`, `moonPhase`, `skyColor`, `fogColor`, `fogMode`, `fogDensity`, `fogStart`, `fogEnd` |
| Time | `worldTime`, `worldDay`, `frameCounter`, `frameTime`, `frameTimeCounter` |
| Weather | `rainStrength`, `wetness` |
| Player | `isEyeInWater`, `eyeBrightness`, `eyeBrightnessSmooth`, `nightVision`, `blindness`, `screenBrightness`, `heldItemId`, `heldBlockLightValue`, `heldItemId2`, `heldBlockLightValue2`, `hideGUI`, `centerDepthSmooth` |
| Atlas | `atlasSize`, `terrainTextureSize`, `terrainIconSize` |
| Host extras (`vkm_` prefix) | `vkm_dimension` (-1 Nether, 0 Overworld, 1 End, mod dimensions by id), `vkm_biome` (numeric id at the camera), `vkm_lavaResistance` (1 while the eye is in lava and the viewer has fire resistance), `vkm_jitter`, `vkm_renderScale`, `vkm_displaySize`, `vkm_rtEnabled`, `vkm_dynLights[16]` |

Notes:
- The matrices are the **GL** matrices as vanilla set them, unjittered and without
  `DEPTH_REMAP`. Packs that reconstruct positions with `gbufferProjectionInverse` and
  `depth * 2 - 1` work unchanged.
- `gbufferModelView` is the camera matrix captured at the head of the terrain pass
  (`VRenderSystem.captureCameraMatrices`). The previous-frame matrices come from
  `CameraHistory`.
- Smoothed values (`eyeBrightnessSmooth`, `centerDepthSmooth`, `wetness`) use OptiFine's
  half-lives (OptiFine: `eyeBrightnessHalflife`, `centerDepthHalflife`,
  `wetnessHalflife`, `drynessHalflife`; overridable in `pack.json` `renderer`).
- `centerDepthSmooth` needs the depth at the screen center from the previous frame. The
  host copies one texel into a host-visible buffer. No stall: it is read one frame in
  flight later.

## `Pass` block (per pass, a push constant)

| Field | Meaning |
|---|---|
| `vkm_passSize` | Viewport size of this pass, after `scale` |
| `vkm_passIndex` | Index in the pass's command list |
| `vkm_isShadowPass` | 1 inside `shadow` / `SHADOW_COMP` |

`viewWidth` and `viewHeight` stay the scene size, as in OptiFine. Packs that want the pass
size use `vkm_passSize`.

## Command-list passes (M1)

What a native `POST_SCENE` or `combination` program sees besides the blocks above:
- **Fragment passes** draw one host triangle. The prelude declares
  `layout(location = 0) in vec2 vkm_texCoord;` ((0,0) bottom-left). The program declares
  its own outputs, `layout(location = i) out vec4 ...;` for target `i` of `targets`; the
  combination has one output at location 0.
- **`vkm_FragCoord`** is `gl_FragCoord` in pack space. Use it instead of `gl_FragCoord`
  in the combination, which draws into the host image in Vulkan row order (the flip point).
- **Compute passes**: the prelude declares the work-group size from `workGroups.local`
  (a program must not declare `local_size_*`) and one storage image per `writes` entry,
  named after the texture, or `<name>Out` when the pass also samples that texture (the
  sampler then reads the old image, the storage image is the new one).
- Compute passes can write the base storage formats only: RGBA8, RGBA16F, RGBA32F, R32F,
  R32UI, RGBA32UI. Integer textures are sampled as `usampler2D`.
- `motion` is in pack texcoords (previous minus current, y up), is available from `DEFERRED`
  onward, and runs the motion-vector pass only for packs that sample it.

## Geometry programs (M3)

A geometry program replaces one vanilla shader for a draw category (runtime.md). It draws
with that shader's vertex format and per-draw descriptors, so the prelude is generated from
the vanilla source: the same program compiled for two categories can see different inputs.

- **Outputs** are declared by the host as `vkmOut_<target>`, and `<target>` is a macro for it:
  `"targets": ["albedo", "normal"]` gives `vec4 albedo` and `vec4 normal` (`uvec4` for integer
  textures). A program does not write `layout(location = ...)`. A target that some program also
  samples has no macro: its name means the sampler there. Attachment 0 is the scene color, or the
  texture named by `renderer.sceneTarget`; a category without a program draws there with its
  vanilla shader.
- **Vertex stage:** `vkm_position()` is the host clip position, `vkm_mvp` and `vkm_modelView` are
  the draw's matrices (per section in the batched path). A pack must use them instead of
  `gbufferProjection * gbufferModelView`: they carry the depth remap, and the model part.
- **Fragment stage:** `vkm_alphaTest(a)` applies the live GL alpha test, where the vanilla shader had
  one. `VKM_LIGHTMAP` is defined where `lightmap` exists: entity models have none, their light is
  in `ColorModulator`.
- The vanilla uniform blocks are visible under their names (`ColorModulator`, `FogColor`, ...).
- `inputs` of geometry programs share one set, so all of them see all inputs. A geometry target
  that is also an input is read through a copy taken after `DEFERRED`; before that, the copy holds
  the previous frame. `depthtex1` and `depthtex2` hold the previous frame for programs drawn before
  they are copied.
- `VKM_ALPHA_TEST`, `VKM_LIGHTMAP` and `VKM_GTEXTURE` are defined where the vanilla shader has the
  alpha test, the lightmap and a bound texture. `VKM_SHADOW_PASS` is defined in the program `shadow`.

## `Draw` push constants (geometry stages only)

80 bytes in std430, pushed with every draw of a program, visible to all its stages. The alpha test is `vkm_alphaTest`, and the batched
path reads the slot from a vertex attribute.

| Field | Meaning | OptiFine name |
|---|---|---|
| `vkm_tint` | The GL color of the draw, for a model before the lightmap multiply | `gl_Color` of a model |
| `entityColor` | Hurt tint: red with alpha 0.3 while it lasts | `entityColor` |
| `vkm_light` | Lightmap coordinates of a model, 0..240: block, sky | `gl_MultiTexCoord1` of a model |
| `entityId` | Entity id from `entity.properties`, -1 when unmapped | `entityId` |
| `blockEntityId` | Id from `block.properties` of the block under a block entity, -1 outside one | `blockEntityId` |
| `renderStage` | `MC_RENDER_STAGE_*` of the draw | `renderStage` |
| `currentRenderedItemId` | Id from `item.properties` of the item drawn, 0 when none or unmapped | `currentRenderedItemId` |
| `vkm_material` | Offset and scale into the actor atlas at offset 64; z is 0 without maps, below 0 for the block atlas | none |

## Samplers

| Name | What | Notes |
|---|---|---|
| `gtexture` (alias `texture`, `tex`) | The bound texture of the draw | Set 1 |
| `lightmap` | Vanilla lightmap | Set 1 |
| `normals`, `specular` | `_n` / `_s` atlas textures of the bound atlas | Set 1. Raw resource-pack data, as in OptiFine; Bedrock texture sets are encoded to LabPBR for these (frontends.md). Native packs may sample `vkm_material0/1` (the neutral layout, D11) instead. |
| `colortex0..15` and the pack's own names | Pack textures | OptiFine aliases `gcolor`, `gdepth`, `gnormal`, `composite`, `gaux1..4` map to `colortex0..7` |
| `depthtex0` | Depth with everything | |
| `depthtex1` | Depth before the translucent layer, with the hand | G5 `preWater` copy, only when a program reads it |
| `depthtex2` | Depth before the hand, only when a program reads it. G5 copies before the weather; the host draws weather after the translucent layer, and packs use this texture to tell the hand apart | |
| `shadowtex0`, `shadowtex1` | Shadow depth: all, and without translucent | `sampler2DShadow` when `shadow.hardwareFiltering` |
| `shadowtex0Hw`, `shadowtex1Hw` | The same images as comparison samplers (`sampler2DShadow`), for a program that declares them so while another reads the depth | Lowered from `uniform sampler2DShadow` |
| `actorNormals`, `actorSpecular` | Maps of model textures, one rectangle each (`vkm_material`) | Geometry programs |
| `gnormalCopy` | Snapshot of a geometry target named `gnormal`, taken after `DEFERRED` | Geometry programs; avoids a name collision with the `gnormal` output |
| `shadowcolor0` to `shadowcolor7` | Shadow color targets | OptiFine alias `shadowcolor` → `shadowcolor0`, `watershadow` → `shadowtex0` |
| `normals`, `specular` in passes | The same atlases | Sampled with nearest filtering |
| `sceneColor`, `sceneDepth` | The host scene (native packs) | As in the current parser |
| `motion` | Motion vectors | Only when `MotionVectorPass` runs |

A sampler a program declares but never reads after preprocessing is dropped by reflection,
and an image nothing reads is not allocated (D7).

## Vertex inputs (geometry stages)

The prelude declares inputs per draw category (runtime.md). Missing attributes become
constants, not buffer reads.

| Name | Type | Present in | Default when missing |
|---|---|---|---|
| `vaPosition` | vec3 | all | — |
| `vaColor` | vec4 | terrain, item, particle, sky (vcol), clouds | (1,1,1,1) |
| `vaUV0` | vec2 | all textured | (0,0) |
| `vaUV2` | ivec2 | terrain, particle | the per-draw constant lightmap (`EntityRenderer.currentEntity` / `tesrLight`) |
| `vaNormal` | vec3 | entity model; terrain and item when `requires.vertex` has `normal` | (0,1,0) |
| `at_tangent` | vec4 | terrain and entity with `tangent` | (1,0,0,1) |
| `mc_midTexCoord` | vec2 | terrain and entity with `midTexCoord` | `vaUV0` |
| `mc_Entity` | vec4 | terrain with `blockId` | (-1, 0, 0, 0) |

The OptiFine lowering maps `gl_Vertex`, `gl_Color`, `gl_MultiTexCoord0/1`, `gl_Normal` and
`gl_TextureMatrix[n]` onto these (frontends.md).

## Helpers in the prelude

- `vkm_linearDepth(d)`: linear depth from a stored depth value.
- `vkm_screenToView(uv, d)` and `vkm_viewToScreen(p)`: these hide the depth convention.
  Native packs should use them, because a later switch to reversed-Z (an option discussed
  separately) would then not break them.
- `vkm_hostUV(uv)`: the texcoord of a host texture produced outside pack space. Needed for
  `sceneColor` in a pack with `renderer.rt`, because RT keeps Vulkan row order.
- `vkm_alphaTest(a)`: the alpha test used by the host epilogue. It is exposed so packs that
  write their own epilogue keep vanilla cutout behavior.
