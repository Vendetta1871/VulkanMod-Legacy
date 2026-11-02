# Frontends and the `pack-compiler` module

## Module

`pack-compiler/` is a Gradle subproject of this repo: Java 25 (the repo toolchain), with no Minecraft or Cleanroom
dependency. The mod shades it. The CLI is the same jar with a `Main`.

```
pack-compiler/src/main/java/net/vulkanmod/pack/
  ir/        IrPack, Pass, Program, TextureDef, OptionDef, Validator, JSON codec
             (J/shader/ShaderPack.java moves here)
  source/    PackSource: Dir, Zip, Jar; includes with #line (J/shader/PackFiles.java moves here)
  glsl/      Preprocessor (shaderc preprocess-only), Tokenizer/Parser, Rewriter, Reflector
  expr/      expression language for custom uniforms, `enabled` and properties `#if`
  frontend/  Frontend interface, registry, HostCaps
  optifine/  the OptiFine frontend
  postfx/    the 26.2 post_effect frontend (also the Vulcade / Vulkan PostFX manifests)
  bedrock/   the Bedrock frontend (materials + Vibrant Visuals settings)
  cli/       Main
```

```java
interface Frontend {
    String id();                                    // "native", "optifine", "postfx", "bedrock"
    boolean detect(PackSource src);
    OptionSchema options(PackSource src) throws PackException;
    IrPack lower(PackSource src, OptionValues values, HostCaps caps) throws PackException;
}
```

- `HostCaps` is plain data: device features, the 1.12.2 block/item/entity id tables,
  dimension ids, and the vertex formats per category. The mod fills it from the running
  game. The CLI loads it from a JSON dump (`vkpack caps --dump` in game), so offline runs
  emulate a real device.
- `lower` is deterministic. The mod caches its result by (pack content hash, options hash,
  caps hash).

Dependencies: Gson, LWJGL core with `shaderc` (already used by `ShaderSPIRVUtils`) and
`spvc` for reflection. Cleanroom 0.7.12-alpha does not ship `spvc`: its LWJGL 3.4.3 set is
core, jemalloc, opengl, openal, stb, sdl and freetype. Two options:
- add `org.lwjgl:lwjgl-spvc:3.4.3` with natives to `vulkanBundle`, like `vma` and `shaderc`
  (Maven Central has linux and windows natives, about 3 MB each), and extend the sharing
  regex to `lwjgl-(vulkan|vma|shaderc|spvc)`;
- or decorations-only reflection with a small SPIR-V parser of our own (bindings,
  locations, used variables), which ships no natives.

### CLI

```
vkpack detect  <pack>                         # which frontend, warnings
vkpack options <pack>                         # option schema, profiles, screens
vkpack check   <pack>... [--all-profiles] [--caps caps.json]
                                              # lower + compile + spirv-val, no GPU needed
vkpack convert <pack> -o <dir> [--profile P] [--set NAME=VALUE]...
                                              # write a native pack (options baked in)
