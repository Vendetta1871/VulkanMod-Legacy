# Native pack format v1 (the IR)

This is both the format authors write by hand and the output of every frontend. It evolves
the unwired parser in `J/shader/ShaderPack.java`: `textures`, `commandLists[].stage`,
`passes` and `combination` keep their meaning, and v1 adds geometry stages, shadow, options,
custom uniforms and requirements.

## Layout

```
<pack>/                      directory or .zip in shaderpacks/, or built in under
  pack.json                  /assets/vesu/shaderpacks/<id>/
  lang/en_us.json            option and screen labels (optional)
  programs/**/*.vsh|gsh|fsh|csh
  lib/**/*.glsl              #include targets
  textures/**                custom textures
```

Detection: a `pack.json` with `"format": 1` at the root. A Vulkan PostFX pack also has a
`pack.json`, but without `format`, so the field is required.

Includes: `#include "x"` resolves relative to the including file, and `#include <x>`
resolves against the pack root (`PackFiles.expandIncludes`). Expansion emits
`#line <n> "<file>"` (with `GL_GOOGLE_cpp_style_line_directive`) instead of the current
`// begin include` comments, so compiler errors point at the original file and line.

## `pack.json`

```jsonc
{
  "format": 1,
  "id": "example",                       // [a-z0-9_-], unique in shaderpacks/
  "name": "Example Pack",
  "version": "1.0.0",

  "requires": {
    "features": ["geometryShader"],      // see "Requirements"
    "vertex": ["normal", "tangent", "midTexCoord", "blockId"]
  },

  "options": [ /* see "Options" */ ],
  "profiles": { "LOW": { "SHADOWS": false }, "HIGH": { "SHADOWS": true, "SHADOW_RES": 2048 } },
  "screens": { "main": ["SHADOWS", "SHADOW_RES", "<empty>", "[lighting]"], "lighting": ["SUN_STRENGTH"] },

  "textures": [
    { "name": "gAlbedo", "format": "RGBA8",  "scale": 1.0, "clear": [0, 0, 0, 0] },
    { "name": "gNormal", "format": "RGBA16F" },
    { "name": "bloom",   "format": "RGBA16F", "scale": 0.5, "mipmaps": true, "clear": false },
    { "name": "taa",     "format": "RGBA16F", "history": true }
  ],
  "images": {                             // custom textures read from the pack
    "noise": { "path": "textures/noise.png", "filter": "linear", "wrap": "repeat" }
  },

  "uniforms": {                           // pack-defined, evaluated once per frame on the CPU
    "float sunVisibility": "clamp(dot(sunPosition, upPosition) * 0.01, 0.0, 1.0)",
    "float wetSmooth": "smooth(1, wetness, 2.0, 5.0)"
  },

  "shadow": {
    "resolution": 2048, "distance": 120.0, "intervalSize": 2.0, "sunPathRotation": -40.0,
    "depthTargets": 2,                    // shadowtex0 (all), shadowtex1 (no translucent)
    "colorTargets": [ { "name": "shadowcolor0", "format": "RGBA8" } ],
    "hardwareFiltering": true,            // shadowtex*: sampler2DShadow compare sampling
    "entities": true, "blockEntities": true,
    "programs": { "default": { "vertex": "programs/shadow.vsh", "fragment": "programs/shadow.fsh" } }
  },

  "gbuffers": {                           // geometry programs, see runtime.md for the categories
    "terrain": { "vertex": "programs/terrain.vsh", "fragment": "programs/terrain.fsh",
                 "targets": ["gAlbedo", "gNormal"] },
    "water":   { "vertex": "programs/water.vsh", "fragment": "programs/water.fsh",
                 "targets": ["gAlbedo", "gNormal"], "blend": "default" },
    "textured":{ "vertex": "programs/basic.vsh", "fragment": "programs/basic.fsh",
                 "targets": ["gAlbedo"] }
  },

  "commandLists": [
    { "stage": "DEFERRED",   "passes": [ { "name": "lighting", "fragment": "programs/deferred.fsh",
                                           "inputs": ["gAlbedo", "gNormal", "sceneDepth", "shadowtex0"],
                                           "targets": ["sceneHdr"] } ] },
    { "stage": "POST_SCENE", "passes": [ { "name": "bloomDown", "compute": "programs/bloom.csh",
                                           "inputs": ["sceneHdr"], "writes": ["bloom"],
                                           "workGroups": { "scale": 0.5, "local": [8, 8] } } ] }
  ],
  "combination": { "fragment": "programs/final.fsh", "inputs": ["sceneHdr", "bloom"] },

  "scenes": {                             // optional whole-pack variants: underwater, nether, end
    "nether": { "textures": [ /* ... */ ], "commandLists": [ /* ... */ ], "combination": { /* ... */ } }
  },

  "renderer": {                           // host features the pack replaces or keeps
    "sun": false, "moon": true, "vanillaClouds": true, "vignette": false,
    "underwaterOverlay": true, "handDepth": 0.125,
    "hostJitter": false, "upscaler": "allow",   // allow | deny (pack does its own TAA)
    "hdr": false,                         // true: RGBA16F scene, combination tonemaps (runtime.md)
    "settings": { /* Bedrock-style lighting/fog/water, see frontends.md */ }
  },

  "materials": { "format": "labpbr" }    // labpbr | bedrock | none
}
```

