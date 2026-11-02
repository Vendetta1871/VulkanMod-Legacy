package net.vesu.pack.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.vesu.pack.ir.IrPack;
import net.vesu.pack.source.PackSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Bedrock frontend (frontends.md): a Vibrant Visuals resource pack has no shaders, only
 * materials and lighting settings. It lowers to a deferred PBR pipeline of ours, whose constants
 * and key-framed curves are generated from the settings JSON. The materials become the
 * {@code normals} and {@code specular} atlases in the neutral layout (D11), see BedrockMaterials
 * in the mod. The model follows vanilla Vibrant Visuals; frontends.md says what differs.
 */
public final class BedrockFrontend {

    private static final String DIR = "vkm_vv/";

    public record Result(IrPack pack, Map<String, String> programs, List<String> warnings) {
    }

    private final PackSource src;
    private final List<String> warnings = new ArrayList<>();

    private BedrockFrontend(PackSource src) {
        this.src = src;
    }

    public static Result lower(PackSource src, String id) throws IOException {
        return new BedrockFrontend(src).run(id);
    }

    private JsonObject json(String path, String root) throws IOException {
        if (!src.exists(path)) return null;
        // Bedrock JSON allows comments.
        String text = src.read(path).replaceAll("(?m)^\\s*//.*$", "");
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        return o.has(root) ? o.getAsJsonObject(root) : null;
    }

    /** The names of the client biomes by numeric id; Bedrock kept the ids and names of 1.12. */
    private static final String[] BIOMES = {"ocean", "plains", "desert", "extreme_hills", "forest", "taiga", "swampland", "river",
            "hell", "the_end", "frozen_ocean", "frozen_river", "ice_plains", "ice_mountains", "mushroom_island",
            "mushroom_island_shore", "beach", "desert_hills", "forest_hills", "taiga_hills", "extreme_hills_edge", "jungle",
            "jungle_hills", "jungle_edge", "deep_ocean", "stone_beach", "cold_beach", "birch_forest", "birch_forest_hills",
            "roofed_forest", "cold_taiga", "cold_taiga_hills", "mega_taiga", "mega_taiga_hills", "extreme_hills_plus_trees",
            "savanna", "savanna_plateau", "mesa", "mesa_plateau_stone", "mesa_plateau"};

    /** One combination of settings; biomes that share it share a profile. */
    private record Profile(JsonObject lighting, JsonObject atmosphere, JsonObject grading, JsonObject fog,
                           JsonObject water, JsonElement waterColor, double waterAlpha) {
    }

    private final Map<JsonObject, Integer> emitted = new java.util.IdentityHashMap<>();
    private int nextCurves;

    private Map<String, JsonObject> byIdentifier(String dir, String root) throws IOException {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (String file : src.list(dir)) {
            if (!file.endsWith(".json")) continue;
            JsonObject o = json(dir + "/" + file, root);
            JsonObject d = o == null ? null : path(o, "description");
            if (d != null && d.has("identifier")) out.put(d.get("identifier").getAsString(), o);
        }
        return out;
    }

    private static String identifier(JsonObject components, String component, String key) {
        JsonObject c = path(components, component);
        return c == null || !c.has(key) ? null : c.get(key).getAsString();
    }

