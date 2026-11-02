# Runtime: executing a pack

How the renderer runs an IR pack, in terms of the code that exists today.

## New components

| Class (proposed) | Role |
|---|---|
| `J/shader/PackManager` | Scans `shaderpacks/` (dirs, zips, built-in), detects the format through the frontends, owns the selected pack and its option values, and drives async compilation and switching. |
| `J/vulkan/PackRuntime` | The loaded, compiled pack: images, pipelines, descriptor sets, per-pass GPU timers; records the passes. Immutable. A new option set builds a new one. In `J/vulkan` because it shares the package-private helpers of `FrameTargets` and the compute passes. |
| `pack-compiler` `plan/FramePlan` | Static schedule of command-list passes (D6): which image (main/alt/prev) each pass reads and writes. GPU-free, unit-tested. Barriers come from a small per-image state tracker in `PackRuntime`; transient aliasing is not done yet. |
| `J/shader/ProgramTable` | Category → program instance → pipeline variants. Replaces the fixed `Pipeline` fields in the renderers while a pack is active. |
| `J/shader/PackTargets` | Allocates the pack's textures, depth copies and shadow maps; recreated on resize together with `FrameTargets` (same `generation()` scheme). |
| `J/shader/ShadowPass` | D8: light-frustum culling over the arena, captured entities, records into the early command buffer. |
| `J/shader/FrameUniforms` | Fills the `Frame` and `PackUniforms` blocks once per frame. |
| `J/config/gui/option/VesuOptionPages` (Shader Packs page), `ShaderOptionsScreen` | Pack list, generated option pages, profiles, error list. |

The vanilla path keeps its current classes untouched. With no pack, `PackRuntime` is null
and every hook below is a single null check (goal 4).

## Frame sequence

Today (`FrameTargets` doc comment):
1. SCENE: the world renders into `scene`.
2. Motion vectors, then RT, then the upscale into `display`.
3. DISPLAY: the hand and the GUI.
4. Copy to the swapchain.

With a pack:

| # | Step | Hook (existing code) | Notes |
|---|---|---|---|
| 1 | `SETUP` (after load or resize), `BEGIN` | `FrameTargets.beginFrame`, before `beginRendering` | |
| 2 | Shadow pass + `SHADOW_COMP` | recorded late (step 11), submitted first | D8, see below. `SHADOW_COMP` is not an OptiFine G5 stage |
| 3 | `PREPARE` | `beginFrame` | Not an OptiFine G5 stage (Iris superset and native packs) |
| 4 | Begin pack-space rendering into the gbuffer MRT union | `beginFrame` replaces `beginRendering(scene)` | Positive viewport, host-interface.md |
| 5 | Opaque geometry: sky, clouds below the cloud layer, terrain solid/cutout, entities, block entities, selection box, block damage, particles | the existing renderers, routed through `ProgramTable` | 1.12.2 draws particles before the translucent layer |
| 6 | Depth copy → `depthtex2`, then weather and the world border | `EntityRenderer.isRainPass()` window | OptiFine `beginWeather`: rain never sees itself in `depthtex2` |
| 7 | Opaque hand (`gbuffers_hand`) in pack space | the hand moves from `renderWorld` into the world pass, before the translucent layer (OptiFine `renderHand0`). `EntityRendererM.beginHand` must **not** enter the display phase while a pack is active | |
| 8 | Depth copy → `depthtex1`, `DEFERRED`, then resume rendering | `Drawer.snapshotOpaque()`, the `TerrainRenderer` call before the translucent layer | OptiFine `preWater` + `beginWater`. Must follow steps 6–7, so the `ParticleManagerM` call site must not trigger the pack copy |
| 9 | Translucent geometry: water layer, translucent entities (Forge pass 1), clouds above the layer | | |
| 10 | Translucent hand (`gbuffers_hand_water`) | the same hand hook, after the translucent layer (OptiFine `renderHand1`) | |
| 11 | Record the shadow pass into the early command buffer | after the world pass, using this frame's captures | |
| 12 | `POST_SCENE`, then `combination` into the host scene color | `FrameTargets.enterDisplayPhase`, before the upscale | After both hand passes. `combination` uses the negative viewport (the flip point) |
| 13 | Motion vectors / RT (native packs only) / upscale | unchanged | Upscale input is the host scene color |
| 14 | DISPLAY: GUI only | unchanged | |