### Stages

The order is the frame order. Geometry stages hold programs that vanilla draws run through,
and command-list stages hold full-screen or compute passes.

| Stage | Kind | Runs | OptiFine / Iris counterpart |
|---|---|---|---|
| `SETUP` | command list (compute) | once after load and after every resize | Iris `setup` |
| `BEGIN` | command list | every frame, before anything else | Iris `begin` |
| `shadow` | geometry | the light-view pass (D8) | `shadow` |
| `SHADOW_COMP` | command list | after the shadow pass | `shadowcomp` (Iris, OptiFine after G5) |
| `PREPARE` | command list | before the scene geometry | `prepare` (Iris, OptiFine after G5) |
| `gbuffers` (opaque categories) | geometry | sky, terrain, entities, particles, then weather and the world border | `gbuffers_*`, `gbuffers_weather` |
| `gbuffers` (opaque hand) | geometry | first-person hand, before `DEFERRED` | `gbuffers_hand` |
| `DEFERRED` | command list | after opaque and the opaque hand, before translucent | `deferred*` |
| `gbuffers` (translucent categories) | geometry | water, translucent | `gbuffers_water` |
| `gbuffers` (translucent hand) | geometry | translucent held items, after the translucent layer | `gbuffers_hand_water` |
| `POST_SCENE` | command list | after all geometry | `composite*` |
| `combination` | one pass | writes the host scene color | `final` |

A command list whose stage the runtime does not support yet (`SHADOW_COMP`) is skipped with a
warning, not a load failure. `SETUP`, `BEGIN` and `PREPARE` run before the scene and cannot sample
host textures. `hand_water` is a program of its own, for items of the translucent layer.

### Passes

| Field | Meaning |
|---|---|
| `fragment` or `compute` | Exactly one. A fragment pass draws one full-screen triangle (host vertex stage unless `vertex` is given). |
| `geometry`, `tessControl`, `tessEval` | Optional stages of a fragment pass with its own `vertex`, the tessellation pair together. The triangle is one patch of three. |
| `inputs` | Textures sampled: declared, host (see host-interface.md) or `images`. |
| `baseLevelInputs` | Inputs read at level 0 only, though another pass made mips of them. OptiFine's `MipmapEnabled` holds per program. |
| `filters` | Per-input override of the texture's `filter`: `{ "name": "nearest" }`. |
| `targets` | Color attachments of a fragment pass, in output location order. |
| `writes` | Storage images a compute pass writes, or that a fragment pass stores to beside its targets. A geometry program takes the same field. |
| `blend` | `false` (default), `"default"` (src-alpha, one-minus-src-alpha) or an explicit `[srcRGB, dstRGB, srcA, dstA]`. |
| `scale` | Viewport scale for this pass. Defaults to the first target's scale. |
| `workGroups` | Compute dispatch: `{ "scale": s, "local": [x, y] }` gives the render size × s divided by the local size, rounded up. `{ "fixed": [x, y, z] }` is also accepted. |
| `enabled` | Option expression (`"SHADOWS && BLOOM_QUALITY > 0"`). Evaluated at load, and changes on option changes only. |
| `flip` | `{ "name": true }` swaps the texture's two images after the pass, `false` keeps the current one (OptiFine `flip.<program>.<buffer>`). |
| `mipmaps` | Textures to regenerate mips for after this pass. |
| `mipmapsBefore` | Inputs to regenerate mips for right before this pass, as OptiFine does for `colortexNMipmapEnabled` programs. Also on `combination`. |

