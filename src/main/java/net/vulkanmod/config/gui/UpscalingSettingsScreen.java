package net.vulkanmod.config.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiPageButtonList;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlider;
import net.minecraft.client.resources.I18n;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.vulkan.FrameTargets;
import net.vulkanmod.vulkan.ffx.FfxApi;

import java.io.IOException;
import java.util.Locale;

/**
 * In-game editor for the upscaling settings (render scale, upscaler, sharpening).
 *
 * <p>Edits are applied live: every slider move writes the value into
 * {@link Initializer#CONFIG} and calls {@link FrameTargets#applySettings()}, so the
 * effect is visible behind the screen. The config file is only written on Done/Escape
 * or when the screen is closed some other way.</p>
 */
public class UpscalingSettingsScreen extends GuiScreen {

    private static final String TITLE = "Upscaling (VulkanMod)";
    private static final String[] UPSCALERS = {"auto", "fsr3", "fsr1", "blit"};

    private static final int ID_RENDER_SCALE = 1;
    private static final int ID_UPSCALER = 2;
    private static final int ID_FSR1 = 3;
    private static final int ID_FSR3 = 4;
    private static final int ID_DONE = 200;

    private static final int CONTROL_WIDTH = 200;
    private static final int CONTROL_HEIGHT = 20;

    private final GuiScreen parent;
    private final boolean fsr3Available;

    // Snapped values, authoritative for both the labels and the config.
    private float renderScale;
    private String upscaler;
    private float fsr1Sharpness;
    private float fsr3Sharpness;

    private GuiSlider renderScaleSlider;
    private GuiButton upscalerButton;
    private GuiSlider fsr1Slider;
    private GuiSlider fsr3Slider;

    /** Set once the values have been written out; avoids saving twice on close. */
    private boolean committed;

    public UpscalingSettingsScreen(GuiScreen parent) {
        this.parent = parent;
        // Safe any time; tells whether the native FSR 3 library could be opened.
        this.fsr3Available = FfxApi.load();
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.committed = false;

        Config config = Initializer.CONFIG;
        // The stored values, not the effective ones: environment overrides must not be saved.
        this.renderScale = snapRenderScale(config.renderScale);
        this.upscaler = config.upscaler == null ? "auto" : config.upscaler;
        this.fsr1Sharpness = snap(config.fsr1Sharpness, 0.1F, 0.0F, 2.0F);
        this.fsr3Sharpness = snap(config.fsr3Sharpness, 0.05F, 0.0F, 1.0F);

        int x = this.width / 2 - CONTROL_WIDTH / 2;
        int y = 50;

        this.renderScaleSlider = addSlider(ID_RENDER_SCALE, x, y, "Render scale",
                0.25F, 1.0F, this.renderScale);
        y += CONTROL_HEIGHT + 6;

        this.upscalerButton = new GuiButton(ID_UPSCALER, x, y, CONTROL_WIDTH, CONTROL_HEIGHT,
                this.upscalerLabel());
        this.buttonList.add(this.upscalerButton);
        y += CONTROL_HEIGHT + 6;

        this.fsr1Slider = addSlider(ID_FSR1, x, y, "FSR 1 sharpening", 0.0F, 2.0F, this.fsr1Sharpness);
        y += CONTROL_HEIGHT + 6;

        this.fsr3Slider = addSlider(ID_FSR3, x, y, "FSR 3 sharpening", 0.0F, 1.0F, this.fsr3Sharpness);

        // Same spot as the vanilla video settings Done button.
        this.buttonList.add(new GuiButton(ID_DONE, this.width / 2 - 100, this.height - 27,
                I18n.format("gui.done")));
    }