This is the OptiFine HD U G5 order (`Shaders.beginWeather` copies `depthtex2`, `preWater`
copies `depthtex1`, `beginWater` runs `deferred*` first; `ShadersRender.renderHand0/1`).

With an OptiFine pack, RT is off by default: the pack does its own lighting, and the RT
composite modifies the scene color, which conflicts with the pack's G-buffer. With RT on, a pack
with its own lighting is paused; only a post-only pack with `renderer.rt` runs over the RT picture.

**Where the code differs from the table.** The hand draws at step 7 (`RenderGlobalM`, head of
`renderBlockLayer(TRANSLUCENT)`: later, the chunk vertex arrays are enabled, and a first-use display
list compile would read them). `depthtex2` is copied right before it. A translucent block in the main
hand moves both hands to step 10 (return of the same call), through `hand_water`: an opaque item in
the other hand then also misses `DEFERRED`. The overlays (water, fire) stay
in vanilla's hand call. Weather draws after the translucent layer (`EntityRenderer.flushWeather`),
not at step 6. The hand is in the scene for every pack, in `0.5 +- renderer.handDepth / 2` of the depth
range (a viewport `maxDepth`, no matrix change), and vanilla's depth clear before it is skipped.
`SETUP`, `BEGIN` and `PREPARE` run at the head of the frame's command buffer.

The shadow pass is recorded alongside the world pass, not after it (next section).

## Draw categories and program routing

Every draw the renderers issue today belongs to one category. A category has a fixed
vertex format and a fallback chain of program names. The first program the pack defines
is used. If none is defined, the category renders with the vanilla pipeline inside pack
space, the way OptiFine falls back to fixed function.

| Category | Today | Vertex format | Fallback chain (G5 `Shaders.java` program backups) |
|---|---|---|---|
| sky dome, stars, horizon | `SkyRenderer` `sky`, `sky_vcol` | POSITION, POSITION_COLOR | `skybasic` → `basic` |
| sun, moon, End sky | `SkyRenderer` `sky_tex` | POSITION_TEX | `skytextured` → `textured` |
| clouds | `CloudRenderer` | POSITION_TEX_COLOR | `clouds` → `textured` |
| terrain solid / cutout / cutout_mipped | `TerrainRenderer` (batched and per-section) | BLOCK (+slot, +extended) | `terrain` → `textured_lit` → `textured` → `basic` |
| terrain translucent | `TerrainRenderer` | same | `water` → `terrain` → … |
| block damage overlay | `TerrainRenderer` / entity path | BLOCK | `damagedblock` → `terrain` |
| entities (models) | `EntityRenderer` `entity` | OLDMODEL_POSITION_TEX_NORMAL | `entities` → `textured_lit` → … |
| entities glowing (outline effect) | `EntityRenderer` | same | `entities_glowing` → `entities` |
| translucent entities (Forge pass 1) | `EntityRenderer` `entity` | OLDMODEL_POSITION_TEX_NORMAL | `entities_translucent` → `entities` |
| spider / enderman eyes | `EntityRenderer` | same | `spidereyes` → `textured` |
| armor and item glint | `EntityRenderer` `entity_flat` | POSITION_TEX | `armor_glint` → `textured` |
| dropped items, held blocks | `entity_item`, `terrain#entity` | ITEM, BLOCK | `entities` |
| block entities (TESR, End portal) | `EntityRenderer` window, `EndPortalRenderer` | model formats | `block` → `terrain` |
| beacon beam | TESR | POSITION_TEX_COLOR | `beaconbeam` → `textured` |
| entity drop shadow | `entity_shadow` | POSITION_TEX_COLOR | `entities` (drawn inside the entities window) |
| particles | `entity_particle` | PARTICLE_POSITION_TEX_COLOR_LMAP | `textured` → `basic` (lit particles: `textured_lit`) |
| weather | rain window | PARTICLE / POSITION_TEX_COLOR | `weather` → `textured_lit` |
| world border | `WorldBorderRenderer` | POSITION_TEX_COLOR | `textured_lit` |
| selection box, leash, debug lines | `DebugLinesRenderer` | POSITION_COLOR | `basic` (G5 has no `gbuffers_line`) |
| world text (name tags, signs) | `GuiRenderer` world text | POSITION_TEX_COLOR | name tags `entities`, sign text `block` (the window they are drawn in) |
| hand, held items | hand window | model formats | `hand` → `textured_lit` (opaque), `hand_water` → `hand` (translucent) |
| GUI | `GuiRenderer` | — | never routed: GUI stays in the DISPLAY phase |