    private Result run(String id) throws IOException {
        JsonObject lighting = json("lighting/global.json", "minecraft:lighting_settings");
        baseLighting = lighting;
        JsonObject atmosphere = json("atmospherics/atmospherics.json", "minecraft:atmosphere_settings");
        JsonObject grading = json("color_grading/color_grading.json", "minecraft:color_grading_settings");
        if (lighting == null) warnings.add("lighting/global.json is missing, using defaults");
        if (atmosphere == null) warnings.add("atmospherics/atmospherics.json is missing, using defaults");

        JsonObject biome = defaultBiome();
        JsonElement defaultWater = biome != null && biome.has("water_surface_color") ? biome.get("water_surface_color") : new JsonPrimitive("#44AFF5");
        double defaultAlpha = biome != null && biome.has("water_surface_transparency") ? biome.get("water_surface_transparency").getAsDouble() : 0.65;
        JsonObject waterSettings = json("water/water.json", "minecraft:water_settings");
        Profile base = new Profile(lighting, atmosphere, grading, fogFile(), waterSettings, defaultWater, defaultAlpha);

        Map<String, JsonObject> lightings = byIdentifier("lighting", "minecraft:lighting_settings");
        Map<String, JsonObject> atmospheres = byIdentifier("atmospherics", "minecraft:atmosphere_settings");
        Map<String, JsonObject> gradings = byIdentifier("color_grading", "minecraft:color_grading_settings");
        Map<String, JsonObject> fogs = byIdentifier("fogs", "minecraft:fog_settings");
        Map<String, JsonObject> waters = byIdentifier("water", "minecraft:water_settings");

        List<Profile> profiles = new ArrayList<>(List.of(base));
        int[] table = new int[256];
        Map<String, Integer> byName = new java.util.HashMap<>();
        for (String file : src.list("biomes")) {
            if (!file.endsWith(".client_biome.json")) continue;
            JsonObject client = json("biomes/" + file, "minecraft:client_biome");
            JsonObject c = client == null ? null : path(client, "components");
            if (c == null) continue;
            JsonObject water = path(c, "minecraft:water_appearance");
            Profile p = new Profile(
                    lightings.getOrDefault(identifier(c, "minecraft:lighting_identifier", "lighting_identifier"), lighting),
                    atmospheres.getOrDefault(identifier(c, "minecraft:atmosphere_identifier", "atmosphere_identifier"), atmosphere),
                    gradings.getOrDefault(identifier(c, "minecraft:color_grading_identifier", "color_grading_identifier"), grading),
                    fogs.getOrDefault(identifier(c, "minecraft:fog_appearance", "fog_identifier"), base.fog),
                    waters.getOrDefault(identifier(c, "minecraft:water_identifier", "water_identifier"), waterSettings),
                    water != null && water.has("surface_color") ? water.get("surface_color") : defaultWater,
                    water != null && water.has("surface_opacity") ? water.get("surface_opacity").getAsDouble() : defaultAlpha);
            int index = profiles.indexOf(p);
            if (index < 0) {
                index = profiles.size();
                profiles.add(p);
            }
            byName.put(file.substring(0, file.length() - ".client_biome.json".length()), index);
        }
        for (int i = 0; i < BIOMES.length; i++) table[i] = byName.getOrDefault(BIOMES[i], 0);
        // 1.12 mutated biomes sit 128 ids above their base.
        for (int i = 128; i < 256; i++) table[i] = table[i - 128];

        StringBuilder curves = new StringBuilder("#define VV_TIME fract((float(worldTime) - 6000.0) / 24000.0)\n");
        StringBuilder profileCode = new StringBuilder();
        for (int p = 0; p < profiles.size(); p++) profileCode.append(profile(p, profiles.get(p), curves));
        StringBuilder dispatch = new StringBuilder();
        for (int p = 0; p < profiles.size(); p++) {
            dispatch.append("        case ").append(p).append(": return vv_profile").append(p).append("(i);\n");
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 256; i++) list.append(i == 0 ? "" : ", ").append(table[i]);

        String params = curves + profileCode.toString()
                + "const int VV_PROFILE[256] = int[256](" + list + ");\n"
                + "vec4 vv_row(int i) {\n    switch (VV_PROFILE[clamp(vkm_biome, 0, 255)]) {\n" + dispatch + "        default: return vv_profile0(i);\n    }\n}\n";

        String operator = grading != null && path(grading, "tone_mapping") != null
                ? path(grading, "tone_mapping").get("operator").getAsString() : "generic";
        String header = "#define VV_TONEMAP_" + operator.toUpperCase() + "\n#define VV_RAIN_SKY "
                + literal("vec3", color(new JsonPrimitive("#676767"))) + "\n" // atmospheric_weather.json of the game
                + shadowDefines() + waterDefines(waterSettings) + materialDefines();
        Map<String, List<String>> lights = localLights();
        header += localLightDefines(lights);
        String causticPath = causticTexture(waterSettings);
        if (causticPath != null) {
            int[] size = pngSize(causticPath);
            header += "#define VV_CAUSTIC_FRAMES " + Math.max(1, size[1] / size[0]) + ".0\n";
        }
        boolean cubemap = cubemapFaces();
        if (cubemap) header += "#define VV_CUBEMAP\n";

        Map<String, String> programs = new LinkedHashMap<>();
        BedrockPrograms.write(programs, DIR, header, params);

        JsonObject ir = JsonParser.parseString(BedrockPrograms.PACK_JSON.replace("@DIR@", DIR)).getAsJsonObject();
        ir.addProperty("name", name(id));
        JsonObject gbuffers = ir.getAsJsonObject("gbuffers");
        if (causticPath != null) {
            ir.getAsJsonArray("textures").add(fileTexture("causticsMap", causticPath, true));
            gbuffers.getAsJsonObject("water").getAsJsonArray("inputs").add("causticsMap");
        }
        if (cubemap) {
            for (int i = 0; i < 6; i++) {
                ir.getAsJsonArray("textures").add(fileTexture("cubemap" + i, CUBEMAP_DIR + "cubemap_" + i + ".png", false));
                gbuffers.getAsJsonObject("skybasic").getAsJsonArray("inputs").add("cubemap" + i);
            }
        }
        addLocalLights(ir, lights);
        // The offset of the orbit tilts the sun's path; its key frames would move the shadow map's axis.
        JsonObject orbitalBase = lighting == null ? null : path(lighting, "directional_lights", "orbital");
        if (orbitalBase != null && orbitalBase.has("orbital_offset_degrees") && orbitalBase.get("orbital_offset_degrees").isJsonPrimitive()) {
            ir.getAsJsonObject("shadow").addProperty("sunPathRotation", orbitalBase.get("orbital_offset_degrees").getAsFloat());
        }
        return new Result(IrPack.parse(id, ir), programs, warnings);
    }

