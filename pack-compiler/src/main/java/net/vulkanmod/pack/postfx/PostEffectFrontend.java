package net.vulkanmod.pack.postfx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.vulkanmod.pack.ir.IrPack;
import net.vulkanmod.pack.source.PackSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The 26.2 {@code post_effect} frontend (frontends.md): lowers post chains into an
 * {@link IrPack} plus generated GLSL 450 programs. A pack is a Vulcade manifest
 * ({@code vulcade.json}: profile → scene → chains) or plain chains, of which one runs.
 *
 * <p>Targets are RGBA8 as in 26.2. {@code minecraft:main} is the host scene color until a
 * pass writes it; earlier writes go to a texture, and the last write is the combination.
 * Depth is reversed in 26.2, so a generated first pass stores {@code 1 - sceneDepth}.</p>
 */
public final class PostEffectFrontend {

    public static final String MANIFEST = "vulcade.json";

    private static final String MAIN = "minecraft:main";
    private static final String SCREENQUAD = "minecraft:core/screenquad";
    private static final String GENERATED_ROOT = "vkm_gen/";
    private static final String MAIN_TEXTURE = "pfx_main_t", DEPTH_TEXTURE = "pfx_depth_t";
    private static final String DEPTH_PROGRAM = GENERATED_ROOT + "reversed_depth.fsh";
    private static final Pattern IMPORT = Pattern.compile("^[ \\t]*#[ \\t]*moj_import[ \\t]*([<\"])([^>\"]+)[>\"].*$",
            Pattern.MULTILINE);

    /** Block declarations of the vanilla includes, used when a pack does not ship them. */
    private static final Map<String, String> VANILLA_INCLUDES = Map.of(
            "minecraft:globals.glsl", """
                    layout(std140) uniform Globals {
                        ivec3 CameraBlockPos; vec3 CameraOffset; vec2 ScreenSize; float GlintAlpha;
                        float GameTime; int MenuBlurRadius; int UseRgss;
                    };""",
            "minecraft:projection.glsl", "layout(std140) uniform Projection { mat4 ProjMat; };",
            "minecraft:fog.glsl", """
                    layout(std140) uniform Fog {
                        vec4 FogColor; float FogEnvironmentalStart; float FogEnvironmentalEnd;
                        float FogRenderDistanceStart; float FogRenderDistanceEnd; float FogSkyEnd; float FogCloudsEnd;
                    };""",
            "minecraft:light.glsl", "layout(std140) uniform Lighting { vec3 Light0_Direction; vec3 Light1_Direction; };");

    private static final Map<String, String> VANILLA_SHADERS = Map.of("minecraft:post/blit", """
            #version 330
            uniform sampler2D InSampler;
            layout(std140) uniform BlitConfig { vec4 ColorModulate; };
            in vec2 texCoord;
            out vec4 fragColor;
            void main() { fragColor = texture(InSampler, texCoord) * ColorModulate; }
            """);

    /** The lowered pack, the programs to overlay on the source, and notes for the log. */
    public record Result(IrPack pack, Map<String, String> programs, List<String> warnings) {
    }

    private final PackSource src;
    private final String id;
    private final Map<String, String> programs = new LinkedHashMap<>();
    private final Map<String, String> pathByText = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private PostEffectFrontend(PackSource src, String id) {
        this.src = src;
        this.id = id;
    }

    /**
     * @param profile manifest profile, or null for its default
     * @throws IllegalArgumentException with a readable message on a pack that cannot run
     */
    public static Result lower(PackSource src, String id, String profile) throws IOException {
        PostEffectFrontend f = new PostEffectFrontend(src, id);
        JsonObject ir = src.exists(MANIFEST) ? f.lowerManifest(profile) : f.lowerPlainChains();
        return new Result(IrPack.parse(id, ir), f.programs, f.warnings);
    }