**Program instance** = (program, category vertex format, preprocessed-source hash). The
same `textured_lit` program used by particles and by the world border becomes two
instances, because the inputs differ. Instances are compiled only for categories the pack
actually routes to them.

**Pipeline variants:** today's `Pipeline.PipelineState` key (blend, depth, logic op, color
mask, cull) times the instance. The pack can override blend (`blend` field). Alpha test is
a specialization constant, as in the `Draw` block. The warm-up sidecar
(`Pipeline.PIPELINE_CACHE_FILE` neighbor) gets one section per (pack id, options hash), so
a second launch with the same pack creates every variant during loading.

## Render targets in geometry stages

- OptiFine programs each declare their own `DRAWBUFFERS`. Ending and restarting rendering
  for every program change would cost a lot. Instead the **union** of all geometry targets
  of a stage is attached once, and each pipeline variant gets a color write mask covering
  only its own targets.
- Unwritten fragment outputs are not a problem: a write mask of 0 leaves the attachment
  unchanged.
- The location of target `t` is fixed for the whole stage. The lowering assigns
  `layout(location = …)` by target, not by declaration order.
- Depth: one depth attachment, copied at step 8 only when a program reads it (D7).
- A vanilla shader writes attachment 0. By default that is the scene color, so a pack whose final
  image is its own texture does not get the draws it left unrouted, unless it reads `sceneColor`.
  `renderer.sceneTarget` puts a texture there instead; the OptiFine frontend names `colortex0`.

## Vertex data (D9)

Today the terrain uses BLOCK (28 bytes), plus a slot float in the batched path (32 bytes).
With `requires.vertex` the chunk format grows:

| Attribute | Encoding | Bytes |
|---|---|---|
| normal | snorm8 × 3 + pad | 4 |
| tangent | snorm8 × 4 (w = handedness) | 4 |
| midTexCoord | unorm16 × 2 | 4 |
| blockId + flags | uint16 + uint16 | 4 |

That is up to 48 bytes per vertex. Only the requested attributes are added, rounded up to
4-byte alignment. They exist in the batched path only (`ChunkBatcher`, `VBO.extend`): the
per-section fallback keeps BLOCK and its programs see the constants.

The format changes between frames (`ChunkBatcher.applyVertexFlags`), which rebuilds all chunks.
The frame a pack starts in draws terrain with the vanilla shader, because the arena still holds
the old vertices. With RT on, or without a pack, the format is the plain one, so `STRIDE` is
32 bytes whenever RT reads the arena.

`mc_Entity` = (block id or `block.properties` alias, render layer 0–3, metadata, 1), as in G5.
The source id has 12 bits (no extended block ids). G5 passes `at_tangent` unnormalized (xyz × 32767,
w = ±32767); here it is snorm [-1, 1], which is what packs get after their `normalize`. A pack that
divides by 32767 itself sees tiny tangents. `mc_midTexCoord` is the average of the quad's four
texcoords.