    /** The curves of a settings file once, whatever number of profiles use it. */
    private int emit(JsonObject settings, StringBuilder curves, java.util.function.BiConsumer<String, StringBuilder> writer) {
        Integer known = settings == null ? null : emitted.get(settings);
        if (known != null) return known;
        int n = nextCurves++;
        if (settings != null) emitted.put(settings, n);
        writer.accept("_" + n, curves);
        return n;
    }

    /** {@code directional_lights.orbital.<light>}, or {@code directional_lights.<light>} in the older schema most biome files use. */
    private static JsonObject orbitalLight(JsonObject lighting, String light) {
        if (lighting == null) return null;
        JsonObject orbital = path(lighting, "directional_lights", "orbital", light);
        return orbital != null ? orbital : path(lighting, "directional_lights", light);
    }

    /** A biome's lighting file holds what differs: the rest comes from {@code lighting/global.json}. */
    private JsonObject inherited(JsonObject lighting, String section) {
        JsonObject own = lighting == null ? null : path(lighting, section);
        return own != null || baseLighting == null ? own : path(baseLighting, section);
    }

    private JsonObject baseLighting;

    private String profile(int index, Profile p, StringBuilder curves) {
        JsonObject sun = orbitalLight(p.lighting, "sun"), moon = orbitalLight(p.lighting, "moon");
        double fallbackSun = sun == null ? 100.0 : 0.0, fallbackMoon = moon == null ? 0.4 : 0.0;
        int l = emit(p.lighting, curves, (x, c) -> {
            c.append(curve("vv_sunIlluminance" + x, sun, "illuminance", fallbackSun));
            c.append(colorCurve("vv_sunColor" + x, sun, "color", new double[]{1, 0.95, 0.9}));
            c.append(curve("vv_moonIlluminance" + x, moon, "illuminance", fallbackMoon));
            c.append(colorCurve("vv_moonColor" + x, moon, "color", new double[]{0.64, 0.71, 1}));
            JsonObject ambient = inherited(p.lighting, "ambient");
            c.append(curve("vv_ambientIlluminance" + x, ambient, "illuminance", 0.02));
            c.append(colorCurve("vv_ambientColor" + x, ambient, "color", new double[]{1, 1, 1}));
            c.append(curve("vv_skyIntensity" + x, inherited(p.lighting, "sky"), "intensity", 1.0));
        });
        String cubemapRow = cubemapRow(curves);
        int a = emit(p.atmosphere, curves, (x, c) -> {
            c.append(curve("vv_rayleigh" + x, p.atmosphere, "rayleigh_strength", 5.0));
            c.append(curve("vv_sunMie" + x, p.atmosphere, "sun_mie_strength", 0.5));
            c.append(colorCurve("vv_skyZenith" + x, p.atmosphere, "sky_zenith_color", new double[]{0.24, 0.45, 0.85}));
            c.append(colorCurve("vv_skyHorizon" + x, p.atmosphere, "sky_horizon_color", new double[]{0.72, 0.82, 0.95}));
            JsonObject stops = p.atmosphere == null ? null : path(p.atmosphere, "horizon_blend_stops");
            c.append(curve("vv_horizonMin" + x, stops, "min", 0.0));
            c.append(curve("vv_horizonStart" + x, stops, "start", 0.5));
            c.append(curve("vv_horizonMie" + x, stops, "mie_start", 0.5));
            c.append(curve("vv_horizonMax" + x, stops, "max", 0.25));
        });
        JsonObject distance = p.fog == null ? null : path(p.fog, "distance");
        JsonObject midtones = p.grading == null ? null : path(p.grading, "color_grading", "midtones");
        String L = "_" + l + "()", A = "_" + a + "()";
        return "vec4 vv_profile" + index + "(int i) {\n    switch (i) {\n"
                + "        case 0: return vec4(vv_sunColor" + L + ", vv_sunIlluminance" + L + ");\n"
                + "        case 1: return vec4(vv_moonColor" + L + ", vv_moonIlluminance" + L + ");\n"
                + "        case 2: return vec4(vv_ambientColor" + L + ", vv_ambientIlluminance" + L + ");\n"
                + "        case 3: return vec4(vv_skyZenith" + A + ", vv_rayleigh" + A + ");\n"
                + "        case 4: return vec4(vv_skyHorizon" + A + ", vv_sunMie" + A + ");\n"
                + "        case 5: return vec4(vv_horizonMin" + A + ", vv_horizonStart" + A + ", vv_horizonMie" + A + ", vv_horizonMax" + A + ");\n"
                + "        case 6: return vec4(" + fogRange(distance == null ? null : path(distance, "air"), 0.92, 1.0) + ", "
                + fogRange(distance == null ? null : path(distance, "weather"), 0.23, 0.7) + ");\n"
                + "        case 7: return vec4(" + literal("vec3", color(p.waterColor)) + ", " + p.waterAlpha + ");\n"
                + "        case 8: return vec4(" + vector(midtones, "contrast", 1.0) + ", 1.0);\n"
                + "        case 9: return vec4(" + vector(midtones, "gain", 1.0) + ", 1.0);\n"
                + "        case 10: return vec4(" + vector(midtones, "offset", 0.0) + ", 1.0);\n"
                + "        case 11: return vec4(" + vector(midtones, "saturation", 1.0) + ", 1.0);\n"
                + "        case 12: return vec4(" + vector(midtones, "gamma", 2.2) + ", 1.0);\n"
                + "        case 13: return " + waterRow(p.water) + ";\n"
                + "        case 14: return vec4(vv_skyIntensity" + L + ", " + number(inherited(p.lighting, "emissive"), "desaturation", 0.0) + ", 0.0, 0.0);\n"
                + volumeRows(p.fog) + "        case 19: return " + cubemapRow + ";\n    }\n    return vec4(0.0);\n}\n";
    }

