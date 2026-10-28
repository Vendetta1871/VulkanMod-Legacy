package net.vulkanmod.config.gui;

import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.client.EnumHelperClient;
import net.vulkanmod.Initializer;

import java.util.Arrays;

/**
 * The VulkanMod entry of the vanilla video settings list ("FSR resolution" slider); ray
 * tracing has its own screen ({@link RayTracingSettingsScreen}).
 *
 * <p>They are not part of {@link GameSettings.Options} at compile time: Forge's
 * enum helper appends it to the enum once, on first use. The
 * {@code GuiVideoSettingsM} mixin appends it to the option array it hands to the row list, and
 * {@code GameSettingsM} answers the vanilla callback methods for it.</p>
 */
public final class VulkanVideoOptions {

    // Only used by vanilla's I18n path, which we replace in GameSettings#getKeyBinding.
    private static final String FSR_RESOLUTION_TRANSLATION = "options.vulkanmod.fsrResolution";

    /** Render scale bounds and step the slider snaps to. */
    public static final float FSR_MIN = 0.25F;
    public static final float FSR_MAX = 1.0F;
    public static final float FSR_STEP = 0.05F;

    private static GameSettings.Options fsrResolution;

    /** "FSR resolution" slider over {@link #FSR_MIN}..{@link #FSR_MAX}. */
    public static GameSettings.Options fsrResolution() {
        if (fsrResolution == null) {
            fsrResolution = EnumHelperClient.addOptions("VULKANMOD_FSR_RESOLUTION", FSR_RESOLUTION_TRANSLATION,
                    true, false, FSR_MIN, FSR_MAX, FSR_STEP);
        }
        return fsrResolution;
    }

    /** Identity check that does not force the enum to be created. */
    public static boolean isFsrResolution(GameSettings.Options option) {
        return option != null && option == fsrResolution;
    }

    /** Returns {@code options} with the VulkanMod entry appended. */
    public static GameSettings.Options[] appendTo(GameSettings.Options[] options) {
        GameSettings.Options fsrOption = fsrResolution();
        if (fsrOption == null) {
            return options;
        }
        GameSettings.Options[] extended = Arrays.copyOf(options, options.length + 1);
        extended[options.length] = fsrOption;
        return extended;
    }

    /** Slider label in whole percent. */
    public static String fsrResolutionLabel() {
        return "FSR resolution: " + Math.round(fsrResolutionValue() * 100.0F) + "%";
    }

    /** Current render scale, clamped to the slider range. */
    public static float fsrResolutionValue() {
        return Math.max(FSR_MIN, Math.min(FSR_MAX, Initializer.CONFIG.renderScale));
    }

    private VulkanVideoOptions() {
    }
}