vkpack dump-ir <pack> -o <dir>                # IR + lowered GLSL, for debugging the lowering
```

`convert` bakes the chosen option values into a native pack that authors can edit by hand.
Preserving options through conversion (`--keep-options`) is v2. It requires the lowering to
prove that a rewritten region does not depend on an option, which only works for some
packs.

`check` runs in CI over the corpus (see Testing) as a Gradle test task. It needs no GPU.

---

## OptiFine frontend

Baseline: the shader feature set of OptiFine HD U G5 for 1.12.2. Iris extensions are a v2
superset (M7). Code: `pack-compiler/.../pack/optifine/` (`OptiFineFrontend`, `GlslPreprocessor`,
`GlslLowering`, `ShaderProperties`, `ShaderScreens`) and `pack/expr/Expression`. The sections below
are the design; "OptiFine: what differs" lists where the code stops short of it.

`gradlew :pack-compiler:packCheck --args="<pack> <vanilla shaders> <out>"` writes every program as
the runtime compiles it, for `glslangValidator`; `local/` holds the corpus run.

### Detection and program discovery

- A zip or directory with a `shaders/` folder. `shaders.properties`, `block.properties`,
  `item.properties`, `entity.properties` and `lang/*.lang` are optional.
- Programs are `shaders/<name>.vsh|gsh|fsh` (plus `.csh` for Iris).
- Dimension folders (`shaders/world-1/` for the Nether, `world1/` for the End) override per
  dimension. Each dimension lowers to a separate program set inside one IR, selected by
  `vkm_dimension`.
- Program names map onto IR stages:

| OptiFine | IR |
|---|---|
| `shadow` | `shadow.programs.default` |
| `shadowcomp`, `shadowcomp1..` | `SHADOW_COMP` passes (Iris superset only) |
| `prepare`, `prepare1..` | `PREPARE` passes (Iris superset only) |
| `gbuffers_<x>` | `gbuffers.<x>` (category table in runtime.md) |
| `deferred`, `deferred1..` | `DEFERRED` passes |
| `composite`, `composite1..` | `POST_SCENE` passes |
| `final` | `combination` |

G5 has no `prepare`, `shadowcomp`, `gbuffers_line`, compute programs or `renderStage` (they
appeared in OptiFineDoc after the G5 release, 2021-01-24). A G5-parity load ignores those
files with an info message, as G5 does; the Iris superset (M7) maps them. G5 has 16
`colortex`, 16 `deferred` and 16 `composite`.

### Options

Options are scanned from the source the way OptiFine does:

- `#define NAME value // [a b c]` → enum or slider.
- `#define NAME` / `//#define NAME` → bool.
- `const` options → constants.
- `#ifdef NAME` usage marks a bool as an option.

`shaders.properties` `screen*`, `sliders` and `profile.*` become IR `screens`, slider hints
and `profiles`. `lang/<locale>.lang` (`option.X=`, `value.X.v=`, `screen.X=`) becomes
`lang/<locale>.json`.

Standard macros, defined from `HostCaps`:
- `MC_VERSION 11202`, `MC_GL_VERSION`, `MC_GLSL_VERSION`;
- `MC_OS_*`, `MC_GL_VENDOR_*`, `MC_GL_RENDERER_*`;
- `MC_RENDER_QUALITY`, `MC_SHADOW_QUALITY`, `MC_HAND_DEPTH`;
- `MC_NORMAL_MAP` and `MC_SPECULAR_MAP` when the resource pack has `_n` / `_s`.

The vendor macros report the **real** vendor. Packs use them for driver workarounds that
still apply.

### `shaders.properties` → IR

The file has its own `#if` / `#ifdef` preprocessor over options and macros, evaluated by
`expr/`.

| Key | IR |
|---|---|
| `sun`, `moon`, `vignette`, `clouds`, `underwaterOverlay`, `oldLighting`, `oldHandLight`, `dynamicHandLight` | `renderer.*` |
| `shadowTerrain`, `shadowTranslucent`, `shadowEntities`, `shadowBlockEntities` | `shadow.*` |
| `backFace.solid / cutout / cutoutMipped / translucent` | cull override per terrain layer |
| `rain.depth`, `beacon.beam.depth` | depth write override for those categories |
| `separateAo` | terrain vertex color without AO + AO brightness in `vaColor.a` (G5 `BlockModelRenderer` patch) |
| `frustum.culling`, `shadow.culling` | culling switches |
| `program.[<world>/]<name>.enabled=<expr>` | program presence / pass `enabled` |
| `blend.<program>[.<buffer>]=<src> <dst> <srcA> <dstA>` / `off` | blend override per program (per target with independent blend) |
| `alphaTest.<program>=off / <func> <ref>` | the alpha test specialization |
| `scale.<program>=<s> [<x> <y>]` | pass `scale` (and viewport offset) |
| `flip.<program>.<buffer>=<bool>` | forced main/alt flip in the schedule |
| `size.buffer.<name>=<w> <h>` | texture `size` |
| `texture.<stage>.<name>=<path>` and `texture.noise` | `images` bound for that stage |
| `uniform.<type>.<name>=<expr>`, `variable.<type>.<name>=<expr>` | `uniforms` (variables are not exposed to GLSL) |

### ID maps

- **`block.properties`**:
  - `block.<numeric-id>=<token> ...`, where a token is `[domain:]name` or a numeric id, with
    `:<meta>[,<meta>]` or `:<prop>=<v>[,<v>]` selectors;
  - `layer.solid|cutout_mipped|cutout|translucent=<ids>` forces a render layer;
  - read from the shader pack and from mods' `assets/<modid>/shaders/block.properties`,
    with the same `#if` preprocessor over options;
  - flattened into a table over (block state) → id;
  - applied at chunk build time (runtime.md, "Vertex data");
  - `mc_Entity` = (id, render layer 0–3, metadata, 1). Without the file, `x` is the legacy
    numeric block id; 1.12.2 packs test `mc_Entity.x == 18` and similar.
- **`item.properties`**: `heldItemId` and `heldItemId2`.
- **`entity.properties`**: `entityId`, set per draw from `EntityRenderer.currentEntity`.
- **Block entity ids**: `blockEntityId` in the TESR window.

### Directives in the source (after preprocessing)

| Directive | IR |
|---|---|
| `/* DRAWBUFFERS:0127 */`, `/* RENDERTARGETS: 0,1,12 */` | `targets` of the program |
| `const int colortexNFormat = RGBA16F;` (often inside a comment, as OptiFine reads it) | texture `format` |
| `const bool colortexNClear`, `const vec4 colortexNClearColor`, `const bool colortexNMipmapEnabled` | texture `clear`, `mipmaps` |
| `shadowMapResolution`, `shadowDistance`, `shadowIntervalSize`, `sunPathRotation`, `shadowHardwareFiltering`, `shadowtexNNearest`, `generateShadowMipmap` | `shadow.*` |
| `noiseTextureResolution` | `noisetex` |
| `eyeBrightnessHalflife`, `wetnessHalflife`, `drynessHalflife`, `centerDepthHalflife` | `renderer` smoothing |
| `ambientOcclusionLevel` | terrain AO strength |

### GLSL lowering

Input: the preprocessed program, any `#version` from 110 to 460, compatibility profile.
Output: Vulkan GLSL 450 for the host prelude.

