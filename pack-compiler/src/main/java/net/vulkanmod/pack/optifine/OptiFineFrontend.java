package net.vulkanmod.pack.optifine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.vulkanmod.pack.ir.IrPack;
import net.vulkanmod.pack.ir.TextureFormat;
import net.vulkanmod.pack.plan.GeometryCategory;
import net.vulkanmod.pack.host.FrameBlock;
import net.vulkanmod.pack.host.Prelude;
import net.vulkanmod.pack.host.Resources;
import net.vulkanmod.pack.ir.TextureFormat;
import net.vulkanmod.pack.source.PackSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OptiFine frontend (frontends.md): lowers a {@code shaders/} folder for one set of option
 * values into an {@link IrPack} plus generated GLSL 450 programs. Baseline is the feature set of
 * OptiFine HD U G5 for 1.12.2.
 */
public final class OptiFineFrontend {

    private static final String ROOT = "shaders/", GENERATED = "vkm_of/";
    private static final Pattern DRAWBUFFERS = Pattern.compile("DRAWBUFFERS\\s*:\\s*(\\d+)");
    private static final Pattern RENDERTARGETS = Pattern.compile("RENDERTARGETS\\s*:\\s*([\\d,\\s]+)");
    private static final Pattern CONST = Pattern.compile("const\\s+(\\w+)\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*[\"<]([^\">]+)[\">].*$");
    private static final Pattern OPTION = Pattern.compile("^\\s*(//)?\\s*#\\s*define\\s+(\\w+)(\\s+([^/\\s]\\S*))?\\s*(//.*)?$");
    private static final Pattern OPTION_VALUES = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern GEOMETRY_OUT = Pattern.compile("vkm_fragData(\\d+)");

    /** A value option or a bool option of the pack. */
    public static final class Option {
        public final String name;
        public final boolean bool;
        public String value;
        /** The value in the pack's source. */
        public final String defaultValue;
        public final List<String> values = new ArrayList<>();
        /** The text behind the value on its {@code #define} line. */
        public String comment;

        Option(String name, boolean bool, String value) {
            this.name = name;
            this.bool = bool;
            this.value = value;
            this.defaultValue = value;
        }
    }

    /** The lowered pack, the programs to overlay, notes for the log and the pack's options. */
    public record Result(IrPack pack, Map<String, String> programs, List<String> warnings, Map<String, Option> options,
                         ShaderScreens screens) {
    }

    private final PackSource src;
    private final String id;
    private final Map<String, String> chosen;
    private final Map<String, String> macroValues = new LinkedHashMap<>();
    private final Map<String, String> programs = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Option> options = new TreeMap<>();
    private final Map<String, String> consts = new LinkedHashMap<>();
    private final Map<String, Set<String>> filesByFolder = new HashMap<>();
    private ShaderProperties properties;
    private final Set<String> customUniforms = new LinkedHashSet<>();
    private final JsonArray uniformsIr = new JsonArray();

    private OptiFineFrontend(PackSource src, String id, Map<String, String> chosen, Map<String, String> hostMacros) {
        this.src = src;
        this.id = id;
        this.chosen = chosen;
        macroValues.putAll(hostMacros);
        for (int i = 0; i < RENDER_STAGES.size(); i++) macroValues.put("MC_RENDER_STAGE_" + RENDER_STAGES.get(i), String.valueOf(i));
        macroValues.put("MC_VERSION", "11202");
        macroValues.put("MC_GL_VERSION", "460");
        macroValues.put("MC_GLSL_VERSION", "460");
        macroValues.put("MC_RENDER_QUALITY", "1.0");
        macroValues.put("MC_SHADOW_QUALITY", "1.0");
        macroValues.put("MC_HAND_DEPTH", "0.125");
        macroValues.put("MC_NORMAL_MAP", "");
        macroValues.put("MC_SPECULAR_MAP", "");
    }

    /**
     * @param chosen     option values that differ from the defaults
     * @param hostMacros {@code MC_*} macros of the running device
     * @throws IllegalArgumentException with a readable message on a pack that cannot run
     */
    public static Result lower(PackSource src, String id, Map<String, String> chosen, Map<String, String> hostMacros) throws IOException {
        OptiFineFrontend f = new OptiFineFrontend(src, id, chosen, hostMacros);
        f.scanOptions();
        f.properties = f.readProperties();
        f.readResources();
        JsonObject ir = f.lowerDimension(new String[]{"world0/", ""});
        JsonObject scenes = new JsonObject();
        for (String[] scene : new String[][]{{"nether", "world-1/"}, {"end", "world1/"}}) {
            if (f.hasPrograms(scene[1])) {
                scenes.add(scene[0], f.lowerDimension(new String[]{scene[1], ""}));
            }
        }
        if (!scenes.isEmpty()) ir.add("scenes", scenes);
        ir.addProperty("format", 1);
        ir.addProperty("name", id);
        ShaderScreens screens = ShaderScreens.parse(f.properties, f.options);
        f.readLang(screens);
        return new Result(IrPack.parse(id, ir), f.programs, f.warnings, f.options, screens);
    }

    // ------------------------------------------------------------------------------------
    // Options

    private void scanOptions() throws IOException {
        Set<String> tested = new LinkedHashSet<>();
        List<String> files = new ArrayList<>();
        collect(ROOT, files);
        Pattern ifdef = Pattern.compile("^\\s*#\\s*(?:ifdef|ifndef)\\s+(\\w+)|defined\\s*\\(?\\s*(\\w+)");
        Map<String, Option> found = new LinkedHashMap<>();
        for (String file : files) {
            for (String line : src.read(file).replace("\r", "").split("\n")) {
                Matcher t = ifdef.matcher(line);
                while (t.find()) tested.add(t.group(1) != null ? t.group(1) : t.group(2));
                Matcher m = OPTION.matcher(line);
                if (!m.matches() || m.group(2).startsWith("MC_")) continue;
                String name = m.group(2), value = m.group(4), comment = m.group(5);
                boolean commented = m.group(1) != null;
                Option o = found.get(name);
                if (value != null) {
                    if (o == null && comment != null && OPTION_VALUES.matcher(comment).find()) {
                        o = new Option(name, false, value);
                        Matcher v = OPTION_VALUES.matcher(comment);
                        v.find();
                        for (String s : v.group(1).trim().split("\\s+")) if (!s.isEmpty()) o.values.add(s);
                        o.comment = comment;
                        found.put(name, o);
                    }
                } else if (o == null) {
                    o = new Option(name, true, commented ? "false" : "true");
                    found.put(name, o);
                }
            }
        }
        for (Option o : found.values()) {
            if (o.bool && !tested.contains(o.name)) continue;
            if (chosen.containsKey(o.name)) o.value = chosen.get(o.name);
            options.put(o.name, o);
        }
    }

    private void readLang(ShaderScreens screens) throws IOException {
        for (String file : List.of(ROOT + "lang/en_US.lang", ROOT + "lang/en_us.lang")) {
            if (!src.exists(file)) continue;
            for (String line : src.read(file).replace("\r", "").split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) screens.lang.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
            return;
        }
    }

    private void collect(String dir, List<String> into) {
        for (String entry : src.list(dir)) {
            String path = dir + entry;
            if (src.hasDirectory(path)) {
                collect(path + "/", into);
            } else if (!entry.matches(".*\\.(properties|lang|png|jpg|jpeg|bin|dat|json|md|txt)")) {
                // Includes can have any name; OptiFine's scan follows them.
                into.add(path);
            }
        }
    }

    private GlslPreprocessor preprocessor(String stageMacro) {
        GlslPreprocessor p = new GlslPreprocessor();
        macroValues.forEach(p::define);
        if (stageMacro != null) p.define(stageMacro, "");
        return p;
    }

    /** The Iris features this host has, as {@code iris.features.*} name them. */
    /** {@code MC_RENDER_STAGE_*}, in the order of their values. The host's {@code PackRuntime.renderStage} sends them. */
    private static final List<String> RENDER_STAGES = List.of("NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID",
            "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG",
            "HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT");

    private static final Set<String> IRIS_FEATURES = Set.of("COMPUTE_SHADERS", "CUSTOM_IMAGES", "SSBO", "PER_BUFFER_BLENDING",
            "REVERSED_CULLING", "BLOCK_EMISSION_ATTRIBUTE", "SEPARATE_HARDWARE_SAMPLERS", "FADE_VARIABLE", "ENTITY_TRANSLUCENT",
            "CAN_DISABLE_WEATHER", "TESSELLATION_SHADERS", "HIGHER_SHADOWCOLOR");

    /** {@code IRIS_FEATURE_<name>} for the features a pack lists and the host has. */
    private void defineFeatures() throws IOException {
        String file = ROOT + "shaders.properties";
        if (!src.exists(file)) return;
        Matcher m = Pattern.compile("iris\\.features\\.(?:required|optional)\\s*=(.*)").matcher(src.read(file));
        while (m.find()) {
            for (String f : m.group(1).trim().split("\\s+")) {
                if (IRIS_FEATURES.contains(f)) macroValues.put("IRIS_FEATURE_" + f, "");
            }
        }
    }

