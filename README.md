# Vesu

A Vulkan renderer for Minecraft **1.12.2**, running on [Cleanroom](https://github.com/CleanroomMC/Cleanroom).
Vesu (short for Vesuvius) began as an unofficial port of [VulkanMod](https://github.com/xCollateral/VulkanMod)
(by xCollateral, originally for 1.16.5+) to the 1.12.2 rendering pipeline and has since grown its own
shader pack runtime, ray tracing, upscaling and distant (LOD) terrain.

Instead of Minecraft's OpenGL draw calls being sent to the GPU, Cleanroom's Kirino layer tracks the
GL state and the mod replays the render passes - terrain, entities, GUI, sky, clouds, weather and more -
as Vulkan command buffers. The game logic, world and shaders semantics stay vanilla; only the
presentation layer is Vulkan.

## Requirements

- Minecraft 1.12.2 with [Cleanroom](https://github.com/CleanroomMC/Cleanroom) (LWJGL3 and the Kirino
  GL-state tracking are provided by Cleanroom; this port was developed against Cleanroom 0.7.12-alpha)
- A GPU and driver with Vulkan 1.3, or Vulkan 1.2 with `VK_KHR_dynamic_rendering` and
  `VK_KHR_synchronization2` (developed and tested on Linux/NVIDIA)

## Install

1. Install Cleanroom for Minecraft 1.12.2.
2. Drop `Vesu_1.12.2-<version>.jar` into your `mods/` folder.
3. Launch the game. If the Vulkan renderer cannot start, the game shows the reason (for example,
   which Vulkan version or extension the GPU lacks) and can only be quit; it does not continue on
   OpenGL. `VESU_DISABLE=1` runs the game without Vesu's renderer.

## Configuration

Options -> Video Settings opens the Vesu settings screen: pages for General, Quality, Render Distance
(with the LOD settings), Ray Tracing, Shader Packs and Advanced in the left column, and a search field on top
(Ctrl+F). Hover a row for its description; edits wait for Apply or Done (Undo drops them).
Vanilla options still go to `options.txt`, Vesu's own to `config/vesu_settings.json`, for example:

| Setting              | Default | Description                                                   |
|----------------------|---------|---------------------------------------------------------------|
| `renderScale`        | `1.0`   | World resolution relative to the window, 0.25..2 (above 1 supersamples) |
| `upscaler`           | `auto`  | Scaling method: `auto`, `blit` (bilinear), `fsr1` or `fsr3`   |
| `fsrSharpening`      | `0.5`   | Sharpening of FSR 1 and FSR 3, 0..1                           |
| `fullscreenMode`     | none    | Fullscreen video mode `WIDTHxHEIGHT@HZ`; none keeps the desktop mode |
| `menuFramerate`      | `60`    | Frame rate limit while paused, on the title menus or minimized |
| `simulationDistance` | `12`    | Singleplayer: chunks around a player where the world runs, 5..32 |
| `dynamicLights`      | `auto`  | `off` or `auto`; `rtDynamicLights` (`fast`, `rt`) with ray tracing |
| `precipitation`      | `fancy` | `fancy` or `off`                                              |
| `rtPreset`           | `off`   | Ray tracing: `off`, `potato`, `low`, `medium`, `high`, `ultra`, `nasa`, `custom` |
| `shaderPack`         | none    | Selected pack in `shaderpacks/`, or `builtin:sample_post`, `builtin:vibrant_visuals` |
| `frameGeneration`    | `false` | FSR 3 frame generation: a generated frame between every two rendered ones, while FSR 3 upscales |
| `lodEnabled`         | `true`  | Distant terrain past the render distance; off: nothing of it runs |
| `lodDistance`        | `512`   | LOD distance in chunks, 32..2048 in steps of 32               |
| `lodQuality`         | `64`    | Detail: `128` low, `64` medium, `40` high (screen size in pixels before a node splits) |
| `lodFog`             | `fog+fade` | `fog+fade`, `fog`, `fade` or `off`                         |
| `lodSsao`            | `auto`  | Ambient occlusion of LOD pixels: `auto`, `basic`, `better`, `best`, `off` |
| `lodGenerationDistance` | `0`  | Singleplayer: generate chunks out to this distance in the background (0 = off) |
| `frameQueueSize`     | `3`     | Frames in flight; read at startup                             |

Settings and caches of versions named VulkanMod (`config/vulkanmod_settings.json`,
`config/vulkanmod/`) move to the new names on the first launch.

Environment variables (the older `VULKANMOD_*` spellings still work):

- `VESU_DISABLE=1` - keep the original OpenGL path (useful for bisecting issues).
- `VESU_VK_API=1.2` - cap the Vulkan version, to test the Vulkan 1.2 path on a newer driver.
  NRD needs Vulkan 1.3; on 1.2 the RT passes use their own denoiser.
- `VESU_FRAMEGEN=1` / `0` - force frame generation on or off; `VESU_FRAMEGEN_LOG=1` logs
  rendered against presented frames, `VESU_FRAMEGEN_DEBUG=1` draws FSR's debug overlays.
- `VESU_LOD=0` / `1` - force distant terrain off or on; `VESU_LOD_DISTANCE=<chunks>` and
  `VESU_LOD_GEN=<chunks>` override the LOD and generation distances.
- `VESU_BATCH=1` - opt-in chunk batching: uploads all visible chunk sections into one arena
  buffer and draws them with indirect commands.

## What is rendered through Vulkan

- Terrain (all chunk layers), with optional indirect chunk batching
- Distant terrain past the render distance (LOD), also through shader packs and in the RT scene
- Entities, including item entities, shadows, glowing eyes, hurt tint and the first-person hand
  (held items, maps, overlays)
- Block entities (chests, signs, banners, skulls, ...)
- Particles, rain/snow weather
- Screen GUI, item icons and 3D GUI icons (with a real depth buffer)
- Sky: day/night with sunrise/sunset gradient, the End sky cube
- Fancy and fast clouds
- Fog, lightmap (torch / sky lighting), enchantment glint
- World border forcefield and the border warning vignette
- F3 debug renderers (chunk borders, collision boxes, ...) and the block selection box

## Distant terrain

Past the render distance Vesu draws simplified terrain out to the LOD distance (512 chunks by
default). It is built from the chunks the client receives and kept per world and dimension (in a `vesu_lod/`
folder inside the save, or under `vesu_lod/servers/` for multiplayer), so it grows as you explore; `/vesu lod import current` or
`/vesu lod import world <name>` fills it from a save, and LOD Generation Distance generates the
missing chunks in the background in singleplayer. Node selection and occlusion culling run on the
GPU. Shader packs with Distant Horizons programs (`dh_terrain`, `dh_water`) light it themselves;
other packs draw it through their terrain and water programs.

## Known limitations

- Name tags and sign text are rendered as of v0.1.5; niche world-space text drawn by the F3 debug
  renderers (height-map / pathfinding labels) may still stay invisible.
- This is a work in progress port; some niche GL paths or heavily mixin-modded renderers may still
  fall through or render incorrectly. Please open an issue with a screenshot and your mod list.

## Performance

Average FPS, 1% low and 0.1% low (the mean FPS of the worst 1% / 0.1% of individual frames,
measured from per-frame times) while flying a fixed spectator path over a fixed-seed world for
90 seconds. Intel UHD 620 iGPU, 1600x900, render distance 12, mipmaps 4, VSync off, FPS uncapped.
Two rounds per configuration with cooldowns between runs; the numbers are the round average.

| Configuration | Avg FPS | 1% low | 0.1% low |
|--------------------------------------------|---------|--------|----------|
| **Vesu (this mod)** on Cleanroom 0.7.12 | **175.2** | 64.0 | 40.9 |
| Cleanroom 0.6.13 + Actinium alpha-0.0.13   | 153.9   | 78.3 | 37.9 |
| Cleanroom 0.7.12 (base, no renderer mod)   | 136.4   | 61.7 | 40.7 |
| Forge 14.23.5.2860 (no mods)               | 131.1   | 51.9 | 33.2 |
| Forge 14.23.5.2860 + OptiFine HD U G5      | 126.5   | 51.9 | 32.8 |

The base and Vesu runs use the Gradle dev environment (a few percent of extra overhead, so
their numbers are conservative); the Forge and Cleanroom+Actinium runs use production installs.
Actinium has the smoothest frame pacing of these (best 1% low) but a lower ceiling than the
Vulkan path; on a discrete GPU the gap narrows as the workload becomes less GPU-bound. On this
hardware OptiFine costs a few percent rather than saving any.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. Requires the JDK the Gradle toolchain pins (see `build.gradle`).
The build downloads Cleanroom/Minecraft dev dependencies through Gradle.

## Development notes

The repo ships an autotest harness (`VESU_AUTOTEST=1`) that boots the game headlessly into a
fixed scene, captures screenshots at deterministic ticks and exits - used to verify every render
pass against a GL twin (`VESU_DISABLE=1` on the same scene). See `net.vesu.debug.AutoTest`.

## License

LGPL-3.0, same as the original [VulkanMod](https://github.com/xCollateral/VulkanMod) by xCollateral,
whose renderer architecture and shader pipeline this port follows. Original mod: VulkanMod by
xCollateral; 1.12.2/Cleanroom port by CelestialD.