| OptiFine / GL | Lowered to |
|---|---|
| `#version 1xx` / `#extension GL_EXT_gpu_shader4` … | `#version 450` plus the prelude. Extensions are dropped when core in 450. |
| `attribute` / `varying` (VS), `varying` (FS) | `in` / `out` with `layout(location)` assigned **by name** across the stages of one program |
| `gl_Vertex`, `gl_Color`, `gl_Normal`, `gl_MultiTexCoord0`, `gl_MultiTexCoord1` | `vec4(vaPosition,1)`, `vaColor`, `vaNormal`, `vec4(vaUV0,0,1)`, `vec4(vaUV2,0,1)` |
| `gl_ModelViewMatrix`, `gl_ProjectionMatrix`, `gl_ModelViewProjectionMatrix`, `gl_NormalMatrix`, `*Inverse` | per-draw host matrices (`Draw` block: model-view, `Frame`: projection), with inverses computed in the prelude only if used |
| `gl_TextureMatrix[0]`, `[1]` | texture matrix (glint scroll) / lightmap matrix (scale 1/256 + 1/32 bias, as vanilla sets it) |
| `ftransform()` | the host clip transform (with `DEPTH_REMAP` and the pack-space viewport) |
| `gl_FrontColor`, `gl_TexCoord[n]`, `gl_FogFragCoord` | named varyings |
| `gl_Fog.*` | `fogColor`, `fogDensity`, `fogStart`, `fogEnd`, `fogMode` |
| `gl_FragColor`, `gl_FragData[n]` | `layout(location = k) out vec4`, where k comes from the stage's target union (runtime.md) |
| `texture2D`, `texture2DLod`, `texture2DGrad(ARB)`, `texture3D`, `texture2DProj`, `shadow2D`, `shadow2DLod` | `texture`, `textureLod`, `textureGrad`, `textureProj`. `shadow2D(s, c)` becomes `vec4(texture(s, c))`, because the GL version returns a vec4. |
| loose `uniform` declarations | removed when the name is a host or custom uniform. An unknown name becomes a zero constant plus a warning, which matches OptiFine's unset uniforms. |
| sampler declarations | removed (the prelude declares them). Aliases are resolved (`gcolor` → `colortex0`, …). |
| `gl_FragCoord` in `final` | `vkm_FragCoord` (the flip point, host-interface.md) |
| end of every FS with alpha test | epilogue `if (!vkm_alphaTest(out0.a)) discard;` (specialization constant) |

Driver leniency: packs written against NVIDIA's lenient GL compiler contain code glslang
rejects, such as implicit conversions GLSL 1.x does not allow, missing `return`, or
`const` initialized from non-constants. A **fix-up list** grows from corpus failures, and
each fix-up gets a test. Iris's transformer handles the same corpus, so check its list
first.