- **Where the values come from:** `BlockRendererDispatcherM` tags each block's vertices in the
  unused high bytes of UV2, so the tag survives the translucent sort, and `VBO.extend` moves it
  to the attribute at upload, through the table of `PackIds`, so the GPU gets the final id, not a
  lookup.
- Normals and tangents are computed per quad from positions and UVs. That is cheap, and
  vanilla quads are planar.
- Switching packs, or changing an option that changes `requires.vertex`, rebuilds chunks.
  The machinery is the same as for the RT toggle (AUDIT M5).
- Entity formats keep their formats. Missing attributes are constants (host-interface.md).

## Shadow pass (D8)

Implemented in `J/vulkan/ShadowPass.java`. The pass has its own command buffer, submitted in the
frame's submit between the transfers and the main buffer, and it is recorded while the world pass
runs, so nothing is captured and replayed.

1. **Terrain.** At the end of each chunk layer `ChunkBatcher` draws every section in the light's box
   into the map, through the program `shadow`, as one indirect draw. Sections are culled once per
   frame. The matrices go into the upper half of the slot buffer (`SHADOW_SLOT_BASE`). Sections in
   the box that vanilla has not built, because the camera does not see them, are queued for building.
2. **Entities and block entities.** Right after an entity's main draw, `Drawer` draws it again with
   the vertices already in the ring and the light's matrices, so vanilla's entity code runs once.
   Blob shadows are left out.
3. **What the camera does not see.** The first-person player and entities the main pass culled run
   through vanilla's entity code at the end of the entity pass, with the main draw skipped.
4. **Layers.** Solid and cutout layers, entities, then `shadowtex1` is copied if read, then the
   translucent layer, blended. Culling is off, as in OptiFine.
5. **Matrices** are OptiFine's (`FrameUniforms.fillShadow`, from `Shaders.setCameraShadow`).

`SHADOW_COMP` runs with the prologue at the head of the main command buffer: the shadow command
buffer is submitted first, so the map is ready. It cannot write `shadowcolor*` yet.

Not done: mipmaps of the shadow depth (color mipmaps work), the update-rate option,
`shadow.culling`, distortion-aware cull. The cost of the entity mirror is CPU; see the README.

## Command-list passes

- A full-screen pass is one triangle, no vertex buffer. A compute pass is one dispatch.
- For every pass the loader precomputes:
  - the image used for each name (main or alt);
  - the layout transitions and barriers (`synchronization2`, as `FrameTargets` uses
    today);
  - the descriptor set per frame in flight.
- Recording a pass is then a fixed sequence of commands with no lookups (D6).
- **Transient aliasing.** Textures whose lifetimes (first write to last read within a
  frame) do not overlap share memory. `clear: false` and `history` textures are excluded.
- **Mipmaps** are regenerated only where a pass asks for them, with blits: `mipmaps` after a pass,
  `mipmapsBefore` before it, from the image the pass reads at that point.

## Scene format and HDR

`FrameTargets.COLOR_FORMAT` stays RGBA8 for the vanilla path and for the display target,
because vanilla blending relies on the [0,1] clamp. A pack opts in with `renderer.hdr: true`:

- The scene color becomes RGBA16F (`FrameTargets.HDR_SCENE_FORMAT`). Without the opt-in it
  stays RGBA8, which saves bandwidth on iGPUs.
- Scene pipelines get a variant for the format (`Pipeline.packStateKey` bit 38). The
  hand and GUI draw into the display, so they keep the RGBA8 variants.
- The scene never shares its image with the display (N2), so one copy or upscale runs.
- `combination` writes the same RGBA16F image, display-referred. The upscalers and the blit
  read it as they read RGBA8, and values above 1 clip when they reach the display. So the
  pack tonemaps in `combination`.
- The format is chosen at frame start. A change (pack switched, or paused by RT) recreates
  the targets after `vkDeviceWaitIdle`, once per change, never per frame.