    private static final String CUBEMAP_DIR = "textures/environment/overworld_cubemap/";

    /** The Overworld's six faces; a pack ships all of them or the atmosphere stays. */
    private boolean cubemapFaces() {
        for (int i = 0; i < 6; i++) {
            if (!src.exists(CUBEMAP_DIR + "cubemap_" + i + ".png")) return false;
        }
        return true;
    }

    private JsonObject cubemapLighting;
    private boolean cubemapRead;

    /** {@code cubemaps/*.json}: ambient light, sky and sun contributions, atmospheric scattering. */
    private String cubemapRow(StringBuilder curves) {
        if (!cubemapRead) {
            cubemapRead = true;
            try {
                for (JsonObject o : byIdentifier("cubemaps", "minecraft:cubemap_settings").values()) {
                    cubemapLighting = path(o, "lighting");
                    curves.append(curve("vv_cubemapAmbient", cubemapLighting, "ambient_light_illuminance", 5.625));
                    break;
                }
            } catch (IOException e) {
                warnings.add("cubemaps: " + e);
            }
            if (cubemapLighting == null) curves.append(curve("vv_cubemapAmbient", null, "", 5.625));
        }
        JsonObject l = cubemapLighting;
        boolean atmosphere = l != null && l.has("affected_by_atmospheric_scattering") && l.get("affected_by_atmospheric_scattering").getAsBoolean();
        return "vec4(vv_cubemapAmbient(), " + number(l, "sky_light_contribution", 1.0) + ", " + number(l, "directional_light_contribution", 0.0)
                + ", " + (atmosphere ? "1.0" : "0.0") + ")";
    }

    private String causticTexture(JsonObject water) {
        JsonObject caustics = water == null ? null : path(water, "caustics");
        if (caustics == null || !caustics.has("texture")) return null;
        String path = caustics.get("texture").getAsString().replaceFirst("\\.png$", "") + ".png";
        if (src.exists(path)) return path;
        warnings.add("caustics texture " + path + " is missing, or not a PNG");
        return null;
    }