Parser choice: a tokenizer plus a declaration-level parser of our own. glsl-transformer
(now https://github.com/IrisShaders/glsl-transformer) is AGPL-3.0, which an LGPL-3.0 mod
cannot link or derive from. Iris itself is LGPL-3.0, so its fix-up list and logic may be
ported with attribution. Statement bodies do not need parsing for anything above except the
`gl_*` identifier rewrites, which are token-level. *(README Q3)*

### OptiFine: what differs

- Directives are read after preprocessing, so a `DRAWBUFFERS` in an inactive branch does not count.
  G5 reads them line by line. A draw buffer no `gl_FragData` writes is not attached.
- `#if` runs in a preprocessor of our own (`GlslPreprocessor`), and `#define` lines stay for glslang.
- Unknown uniforms and attributes become zero constants, with a warning. Iris-only uniforms of
  packs that also target Iris land here (`relativeEyePosition`, `darknessFactor`, ...).
- `.gsh`, `.tcs` and `.tes` work on geometry programs, `shadow` and full-screen passes. A `.tes`
  without `.tcs` gets GL's default control stage: patches of three, levels of 1. Each pair of stages has its
  own varying table, and the depth remap sits in the last stage before the rasterizer, so earlier
  stages see GL depth. The old spellings work: `gl_PositionIn`, `gl_TexCoordIn`, `gl_FrontColorIn`,
  `gl_VerticesIn`, `maxVerticesOut`, `varying in`, `varying out`, and `gl_in[i].gl_FrontColor` or
  `.gl_TexCoord` of a compatibility profile. `gl_ModelViewMatrix` and friends are the camera's
  outside the vertex stage: a draw's own matrices live in vertex attributes.
- `<buffer>MipmapEnabled` holds for its program: every other pass reads that buffer at level 0 (`baseLevelInputs`), as in OptiFine. Otherwise `texture()` takes a stale level from the quad's derivatives, which shows as dark 2x2 blocks.
- A tessellation program draws patches of three, from triangle lists only. A geometry shader
  takes any triangle topology. Line draws and strips keep the vanilla shaders under such a program.
- Interface blocks between stages take locations from the varying table, matched by block name as
  in GL. A block of pass-through varyings survives the default control stage too.
- `#version` below 450 keeps no semantics of its own: the source compiles as 450.
- `size.buffer` with a viewport offset is not read. An option named in `size.buffer` is expanded.
- `texture.<stage>.*` and `texture.noise` follow OptiFine's defaults: GL_NEAREST and GL_REPEAT
  unless `<file>.mcmeta` says otherwise.
- `colortex0` clears to the fog color unless the pack sets `colortex0ClearColor`.
- Local variables declared without a value start at zero, as they do on GL drivers. A SPIR-V
  variable would be undefined, and packs rely on this without knowing it.
- Varyings share locations by component where GLSL allows it: Vulkan counts locations, GL counts
  components. More than 32 locations is a load error. Past that limit a GPU can fault at draw time.
- `uniform.*` that names an unknown variable is dropped and reads 0, as in G5. `biome_category`,
  `biome_precipitation`, `CAT_*` and `PPT_*` go beyond G5: they come from the Forge biome dictionary.
- `oldLighting=false` removes the baked face shade from chunk colors, `separateAo=true` moves AO to
  the vertex alpha. Both rebuild the chunks. `backFace.*`, `rain.depth`, `beacon.beam.depth`,
  `dynamicHandLight`, `oldHandLight`, `sun`, `moon`, `vignette`, `underwaterOverlay` and `clouds=off`
  are honored.
- `alphaTest.<program>=<func> <ref>` compares in the epilogue, `off` removes the test.
- `MC_GL_VENDOR_*` and `MC_GL_RENDERER_*` come from the Vulkan device.
- `centerDepthSmooth` is read back from the depth center with OptiFine's fade, only for packs that
  use it. `atlasSize` is still 0.
- Options change through "Pack options...", saved in `shaderpacks/<pack>.txt`. Options listed in
  `sliders` are sliders, the others cycle. A change rebuilds the pack in the background.
- `normals` and `specular` are the raw `_n` and `_s` of the resource packs in the block atlas
  layout, first animation frame, nearest-sampled. Entity textures get the neutral value.
- Dimension folders become scene variants, built when the dimension is first entered.

### Parity matrix (OptiFine HD U G5 shader features)

| Feature | Milestone |
|---|---|
| gbuffers programs + fallback chains | M3 |
| composite 0..15, deferred 0..15, final | M1 (native), M5 (OptiFine) |
| prepare, setup, begin, shadowcomp, renderStage | not in G5: Iris superset (M7) |
| compute programs, custom images, SSBOs, `indirect.*`, `shadow.culling`, `customTexture.*` | Iris superset |
| `shadowcolor2` to `shadowcolor7` | Iris superset |
| writing shadow colors after the shadow pass, `shadowcolorimgN` from compute | Iris superset, as the pack texture `shadowworkN` |
| `colorimgN` and `shadowcolorimgN` from fragment, gbuffers and shadow programs | Iris superset, `testpacks/pass-stages`. A store lands on the current image and the image spends the pass in GENERAL. `shadowcolorimgN` from the `shadow` program itself is lost: the copy to `shadowworkN` runs after it, with a warning |
| `imageStore` and `imageLoad` past an edge | `robustImageAccess` where the device has it: dropped, as GL does |
| shadow map, shadowtex0/1, shadowcolor0/1, hardware filtering, mipmaps | M4 |
| colortex0..15 formats, clear, mipmaps, flip, size, scale | M1–M5 |
| depthtex0..2 | M3 |
| custom textures, noise texture | M5 |
| options, sliders, profiles, screens, lang | M5 |
| custom uniforms / variables | M5 |
| block / item / entity ID maps, `mc_Entity`, `mc_midTexCoord`, `at_tangent` | M3 (attributes), M5 (maps) |
| geometry and tessellation stages | gbuffers and `shadow`; tested by `testpacks/geometry` |
| `flip.<program>.<buffer>` | M5 |
| raw 1D, 2D and 3D textures | Iris syntax; tested by `testpacks/resources` |
| `blend.<program>.<buffer>`, also on composite passes | M5 |
| `program.<dimension>/<name>.enabled` per dimension | M5 |
| `_n` / `_s` resource-pack textures | P2 + M5 |
| dimension folders | M5 |
| per-program blend / alpha test | M5 |
| back face per layer, dynamic hand light, old lighting, separateAo | M5 |
| `sky`, `stars`, `moon`, `sun`, `weather` switches | M5, Iris superset |

---

## 26.2 `post_effect` frontend

The input is the format vanilla 26.2 and Vulcade use. Code: `pack-compiler/.../pack/postfx/`
(`PostEffectFrontend`, `PostShaderLowering`). The schema below is read from the 26.2 client
jar (`PostChainConfig`, `PostChain`, `PostPass`, the `post_effect/*.json` and `shaders/post/*`
assets). The 1.21.x schema (`program`, flat `uniforms`, `aux`, `widthScale`, `swappable`) is
gone.

Vulkan PostFX is not covered: its chains take a `VpfxBuiltins` block that its own mod fills,
and its license forbids redistribution, so it has no reference pack here.

- **Detection:** a `post_effect/` folder, `assets/<namespace>/post_effect/`, or the Vulcade
  manifest `vulcade.json`. Without a manifest nothing names the chain to run, so the first
  one by path runs, with a warning.
- **Manifest:** `default_profile`, and `profiles` as profile → scene (`world`, `underwater`,
  `nether`, `end`) → list of chain ids, run in sequence. Each scene other than `world`
  becomes an IR variant (`scenes` in pack-format.md).
- **`targets`** (optional map id → target): `width`, `height` (optional, default the screen
  size), `persistent` (default false), `clear_color` (ARGB int or `[r,g,b,a]` floats).
  There is no format field: 26.2 always allocates RGBA8_UNORM with a depth buffer, so IR
  textures are RGBA8. Ids not declared here are external and must be in the context's set:
  `minecraft:main`, `translucent`, `item_entity`, `particles`, `weather`, `clouds`,
  `entity_outline`. `minecraft:main` is the host `sceneColor`, and its depth
  (`use_depth_buffer`) is `sceneDepth`.
- **`passes[]`:** `vertex_shader`, `fragment_shader` and `output` (required), `inputs[]`,
  `uniforms`. These become IR passes.
  - An input is exactly one of: a target (`sampler_name`, `target`, `use_depth_buffer`,
    `bilinear`) or a texture (`sampler_name`, `location`, `width`, `height` required,
    `bilinear`; loaded from `textures/effect/<path>.png`). Sampler names are unique per pass.
  - `uniforms` is a map block name → `[{"name", "type", "value"}]`, packed std140 in array
    order. Types: `int`, `ivec3`, `float`, `vec2`, `vec3`, `vec4`, `matrix4x4` (16 floats).
    The values are static, so they become `const` declarations of that pass's program,
    matched to the block's members by position (`name` is optional). Passes with equal
    constants share a program.
  - The last pass writing `minecraft:main` becomes `combination`, and passes after it are
    skipped with a warning.
  - Earlier writes to `main` go to the IR texture `pfx_main_t`, and later reads follow it.
  - A pass may not sample its own output, as in 26.2.
  - `bilinear` is a per-input filter in IR (`filters`). Its default is nearest, so it is
    written for every input.
- **Shaders:** `#version 330` with `#moj_import <minecraft:...>` (from `shaders/include/`;
  quoted imports are relative).
  - Engine blocks bound on every pass: `Projection`, `Fog`, `Globals` (`CameraBlockPos`,
    `CameraOffset`, `ScreenSize`, `GlintAlpha`, `GameTime`, `MenuBlurRadius`, `UseRgss`),
    `Lighting`. Their fields map onto `Frame` through macros. `Frame` is declared as the
    instance `vkm_frame` (`renderer.frameInstance`), because a post shader may declare its own
    `near` or `fogColor`. The vanilla includes for these blocks are built in, without their
    helper functions; `minecraft:post/blit` is built in too. Other vanilla shaders must be
    in the pack, since the mod does not ship Mojang's.
  - `SamplerInfo` is `OutSize` followed by one `vec2` size per input, in input order. It
    maps onto `vkm_passSize` and the inputs' sizes.
  - Input `X` is sampled as `XSampler`; these become declared inputs.
  - The vertex shader is `core/screenquad.vsh`: one procedural triangle
    (`draw(3, 1, 0, 0)`), texcoord (0,0) at NDC (-1,-1), and targets store row 0 at the
    bottom. Pack space already matches this GL convention.
  - The vertex shader `core/screenquad` becomes the host triangle. Others are lowered too
    and run as the pass's `vertex` program.
  - **Depth is reversed** in 26.2 (near = 1, far = 0, `Projection.getMatrix`). A generated
    first pass stores `1 - sceneDepth` in an R32F texture, which the `use_depth_buffer`
    inputs sample, so the sky is exactly 0 as such packs test for. Rewriting the `texture()`
    calls would miss a sampler passed to a function.
- **Scenes** are chosen per frame like Vulcade's `resolveScene`: water at the camera first,
  then the dimension. All variants are compiled when the pack loads; only the running one
  holds images, and a change waits for the GPU once.

**Differences from 26.2:**
- The chain runs at the render resolution, so a pixel radius covers more of the screen at a
  render scale below 1.
- The hand is in `sceneDepth`, in the near part of the depth range (`renderer.handDepth`).
- `minecraft:translucent`, `item_entity`, `particles`, `weather`, `clouds` and
  `entity_outline` do not exist here: a chain that reads them fails to load with that target
  in its message.
- Depth of a chain's own target does not exist either.
- The profile comes from the manifest's default, or `VULKANMOD_PACK_PROFILE`; the GUI arrives
  with options (M5).

---

## Bedrock frontend

> Not in this build: the Bedrock frontend is held back until its bugs are fixed. A Bedrock pack is listed as not supported. The text below describes the work in progress.

Only the portable part of Bedrock packs. Shaders are out of scope (README non-goals). Code:
`pack-compiler/.../pack/bedrock/`, `J/shader/BedrockMaterials.java`.

**What runs today.** Such a pack has no shaders, so it lowers to a deferred PBR pipeline of ours
(`BedrockPrograms`), written after the structure of vanilla Vibrant Visuals and checked against it
(Bedrock 26.52 on Mesa, the same scene built in both games, `local/ref/vv.sh`, `local/ref/vvcmp.py`):

- **Sky and fog:** a Rayleigh term of `sky_zenith_color` and `sky_horizon_color` with a
  Henyey-Greenstein Mie term around the sun. `horizon_blend_stops` read as min, start, mie_start, max.
- **Light:** GGX and Lambert from `lighting/global.json`. Sky ambient is a desaturated zenith term, with
  `ambient` as a floor. Direct light has a gain (`VV_SUN_GAIN`) measured from the game's picture.
- **Exposure:** a 1x1 history texture holds the log mean of the 80..100 percentile of a 16x9 luminance
  grid, clamped to 2^-3..2^4 and adapted over a second (`average_luminance.json`, `tone_mapping.json`
  of the game). A pack cannot change these files.
- **Tone mapping:** the `generic` operator (a curve on the peak channel with crosstalk), contrast
  around the average, saturation, sRGB. Its parameters are CPU-side in the game, so a=1.05, white=32
  and crosstalk=32 are fitted to its sky. The other operators are `aces` and `reinhard`.
- **Rain:** the game's `rain_sky_color`, no direct light, weather fog from the biome's fog file.
- **Water:** biome surface color and transparency over the scene, sky reflection by Fresnel.
- **Sun and moon** are additive HDR discs; stars come from the gray vertices of the sky category.

- **Biomes:** `biomes/*.client_biome.json` name lighting, atmosphere, grading, fog and water color by
  identifier. Biomes that share settings share a profile. A `BEGIN` pass evaluates the key-framed
  curves of the camera's biome (`vkm_biome`) once a frame into a 13x1 texture, every program reads
  that texture, and its history blends biomes over about a quarter of a second.
- **Bloom:** the game's pyramid: bright pixels, four levels down, a tent up, added at 0.02
  (`bloom_configuration.shared.json` of the game).

Read from the pack: `lighting/`, `atmospherics/`, `color_grading/`, `fogs/`, `biomes/`,
`biomes_client.json` (default biome: fog identifier, water surface) and `textures/blocks/*.texture_set.json`
(MER, MERS, normal, heightmap; PNG and TGA). The `color` of a texture set replaces the sprite in the
block atlas with its mips, while the pack is on. Animated sprites and `grass_side` (Java draws it as two
sprites) keep the 1.12 texture.

Measured difference to the game: about 24/255 mean over 11 scenes in the plains scene, a large part
of it the hand, the HUD and rain particles, which differ by content. Sky values agree within about 7/255.

Not done here: the neutral layout in `RtMaterials`.

**Input:** `.mcpack`, `.zip` or a directory with `manifest.json` (a `resources` module,
`capabilities: ["pbr"]`).

**Texture sets.** Each `textures/**/<name>.texture_set.json` (`minecraft:texture_set`) has:
- `color`;
- `metalness_emissive_roughness` or `metalness_emissive_roughness_subsurface`;
- `normal` or `heightmap`.

`format_version` is `1.16.100` (`1.21.30` with MERS). `color` is required. MERS and plain
MER are mutually exclusive; where metalness and subsurface overlap in a pixel, the higher
value wins (a tie goes to subsurface). `normal` and `heightmap` are mutually exclusive, and
heightmaps are invalid for items. A missing texture-set value falls back to
`pbr/global.json` (`minecraft:pbr_fallback_settings`).

Each value is a file or a uniform value (hex or array). The frontend decodes them into the
**neutral material layout** (D11):

| Neutral texture | Channels |
|---|---|
| `material0` (RGBA8) | perceptual roughness, metalness, emissive, dielectric F0 |
| `material1` (RGBA8, only when any source has it) | subsurface, porosity, AO, flags |
| `normal` (RGBA8) | tangent-space normal xy, height, AO |

Decoding rules:
- LabPBR `_s` / `_n` decode into the same layout. Metal ids 230–254 become metalness 1
  with the predefined F0, and 255 means albedo as F0.
- A Bedrock heightmap is converted to normal xy at stitch time and also kept in `normal.b`.
- MER(S) roughness: Microsoft defines it only as 0–1 grayscale (0 = smooth). Whether it is
  perceptual or linear is not specified; we read it as perceptual. The game's deferred shader
  computes alpha as roughness squared (README Q7).
- `RtMaterials` reads the neutral layout. The OptiFine `specular` and `normals` samplers get
  the resource pack's raw `_s` / `_n` atlas, exactly as OptiFine does. Only Bedrock texture
  sets are encoded to LabPBR for them, because OptiFine packs interpret `_s` in their own
  format.

**Texture names.** Bedrock kept many legacy Java names (`textures/blocks/log_oak.png`,
`planks_oak`, `wool_colored_red`, …), so the mapping is mostly identity. A table covers the
rest, and the loader logs unmatched textures. A Bedrock pack can also serve its color
textures as a virtual 1.12.2 resource pack.

**Vibrant Visuals settings.** The pack's `manifest.json` has `capabilities: ["pbr"]`.
The settings JSON become IR `renderer.settings`, which the host lighting consumes:
- `atmospherics/` (`minecraft:atmosphere_settings`);
- `color_grading/` (grading, temperature, tone-mapping operator);
- `lighting/` (sun, moon, flash, ambient, sky);
- `local_lighting/local_lighting.json` (per-block light color and type);
- `water/`;
- `shadows/global.json` (style, texel size);
- `cubemaps/`;
- `fogs/` (distance and volumetric fog), selected per biome by `minecraft:fog_identifier`.

Biomes select settings through client-biome components, and the reserved global files are
the defaults. Any `optkeyframe` field may be written as `{"<t>": value, ...}`, where t in [0,1]
is the time of day (0 = noon, 0.25 = sunset); interpolation is linear. Settings blend between
biomes, except the tone-mapping operator, `orbital_offset_degrees`, caustics and
`waves.enabled`. This is roadmap P3, and it applies to RT and raster:

| Settings | Consumer |
|---|---|
| sun, moon and ambient light | sky and lighting |
| atmosphere scattering | sky |
| fog | fog |
| water absorption and scattering | the RT water (already has its own absorption and scattering) |
| exposure, tonemap, color grading | the HDR tonemap step (M1) |

**Effects.** What each settings file drives:

| File | Effect |
|---|---|
| `shadows/global.json` | Blocky style: shading by texel centers, `texel_size` of them per block |
| `pbr/global.json` | The material of blocks and actors without a texture set |
| `water/water.json`, per biome through `minecraft:water_identifier` | Absorption from `particle_concentrations`, `biome_water_color_contribution` |
| `water/water.json`, the default one | Waves and caustics; the game cannot blend caustics between biomes either |
| `fogs/*.json` `volumetric` | Light shafts and fog: 12 steps at half size through the shadow map, Henyey-Greenstein phase, one absorption channel as in the game |
| `lighting/*.json` `sky`, `emissive`, `orbital_offset_degrees` | Ambient scale, emissive desaturation, tilt of the orbit (a plain number only) |
| `cubemaps/*.json` with `textures/environment/overworld_cubemap/cubemap_0..5.png` | The sky picture and its light |
| `local_lighting/local_lighting.json`, `point_lights/global.json` | Colors of the point lights; without either, the game's list |

Water and glossy surfaces reflect through a screen-space trace, with the sky as fallback.
Caustics are generated unless `caustics.texture` names a PNG, since the game's texture is its asset.
Texture sets of `textures/items` join the block atlas, where 1.12 keeps item sprites. Those of
`textures/entity/**` go to a separate actor atlas, found by the path of the Java texture.

Differences from the game:

- Point lights use the game's BRDF and falloff and its clusters, with other limits: one light per
  cell of 4 blocks inside a 128x64x128 volume around the camera, 8 lights per cluster of 8 blocks
  against the game's 24. The game's shadow atlas is a walk through the voxels of the shadow pass,
  for the first 4 lights of a cluster, plus a short depth march for entities, for the first 2.
  The rest are unshadowed, held back only by the vanilla level. Partial blocks fill their voxel.
  Light volumetrics are not drawn. Every section must reach the shadow pass, so VV runs with
  `shadow.culling` off. In the reference night scene the wall next to the torch stays brighter
  and yellower than in the game.
- `textures/particle` sets and per-biome cubemaps are not read. Cubemap faces and caustic
  textures must be PNG. The order of the faces is assumed, not checked against the game.
- Subsurface scattering is stored and unused. The End flash is not drawn.

Exact schema: Microsoft Learn "Vibrant Visuals resource packs" and the wiki's configuration
values (README references).

---

## Testing

- **Offline (CI, no GPU):** `vkpack check` over every corpus pack, all profiles, and the
  `HostCaps` dumps of three devices (NVIDIA, Intel UHD 620, an AMD/Mesa dump). The test
  fails on a new lowering or compile failure.
- **Feature test packs:** `testpacks/` holds one small pack per feature:
  - DRAWBUFFERS order, flip, scale, history, mipmaps;
  - shadow;
  - custom uniforms, options;
  - ID maps, each dimension folder.

  Each one runs in `AutoTest` with a fixed scene and a stored expected screenshot.
- **Corpus:** OptiFine packs with stated 1.12.2 support, pinned (checked 2026-10-29):

  | Pack | Version | Source |
  |---|---|---|
  | BSL | v10.1.7 | capttatsu.com/bslshaders → Modrinth / CurseForge |
  | Sildur's Vibrant | v2.02 | sildurs-shaders.github.io/downloads |
  | Sildur's Enhanced Default | v1.20 | sildurs-shaders.github.io/downloads |
  | Chocapic13 | V9 | CurseForge (`chocapic13-shaders`) |
  | SEUS Renewed | 1.0.1 | sonicether.com/seus |
  | Continuum | 2.0.5 | continuum.graphics/downloads |
  | KUDA | 6.5.26 | Modrinth `kuda-shaders` (maintainer changed from RRe36 to T1taniumF0rge) |
  | Nostalgia | v2.3 | GitHub `rre36/NostalgiaShader_Mirror` |

  Only Nostalgia dropped 1.12.2 (v3.0 is 1.16+). Continuum's site states only "up to 1.19.0"
  for 2.0.x; 1.12.2 works but is community-attested, not official. "Continuum 2.0 Redux" is
  an Iris edit, not this pack.
- **Visual comparison:** reference shots come from the Forge 14.23.5.2860 + OptiFine HD U G5
  install (the README benchmark setup), at the same seed, path and ticks as `AutoTest`. The
  metric is SSIM per shot, with a per-pack threshold. Exact equality is impossible because
  driver precision differs.
- **Performance:** the README benchmark, us against OptiFine on the same pack and options
  (goal 3). Results go into the pack compatibility table in the README once M5 lands.