Vanilla lighting and textures stay in [0,1]. What exceeds 1 is additive and modulating
blending: sun, moon, glints, and the `2 * src * dst` block damage overlay. Unlike RGBA8, the
target also keeps a NaN from a shader, so a blur can spread it: passes that read
`sceneColor` should clamp it.

## Upscaling, jitter, TAA

- Pack passes run at the render resolution (`FrameTargets.renderScale`), and `combination`
  writes the scene color, which the existing upscalers consume.
- `renderer.hostJitter: false` (the default for OptiFine packs) disables
  `CameraHistory` jitter, and with it FSR 3. FSR 1 remains available.
- `renderer.upscaler: "deny"` forces scale 1 for packs with their own TAA.

## Compilation and switching (D10)

1. **Resolve.** The `PackManager` picks a frontend and calls
   `lower(source, options, hostCaps)`, which returns the IR.
2. **Preprocess.** For each program instance: prelude + defines → `shaderc` preprocess
   only → hash.
3. **Compile** cache misses to SPIR-V on a worker pool, one `shaderc` compiler per thread. Pack
   programs skip shaderc's optimizer (`compilePackShader`): it produced invalid SPIR-V from a plain
   if-chain, and drivers optimize again.
   `ShaderSPIRVUtils` already caches SPIR-V on disk by source + include closure + defines.
   The IR text cache sits in front of it.
4. **Reflect** (`lwjgl-spvc`, bundled by the mod: Cleanroom does not ship it): used resources, outputs, push constant use. This drives D7
   and the validation of `targets` against outputs.
5. **Create pipelines** for the warm-up keys, then build the frame graph and allocate
   targets.
6. **Swap** at a frame boundary. The old runtime's resources go through
   `MemoryManager.addToFreeable`, so there is no device wait.

**Hot reload:** F3+R reloads the pack. In dev, the pack directory is also watched.

**Failures:**
- A program that fails to compile falls back along its category chain, and the error is
  listed in the GUI with `file:line` (through `#line`).
- A pack with no usable `combination`, or a failure in the frame graph, falls back to the
  vanilla path with a toast.
- A crash inside a pack frame is caught at the next launch the same way as the planned
  Graphics API crash marker: the pack is deselected, with a message.

## Resource-pack overrides of our own shaders

This is the vanilla 26.2 model, and independent of shader packs. `S/` shaders are read
through the 1.12.2 `IResourceManager` (`assets/vesu/shaders/...`) instead of
`getResourceAsStream`, and recompiled on F3+T. Two preconditions:

- the `Frame` block and named blocks also on the vanilla path;
- variants as defines. The six `terrain*` shaders collapse into `terrain` with `BATCH`,
  `SLOT` and `DEBUG_UV`.

Otherwise every new UBO field breaks resource packs.

## Performance rules

These are review criteria for every shader-pack change:

1. Without a pack, nothing new runs, nothing new is allocated, and no vertex grows. The
   README benchmark stays within ±1 %.
2. No per-frame allocation, map lookup or descriptor write in the frame graph. Everything
   is prebuilt (D6).
3. One `Frame` upload per frame, one `Pass` block per pass, push constants per draw. No
   per-draw UBO for pack data.
4. Nothing a program does not read after preprocessing is allocated, cleared, copied or
   resolved (D7). This includes `depthtex1/2` copies, history and mips.
5. `discard` exists only in alpha-tested variants (a specialization constant).
6. Geometry stages begin rendering once per stage (the MRT union). Programs switch by
   pipeline bind only.
7. The shadow pass costs no CPU per section beyond the cull, and never re-runs vanilla
   rendering (D8).
8. Compilation never blocks the frame (D10). The first launch with a pack may load longer,
   but later launches hit the SPIR-V cache, the pipeline cache and the warm-up sidecar.

**Measurement:**
- `VESU_PROFILE=1` `[gpu]` lines per pack pass (`GpuTimer` gets one mark per command
  list).
- The README benchmark path for us versus OptiFine HD U G5 on the Forge install, same pack,
  same options.