    private ShaderProperties readProperties() throws IOException {
        defineFeatures();
        GlslPreprocessor macros = preprocessor(null);
        for (Option o : options.values()) {
            if (!o.bool) macros.define(o.name, o.value);
            else if (o.value.equals("true")) macros.define(o.name, "");
        }
        String file = ROOT + "shaders.properties";
        ShaderProperties p = src.exists(file) ? ShaderProperties.parse(src.read(file), macros) : new ShaderProperties();
        int slot = 0;
        for (Map.Entry<String, String> e : p.values.entrySet()) {
            Matcher m = Pattern.compile("(uniform|variable)\\.(\\w+)\\.(\\w+)").matcher(e.getKey());
            if (!m.matches()) continue;
            JsonObject u = new JsonObject();
            u.addProperty("name", m.group(3));
            u.addProperty("type", m.group(2));
            u.addProperty("expr", e.getValue());
            boolean exposed = m.group(1).equals("uniform");
            u.addProperty("exposed", exposed);
            // A later definition of a name replaces the earlier one, of whatever type.
            for (int i = 0; i < uniformsIr.size(); i++) {
                JsonObject old = uniformsIr.get(i).getAsJsonObject();
                if (old.get("name").getAsString().equals(m.group(3))) {
                    if (old.has("slot")) u.add("slot", old.get("slot"));
                    uniformsIr.remove(i);
                    break;
                }
            }
            if (exposed) {
                if (!u.has("slot")) u.addProperty("slot", slot++);
                customUniforms.add(m.group(3));
            }
            uniformsIr.add(u);
        }
        return p;
    }