    private JsonObject fileTexture(String name, String path, boolean repeat) throws IOException {
        int[] size = pngSize(path);
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("format", "RGBA8");
        t.addProperty("file", path);
        JsonArray s = new JsonArray();
        s.add(size[0]);
        s.add(size[1]);
        t.add("size", s);
        if (repeat) t.addProperty("wrap", "repeat");
        return t;
    }

    private int[] pngSize(String path) throws IOException {
        byte[] b = src.readBytes(path);
        return new int[]{(b[16] & 255) << 24 | (b[17] & 255) << 16 | (b[18] & 255) << 8 | (b[19] & 255),
                (b[20] & 255) << 24 | (b[21] & 255) << 16 | (b[22] & 255) << 8 | (b[23] & 255)};
    }

    /** Point lights of the game (lightingcustomization), and the warm color of every other light block. */
    private static final Map<String, String> LIGHT_COLORS = new LinkedHashMap<>();

    static {
        LIGHT_COLORS.put("minecraft:torch", "#EFE39D");
        LIGHT_COLORS.put("minecraft:redstone_torch", "#FF0000");
        LIGHT_COLORS.put("minecraft:end_rod", "#FFFFFF");
        for (String block : List.of("glowstone", "lava", "flowing_lava", "fire", "sea_lantern", "lit_pumpkin", "lit_redstone_lamp", "beacon",
                "lit_furnace", "magma")) {
            LIGHT_COLORS.put("minecraft:" + block, "#FFC080");
        }
    }

    /** Vanilla levels where they are not 15. */
    private static final Map<String, Integer> LIGHT_LEVELS = Map.of("minecraft:torch", 14, "minecraft:redstone_torch", 7,
            "minecraft:end_rod", 14, "minecraft:lit_furnace", 13, "minecraft:magma", 3);

    /** Blocks that let light through though they fill a voxel of the shadow pass. */
    private static final String LIGHT_PASSES = String.join(" ", List.of("tallgrass", "double_plant", "deadbush", "yellow_flower", "red_flower",
            "sapling", "brown_mushroom", "red_mushroom", "reeds", "wheat", "carrots", "potatoes", "beetroots", "vine", "web", "ladder", "rail",
            "golden_rail", "detector_rail", "activator_rail", "lever", "stone_button", "wooden_button", "tripwire", "tripwire_hook",
            "redstone_wire", "unpowered_repeater", "powered_repeater", "unpowered_comparator", "powered_comparator", "carpet", "snow_layer",
            "stone_pressure_plate", "wooden_pressure_plate", "light_weighted_pressure_plate", "heavy_weighted_pressure_plate", "wall_sign",
            "standing_sign", "flower_pot", "skull", "glass", "glass_pane", "stained_glass", "stained_glass_pane", "iron_bars", "fence",
            "nether_brick_fence", "spruce_fence", "birch_fence", "jungle_fence", "dark_oak_fence", "acacia_fence", "nether_wart",
            "melon_stem", "pumpkin_stem", "waterlily").stream().map(b -> "minecraft:" + b).toList());

    /** Blocks by color and reach, from the game's list, {@code local_lighting/local_lighting.json} or the older {@code point_lights/global.json}. */
    private Map<String, List<String>> localLights() throws IOException {
        Map<String, String> colors = new LinkedHashMap<>(LIGHT_COLORS);
        JsonObject local = json("local_lighting/local_lighting.json", "minecraft:local_light_settings");
        JsonObject old = local == null ? json("point_lights/global.json", "minecraft:point_light_settings") : null;
        if (local != null) {
            for (Map.Entry<String, JsonElement> e : local.entrySet()) {
                JsonElement c = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject().get("light_color") : null;
                if (c != null) colors.put(e.getKey(), hex(c));
            }
        } else if (old != null && old.has("colors")) {
            old.getAsJsonObject("colors").entrySet().forEach(e -> colors.put(e.getKey(), hex(e.getValue())));
        }
        Map<String, List<String>> byKind = new LinkedHashMap<>();
        colors.forEach((block, color) -> byKind.computeIfAbsent(color + " " + LIGHT_LEVELS.getOrDefault(block, 15), k -> new ArrayList<>()).add(block));
        return byKind;
    }

