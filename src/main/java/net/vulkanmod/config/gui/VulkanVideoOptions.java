package net.vulkanmod.config.gui;

import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.client.EnumHelperClient;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.rt.RtScene;

import java.util.Arrays;

/**
 * The two VulkanMod entries of the vanilla video settings list ("RTX" toggle and
 * "FSR resolution" slider).
 *
 * <p>They are not part of {@link GameSettings.Options} at compile time: Forge's
 * enum helper appends them to the enum once, on first use. The
 * {@code GuiVideoSettingsM} mixin appends them to the option array it hands to the row list, and
 * {@code GameSettingsM} answers the vanilla callback methods for them.</p>
 */
public final class VulkanVideoOptions {

    // Only used by vanilla's I18n path, which we replace in GameSettings#getKeyBinding.
    private static final String RTX_TRANSLATION = "options.vulkanmod.rtx";
    private static final String FSR_RESOLUTION_TRANSLATION = "options.vulkanmod.fsrResolution";

    /** Render scale bounds and step the slider snaps to. */
    public static final float FSR_MIN = 0.25F;
    public static final float FSR_MAX = 1.0F;
    public static final float FSR_STEP = 0.05F;

    private static GameSettings.Options rtx;
    private static GameSettings.Options fsrResolution;

    /** Boolean "RTX: ON/OFF" toggle. */
    public static GameSettings.Options rtx() {
        if (rtx == null) {
            rtx = EnumHelperClient.addOptions("VULKANMOD_RTX", RTX_TRANSLATION, false, true);
        }
        return rtx;
    }

    /** "FSR resolution" slider over {@link #FSR_MIN}..{@link #FSR_MAX}. */
    public static GameSettings.Options fsrResolution() {
        if (fsrResolution == null) {
            fsrResolution = EnumHelperClient.addOptions("VULKANMOD_FSR_RESOLUTION", FSR_RESOLUTION_TRANSLATION,
                    true, false, FSR_MIN, FSR_MAX, FSR_STEP);
        }
        return fsrResolution;
    }

    /** Identity checks that do not force the enums to be created. */
    public static boolean isRtx(GameSettings.Options option) {
        return option != null && option == rtx;
    }

    public static boolean isFsrResolution(GameSettings.Options option) {
        return option != null && option == fsrResolution;
    }

    /** Returns {@code options} with both VulkanMod entries appended. */
    public static GameSettings.Options[] appendTo(GameSettings.Options[] options) {
        GameSettings.Options rtxOption = rtx();
        GameSettings.Options fsrOption = fsrResolution();
        if (rtxOption == null || fsrOption == null) {
            return options;
        }
        GameSettings.Options[] extended = Arrays.copyOf(options, options.length + 2);
        extended[options.length] = rtxOption;
        extended[options.length + 1] = fsrOption;
        return extended;
    }

    /** Button label; "N/A" when the device cannot run the ray-tracing pipeline. */
    public static String rtxLabel() {
        if (!RtScene.supported()) {
            return "RTX: N/A";
        }
        return Initializer.CONFIG.rayTracing ? "RTX: ON" : "RTX: OFF";
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