**Read-while-write (ping-pong).** A fragment pass may name a texture in both `inputs` and
`targets`, which is OptiFine's flip semantic. The loader gives such textures a second image
and resolves for every pass which image is "main" and which is "alt". This happens once at
load time (D6). The pass then reads the previous state and writes the other image. A texture
that is never read-while-written gets one image only.

**History textures.** `"history": true` keeps the texture across frames and adds
`<name>Prev` as an input name. The host resets it on the same events `CameraHistory`
already handles: world change, view entity change, a camera jump over 8 blocks, resize.

### Textures

Formats are `RGBA8`, `RGBA16`, `RGBA16F`, `RGBA32F`, `R8`, `R16F`, `R32F`, `RG8`, `RG16F`,
`RG32F`, `R11G11B10F`, `RGB10A2`, `R32UI` and `RGBA32UI`. The list covers OptiFine's
`colortexNFormat` values that have a Vulkan counterpart. RGB formats without alpha map to
the RGBA format of the same width.

| Field | Default | Meaning |
|---|---|---|
| `scale` | 1.0 | Relative to the scene (render) resolution, in (0, 1]. |
| `size` | — | Fixed `[w, h]` instead of `scale`. |
| `wrap` | `clamp` | `repeat` for GL_REPEAT outside `[0, 1]` |
| `clear` | `[0,0,0,0]` | Clear color at frame start, `"fog"` for the frame's fog color, or `false` to keep the contents. Kept textures start as zeros. |
| `mipmaps` | false | Allocates the mip chain. Regenerated where a pass names it in `mipmaps` or `mipmapsBefore`. |
| `history` | false | See above. |
| `filter` | `linear` | `nearest` or `linear` sampling. |
| `file` | none | A PNG in the pack to load once instead of rendering. Needs `format: RGBA8` and `size`; row 0 is the top row of the file. `vkm:noise` is random noise, `vkm:solid:rrggbbaa` one color. |

A texture with `clear: false` starts the next frame with this frame's result, also when a
read-while-write pass left that result in the other image.

### Renderer

