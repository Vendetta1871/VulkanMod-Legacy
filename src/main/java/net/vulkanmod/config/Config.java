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
     * Ray-traced effects (shadows for now) within this many chunks of the camera; farther
     * sections keep plain raster lighting. 0 = off. Needs ray-tracing pipeline support.
     */
    public int rtDistance = 0;
    /** Pre-rtDistance on/off switch, read once and migrated (true becomes {@link #RT_DISTANCE_DEFAULT}). */
    public Boolean rayTracing;

    public static final int RT_DISTANCE_MAX = 16, RT_DISTANCE_DEFAULT = 8;
    /** How much ray-traced shadows darken, 0..1. */
    public float rtShadowStrength = 0.45f;
    /** How much ray-traced block light (torches, glowstone...) replaces vanilla's, 0..1. */
    public float rtBlockLight = 1.0f;
    /** Ray-traced reflections on water, glass and ice, 0..1 (0 = off). */
    public float rtReflections = 1.0f;

    // Environment overrides (tests and autotest runs) take precedence over the file.

    public boolean effectiveRayTracing() {
        return effectiveRtDistance() > 0;
    }

    /** VULKANMOD_RT=0 turns RT off; VULKANMOD_RT=1 turns it on at the configured or default distance. */
    public int effectiveRtDistance() {
        int d = (int) envFloat("VULKANMOD_RT_DISTANCE", rtDistance);
        String env = System.getenv("VULKANMOD_RT");
        if (env != null) {
            if (!env.isEmpty() && env.charAt(0) == '0') return 0;
            if (d <= 0) d = RT_DISTANCE_DEFAULT;
        }
        return Math.max(0, Math.min(RT_DISTANCE_MAX, d));
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
            if (c != null && c.rayTracing != null) {
                if (c.rayTracing && c.rtDistance <= 0) c.rtDistance = RT_DISTANCE_DEFAULT;
                c.rayTracing = null;
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
