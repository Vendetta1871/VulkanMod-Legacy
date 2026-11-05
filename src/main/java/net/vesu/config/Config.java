package net.vesu.config;

import net.vesu.Env;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Renderer settings persisted as JSON, mirroring the original VulkanMod config.
 * The video-settings GUI screen is ported separately for the 1.12.2 widget system.
 */
public class Config {

    /**
     * Number of swapchain images / frames in flight. 3: with only 2 the CPU waits for the
     * present of the previous frame, which on a PRIME setup (render GPU without a display)
     * is a full-frame copy over PCIe that then cannot overlap rendering.
     */
    public int frameQueueSize = 3;
    /** Settings format; null in files written before it existed (see {@link #migrate}). */
    public Integer configVersion;
    private static final int CONFIG_VERSION = 2;
    /** Batch GUI draws through the Vulkan GUI renderer. */
    public boolean guiOptimizations = true;

    /**
     * World resolution relative to the window, 0.25..2. Below 1 the upscaler fills in (F7);
     * above 1 the world is supersampled and filtered down bilinearly.
     */
    public float renderScale = 1.0f;
    public static final float RENDER_SCALE_MIN = 0.25f, RENDER_SCALE_MAX = 2.0f;
    /** auto (FSR 3 when scaled with ray tracing on and available, else FSR 1) | fsr3 | fsr1 | blit. */
    public String upscaler = "auto";
    /** F9: FSR 3 frame generation (needs the FSR 3 upscaler; doubles the presented frames). */
    public boolean frameGeneration = false;
    /** FSR sharpening 0..1, shared by FSR 1 (RCAS, 2 stops at 0 down to 0 stops at 1) and FSR 3. */
    public float fsrSharpening = 0.5f;
    /** Older per-upscaler sharpness, read once by {@link #migrate}. */
    public Float fsr1Sharpness, fsr3Sharpness;

    /**
     * Exclusive fullscreen video mode as {@code WIDTHxHEIGHT@HZ}, or null for the desktop
     * mode. See {@link net.vesu.config.DisplayModes}.
     */
    public String fullscreenMode;
    /** Frame rate limit while the game is paused, on the title menus or hidden. */
    public int menuFramerate = 60;
    /**
     * Singleplayer: entities, block entities, scheduled and random ticks only run within this
     * many chunks of a player; farther chunks up to the render distance stay loaded and frozen.
     */
    public int simulationDistance = 12;
    public static final int SIMULATION_DISTANCE_MIN = 5, SIMULATION_DISTANCE_MAX = 32;
    /** Rain and snow: fancy (the vanilla drawing) | off. */
    public String precipitation = "fancy";

    /**
     * Fancy leaves: off | hollow (a face with two leaf layers behind it) | aggressive (a leaf
     * block covered on its four sides is buried) | solid (covered on all six). Faces toward
     * buried leaves are dropped at chunk build; buried leaves go to the solid layer while no
     * shader pack or ray tracing runs. See {@link net.vesu.render.LeafCulling}.
     */
    public String leafCulling = "solid";
    /**
     * Entities and block entities that no ray from the camera reaches skip rendering; far
     * dropped item stacks draw fewer copies. See {@link net.vesu.render.OcclusionCuller}.
     */
    public boolean entityCulling = true;
    /** Particles outside the view are not drawn, block-break particles outside it not spawned. */
    public boolean particleCulling = true;
    /**
     * Times a second the in-game HUD is redrawn into its cached image; 0 draws it every frame.
     * See {@link net.vesu.render.HudCache}.
     */
    public int hudFramerate = 60;
    /** Particle billboards built on the GPU from one record each; see {@link net.vesu.render.GpuParticles}. */
    public boolean gpuParticles = true;
    /** Spawn chance 0..1 per particle name (EnumParticleTypes, e.g. "smoke"); unlisted types always spawn. */
    public java.util.Map<String, Float> particleSpawnChance = new java.util.HashMap<>();