    private GuiSlider addSlider(int id, int x, int y, String name, float min, float max, float value) {
        GuiSlider slider = new GuiSlider(this.responder, id, x, y, name, min, max, value,
                this.formatHelper);
        // GuiSlider is built 150 wide; match the column width of the other controls.
        slider.setWidth(CONTROL_WIDTH);
        this.buttonList.add(slider);
        return slider;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.drawCenteredString(this.fontRenderer, TITLE, this.width / 2, 15, 0xFFFFFF);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) {
            return;
        }
        if (button.id == ID_UPSCALER) {
            this.upscaler = nextUpscaler(this.upscaler);
            this.upscalerButton.displayString = this.upscalerLabel();
            this.commit(false);
        } else if (button.id == ID_DONE) {
            this.done();
        }
        // Slider clicks also arrive here; their value was already handled by the responder.
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            this.done();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /** Escape and Done behave the same: apply, save and return to the parent screen. */
    private void done() {
        this.committed = true;
        this.commit(true);
        this.mc.displayGuiScreen(this.parent);
    }

    @Override
    public void onGuiClosed() {
        if (!this.committed) {
            this.committed = true;
            this.commit(true);
        }
    }

    /** Writes the current values into the config; saves to disk when requested. */
    private void commit(boolean save) {
        Config config = Initializer.CONFIG;
        config.renderScale = this.renderScale;
        config.upscaler = this.upscaler;
        config.fsr1Sharpness = this.fsr1Sharpness;
        config.fsr3Sharpness = this.fsr3Sharpness;
        FrameTargets.applySettings();
        if (save) {
            Initializer.saveConfig();
        }
    }

    private String upscalerLabel() {
        String label = switch (this.upscaler) {
            case "fsr3" -> "Upscaler: FSR 3";
            case "fsr1" -> "Upscaler: FSR 1";
            case "blit" -> "Upscaler: Bilinear";
            default -> "Upscaler: Auto";
        };
        if (!this.fsr3Available && (this.upscaler.equals("auto") || this.upscaler.equals("fsr3"))) {
            label += " (FSR 3 unavailable)";
        }
        return label;
    }

    private static String nextUpscaler(String current) {
        for (int i = 0; i < UPSCALERS.length; i++) {
            if (UPSCALERS[i].equals(current)) {
                return UPSCALERS[(i + 1) % UPSCALERS.length];
            }
        }
        return UPSCALERS[0];
    }

    /** Slider values snap to their step; the labels and the config get the same number. */
    private final GuiPageButtonList.GuiResponder responder = new GuiPageButtonList.GuiResponder() {

        @Override
        public void setEntryValue(int id, boolean value) {
        }

        @Override
        public void setEntryValue(int id, String value) {
        }

        @Override
        public void setEntryValue(int id, float value) {
            switch (id) {
                case ID_RENDER_SCALE -> {
                    renderScale = snapRenderScale(value);
                    renderScaleSlider.setSliderValue(renderScale, false);
                }
                case ID_FSR1 -> {
                    fsr1Sharpness = snap(value, 0.1F, 0.0F, 2.0F);
                    fsr1Slider.setSliderValue(fsr1Sharpness, false);
                }
                case ID_FSR3 -> {
                    fsr3Sharpness = snap(value, 0.05F, 0.0F, 1.0F);
                    fsr3Slider.setSliderValue(fsr3Sharpness, false);
                }
                default -> {
                    return;
                }
            }
            commit(false);
        }
    };

    private final GuiSlider.FormatHelper formatHelper = new GuiSlider.FormatHelper() {

        @Override
        public String getText(int id, String name, float value) {
            return switch (id) {
                case ID_RENDER_SCALE -> name + ": " + Math.round(value * 100.0F) + "%";
                case ID_FSR1 -> String.format(Locale.ROOT, "%s: %.1f stops", name, value);
                case ID_FSR3 -> value <= 0.0F
                        ? name + ": off"
                        : String.format(Locale.ROOT, "%s: %.2f", name, value);
                default -> name;
            };
        }
    };

    /** Render scale in whole percent, snapped to 5% (0.25..1.0). */
    private static float snapRenderScale(float value) {
        float percent = Math.round(clamp(value, 0.25F, 1.0F) * 100.0F / 5.0F) * 5.0F;
        return percent / 100.0F;
    }

    private static float snap(float value, float step, float min, float max) {
        float snapped = Math.round(clamp(value, min, max) / step) * step;
        return clamp(snapped, min, max);
    }

    private static float clamp(float value, float min, float max) {
        return Float.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
