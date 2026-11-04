package net.vesu.pack.ir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A parsed native pack, format v1 (docs/shaderpacks/pack-format.md): the pack's offscreen
 * textures, the passes of its command lists and the combination pass that produces the
 * final world image. Every frontend lowers into this, and the runtime only executes it.
 * Programs are Vulkan GLSL 450; the host injects the declarations of every resource a
 * program uses (host-interface.md), so programs never declare bindings themselves.
 *
 * <p>Sections consumed by later milestones (options, uniforms, shadow, requires,
 * materials) are kept as raw JSON in {@link #raw} until their parsers land.</p>
 */
public final class IrPack {

    /** The format this parser reads; newer packs are rejected with a message. */
    public static final int FORMAT_VERSION = 1;

    /** Host texture names (host-interface.md, "Samplers"); a pack must not declare them. */
    public static final Set<String> HOST_TEXTURES = Set.of(
            "sceneColor", "sceneDepth", "motion",
            "gtexture", "texture", "tex", "lightmap", "normals", "specular", "actorNormals", "actorSpecular",
            "gnormalCopy",
            "depthtex0", "depthtex1", "depthtex2",
            "shadowtex0", "shadowtex1", "shadowtex0Hw", "shadowtex1Hw", "shadowcolor0", "shadowcolor1", "shadowcolor2",
            "shadowcolor3", "shadowcolor4", "shadowcolor5", "shadowcolor6", "shadowcolor7", "noisetex",
            "dhDepthTex0", "dhDepthTex1");

    /** The color attachments of the shadow pass, in order. */
    public static final List<String> SHADOW_COLORS = List.of("shadowcolor0", "shadowcolor1", "shadowcolor2", "shadowcolor3",
            "shadowcolor4", "shadowcolor5", "shadowcolor6", "shadowcolor7");

    /** Prefix reserved for host names. */
    public static final String HOST_PREFIX = "vkm";

    /** Scenes a pack can replace its passes for; everything else is the base pack. */
    public static final List<String> SCENES = List.of("underwater", "nether", "end");

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Command-list stages in frame order (pack-format.md, "Stages"). */
    public enum Stage { SETUP, BEGIN, SHADOW_COMP, PREPARE, DEFERRED, POST_SCENE }

    public final int format;
    public final String id;
    public final String name;
    public final String version;
    /** Raw "renderer" block (sun, clouds, jitter, upscaler, settings, ...). */
    public final JsonObject renderer;
    public final List<TextureDef> textures;
    /** Passes per command-list stage, in pack order; stages without passes are absent. */
    public final Map<Stage, List<Pass>> commandLists;
    /** Required final pass: writes the world image into the host scene color. */
    public final Pass combination;
    /** Geometry programs by program name (runtime.md, "Draw categories"). Empty for post-only packs. */
    public final Map<String, Gbuffer> gbuffers;
    /** {@code requires.vertex}: extended chunk attributes, by name (runtime.md, "Vertex data"). */
    public final List<String> requiredVertex;
    /** The shadow map, or null for a pack without one. */
    public final Shadow shadow;
    /** Whole-pack variants by {@link #SCENES} name; the runtime picks one per frame. Empty for most packs. */
    public final Map<String, IrPack> scenes;
    /** The whole pack.json, for sections parsed by later milestones. */
    public final JsonObject raw;

    private IrPack(int format, String id, String name, String version, JsonObject renderer,
                   List<TextureDef> textures, Map<Stage, List<Pass>> commandLists, Pass combination,
                   Map<String, Gbuffer> gbuffers, List<String> requiredVertex, Shadow shadow, Map<String, IrPack> scenes,
                   JsonObject raw) {
        this.format = format;
        this.id = id;
        this.name = name;
        this.version = version;
        this.renderer = renderer;
        this.textures = textures;
        this.commandLists = commandLists;
        this.combination = combination;
        this.gbuffers = gbuffers;
        this.requiredVertex = requiredVertex;
        this.shadow = shadow;
        this.scenes = scenes;
        this.raw = raw;
    }

    /** Passes of one stage; empty when the pack has none. */
    public List<Pass> passes(Stage stage) {
        List<Pass> p = commandLists.get(stage);
        return p == null ? List.of() : p;
    }

    /** This pack or its variant for a scene name; null means the base pack. */
    public IrPack forScene(String scene) {
        IrPack variant = scene == null ? null : scenes.get(scene);
        return variant == null ? this : variant;
    }

    /** Declared texture by name, or null. */
    public TextureDef texture(String name) {
        for (TextureDef t : textures) {
            if (t.name.equals(name)) return t;
        }
        return null;
    }

    /** One declared offscreen texture (pack-format.md, "Textures"). */
    public static final class TextureDef {
        public final String name;
        public final TextureFormat format;
        /** Relative to the render resolution; ignored when {@link #width} is set. */
        public final float scale;
        /** Fixed size, or 0 for scaled textures. */
        public final int width, height;
        /** Clear color at frame start, or null to keep the contents. */
        public final float[] clearColor;
        /** Clears with the frame's fog color instead of {@link #clearColor}'s rgb. */
        public final boolean clearFog;
        public final boolean mipmaps;
        /** Kept across frames, readable as {@code <name>Prev}. */
        public final boolean history;
        public final boolean linearFilter;
        /** GL_REPEAT outside [0, 1], otherwise clamped. */
        public final boolean repeat;
        /** PNG in the pack this texture is loaded from, or null for a render texture. */
        public final String file;

        TextureDef(String name, TextureFormat format, float scale, int width, int height, float[] clearColor,
                   boolean clearFog, boolean mipmaps, boolean history, boolean linearFilter, boolean repeat, String file) {
            this.name = name;
            this.format = format;
            this.scale = scale;
            this.width = width;
            this.height = height;
            this.clearColor = clearColor;
            this.clearFog = clearFog;
            this.mipmaps = mipmaps;
            this.history = history;
            this.linearFilter = linearFilter;
            this.repeat = repeat;
            this.file = file;
        }
    }

    /** Blend of a fragment pass: off, the default alpha blend, or explicit factors. */
    public static final class Blend {
        public static final Blend OFF = new Blend(false, null);
        public static final Blend DEFAULT = new Blend(true,
                new String[]{"SRC_ALPHA", "ONE_MINUS_SRC_ALPHA", "ONE", "ONE_MINUS_SRC_ALPHA"});

        public final boolean enabled;
        /** srcRGB, dstRGB, srcAlpha, dstAlpha as Vulkan factor names without the prefix. */
        public final String[] factors;

        Blend(boolean enabled, String[] factors) {
            this.enabled = enabled;
            this.factors = factors;
        }
    }

    /**
     * One pass of a command list, or the combination pass. Exactly one of
     * {@code fragment} (full-screen triangle) and {@code compute} is set.
     */
    public static final class Pass {
        public final String name;
        public final String vertex;
        public final String fragment;
        public final String compute;
        /** Textures sampled: declared, history ({@code <name>Prev}) or host textures. */
        public final List<String> inputs;
        /** Storage images a pass writes: all a compute pass writes, or what a fragment pass stores beside its targets. */
        public final List<String> writes;
        /** Color attachments of a fragment pass, in output location order. */
        public final List<String> targets;
        public final Blend blend;
        /** {@code blend.<program>.<buffer>}: the blend of single targets, over {@link #blend}. */
        public final Map<String, Blend> targetBlend;
        /** {@code flip.<program>.<buffer>}: forces the swap of a texture's images after the pass, or forbids it. */
        public final Map<String, Boolean> flip;
        /** Viewport scale, or null for the first target's scale. */
        public final Float scale;
        /** Option expression, evaluated at load; null = always. */
        public final String enabled;
        /** Textures whose mips are regenerated after this pass. */
        public final List<String> mipmaps;
        /** Inputs whose mips are regenerated right before this pass. */
        public final List<String> mipmapsBefore;
        /** Per-input filter override, true for linear; other inputs use the texture's filter. */
        public final Map<String, Boolean> linearInputs;
        /** Compute dispatch: work-group count scale of the render size and local size, or fixed counts. */
        public final float workGroupScale;
        public final int[] localSize;
        public final int[] fixedGroups;
        /** Buffer binding and byte offset of the group counts, or null. */
        public int[] indirect;
        /** Optional stages of a fragment pass; the tessellation pair comes together. */
        public String geometry, tessControl, tessEval;
        /** Inputs read at level 0 only, even if another pass made mips of them. */
        public List<String> baseLevelInputs = List.of();

        Pass(String name, String vertex, String fragment, String compute, List<String> inputs,
             List<String> writes, List<String> targets, Blend blend, Map<String, Blend> targetBlend, Map<String, Boolean> flip,
             Float scale, String enabled, List<String> mipmaps, List<String> mipmapsBefore, Map<String, Boolean> linearInputs,
             float workGroupScale, int[] localSize,
             int[] fixedGroups) {
            this.name = name;
            this.vertex = vertex;
            this.fragment = fragment;
            this.compute = compute;
            this.inputs = inputs;
            this.writes = writes;
            this.targets = targets;
            this.blend = blend;
            this.targetBlend = targetBlend;
            this.flip = flip;
            this.scale = scale;
            this.enabled = enabled;
            this.mipmaps = mipmaps;
            this.mipmapsBefore = mipmapsBefore;
            this.linearInputs = linearInputs;
            this.workGroupScale = workGroupScale;
            this.localSize = localSize;
            this.fixedGroups = fixedGroups;
        }

        public boolean isCompute() {
            return compute != null;
        }

        /** A texture both sampled and written: the schedule gives it a main and an alt image. */
        public boolean readsWhileWriting(String texture) {
            return inputs.contains(texture) && (targets.contains(texture) || writes.contains(texture));
        }
    }

    /**
     * The shadow map (pack-format.md, "Shadow"). Values default to OptiFine's. The program is the
     * geometry program {@code shadow}; its targets are {@code shadowcolor0} and {@code shadowcolor1}.
     */
    public static final class Shadow {
        public final int resolution;
        /** Half the side of the light's orthographic box, in blocks. */
        public final float distance;
        /** Camera movement in the light's frame snaps to this many blocks. */
        public final float intervalSize;
        /** Degrees; the sun's path tilts around the world z axis. */
        public final float sunPathRotation;
        /** Above 0: a perspective shadow map of this field of view, degrees. */
        public final float fov;
        /** The sun's angle in the light's frame snaps to this many degrees; 0 follows it exactly. */
        public final float angleInterval;
        public final boolean terrain, translucent, entities, blockEntities;
        /** False: every built section casts, for packs that voxelize the world in the shadow pass. */
        public final boolean culling;
        /** {@code shadowtex0} and {@code shadowtex1} are comparison samplers. */
        public final boolean hardwareFiltering;
        public final boolean nearest;
        public final TextureFormat[] colorFormats;
        public final boolean[] colorMipmaps;

        Shadow(int resolution, float distance, float intervalSize, float sunPathRotation, float fov, float angleInterval,
               boolean terrain, boolean translucent, boolean entities, boolean blockEntities, boolean culling,
               boolean hardwareFiltering, boolean nearest, TextureFormat[] colorFormats, boolean[] colorMipmaps) {
            this.resolution = resolution;
            this.distance = distance;
            this.intervalSize = intervalSize;
            this.sunPathRotation = sunPathRotation;
            this.fov = fov;
            this.angleInterval = angleInterval;
            this.terrain = terrain;
            this.culling = culling;
            this.translucent = translucent;
            this.entities = entities;
            this.blockEntities = blockEntities;
            this.hardwareFiltering = hardwareFiltering;
            this.nearest = nearest;
            this.colorFormats = colorFormats;
            this.colorMipmaps = colorMipmaps;
        }
    }

    /** One geometry program: the draws of the categories routed to it run through it. */
    public static final class Gbuffer {
        public final String name;
        public final String vertex;
        public final String fragment;
        /** Optional stages; the tessellation pair comes together. */
        public final String geometry, tessControl, tessEval;
        /** Render textures the program stores to, beside its targets. */
        public List<String> writes = List.of();
        /** Fragment outputs: declared textures or {@code sceneColor}. Named after the target in the program. */
        public final List<String> targets;
        /** Textures sampled; none of them may be a geometry target of the pack. */
        public final List<String> inputs;
        /** Replaces the blend vanilla set for the draw, or null to keep it. */
        public final Blend blend;
        /** {@code blend.<program>.<buffer>}: the blend of single targets, over {@link #blend}. */
        public final Map<String, Blend> targetBlend;

        Gbuffer(String name, String vertex, String fragment, String geometry, String tessControl, String tessEval,
                List<String> targets, List<String> inputs, Blend blend, Map<String, Blend> targetBlend) {
            this.name = name;
            this.vertex = vertex;
            this.fragment = fragment;
            this.geometry = geometry;
            this.tessControl = tessControl;
            this.tessEval = tessEval;
            this.targets = targets;
            this.inputs = inputs;
            this.blend = blend;
            this.targetBlend = targetBlend;
        }
    }

    /**
     * Parses {@code pack.json}. Throws IllegalArgumentException with a readable message for
     * any malformed field; the pack manager shows it as the pack's load error.
     */
    public static IrPack parse(String id, JsonObject root) {
        if (!root.has("format")) {
            throw new IllegalArgumentException("pack.json has no \"format\" field (not a native pack)");
        }
        int format = intValue(root.get("format"), "format");
        if (format < 1) {
            throw new IllegalArgumentException("invalid pack format: " + format);
        }
        if (format > FORMAT_VERSION) {
            throw new IllegalArgumentException("pack format " + format + " is newer than the supported "
                    + FORMAT_VERSION + "; update Vesu");
        }
        String name = root.has("name") ? root.get("name").getAsString() : id;
        String version = root.has("version") ? root.get("version").getAsString() : "";
        JsonObject renderer = root.has("renderer") && root.get("renderer").isJsonObject()
                ? root.getAsJsonObject("renderer") : new JsonObject();

        List<TextureDef> textures = new ArrayList<>();
        if (root.has("textures")) {
            for (JsonElement e : array(root, "textures")) {
                TextureDef def = parseTexture(object(e, "texture"));
                if (find(textures, def.name) != null) {
                    throw new IllegalArgumentException("duplicate texture: " + def.name);
                }
                textures.add(def);
            }
        }

        Map<Stage, List<Pass>> lists = new EnumMap<>(Stage.class);
        if (root.has("commandLists")) {
            for (JsonElement e : array(root, "commandLists")) {
                JsonObject list = object(e, "command list");
                String stageName = list.has("stage") ? list.get("stage").getAsString() : "POST_SCENE";
                Stage stage;
                try {
                    stage = Stage.valueOf(stageName);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("unknown command list stage: " + stageName);
                }
                if (!list.has("passes")) {
                    continue;
                }
                List<Pass> passes = lists.computeIfAbsent(stage, s -> new ArrayList<>());
                for (JsonElement p : array(list, "passes")) {
                    passes.add(parsePass(object(p, "pass"), textures));
                }
            }
        }
        lists.replaceAll((s, l) -> Collections.unmodifiableList(l));

        Pass combination = root.has("combination")
                ? parsePass(object(root.get("combination"), "combination"), textures) : null;
        if (combination != null && (combination.isCompute() || !combination.targets.isEmpty())) {
            throw new IllegalArgumentException("the combination pass must be a fragment pass without targets");
        }

        Map<String, Gbuffer> gbuffers = new java.util.LinkedHashMap<>();
        if (root.has("gbuffers")) {
            for (Map.Entry<String, JsonElement> e : object(root.get("gbuffers"), "gbuffers").entrySet()) {
                gbuffers.put(e.getKey(), parseGbuffer(e.getKey(), object(e.getValue(), "gbuffer"), textures));
            }
        }

        Shadow shadow = root.has("shadow") ? parseShadow(object(root.get("shadow"), "shadow")) : null;
        if (gbuffers.containsKey("shadow") && shadow == null) {
            throw new IllegalArgumentException("the geometry program shadow needs a \"shadow\" section");
        }

        Map<String, IrPack> scenes = new java.util.HashMap<>();
        if (root.has("scenes")) {
            for (Map.Entry<String, JsonElement> e : object(root.get("scenes"), "scenes").entrySet()) {
                if (!SCENES.contains(e.getKey())) {
                    throw new IllegalArgumentException("unknown scene: " + e.getKey());
                }
                JsonObject variant = object(e.getValue(), "scene").deepCopy();
                variant.addProperty("format", format);
                scenes.put(e.getKey(), parse(id, variant));
            }
        }

        return new IrPack(format, id, name, version, renderer, Collections.unmodifiableList(textures),
                Collections.unmodifiableMap(lists), combination, Collections.unmodifiableMap(gbuffers),
                root.has("requires") ? readNames(object(root.get("requires"), "requires"), "vertex") : List.of(),
                shadow, Collections.unmodifiableMap(scenes), root);
    }

    private static Shadow parseShadow(JsonObject s) {
        int resolution = s.has("resolution") ? intValue(s.get("resolution"), "shadow.resolution") : 1024;
        if (resolution < 16 || resolution > 16384) {
            throw new IllegalArgumentException("shadow.resolution out of range: " + resolution);
        }
        int colors = SHADOW_COLORS.size();
        TextureFormat[] formats = new TextureFormat[colors];
        Arrays.fill(formats, TextureFormat.RGBA8);
        if (s.has("colorFormats")) {
            JsonArray a = array(s, "colorFormats");
            if (a.size() > colors) throw new IllegalArgumentException("shadow.colorFormats has more than " + colors + " formats");
            for (int i = 0; i < a.size(); i++) {
                formats[i] = TextureFormat.parse(a.get(i).getAsString());
                if (formats[i] == null) throw new IllegalArgumentException("unknown shadow format " + a.get(i));
            }
        }
        boolean[] mips = new boolean[colors];
        if (s.has("colorMipmaps")) {
            JsonArray a = array(s, "colorMipmaps");
            if (a.size() > colors) throw new IllegalArgumentException("shadow.colorMipmaps has more than " + colors + " values");
            for (int i = 0; i < a.size(); i++) mips[i] = a.get(i).getAsBoolean();
        }
        return new Shadow(resolution, floatOr(s, "distance", 160.0f), floatOr(s, "intervalSize", 2.0f),
                floatOr(s, "sunPathRotation", 0.0f), floatOr(s, "fov", 0.0f), floatOr(s, "angleInterval", 0.0f),
                boolOr(s, "terrain", true), boolOr(s, "translucent", true), boolOr(s, "entities", true),
                boolOr(s, "blockEntities", true), boolOr(s, "culling", true), boolOr(s, "hardwareFiltering", false), boolOr(s, "nearest", false),
                formats, mips);
    }

    private static float floatOr(JsonObject o, String key, float value) {
        return o.has(key) ? o.get(key).getAsFloat() : value;
    }

    private static boolean boolOr(JsonObject o, String key, boolean value) {
        return o.has(key) ? o.get(key).getAsBoolean() : value;
    }

    private static Gbuffer parseGbuffer(String name, JsonObject g, List<TextureDef> textures) {
        if (!net.vesu.pack.plan.GeometryCategory.PROGRAMS.contains(name)) {
            throw new IllegalArgumentException("unknown geometry program: " + name);
        }
        if (!g.has("vertex") || !g.has("fragment")) {
            throw new IllegalArgumentException("geometry program " + name + " needs vertex and fragment");
        }
        List<String> targets = readNames(g, "targets");
        boolean shadow = name.equals("shadow");
        for (String t : targets) {
            if (shadow) {
                if (!SHADOW_COLORS.contains(t)) {
                    throw new IllegalArgumentException("the shadow program can only target shadowcolor0 to shadowcolor7, not " + t);
                }
            } else if (!t.equals("sceneColor") && find(textures, t) == null) {
                throw new IllegalArgumentException("geometry program " + name + " targets undeclared texture: " + t);
            }
        }
        List<String> inputs = readNames(g, "inputs");
        for (String in : inputs) {
            if (!isReadable(in, textures)) {
                throw new IllegalArgumentException("geometry program " + name + " samples undeclared texture: " + in);
            }
        }
        if (g.has("tessControl") != g.has("tessEval")) {
            throw new IllegalArgumentException("geometry program " + name + " needs both tessControl and tessEval");
        }
        Gbuffer program = new Gbuffer(name, g.get("vertex").getAsString(), g.get("fragment").getAsString(), optionalString(g, "geometry"),
                optionalString(g, "tessControl"), optionalString(g, "tessEval"), targets, inputs,
                g.has("blend") ? parseBlend(g, name) : null, parseTargetBlend(g, name));
        program.writes = readNames(g, "writes");
        for (String w : program.writes) {
            if (find(textures, w) == null) throw new IllegalArgumentException("geometry program " + name + " stores to undeclared texture: " + w);
        }
        return program;
    }

    private static String optionalString(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : null;
    }

    private static TextureDef parseTexture(JsonObject t) {
        if (!t.has("name")) {
            throw new IllegalArgumentException("texture needs a name");
        }
        String name = t.get("name").getAsString();
        checkPackName(name, "texture");
        String formatName = t.has("format") ? t.get("format").getAsString() : "RGBA16F";
        TextureFormat format = TextureFormat.parse(formatName);
        if (format == null) {
            throw new IllegalArgumentException("unknown texture format " + formatName + ": " + name);
        }
        float scale = t.has("scale") ? t.get("scale").getAsFloat() : 1.0f;
        if (!(scale > 0.0f && scale <= 1.0f)) {
            throw new IllegalArgumentException("texture scale out of (0, 1]: " + name);
        }
        int width = 0, height = 0;
        if (t.has("size")) {
            JsonArray size = array(t, "size");
            if (size.size() != 2) {
                throw new IllegalArgumentException("texture size must be [width, height]: " + name);
            }
            width = intValue(size.get(0), name + " width");
            height = intValue(size.get(1), name + " height");
            if (width < 1 || height < 1 || width > 16384 || height > 16384) {
                throw new IllegalArgumentException("texture size out of range: " + name);
            }
        }
        float[] clear = new float[4];
        boolean clearFog = false;
        if (t.has("clear")) {
            JsonElement c = t.get("clear");
            if (c.isJsonPrimitive() && c.getAsJsonPrimitive().isString()) {
                if (!c.getAsString().equals("fog")) {
                    throw new IllegalArgumentException("texture clear must be false, \"fog\" or [r, g, b, a]: " + name);
                }
                clearFog = true;
                clear[3] = 1;
            } else if (c.isJsonPrimitive() && c.getAsJsonPrimitive().isBoolean()) {
                clear = c.getAsBoolean() ? clear : null;
            } else {
                JsonArray arr = c.getAsJsonArray();
                if (arr.size() != 4) {
                    throw new IllegalArgumentException("texture clear must be false or [r, g, b, a]: " + name);
                }
                for (int i = 0; i < 4; i++) clear[i] = arr.get(i).getAsFloat();
            }
        }
        boolean mipmaps = t.has("mipmaps") && t.get("mipmaps").getAsBoolean();
        boolean history = t.has("history") && t.get("history").getAsBoolean();
        if (history) {
            // History survives the frame; clearing it would defeat the point.
            clear = null;
        }
        String filter = t.has("filter") ? t.get("filter").getAsString() : "linear";
        if (!filter.equals("linear") && !filter.equals("nearest")) {
            throw new IllegalArgumentException("texture filter must be linear or nearest: " + name);
        }
        String file = t.has("file") ? t.get("file").getAsString() : null;
        if (file != null) {
            if (format != TextureFormat.RGBA8 || width == 0 || mipmaps || history) {
                throw new IllegalArgumentException("a file texture must be RGBA8 with a size, no mipmaps or history: " + name);
            }
            clear = null;
        }
        return new TextureDef(name, format, scale, width, height, clear, clearFog, mipmaps, history, filter.equals("linear"),
                t.has("wrap") && t.get("wrap").getAsString().equals("repeat"), file);
    }

    private static Pass parsePass(JsonObject p, List<TextureDef> textures) {
        String name = p.has("name") ? p.get("name").getAsString() : null;
        String fragment = p.has("fragment") ? p.get("fragment").getAsString() : null;
        String compute = p.has("compute") ? p.get("compute").getAsString() : null;
        if (fragment == null && compute == null) {
            throw new IllegalArgumentException("pass needs a fragment or compute program"
                    + (name == null ? "" : ": " + name));
        }
        if (fragment != null && compute != null) {
            throw new IllegalArgumentException("pass cannot have both fragment and compute: " + name);
        }
        if (name == null) {
            name = fragment != null ? fragment : compute;
        }

        List<String> inputs = readNames(p, "inputs");
        List<String> writes = readNames(p, "writes");
        List<String> targets = readNames(p, "targets");
        List<String> mipmaps = readNames(p, "mipmaps");
        List<String> mipmapsBefore = readNames(p, "mipmapsBefore");

        for (String in : inputs) {
            if (!isReadable(in, textures)) {
                throw new IllegalArgumentException("pass " + name + " samples undeclared texture: " + in);
            }
        }
        for (String w : writes) {
            if (find(textures, w) == null) {
                throw new IllegalArgumentException("pass " + name + " writes "
                        + (isHost(w) ? "a host texture: " : "undeclared texture: ") + w);
            }
        }
        for (String t : targets) {
            if (find(textures, t) == null) {
                throw new IllegalArgumentException("pass " + name + " targets "
                        + (isHost(t) ? "a host texture: " : "undeclared texture: ") + t);
            }
        }
        for (String m : mipmaps) {
            TextureDef def = find(textures, m);
            if (def == null || !def.mipmaps) {
                throw new IllegalArgumentException("pass " + name + " regenerates mips of a texture without mipmaps: " + m);
            }
        }
        for (String m : mipmapsBefore) {
            TextureDef def = find(textures, m);
            if (def == null || !def.mipmaps || !inputs.contains(m)) {
                throw new IllegalArgumentException("pass " + name + " needs mips of something it does not read with mips: " + m);
            }
        }
        if (compute != null && !targets.isEmpty()) {
            throw new IllegalArgumentException("compute pass " + name + " has targets; use writes");
        }
        if (p.has("tessControl") != p.has("tessEval")) {
            throw new IllegalArgumentException("pass " + name + " needs both tessControl and tessEval");
        }
        if ((p.has("geometry") || p.has("tessControl")) && !p.has("vertex")) {
            throw new IllegalArgumentException("pass " + name + " needs its own vertex program for its other stages");
        }

        Float scale = null;
        if (p.has("scale")) {
            float s = p.get("scale").getAsFloat();
            if (!(s > 0.0f && s <= 1.0f)) {
                throw new IllegalArgumentException("pass scale out of (0, 1]: " + name);
            }
            scale = s;
        }
        String enabled = p.has("enabled") ? p.get("enabled").getAsString() : null;

        float groupScale = 1.0f;
        int[] local = {8, 8, 1};
        int[] fixed = null;
        if (p.has("workGroups")) {
            if (compute == null) {
                throw new IllegalArgumentException("workGroups on a fragment pass: " + name);
            }
            JsonObject wg = object(p.get("workGroups"), "workGroups");
            if (wg.has("local")) local = ints(array(wg, "local"), 3, name + " workGroups.local");
            if (wg.has("fixed")) {
                fixed = ints(array(wg, "fixed"), 3, name + " workGroups.fixed");
            } else {
                if (wg.has("scale")) groupScale = wg.get("scale").getAsFloat();
                if (!(groupScale > 0.0f && groupScale <= 1.0f)) {
                    throw new IllegalArgumentException("workGroups scale out of (0, 1]: " + name);
                }
            }
        }

        int[] indirect = null;
        if (p.has("indirect")) {
            JsonArray a = array(p, "indirect");
            if (a.size() != 2) throw new IllegalArgumentException(name + " indirect needs a binding and an offset");
            indirect = new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()};
        }
        Pass pass = new Pass(name, p.has("vertex") ? p.get("vertex").getAsString() : null, fragment, compute,
                inputs, writes, targets, parseBlend(p, name), parseTargetBlend(p, name), parseFlip(p), scale, enabled, mipmaps, mipmapsBefore, parseFilters(p, inputs, name),
                groupScale, local, fixed);
        pass.indirect = indirect;
        pass.baseLevelInputs = readNames(p, "baseLevelInputs");
        pass.geometry = optionalString(p, "geometry");
        pass.tessControl = optionalString(p, "tessControl");
        pass.tessEval = optionalString(p, "tessEval");
        return pass;
    }

    private static Map<String, Boolean> parseFlip(JsonObject p) {
        if (!p.has("flip")) return Map.of();
        Map<String, Boolean> out = new java.util.HashMap<>();
        p.getAsJsonObject("flip").entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsBoolean()));
        return out;
    }

    private static Map<String, Boolean> parseFilters(JsonObject p, List<String> inputs, String pass) {
        if (!p.has("filters")) return Map.of();
        Map<String, Boolean> out = new java.util.HashMap<>();
        for (Map.Entry<String, JsonElement> e : object(p.get("filters"), "filters").entrySet()) {
            String filter = e.getValue().getAsString();
            if (!inputs.contains(e.getKey()) || !(filter.equals("linear") || filter.equals("nearest"))) {
                throw new IllegalArgumentException("pass " + pass + ": bad filter " + filter + " for " + e.getKey());
            }
            out.put(e.getKey(), filter.equals("linear"));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Blend> parseTargetBlend(JsonObject p, String pass) {
        if (!p.has("targetBlend")) return Map.of();
        Map<String, Blend> blends = new java.util.HashMap<>();
        for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("targetBlend").entrySet()) {
            JsonObject one = new JsonObject();
            one.add("blend", e.getValue());
            blends.put(e.getKey(), parseBlend(one, pass));
        }
        return blends;
    }

    private static Blend parseBlend(JsonObject p, String pass) {
        if (!p.has("blend")) return Blend.OFF;
        JsonElement b = p.get("blend");
        if (b.isJsonPrimitive()) {
            JsonPrimitive prim = b.getAsJsonPrimitive();
            if (prim.isBoolean()) return prim.getAsBoolean() ? Blend.DEFAULT : Blend.OFF;
            if (prim.getAsString().equals("default")) return Blend.DEFAULT;
        } else if (b.isJsonArray() && b.getAsJsonArray().size() == 4) {
            String[] f = new String[4];
            for (int i = 0; i < 4; i++) {
                f[i] = b.getAsJsonArray().get(i).getAsString();
                if (!BLEND_FACTORS.contains(f[i])) {
                    throw new IllegalArgumentException("unknown blend factor " + f[i] + " in pass " + pass);
                }
            }
            return new Blend(true, f);
        }
        throw new IllegalArgumentException("blend must be false, \"default\" or four factors: " + pass);
    }

    private static final Set<String> BLEND_FACTORS = Set.of("ZERO", "ONE", "SRC_COLOR", "ONE_MINUS_SRC_COLOR",
            "DST_COLOR", "ONE_MINUS_DST_COLOR", "SRC_ALPHA", "ONE_MINUS_SRC_ALPHA", "DST_ALPHA",
            "ONE_MINUS_DST_ALPHA", "SRC_ALPHA_SATURATE");

    private static boolean isReadable(String name, List<TextureDef> textures) {
        if (isHost(name) || find(textures, name) != null) return true;
        if (name.endsWith("Prev")) {
            TextureDef def = find(textures, name.substring(0, name.length() - 4));
            return def != null && def.history;
        }
        return false;
    }

    private static boolean isHost(String name) {
        return HOST_TEXTURES.contains(name) || name.startsWith(HOST_PREFIX);
    }

    private static void checkPackName(String name, String what) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid " + what + " name: " + name);
        }
        if (isHost(name)) {
            throw new IllegalArgumentException("reserved " + what + " name: " + name);
        }
        if (name.endsWith("Prev")) {
            throw new IllegalArgumentException(what + " names ending in Prev are reserved for history: " + name);
        }
    }

    private static List<String> readNames(JsonObject p, String key) {
        if (!p.has(key)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonElement e : array(p, key)) {
            names.add(e.getAsString());
        }
        return Collections.unmodifiableList(names);
    }

    private static TextureDef find(List<TextureDef> textures, String name) {
        for (TextureDef t : textures) {
            if (t.name.equals(name)) {
                return t;
            }
        }
        return null;
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        return e.getAsJsonArray();
    }

    private static JsonObject object(JsonElement e, String what) {
        if (e == null || !e.isJsonObject()) {
            throw new IllegalArgumentException(what + " must be an object");
        }
        return e.getAsJsonObject();
    }

    private static int intValue(JsonElement e, String what) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(what + " must be a number");
        }
        double d = e.getAsDouble();
        if (d != Math.rint(d)) {
            throw new IllegalArgumentException(what + " must be an integer");
        }
        return (int) d;
    }

    private static int[] ints(JsonArray arr, int max, String what) {
        if (arr.isEmpty() || arr.size() > max) {
            throw new IllegalArgumentException(what + " needs 1.." + max + " values");
        }
        int[] out = {1, 1, 1};
        for (int i = 0; i < arr.size(); i++) {
            out[i] = intValue(arr.get(i), what);
            if (out[i] < 1) {
                throw new IllegalArgumentException(what + " values must be >= 1");
            }
        }
        return out;
    }
}