| Field | Meaning |
|---|---|
| `hdr` | The scene color is RGBA16F. |
| `handDepth` | The hand's share of the depth range, 0.125 by default. |
| `sceneTarget` | A texture that replaces the scene color as attachment 0 during geometry, so vanilla shaders draw into it (OptiFine's `colortex0`). The combination still writes the host scene color. |
| `rt` | The pack keeps running with RT on, after the RT composite. Post passes only; it samples `sceneColor` through `vkm_hostUV`. |

### Shadow

```jsonc
"shadow": { "resolution": 2048, "distance": 120, "intervalSize": 2, "sunPathRotation": -40,
            "fov": 0, "angleInterval": 0, "terrain": true, "translucent": true, "entities": true,
            "blockEntities": true, "hardwareFiltering": false, "nearest": false,
            "culling": true,
            "colorFormats": ["RGBA8", "RGBA8"], "colorMipmaps": [false, false] }
```

`culling` false draws every built section, for packs that voxelize the world in the shadow pass.
`colorFormats` and `colorMipmaps` list up to eight entries; the missing ones are `RGBA8` and false.

Defaults are OptiFine's. The program is the geometry program `shadow`, whose `targets` are
`shadowcolor0` to `shadowcolor7` (none is fine). It can sample `file` textures only. It runs in
place of the terrain and entity shaders, with `vkm_mvp` and `vkm_modelView` of the light.

Names must match `[A-Za-z_][A-Za-z0-9_]*`. Host names and the `vkm` prefix are reserved.
The current reserved `ap` prefix gets renamed to `vkm`: we are not Aperture-compatible, and
the name should not suggest that we are.

### Resources

`resources` declares what every program shares, in set 2:

```json
"resources": {
  "images": [ { "name": "voxelImg", "sampler": "voxelTex", "format": "R8UI", "clear": true, "size": [128, 64, 128] } ],
  "buffers": [ { "binding": 0, "size": 4096 } ]
}
```

An image has `size` with up to three numbers, or `scale` relative to the screen. `clear` zeroes
it every frame; every image starts zeroed. Images stay in GENERAL layout and a global barrier
separates the stages. `textures` with `file` read PNGs; an entry under `resources` can also read
raw 1D, 2D or 3D data. A compute pass takes `indirect: [buffer, offset]` for its dispatch.

### Geometry stages

A geometry program may add `geometry`, and `tessControl` with `tessEval`. Both tessellation
stages are required together. Patches have three control points, whatever the domain.

### Options

```jsonc
{ "name": "SHADOW_RES", "type": "int",   "default": 1024, "values": [512, 1024, 2048, 4096] }
{ "name": "SUN_STRENGTH","type": "float", "default": 1.0, "min": 0.0, "max": 4.0, "step": 0.1 }
{ "name": "SHADOWS",    "type": "bool",  "default": true }
{ "name": "WATER_MODE", "type": "enum",  "default": "WAVES", "values": ["FLAT", "WAVES", "PARALLAX"] }
```

- Every option reaches every program as a `#define`: bool options are defined or not, the
  others as their value, and enums as an index plus `WATER_MODE_WAVES`-style flags.
- Options may appear in `enabled`, `uniforms` and `shadow.*` expressions.
- A change only recompiles programs whose preprocessed text changed. The cache key is the
  preprocessed source hash, so an option a program does not use costs nothing.
- Values are stored per pack in `config/vesu/shaderpacks/<id>.json`.
- `profiles` assign a set of values. `screens` define the GUI pages: option names,
  `"<empty>"` and `"[page]"` links. Labels come from `lang/<locale>.json`
  (`option.<NAME>`, `option.<NAME>.comment`, `value.<NAME>.<value>`, `screen.<page>`).

### Custom uniforms and ID maps

`"uniforms": [{ "name", "type", "expr", "exposed", "slot" }]` are evaluated in order, once per
frame (`pack/expr/Expression`), and exposed ones land in `vkm_packUniforms[slot]` of the Frame
block, 128 slots. `blockIds`, `itemIds` and `entityIds` are objects of id → names, in the syntax of
OptiFine's `.properties` files.

`"<type> <name>": "<expression>"`, where the type is `bool`, `int`, `float`, `vec2`, `vec3`
or `vec4`.

- Expressions can use host uniforms, options and earlier custom uniforms.
- Functions: the OptiFine set (`sin`, `cos`, `asin`, `acos`, `tan`, `atan`, `atan2`,
  `torad`, `todeg`, `min`, `max`, `clamp`, `abs`, `floor`, `ceil`, `exp`, `frac`, `log`,
  `pow`, `random`, `round`, `signum`, `sqrt`, `fmod`, `if`, `between`, `equals`, `in`,
  `smooth`, `vec2`/`vec3`/`vec4`).
- `smooth(id, value, up, down)` keeps state per id across frames.
- They are compiled to a Java expression tree once at load and evaluated once per frame
  into the `PackUniforms` block (host-interface.md). Pack programs see them as plain
  identifiers.

### Requirements

`requires.features`: `geometryShader`, `computeShaders`, `storageImages`, `shadowMap`,
`rayTracing` and `float32Blend`. A pack whose requirement the device lacks is listed as
unavailable with the missing feature named. It does not fail at load.

`requires.vertex`: extended attributes for terrain and entities (runtime.md, "Vertex data").
Only the requested ones are added (D9).

### Versioning

`format` is an integer. The runtime loads every format up to its own and rejects newer ones
with a message. Frontends always emit the current format.