    /**
     * Selected shader pack: a folder or zip name in {@code shaderpacks/}, or null for none
     * (docs/shaderpacks). See {@link net.vesu.shader.PackManager}.
     */
    public String shaderPack;

    /**
     * Ray tracing preset: off | low | medium | high | custom. The named presets set the three
     * distances below; custom keeps them as edited. Needs ray-tracing pipeline support.
     */
    public String rtPreset = "off";
    /** Per effect: within this many chunks of the camera (0 = off); farther stays raster. */
    public int rtShadowDistance = 6, rtIndirectDistance = 3, rtReflectionDistance = 4;

    // --- LOD terrain (AUDIT 5.18) ---
    /** Far terrain past the render distance. Off: no LOD thread, buffer or pipeline exists. */
    public boolean lodEnabled = true;
    /** Draw LODs (ingest and storage keep running when false). */
    public boolean lodRendering = true;
    /** Take terrain from received chunks into the LOD store. */
    public boolean lodIngest = true;
    /** In chunks, a multiple of 32 (one top-level node). */
    public int lodDistance = 512;
    public static final int LOD_DISTANCE_MIN = 32, LOD_DISTANCE_MAX = 2048;
    /** Subdivision threshold in pixels: Low 128, Medium 64, High 40; anything 28..256 is custom. */
    public int lodQuality = 64;
    public static final int LOD_QUALITY_MIN = 28, LOD_QUALITY_MAX = 256;
    /** Singleplayer pre-generation radius in chunks, 0 = off (AUDIT 5.7). */
    public int lodGenerationDistance = 0;
    /** Worker threads; 0 = max(cores / 1.5, 1). */
    public int lodThreads = 0;
    /** fog+fade | fog | fade | off. */
    public String lodFog = "fog+fade";
    /** auto | basic | better | best (AUDIT 5.15). */
    public String lodSsao = "auto";
    /** Top-level sections past the vanilla distance whose LODs enter the RT scene. */
    public int lodRtRing = 2;
    /** Levels 3..4 in the shader pack's shadow pass. */
    public boolean lodPackShadows = false;
    /** CPU mesh cache in MiB. */
    public int lodMeshCacheMb = 512;
    /** Older settings, read once and migrated to the preset and distances. */
    public Integer rtDistance;
    public Boolean rayTracing;

    public static final int RT_DISTANCE_MAX = 16;
    public static final String[] RT_PRESETS = {"off", "potato", "low", "medium", "high", "ultra", "nasa", "custom"};

    /** {shadows, indirect, reflections} of a named preset, or null for custom. */
    public static int[] rtPresetDistances(String preset) {
        return switch (preset == null ? "off" : preset) {
            case "off" -> new int[]{0, 0, 0};
            case "potato" -> new int[]{3, 0, 0};
            case "low" -> new int[]{4, 0, 2};
            case "medium" -> new int[]{6, 3, 4};
            case "high" -> new int[]{10, 6, 8};
            case "ultra" -> new int[]{14, 10, 12};
            case "nasa" -> new int[]{RT_DISTANCE_MAX, RT_DISTANCE_MAX, RT_DISTANCE_MAX};
            default -> null;
        };
    }

    /** Applies a named preset to the distances (custom leaves them). */
    public void applyRtPreset(String preset) {
        rtPreset = preset;
        int[] d = rtPresetDistances(preset);
        if (d != null) {
            rtShadowDistance = d[0];
            rtIndirectDistance = d[1];
            rtReflectionDistance = d[2];
        }
    }
    /** How much ray-traced shadows darken, 0..1. */
    public float rtShadowStrength = 0.45f;
    /** How much ray-traced block light (torches, glowstone...) replaces vanilla's, 0..1. */
    public float rtBlockLight = 1.0f;
    /** Ray-traced reflections on water, glass and ice, 0..1 (0 = off). */
    public float rtReflections = 1.0f;
    /** Ray-traced indirect light (bounces, occlusion), 0..1 (0 = off). */
    public float rtIndirect = 1.0f;
    /**
     * Dynamic lights (held and dropped light sources): off | auto. Auto is off while a shader
     * pack lights the world; with ray tracing on, {@link #rtDynamicLights} picks the kind.
     */
    public String dynamicLights = "auto";
    /** Dynamic lights with ray tracing on: fast (raster) | rt (ray traced, casts shadows). */
    public String rtDynamicLights = "rt";