    private static String hex(JsonElement e) {
        if (!e.isJsonArray()) return e.getAsString();
        JsonArray a = e.getAsJsonArray();
        return String.format("#%02X%02X%02X", a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static String localLightDefines(Map<String, List<String>> lights) {
        StringBuilder d = new StringBuilder("const int VV_LIGHT_KINDS = ").append(lights.size()).append(";\nconst vec4 VV_LIGHT_COLORS[")
                .append(lights.size()).append("] = vec4[](");
        boolean first = true;
        for (String kind : lights.keySet()) {
            String[] parts = kind.split(" ");
            // A level of light reaches that many blocks; the radius is where it ends.
            d.append(first ? "" : ", ").append("vec4(").append(literal("vec3", color(new JsonPrimitive(parts[0])))).append(", ")
                    .append(Integer.parseInt(parts[1]) + 1).append(".0)");
            first = false;
        }
        return d.append(");\n").toString();
    }

    /**
     * Point lights come from the voxels the shadow pass marks: lights and what stops light. So
     * that pass must see all that is around the camera, not what the sun's frustum holds. Compute
     * passes turn the voxels into lights and sort those into clusters.
     */
    private static void addLocalLights(JsonObject ir, Map<String, List<String>> lights) {
        JsonObject blockIds = ir.getAsJsonObject("blockIds");
        int id = 2;
        for (List<String> blocks : lights.values()) blockIds.addProperty(Integer.toString(id++), String.join(" ", blocks));
        blockIds.addProperty(Integer.toString(id), LIGHT_PASSES);
        ir.getAsJsonObject("requires").getAsJsonArray("vertex").add("midBlock");
        ir.getAsJsonObject("shadow").addProperty("culling", false);
        JsonObject resources = JsonParser.parseString(BedrockPrograms.LOCAL_LIGHT_RESOURCES).getAsJsonObject();
        ir.add("resources", resources);
        JsonObject prepare = JsonParser.parseString(BedrockPrograms.LOCAL_LIGHT_STAGE.replace("@DIR@", DIR)).getAsJsonObject();
        ir.getAsJsonArray("commandLists").add(prepare);
    }

    private static double number(JsonObject o, String key, double fallback) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback;
    }

    private static String waterRow(JsonObject water) {
        JsonObject c = water == null ? null : path(water, "particle_concentrations");
        return "vec4(" + number(c, "chlorophyll", 0.0) + ", " + number(c, "suspended_sediment", 0.0) + ", " + number(c, "cdom", 0.0) + ", "
                + number(water, "biome_water_color_contribution", 0.0) + ")";
    }

    /** {@code volumetric} of a fog file; without one, the values of the game's documentation. */
    private static String volumeRows(JsonObject fog) {
        JsonObject volumetric = fog == null ? null : path(fog, "volumetric");
        JsonObject air = volumetric == null ? null : path(volumetric, "density", "air");
        JsonObject water = volumetric == null ? null : path(volumetric, "density", "water");
        JsonObject airMedia = volumetric == null ? null : path(volumetric, "media_coefficients", "air");
        JsonObject waterMedia = volumetric == null ? null : path(volumetric, "media_coefficients", "water");
        boolean uniform = air != null && air.has("uniform") && air.get("uniform").getAsBoolean();
        return "        case 15: return vec4(" + number(air, "max_density", 0.01) + ", " + number(air, "zero_density_height", 150.0) + ", "
                + number(air, "max_density_height", 50.0) + ", " + (uniform ? "1.0" : "0.0") + ");\n"
                + "        case 16: return vec4(" + vector(airMedia, "scattering", 0.02) + ", " + number(airMedia, "henyey_greenstein_g", 0.75) + ");\n"
                + "        case 17: return vec4(" + luminance(airMedia, "absorption", 0.0) + ", " + number(water, "max_density", 0.25) + ", "
                + number(waterMedia, "henyey_greenstein_g", 0.6) + ", " + luminance(waterMedia, "absorption", 0.09) + ");\n"
                + "        case 18: return vec4(" + vector(waterMedia, "scattering", 0.02) + ", 0.0);\n";
    }

    /** Volumes have one channel in Vibrant Visuals: the luminance of the color. */
    private static double luminance(JsonObject o, String key, double fallback) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return fallback;
        JsonArray a = o.getAsJsonArray(key);
        return 0.2126 * a.get(0).getAsDouble() + 0.7152 * a.get(1).getAsDouble() + 0.0722 * a.get(2).getAsDouble();
    }

    /** {@code pbr/global.json}: what a surface without a texture set is made of, roughness first as in the {@code specular} atlas. */
    private String materialDefines() throws IOException {
        JsonObject o = null;
        if (src.exists("pbr/global.json")) {
            o = JsonParser.parseString(src.read("pbr/global.json").replaceAll("(?m)^\\s*//.*$", "")).getAsJsonObject()
                    .getAsJsonObject("minecraft:pbr_fallback_settings");
        }
        return "#define VV_PBR_BLOCK " + fallback(o, "blocks") + "\n#define VV_PBR_ACTOR " + fallback(o, "actors") + "\n";
    }