    private JsonObject lowerManifest(String profile) throws IOException {
        JsonObject manifest = parseJson(MANIFEST);
        String chosen = profile != null ? profile : string(manifest, "default_profile", null);
        JsonObject profiles = manifest.getAsJsonObject("profiles");
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException(MANIFEST + " has no profiles");
        }
        if (chosen == null) chosen = profiles.keySet().iterator().next();
        if (!profiles.has(chosen)) {
            throw new IllegalArgumentException(MANIFEST + " has no profile " + chosen);
        }
        JsonObject scenes = profiles.getAsJsonObject(chosen);
        if (!scenes.has("world")) {
            throw new IllegalArgumentException("profile " + chosen + " has no world chains");
        }
        JsonObject ir = lowerScene(chains(scenes.getAsJsonArray("world")), string(manifest, "name", id));
        JsonObject variants = new JsonObject();
        for (String scene : IrPack.SCENES) {
            if (scenes.has(scene)) {
                variants.add(scene, lowerScene(chains(scenes.getAsJsonArray(scene)), scene));
            }
        }
        if (!variants.isEmpty()) ir.add("scenes", variants);
        return ir;
    }

    private static List<String> chains(JsonArray ids) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : ids) out.add(e.getAsString());
        return out;
    }

    /** Without a manifest nothing says which chain to run: the first by name. */
    private JsonObject lowerPlainChains() throws IOException {
        Set<String> found = new TreeSet<>();
        for (String name : src.list("post_effect")) {
            if (name.endsWith(".json")) found.add("post_effect/" + name);
        }
        for (String ns : src.list("assets")) {
            for (String name : src.list("assets/" + ns + "/post_effect")) {
                if (name.endsWith(".json")) found.add("assets/" + ns + "/post_effect/" + name);
            }
        }
        if (found.isEmpty()) throw new IllegalArgumentException("no post_effect chains in the pack");
        String first = found.iterator().next();
        if (found.size() > 1) warnings.add(found.size() + " chains and no " + MANIFEST + ": running " + first);
        return lowerSceneFiles(List.of(first), id);
    }

    private JsonObject lowerScene(List<String> chainIds, String name) throws IOException {
        List<String> files = new ArrayList<>();
        for (String chain : chainIds) {
            Identifier c = Identifier.parse(chain);
            files.add("assets/" + c.namespace + "/post_effect/" + c.path + ".json");
        }
        return lowerSceneFiles(files, name);
    }

    /** One pass of a chain, before the combination is known. */
    private static final class Draft {
        String name, fragment, vertex, output;
        final JsonArray inputs = new JsonArray();
        final JsonObject filters = new JsonObject();
        final Set<String> inputNames = new LinkedHashSet<>();
    }

    private JsonObject lowerSceneFiles(List<String> files, String name) throws IOException {
        JsonArray textures = new JsonArray();
        List<Draft> drafts = new ArrayList<>();
        Set<String> written = new HashSet<>(), readFirst = new HashSet<>(), persistent = new HashSet<>();
        Map<String, JsonObject> textureDefs = new LinkedHashMap<>();
        boolean mainWritten = false, usesDepth = false;

        for (int c = 0; c < files.size(); c++) {
            String file = files.get(c);
            if (!src.exists(file)) throw new IllegalArgumentException("missing chain: " + file);
            JsonObject chain = parseJson(file);
            Map<String, String> locals = new HashMap<>();
            JsonObject targets = chain.has("targets") ? chain.getAsJsonObject("targets") : new JsonObject();
            int index = 0;
            for (String target : targets.keySet()) {
                String ir = "pfx" + c + "_" + index++ + "_" + target.replaceAll("[^A-Za-z0-9_]", "_") + "_t";
                locals.put(target, ir);
                JsonObject def = targets.getAsJsonObject(target);
                JsonObject tex = new JsonObject();
                tex.addProperty("name", ir);
                tex.addProperty("format", "RGBA8");
                if (def.has("width") || def.has("height")) {
                    if (!def.has("width") || !def.has("height")) {
                        throw new IllegalArgumentException(file + ": target " + target + " needs both width and height");
                    }
                    JsonArray size = new JsonArray();
                    size.add(def.get("width"));
                    size.add(def.get("height"));
                    tex.add("size", size);
                }
                if (def.has("persistent") && def.get("persistent").getAsBoolean()) persistent.add(ir);
                textureDefs.put(ir, tex);
            }

            JsonArray passes = chain.has("passes") ? chain.getAsJsonArray("passes") : new JsonArray();
            for (int p = 0; p < passes.size(); p++) {
                JsonObject pass = passes.get(p).getAsJsonObject();
                Draft d = new Draft();
                Map<String, String> samplers = new LinkedHashMap<>();
                JsonArray inputs = pass.has("inputs") ? pass.getAsJsonArray("inputs") : new JsonArray();
                for (JsonElement e : inputs) {
                    JsonObject in = e.getAsJsonObject();
                    String sampler = string(in, "sampler_name", null) + "Sampler";
                    boolean depth = in.has("use_depth_buffer") && in.get("use_depth_buffer").getAsBoolean();
                    boolean bilinear = in.has("bilinear") && in.get("bilinear").getAsBoolean();
                    String ir;
                    if (in.has("location")) {
                        ir = textureInput(in, textureDefs);
                    } else {
                        String target = string(in, "target", null);
                        if (target == null) {
                            throw new IllegalArgumentException(file + ": input " + sampler + " has neither target nor location");
                        }
                        if (target.equals(MAIN) && depth) {
                            usesDepth = true;
                            ir = DEPTH_TEXTURE;
                        } else if (depth) {
                            throw new IllegalArgumentException(file + ": only " + MAIN + " has a depth buffer here, not " + target);
                        } else if (target.equals(MAIN)) {
                            ir = mainWritten ? MAIN_TEXTURE : "sceneColor";
                        } else {
                            ir = locals.get(target);
                            if (ir == null) {
                                throw new IllegalArgumentException(file + ": target " + target + " is not available here");
                            }
                            if (!written.contains(ir)) readFirst.add(ir);
                        }
                    }
                    samplers.put(sampler, ir);
                    if (d.inputNames.add(ir)) {
                        d.inputs.add(ir);
                        d.filters.addProperty(ir, bilinear ? "linear" : "nearest");
                    }
                }

                String output = string(pass, "output", null);
                if (output == null) throw new IllegalArgumentException(file + ": pass " + p + " has no output");
                String fragmentId = string(pass, "fragment_shader", null), vertexId = string(pass, "vertex_shader", SCREENQUAD);
                if (fragmentId == null) throw new IllegalArgumentException(file + ": pass " + p + " has no fragment_shader");
                JsonObject uniforms = pass.has("uniforms") ? pass.getAsJsonObject("uniforms") : null;
                Map<String, Integer> varyings = Map.of("texCoord", 0);
                if (!vertexId.equals(SCREENQUAD)) {
                    String vs = readShader(vertexId, "vsh");
                    varyings = PostShaderLowering.varyings(vs);
                    d.vertex = addProgram(vertexId, "vsh", PostShaderLowering.lower(vs, shaderFile(vertexId, "vsh"),
                            samplers, uniforms, varyings, false, warnings));
                }
                d.fragment = addProgram(fragmentId, "fsh", PostShaderLowering.lower(readShader(fragmentId, "fsh"),
                        shaderFile(fragmentId, "fsh"), samplers, uniforms, varyings, true, warnings));
                d.name = Identifier.parse(fragmentId).path.replace("post/", "") + "_" + drafts.size();

                if (output.equals(MAIN)) {
                    d.output = MAIN_TEXTURE;
                    mainWritten = true;
                } else {
                    d.output = locals.get(output);
                    if (d.output == null) throw new IllegalArgumentException(file + ": target " + output + " is not available here");
                }
                if (d.inputNames.contains(d.output)) {
                    throw new IllegalArgumentException(file + ": pass " + p + " reads and writes " + output);
                }
                written.add(d.output);
                drafts.add(d);
            }
        }

        int combination = -1;
        for (int i = 0; i < drafts.size(); i++) {
            if (drafts.get(i).output.equals(MAIN_TEXTURE)) combination = i;
        }
        if (combination < 0) throw new IllegalArgumentException("no pass writes " + MAIN);
        if (combination < drafts.size() - 1) {
            warnings.add((drafts.size() - 1 - combination) + " pass(es) after the last write to " + MAIN + " are skipped");
        }

        boolean mainTexture = false;
        JsonArray passes = new JsonArray();
        if (usesDepth) {
            textureDefs.put(DEPTH_TEXTURE, texture(DEPTH_TEXTURE, "R32F"));
            programs.put(DEPTH_PROGRAM, """
                    #version 450
                    layout(location = 0) out vec4 outDepth;
                    void main() { outDepth = vec4(1.0 - texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r); }
                    """);
            JsonObject pass = new JsonObject();
            pass.addProperty("name", "reversed_depth");
            pass.addProperty("fragment", DEPTH_PROGRAM);
            pass.add("inputs", names("sceneDepth"));
            pass.add("targets", names(DEPTH_TEXTURE));
            passes.add(pass);
        }
        JsonObject combinationPass = null;
        for (int i = 0; i <= combination; i++) {
            Draft d = drafts.get(i);
            JsonObject pass = new JsonObject();
            pass.addProperty("name", d.name);
            pass.addProperty("fragment", d.fragment);
            if (d.vertex != null) pass.addProperty("vertex", d.vertex);
            pass.add("inputs", d.inputs);
            pass.add("filters", d.filters);
            if (i == combination) {
                combinationPass = pass;
            } else {
                mainTexture |= d.output.equals(MAIN_TEXTURE);
                pass.add("targets", names(d.output));
                passes.add(pass);
            }
        }
        if (mainTexture) textureDefs.put(MAIN_TEXTURE, texture(MAIN_TEXTURE, "RGBA8"));

        for (Map.Entry<String, JsonObject> t : textureDefs.entrySet()) {
            // Transient targets start empty, but a clear only matters when one is read first.
            boolean clear = readFirst.contains(t.getKey()) && !persistent.contains(t.getKey());
            if (!t.getValue().has("file")) t.getValue().addProperty("clear", clear);
            textures.add(t.getValue());
        }

        JsonObject ir = new JsonObject();
        ir.addProperty("format", 1);
        ir.addProperty("name", name);
        JsonObject renderer = new JsonObject();
        renderer.addProperty("frameInstance", PostShaderLowering.FRAME_INSTANCE);
        ir.add("renderer", renderer);
        ir.add("textures", textures);
        JsonArray lists = new JsonArray();
        JsonObject list = new JsonObject();
        list.addProperty("stage", "POST_SCENE");
        list.add("passes", passes);
        lists.add(list);
        ir.add("commandLists", lists);
        ir.add("combination", combinationPass);
        return ir;
    }

    /** A {@code location} input: a PNG from {@code textures/effect/}, loaded once. */
    private String textureInput(JsonObject in, Map<String, JsonObject> defs) {
        Identifier location = Identifier.parse(string(in, "location", null));
        String file = "assets/" + location.namespace + "/textures/effect/" + location.path + ".png";
        if (!src.exists(file)) throw new IllegalArgumentException("missing texture: " + file);
        String ir = "pfx_file_" + file.hashCode() + "_t";
        ir = ir.replace('-', 'n');
        if (!defs.containsKey(ir)) {
            JsonObject tex = texture(ir, "RGBA8");
            tex.addProperty("file", file);
            JsonArray size = new JsonArray();
            size.add(in.get("width"));
            size.add(in.get("height"));
            tex.add("size", size);
            defs.put(ir, tex);
        }
        return ir;
    }

    private static JsonObject texture(String name, String format) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("format", format);
        return t;
    }

    private static JsonArray names(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    /** Stores a generated program; passes with the same constants share it. */
    private String addProgram(String shaderId, String extension, String text) {
        String path = pathByText.get(text);
        if (path == null) {
            Identifier s = Identifier.parse(shaderId);
            path = GENERATED_ROOT + s.namespace + "/" + s.path + "_" + programs.size() + "." + extension;
            pathByText.put(text, path);
            programs.put(path, text);
        }
        return path;
    }

    private static String shaderFile(String shaderId, String extension) {
        Identifier s = Identifier.parse(shaderId);
        return "assets/" + s.namespace + "/shaders/" + s.path + "." + extension;
    }

    private String readShader(String shaderId, String extension) throws IOException {
        String file = shaderFile(shaderId, extension);
        if (src.exists(file)) return expandImports(file, src.read(file), new HashSet<>());
        String vanilla = VANILLA_SHADERS.get(shaderId);
        if (vanilla == null || !extension.equals("fsh")) {
            throw new IllegalArgumentException("missing shader: " + file);
        }
        return vanilla;
    }

    private String expandImports(String file, String text, Set<String> seen) throws IOException {
        Matcher m = IMPORT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String requested = m.group(2), path, vanilla = null;
            if (m.group(1).equals("\"")) {
                path = PackSource.normalize(file.substring(0, file.lastIndexOf('/') + 1) + requested);
            } else {
                Identifier include = Identifier.parse(requested);
                path = "assets/" + include.namespace + "/shaders/include/" + include.path;
                vanilla = VANILLA_INCLUDES.get(include.namespace + ":" + include.path);
            }
            String replacement = "";
            if (seen.add(path)) {
                String included;
                if (src.exists(path)) {
                    included = expandImports(path, src.read(path).replaceFirst("(?m)^[ \\t]*#[ \\t]*version\\b.*$", ""), seen);
                } else if (vanilla != null) {
                    included = vanilla;
                } else {
                    throw new IllegalArgumentException(file + ": missing import " + path);
                }
                int line = (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 2;
                replacement = "#line 1 \"" + path + "\"\n" + included + "\n#line " + line + " \"" + file + "\"";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        return m.appendTail(out).toString();
    }

    private JsonObject parseJson(String file) throws IOException {
        try {
            return JsonParser.parseString(src.read(file)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException(file + " is not a JSON object: " + e.getMessage());
        }
    }

    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }

    /** {@code namespace:path}; the namespace defaults to minecraft, as in the game. */
    private record Identifier(String namespace, String path) {
        static Identifier parse(String id) {
            int colon = id.indexOf(':');
            return colon < 0 ? new Identifier("minecraft", id)
                    : new Identifier(id.substring(0, colon), id.substring(colon + 1));
        }
    }
}
