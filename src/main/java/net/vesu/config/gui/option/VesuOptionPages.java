package net.vesu.config.gui.option;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.vesu.Initializer;
import net.vesu.config.Config;
import net.vesu.config.DisplayModes;
import net.vesu.config.gui.ShaderOptionsScreen;
import net.vesu.config.gui.VesuOptionsScreen;
import net.vesu.config.gui.option.Option.Impact;
import net.vesu.config.gui.option.Option.Storage;
import net.vesu.pack.PackKind;
import net.vesu.shader.PackManager;
import net.vesu.vulkan.PackRuntime;
import net.vesu.vulkan.ffx.FfxApi;
import net.vesu.vulkan.rt.RtScene;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The pages of {@link VesuOptionsScreen}: every vanilla video option except VBOs
 * (meaningless under Vulkan), then Vesu's own settings. Vanilla options are applied through
 * {@link GameSettings#setOptionValue} and {@link GameSettings#setOptionFloatValue}, so their
 * side effects (chunk reloads, fullscreen, mipmaps) stay vanilla's.
 */
public final class VesuOptionPages {

    public static final String SHADER_PACKS = "Shader Packs";

    private static final String[] RT_PRESETS = {"off", "potato", "low", "medium", "high", "ultra", "nasa"};
    private static final String[] SCALING = {"auto", "blit", "fsr1", "fsr3"};
    private static final String RT_UNSUPPORTED = "Needs a GPU with Vulkan ray tracing.";
    private static final String NOT_YET = "Not available yet.";

    public static List<OptionPage> create() {
        boolean rt = RtScene.supported();
        // The Ray Tracing page's dynamic lighting depends on the General page's.
        Option.Cycle<String> dynamicLights = dynamicLights();
        return new ArrayList<>(List.of(general(dynamicLights), quality(), renderDistance(), rayTracing(rt, dynamicLights),
                shaderPacks(), advanced()));
    }

    private static Option.Cycle<String> dynamicLights() {
        Option.Cycle<String> dynamicLights = new Option.Cycle<>("Dynamic Lighting", Storage.VESU, new String[]{"off", "auto"},
                v -> v.equals("off") ? "Off" : "Auto", () -> "off".equals(cfg().dynamicLights) ? "off" : "auto",
                v -> cfg().dynamicLights = v);
        dynamicLights.tooltip("Held and dropped torches and burning mobs light up their surroundings. "
                        + "Auto turns it off while a shader pack lights the world; with ray tracing on, the Ray Tracing page picks how it is drawn.")
                .impact(Impact.LOW);
        return dynamicLights;
    }

    private static OptionPage general(Option.Cycle<String> dynamicLights) {
        boolean fsr3 = FfxApi.load();
        Option.Toggle fullscreen = vanillaToggle(GameSettings.Options.USE_FULLSCREEN, () -> gs().fullScreen);
        fullscreen.tooltip("Covers the whole screen with the game.");

        // A slider over Current (the desktop mode) and then the modes from the smallest up, as in newer Minecraft.
        List<String> modes = new ArrayList<>(DisplayModes.list());
        java.util.Collections.reverse(modes);
        modes.add(0, null);
        String current = cfg().fullscreenMode;
        if (current != null && !modes.contains(current)) modes.add(current);
        Option resolution = new Option.Slider("Fullscreen Resolution", Storage.VESU, 0.0f, Math.max(1, modes.size() - 1), 1.0f,
                v -> DisplayModes.label(modes.get(Math.min(modes.size() - 1, Math.round(v)))),
                () -> (float) Math.max(0, modes.indexOf(cfg().fullscreenMode)),
                v -> {
                    cfg().fullscreenMode = modes.get(Math.min(modes.size() - 1, Math.round(v)));
                    DisplayModes.apply(Minecraft.getMinecraft().isFullScreen(), true);
                })
                .tooltip("The display's video mode in fullscreen. Current keeps the desktop's mode. "
                        + "A lower mode makes the monitor scale the picture; Render Scale is usually the better way to gain speed.")
                .availableWhen(() -> DisplayModes.supported() && fullscreen.pending(),
                        () -> DisplayModes.supported() ? "Applies in fullscreen only."
                                : "Cleanroom's borderless fullscreen is on (Forge early config), so the display mode cannot change.");

        Option.Cycle<String> scaling = new Option.Cycle<>("Scaling Method", Storage.VESU, SCALING, v -> switch (v) {
            case "blit" -> "Bilinear";
            case "fsr1" -> "FSR 1";
            case "fsr3" -> "FSR 3" + (fsr3 ? "" : " (n/a)");
            default -> "Auto";
        }, () -> cfg().upscaler == null ? "auto" : cfg().upscaler, v -> cfg().upscaler = v);
        scaling.tooltip("How the world is brought to the window size. Auto uses FSR 3 when the world is scaled down with ray tracing on, "
                        + "FSR 1 otherwise. FSR 3 is temporal and also smooths edges; FSR 1 is spatial and cheaper; Bilinear is plain filtering. "
                        + "Above 100% the world is always filtered down bilinearly."
                        + (fsr3 ? "" : " The FSR 3 library could not be loaded, so FSR 3 falls back to FSR 1."))
                .impact(Impact.MEDIUM);


        return OptionPage.of("General", List.of(
                List.of(
                        resolution,
                        vesuSlider("Render Scale", Config.RENDER_SCALE_MIN, Config.RENDER_SCALE_MAX, 0.05f, v -> Math.round(v * 100.0f) + "%",
                                () -> cfg().renderScale, v -> cfg().renderScale = v)
                                .tooltip("The world is rendered at this share of the window size; the menus stay sharp. "
                                        + "Below 100% it is upscaled and faster; above 100% it is supersampled: smoother edges, much slower.")
                                .impact(Impact.HIGH),
                        scaling,
                        new Option.Toggle("Frame Generation", Storage.VESU, () -> cfg().frameGeneration,
                                v -> cfg().frameGeneration = v)
                                .tooltip("FSR 3 inserts a generated frame between every two rendered ones: smoother motion at "
                                        + "about the same input latency as without it. Works while FSR 3 upscales the world "
                                        + "(Scaling Method FSR 3, or Auto with ray tracing); menus and shader packs show rendered frames only.")
                                .impact(Impact.LOW)
                                .availableWhen(() -> fsr3 && !scaling.pending().equals("blit") && !scaling.pending().equals("fsr1"),
                                        () -> fsr3 ? "Needs FSR 3 as the scaling method." : "The FSR 3 library could not be loaded."),
                        vesuSlider("FSR Sharpening", 0.0f, 1.0f, 0.05f, v -> v <= 0.0f ? "Off" : Math.round(v * 100.0f) + "%",
                                () -> cfg().fsrSharpening, v -> cfg().fsrSharpening = v)
                                .tooltip("Sharpening after FSR 1 and FSR 3.")
                                .availableWhen(() -> !scaling.pending().equals("blit"), "Bilinear scaling does not sharpen.")),
                List.of(
                        vanillaSlider(GameSettings.Options.GAMMA, 0.01f, VesuOptionPages::gammaText)
                                .tooltip("The brightness of dark areas. It does not change the light level of the world.")
                                .impact(Impact.LOW),
                        dynamicLights),
                List.of(
                        vanillaCycle(GameSettings.Options.GUI_SCALE, () -> gs().guiScale, maxGuiScale() + 1, maxGuiScale() + 1,
                                v -> v == 0 ? I18n.format("options.guiScale.auto") : Integer.toString(v))
                                .tooltip("The size of menus, the hotbar and text. Auto picks the largest scale that fits the window; "
                                        + "a larger window allows larger scales."),
                        fullscreen,
                        vanillaToggle(GameSettings.Options.ENABLE_VSYNC, () -> gs().enableVsync)
                                .tooltip("Syncs frames to the display's refresh rate to avoid tearing, at the cost of a little input latency.")
                                .impact(Impact.VARIES),
                        new Option.Slider("Game Framerate", Storage.VANILLA, GameSettings.Options.FRAMERATE_LIMIT.getValueMin(),
                                GameSettings.Options.FRAMERATE_LIMIT.getValueMax(), 10.0f, VesuOptionPages::framerateText,
                                () -> gs().getOptionFloatValue(GameSettings.Options.FRAMERATE_LIMIT),
                                v -> gs().setOptionFloatValue(GameSettings.Options.FRAMERATE_LIMIT, v))
                                .tooltip("The highest frame rate while playing. A limit saves power when the game runs faster than needed."),
                        vesuSlider("Menu Framerate", 10.0f, 240.0f, 10.0f, v -> Math.round(v) + " fps",
                                () -> (float) cfg().menuFramerate, v -> cfg().menuFramerate = Math.round(v))
                                .tooltip("The highest frame rate while the game is paused, on the title menus or minimized. "
                                        + "It never goes above the game frame rate.")),
                List.of(
                        vanillaToggle(GameSettings.Options.VIEW_BOBBING, () -> gs().viewBobbing)
                                .tooltip("The view sways while walking."),
                        vanillaCycle(GameSettings.Options.ATTACK_INDICATOR, () -> gs().attackIndicator,
                                I18n.format("options.off"), I18n.format("options.attack.crosshair"),
                                I18n.format("options.attack.hotbar"))
                                .tooltip("Where the attack cooldown is shown."))));
    }

    private static OptionPage renderDistance() {
        return OptionPage.of("Render Distance", List.of(
                List.of(
                        vanillaSlider(GameSettings.Options.RENDER_DISTANCE, 1.0f, VesuOptionPages::chunks)
                                .tooltip("How far away terrain is drawn. Shorter distances render fewer chunks and raise the frame rate.")
                                .impact(Impact.HIGH),
                        vesuSlider("Simulation Distance", Config.SIMULATION_DISTANCE_MIN, Config.SIMULATION_DISTANCE_MAX, 1.0f,
                                VesuOptionPages::chunks, () -> (float) cfg().simulationDistance, v -> cfg().simulationDistance = Math.round(v))
                                .tooltip("How far from you mobs move, crops grow, furnaces smelt and redstone runs. Farther chunks up to the "
                                        + "render distance stay visible but frozen. Chunks kept loaded by mods always run. Singleplayer only; "
                                        + "a server decides this for itself.")
                                .impact(Impact.MEDIUM)),
                List.of(
                        new Option.Toggle("Distant Terrain", Storage.VESU, () -> cfg().lodEnabled, v -> cfg().lodEnabled = v)
                                .tooltip("Simplified terrain beyond the render distance, built from the chunks you have seen and "
                                        + "kept per world. Off: nothing of it runs.")
                                .impact(Impact.MEDIUM)
                                .onChange(net.vesu.lod.mc.LodSystem::applySettings),
                        vesuSlider("LOD Distance", Config.LOD_DISTANCE_MIN, Config.LOD_DISTANCE_MAX, 32.0f,
                                VesuOptionPages::chunks, () -> (float) cfg().lodDistance, v -> cfg().lodDistance = Math.round(v / 32.0f) * 32)
                                .tooltip("Simplified terrain is drawn out to this many chunks.")
                                .impact(Impact.MEDIUM),
                        new Option.Cycle<>("LOD Quality", Storage.VESU, new Integer[]{128, 64, 40},
                                q -> q == 128 ? "Low" : q == 64 ? "Medium" : q == 40 ? "High" : q + " px",
                                () -> cfg().lodQuality, v -> cfg().lodQuality = v)
                                .tooltip("How large a piece of distant terrain may look on screen before it is split into finer detail.")
                                .impact(Impact.MEDIUM),
                        new Option.Cycle<>("LOD Fog", Storage.VESU, new String[]{"fog+fade", "fog", "fade", "off"},
                                f -> switch (f) {
                                    case "fog+fade" -> "Fog and Fade";
                                    case "fog" -> "Fog";
                                    case "fade" -> "Fade";
                                    default -> "Off";
                                }, () -> cfg().lodFog, v -> cfg().lodFog = v)
                                .tooltip("Fog follows the LOD distance instead of the render distance; the fade blends the outer edge into the sky."),
                        new Option.Cycle<>("LOD Ambient Occlusion", Storage.VESU, new String[]{"auto", "basic", "better", "best", "off"},
                                m -> switch (m) {
                                    case "auto" -> "Auto";
                                    case "basic" -> "Basic";
                                    case "better" -> "Better";
                                    case "best" -> "Best";
                                    default -> "Off";
                                }, () -> cfg().lodSsao, v -> cfg().lodSsao = v)
                                .tooltip("Screen-space shading in the corners of distant terrain, which has no smooth lighting. "
                                        + "Auto picks by the graphics card. Not used with shader packs.")
                                .impact(Impact.LOW),
                        vesuSlider("LOD Generation Distance", 0, 512, 16.0f,
                                v -> Math.round(v) == 0 ? "Off" : chunks(v), () -> (float) cfg().lodGenerationDistance,
                                v -> cfg().lodGenerationDistance = Math.round(v / 16.0f) * 16)
                                .tooltip("Singleplayer: chunks out to this distance are generated in the background so distant terrain "
                                        + "exists before you go there. They are saved into the world, as a pregenerator would. "
                                        + "Servers decide this for themselves.")
                                .impact(Impact.MEDIUM)
                                .availableWhen(() -> Minecraft.getMinecraft().world == null
                                        || Minecraft.getMinecraft().isIntegratedServerRunning(), "Singleplayer only"))));
    }

    private static OptionPage rayTracing(boolean rt, Option.Cycle<String> general) {
        Option.Slider[] distances = new Option.Slider[3];
        Option.Cycle<String> preset = new Option.Cycle<>("Ray Tracing", Storage.VESU, RT_PRESETS,
                VesuOptionPages::presetText,
                () -> cfg().rtPreset == null ? "off" : cfg().rtPreset, v -> cfg().rtPreset = v);
        preset.tooltip("Ray-traced shadows, indirect light and reflections near the camera; farther away the world stays rasterized. "
                        + "Not compatible with shader packs: a pack that lights the world pauses while ray tracing is on, "
                        + "only post-processing packs run over it. Moving a distance below makes the preset Custom.")
                .impact(Impact.HIGH)
                .availableWhen(() -> rt, RT_UNSUPPORTED)
                .onChange(() -> {
                    int[] d = Config.rtPresetDistances(preset.pending());
                    if (d != null) {
                        for (int i = 0; i < 3; i++) distances[i].set(d[i]);
                    }
                });
        String[] names = {"Shadows", "Indirect Light", "Reflections"};
        String[] tips = {
                "Sun and moon shadows are ray traced within this many chunks of the camera.",
                "Bounced light and occlusion are ray traced within this many chunks of the camera.",
                "Water, glass and ice reflect the world within this many chunks of the camera."};
        for (int i = 0; i < 3; i++) {
            int effect = i;
            distances[i] = vesuSlider(names[i], 0.0f, Config.RT_DISTANCE_MAX, 1.0f,
                    v -> v < 0.5f ? "Off" : chunks(v),
                    () -> (float) liveDistances()[effect], v -> setDistance(effect, Math.round(v)));
            distances[i].tooltip(tips[i]).impact(Impact.HIGH).availableWhen(() -> rt, RT_UNSUPPORTED)
                    .onChange(() -> preset.set(matchPreset(distances)));
        }
        Option dynamic = new Option.Cycle<>("Dynamic Lighting", Storage.VESU, new String[]{"fast", "rt"},
                v -> v.equals("fast") ? "Fast" : "Ray Traced", () -> "fast".equals(cfg().rtDynamicLights) ? "fast" : "rt",
                v -> cfg().rtDynamicLights = v)
                .tooltip("How dynamic lights are drawn while ray tracing is on. Fast brightens the surroundings like without ray tracing; "
                        + "Ray Traced adds them to the traced light, with shadows.")
                .impact(Impact.LOW)
                .availableWhen(() -> rt && !general.pending().equals("off"),
                        () -> rt ? "Dynamic Lighting is off on the General page." : RT_UNSUPPORTED);
        return OptionPage.of("Ray Tracing [Beta]", List.of(
                List.of(preset, dynamic),
                List.of(distances[0], distances[1], distances[2])));
    }

    /** The pack list, scanned afresh: Off, the built-in packs, the folder's packs, then Rescan and Pack Options. */
    public static OptionPage shaderPacks() {
        PackManager.rescan();
        List<PackManager.Entry> entries = PackManager.entries();
        String[] ids = new String[entries.size() + 1];
        ids[0] = "";
        for (int i = 0; i < entries.size(); i++) ids[i + 1] = entries.get(i).id();
        Option.Cycle<String> choice = new Option.Cycle<>("Shader Pack", Storage.VESU, ids, v -> v,
                () -> PackManager.selectedId() == null ? "" : PackManager.selectedId(),
                v -> PackManager.select(v.isEmpty() ? null : v));

        List<Option> builtIn = new ArrayList<>();
        builtIn.add(new Option.Radio("Off", choice, "", "", () -> "Draws the world with Vesu's own shaders."));
        List<Option> folder = new ArrayList<>();
        for (PackManager.Entry e : entries) {
            Option row = new Option.Radio(e.name(), choice, e.id(), "[" + e.format() + "]", () -> packInfo(e))
                    .availableWhen(e::usable, () -> e.message() != null ? e.message() : "This pack cannot be used.");
            (e.id().startsWith(PackManager.BUILTIN_PREFIX) ? builtIn : folder).add(row);
        }

        Option rescan = new Option.Action("Rescan Folder", screen -> ((VesuOptionsScreen) screen).reloadPage(SHADER_PACKS)).tooltip("Looks for new packs in " + PackManager.DIR.toAbsolutePath() + ". Put pack folders or zips there.");
        Option options = new Option.Action("Pack Options...", screen -> {
            ((VesuOptionsScreen) screen).applyPending();
            if (PackManager.activeOptions() != null) {
                Minecraft.getMinecraft().displayGuiScreen(new ShaderOptionsScreen(screen, PackManager.activeOptions()));
            }
        }).tooltip("The selected pack's own settings.")
                .availableWhen(() -> kindOf(entries, choice.pending()) == PackKind.OPTIFINE,
                        "Only OptiFine and Iris packs have options.");

        List<List<Option>> groups = new ArrayList<>();
        groups.add(builtIn);
        if (!folder.isEmpty()) groups.add(folder);
        groups.add(List.of(rescan, options));
        return new OptionPage(SHADER_PACKS, groups, List.of(choice));
    }

    private static PackKind kindOf(List<PackManager.Entry> entries, String id) {
        for (PackManager.Entry e : entries) {
            if (e.id().equals(id)) return e.kind();
        }
        return null;
    }

    private static String packInfo(PackManager.Entry e) {
        StringBuilder s = new StringBuilder(switch (e.format()) {
            case "Iris" -> "An OptiFine-format pack that uses Iris features.";
            case "OptiFine" -> "An OptiFine-format pack.";
            case "Bedrock" -> "A Bedrock Vibrant Visuals pack.";
            case "Native" -> "A Vesu pack.";
            default -> "A " + e.format() + " pack.";
        });
        if (e.message() != null && e.usable()) s.append(' ').append(e.message()).append('.');
        if (e.id().equals(PackManager.selectedId())) {
            String compiling = PackRuntime.compiling();
            if (compiling != null) {
                s.append(" Compiling: ").append(PackRuntime.compilePercent()).append("%, the picture changes when it is done.");
            } else if (PackManager.activeError() != null) {
                s.append(" Failed: ").append(PackManager.activeError());
            } else if (PackManager.pausedByRt(PackManager.active())) {
                s.append(" Paused: ray tracing is on. Turn it off to use this pack.");
            }
        }
        return s.toString();
    }

    private static OptionPage quality() {
        return OptionPage.of("Quality", List.of(
                List.of(
                        vanillaCycle(GameSettings.Options.GRAPHICS, () -> gs().fancyGraphics ? 1 : 0,
                                I18n.format("options.graphics.fast"), I18n.format("options.graphics.fancy"))
                                .tooltip("Fancy draws transparent leaves, detailed weather and other details. Changing it rebuilds all chunks.")
                                .impact(Impact.MEDIUM),
                        vanillaCycle(GameSettings.Options.RENDER_CLOUDS, () -> gs().clouds, 4, 3,
                                v -> switch (v) {
                                    case 0 -> I18n.format("options.off");
                                    case 1 -> I18n.format("options.clouds.fast");
                                    case 2 -> I18n.format("options.clouds.fancy");
                                    default -> "Better";
                                })
                                .skip(3)
                                .tooltip("Fast clouds are flat, Fancy clouds have volume. Better, volumetric clouds, is not available yet.")
                                .impact(Impact.LOW),
                        new Option.Cycle<>("Precipitation", Storage.VESU, new String[]{"off", "fancy", "particle"}, v -> switch (v) {
                            case "off" -> "Off";
                            case "particle" -> "Particle";
                            default -> "Fancy";
                        }, () -> "off".equals(cfg().precipitation) ? "off" : "fancy", v -> cfg().precipitation = v)
                                .skip("particle")
                                .tooltip("Rain and snow. Off hides them and their splashes; the rain still sounds. "
                                        + "Particle, drops as particles, is not available yet.")
                                .impact(Impact.LOW),
                        vanillaCycle(GameSettings.Options.PARTICLES, () -> gs().particleSetting,
                                I18n.format("options.particles.all"), I18n.format("options.particles.decreased"),
                                I18n.format("options.particles.minimal"))
                                .tooltip("How many particles are spawned.")
                                .impact(Impact.MEDIUM),
                        placeholder("Foliage", gs().fancyGraphics ? "Fancy" : "Fast",
                                "Leaves and plants: Potato, Fast or Fancy. For now Graphics decides.")),
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

    /** A row for a setting that is planned but not there yet: shown, greyed out. */
    private static Option placeholder(String name, String value, String tooltip) {
        return new Option.Cycle<>(name, Storage.VESU, new String[]{value}, v -> v, () -> value, v -> { })
                .tooltip(tooltip)
                .availableWhen(() -> false, NOT_YET);
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
        return vanillaCycle(option, getter, labels.length, labels.length,
                i -> i >= 0 && i < labels.length ? labels[i] : Integer.toString(i));
    }

    /**
     * {@code shown} values 0..shown-1. Vanilla adds the value passed to setOptionValue modulo
     * its own number of choices, {@code count}, which is less when a listed value is not offered yet.
     */
    private static Option.Cycle<Integer> vanillaCycle(GameSettings.Options option, IntSupplier getter, int shown, int count,
                                                      Function<Integer, String> label) {
        Integer[] values = new Integer[shown];
        for (int i = 0; i < shown; i++) values[i] = i;
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

    private static String chunks(float v) {
        int n = Math.round(v);
        return n + (n == 1 ? " chunk" : " chunks");
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

    private static String presetText(String preset) {
        return switch (preset) {
            case "potato" -> "Potato";
            case "low" -> "Low";
            case "medium" -> "Medium";
            case "high" -> "High";
            case "ultra" -> "Ultra";
            case "nasa" -> "NASA Supercomputer";
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