    private static String fallback(JsonObject settings, String kind) {
        JsonObject o = settings == null ? null : path(settings, kind);
        JsonElement e = o == null ? null : o.get("global_metalness_emissive_roughness_subsurface");
        double[] mers = {0, 0, 1, 0};
        if (e != null && e.isJsonArray()) {
            for (int i = 0; i < 4 && i < e.getAsJsonArray().size(); i++) mers[i] = e.getAsJsonArray().get(i).getAsDouble() / 255.0;
        } else if (e != null && e.isJsonPrimitive()) {
            String hex = e.getAsString().replace("#", "");
            for (int i = 0; i < 4 && i * 2 + 2 <= hex.length(); i++) mers[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) / 255.0;
        }
        return String.format(java.util.Locale.ROOT, "vec4(%.4f, %.4f, %.4f, %.4f)", mers[2], mers[0], mers[1], mers[3]);
    }

    private String shadowDefines() throws IOException {
        if (!src.exists("shadows/global.json")) return "";
        JsonObject o = JsonParser.parseString(src.read("shadows/global.json").replaceAll("(?m)^\\s*//.*$", "")).getAsJsonObject()
                .getAsJsonObject("minecraft:shadow_settings");
        if (o == null || !o.has("shadow_style") || !o.get("shadow_style").getAsString().equals("blocky_shadows")) return "";
        return "#define VV_SHADOW_BLOCKY\n#define VV_SHADOW_TEXEL " + number(o, "texel_size", 16.0) + "\n";
    }

    /** Waves and caustics of the default water: the game cannot blend them between biomes either. */
    private static String waterDefines(JsonObject water) {
        JsonObject waves = water == null ? null : path(water, "waves"), caustics = water == null ? null : path(water, "caustics");
        StringBuilder d = new StringBuilder();
        if (waves != null && waves.has("enabled") && waves.get("enabled").getAsBoolean()) {
            d.append("#define VV_WAVES\n");
            // More octaves than this change nothing a pixel can show, and each costs three evaluations.
            d.append("#define VV_WAVE_OCTAVES ").append(Math.min(16, (int) number(waves, "octaves", 12))).append('\n');
            for (String[] k : new String[][]{{"depth", "DEPTH", "1.0"}, {"frequency", "FREQUENCY", "1.0"}, {"frequency_scaling", "FREQUENCY_SCALING", "1.2"},
                    {"speed", "SPEED", "2.0"}, {"speed_scaling", "SPEED_SCALING", "1.03"}, {"shape", "SHAPE", "1.5"}, {"pull", "PULL", "0.38"},
                    {"mix", "MIX", "0.2"}, {"direction_increment", "DIRECTION", "80.0"}}) {
                d.append("#define VV_WAVE_").append(k[1]).append(' ').append(number(waves, k[0], Double.parseDouble(k[2]))).append('\n');
            }
        }
        if (caustics == null || !caustics.has("enabled") || caustics.get("enabled").getAsBoolean()) {
            d.append("#define VV_CAUSTICS\n");
            d.append("#define VV_CAUSTIC_SCALE ").append(number(caustics, "scale", 0.5)).append('\n');
            d.append("#define VV_CAUSTIC_POWER ").append(number(caustics, "power", 2.0)).append('\n');
            d.append("#define VV_CAUSTIC_FRAME ").append(number(caustics, "frame_length", 0.05)).append('\n');
        }
        return d.toString();
    }

    private static String fogRange(JsonObject layer, double start, double end) {
        return (layer != null && layer.has("fog_start") ? layer.get("fog_start").getAsDouble() : start) + ", "
                + (layer != null && layer.has("fog_end") ? layer.get("fog_end").getAsDouble() : end);
    }

    /** The default client biome, from {@code biomes_client.json}: fog file and water surface. */
    private JsonObject defaultBiome() throws IOException {
        if (!src.exists("biomes_client.json")) return null;
        JsonObject o = JsonParser.parseString(src.read("biomes_client.json").replaceAll("(?m)^\\s*//.*$", "")).getAsJsonObject();
        return path(o, "biomes", "default");
    }