    /** {@code #define} lines of the options, rewritten for the chosen values. */
    private String applyOptions(String text) {
        if (chosen.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            Matcher m = OPTION.matcher(line);
            if (m.matches() && chosen.containsKey(m.group(2)) && options.containsKey(m.group(2))) {
                Option o = options.get(m.group(2));
                String v = chosen.get(o.name);
                if (o.bool) line = v.equals("true") ? "#define " + o.name : "";
                else line = "#define " + o.name + " " + v;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------
    // Programs

    /** {@code folders}: the dimension folder, then the root. */
    private String findProgram(String name, String[] folders) {
        for (String folder : folders) {
            if (src.exists(ROOT + folder + name)) return ROOT + folder + name;
        }
        return null;
    }

    private boolean hasPrograms(String folder) {
        return src.hasDirectory(ROOT + folder.substring(0, folder.length() - 1));
    }

    private String include(String path, Set<String> chain) throws IOException {
        String text = applyOptions(src.read(path).replace("\r", ""));
        StringBuilder out = new StringBuilder(text.length());
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = INCLUDE.matcher(lines[i]);
            if (!m.matches()) {
                out.append(lines[i]).append('\n');
                continue;
            }
            String rel = m.group(1);
            String target = PackSource.normalize(rel.startsWith("/") ? ROOT + rel.substring(1)
                    : path.substring(0, path.lastIndexOf('/') + 1) + rel);
            if (chain.add(target)) {
                out.append("#line 1 \"").append(target).append("\"\n").append(include(target, chain));
                chain.remove(target);
            }
            out.append("#line ").append(i + 2).append(" \"").append(path).append("\"\n");
        }
        return out.toString();
    }

    private JsonObject lowerDimension(String[] folders) throws IOException {
        Set<Integer> usedTextures = new LinkedHashSet<>();
        JsonObject ir = new JsonObject();
        JsonObject gbuffers = new JsonObject();
        JsonObject vertexFlags = new JsonObject();
        Set<String> vertex = new LinkedHashSet<>();
        String prefix = GENERATED + folders[0].replace("/", "") + "/";

        for (String name : GeometryCategory.PROGRAMS) {
            String program = name.equals("shadow") ? "shadow" : "gbuffers_" + name;
            if (!enabled(program, folders)) continue;
            Lowered l = lowerProgram(program, folders, prefix, true, false);
            if (l == null) continue;
            JsonObject g = new JsonObject();
            g.addProperty("vertex", l.vertexPath);
            g.addProperty("fragment", l.fragmentPath);
            l.middlePaths.forEach(g::addProperty);
            addImages(g, l, usedTextures);
            JsonArray targets = new JsonArray();
            for (int t : l.targets) {
                if (name.equals("shadow")) {
                    if (t < IrPack.SHADOW_COLORS.size()) targets.add("shadowcolor" + t);
                } else {
                    targets.add("colortex" + t);
                    usedTextures.add(t);
                }
            }
            g.add("targets", targets);
            JsonArray inputs = new JsonArray();
            for (String s : l.inputs) {
                if (s.equals("gtexture") || s.equals("lightmap")) continue;
                if (name.equals("shadow") && !s.startsWith("vkf_")) {
                    if (!s.startsWith("shadow")) warnings.add("shadow: sampler " + s + " is not available in the shadow pass");
                    continue;
                }
                inputs.add(s);
                Matcher c = Pattern.compile("colortex(\\d+)").matcher(s);
                if (c.matches()) usedTextures.add(Integer.parseInt(c.group(1)));
            }
            g.add("inputs", inputs);
            applyBlend(g, program);
            gbuffers.add(name, g);
            vertex.addAll(l.vertexAttributes);
        }

        // Iris's stages: G5 has none of these, and a pack ships them for Iris only.
        JsonArray setup = passes("setup", folders, prefix, usedTextures);
        JsonArray begin = passes("begin", folders, prefix, usedTextures);
        JsonArray shadowComp = passes("shadowcomp", folders, prefix, usedTextures);
        JsonArray prepare = passes("prepare", folders, prefix, usedTextures);
        JsonArray deferred = passes("deferred", folders, prefix, usedTextures);
        JsonArray composite = passes("composite", folders, prefix, usedTextures);
        Lowered fin = enabled("final", folders) ? lowerProgram("final", folders, prefix, false, true) : null;
        JsonObject combination = new JsonObject();
        if (fin == null) {
            String path = prefix + "final_default.fsh";
            programs.put(path, "layout(location = 0) out vec4 vkm_out;\nvoid main() { vkm_out = texture(colortex0, vkm_texCoord); }\n");
            combination.addProperty("fragment", path);
            JsonArray in = new JsonArray();
            in.add("colortex0");
            combination.add("inputs", in);
        } else {
            combination.addProperty("vertex", fin.vertexPath);
            combination.addProperty("fragment", fin.fragmentPath);
            fin.middlePaths.forEach(combination::addProperty);
            addImages(combination, fin, usedTextures);
            JsonArray in = new JsonArray();
            for (String s : fin.inputs) {
                in.add(s);
                noteTexture(s, usedTextures);
            }
            combination.add("inputs", in);
            addMipmapsBefore(combination, fin);
        }
        usedTextures.add(0);
        if (!shadowWork.isEmpty()) {
            for (JsonArray stage : List.of(setup, begin, shadowComp, prepare, deferred, composite)) {
                for (var p : stage) retargetShadowColors(p.getAsJsonObject());
            }
            for (var e : gbuffers.entrySet()) {
                if (!e.getKey().equals("shadow")) retargetShadowColors(e.getValue().getAsJsonObject());
            }
            retargetShadowColors(combination);
            JsonArray copies = new JsonArray();
            for (int n : shadowWork) copies.add(shadowCopyPass(n, prefix));
            copies.addAll(shadowComp);
            shadowComp = copies;
            // Mips of a written shadow color follow the last pass that writes in this stage.
            JsonArray mips = new JsonArray();
            for (int n : shadowWork) {
                if (shadowColorMipmapped(n)) mips.add(SHADOW_WORK + n);
            }
            if (!mips.isEmpty()) shadowComp.get(shadowComp.size() - 1).getAsJsonObject().add("mipmaps", mips);
        }

        JsonArray lists = new JsonArray();
        if (!setup.isEmpty()) lists.add(list("SETUP", setup));
        if (!begin.isEmpty()) lists.add(list("BEGIN", begin));
        if (!shadowComp.isEmpty()) lists.add(list("SHADOW_COMP", shadowComp));
        if (!prepare.isEmpty()) lists.add(list("PREPARE", prepare));
        if (!deferred.isEmpty()) lists.add(list("DEFERRED", deferred));
        if (!composite.isEmpty()) lists.add(list("POST_SCENE", composite));
        ir.add("commandLists", lists);
        ir.add("resources", resourceJson);
        ir.add("combination", combination);
        ir.add("gbuffers", gbuffers);

        JsonArray textures = new JsonArray();
        for (int t : new TreeSet<>(usedTextures)) textures.add(texture(t));
        addSpecialTextures(textures, ir);
        for (int n : shadowWork) textures.add(shadowWorkTexture(n));
        ir.add("textures", textures);

        JsonObject renderer = new JsonObject();
        renderer.addProperty("sceneTarget", "colortex0");
        for (String key : List.of("sun", "moon", "sky", "stars", "clouds", "vignette", "underwaterOverlay", "oldLighting", "oldHandLight",
                "dynamicHandLight", "separateAo", "weather", "rain.depth", "beacon.beam.depth", "backFace.solid", "backFace.cutout",
                "backFace.cutoutMipped", "backFace.translucent")) {
            if (properties.get(key) != null) renderer.addProperty(key, properties.get(key));
        }
        renderer.addProperty("handDepth", 0.125f);
        // OptiFine gives a block without an id its vanilla id, Iris none.
        if (irisResources) renderer.addProperty("unmappedBlockId", 0);
        renderer.addProperty("baseLevelGeometry", true);
        if (programs.values().stream().anyMatch(t -> t.contains("centerDepthSmooth"))) {
            renderer.addProperty("centerDepthHalflife", number("centerDepthHalflife", 1));
        }
        ir.add("renderer", renderer);

        String read = ir.toString();
        if (!gbuffers.has("shadow") && Pattern.compile("\"(shadowtex[01](Hw)?|shadowcolor[0-7])\"").matcher(read).find()) {
            // OptiFine renders the map with the fixed pipeline then.
            String vertexPath = prefix + "shadow_default.vsh", fragmentPath = prefix + "shadow_default.fsh";
            programs.put(vertexPath, "layout(location = 0) out vec2 vkm_uv;\nvoid main() { gl_Position = vkm_position(); vkm_uv = vaUV0; }\n");
            programs.put(fragmentPath, """
                    layout(location = 0) in vec2 vkm_uv;
                    void main() {
                    #if defined(VKM_ALPHA_TEST) && defined(VKM_GTEXTURE)
                        vkm_alphaTest(texture(gtexture, vkm_uv).a);
                    #endif
                    }
                    """);
            JsonObject g = new JsonObject();
            g.addProperty("vertex", vertexPath);
            g.addProperty("fragment", fragmentPath);
            g.add("targets", new JsonArray());
            gbuffers.add("shadow", g);
        }
        if (gbuffers.has("shadow")) ir.add("shadow", shadowSection());

        JsonObject requires = new JsonObject();
        JsonArray v = new JsonArray();
        for (String a : vertex) v.add(a);
        requires.add("vertex", v);
        ir.add("requires", requires);
        if (!uniformsIr.isEmpty()) ir.add("uniforms", uniformsIr);
        for (String[] map : new String[][]{{"block", "blockIds"}, {"item", "itemIds"}, {"entity", "entityIds"}}) {
            String file = ROOT + map[0] + ".properties";
            if (!src.exists(file)) continue;
            GlslPreprocessor macros = preprocessor(null);
            for (Option o : options.values()) {
                if (!o.bool) macros.define(o.name, o.value);
                else if (o.value.equals("true")) macros.define(o.name, "");
            }
            JsonObject ids = new JsonObject();
            ShaderProperties.parse(src.read(file), macros).under(map[0] + ".").forEach(ids::addProperty);
            ir.add(map[1], ids);
        }
        return ir;
    }

    private static JsonObject list(String stage, JsonArray passes) {
        JsonObject o = new JsonObject();
        o.addProperty("stage", stage);
        o.add("passes", passes);
        return o;
    }

    private void noteTexture(String sampler, Set<Integer> used) {
        Matcher c = Pattern.compile("colortex(\\d+)").matcher(sampler);
        if (c.matches()) used.add(Integer.parseInt(c.group(1)));
    }

    private JsonArray passes(String base, String[] folders, String prefix, Set<Integer> used) throws IOException {
        JsonArray list = new JsonArray();
        for (int i = 0; i < 100; i++) {
            String name = i == 0 ? base : base + i;
            if (!enabled(name, folders)) continue;
            computePasses(name, folders, prefix, used, list);
            if (base.equals("setup")) continue;
            Lowered l = lowerProgram(name, folders, prefix, false, false);
            if (l == null) continue;
            JsonObject pass = new JsonObject();
            pass.addProperty("name", name);
            pass.addProperty("vertex", l.vertexPath);
            pass.addProperty("fragment", l.fragmentPath);
            l.middlePaths.forEach(pass::addProperty);
            addImages(pass, l, used);
            JsonArray targets = new JsonArray(), inputs = new JsonArray();
            for (int t : l.targets) {
                if (base.equals("shadowcomp")) {
                    targets.add(SHADOW_WORK + t);
                    shadowWork.add(t);
                } else {
                    targets.add("colortex" + t);
                    used.add(t);
                }
            }
            for (String s : l.inputs) {
                inputs.add(s);
                noteTexture(s, used);
            }
            pass.add("targets", targets);
            pass.add("inputs", inputs);
            addMipmapsBefore(pass, l);
            String scale = properties.get("scale." + name);
            if (scale != null) pass.addProperty("scale", Math.min(1.0f, Float.parseFloat(scale.split("\\s+")[0])));
            applyBlend(pass, name);
            applyFlip(pass, name);
            list.add(pass);
        }
        return list;
    }

    /**
     * The shadow pass's attachments stay the host's. A shadow color that a program after it
     * writes becomes a pack texture: the first pass copies the map there, and everything but
     * the shadow program reads that texture, with the flips of any other pack texture.
     */
    private static final String SHADOW_WORK = "shadowwork";
    private final Set<Integer> shadowWork = new TreeSet<>();

    private void retargetShadowColors(JsonObject program) {
        for (String stage : List.of("vertex", "fragment", "compute", "geometry", "tessControl", "tessEval")) {
            if (!program.has(stage)) continue;
            programs.computeIfPresent(program.get(stage).getAsString(), (path, text) -> {
                for (int n : shadowWork) text = text.replaceAll("\\bshadowcolor" + n + "\\b", SHADOW_WORK + n);
                return text;
            });
        }
        if (!program.has("inputs")) return;
        JsonArray inputs = new JsonArray();
        for (var in : program.getAsJsonArray("inputs")) {
            Matcher m = Pattern.compile("shadowcolor(\\d)").matcher(in.getAsString());
            if (m.matches() && shadowWork.contains(Integer.parseInt(m.group(1)))) inputs.add(SHADOW_WORK + m.group(1));
            else inputs.add(in);
        }
        program.add("inputs", inputs);
    }

    private JsonObject shadowCopyPass(int n, String prefix) {
        String path = prefix + SHADOW_WORK + n + ".fsh";
        String format = consts.get("shadowcolor" + n + "Format");
        boolean integer = format != null && TextureFormat.parse(irFormat(format)) != null && TextureFormat.parse(irFormat(format)).isInteger();
        programs.put(path, "layout(location = 0) out " + (integer ? "uvec4" : "vec4") + " vkm_out;\nvoid main() { vkm_out = texture(shadowcolor" + n + ", vkm_texCoord); }\n");
        JsonObject pass = new JsonObject();
        pass.addProperty("name", SHADOW_WORK + n);
        pass.addProperty("fragment", path);
        JsonArray inputs = new JsonArray(), targets = new JsonArray();
        inputs.add("shadowcolor" + n);
        targets.add(SHADOW_WORK + n);
        pass.add("inputs", inputs);
        pass.add("targets", targets);
        return pass;
    }

    private boolean shadowColorMipmapped(int n) {
        return "true".equals(consts.get("generateShadowColorMipmap")) || "true".equals(consts.get("shadowcolor" + n + "Mipmap"));
    }

    private JsonObject shadowWorkTexture(int n) {
        JsonObject o = new JsonObject();
        o.addProperty("name", SHADOW_WORK + n);
        String format = consts.get("shadowcolor" + n + "Format");
        o.addProperty("format", format == null ? "RGBA8" : irFormat(format));
        o.addProperty("clear", false);
        if (shadowColorMipmapped(n)) o.addProperty("mipmaps", true);
        int resolution = (int) number("shadowMapResolution", 1024);
        JsonArray size = new JsonArray();
        size.add(resolution);
        size.add(resolution);
        o.add("size", size);
        return o;
    }

    private static final Pattern LOCAL_SIZE = Pattern.compile("layout\\s*\\(([^)]*local_size_[^)]*)\\)\\s*in\\s*;");
    private static final Pattern WORK_GROUPS = Pattern.compile("const\\s+ivec3\\s+workGroups\\s*=\\s*ivec3\\s*\\(([^;]*)\\)\\s*;");
    private static final Pattern WORK_RENDER = Pattern.compile("const\\s+vec2\\s+workGroupsRender\\s*=\\s*vec2\\s*\\(([^;]*)\\)\\s*;");

    /** {@code <name>.csh}, then {@code _a} to {@code _z}: they run before the program of that name. */
    private void computePasses(String name, String[] folders, String prefix, Set<Integer> used, JsonArray list) throws IOException {
        for (char c = '`'; c <= 'z'; c++) {
            String file = c == '`' ? name : name + "_" + c;
            String csh = findProgram(file + ".csh", folders);
            if (csh == null) continue;
            lowering = file;
            GlslLowering.Program program = new GlslLowering.Program(file, false, false);
            program.resources = resources;
            program.customTextures = customTextures;
            GlslPreprocessor cp = preprocessor("MC_COMPUTE_SHADER");
            String processed = cp.process(include(csh, new LinkedHashSet<>(Set.of(csh))));
            int[] local = {1, 1, 1};
            Matcher size = LOCAL_SIZE.matcher(processed);
            if (size.find()) {
                Matcher axis = Pattern.compile("local_size_([xyz])\\s*=\\s*(\\d+)").matcher(size.group(1));
                while (axis.find()) local[axis.group(1).charAt(0) - 'x'] = Integer.parseInt(axis.group(2));
                processed = size.replaceAll("");
            }
            JsonObject groups = new JsonObject();
            JsonArray localSize = new JsonArray();
            for (int l : local) localSize.add(l);
            groups.add("local", localSize);
            Matcher fixed = WORK_GROUPS.matcher(processed), render = WORK_RENDER.matcher(processed);
            if (fixed.find()) {
                JsonArray g = new JsonArray();
                for (String v : fixed.group(1).split(",")) g.add((int) cp.evaluateNumber(v));
                groups.add("fixed", g);
            } else {
                groups.addProperty("scale", render.find() ? Float.parseFloat(render.group(1).split(",")[0].trim()) : 1.0f);
            }
            String lowered = new GlslLowering(program, GlslLowering.Stage.COMPUTE, customUniforms, warnings, cp).lower(processed);
            StringBuilder head = new StringBuilder(compatHeader(program, false, false, GlslLowering.Stage.FRAGMENT));
            head.insert(0, customTextures(name, program));
            String path = prefix + file + ".csh";
            programs.put(path, storageBlocks(head + GlslLowering.finish(lowered) + "\nvoid main() {\n    vkm_main();\n}\n"));
            JsonObject pass = new JsonObject();
            pass.addProperty("name", file);
            pass.addProperty("compute", path);
            JsonArray inputs = new JsonArray();
            // Packs include their whole sampler list; here a scene texture must not be a dependency.
            for (String s : program.samplers) {
                if (!Pattern.compile("\\b" + s + "\\b").matcher(lowered).find()) continue;
                inputs.add(s);
                noteTexture(s, used);
            }
            pass.add("inputs", inputs);
            JsonArray writes = new JsonArray();
            StringBuilder images = new StringBuilder();
            Matcher image = Pattern.compile("\\b" + GlslLowering.COLOR_IMAGE.pattern() + "\\b").matcher(lowered);
            while (image.find()) {
                boolean shadowImage = image.group().startsWith("shadow");
                String texture = (shadowImage ? SHADOW_WORK : "colortex") + image.group(1);
                String read = shadowImage ? "shadowcolor" + image.group(1) : texture;
                if (writes.contains(new com.google.gson.JsonPrimitive(texture))) continue;
                writes.add(texture);
                if (shadowImage) shadowWork.add(Integer.parseInt(image.group(1)));
                noteTexture(texture, used);
                images.append("#define ").append(image.group()).append(' ').append(inputs.contains(new com.google.gson.JsonPrimitive(read)) ? texture + "Out" : texture).append('\n');
            }
            programs.put(path, images + programs.get(path));
            pass.add("writes", writes);
            pass.add("workGroups", groups);
            String indirect = properties.get("indirect." + file);
            if (indirect != null) {
                String[] f = indirect.trim().split("\\s+");
                JsonArray a = new JsonArray();
                a.add(Integer.parseInt(f[0]));
                a.add(f.length > 1 ? Integer.parseInt(f[1]) : 0);
                pass.add("indirect", a);
            }
            list.add(pass);
        }
    }

    /** The render textures a graphics program stores to. */
    private void addImages(JsonObject program, Lowered l, Set<Integer> used) {
        if (l.images.isEmpty()) return;
        JsonArray writes = new JsonArray();
        for (String image : l.images) {
            writes.add(image);
            noteTexture(image, used);
        }
        program.add("writes", writes);
    }

    private static final Pattern STORAGE_BLOCK = Pattern.compile("layout\\s*\\(\\s*std430\\s*,\\s*binding\\s*=\\s*(\\d+)\\s*\\)");

    /** Puts a pack's {@code std430} block into the resource set. */
    private static String storageBlocks(String text) {
        return STORAGE_BLOCK.matcher(text).replaceAll(m -> "layout(std430, set = " + Resources.SET + ", binding = "
                + Resources.bufferBinding(Integer.parseInt(m.group(1))) + ")");
    }

    /** {@code image.<name>} and {@code bufferObject.<n>} from {@code shaders.properties}. */
    private void readResources() {
        JsonArray images = new JsonArray(), buffers = new JsonArray();
        Set<String> names = new LinkedHashSet<>();
        properties.under("image.").forEach((name, value) -> {
            // <sampler> <pixel format> <internal format> <pixel type> <clear> <relative> <sizes>
            String[] f = value.trim().split("\\s+");
            TextureFormat format = f.length >= 7 ? TextureFormat.ofQualifier(f[2]) : null;
            if (format == null) {
                warnings.add("image." + name + ": not understood: " + value);
                return;
            }
            JsonObject image = new JsonObject();
            image.addProperty("name", name);
            image.addProperty("format", format.name());
            if (!f[0].equals("none")) {
                image.addProperty("sampler", f[0]);
                names.add(f[0]);
            }
            names.add(name);
            image.addProperty("clear", Boolean.parseBoolean(f[4]));
            JsonArray sizes = new JsonArray();
            boolean relative = Boolean.parseBoolean(f[5]);
            for (int i = 6; i < f.length; i++) {
                if (relative) sizes.add(Float.parseFloat(f[i]));
                else sizes.add(Integer.parseInt(f[i]));
            }
            image.add(relative ? "scale" : "size", sizes);
            images.add(image);
        });
        properties.under("bufferObject.").forEach((binding, value) -> {
            String[] f = value.trim().split("\\s+");
            JsonObject buffer = new JsonObject();
            buffer.addProperty("binding", Integer.parseInt(binding));
            buffer.addProperty("size", Long.parseLong(f[0]));
            buffers.add(buffer);
        });
        boolean iris = !images.isEmpty() || !buffers.isEmpty();
        properties.under("customTexture.").forEach((name, value) -> {
            if (isRaw(value)) images.add(rawImage(name, name, value, names));
        });
        properties.under("texture.").forEach((key, value) -> {
            if (!isRaw(value)) return;
            String name = "vkf_raw" + rawTextures.size();
            rawTextures.put(key, name);
            images.add(rawImage(name, name, value, names));
        });
        resources = names;
        irisResources = iris;
        resourceJson = new JsonObject();
        resourceJson.add("images", images);
        resourceJson.add("buffers", buffers);
        properties.under("customTexture.").forEach((name, value) -> {
            String path = value.trim();
            if (isRaw(value)) return;
            if (path.equals("minecraft:textures/atlas/blocks.png")) {
                customTextures.put(name, "gtexture");
                return;
            }
            String file = PackSource.normalize(ROOT + path);
            if (path.contains(":") || !file.endsWith(".png") || !src.exists(file)) {
                warnings.add("customTexture." + name + ": only the block atlas and PNG files of the pack are supported");
                return;
            }
            customTextures.put(name, customNames.computeIfAbsent(file, f -> "vkf_file" + customNames.size()));
        });
        // Such a pack is written for Iris, and may refuse to run otherwise. Without resources a
        // pack keeps its OptiFine paths, which the OptiFine reference covers.
        if (iris) {
            macroValues.put("IS_IRIS", "");
            macroValues.put("IRIS_VERSION", "11103");
            macroValues.put("IRIS_HAS_TRANSLUCENCY_SORTING", "");
        }
    }

    /** {@code <stage>.<sampler>} of a raw {@code texture.*} property, and the sampler that holds it. */
    private final Map<String, String> rawTextures = new LinkedHashMap<>();
    private boolean irisResources;

    private static boolean isRaw(String value) {
        String[] f = value.trim().split("\\s+");
        return f.length >= 2 && f[1].startsWith("TEXTURE_");
    }

    /** {@code <path> TEXTURE_<n>D <internal format> <sizes> <pixel format> <pixel type>}. */
    private JsonObject rawImage(String name, String sampler, String value, Set<String> names) {
        String[] f = value.trim().split("\\s+");
        int dimensions = f[1].equals("TEXTURE_1D") ? 1 : f[1].equals("TEXTURE_3D") ? 3 : 2;
        JsonObject image = new JsonObject();
        if (f.length != dimensions + 5) {
            throw new IllegalArgumentException("raw texture " + name + ": not understood: " + value);
        }
        TextureFormat format = TextureFormat.parse(f[2]);
        if (format == null) throw new IllegalArgumentException("raw texture " + name + ": unknown format " + f[2]);
        image.addProperty("name", name);
        image.addProperty("sampler", sampler);
        image.addProperty("format", format.name());
        image.addProperty("file", PackSource.normalize(ROOT + f[0]));
        JsonArray sizes = new JsonArray();
        for (int i = 0; i < dimensions; i++) sizes.add(Integer.parseInt(f[3 + i]));
        image.add("size", sizes);
        String pixelFormat = f[3 + dimensions].replace("_INTEGER", ""), pixelType = f[4 + dimensions];
        JsonObject pixels = new JsonObject();
        boolean packed = pixelType.matches(".*_\\d.*");
        pixels.addProperty("components", packed ? 1 : switch (pixelFormat) {
            case "RED" -> 1;
            case "RG" -> 2;
            case "RGB", "BGR" -> 3;
            default -> 4;
        });
        pixels.addProperty("bytes", packed || pixelType.endsWith("INT") || pixelType.equals("FLOAT") ? 4
                : pixelType.endsWith("SHORT") || pixelType.equals("HALF_FLOAT") ? 2 : 1);
        pixels.addProperty("bgra", pixelFormat.startsWith("BGR"));
        pixels.addProperty("type", pixelType);
        image.add("pixels", pixels);
        names.add(sampler);
        return image;
    }

    /** Names of pack images and samplers, and the IR section. */
    private Set<String> resources = Set.of();
    private JsonObject resourceJson;

    /** The epilogue {@code alphaTest.<program>} asks for; without one the host's state decides. */
    private String alphaTest(String name) {
        String test = properties.get("alphaTest." + name);
        if (test == null) return "#ifdef VKM_ALPHA_TEST\n    vkm_alphaTest(vkm_fragData0.a);\n#endif\n";
        if (test.trim().equalsIgnoreCase("off")) return "";
        String[] f = test.trim().split("\\s+");
        String op = switch (f[0].toUpperCase()) {
            case "NEVER" -> "NEVER";
            case "LESS" -> "<";
            case "EQUAL" -> "==";
            case "LEQUAL" -> "<=";
            case "GREATER" -> ">";
            case "NOTEQUAL" -> "!=";
            case "GEQUAL" -> ">=";
            case "ALWAYS" -> "";
            default -> null;
        };
        if (op == null || (f.length != 2 && !op.isEmpty() && !op.equals("NEVER"))) {
            warnings.add("alphaTest." + name + " is not <func> <ref>: " + test);
            return "";
        }
        if (op.isEmpty()) return "";
        if (op.equals("NEVER")) return "    discard;\n";
        return "    if (!(vkm_fragData0.a " + op + " " + Float.parseFloat(f[1]) + ")) discard;\n";
    }

    private void applyBlend(JsonObject program, String name) {
        JsonObject targets = new JsonObject();
        properties.under("blend." + name + ".").forEach((buffer, value) -> {
            JsonObject one = new JsonObject();
            applyBlendValue(one, name + "." + buffer, value);
            if (one.has("blend")) targets.add(GlslLowering.alias(buffer), one.get("blend"));
        });
        if (targets.size() > 0) program.add("targetBlend", targets);
        String b = properties.get("blend." + name);
        if (b != null) applyBlendValue(program, name, b);
    }

    private void applyFlip(JsonObject pass, String name) {
        JsonObject flips = new JsonObject();
        properties.under("flip." + name + ".").forEach((buffer, value) -> flips.addProperty(GlslLowering.alias(buffer), Boolean.parseBoolean(value.trim())));
        if (flips.size() > 0) pass.add("flip", flips);
    }

    private void applyBlendValue(JsonObject program, String name, String b) {
        if (b.equalsIgnoreCase("off")) {
            program.addProperty("blend", false);
            return;
        }
        String[] f = b.trim().split("\\s+");
        if (f.length != 4) {
            warnings.add("blend." + name + " needs four factors: " + b);
            return;
        }
        JsonArray a = new JsonArray();
        for (String s : f) a.add(s.toUpperCase());
        program.add("blend", a);
    }

    /** {@code program.<name>.enabled}, with the options as its variables. */
    private boolean enabled(String name, String[] folders) {
        String folder = folders[0].replace("shaders/", "").replace("/", "");
        String e = properties.get("program." + (folder.isEmpty() ? "world0" : folder) + "/" + name + ".enabled");
        if (e == null) e = properties.get("program." + name + ".enabled");
        if (e == null) return true;
        GlslPreprocessor macros = preprocessor(null);
        macros.define("true", "1");
        macros.define("false", "0");
        for (Option o : options.values()) {
            if (o.bool) macros.define(o.name, o.value.equals("true") ? "1" : "0");
            else macros.define(o.name, o.value);
        }
        return macros.evaluate(e.replace("&&", " && ").replace("||", " || "));
    }

    /** The program being lowered, for its header. */
    private String lowering;

    private record Lowered(GlslLowering.Program program, String vertexPath, String fragmentPath, List<Integer> targets,
                           Set<String> inputs, Set<String> vertexAttributes, Set<Integer> mipmapped,
                           Map<String, String> middlePaths, List<String> images) {
    }

    /** A stage between vertex and fragment: the file extension, the macro and the {@code pack.json} key. */
    private record Middle(GlslLowering.Stage stage, String extension, String macro, String key) {
    }

    private static final List<Middle> MIDDLE_STAGES = List.of(
            new Middle(GlslLowering.Stage.TESS_CONTROL, ".tcs", "MC_TESS_CONTROL_SHADER", "tessControl"),
            new Middle(GlslLowering.Stage.TESS_EVAL, ".tes", "MC_TESS_EVAL_SHADER", "tessEval"),
            new Middle(GlslLowering.Stage.GEOMETRY, ".gsh", "MC_GEOMETRY_SHADER", "geometry"));

    private static final Pattern GEOMETRY_INPUT_LAYOUT = Pattern.compile("layout\\s*\\([^)]*\\)\\s*in\\s*;");
    private static final Pattern MAX_VERTICES_OUT = Pattern.compile("maxVerticesOut\\s*=\\s*(\\d+)");
    /** {@code gl_in[i].gl_FrontColor} of a compatibility profile: the host has no such block members. */
    private static final Pattern LEGACY_IN = Pattern.compile("gl_in\\s*\\[([^\\]]*)\\]\\s*\\.\\s*gl_(FrontColor|TexCoord)");
    private static final Pattern LEGACY_POSITION_IN = Pattern.compile("gl_PositionIn\\s*\\[([^\\]]*)\\]");

    /** The stages of a program that exist. A stage it cannot use is dropped with a warning. */
    private List<Middle> middleStages(String name, String[] folders, boolean geometry) {
        List<Middle> found = new ArrayList<>();
        for (Middle m : MIDDLE_STAGES) {
            if (findProgram(name + m.extension, folders) != null) found.add(m);
        }
        boolean control = found.stream().anyMatch(m -> m.stage == GlslLowering.Stage.TESS_CONTROL);
        boolean eval = found.stream().anyMatch(m -> m.stage == GlslLowering.Stage.TESS_EVAL);
        if (eval && !control) {
            // GL has a default control stage, not a default evaluation stage.
            found.add(0, MIDDLE_STAGES.get(0));
        } else if (control && !eval) {
            warnings.add(name + ": a tessellation control stage without an evaluation stage is skipped");
            found.removeIf(m -> m.stage != GlslLowering.Stage.GEOMETRY);
        }
        return found;
    }

    private Lowered lowerProgram(String name, String[] folders, String prefix, boolean geometry, boolean combination) throws IOException {
        lowering = name;
        String fsh = findProgram(name + ".fsh", folders), vsh = findProgram(name + ".vsh", folders);
        if (fsh == null) {
            if (vsh != null) warnings.add(name + ": a vertex program without a fragment program is skipped");
            return null;
        }
        List<Middle> middles = middleStages(name, folders, geometry);
        GlslLowering.Program program = new GlslLowering.Program(name, geometry, combination);
        program.resources = resources;
        program.customTextures = customTextures;
        boolean shadow = name.equals("shadow");

        StringBuilder vertexOut = new StringBuilder();
        String vertexSource = vsh != null ? include(vsh, new LinkedHashSet<>(Set.of(vsh)))
                : "void main() { gl_Position = ftransform(); gl_TexCoord[0] = gl_MultiTexCoord0; gl_TexCoord[1] = gl_MultiTexCoord1; gl_FrontColor = gl_Color; }\n";
        GlslPreprocessor vp = preprocessor("MC_VERTEX_SHADER");
        String vertex = new GlslLowering(program, GlslLowering.Stage.VERTEX, customUniforms, warnings, vp).lower(vp.process(vertexSource));
        List<String> middleTexts = new ArrayList<>();
        for (Middle m : middles) {
            program.nextStage();
            String file = findProgram(name + m.extension, folders);
            if (file == null) {
                program.passThrough();
                middleTexts.add("");
                continue;
            }
            GlslPreprocessor mp = preprocessor(m.macro);
            String source = LEGACY_IN.matcher(mp.process(include(file, new LinkedHashSet<>(Set.of(file))))).replaceAll("gl_$2In[$1]");
            source = LEGACY_POSITION_IN.matcher(source).replaceAll("gl_in[$1].gl_Position");
            middleTexts.add(new GlslLowering(program, m.stage, customUniforms, warnings, mp).lower(source));
        }
        GlslPreprocessor fp = preprocessor("MC_FRAGMENT_SHADER");
        String preprocessed = fp.process(include(fsh, new LinkedHashSet<>(Set.of(fsh))));
        String fragment = new GlslLowering(program, GlslLowering.Stage.FRAGMENT, customUniforms, warnings, fp).lower(preprocessed);
        programConsts.clear();
        readConsts(vp);
        readConsts(fp);
        Set<Integer> mipmapped = new TreeSet<>();
        for (int t = 0; t < 16; t++) {
            if ("true".equals(bufferConst(programConsts, t, "MipmapEnabled"))) mipmapped.add(t);
        }

        List<String> images = new ArrayList<>();
        StringBuilder imageNames = new StringBuilder();
        for (String image : program.colorImages) {
            boolean shadowImage = image.startsWith("shadow");
            String n = image.replaceAll("\\D", "");
            String texture = (shadowImage ? SHADOW_WORK : "colortex") + n;
            if (shadowImage) {
                shadowWork.add(Integer.parseInt(n));
                // That copy runs after the shadow pass and would replace what this program stored.
                if (shadow) warnings.add("shadow: " + image + " is overwritten by the copy of the shadow map");
            }
            images.add(texture);
            imageNames.append("#define ").append(image).append(' ').append(Prelude.storageImage(texture)).append('\n');
        }

        String head0 = "";
        Matcher used = GEOMETRY_OUT.matcher(fragment);
        Set<Integer> written = new java.util.TreeSet<>();
        while (used.find()) written.add(Integer.parseInt(used.group(1)));

        // gl_FragData[i] writes the i-th draw buffer. A buffer nothing writes keeps its contents:
        // attaching it would store undefined values.
        List<Integer> buffers = drawBuffers(fp);
        Map<Integer, Integer> outputs = new TreeMap<>();
        for (int i : written) {
            if (buffers == null) outputs.put(i, i);
            else if (i < buffers.size()) outputs.put(i, buffers.get(i));
        }
        List<Integer> targets = new ArrayList<>(new LinkedHashSet<>(outputs.values()));
        StringBuilder head = new StringBuilder(compatHeader(program, shadow, geometry, GlslLowering.Stage.FRAGMENT));
        StringBuilder custom = customTextures(name, program).append(imageNames);
        head.insert(0, custom);
        if (targets.isEmpty() && !geometry) {
            targets.add(buffers != null && !buffers.isEmpty() ? buffers.get(0) : 0);
            head.append("layout(location = 0) out vec4 vkm_unwritten;\n");
        }
        for (int i : written) {
            Integer buffer = outputs.get(i);
            if (buffer == null || (shadow && buffer >= IrPack.SHADOW_COLORS.size())) {
                head.append("vec4 vkm_fragData").append(i).append(";\n");
            } else if (geometry) {
                head.append("#define vkm_fragData").append(i).append(" vkmOut_").append(shadow ? "shadowcolor" : "colortex").append(buffer).append('\n');
            } else {
                head.append("layout(location = ").append(targets.indexOf(buffer)).append(") out ")
                        .append(program.integerOutputs.getOrDefault(i, "vec4")).append(" vkm_fragData").append(i).append(";\n");
            }
        }
        int texCoords = texCoordCount(vertex + String.join("", middleTexts), fragment);
        head.append(legacyVaryings(program, "in", texCoords, program.legacyBase(middles.size()), "", ""));

        String tail = "\nvoid main() {\n    vkm_main();\n" + (geometry && written.contains(0) ? alphaTest(name) : "") + "}\n";
        String fragmentText = head + GlslLowering.finish(fragment) + tail;

        StringBuilder vhead = new StringBuilder(compatHeader(program, shadow, geometry, GlslLowering.Stage.VERTEX));
        vhead.insert(0, custom);
        vhead.append(legacyVaryings(program, "out", texCoords, program.legacyBase(0), "", ""));
        if (!geometry) vhead.append("layout(location = 0) out vec2 vkm_texCoord;\n");
        boolean writesColor = vertex.contains("vkm_Color =") || vertex.contains("vkm_Color=");
        String vtail = "\nvoid main() {\n" + (program.usesFrontColor && !writesColor ? "    vkm_Color = vkm_glColor;\n" : "")
                + (geometry ? "" : "    vkm_texCoord = vkm_quad;\n")
                + "    vkm_main();\n" + (middles.isEmpty() ? DEPTH_REMAP : "") + "}\n";
        String vertexText = vhead + GlslLowering.finish(vertex) + vtail;

        Set<String> attributes = new LinkedHashSet<>();
        if (geometry) {
            for (String a : program.attributes) {
                attributes.add(switch (a) {
                    case "mc_Entity" -> "blockId";
                    case "mc_midTexCoord" -> "midTexCoord";
                    case "at_midBlock" -> "midBlock";
                    default -> "tangent";
                });
            }
            if (Set.of("gbuffers_terrain", "gbuffers_water", "gbuffers_block", "shadow", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic").contains(name)
                    && vertexText.contains("vkm_glNormal")) {
                attributes.add("normal");
            }
        }

        String vertexPath = prefix + name + ".vsh", fragmentPath = prefix + name + ".fsh";
        programs.put(vertexPath, storageBlocks(vertexText));
        programs.put(fragmentPath, storageBlocks(fragmentText));
        Map<String, String> middlePaths = new LinkedHashMap<>();
        for (int i = 0; i < middles.size(); i++) {
            Middle m = middles.get(i);
            String path = prefix + name + m.extension;
            String text = middleProgram(program, m, i, i + 1 == middles.size(), shadow, geometry, custom, texCoords,
                    findProgram(name + m.extension, folders) == null ? program.defaultControl() : middleTexts.get(i));
            programs.put(path, storageBlocks(text));
            middlePaths.put(m.key, path);
        }
        return new Lowered(program, vertexPath, fragmentPath, targets, program.samplers, attributes, mipmapped, middlePaths, images);
    }

    /** Pack depth is in GL's range. The last stage before the rasterizer maps it, so earlier ones see GL values. */
    private static final String DEPTH_REMAP = "    gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);\n";

    private String middleProgram(GlslLowering.Program program, Middle m, int index, boolean last, boolean shadow, boolean geometry,
                                 StringBuilder custom, int texCoords, String lowered) {
        StringBuilder head = new StringBuilder(compatHeader(program, shadow, geometry, m.stage));
        head.insert(0, custom);
        head.append(legacyVaryings(program, "in", texCoords, program.legacyBase(index), "In", "[]"));
        head.append(legacyVaryings(program, "out", texCoords, program.legacyBase(index + 1), "",
                m.stage == GlslLowering.Stage.TESS_CONTROL ? "[]" : ""));
        // A pass's fragment stage reads its position on the screen from the stage before it.
        String quad = "";
        if (!geometry && last) {
            head.append("layout(location = 0) out vec2 vkm_texCoord;\n");
            quad = "    vkm_texCoord = gl_Position.xy / gl_Position.w * 0.5 + 0.5;\n";
        }
        if (m.stage == GlslLowering.Stage.GEOMETRY) {
            // The pre-layout spelling: only OptiFine's maxVerticesOut constant says how many.
            if (!GEOMETRY_INPUT_LAYOUT.matcher(lowered).find()) {
                Matcher max = MAX_VERTICES_OUT.matcher(lowered);
                head.append("layout(triangles) in;\nlayout(triangle_strip, max_vertices = ")
                        .append(max.find() ? max.group(1) : "3").append(") out;\n");
            }
            head.append("void vkm_emit() {\n").append(quad).append(DEPTH_REMAP).append("    EmitVertex();\n}\n#define EmitVertex() vkm_emit()\n");
        }
        String tail = "\nvoid main() {\n    vkm_main();\n" + (last && m.stage == GlslLowering.Stage.TESS_EVAL ? quad + DEPTH_REMAP : "") + "}\n";
        return head + GlslLowering.finish(lowered) + tail;
    }

    /** The stage of {@code texture.<stage>.<name>} a program belongs to. */
    private static String stageOf(String program) {
        if (program.startsWith("gbuffers_")) return "gbuffers";
        if (program.startsWith("deferred")) return "deferred";
        if (program.startsWith("composite") || program.equals("final")) return "composite";
        return program;
    }

    /** {@code texture.<stage>.<name>=<png>}: the sampler reads the file in that stage. Returns the macros that say so. */
    private StringBuilder customTextures(String program, GlslLowering.Program p) throws IOException {
        StringBuilder macros = new StringBuilder();
        Map<String, String> files = properties.under("texture." + stageOf(program) + ".");
        for (Map.Entry<String, String> e : files.entrySet()) {
            String sampler = GlslLowering.alias(e.getKey());
            if (!p.samplers.contains(sampler)) continue;
            String raw = rawTextures.get(stageOf(program) + "." + e.getKey());
            if (raw != null) {
                macros.append("#define ").append(sampler).append(' ').append(raw).append('\n');
                p.samplers.remove(sampler);
                continue;
            }
            String file = PackSource.normalize(ROOT + e.getValue().trim());
            if (!file.endsWith(".png") || !src.exists(file)) {
                warnings.add("texture." + stageOf(program) + "." + e.getKey() + ": only PNG files of the pack are supported");
                continue;
            }
            String name = customNames.computeIfAbsent(file, f -> "vkf_file" + customNames.size());
            macros.append("#define ").append(sampler).append(' ').append(name).append('\n');
            p.samplers.remove(sampler);
            p.samplers.add(name);
        }
        return macros;
    }

    private final Map<String, String> customNames = new LinkedHashMap<>();
    /** {@code customTexture.<sampler>}: the sampler reads this input in every program. */
    private final Map<String, String> customTextures = new LinkedHashMap<>();

    /** Width and height from the IHDR chunk. */
    private int[] pngSize(String file) throws IOException {
        byte[] b = src.readBytes(file);
        return new int[]{(b[16] & 255) << 24 | (b[17] & 255) << 16 | (b[18] & 255) << 8 | (b[19] & 255),
                (b[20] & 255) << 24 | (b[21] & 255) << 16 | (b[22] & 255) << 8 | (b[23] & 255)};
    }

    /** gl_FrontColor, gl_TexCoord and gl_FogFragCoord where used, after the program's own varyings. */
    private static final int MAX_VARYING_LOCATIONS = 32;
    private static final Pattern TEX_COORD_INDEX = Pattern.compile("vkm_TexCoord\\s*\\[\\s*(?:(\\d+)\\s*\\]|)");

    private static String legacyVaryings(GlslLowering.Program p, String direction, int texCoords, int base, String suffix, String perVertex) {
        StringBuilder b = new StringBuilder();
        int at = base;
        if (p.usesFrontColor) b.append("layout(location = ").append(at++).append(") ").append(direction).append(" vec4 vkm_Color").append(suffix).append(perVertex).append(";\n");
        if (p.usesTexCoord) {
            b.append("layout(location = ").append(at).append(") ").append(direction).append(" vec4 vkm_TexCoord").append(suffix).append(perVertex).append('[').append(texCoords).append("];\n");
            at += texCoords;
        }
        if (p.usesFogCoord) b.append("layout(location = ").append(at++).append(") ").append(direction).append(" float vkm_FogCoord").append(suffix).append(perVertex).append(";\n");
        if (at > MAX_VARYING_LOCATIONS) {
            // Past the limit a GPU can fault at draw time instead of failing the pipeline.
            throw new IllegalArgumentException(p.name + ": " + at + " varying locations, the limit is " + MAX_VARYING_LOCATIONS);
        }
        return b.toString();
    }

    /** gl_TexCoord entries in use: a non-constant index keeps the whole array. */
    private static int texCoordCount(String vertex, String fragment) {
        if (vertex.contains("vkm_TexCoordIn")) return 8;
        Matcher m = TEX_COORD_INDEX.matcher(vertex + fragment);
        int count = 1;
        while (m.find()) {
            count = m.group(1) == null ? 8 : Math.max(count, Integer.parseInt(m.group(1)) + 1);
            if (count == 8) break;
        }
        return count;
    }

    private static List<Integer> drawBuffers(GlslPreprocessor p) {
        List<Integer> out = null;
        for (String line : p.directiveLines) {
            Matcher r = RENDERTARGETS.matcher(line);
            if (r.find()) {
                out = new ArrayList<>();
                for (String s : r.group(1).split(",")) if (!s.isBlank()) out.add(Integer.parseInt(s.trim()));
                continue;
            }
            Matcher d = DRAWBUFFERS.matcher(line);
            if (d.find()) {
                out = new ArrayList<>();
                for (char c : d.group(1).toCharArray()) out.add(c - '0');
            }
        }
        return out;
    }

    /** The constants of the program being lowered: some settings are per program. */
    private final Map<String, String> programConsts = new HashMap<>();

    private void readConsts(GlslPreprocessor p) {
        for (String line : p.directiveLines) {
            Matcher m = CONST.matcher(line);
            while (m.find()) {
                String value = m.group(3).trim();
                String macro = p.value(value);
                consts.put(m.group(2), macro != null ? macro : value);
                programConsts.put(m.group(2), macro != null ? macro : value);
            }
        }
    }

    /** The compatibility layer in front of a lowered program. */
    private String compatHeader(GlslLowering.Program p, boolean shadow, boolean geometry, GlslLowering.Stage stage) {
        StringBuilder h = new StringBuilder();
        macroValues.forEach((k, v) -> h.append("#define ").append(k).append(' ').append(v).append('\n'));
        h.append("""
                vec4 vkm_shadow2D(sampler2DShadow s, vec3 c) { return vec4(texture(s, c)); }
                vec4 vkm_shadow2D(sampler2D s, vec3 c) { return vec4(step(c.z, texture(s, c.xy).r)); }
                vec4 vkm_shadow2DLod(sampler2DShadow s, vec3 c, float lod) { return vec4(textureGrad(s, c, vec2(0.0), vec2(0.0))); }
                vec4 vkm_shadow2DLod(sampler2D s, vec3 c, float lod) { return vec4(step(c.z, textureLod(s, c.xy, lod).r)); }
                const mat4 vkm_glTextureMatrix[2] = mat4[2](mat4(1.0),
                    mat4(0.00390625, 0.0, 0.0, 0.0, 0.0, 0.00390625, 0.0, 0.0, 0.0, 0.0, 0.00390625, 0.0, 0.03125, 0.03125, 0.03125, 1.0));
                struct vkm_FogParams { vec4 color; float density; float start; float end; float scale; };
                """);
        // The frame's fog is the terrain's. Vanilla fogs the sky from the camera to the far plane.
        boolean sky = "gbuffers_skybasic".equals(lowering);
        h.append("#define vkm_glFog vkm_FogParams(vec4(fogColor, 1.0), fogDensity, ").append(sky ? "0.0, far, 1.0 / max(far, 0.0001)" : "fogStart, fogEnd, 1.0 / max(fogEnd - fogStart, 0.0001)").append(")\n");
        // Globals, not macros: a parameter of a pack function may have the same name.
        for (String u : customUniforms) h.append(uniformDeclaration(u));
        if (!geometry) {
            h.append("const vec4 entityColor = vec4(0.0);\nconst int entityId = 0;\nconst int blockEntityId = 0;\nconst int renderStage = MC_RENDER_STAGE_NONE;\nconst int currentRenderedItemId = 0;\n");
            if (stage.isMiddle()) {
                h.append("""
                        #define vkm_glModelView mat4(1.0)
                        #define vkm_glModelViewInverse mat4(1.0)
                        #define vkm_glProjection mat4(2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, -1.0, -1.0, -1.0, 1.0)
                        #define vkm_glProjectionInverse inverse(vkm_glProjection)
                        #define vkm_glMVP vkm_glProjection
                        #define vkm_glNormalMatrix mat3(1.0)
                        """);
            }
            if (stage == GlslLowering.Stage.VERTEX) {
                h.append("""
                        #define vkm_quad vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2)
                        #define vkm_glVertex vec4(vkm_quad, 0.0, 1.0)
                        #define vkm_glColor vec4(1.0)
                        #define vkm_glNormal vec3(0.0, 0.0, 1.0)
                        #define vkm_glMultiTexCoord0 vec4(vkm_quad, 0.0, 1.0)
                        #define vkm_glMultiTexCoord1 vec4(240.0, 240.0, 0.0, 1.0)
                        #define vkm_glModelView mat4(1.0)
                        #define vkm_glModelViewInverse mat4(1.0)
                        #define vkm_glProjection mat4(2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, -1.0, -1.0, -1.0, 1.0)
                        #define vkm_glProjectionInverse inverse(vkm_glProjection)
                        #define vkm_glMVP vkm_glProjection
                        #define vkm_glNormalMatrix mat3(1.0)
                        vec4 vkm_ftransform() { return vkm_glMVP * vkm_glVertex; }
                        """);
            }
            return h.toString();
        }
        h.append("#ifndef VKM_LIGHTMAP\n#define lightmap vkf_white\n#endif\n#ifndef VKM_GTEXTURE\n#define gtexture vkf_white\n#endif\n");
        p.samplers.add("vkf_white");
        if (stage == GlslLowering.Stage.VERTEX) {
            String projection = shadow ? "shadowProjection"
                    : p.name.startsWith("gbuffers_hand") ? "(vkm_unremap * vkm_mvp * inverse(vkm_modelView))" : "gbufferProjection";
            h.append("""
                    const mat4 vkm_unremap = mat4(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, -1.0, 1.0);
                    #define vkm_glVertex vec4(vaPosition, 1.0)
                    #define vkm_glColor (vaColor * vkm_tint)
                    #define vkm_glNormal vaNormal
                    #define vkm_glMultiTexCoord0 vec4(vaUV0, 0.0, 1.0)
                    #define vkm_glMultiTexCoord1 vec4(vec2(vaUV2), 0.0, 1.0)
                    #define vkm_glMidTexCoord vec4(mc_midTexCoord, 0.0, 1.0)
                    #define vkm_glModelView vkm_modelView
                    #define vkm_glModelViewInverse inverse(vkm_modelView)
                    #define vkm_glMVP (vkm_unremap * vkm_mvp)
                    vec4 vkm_ftransform() { return vkm_glMVP * vkm_glVertex; }
                    """);
            h.append("#define vkm_glNormalMatrix mat3(vkm_modelView)\n");
            h.append("#define vkm_glProjection ").append(projection).append('\n');
            h.append("#define vkm_glProjectionInverse inverse(vkm_glProjection)\n");
        } else if (stage.isMiddle()) {
            // Only the vertex stage knows the draw's matrices: these are the camera's.
            String view = shadow ? "shadowModelView" : "gbufferModelView";
            h.append("#define vkm_glModelView ").append(view).append('\n');
            h.append("#define vkm_glModelViewInverse inverse(").append(view).append(")\n");
            h.append("#define vkm_glProjection ").append(shadow ? "shadowProjection" : "gbufferProjection").append('\n');
            h.append("#define vkm_glProjectionInverse inverse(vkm_glProjection)\n");
            h.append("#define vkm_glMVP (vkm_glProjection * vkm_glModelView)\n");
            h.append("#define vkm_glNormalMatrix mat3(vkm_glModelView)\n");
        }
        return h.toString();
    }

    private String uniformDeclaration(String name) {
        for (var e : uniformsIr) {
            JsonObject u = e.getAsJsonObject();
            if (!u.get("name").getAsString().equals(name)) continue;
            String slot = "vkm_packUniforms[" + u.get("slot").getAsInt() + "]";
            String type = u.get("type").getAsString();
            // A pack's uniform named like one of the frame block's is the pack's own.
            String own = FrameBlock.LAYOUT.fields().stream().anyMatch(f -> f.name().equals(name))
                    ? "#define " + name + " vkm_c_" + name + "\n" : "";
            return own + type + " " + name + " = " + switch (type) {
                case "float" -> slot + ".x";
                case "int" -> "int(" + slot + ".x)";
                case "bool" -> "(" + slot + ".x != 0.0)";
                case "vec2" -> slot + ".xy";
                case "vec3" -> slot + ".xyz";
                case "ivec2" -> "ivec2(" + slot + ".xy)";
                case "ivec3" -> "ivec3(" + slot + ".xyz)";
                case "ivec4" -> "ivec4(" + slot + ")";
                default -> slot;
            } + ";\n";
        }
        throw new IllegalStateException(name);
    }

    // ------------------------------------------------------------------------------------
    // Resources

    private static final Map<String, Integer> LEGACY_BUFFERS = Map.of("gcolor", 0, "gdepth", 1, "gnormal", 2, "composite", 3,
            "gaux1", 4, "gaux2", 5, "gaux3", 6, "gaux4", 7);

    private String bufferConst(int buffer, String suffix) {
        return bufferConst(consts, buffer, suffix);
    }

    private static String bufferConst(Map<String, String> consts, int buffer, String suffix) {
        String v = consts.get("colortex" + buffer + suffix);
        if (v != null) return v;
        for (Map.Entry<String, Integer> e : LEGACY_BUFFERS.entrySet()) {
            if (e.getValue() == buffer && consts.containsKey(e.getKey() + suffix)) return consts.get(e.getKey() + suffix);
        }
        return null;
    }

    /** G5's compositeMipmapSetting: per program, the buffers it declares and reads. */
    private void addMipmapsBefore(JsonObject pass, Lowered l) {
        JsonArray mips = new JsonArray();
        for (int t = 0; t < 16; t++) {
            if (l.mipmapped.contains(t) && l.inputs.contains("colortex" + t)) mips.add("colortex" + t);
        }
        if (!mips.isEmpty()) pass.add("mipmapsBefore", mips);
        // MipmapEnabled holds for its program. Elsewhere a texture() on an edge of the coordinates
        // would take a level from the quad's derivatives: a stale one, as a dark 2x2 block.
        JsonArray flat = new JsonArray();
        for (String s : l.inputs) {
            if (s.matches("colortex\\d+") && !l.mipmapped.contains(Integer.parseInt(s.substring(8)))) flat.add(s);
        }
        if (!flat.isEmpty()) pass.add("baseLevelInputs", flat);
    }

    private boolean mipmapped(int buffer) {
        return "true".equals(bufferConst(buffer, "MipmapEnabled"));
    }

    private JsonObject texture(int t) {
        JsonObject o = new JsonObject();
        o.addProperty("name", "colortex" + t);
        String format = bufferConst(t, "Format");
        o.addProperty("format", format == null ? "RGBA8" : irFormat(format));
        String clear = bufferConst(t, "Clear");
        if ("false".equals(clear)) {
            o.addProperty("clear", false);
        } else {
            JsonArray c = new JsonArray();
            float[] value = t == 1 ? new float[]{1, 1, 1, 1} : new float[]{0, 0, 0, t == 0 ? 1 : 0};
            String color = bufferConst(t, "ClearColor");
            if (t == 0 && color == null) {
                // OptiFine leaves the vanilla clear color in place: the part of the sky the
                // sky disc does not reach has to match the fog.
                o.addProperty("clear", "fog");
            } else if (color != null) {
                Matcher m = Pattern.compile("-?\\d*\\.?\\d+").matcher(color.replaceAll("vec\\d", ""));
                int i = 0;
                float last = 0;
                while (m.find() && i < 4) value[i++] = last = Float.parseFloat(m.group());
                if (i == 1) value = new float[]{last, last, last, last};
            }
            for (float f : value) c.add(f);
            if (!o.has("clear")) o.add("clear", c);
        }
        if (mipmapped(t)) o.addProperty("mipmaps", true);
        String size = properties.get("size.buffer.colortex" + t);
        if (size != null) {
            String[] wh = size.trim().split("\\s+");
            // The properties may name an option, which the file's preprocessor would expand.
            for (int i = 0; i < wh.length; i++) {
                Option option = options.get(wh[i]);
                if (option != null) wh[i] = option.value;
            }
            if (wh[0].contains(".") || wh.length < 2) {
                o.addProperty("scale", Math.min(1.0f, Float.parseFloat(wh[0])));
            } else {
                JsonArray s = new JsonArray();
                s.add(Integer.parseInt(wh[0]));
                s.add(Integer.parseInt(wh[1]));
                o.add("size", s);
            }
        }
        return o;
    }

    private String irFormat(String name) {
        String n = name.trim();
        TextureFormat f = TextureFormat.parse(switch (n) {
            case "RGBA", "RGB", "RGBA4", "RGB5_A1", "RGB565", "RGB5", "R3_G3_B2", "RGBA2", "RGB4" -> "RGBA8";
            case "R11F_G11F_B10F" -> "R11G11B10F";
            case "RGB10_A2" -> "RGB10A2";
            case "R16", "R16_SNORM", "R8_SNORM" -> "R16F";
            case "RG16", "RG16_SNORM", "RG8_SNORM" -> "RG16F";
            case "RGB8_SNORM", "RGBA8_SNORM", "RGB16_SNORM", "RGBA16_SNORM", "RGB9_E5" -> "RGBA16F";
            default -> n;
        });
        if (f == null) {
            warnings.add("unknown buffer format " + n + ", using RGBA16F");
            return "RGBA16F";
        }
        return f.name();
    }

    private void addSpecialTextures(JsonArray textures, JsonObject ir) throws IOException {
        String all = ir.toString();
        for (Map.Entry<String, String> e : customNames.entrySet()) {
            if (!all.contains("\"" + e.getValue() + "\"")) continue;
            int[] size = pngSize(e.getKey());
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getValue());
            o.addProperty("format", "RGBA8");
            JsonArray s = new JsonArray();
            s.add(size[0]);
            s.add(size[1]);
            o.add("size", s);
            o.addProperty("file", e.getKey());
            applyMeta(o, e.getKey());
            textures.add(o);
        }
        if (all.contains("vkf_white")) textures.add(solid("vkf_white", "ffffffff"));
        if (all.contains("vkf_noisetex")) {
            JsonObject o = new JsonObject();
            o.addProperty("name", "vkf_noisetex");
            o.addProperty("format", "RGBA8");
            int size = 256;
            String res = consts.get("noiseTextureResolution");
            if (res != null) {
                try {
                    size = Integer.parseInt(res.trim());
                } catch (NumberFormatException ignored) {
                    warnings.add("noiseTextureResolution is not a number: " + res);
                }
            }
            String file = properties.get("texture.noise");
            String path = file != null ? PackSource.normalize(ROOT + file.trim()) : null;
            boolean png = path != null && path.endsWith(".png") && src.exists(path);
            int[] wh = png ? pngSize(path) : new int[]{size, size};
            JsonArray s = new JsonArray();
            s.add(wh[0]);
            s.add(wh[1]);
            o.add("size", s);
            o.addProperty("file", png ? path : "vkm:noise");
            if (png) {
                applyMeta(o, path);
            } else {
                o.addProperty("wrap", "repeat");
            }
            textures.add(o);
        }
    }

    /** {@code <file>.mcmeta}: OptiFine's defaults are GL_NEAREST and GL_REPEAT. */
    private void applyMeta(JsonObject texture, String file) throws IOException {
        boolean blur = false, clamp = false;
        String meta = file + ".mcmeta";
        if (src.exists(meta)) {
            try {
                JsonObject root = new com.google.gson.JsonParser().parse(new String(src.readBytes(meta), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject t = root.getAsJsonObject("texture");
                if (t != null) {
                    blur = t.has("blur") && t.get("blur").getAsBoolean();
                    clamp = t.has("clamp") && t.get("clamp").getAsBoolean();
                }
            } catch (RuntimeException e) {
                warnings.add(meta + " is not readable: " + e.getMessage());
            }
        }
        texture.addProperty("filter", blur ? "linear" : "nearest");
        texture.addProperty("wrap", clamp ? "clamp" : "repeat");
    }

    /** A generated 1x1 texture, see {@code PackRuntime.decodePng}. */
    private static JsonObject solid(String name, String rgba) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("format", "RGBA8");
        JsonArray s = new JsonArray();
        s.add(1);
        s.add(1);
        o.add("size", s);
        o.addProperty("file", "vkm:solid:" + rgba);
        return o;
    }

    private JsonObject shadowSection() {
        JsonObject s = new JsonObject();
        s.addProperty("resolution", (int) number("shadowMapResolution", 1024));
        s.addProperty("distance", number("shadowDistance", 160));
        s.addProperty("intervalSize", number("shadowIntervalSize", 2));
        s.addProperty("sunPathRotation", number("sunPathRotation", 0));
        s.addProperty("fov", number("shadowMapFov", 0));
        s.addProperty("angleInterval", number("shadowAngleInterval", 0));
        s.addProperty("terrain", !"false".equals(properties.get("shadowTerrain")));
        s.addProperty("translucent", !"false".equals(properties.get("shadowTranslucent")));
        s.addProperty("entities", !"false".equals(properties.get("shadowEntities")));
        s.addProperty("blockEntities", !"false".equals(properties.get("shadowBlockEntities")));
        String culling = properties.get("shadow.culling");
        s.addProperty("culling", !"false".equals(culling) && !"reversed".equals(culling));
        s.addProperty("nearest", "true".equals(consts.get("shadowtexNearest")) || "true".equals(consts.get("shadowtex0Nearest")));
        JsonArray formats = new JsonArray(), mips = new JsonArray();
        for (int i = 0; i < IrPack.SHADOW_COLORS.size(); i++) {
            String f = consts.get("shadowcolor" + i + "Format");
            formats.add(f == null ? "RGBA8" : irFormat(f));
            mips.add(shadowColorMipmapped(i));
        }
        s.add("colorFormats", formats);
        s.add("colorMipmaps", mips);
        return s;
    }

    private float number(String key, float fallback) {
        String v = consts.get(key);
        if (v == null) return fallback;
        try {
            return Float.parseFloat(v.replaceAll("[fF]$", ""));
        } catch (NumberFormatException e) {
            warnings.add(key + " is not a number: " + v);
            return fallback;
        }
    }
}
