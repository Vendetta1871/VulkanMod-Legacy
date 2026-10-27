package net.vulkanmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renderer settings persisted as JSON, mirroring the original VulkanMod config.
 * The video-settings GUI screen is ported separately for the 1.12.2 widget system.
 */
public class Config {

    /** Number of swapchain images / frames in flight. */
    public int frameQueueSize = 2;
    /** Batch GUI draws through the Vulkan GUI renderer. */
    public boolean guiOptimizations = true;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static Config load(Path path) {
        if (!Files.exists(path)) {
            return null;
        }

        // L14: a corrupt or unreadable file must not kill the game - fall back to the
        // defaults (the caller writes a fresh file over the broken one).
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, Config.class);
        } catch (JsonSyntaxException e) {
            net.vulkanmod.Initializer.LOGGER.error("Failed to parse VulkanMod config, using defaults: {}", path, e);
            return null;
        } catch (IOException e) {
            net.vulkanmod.Initializer.LOGGER.error("Failed to load VulkanMod config, using defaults: {}", path, e);
            return null;
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
