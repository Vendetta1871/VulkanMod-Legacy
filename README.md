# VulkanMod Legacy

A Vulkan renderer for Minecraft **1.12.2**, running on [Cleanroom](https://github.com/CleanroomMC/Cleanroom).
It is an unofficial port of [VulkanMod](https://github.com/xCollateral/VulkanMod) (by xCollateral, originally
for 1.16.5+) down to the 1.12.2 rendering pipeline.

Instead of Minecraft's OpenGL draw calls being sent to the GPU, Cleanroom's Kirino layer tracks the
GL state and the mod replays the render passes - terrain, entities, GUI, sky, clouds, weather and more -
as Vulkan command buffers. The game logic, world and shaders semantics stay vanilla; only the
presentation layer is Vulkan.

## Requirements

- Minecraft 1.12.2 with [Cleanroom](https://github.com/CleanroomMC/Cleanroom) (LWJGL3 and the Kirino
  GL-state tracking are provided by Cleanroom; this port was developed against Cleanroom 0.7.12-alpha)
- A Vulkan-capable GPU and driver (developed and tested on Linux/NVIDIA)

## Install

1. Install Cleanroom for Minecraft 1.12.2.
2. Drop `vulkanmod-<version>.jar` into your `mods/` folder.
3. Launch the game. If the Vulkan renderer fails to initialize, the mod logs the error and you can
   fall back by setting the environment variable `VULKANMOD_DISABLE=1`.

## Configuration

`config/vulkanmod_settings.json` (created on first run):

| Setting          | Default | Description                                  |
|------------------|---------|----------------------------------------------|
| `frameQueueSize` | `2`     | Swapchain images in flight (2 = low latency) |
| `vsync`          | `false` | VSync via the swapchain present mode         |

Environment variables:

- `VULKANMOD_DISABLE=1` - keep the original OpenGL path (useful for bisecting issues).
- `VULKANMOD_BATCH=1` - opt-in chunk batching: uploads all visible chunk sections into one arena
  buffer and draws them with indirect commands.

## What is rendered through Vulkan

- Terrain (all chunk layers), with optional indirect chunk batching
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

## Known limitations

- World-space text drawn during the world pass (e.g. the F3 height-map / pathfinding labels) is not
  captured yet and stays invisible.
- This is a work in progress port; some niche GL paths or heavily mixin-modded renderers may still
  fall through or render incorrectly. Please open an issue with a screenshot and your mod list.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. Requires the JDK the Gradle toolchain pins (see `build.gradle`).
The build downloads Cleanroom/Minecraft dev dependencies through Gradle.

## Development notes

The repo ships an autotest harness (`VULKANMOD_AUTOTEST=1`) that boots the game headlessly into a
fixed scene, captures screenshots at deterministic ticks and exits - used to verify every render
pass against a GL twin (`VULKANMOD_DISABLE=1` on the same scene). See `net.vulkanmod.debug.AutoTest`.

## License

LGPL-3.0, same as the original [VulkanMod](https://github.com/xCollateral/VulkanMod) by xCollateral,
whose renderer architecture and shader pipeline this port follows. Original mod: VulkanMod by
xCollateral; 1.12.2/Cleanroom port by CelestialD.
