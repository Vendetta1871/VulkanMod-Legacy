package net.vulkanmod.config;

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

    /** Number of swapchain images / frames in flight. */
    public int frameQueueSize = 2;
    /** Batch GUI draws through the Vulkan GUI renderer. */
    public boolean guiOptimizations = true;

    /** World resolution relative to the window, 0.25..1; below 1 the upscaler fills in (F7). */
    public float renderScale = 1.0f;
    /** auto (FSR 3 when scaled and available, else FSR 1) | fsr3 | fsr1 | blit. */
    public String upscaler = "auto";
    /** FSR 1 RCAS sharpness in stops: 0 = strongest. */
    public float fsr1Sharpness = 0.2f;
    /** FSR 3 sharpening strength 0..1: 0 = off. */
    public float fsr3Sharpness = 0.3f;

    /**
     * Ray tracing preset: off | low | medium | high | custom. The named presets set the three
     * distances below; custom keeps them as edited. Needs ray-tracing pipeline support.
     */
    public String rtPreset = "off";
    /** Per effect: within this many chunks of the camera (0 = off); farther stays raster. */
    public int rtShadowDistance = 6, rtIndirectDistance = 3, rtReflectionDistance = 4;
    /** Older settings, read once and migrated to the preset and distances. */
    public Integer rtDistance;
    public Boolean rayTracing;

    public static final int RT_DISTANCE_MAX = 16;
    public static final String[] RT_PRESETS = {"off", "low", "medium", "high", "custom"};

    /** {shadows, indirect, reflections} of a named preset, or null for custom. */
    public static int[] rtPresetDistances(String preset) {
        return switch (preset == null ? "off" : preset) {
            case "off" -> new int[]{0, 0, 0};
            case "low" -> new int[]{4, 0, 2};
            case "medium" -> new int[]{6, 3, 4};
            case "high" -> new int[]{10, 6, 8};
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
    /** Dynamic lights (held / dropped light sources): off | fast (raster) | on (ray traced). */
    public String rtDynamicLights = "on";

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
     * {shadows, indirect, reflections} in chunks. VULKANMOD_RT=0 turns RT off, VULKANMOD_RT=1
     * turns an "off" config on with the medium preset, VULKANMOD_RT_DISTANCE sets all three.
     */
    public int[] effectiveRtDistances() {
        int[] d = rtPresetDistances(rtPreset);
        if (d == null) d = new int[]{rtShadowDistance, rtIndirectDistance, rtReflectionDistance};
        String env = System.getenv("VULKANMOD_RT");
        if (env != null) {
            if (!env.isEmpty() && env.charAt(0) == '0') return new int[]{0, 0, 0};
            if (d[0] + d[1] + d[2] == 0) d = rtPresetDistances("medium");
        }
        String all = System.getenv("VULKANMOD_RT_DISTANCE");
        if (all != null) {
            int v = (int) envFloat("VULKANMOD_RT_DISTANCE", 0.0f);
            d = new int[]{v, v, v};
        }
        for (int i = 0; i < 3; i++) d[i] = Math.max(0, Math.min(RT_DISTANCE_MAX, d[i]));
        return d;
    }

    public String effectiveDynamicLights() {
        String env = System.getenv("VULKANMOD_DYNAMIC_LIGHTS");
        String v = (env != null ? env : rtDynamicLights == null ? "on" : rtDynamicLights).toLowerCase();
        return v.equals("off") || v.equals("fast") ? v : "on";
    }

    public float effectiveRtIndirect() {
        return clamp(envFloat("VULKANMOD_RT_GI", rtIndirect), 0.0f, 1.0f);
    }

    public float effectiveRtReflections() {
        return clamp(envFloat("VULKANMOD_RT_REFLECTIONS", rtReflections), 0.0f, 1.0f);
    }

    public float effectiveRtBlockLight() {
        return clamp(envFloat("VULKANMOD_RT_BLOCK", rtBlockLight), 0.0f, 1.0f);
    }

    public float effectiveRtShadowStrength() {
        return clamp(envFloat("VULKANMOD_RT_SHADOWS", rtShadowStrength), 0.0f, 1.0f);
    }

    public float effectiveRenderScale() {
        return clamp(envFloat("VULKANMOD_RENDER_SCALE", renderScale), 0.25f, 1.0f);
    }

    public String effectiveUpscaler() {
        String env = System.getenv("VULKANMOD_UPSCALER");
        String v = (env != null ? env : upscaler == null ? "auto" : upscaler).toLowerCase();
        return switch (v) {
            case "fsr3", "fsr1", "blit" -> v;
            default -> "auto";
        };
    }

    public float effectiveFsr1Sharpness() {
        return clamp(envFloat("VULKANMOD_FSR_SHARPNESS", fsr1Sharpness), 0.0f, 2.0f);
    }

    public float effectiveFsr3Sharpness() {
        return clamp(envFloat("VULKANMOD_FSR3_SHARPNESS", fsr3Sharpness), 0.0f, 1.0f);
    }

    private static float envFloat(String name, float fallback) {
        String v = System.getenv(name);
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
            return c;
        } catch (JsonParseException | IOException e) {
            net.vulkanmod.Initializer.LOGGER.error("Failed to load VulkanMod config, using defaults: {}", path, e);
            backup(path);
            return null;
        }
    }

    private static void backup(Path path) {
        Path copy = path.resolveSibling(path.getFileName() + ".bak");
        try {
            Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
            net.vulkanmod.Initializer.LOGGER.warn("Saved the unreadable VulkanMod config as {}", copy);
        } catch (IOException e) {
            net.vulkanmod.Initializer.LOGGER.warn("Could not back up the VulkanMod config: {}", path, e);
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
            throw new RuntimeException("Failed to write VulkanMod config: " + path, e);
        }
    }
}