    // Environment overrides (tests and autotest runs) take precedence over the file.

    public boolean effectiveRayTracing() {
        return effectiveRtDistance() > 0;
    }

    /** Range of the RT scene: the largest effect distance. */
    public int effectiveRtDistance() {
        int[] d = effectiveRtDistances();
        return Math.max(d[0], Math.max(d[1], d[2]));
    }

    public int effectiveRtShadowDistance() { return effectiveRtDistances()[0]; }
    public int effectiveRtIndirectDistance() { return effectiveRtDistances()[1]; }
    public int effectiveRtReflectionDistance() { return effectiveRtDistances()[2]; }

    /**
     * {shadows, indirect, reflections} in chunks. VESU_RT=0 turns RT off, VESU_RT=1
     * turns an "off" config on with the medium preset, VESU_RT_DISTANCE sets all three.
     */
    public int[] effectiveRtDistances() {
        int[] d = rtPresetDistances(rtPreset);
        if (d == null) d = new int[]{rtShadowDistance, rtIndirectDistance, rtReflectionDistance};
        String env = Env.get("VESU_RT");
        if (env != null) {
            if (!env.isEmpty() && env.charAt(0) == '0') return new int[]{0, 0, 0};
            if (d[0] + d[1] + d[2] == 0) d = rtPresetDistances("medium");
        }
        String all = Env.get("VESU_RT_DISTANCE");
        if (all != null) {
            int v = (int) envFloat("VESU_RT_DISTANCE", 0.0f);
            d = new int[]{v, v, v};
        }
        for (int i = 0; i < 3; i++) d[i] = Math.max(0, Math.min(RT_DISTANCE_MAX, d[i]));
        return d;
    }

    /**
     * "off" | "fast" | "on" (ray traced). VESU_DYNAMIC_LIGHTS overrides with one of those.
     * Auto is off under a pack with its own lighting, else fast, or the ray-tracing choice
     * while ray tracing is on.
     */
    public String effectiveDynamicLights() {
        String env = Env.get("VESU_DYNAMIC_LIGHTS");
        if (env != null) {
            String v = env.toLowerCase();
            return v.equals("off") || v.equals("fast") ? v : "on";
        }
        if ("off".equals(dynamicLights)) return "off";
        if (net.vesu.vulkan.rt.RtScene.enabled()) return "fast".equals(rtDynamicLights) ? "fast" : "on";
        net.vesu.pack.ir.IrPack pack = net.vesu.shader.PackManager.active();
        if (pack != null && !(pack.renderer.has("rt") && pack.renderer.get("rt").getAsBoolean())) return "off";
        return "fast";
    }

    public float effectiveRtIndirect() {
        return clamp(envFloat("VESU_RT_GI", rtIndirect), 0.0f, 1.0f);
    }

    public float effectiveRtReflections() {
        return clamp(envFloat("VESU_RT_REFLECTIONS", rtReflections), 0.0f, 1.0f);
    }

    public float effectiveRtBlockLight() {
        return clamp(envFloat("VESU_RT_BLOCK", rtBlockLight), 0.0f, 1.0f);
    }

    public float effectiveRtShadowStrength() {
        return clamp(envFloat("VESU_RT_SHADOWS", rtShadowStrength), 0.0f, 1.0f);
    }

    public float effectiveRenderScale() {
        return clamp(envFloat("VESU_RENDER_SCALE", renderScale), RENDER_SCALE_MIN, RENDER_SCALE_MAX);
    }

