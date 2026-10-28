package net.vulkanmod.config.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiPageButtonList;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlider;
import net.minecraft.client.resources.I18n;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.vulkan.FrameTargets;
import net.vulkanmod.vulkan.rt.RtScene;

import java.io.IOException;

/**
 * Ray tracing settings: a preset switch (off / low / medium / high, custom once a slider
 * was moved) above one distance slider per effect (shadows, indirect light, reflections),
 * in chunks around the camera. Applied live like {@link UpscalingSettingsScreen}; saved on
 * Done, Escape or close.
 */
public class RayTracingSettingsScreen extends GuiScreen {

    private static final String TITLE = "Ray Tracing (VulkanMod)";
    private static final String[] PRESET_CYCLE = {"off", "low", "medium", "high"};
    private static final String[] EFFECTS = {"Shadows", "Indirect light", "Reflections"};

    private static final int ID_PRESET = 1;
    private static final int ID_DYNAMIC = 2;
    private static final String[] DYNAMIC_MODES = {"off", "fast", "on"};
    private static final int ID_EFFECT = 10;   // + effect index
    private static final int ID_DONE = 200;

    private static final int CONTROL_WIDTH = 200;
    private static final int CONTROL_HEIGHT = 20;

    private final GuiScreen parent;
    private final boolean supported;

    private String preset;
    private final int[] distances = new int[3];
    private String dynamicLights;
    private GuiButton dynamicButton;

    private GuiButton presetButton;
    private final GuiSlider[] sliders = new GuiSlider[3];
    private boolean committed;

    public RayTracingSettingsScreen(GuiScreen parent) {
        this.parent = parent;
        this.supported = RtScene.supported();
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.committed = false;

        Config config = Initializer.CONFIG;
        this.preset = config.rtPreset == null ? "off" : config.rtPreset;
        this.dynamicLights = config.rtDynamicLights == null ? "on" : config.rtDynamicLights;
        int[] d = Config.rtPresetDistances(this.preset);
        if (d == null) d = new int[]{config.rtShadowDistance, config.rtIndirectDistance, config.rtReflectionDistance};
        for (int i = 0; i < 3; i++) this.distances[i] = clamp(d[i]);

        int x = this.width / 2 - CONTROL_WIDTH / 2;
        int y = 50;
        this.presetButton = new GuiButton(ID_PRESET, x, y, CONTROL_WIDTH, CONTROL_HEIGHT, presetLabel());
        this.presetButton.enabled = this.supported;
        this.buttonList.add(this.presetButton);
        y += CONTROL_HEIGHT + 12;

        for (int i = 0; i < 3; i++) {
            GuiSlider slider = new GuiSlider(this.responder, ID_EFFECT + i, x, y, EFFECTS[i],
                    0.0F, Config.RT_DISTANCE_MAX, this.distances[i], this.formatHelper);
            slider.setWidth(CONTROL_WIDTH);
            slider.enabled = this.supported;
            this.sliders[i] = slider;
            this.buttonList.add(slider);
            y += CONTROL_HEIGHT + 6;
        }

        // Independent of the preset; "fast" works without ray tracing.
        y += 6;
        this.dynamicButton = new GuiButton(ID_DYNAMIC, x, y, CONTROL_WIDTH, CONTROL_HEIGHT, dynamicLabel());
        this.buttonList.add(this.dynamicButton);

        this.buttonList.add(new GuiButton(ID_DONE, this.width / 2 - 100, this.height - 27, I18n.format("gui.done")));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.drawCenteredString(this.fontRenderer, TITLE, this.width / 2, 15, 0xFFFFFF);
        String note = this.supported
                ? "Distances in chunks around the camera; farther stays vanilla."
                : "Not supported: needs a GPU with Vulkan ray tracing.";
        this.drawCenteredString(this.fontRenderer, note, this.width / 2, 30, 0xA0A0A0);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) return;
        if (button.id == ID_PRESET) {
            this.preset = nextPreset(this.preset);
            int[] d = Config.rtPresetDistances(this.preset);
            for (int i = 0; i < 3; i++) {
                this.distances[i] = d[i];
                this.sliders[i].setSliderValue(d[i], false);
            }
            this.presetButton.displayString = presetLabel();
            this.commit(false);
        } else if (button.id == ID_DYNAMIC) {
            int i = java.util.Arrays.asList(DYNAMIC_MODES).indexOf(this.dynamicLights);
            this.dynamicLights = DYNAMIC_MODES[(i + 1) % DYNAMIC_MODES.length];
            this.dynamicButton.displayString = dynamicLabel();
            this.commit(false);
        } else if (button.id == ID_DONE) {
            this.done();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            this.done();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

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

    private void commit(boolean save) {
        Config config = Initializer.CONFIG;
        config.rtDynamicLights = this.dynamicLights;
        if (!this.supported) {
            if (save) Initializer.saveConfig();
            return;
        }
        config.rtPreset = this.preset;
        config.rtShadowDistance = this.distances[0];
        config.rtIndirectDistance = this.distances[1];
        config.rtReflectionDistance = this.distances[2];
        FrameTargets.applySettings();
        if (save) Initializer.saveConfig();
    }

    private String dynamicLabel() {
        String name = switch (this.dynamicLights) {
            case "off" -> "Off";
            case "fast" -> "Fast";
            default -> this.supported ? "On (ray traced)" : "On (fast: no RT)";
        };
        return "Dynamic lights: " + name;
    }

    private String presetLabel() {
        if (!this.supported) return "Ray tracing: N/A";
        String name = switch (this.preset) {
            case "low" -> "Low";
            case "medium" -> "Medium";
            case "high" -> "High";
            case "custom" -> "Custom";
            default -> "Off";
        };
        return "Ray tracing: " + name;
    }

    /** Custom continues with off, like any other preset. */
    private static String nextPreset(String current) {
        for (int i = 0; i < PRESET_CYCLE.length; i++) {
            if (PRESET_CYCLE[i].equals(current)) return PRESET_CYCLE[(i + 1) % PRESET_CYCLE.length];
        }
        return PRESET_CYCLE[0];
    }

    private final GuiPageButtonList.GuiResponder responder = new GuiPageButtonList.GuiResponder() {
        @Override
        public void setEntryValue(int id, boolean value) {
        }

        @Override
        public void setEntryValue(int id, String value) {
        }

        @Override
        public void setEntryValue(int id, float value) {
            int i = id - ID_EFFECT;
            if (i < 0 || i >= 3) return;
            int d = clamp(Math.round(value));
            sliders[i].setSliderValue(d, false);
            if (d == distances[i]) return;
            distances[i] = d;
            // A hand-tuned distance makes the preset custom, unless it matches one again.
            preset = matchPreset(distances);
            presetButton.displayString = presetLabel();
            commit(false);
        }
    };

    private static String matchPreset(int[] d) {
        for (String p : PRESET_CYCLE) {
            if (java.util.Arrays.equals(Config.rtPresetDistances(p), d)) return p;
        }
        return "custom";
    }

    private final GuiSlider.FormatHelper formatHelper = (id, name, value) -> {
        int d = Math.round(value);
        return d <= 0 ? name + ": Off" : name + ": " + d + (d == 1 ? " chunk" : " chunks");
    };

    private static int clamp(int v) {
        return Math.max(0, Math.min(Config.RT_DISTANCE_MAX, v));
    }
}
