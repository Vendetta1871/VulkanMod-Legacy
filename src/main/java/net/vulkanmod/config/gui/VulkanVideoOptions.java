package net.vulkanmod.config.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.client.EnumHelperClient;

import java.util.Arrays;

/**
 * VulkanMod's rows in the vanilla video settings list: "Upscaling..." and "Ray Tracing...",
 * buttons that open {@link UpscalingSettingsScreen} and {@link RayTracingSettingsScreen}.
 *
 * <p>They are not part of {@link GameSettings.Options} at compile time: Forge's enum helper
 * appends them to the enum once, on first use. The {@code GuiVideoSettingsM} mixin appends
 * them to the option array it hands to the row list, and {@code GameSettingsM} answers the
 * vanilla callbacks: the label, and a click opens the screen.</p>
 */
public final class VulkanVideoOptions {

    private static GameSettings.Options upscaling, rayTracing;

    private static GameSettings.Options upscaling() {
        if (upscaling == null) {
            // Only used by vanilla's I18n path, which GameSettings#getKeyBinding replaces.
            upscaling = EnumHelperClient.addOptions("VULKANMOD_UPSCALING", "options.vulkanmod.upscaling", false, false);
        }
        return upscaling;
    }

    private static GameSettings.Options rayTracing() {
        if (rayTracing == null) {
            rayTracing = EnumHelperClient.addOptions("VULKANMOD_RAY_TRACING", "options.vulkanmod.rayTracing", false, false);
        }
        return rayTracing;
    }

    /** Returns {@code options} with the VulkanMod rows appended. */
    public static GameSettings.Options[] appendTo(GameSettings.Options[] options) {
        GameSettings.Options a = upscaling(), b = rayTracing();
        if (a == null || b == null) {
            return options;
        }
        GameSettings.Options[] extended = Arrays.copyOf(options, options.length + 2);
        extended[options.length] = a;
        extended[options.length + 1] = b;
        return extended;
    }

    /** The row label, or null for options that are not VulkanMod's. */
    public static String label(GameSettings.Options option) {
        if (option == null) return null;
        if (option == upscaling) return "Upscaling...";
        if (option == rayTracing) return "Ray Tracing...";
        return null;
    }

    /** Opens the row's screen; false for options that are not VulkanMod's. */
    public static boolean click(GameSettings.Options option) {
        if (option == null || (option != upscaling && option != rayTracing)) return false;
        Minecraft mc = Minecraft.getMinecraft();
        mc.displayGuiScreen(option == upscaling
                ? new UpscalingSettingsScreen(mc.currentScreen)
                : new RayTracingSettingsScreen(mc.currentScreen));
        return true;
    }

    private VulkanVideoOptions() {
    }
}