    /** The file of {@code fogs/} whose identifier the default biome names. */
    private JsonObject fogFile() throws IOException {
        JsonObject biome = defaultBiome();
        if (biome != null && biome.has("fog_identifier")) {
            JsonObject fog = byIdentifier("fogs", "minecraft:fog_settings").get(biome.get("fog_identifier").getAsString());
            if (fog != null) return fog;
        }
        return json("fogs/default_fog_setting.json", "minecraft:fog_settings");
    }

    private String name(String id) throws IOException {
        JsonObject m = JsonParser.parseString(src.read("manifest.json")).getAsJsonObject();
        return m.has("header") && m.getAsJsonObject("header").has("name") ? m.getAsJsonObject("header").get("name").getAsString() : id;
    }

    private static JsonObject path(JsonObject o, String... keys) {
        JsonObject cur = o;
        for (String k : keys) {
            if (cur == null || !cur.has(k) || !cur.get(k).isJsonObject()) return null;
            cur = cur.getAsJsonObject(k);
        }
        return cur;
    }

    /** A number or {@code {"t": value, ...}} as a GLSL function of VV_TIME, linear between keys. */
    private static String curve(String name, JsonObject parent, String key, double fallback) {
        JsonElement e = parent == null ? null : parent.get(key);
        TreeMap<Double, double[]> keys = new TreeMap<>();
        if (e != null && e.isJsonObject()) {
            e.getAsJsonObject().entrySet().forEach(k -> keys.put(Double.parseDouble(k.getKey()), new double[]{k.getValue().getAsDouble()}));
        }
        double value = e != null && e.isJsonPrimitive() ? e.getAsDouble() : fallback;
        return function("float", name, keys, new double[]{value});
    }

    private static String colorCurve(String name, JsonObject parent, String key, double[] fallback) {
        JsonElement e = parent == null ? null : parent.get(key);
        TreeMap<Double, double[]> keys = new TreeMap<>();
        if (e != null && e.isJsonObject()) {
            e.getAsJsonObject().entrySet().forEach(k -> keys.put(Double.parseDouble(k.getKey()), color(k.getValue())));
        }
        return function("vec3", name, keys, e != null && !e.isJsonObject() ? color(e) : fallback);
    }

    /** {@code [r, g, b]} in 0..255 or {@code "#RRGGBB"}, linearized. */
    private static double[] color(JsonElement e) {
        double[] c = new double[3];
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < 3; i++) c[i] = a.get(i).getAsDouble() / 255.0;
        } else {
            String s = e.getAsString().replace("#", "");
            int rgb = Integer.parseInt(s.substring(s.length() - 6), 16);
            c[0] = (rgb >> 16 & 255) / 255.0;
            c[1] = (rgb >> 8 & 255) / 255.0;
            c[2] = (rgb & 255) / 255.0;
        }
        for (int i = 0; i < 3; i++) c[i] = Math.pow(c[i], 2.2);
        return c;
    }

    private static String literal(String type, double[] v) {
        if (type.equals("float")) return String.format(java.util.Locale.ROOT, "%.6f", v[0]);
        return String.format(java.util.Locale.ROOT, "vec3(%.6f, %.6f, %.6f)", v[0], v[1], v[2]);
    }

    private static String function(String type, String name, TreeMap<Double, double[]> keys, double[] constant) {
        StringBuilder f = new StringBuilder(type).append(' ').append(name).append("() {\n");
        if (keys.isEmpty()) {
            f.append("    return ").append(literal(type, constant)).append(";\n}\n");
            return f.toString();
        }
        f.append("    float t = VV_TIME;\n");
        Map.Entry<Double, double[]> prev = null;
        for (Map.Entry<Double, double[]> k : keys.entrySet()) {
            if (prev == null) {
                f.append("    if (t <= ").append(k.getKey()).append(") return ").append(literal(type, k.getValue())).append(";\n");
            } else {
                f.append("    if (t <= ").append(k.getKey()).append(") return mix(").append(literal(type, prev.getValue())).append(", ")
                        .append(literal(type, k.getValue())).append(", (t - ").append(prev.getKey()).append(") / ")
                        .append(Math.max(1e-6, k.getKey() - prev.getKey())).append(");\n");
            }
            prev = k;
        }
        f.append("    return ").append(literal(type, prev.getValue())).append(";\n}\n");
        return f.toString();
    }

    private static String vector(JsonObject parent, String key, double fallback) {
        double[] v = {fallback, fallback, fallback};
        if (parent != null && parent.has(key) && parent.get(key).isJsonArray()) {
            JsonArray a = parent.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < a.size(); i++) v[i] = a.get(i).getAsDouble();
        }
        return literal("vec3", v);
    }
}
