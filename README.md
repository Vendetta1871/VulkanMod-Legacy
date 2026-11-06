# Vesu

**A Vulkan renderer for Minecraft 1.12.2.**

[![GitHub][github-badge]][github]
[![Discord][discord-badge]][discord]
![Modrinth: not published yet][modrinth-badge]
![CurseForge: not published yet][curseforge-badge]

[github-badge]: https://img.shields.io/badge/GitHub-Source-181717?style=for-the-badge&logo=github&logoColor=white
[github]: https://github.com/Vendetta1871/vulkanmod-legacy
[discord-badge]: docs/badges/discord.svg
[discord]: https://discord.gg/gvmeswCQFP
[modrinth-badge]: https://img.shields.io/badge/Modrinth-Soon-555555?style=for-the-badge&logo=modrinth&logoColor=white
[curseforge-badge]: docs/badges/curseforge.svg

![Vesu at sunset with BSL Shaders and distant terrain](docs/images/hero.jpg)

Vesu turns Minecraft 1.12.2's OpenGL draw calls into Vulkan command buffers - terrain, entities,
block entities, particles, weather, sky, clouds, GUI and the first-person hand. The world, the game
logic and the look of vanilla stay the same; the way every frame reaches your GPU does not.

It began as an unofficial port of [VulkanMod](https://github.com/xCollateral/VulkanMod) to the 1.12.2
rendering pipeline and has grown into its own renderer: a shader-pack runtime, ray tracing, FSR
upscaling and frame generation, and distant terrain that pushes the horizon far past the vanilla
render distance.

## Why Vesu

- **A real Vulkan renderer.** Cleanroom's Kirino layer tracks the GL state; Vesu records its own
  command buffers, pipelines and descriptors from it instead of replaying GL emulation.
- **Your shader packs still work.** Iris/OptiFine-style packs, including Distant Horizons programs,
  run through Vesu's pack runtime. No OptiFine required.
- **Ray tracing on top.** Shadows, water reflections, glass and ice reflections, block light and
  indirect bounce light - with presets from *potato* to *nasa*.
- **A horizon without a wall.** Simplified distant terrain out to 2,048 chunks, kept per world and
  lit by shader packs or by the ray tracer.
- **Built for old and new GPUs.** Vulkan 1.3, or Vulkan 1.2 with dynamic rendering and
  synchronization2. FSR 1/3 and frame generation pick up the slack on weaker hardware.

## Ray tracing that changes the picture

<p align="center">
  <img src="docs/images/rt-off.jpg" width="49%" alt="Ray tracing off"/>
  <img src="docs/images/rt-on.jpg" width="49%" alt="Ray tracing on"/>
</p>
<p align="center"><sub><b>Left:</b> ray tracing off. <b>Right:</b> ray tracing on, 32-chunk effect range.</sub></p>

Glowstone and sea lanterns become real light sources: iron bars cast their grid onto the walls,
the pool mirrors the lamps and the gold columns, and light bounces into places a lightmap cannot
reach. Sunlight is traced the same way, and reflections work on water, glass, ice, polished stone
and metal blocks. Presets cover the range from a
small 4-chunk shadow pass to a full 32-chunk scene, and every effect has its own distance and
strength.

## Bring your shader packs

<p align="center">
  <img src="docs/images/shaders-off.jpg" width="49%" alt="Vanilla rendering through Vulkan"/>
  <img src="docs/images/shaders-on.jpg" width="49%" alt="BSL Shaders through Vesu"/>
</p>
<p align="center"><sub><b>Left:</b> vanilla rendering through Vesu. <b>Right:</b> BSL Shaders, unchanged.</sub></p>

Vesu runs the pack, not a reimplementation of it. Packs with shadow maps, deferred passes, water,
volumetrics and Distant Horizons programs are compiled and executed by the renderer itself, with a
background compiler so the game does not freeze while a pack builds. The selection lives in
**Options -> Video Settings -> Shader Packs**, alongside a built-in sample pack and Vibrant Visuals.

## Vibrant Visuals, built in

<p align="center">
  <img src="docs/images/vv-off.jpg" width="49%" alt="Vibrant Visuals off"/>
  <img src="docs/images/vv-on.jpg" width="49%" alt="Vibrant Visuals on"/>
</p>
<p align="center"><sub><b>Left:</b> off. <b>Right:</b> the built-in Vibrant Visuals pack.</sub></p>

Vibrant Visuals brings the Bedrock-style atmosphere and water look to 1.12.2 without a download:
softer fog, coloured skies, moving water and a more physical lighting model. It is a normal shader
pack inside the mod, so it can be combined with the rest of the graphics settings - or replaced by
any pack you prefer.

## Distant terrain without the wall

<p align="center">
  <img src="docs/images/lod-off.jpg" width="49%" alt="Distant terrain off"/>
  <img src="docs/images/lod-on.jpg" width="49%" alt="Distant terrain on"/>
</p>
<p align="center"><sub><b>Left:</b> render distance 8, no distant terrain. <b>Right:</b> render distance 8 plus LOD terrain.</sub></p>

Vesu keeps building simplified terrain from the chunks the client already receives, stores it per
world and dimension, and draws it past the vanilla render distance - 512 chunks by default, up to
2,048. Node selection and occlusion culling run on the GPU, so the far horizon does not turn into a
CPU bottleneck. Shader packs with Distant Horizons programs light the LOD terrain themselves; other
packs draw it through their terrain and water programs. Singleplayer can also generate the missing
chunks in the background.

## The quiet wins

Not every improvement is a screenshot.

- **Leaf, entity and particle culling.** Hidden leaf faces, entities behind blocks and particles
  outside the view are skipped before they ever reach the GPU.
- **FSR 1 and FSR 3 upscaling**, with adjustable sharpening, plus optional FSR 3 frame generation.
- **Dynamic lights** from held and dropped light sources, raster or ray traced.
- **A searchable settings screen** for every Vesu option, with Apply/Undo and hover descriptions.
- **Vanilla compatibility.** World logic, saves and shader semantics stay 1.12.2; only the
  presentation layer is Vulkan.

## Performance

Average FPS, 1% low and 0.1% low while flying a fixed spectator path over a fixed-seed world for 90
seconds. Intel UHD 620 iGPU, 1600x900, render distance 12, mipmaps 4, VSync off, FPS uncapped. Two
rounds per configuration with cooldowns between runs; the numbers are the round average.

| Configuration | Avg FPS | 1% low | 0.1% low |
|--------------------------------------------|---------|--------|----------|
| **Vesu (this mod)** on Cleanroom 0.7.12 | **175.2** | 64.0 | 40.9 |
| Cleanroom 0.6.13 + Actinium alpha-0.0.13   | 153.9   | 78.3 | 37.9 |
| Cleanroom 0.7.12 (base, no renderer mod)   | 136.4   | 61.7 | 40.7 |
| Forge 14.23.5.2860 (no mods)               | 131.1   | 51.9 | 33.2 |
| Forge 14.23.5.2860 + OptiFine HD U G5      | 126.5   | 51.9 | 32.8 |

The base and Vesu runs use the Gradle dev environment (a few percent of extra overhead, so their
numbers are conservative); the Forge and Cleanroom+Actinium runs use production installs. Actinium
has the smoothest frame pacing of these (best 1% low) but a lower ceiling than the Vulkan path; on a
discrete GPU the gap narrows as the workload becomes less GPU-bound. On this hardware OptiFine costs
a few percent rather than saving any.

## Install

1. Install [Cleanroom](https://github.com/CleanroomMC/Cleanroom) for Minecraft 1.12.2.
2. Drop `Vesu_1.12.2-<version>.jar` into your `mods/` folder.
3. Launch the game.

Vesu needs a GPU and driver with Vulkan 1.3, or Vulkan 1.2 with `VK_KHR_dynamic_rendering` and
`VK_KHR_synchronization2` (developed and tested on Linux/NVIDIA). If the Vulkan renderer cannot
start, the game shows the reason and exits instead of silently falling back to OpenGL.
`VESU_DISABLE=1` runs the original OpenGL path for bisecting issues.

All the graphics options live in **Options -> Video Settings**: General, Quality, Render Distance
(with the LOD settings), Ray Tracing, Shader Packs and Advanced, with a search field on top. The
file behind the screen is `config/vesu_settings.json`.

![The Render Distance page of the Vesu settings screen](docs/images/settings.png)

## Compatibility and known limitations

- Minecraft 1.12.2 with Cleanroom (LWJGL3 and Kilo's GL-state tracking are provided by Cleanroom;
  this port was developed against Cleanroom 0.7.12-alpha).
- Name tags and sign text are rendered as of v0.1.5; niche world-space text drawn by the F3 debug
  renderers (height-map / pathfinding labels) may still stay invisible.
- This is a work-in-progress port. Some niche GL paths or heavily mixin-modded renderers may still
  fall through or render incorrectly - please open an issue with a screenshot and your mod list.

## Credits

Vesu (short for Vesuvius) is an unofficial 1.12.2/Cleanroom port of
[VulkanMod](https://github.com/xCollateral/VulkanMod) by **xCollateral**, whose renderer architecture
and shader pipeline this project follows. 1.12.2 port by **CelestialD**. Licensed under
[LGPL-3.0](LICENSE), the same as the original.

*Screenshots are raw in-game captures, resized for the web.*