    public String effectiveUpscaler() {
        String env = Env.get("VESU_UPSCALER");
        String v = (env != null ? env : upscaler == null ? "auto" : upscaler).toLowerCase();
        return switch (v) {
            case "fsr3", "fsr1", "blit" -> v;
            default -> "auto";
        };
    }

    /** VESU_FRAMEGEN=1/0 overrides the setting. */
    public boolean effectiveFrameGeneration() {
        String env = Env.get("VESU_FRAMEGEN");
        return env != null ? !env.isEmpty() && env.charAt(0) != '0' : frameGeneration;
    }

    /** FSR 1 RCAS sharpness in stops, 0 = strongest; VESU_FSR_SHARPNESS gives stops directly. */
    public float effectiveFsr1Sharpness() {
        float stops = 2.0f * (1.0f - clamp(fsrSharpening, 0.0f, 1.0f));
        return clamp(envFloat("VESU_FSR_SHARPNESS", stops), 0.0f, 2.0f);
    }

    public float effectiveFsr3Sharpness() {
        return clamp(envFloat("VESU_FSR3_SHARPNESS", fsrSharpening), 0.0f, 1.0f);
    }

    public int effectiveSimulationDistance() {
        return Math.max(SIMULATION_DISTANCE_MIN, Math.min(SIMULATION_DISTANCE_MAX, simulationDistance));
    }

    private static float envFloat(String name, float fallback) {
        String v = Env.get(name);
        if (v == null) return fallback;
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.TRANSIENT)
            .create();

    public static Config load(Path path) {
        if (!Files.exists(path)) {
            return null;
        }

        // L14: a corrupt or unreadable file must not kill the game - fall back to the
        // defaults. The caller writes a fresh file over the broken one, so keep a copy.
        // JsonParseException covers both JsonSyntaxException and JsonIOException.
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Config c = GSON.fromJson(reader, Config.class);
            if (c != null && (c.rayTracing != null || c.rtDistance != null)) {
                int old = c.rtDistance != null ? c.rtDistance : Boolean.TRUE.equals(c.rayTracing) ? 8 : 0;
                if (old > 0) {
                    c.rtPreset = "custom";
                    c.rtShadowDistance = c.rtIndirectDistance = c.rtReflectionDistance = Math.min(old, RT_DISTANCE_MAX);
                } else {
                    c.rtPreset = "off";
                }
                c.rayTracing = null;
                c.rtDistance = null;
            }
            if (c != null) c.migrate();
            return c;
        } catch (JsonParseException | IOException e) {
            net.vesu.Initializer.LOGGER.error("Failed to load Vesu config, using defaults: {}", path, e);
            backup(path);
            return null;
        }
    }

    /** Upgrades settings written by older versions; a new config just gets the current version. */
    public void migrate() {
        if (configVersion == null || configVersion < 1) {
            // 0.2.0 and older defaulted to 2 frames in flight.
            if (frameQueueSize == 2) frameQueueSize = 3;
        }
        if (configVersion == null || configVersion < 2) {
            // 0.3.0: one sharpening for both FSR versions; dynamic lights split in two.
            if (fsr3Sharpness != null) fsrSharpening = clamp(fsr3Sharpness, 0.0f, 1.0f);
            if ("off".equals(rtDynamicLights)) dynamicLights = "off";
            rtDynamicLights = "fast".equals(rtDynamicLights) ? "fast" : "rt";
        }
        fsr1Sharpness = null;
        fsr3Sharpness = null;
        configVersion = CONFIG_VERSION;
    }

    private static void backup(Path path) {
        Path copy = path.resolveSibling(path.getFileName() + ".bak");
        try {
            Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
            net.vesu.Initializer.LOGGER.warn("Saved the unreadable Vesu config as {}", copy);
        } catch (IOException e) {
            net.vesu.Initializer.LOGGER.warn("Could not back up the Vesu config: {}", path, e);
        }
    }

    public void write(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Vesu config: " + path, e);
        }
    }
}
