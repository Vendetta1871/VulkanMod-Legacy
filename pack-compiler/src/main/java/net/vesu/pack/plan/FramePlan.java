package net.vesu.pack.plan;

import net.vesu.pack.ir.IrPack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The static schedule of a pack's command-list passes (D6): which image of each texture a
 * pass reads and writes, resolved once at load. Nothing here is decided per frame.
 *
 * <p>Read-while-write ("ping-pong", OptiFine's flip): a pass that samples a texture it also
 * writes reads the texture's current image and writes the other one, which then becomes
 * current. Such textures get two images; all others get one. Every frame starts with image
 * 0 current, so the alternation is the same each frame. A history texture also has a
 * {@link #PREV} image holding the previous frame's final image, readable as
 * {@code <name>Prev}; the runtime copies the final image into it at the end of the passes.</p>
 */
public final class FramePlan {

    /** Image index of a history texture's previous-frame copy. */
    public static final int PREV = 2;
    /** Image index of a geometry target's copy from before the translucent layer, which geometry programs sample. */
    public static final int SNAPSHOT = 3;

    /** Stages the runtime executes at present; passes of the others are skipped with a warning. */
    public static final Set<IrPack.Stage> SUPPORTED = Set.of(IrPack.Stage.SETUP, IrPack.Stage.BEGIN, IrPack.Stage.SHADOW_COMP, IrPack.Stage.PREPARE,
            IrPack.Stage.DEFERRED, IrPack.Stage.POST_SCENE);

    /** Extended chunk attributes (runtime.md, "Vertex data"), in stream order. */
    public static final int VERTEX_NORMAL = 1, VERTEX_TANGENT = 2, VERTEX_MID_TEX = 4, VERTEX_BLOCK = 8;
    /** Chunk colors without the baked face shade, and with AO in alpha instead of rgb. */
    public static final int VERTEX_PLAIN_SHADE = 16, VERTEX_SEPARATE_AO = 32;
    /** Offset to the block's center in 1/64 of a block, and the block's light in w. */
    public static final int VERTEX_MID_BLOCK = 64;

    /** Host textures a post pass can sample. */
    public static final Set<String> POST_HOST_INPUTS = Set.of("sceneColor", "sceneDepth", "motion",
            "depthtex0", "depthtex1", "depthtex2", "shadowtex0", "shadowtex1", "shadowtex0Hw", "shadowtex1Hw", "shadowcolor0", "shadowcolor1", "shadowcolor2", "shadowcolor3", "shadowcolor4", "shadowcolor5", "shadowcolor6", "shadowcolor7",
            "normals", "specular", "dhDepthTex0", "dhDepthTex1");
    /** The shadow map exists before any geometry; the shadow pass itself cannot read it. */
    private static final Set<String> SHADOW_INPUTS = Set.of("shadowtex0", "shadowtex1", "shadowtex0Hw", "shadowtex1Hw",
            "shadowcolor0", "shadowcolor1", "shadowcolor2", "shadowcolor3", "shadowcolor4", "shadowcolor5", "shadowcolor6", "shadowcolor7");
    private static final Set<String> GEOMETRY_HOST_INPUTS = Set.of("depthtex1", "depthtex2", "normals", "specular", "actorNormals", "actorSpecular",
            "gnormalCopy", "dhDepthTex0", "dhDepthTex1");
    /** Before and after {@code DEFERRED} in {@link #geometryImages} and {@link #geometryInputs}. */
    public static final int OPAQUE = 0, TRANSLUCENT = 1;

    /** One image of one declared texture. */
    public record ImageRef(int texture, int image) {
    }

    /** What a sampled input name binds: a host texture (by name) or a pack image. */
    public record Input(String name, String host, ImageRef image) {
        public boolean isHost() {
            return host != null;
        }
    }

    /** One scheduled pass. {@code outputs} follow the pass's targets or writes order, {@code storage} its writes order. */
    public record Step(IrPack.Pass pass, IrPack.Stage stage, int index, boolean combination,
                       List<Input> inputs, List<ImageRef> outputs, List<Integer> mipmaps,
                       List<ImageRef> mipmapsBefore, List<ImageRef> storage) {
    }

    public final IrPack pack;
    public final List<Step> steps;
    /** Images per declared texture (1, or 2 when ping-ponged), not counting {@link #PREV}. */
    public final int[] imageCount;
    /** The current image of each texture after the last pass (the one history keeps). */
    public final int[] finalImage;
    /** Host textures any pass samples. */
    public final Set<String> hostInputs;
    /** The combination pass samples the scene color it overwrites: the host keeps a copy. */
    public final boolean combinationReadsScene;
    /** {@code renderer.hdr}: the scene renders to RGBA16F and the combination writes it display-referred. */
    public final boolean hdrScene;
    /** {@code requires.vertex} as {@code VERTEX_*} bits; 0 keeps the plain chunk format. */
    public final int vertexFlags;
    /** The leading entries of {@link #steps} that run before the scene: {@code SETUP}, {@code BEGIN}, {@code PREPARE}. */
    public final int prologueSteps;
    /** The entries after those that run at the {@code DEFERRED} hook. */
    public final int deferredSteps;
    /** Program of each category, absent where the category draws with the vanilla shader. */
    public final Map<GeometryCategory, IrPack.Gbuffer> routes;
    /** Pack textures attached during geometry: attachment {@code i + 1}. Attachment 0 is the scene color. */
    public final List<String> geometryTargets;
    /** Image of each geometry target: [OPAQUE or TRANSLUCENT][index in {@link #geometryTargets}]. */
    public final int[][] geometryImages;
    /** Sampled textures of all geometry programs, in binding order: [OPAQUE or TRANSLUCENT]. */
    public final List<List<Input>> geometryInputs;
    /** Textures any geometry program stores to, in binding order. */
    public final List<String> geometryWrites;
    /** Their images: [OPAQUE or TRANSLUCENT][index in {@link #geometryWrites}]. */
    public final List<List<ImageRef>> geometryStorage;
    /** A program reads depthtex1 or depthtex2: the host copies the depth at the {@code DEFERRED} hook. */
    public final boolean depthCopy;
    /** A program reads depthtex2: the host copies the depth before the hand. */
    public final boolean handDepthCopy;
    /** Textures with a {@link #SNAPSHOT} image. */
    public final List<Integer> snapshots;
    /**
     * {@code renderer.sceneTarget}: a pack texture that takes the place of the scene color during
     * geometry, or null. Vanilla shaders then draw into it, which is OptiFine's colortex0.
     */
    public final String sceneTarget;
    /** The geometry program of the shadow map, null without one. */
    public final IrPack.Gbuffer shadowProgram;
    /** Shadow color targets in attachment order. */
    public final List<String> shadowTargets;
    /** A program reads shadowtex1: the host keeps the depth from before the translucent layer. */
    public final boolean shadowDepthCopy;
    /** Human-readable notes about skipped stages and unsupported fields. */
    public final List<String> warnings;

    private FramePlan(IrPack pack, List<Step> steps, int[] imageCount, int[] finalImage, Set<String> hostInputs,
                      boolean combinationReadsScene, boolean hdrScene, int vertexFlags, int prologueSteps, int deferredSteps,
                      Map<GeometryCategory, IrPack.Gbuffer> routes, List<String> geometryTargets,
                      int[][] geometryImages, List<List<Input>> geometryInputs, List<String> geometryWrites,
                      List<List<ImageRef>> geometryStorage, boolean depthCopy, boolean handDepthCopy, List<Integer> snapshots,
                      String sceneTarget, IrPack.Gbuffer shadowProgram, List<String> shadowTargets, boolean shadowDepthCopy,
                      List<String> warnings) {
        this.pack = pack;
        this.steps = steps;
        this.imageCount = imageCount;
        this.finalImage = finalImage;
        this.hostInputs = hostInputs;
        this.combinationReadsScene = combinationReadsScene;
        this.hdrScene = hdrScene;
        this.vertexFlags = vertexFlags;
        this.prologueSteps = prologueSteps;
        this.deferredSteps = deferredSteps;
        this.routes = routes;
        this.geometryTargets = geometryTargets;
        this.geometryImages = geometryImages;
        this.geometryInputs = geometryInputs;
        this.geometryWrites = geometryWrites;
        this.geometryStorage = geometryStorage;
        this.depthCopy = depthCopy;
        this.handDepthCopy = handDepthCopy;
        this.snapshots = snapshots;
        this.sceneTarget = sceneTarget;
        this.shadowProgram = shadowProgram;
        this.shadowTargets = shadowTargets;
        this.shadowDepthCopy = shadowDepthCopy;
        this.warnings = warnings;
    }

    /** Schedules the supported stages and the combination; throws IllegalArgumentException on a pack the runtime cannot run. */
    public static FramePlan build(IrPack pack) {
        if (pack.combination == null) {
            throw new IllegalArgumentException("pack has no combination pass");
        }
        List<String> warnings = new ArrayList<>();
        List<IrPack.Pass> order = new ArrayList<>();
        List<IrPack.Stage> stages = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (IrPack.Stage stage : IrPack.Stage.values()) {
            List<IrPack.Pass> passes = pack.passes(stage);
            if (passes.isEmpty()) continue;
            if (!SUPPORTED.contains(stage)) {
                warnings.add(stage + " is not supported yet: " + passes.size() + " pass(es) skipped");
                continue;
            }
            for (int i = 0; i < passes.size(); i++) {
                order.add(passes.get(i));
                stages.add(stage);
                indices.add(i);
            }
        }

        int textures = pack.textures.size();
        int[] imageCount = new int[textures];
        int[] cur = new int[textures];
        java.util.Arrays.fill(imageCount, 1);
        Set<String> hostInputs = new LinkedHashSet<>();
        List<Step> steps = new ArrayList<>();

        int deferredSteps = pack.passes(IrPack.Stage.DEFERRED).size();
        int prologueSteps = pack.passes(IrPack.Stage.SETUP).size() + pack.passes(IrPack.Stage.BEGIN).size()
                + pack.passes(IrPack.Stage.SHADOW_COMP).size() + pack.passes(IrPack.Stage.PREPARE).size();
        int[] opaqueCur = null;
        String sceneTarget = pack.renderer.has("sceneTarget") ? pack.renderer.get("sceneTarget").getAsString() : null;
        List<String> geometryTargets = geometryTargets(pack);
        // The scene target comes first, in the slot of attachment 0.
        if (sceneTarget != null) {
            geometryTargets.remove(sceneTarget);
            geometryTargets.add(0, sceneTarget);
        }
        int[][] geometryImages = new int[2][geometryTargets.size()];
        int[] translucentCur = null;

        int total = order.size() + 1;
        for (int n = 0; n < total; n++) {
            boolean combination = n == order.size();
            if (n == prologueSteps) opaqueCur = cur.clone();
            if (n == prologueSteps + deferredSteps) translucentCur = cur.clone();
            IrPack.Pass pass = combination ? pack.combination : order.get(n);
            if (pass.enabled != null) {
                warnings.add("pass " + pass.name + ": \"enabled\" expressions arrive with options (M5); the pass always runs");
            }

            List<Input> inputs = new ArrayList<>();
            for (String name : pass.inputs) {
                if (isHostName(name)) {
                    if (!POST_HOST_INPUTS.contains(name)) {
                        throw new IllegalArgumentException("pass " + pass.name + ": host texture " + name
                                + " is not available in post passes");
                    }
                    hostInputs.add(name);
                }
                inputs.add(input(pack, name, cur));
            }

            for (Input in : inputs) {
                // The shadow command buffer is submitted first, so SHADOW_COMP finds the map ready.
                boolean shadowRead = !combination && stages.get(n) == IrPack.Stage.SHADOW_COMP && in.isHost() && in.host().startsWith("shadow");
                // Before the scene, the distant terrain's depth is the last frame's (Iris draws it earlier).
                boolean lastFrame = in.isHost() && in.host().startsWith("dhDepthTex");
                if (in.isHost() && !combination && n < prologueSteps && !shadowRead && !lastFrame) {
                    throw new IllegalArgumentException("pass " + pass.name + ": " + in.host() + " does not exist before the scene");
                }
                if (in.isHost() && !combination && n < prologueSteps + deferredSteps && !DEFERRED_READS.contains(in.host())) {
                    throw new IllegalArgumentException("pass " + pass.name + ": " + in.host()
                            + " does not exist yet at DEFERRED");
                }
            }

            List<ImageRef> mipsBefore = new ArrayList<>();
            for (String m : pass.mipmapsBefore) {
                int t = indexOf(pack, m);
                mipsBefore.add(new ImageRef(t, cur[t]));
            }

            List<String> written = pass.isCompute() ? pass.writes : pass.targets;
            List<ImageRef> outputs = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String name : written) {
                if (!seen.add(name)) {
                    throw new IllegalArgumentException("pass " + pass.name + " writes " + name + " twice");
                }
                int t = indexOf(pack, name);
                if (pack.textures.get(t).file != null) {
                    throw new IllegalArgumentException("pass " + pass.name + " writes the file texture " + name);
                }
                int image = cur[t];
                if (pass.inputs.contains(name)) {
                    imageCount[t] = 2;
                    image = 1 - cur[t];
                }
                outputs.add(new ImageRef(t, image));
            }
            if (!pass.isCompute() && !combination) {
                checkSameExtent(pack, pass);
            }
            // Stores land on the image that is current, as with Iris; a read-while-write target has the other one.
            List<ImageRef> storage = outputs;
            if (!pass.isCompute()) {
                storage = new ArrayList<>();
                for (String name : pass.writes) {
                    int t = indexOf(pack, name);
                    if (pack.textures.get(t).file != null) {
                        throw new IllegalArgumentException("pass " + pass.name + " writes the file texture " + name);
                    }
                    storage.add(new ImageRef(t, cur[t]));
                }
            }
            // The new images become current only after the pass: its inputs read the old ones.
            int[] before = cur.clone();
            for (ImageRef out : outputs) cur[out.texture()] = out.image();
            for (Map.Entry<String, Boolean> flip : pass.flip.entrySet()) {
                int t = indexOf(pack, flip.getKey());
                if (flip.getValue()) {
                    imageCount[t] = 2;
                    cur[t] = 1 - before[t];
                } else {
                    cur[t] = before[t];
                }
            }

            List<Integer> mips = new ArrayList<>();
            for (String m : pass.mipmaps) mips.add(indexOf(pack, m));

            steps.add(new Step(pass, combination ? null : stages.get(n), combination ? 0 : indices.get(n), combination,
                    Collections.unmodifiableList(inputs), Collections.unmodifiableList(outputs),
                    Collections.unmodifiableList(mips), Collections.unmodifiableList(mipsBefore),
                    Collections.unmodifiableList(storage)));
        }

        if (translucentCur == null) translucentCur = cur;
        if (opaqueCur == null) opaqueCur = new int[textures];
        for (int i = 0; i < geometryTargets.size(); i++) {
            geometryImages[OPAQUE][i] = opaqueCur[indexOf(pack, geometryTargets.get(i))];
            geometryImages[TRANSLUCENT][i] = translucentCur[indexOf(pack, geometryTargets.get(i))];
        }

        Set<String> stored = new LinkedHashSet<>();
        for (IrPack.Gbuffer g : pack.gbuffers.values()) stored.addAll(g.writes);
        List<String> geometryWrites = List.copyOf(stored);
        List<ImageRef> storedOpaque = new ArrayList<>(), storedTranslucent = new ArrayList<>();
        for (String name : geometryWrites) {
            int t = indexOf(pack, name);
            storedOpaque.add(new ImageRef(t, opaqueCur[t]));
            storedTranslucent.add(new ImageRef(t, translucentCur[t]));
        }

        Set<String> inputNames = new LinkedHashSet<>();
        for (IrPack.Gbuffer g : pack.gbuffers.values()) {
            for (String name : g.inputs) {
                boolean shadowProgram = g.name.equals("shadow");
                boolean host = isHostName(name);
                if (shadowProgram && (host || pack.texture(name) == null || pack.texture(name).file == null)) {
                    throw new IllegalArgumentException("the shadow program can only sample file textures, not " + name);
                }
                if (host && !(SHADOW_INPUTS.contains(name) && pack.shadow != null)
                        && !GEOMETRY_HOST_INPUTS.contains(name)) {
                    throw new IllegalArgumentException("geometry program " + g.name + ": host texture " + name
                            + " is not available there");
                }
                inputNames.add(name);
            }
        }
        List<Input> opaque = new ArrayList<>(), translucent = new ArrayList<>();
        List<Integer> snapshots = new ArrayList<>();
        for (String name : inputNames) {
            if (isHostName(name)) hostInputs.add(name);
            if (geometryTargets.contains(name)) {
                // It cannot be sampled while attached: programs read a copy taken after DEFERRED.
                ImageRef copy = new ImageRef(indexOf(pack, name), SNAPSHOT);
                snapshots.add(copy.texture());
                opaque.add(new Input(name, null, copy));
                translucent.add(new Input(name, null, copy));
                continue;
            }
            opaque.add(input(pack, name, opaqueCur));
            translucent.add(input(pack, name, translucentCur));
        }

        for (String name : hostInputs) {
            if (SHADOW_INPUTS.contains(name) && pack.shadow == null) {
                throw new IllegalArgumentException(name + " needs a \"shadow\" section");
            }
            if (name.equals("gnormalCopy") && !geometryTargets.contains("gnormal")) {
                throw new IllegalArgumentException("gnormalCopy needs a geometry target named gnormal");
            }
        }
        if (hostInputs.contains("gnormalCopy")) {
            // The translucent programs sample the opaque normals through a copy taken after
            // DEFERRED (resumeGeometry); the texture needs its SNAPSHOT image allocated.
            int gnormal = indexOf(pack, "gnormal");
            if (!snapshots.contains(gnormal)) snapshots.add(gnormal);
        }
        IrPack.Gbuffer shadowProgram = pack.gbuffers.get("shadow");
        if (pack.shadow != null && shadowProgram == null) {
            throw new IllegalArgumentException("a shadow map needs the geometry program shadow");
        }
        List<String> shadowTargets = new ArrayList<>();
        if (shadowProgram != null) {
            for (String t : IrPack.SHADOW_COLORS) {
                if (shadowProgram.targets.contains(t) || hostInputs.contains(t)) shadowTargets.add(t);
            }
        }

        if (pack.renderer.has("rt") && pack.renderer.get("rt").getAsBoolean() && (!pack.gbuffers.isEmpty() || sceneTarget != null)) {
            throw new IllegalArgumentException("a pack with renderer.rt post-processes the image: it cannot have geometry programs");
        }
        Map<GeometryCategory, IrPack.Gbuffer> routes = new java.util.EnumMap<>(GeometryCategory.class);
        if (!pack.gbuffers.isEmpty()) {
            for (GeometryCategory c : GeometryCategory.values()) {
                IrPack.Gbuffer g = c.resolve(pack.gbuffers);
                if (g != null) routes.put(c, g);
            }
        }
        return new FramePlan(pack, Collections.unmodifiableList(steps), imageCount, cur,
                Collections.unmodifiableSet(hostInputs), pack.combination.inputs.contains("sceneColor"),
                pack.renderer.has("hdr") && pack.renderer.get("hdr").getAsBoolean(), vertexFlags(pack), prologueSteps, deferredSteps,
                Collections.unmodifiableMap(routes), Collections.unmodifiableList(geometryTargets), geometryImages,
                List.of(Collections.unmodifiableList(opaque), Collections.unmodifiableList(translucent)),
                geometryWrites, List.of(List.copyOf(storedOpaque), List.copyOf(storedTranslucent)),
                hostInputs.contains("depthtex1"), hostInputs.contains("depthtex2"), Collections.unmodifiableList(snapshots),
                sceneTarget, shadowProgram, Collections.unmodifiableList(shadowTargets),
                hostInputs.contains("shadowtex1") || hostInputs.contains("shadowtex1Hw"),
                Collections.unmodifiableList(warnings));
    }

    /** Host textures a {@code DEFERRED} pass can sample: what exists before the translucent layer. */
    private static final Set<String> DEFERRED_READS = Set.of("sceneColor", "sceneDepth", "motion", "depthtex0", "depthtex1", "depthtex2",
            "dhDepthTex0", "dhDepthTex1",
            "shadowtex0", "shadowtex1", "shadowtex0Hw", "shadowtex1Hw", "shadowcolor0", "shadowcolor1", "shadowcolor2", "shadowcolor3", "shadowcolor4", "shadowcolor5", "shadowcolor6", "shadowcolor7", "normals", "specular");

    private static boolean rendererFalse(IrPack pack, String key) {
        return pack.renderer.has(key) && pack.renderer.get(key).getAsString().equals("false");
    }

    private static int vertexFlags(IrPack pack) {
        int flags = 0;
        if (rendererFalse(pack, "oldLighting")) flags |= VERTEX_PLAIN_SHADE;
        if (pack.renderer.has("separateAo") && pack.renderer.get("separateAo").getAsString().equals("true")) flags |= VERTEX_SEPARATE_AO;
        for (String name : pack.requiredVertex) {
            flags |= switch (name) {
                case "normal" -> VERTEX_NORMAL;
                case "tangent" -> VERTEX_TANGENT;
                case "midTexCoord" -> VERTEX_MID_TEX;
                case "blockId" -> VERTEX_BLOCK;
                case "midBlock" -> VERTEX_MID_BLOCK;
                default -> throw new IllegalArgumentException("unknown vertex attribute in requires.vertex: " + name);
            };
        }
        return flags;
    }

    private static boolean isHostName(String name) {
        return IrPack.HOST_TEXTURES.contains(name) || name.startsWith(IrPack.HOST_PREFIX);
    }

    private static Input input(IrPack pack, String name, int[] cur) {
        if (isHostName(name)) return new Input(name, name, null);
        if (name.endsWith("Prev") && pack.texture(name) == null) {
            return new Input(name, null, new ImageRef(indexOf(pack, name.substring(0, name.length() - 4)), PREV));
        }
        int t = indexOf(pack, name);
        return new Input(name, null, new ImageRef(t, cur[t]));
    }

    /** Dynamic rendering attaches them together with the scene: they must have its size. */
    private static List<String> geometryTargets(IrPack pack) {
        Set<String> union = new LinkedHashSet<>();
        for (IrPack.Gbuffer g : pack.gbuffers.values()) {
            if (g.name.equals("shadow")) continue;
            for (String t : g.targets) {
                if (t.equals("sceneColor")) continue;
                IrPack.TextureDef def = pack.texture(t);
                if (def.width != 0 || def.scale != 1.0f || def.file != null) {
                    throw new IllegalArgumentException("geometry target " + t + " must be a render texture of scale 1");
                }
                union.add(t);
            }
        }
        if (union.size() > MAX_GEOMETRY_TARGETS) {
            throw new IllegalArgumentException("more than " + MAX_GEOMETRY_TARGETS + " geometry targets");
        }
        return new ArrayList<>(union);
    }

    /** Vulkan guarantees 4 color attachments; the devices we target have 8, one is the scene color. */
    private static final int MAX_GEOMETRY_TARGETS = 7;

    /** Fragment output location of a geometry target. */
    public int attachment(String target) {
        if (target.startsWith("shadowcolor")) return shadowTargets.indexOf(target);
        if (target.equals("sceneColor")) return 0;
        // With a scene target the list starts with attachment 0.
        return geometryTargets.indexOf(target) + (sceneTarget != null ? 0 : 1);
    }

    /** The pack draws the scene through programs; a shadow program alone does not count. */
    public boolean hasGeometry() {
        if (sceneTarget != null) return true;
        for (GeometryCategory c : routes.keySet()) {
            if (c != GeometryCategory.SHADOW) return true;
        }
        return false;
    }

    /** Dynamic rendering needs one render area: all targets of a fragment pass share an extent. */
    private static void checkSameExtent(IrPack pack, IrPack.Pass pass) {
        IrPack.TextureDef first = null;
        for (String name : pass.targets) {
            IrPack.TextureDef t = pack.texture(name);
            if (first == null) {
                first = t;
            } else if (t.width != first.width || t.height != first.height
                    || (t.width == 0 && t.scale != first.scale)) {
                throw new IllegalArgumentException("pass " + pass.name + ": targets " + first.name + " and " + t.name
                        + " differ in size");
            }
        }
    }

    private static int indexOf(IrPack pack, String name) {
        for (int i = 0; i < pack.textures.size(); i++) {
            if (pack.textures.get(i).name.equals(name)) return i;
        }
        throw new IllegalArgumentException("unknown texture: " + name);
    }

    /** True when the texture keeps a previous-frame image. */
    public boolean hasHistory(int texture) {
        return pack.textures.get(texture).history;
    }
}
