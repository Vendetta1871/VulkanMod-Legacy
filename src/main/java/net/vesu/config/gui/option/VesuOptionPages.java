package net.vesu.config.gui.option;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.vesu.Initializer;
import net.vesu.config.Config;
import net.vesu.config.gui.ShaderPackScreen;
import net.vesu.config.gui.option.Option.Impact;
import net.vesu.config.gui.option.Option.Storage;
import net.vesu.vulkan.ffx.FfxApi;
import net.vesu.vulkan.rt.RtScene;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The tabs of {@link net.vesu.config.gui.VesuOptionsScreen}: every vanilla video option except
 * VBOs (meaningless under Vulkan), then Vesu's own settings. Vanilla options are applied
 * through {@link GameSettings#setOptionValue} and {@link GameSettings#setOptionFloatValue},
 * so their side effects (chunk reloads, fullscreen, mipmaps) stay vanilla's.
 */
public final class VesuOptionPages {

    private static final String[] RT_PRESETS = {"off", "low", "medium", "high"};
    private static final String[] UPSCALERS = {"auto", "fsr3", "fsr1", "blit"};
    private static final String[] DYNAMIC_LIGHTS = {"off", "fast", "on"};
    private static final String RT_UNSUPPORTED = "Needs a GPU with Vulkan ray tracing.";

    public static List<OptionPage> create() {
        boolean rt = RtScene.supported();
        boolean fsr3 = FfxApi.load();
        return List.of(general(), quality(), upscaling(fsr3), rayTracing(rt), advanced(),
                OptionPage.link("Shader Packs...", ShaderPackScreen::new));
    }

    private static OptionPage general() {
        GameSettings.Options renderDistance = GameSettings.Options.RENDER_DISTANCE;
        return OptionPage.of("General", List.of(
                List.of(
                        vanillaSlider(renderDistance, 1.0f, v -> I18n.format("options.chunks", Math.round(v)))
                                .tooltip("How far away terrain is drawn. Shorter distances render fewer chunks and raise the frame rate.")
                                .impact(Impact.HIGH),
                        vanillaSlider(GameSettings.Options.GAMMA, 0.01f, VesuOptionPages::gammaText)
                                .tooltip("The brightness of dark areas. It does not change the light level of the world.")
                                .impact(Impact.LOW)),
                List.of(
                        vanillaCycle(GameSettings.Options.GUI_SCALE, () -> gs().guiScale, maxGuiScale() + 1,
                                v -> v == 0 ? I18n.format("options.guiScale.auto") : Integer.toString(v))
                                .tooltip("The size of menus, the hotbar and text. Auto picks the largest scale that fits the window; "
                                        + "a larger window allows larger scales."),
                        vanillaToggle(GameSettings.Options.USE_FULLSCREEN, () -> gs().fullScreen)
                                .tooltip("Covers the whole screen with the game."),
                        vanillaToggle(GameSettings.Options.ENABLE_VSYNC, () -> gs().enableVsync)
                                .tooltip("Syncs frames to the display's refresh rate to avoid tearing, at the cost of a little input latency.")
                                .impact(Impact.VARIES),
                        vanillaSlider(GameSettings.Options.FRAMERATE_LIMIT, 10.0f, VesuOptionPages::framerateText)
                                .tooltip("The highest frame rate the game renders at. A limit saves power when the game runs faster than needed.")),
                List.of(
                        vanillaToggle(GameSettings.Options.VIEW_BOBBING, () -> gs().viewBobbing)
                                .tooltip("The view sways while walking."),
                        vanillaCycle(GameSettings.Options.ATTACK_INDICATOR, () -> gs().attackIndicator,
                                I18n.format("options.off"), I18n.format("options.attack.crosshair"),
                                I18n.format("options.attack.hotbar"))
                                .tooltip("Where the attack cooldown is shown."))));
    }

    private static OptionPage quality() {
        return OptionPage.of("Quality", List.of(
                List.of(
                        vanillaCycle(GameSettings.Options.GRAPHICS, () -> gs().fancyGraphics ? 1 : 0,
                                I18n.format("options.graphics.fast"), I18n.format("options.graphics.fancy"))
                                .tooltip("Fancy draws transparent leaves, detailed weather and other details. Changing it rebuilds all chunks.")
                                .impact(Impact.MEDIUM),
                        vanillaCycle(GameSettings.Options.RENDER_CLOUDS, () -> gs().clouds,
                                I18n.format("options.off"), I18n.format("options.clouds.fast"),
                                I18n.format("options.clouds.fancy"))
                                .tooltip("Fast clouds are flat, Fancy clouds have volume.")
                                .impact(Impact.LOW),
                        vanillaCycle(GameSettings.Options.PARTICLES, () -> gs().particleSetting,
                                I18n.format("options.particles.all"), I18n.format("options.particles.decreased"),
                                I18n.format("options.particles.minimal"))
                                .tooltip("How many particles are spawned.")
                                .impact(Impact.MEDIUM)),
                List.of(
                        vanillaCycle(GameSettings.Options.AMBIENT_OCCLUSION, () -> gs().ambientOcclusion,
                                I18n.format("options.off"), I18n.format("options.ao.min"), I18n.format("options.ao.max"))
                                .tooltip("Soft shadows in corners and under blocks. Changing it rebuilds all chunks.")
                                .impact(Impact.LOW),
                        vanillaToggle(GameSettings.Options.ENTITY_SHADOWS, () -> gs().entityShadows)
                                .tooltip("The round shadow under mobs and items.")
                                .impact(Impact.LOW),
                        vanillaSlider(GameSettings.Options.MIPMAP_LEVELS, 1.0f,
                                v -> v < 0.5f ? I18n.format("options.off") : Integer.toString(Math.round(v)))
                                .tooltip("Smoother textures in the distance. Changing it reloads the block models when the screen closes.")
                                .impact(Impact.LOW)),
                List.of(
                        vanillaToggle(GameSettings.Options.ANAGLYPH, () -> gs().anaglyph)
                                .tooltip("Red and cyan 3D for anaglyph glasses."))));
    }

    private static OptionPage upscaling(boolean fsr3) {
        String unavailable = fsr3 ? "" : " (n/a)";
        return OptionPage.of("Upscaling", List.of(
                List.of(
                        vesuSlider("Render Scale", 0.25f, 1.0f, 0.05f, v -> Math.round(v * 100.0f) + "%",
                                () -> cfg().renderScale, v -> cfg().renderScale = v)
                                .tooltip("The world is rendered at this share of the window size and upscaled. Lower is faster; the menus stay sharp.")
                                .impact(Impact.HIGH),
                        new Option.Cycle<>("Upscaler", Storage.VESU, UPSCALERS, v -> switch (v) {
                            case "fsr3" -> "FSR 3" + unavailable;
                            case "fsr1" -> "FSR 1";
                            case "blit" -> "Bilinear";
                            default -> "Auto";
                        }, () -> cfg().upscaler == null ? "auto" : cfg().upscaler, v -> cfg().upscaler = v)
                                .tooltip("Auto uses FSR 3 when the world is scaled with ray tracing on, FSR 1 otherwise. "
                                        + "FSR 3 is temporal and also smooths edges; FSR 1 is spatial and cheaper."
                                        + (fsr3 ? "" : " The FSR 3 library could not be loaded, so FSR 3 falls back to FSR 1."))
                                .impact(Impact.MEDIUM)),
                List.of(
                        vesuSlider("FSR 1 Sharpening", 0.0f, 2.0f, 0.1f,
                                v -> String.format(Locale.ROOT, "%.1f stops", v),
                                () -> cfg().fsr1Sharpness, v -> cfg().fsr1Sharpness = v)
                                .tooltip("RCAS sharpening after FSR 1, in stops: 0 is the strongest, 2 the weakest."),
                        vesuSlider("FSR 3 Sharpening", 0.0f, 1.0f, 0.05f,
                                v -> v <= 0.0f ? "Off" : Math.round(v * 100.0f) + "%",
                                () -> cfg().fsr3Sharpness, v -> cfg().fsr3Sharpness = v)
                                .tooltip("Sharpening applied by FSR 3.")
                                .availableWhen(() -> fsr3, "The FSR 3 library could not be loaded."))));
    }

    private static OptionPage rayTracing(boolean rt) {
        Option.Slider[] distances = new Option.Slider[3];
        Option.Cycle<String> preset = new Option.Cycle<>("Preset", Storage.VESU, RT_PRESETS,
                VesuOptionPages::presetText,
                () -> cfg().rtPreset == null ? "off" : cfg().rtPreset, v -> cfg().rtPreset = v);
        preset.tooltip("Ray-traced shadows, indirect light and reflections near the camera; farther away the world stays rasterized. "
                        + "Moving a distance below makes the preset Custom. "
                        + "Shader packs that light the world pause while ray tracing is on; post-processing packs run over it.")
                .impact(Impact.HIGH)
                .availableWhen(() -> rt, RT_UNSUPPORTED)
                .onChange(() -> {
                    int[] d = Config.rtPresetDistances(preset.pending());
                    if (d != null) {
                        for (int i = 0; i < 3; i++) distances[i].set(d[i]);
                    }
                });
        String[] names = {"Shadow Distance", "Indirect Light Distance", "Reflection Distance"};
        String[] tips = {
                "Sun and moon shadows are ray traced within this many chunks of the camera.",
                "Bounced light and occlusion are ray traced within this many chunks of the camera.",
                "Water, glass and ice reflect the world within this many chunks of the camera."};
        for (int i = 0; i < 3; i++) {
            int effect = i;
            distances[i] = vesuSlider(names[i], 0.0f, Config.RT_DISTANCE_MAX, 1.0f,
                    v -> v < 0.5f ? "Off" : Math.round(v) + (Math.round(v) == 1 ? " chunk" : " chunks"),
                    () -> (float) liveDistances()[effect], v -> setDistance(effect, Math.round(v)));
            distances[i].tooltip(tips[i]).impact(Impact.HIGH).availableWhen(() -> rt, RT_UNSUPPORTED)
                    .onChange(() -> preset.set(matchPreset(distances)));
        }
        return OptionPage.of("Ray Tracing", List.of(
                List.of(preset),
                List.of(distances[0], distances[1], distances[2]),
                List.of(
                        percentSlider("Shadow Strength", () -> cfg().rtShadowStrength, v -> cfg().rtShadowStrength = v)
                                .tooltip("How much ray-traced shadows darken the ground.")
                                .availableWhen(() -> rt, RT_UNSUPPORTED),
                        percentSlider("Block Light", () -> cfg().rtBlockLight, v -> cfg().rtBlockLight = v)
                                .tooltip("How much ray-traced light from torches, lava and glowstone replaces vanilla's.")
                                .availableWhen(() -> rt, RT_UNSUPPORTED),
                        percentSlider("Indirect Light", () -> cfg().rtIndirect, v -> cfg().rtIndirect = v)
                                .tooltip("The strength of bounced light and occlusion.")
                                .availableWhen(() -> rt, RT_UNSUPPORTED),
                        percentSlider("Reflections", () -> cfg().rtReflections, v -> cfg().rtReflections = v)
                                .tooltip("The strength of ray-traced reflections on water, glass and ice.")
                                .availableWhen(() -> rt, RT_UNSUPPORTED)),
                List.of(
                        new Option.Cycle<>("Dynamic Lights", Storage.VESU, DYNAMIC_LIGHTS, v -> switch (v) {
                            case "off" -> "Off";
                            case "fast" -> "Fast";
                            default -> rt ? "Ray Traced" : "Fast (no RT)";
                        }, () -> cfg().rtDynamicLights == null ? "on" : cfg().rtDynamicLights,
                                v -> cfg().rtDynamicLights = v)
                                .tooltip("Held and dropped torches light up their surroundings. "
                                        + "Fast works without ray tracing; Ray Traced also casts shadows.")
                                .impact(Impact.LOW))));
    }

    private static OptionPage advanced() {
        return OptionPage.of("Advanced", List.of(
                List.of(
                        vesuSlider("Frames in Flight", 2.0f, 4.0f, 1.0f, v -> Integer.toString(Math.round(v)),
                                () -> (float) cfg().frameQueueSize, v -> cfg().frameQueueSize = Math.round(v))
                                .tooltip("How many frames the CPU may prepare ahead of the GPU. More frames smooth out the frame rate, "
                                        + "fewer lower the input latency.")
                                .impact(Impact.MEDIUM)
                                .restart())));
    }

    // ---- Vanilla options ----

    private static GameSettings gs() {
        return Minecraft.getMinecraft().gameSettings;
    }

    private static String name(GameSettings.Options option) {
        return I18n.format(option.getTranslation());
    }

    private static Option.Toggle vanillaToggle(GameSettings.Options option, Supplier<Boolean> getter) {
        // Every vanilla boolean option toggles on setOptionValue, whatever the value passed.
        return new Option.Toggle(name(option), Storage.VANILLA, getter, v -> {
            if (v != getter.get()) gs().setOptionValue(option, 1);
        });
    }

    private static Option.Cycle<Integer> vanillaCycle(GameSettings.Options option, IntSupplier getter, String... labels) {
        return vanillaCycle(option, getter, labels.length, i -> i >= 0 && i < labels.length ? labels[i] : Integer.toString(i));
    }

    /** Vanilla adds the value passed to setOptionValue, modulo the number of choices. */
    private static Option.Cycle<Integer> vanillaCycle(GameSettings.Options option, IntSupplier getter, int count,
                                                      Function<Integer, String> label) {
        Integer[] values = new Integer[count];
        for (int i = 0; i < count; i++) values[i] = i;
        return new Option.Cycle<>(name(option), Storage.VANILLA, values, label, getter::getAsInt, v -> {
            int delta = Math.floorMod(v - getter.getAsInt(), count);
            if (delta != 0) gs().setOptionValue(option, delta);
        });
    }

    /** Cleanroom numbers the GUI scales 1..max (0 is auto), max being what still fits 320x240. */
    private static int maxGuiScale() {
        Minecraft mc = Minecraft.getMinecraft();
        int max = 1;
        while (mc.displayWidth / (max + 1) >= 320 && mc.displayHeight / (max + 1) >= 240) max++;
        return max;
    }

    private static Option.Slider vanillaSlider(GameSettings.Options option, float step, Function<Float, String> format) {
        return new Option.Slider(name(option), Storage.VANILLA, option.getValueMin(), option.getValueMax(), step, format,
                () -> gs().getOptionFloatValue(option), v -> gs().setOptionFloatValue(option, v));
    }

    private static String gammaText(float v) {
        if (v <= 0.0f) return I18n.format("options.gamma.min");
        if (v >= 1.0f) return I18n.format("options.gamma.max");
        return "+" + Math.round(v * 100.0f) + "%";
    }

    private static String framerateText(float v) {
        return v >= GameSettings.Options.FRAMERATE_LIMIT.getValueMax()
                ? I18n.format("options.framerateLimit.max")
                : I18n.format("options.framerate", Math.round(v));
    }

    // ---- Vesu options ----

    private static Config cfg() {
        return Initializer.CONFIG;
    }

    private static Option.Slider vesuSlider(String name, float min, float max, float step, Function<Float, String> format,
                                            Supplier<Float> getter, Consumer<Float> setter) {
        return new Option.Slider(name, Storage.VESU, min, max, step, format, getter, setter);
    }

    private static Option.Slider percentSlider(String name, Supplier<Float> getter, Consumer<Float> setter) {
        return vesuSlider(name, 0.0f, 1.0f, 0.05f, v -> v <= 0.0f ? "Off" : Math.round(v * 100.0f) + "%", getter, setter);
    }

    private static String presetText(String preset) {
        return switch (preset) {
            case "low" -> "Low";
            case "medium" -> "Medium";
            case "high" -> "High";
            case "custom" -> "Custom";
            default -> "Off";
        };
    }

    /** The distances in effect: a named preset's, else the stored ones. */
    private static int[] liveDistances() {
        int[] d = Config.rtPresetDistances(cfg().rtPreset);
        return d != null ? d : new int[]{cfg().rtShadowDistance, cfg().rtIndirectDistance, cfg().rtReflectionDistance};
    }

    private static void setDistance(int effect, int chunks) {
        switch (effect) {
            case 0 -> cfg().rtShadowDistance = chunks;
            case 1 -> cfg().rtIndirectDistance = chunks;
            default -> cfg().rtReflectionDistance = chunks;
        }
    }

    /** The named preset with these distances, else custom. */
    private static String matchPreset(Option.Slider[] distances) {
        for (String p : RT_PRESETS) {
            int[] d = Config.rtPresetDistances(p);
            if (d[0] == Math.round(distances[0].pending()) && d[1] == Math.round(distances[1].pending())
                    && d[2] == Math.round(distances[2].pending())) return p;
        }
        return "custom";
    }

    private VesuOptionPages() {
    }
}
