package net.vesu.pack.optifine;

import net.vesu.pack.host.FrameBlock;
import net.vesu.pack.host.Std140Layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GLSL 1.x-4.x of the OpenGL compatibility profile to Vulkan GLSL 450 (frontends.md, "GLSL
 * lowering"). It works on tokens: statements at global scope are parsed as declarations, and
 * function bodies only get identifier rewrites. Host resources are declared by the prelude,
 * so uniform and sampler declarations go away; what the host lacks becomes a zero constant.
 */
final class GlslLowering {

    /** In pipeline order, as a program lowers them. */
    enum Stage {
        VERTEX, TESS_CONTROL, TESS_EVAL, GEOMETRY, FRAGMENT, COMPUTE;

        /** Between the vertex and fragment stages: reads arrays with an element per vertex. */
        boolean isMiddle() {
            return this == TESS_CONTROL || this == TESS_EVAL || this == GEOMETRY;
        }
    }

    /** State shared by the stages of one program. */
    static final class Program {
        final String name;
        /** A geometry program, as opposed to a full-screen pass. */
        final boolean geometry;
        final boolean combination;
        /** Varyings the stage being lowered writes, by name; the fragment stage reads the last table. */
        Map<String, Slot> varyings = new LinkedHashMap<>();
        /** What the stage before a middle stage wrote. */
        Map<String, Slot> previous = Map.of();
        /** First location of the legacy varyings of each interface but the last. */
        final List<Integer> legacyBases = new ArrayList<>();
        /** Names of pack images and their samplers. */
        Set<String> resources = Set.of();
        Map<String, String> customTextures = Map.of();
        /** Location with free components, by kind of variable that may share it. */
        final Map<String, Slot> openSlots = new HashMap<>();
        int nextLocation;
        /** Samplers the program reads, under host or colortex names. */
        final Set<String> samplers = new LinkedHashSet<>();
        /** gl_FragData indices or legacy varyings the program uses. */
        final Set<Integer> fragData = new LinkedHashSet<>();
        final Set<String> attributes = new LinkedHashSet<>();
        /** Fragment stage: an {@code out} variable by location, for packs that declare their own outputs. */
        final Map<Integer, String> namedOutputs = new HashMap<>();
        /** The vector type of an {@code out} variable that is not a float, by location. */
        final Map<Integer, String> integerOutputs = new HashMap<>();
        boolean usesFrontColor, usesTexCoord, usesFogCoord;
        final Set<String> writtenByVertex = new LinkedHashSet<>();
        /** Type and array suffix of a varying by name, for the control stage GL supplies. */
        final Map<String, String[]> varyingTypes = new HashMap<>();
        /** Interface blocks by table key, each member as type, name and array suffix. */
        final Map<String, List<String[]>> blocks = new HashMap<>();
        /** What the default control stage hands on; later stages have tables of their own. */
        Map<String, Slot> controlInterface = Map.of();
        /** {@code colorimgN} and {@code shadowcolorimgN} any stage of a graphics program declared. */
        final Set<String> colorImages = new LinkedHashSet<>();

        Program(String name, boolean geometry, boolean combination) {
            this.name = name;
            this.geometry = geometry;
            this.combination = combination;
            // Location 0 of a pass is the prelude's vkm_texCoord.
            nextLocation = geometry ? 0 : 1;
        }

        /** Each pair of stages has its own interface: a middle stage reads one table and writes another. */
        void nextStage() {
            legacyBases.add(nextLocation);
            previous = varyings;
            varyings = new LinkedHashMap<>();
            openSlots.clear();
            nextLocation = geometry ? 0 : 1;
        }

        /** The control stage GL supplies changes nothing: the evaluation stage reads what the vertex stage wrote. */
        void passThrough() {
            varyings = new LinkedHashMap<>(previous);
            controlInterface = varyings;
            nextLocation = legacyBases.get(legacyBases.size() - 1);
        }

        /**
         * GL's stage for a {@code .tes} without a {@code .tcs}: patches of 3 go through unchanged,
         * at the default tessellation levels of 1.
         */
        String defaultControl() {
            StringBuilder d = new StringBuilder("layout(vertices = 3) out;\n"), copy = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, Slot> e : controlInterface.entrySet()) {
                Slot s = e.getValue();
                String at = "layout(location = " + s.location + (s.component > 0 ? ", component = " + s.component : "") + ") ";
                String from = "vkmFrom" + n, to = "vkmTo" + n;
                n++;
                List<String[]> members = blocks.get(e.getKey());
                if (members != null) {
                    StringBuilder body = new StringBuilder();
                    for (String[] m : members) {
                        body.append(m[0]).append(' ').append(m[1]).append(m[2]).append("; ");
                        copy.append("    ").append(to).append("[gl_InvocationID].").append(m[1]).append(" = ")
                                .append(from).append("[gl_InvocationID].").append(m[1]).append(";\n");
                    }
                    String block = e.getKey().substring(1);
                    d.append(at).append("in ").append(block).append(" { ").append(body).append("} ").append(from).append("[];\n");
                    d.append(at).append("out ").append(block).append(" { ").append(body).append("} ").append(to).append("[];\n");
                } else {
                    String[] type = varyingTypes.get(e.getKey());
                    d.append(at).append("in ").append(type[0]).append(' ').append(from).append("[]").append(type[1]).append(";\n");
                    d.append(at).append("out ").append(type[0]).append(' ').append(to).append("[]").append(type[1]).append(";\n");
                    copy.append("    ").append(to).append("[gl_InvocationID] = ").append(from).append("[gl_InvocationID];\n");
                }
            }
            if (usesFrontColor) copy.append("    vkm_Color[gl_InvocationID] = vkm_ColorIn[gl_InvocationID];\n");
            if (usesTexCoord) copy.append("    vkm_TexCoord[gl_InvocationID] = vkm_TexCoordIn[gl_InvocationID];\n");
            if (usesFogCoord) copy.append("    vkm_FogCoord[gl_InvocationID] = vkm_FogCoordIn[gl_InvocationID];\n");
            return d + "void vkm_main() {\n    gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;\n" + copy
                    + "    gl_TessLevelOuter[0] = gl_TessLevelOuter[1] = gl_TessLevelOuter[2] = gl_TessLevelOuter[3] = 1.0;\n"
                    + "    gl_TessLevelInner[0] = gl_TessLevelInner[1] = 1.0;\n}\n";
        }

        /** First location of the legacy varyings between stage {@code index} and the next. */
        int legacyBase(int index) {
            return index < legacyBases.size() ? legacyBases.get(index) : nextLocation;
        }
    }

    /** Names that are not keywords in the source's GLSL but are in Vulkan GLSL 450. */
    private static final Set<String> RESERVED = Set.of("sample", "filter", "input", "output", "common", "partition",
            "active", "sampler", "resource", "buffer", "shared", "patch", "subroutine", "coherent", "restrict",
            "readonly", "writeonly", "atomic_uint", "image2D", "precise", "texture1D", "texture3D", "textureCube",
            "subpassInput", "noise", "this");

    private static final Map<String, String> FUNCTIONS = Map.ofEntries(
            Map.entry("texture2D", "texture"), Map.entry("texture3D", "texture"), Map.entry("textureCube", "texture"),
            Map.entry("texture2DLod", "textureLod"), Map.entry("texture2DLodARB", "textureLod"),
            Map.entry("texture3DLod", "textureLod"), Map.entry("texture2DGrad", "textureGrad"),
            Map.entry("texture2DGradARB", "textureGrad"), Map.entry("texture2DProj", "textureProj"),
            Map.entry("texture2DProjLod", "textureProjLod"), Map.entry("texelFetch2D", "texelFetch"),
            Map.entry("texelFetch3D", "texelFetch"), Map.entry("textureSize2D", "textureSize"),
            Map.entry("shadow2D", "vkm_shadow2D"), Map.entry("shadow2DLod", "vkm_shadow2DLod"),
            Map.entry("ftransform", "vkm_ftransform"));

    private static final Map<String, String> VERTEX_BUILTINS = Map.ofEntries(
            Map.entry("gl_Vertex", "vkm_glVertex"), Map.entry("gl_Color", "vkm_glColor"),
            Map.entry("gl_Normal", "vkm_glNormal"), Map.entry("gl_MultiTexCoord0", "vkm_glMultiTexCoord0"),
            Map.entry("gl_MultiTexCoord1", "vkm_glMultiTexCoord1"), Map.entry("gl_MultiTexCoord2", "vkm_glMultiTexCoord1"),
            Map.entry("mc_midTexCoord", "vkm_glMidTexCoord"), Map.entry("gl_VertexID", "gl_VertexIndex"),
            Map.entry("gl_InstanceID", "gl_InstanceIndex"));

    private static final Map<String, String> BUILTINS = Map.ofEntries(
            Map.entry("gl_ModelViewMatrix", "vkm_glModelView"), Map.entry("gl_ProjectionMatrix", "vkm_glProjection"),
            Map.entry("gl_ModelViewProjectionMatrix", "vkm_glMVP"), Map.entry("gl_NormalMatrix", "vkm_glNormalMatrix"),
            Map.entry("gl_ModelViewMatrixInverse", "vkm_glModelViewInverse"),
            Map.entry("gl_ProjectionMatrixInverse", "vkm_glProjectionInverse"),
            Map.entry("gl_TextureMatrix", "vkm_glTextureMatrix"), Map.entry("gl_Fog", "vkm_glFog"));

    /** OptiFine's other names of its textures. */
    private static final Map<String, String> SAMPLER_ALIASES = Map.ofEntries(
            Map.entry("gcolor", "colortex0"), Map.entry("gdepth", "colortex1"), Map.entry("gnormal", "colortex2"),
            Map.entry("composite", "colortex3"), Map.entry("gaux1", "colortex4"), Map.entry("gaux2", "colortex5"),
            Map.entry("gaux3", "colortex6"), Map.entry("gaux4", "colortex7"), Map.entry("gdepthtex", "depthtex0"),
            Map.entry("shadowcolor", "shadowcolor0"), Map.entry("watershadow", "shadowtex0"),
            Map.entry("tex", "gtexture"), Map.entry("texture", "gtexture"),
            Map.entry("shadowtex0HW", "shadowtex0"), Map.entry("shadowtex1HW", "shadowtex1"),
            Map.entry("dhDepthTex", "dhDepthTex0"));

    private static final java.util.regex.Pattern KNOWN_SAMPLER = java.util.regex.Pattern.compile(
            "colortex([0-9]|1[0-5])|depthtex[0-2]|shadowtex[01](Hw)?|shadowcolor[0-7]|gtexture|lightmap|normals|specular|vkf_noisetex|dhDepthTex[01]");

    private static final Set<String> QUALIFIERS = Set.of("const", "uniform", "attribute", "varying", "in", "out", "inout",
            "flat", "smooth", "noperspective", "centroid", "invariant", "highp", "mediump", "lowp", "precision",
            "readonly", "writeonly", "coherent", "restrict", "volatile", "patch");

    /** {@link #RESERVED} names that stay keywords when a declaration goes on after them, as a variable cannot. */
    private static final Set<String> QUALIFIER_KEYWORDS = Set.of("buffer", "shared", "coherent", "restrict", "readonly",
            "writeonly", "image2D", "patch");

    private record Tok(char kind, String text) {
        boolean is(String s) {
            return text.equals(s);
        }
    }

    /** The name a sampler has after the aliases, for {@code texture.<stage>.<name>}. */
    static String alias(String name) {
        return SAMPLER_ALIASES.getOrDefault(name, name);
    }

    private final GlslPreprocessor macros;
    private final Program program;
    private final Stage stage;
    private final Set<String> customUniforms;
    private final List<String> warnings;
    /** Names to rewrite everywhere: resolved sampler aliases and cast uniforms. */
    private final Map<String, String> renames = new HashMap<>();
    private final StringBuilder out = new StringBuilder();
    private final StringBuilder header = new StringBuilder();
    private final List<String> constants = new ArrayList<>();
    /** The attributes the program declared; a known name is the host's. */
    private static final Set<String> HOST_ATTRIBUTES = Set.of("mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock");

    GlslLowering(Program program, Stage stage, Set<String> customUniforms, List<String> warnings, GlslPreprocessor macros) {
        this.macros = macros;
        this.program = program;
        this.stage = stage;
        this.customUniforms = customUniforms;
        this.warnings = warnings;
    }

    String lower(String source) {
        List<Tok> toks = tokenize(source);
        boolean watershadow = false;
        for (int i = 0; i + 1 < toks.size(); i++) {
            if (toks.get(i).is("watershadow")) watershadow = true;
        }
        shadowAlias = watershadow ? "shadowtex1" : "shadowtex0";

        int i = 0, n = toks.size();
        while (i < n) {
            Tok t = toks.get(i);
            if (t.kind == 'w' || t.kind == 'd') {
                out.append(t.kind == 'd' ? directive(t.text) : t.text);
                i++;
                continue;
            }
            // A statement at global scope: up to ';' or the '{' of a body.
            int end = i, parens = 0;
            while (end < n) {
                Tok e = toks.get(end);
                if (e.is("(")) parens++;
                else if (e.is(")")) parens--;
                else if (parens == 0 && (e.is(";") || e.is("{"))) break;
                else if (e.kind == 'd') break;
                end++;
            }
            if (end >= n || toks.get(end).kind == 'd') {
                // Text cut by a directive, usually a macro use; passes through.
                for (int k = i; k < Math.min(end, n); k++) out.append(rewrite(toks, k));
                i = end;
                continue;
            }
            if (toks.get(end).is("{")) {
                i = body(toks, i, end);
            } else {
                declaration(toks.subList(i, end));
                i = end + 1;
            }
        }
        return header + String.join("\n", constants) + "\n" + out;
    }

    /** Inside a struct or block: its members are the pack's own names. */
    private boolean blockBody;

    /** {@code shadow} is the depth without translucent geometry when the pack also has {@code watershadow}. */
    private String shadowAlias;

    /** Copies a function, struct or block with identifier rewrites; {@code open} is its '{'. */
    private int body(List<Tok> toks, int from, int open) {
        interfaceBlock(toks, from, open);
        boolean isMain = false;
        for (int k = from; k < open; k++) {
            if (toks.get(k).is("main") && toks.get(k).kind == 'i') isMain = true;
        }
        for (int k = from; k < open; k++) {
            Tok t = toks.get(k);
            if (t.is("main") && isMain) out.append("vkm_main");
            else out.append(rewrite(toks, k));
        }
        // A struct or a block has no parameter list before its body, only perhaps a layout.
        boolean function = false;
        for (int k = open - 1; k >= from; k--) {
            if (toks.get(k).kind == 'w') continue;
            function = toks.get(k).is(")");
            break;
        }
        blockBody = !function;
        int level = 0, k = open;
        Set<Integer> zeroed = new java.util.HashSet<>();
        String zeroType = null;
        for (; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.is("{")) level++;
            else if (t.is("}")) level--;
            if (function && t.kind == 'i' && ZERO_TYPES.contains(t.text) && startsStatement(toks, k) && markLocals(toks, k, zeroed)) {
                zeroType = t.text;
            }
            if (t.kind == 'd') out.append(directive(t.text));
            else out.append(rewrite(toks, k));
            if (zeroed.remove(k)) out.append(" = ").append(zeroType).append("(0)");
            if (level == 0) break;
        }
        blockBody = false;
        // The rest of the declaration: its instance name and the semicolon.
        if (!function) {
            for (k++; k < toks.size() && !toks.get(k).is(";"); k++) out.append(rewrite(toks, k));
            out.append(';');
        }
        return k + 1;
    }

    /**
     * {@code out Name { ... } instance;}: Vulkan wants a location where GL matches by the block's
     * name. The members take locations in order, as they would without a block.
     */
    private void interfaceBlock(List<Tok> toks, int from, int open) {
        boolean input = false, output = false;
        for (int k = from; k < open; k++) {
            if (toks.get(k).is("(")) {
                if (!(k > from && previousCode(toks, k).is("layout"))) return;
            }
            input |= toks.get(k).is("in");
            output |= toks.get(k).is("out");
            if (toks.get(k).is("uniform") || toks.get(k).is("buffer") || toks.get(k).is("struct")) return;
        }
        Tok name = previousCode(toks, open);
        if ((!input && !output) || name == null || name.text.startsWith("gl_")) return;
        String key = "{" + name.text;
        List<String[]> members = new ArrayList<>();
        int count = 0, level = 0;
        List<Tok> member = new ArrayList<>();
        for (int k = open; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.is("{")) level++;
            else if (t.is("}") && --level == 0) break;
            else if (t.is(";")) {
                count += blockMember(member, members);
                member.clear();
            } else if (t.kind != 'w') member.add(t);
        }
        Slot slot;
        if (input) {
            slot = (stage == Stage.FRAGMENT ? program.varyings : program.previous).get(key);
            if (slot == null) {
                warnings.add(program.name + ": nothing writes interface block " + name.text);
                slot = new Slot(UNWRITTEN_BLOCK, 0, "");
            }
        } else {
            slot = new Slot(program.nextLocation, 0, "");
            program.nextLocation += count;
            program.varyings.put(key, slot);
            program.blocks.put(key, members);
        }
        out.append("layout(location = ").append(slot.location).append(") ");
    }

    /** Past anything a stage writes, so a block that reads nothing cannot overlap a real varying. */
    private static final int UNWRITTEN_BLOCK = 30;

    private static Tok previousCode(List<Tok> toks, int at) {
        for (int k = at - 1; k >= 0; k--) {
            if (toks.get(k).kind != 'w') return toks.get(k);
        }
        return null;
    }

    /** One member declaration of a block; returns the locations it takes. */
    private int blockMember(List<Tok> member, List<String[]> members) {
        int p = 0;
        while (p < member.size() && QUALIFIERS.contains(member.get(p).text)) p++;
        if (p >= member.size()) return 0;
        String type = member.get(p++).text;
        int count = 0;
        for (List<Tok> d : splitDeclarators(member.subList(p, member.size()))) {
            if (d.isEmpty()) continue;
            StringBuilder array = new StringBuilder();
            for (int k = 1; k < d.size(); k++) array.append(d.get(k).text);
            members.add(new String[]{type, d.get(0).text, array.toString()});
            count += slots(type, array.toString());
        }
        return count;
    }

    private static final Set<String> ZERO_TYPES = Set.of("float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3",
            "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4");

    private static boolean startsStatement(List<Tok> toks, int at) {
        for (int k = at - 1; k >= 0; k--) {
            Tok t = toks.get(k);
            if (t.kind == 'w') continue;
            return t.kind == 'd' || t.is("{") || t.is("}") || t.is(";");
        }
        return false;
    }

    /**
     * {@code vec3 a, b;} in a function: GL drivers hand out zeros and packs come to rely on it,
     * while an uninitialized SPIR-V variable can read as anything. Marks the names to initialize.
     */
    private static boolean markLocals(List<Tok> toks, int type, Set<Integer> names) {
        List<Integer> found = new ArrayList<>();
        boolean wantName = true;
        for (int k = type + 1; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.kind == 'w') continue;
            if (wantName) {
                if (t.kind != 'i') return false;
                found.add(k);
                wantName = false;
            } else if (t.is(",")) {
                wantName = true;
            } else if (t.is(";")) {
                names.addAll(found);
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    private void declaration(List<Tok> stmt) {
        List<Tok> code = new ArrayList<>();
        for (Tok t : stmt) {
            if (t.kind != 'w') code.add(t);
        }
        if (code.isEmpty()) {
            return;
        }
        int p = 0;
        Set<String> qualifiers = new LinkedHashSet<>();
        String layout = null;
        while (p < code.size()) {
            String t = code.get(p).text;
            if (t.equals("layout") && p + 1 < code.size() && code.get(p + 1).is("(")) {
                StringBuilder l = new StringBuilder();
                int q = p + 2;
                while (q < code.size() && !code.get(q).is(")")) l.append(code.get(q++).text);
                layout = l.toString();
                p = q + 1;
            } else if (QUALIFIERS.contains(t)) {
                qualifiers.add(t);
                p++;
            } else {
                break;
            }
        }
        boolean storage = qualifiers.contains("uniform") || qualifiers.contains("attribute")
                || qualifiers.contains("varying") || qualifiers.contains("in") || qualifiers.contains("out");
        if (!storage || p >= code.size()) {
            // A const, a plain global, a prototype or a precision statement.
            if (qualifiers.contains("precision")) return;
            // A global of the pack named like a host uniform is the pack's own.
            for (int k = p + 1; k < code.size(); k++) {
                String name = code.get(k).text;
                if (code.get(k).kind == 'i' && isHostName(name) && (k + 1 == code.size() || !code.get(k + 1).is("("))) {
                    renames.put(name, "vkm_p_" + name);
                }
                if (code.get(k).is("=") || code.get(k).is("(")) break;
            }
            plainStatement(stmt, qualifiers);
            return;
        }
        String type = code.get(p++).text;
        for (List<Tok> d : splitDeclarators(code.subList(p, code.size()))) {
            if (d.isEmpty()) continue;
            String name = d.get(0).text;
            String array = "";
            int a = 1;
            if (a < d.size() && d.get(a).is("[")) {
                StringBuilder b = new StringBuilder();
                while (a < d.size() && !d.get(a).is("]")) b.append(d.get(a++).text);
                array = b + "]";
                a++;
            }
            if (qualifiers.contains("uniform")) {
                StringBuilder initializer = new StringBuilder();
                if (a < d.size() && d.get(a).is("=")) {
                    for (int k = a + 1; k < d.size(); k++) initializer.append(d.get(k).text).append(' ');
                }
                uniform(type, name, array, initializer.toString().trim());
            }
            else if (qualifiers.contains("attribute") || (stage == Stage.VERTEX && qualifiers.contains("in"))) attribute(type, name, array);
            else if (stage == Stage.FRAGMENT && qualifiers.contains("out")) output(type, name, layout);
            else varying(type, name, array, qualifiers);
        }
    }

    private boolean isHostName(String name) {
        return frameField(name) != null || customUniforms.contains(name) || ENTITY_UNIFORMS.contains(name);
    }

    private static final Set<String> ENTITY_UNIFORMS = Set.of("entityColor", "entityId", "blockEntityId", "renderStage", "currentRenderedItemId");

    private static Std140Layout.Field frameField(String name) {
        for (Std140Layout.Field f : FrameBlock.LAYOUT.fields()) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }

    private void plainStatement(List<Tok> stmt, Set<String> qualifiers) {
        boolean constant = qualifiers.contains("const");
        boolean foldable = true;
        if (constant) {
            // A const initialized from a uniform is an error in 450, not in the old GLSL.
            for (Tok t : stmt) {
                if (t.kind == 'i' && (customUniforms.contains(t.text) || frameField(t.text) != null)) {
                    foldable = false;
                }
            }
        }
        for (int k = 0; k < stmt.size(); k++) {
            if (!foldable && stmt.get(k).is("const")) continue;
            out.append(rewrite(stmt, k));
        }
        out.append(';');
    }

    static final java.util.regex.Pattern COLOR_IMAGE = java.util.regex.Pattern.compile("(?:shadow)?colorimg(\\d+)");

    private void uniform(String type, String name, String array, String initializer) {
        // The host declares the images and their samplers (Resources).
        if (program.resources.contains(name)) return;
        if (name.equals("mc_chunkFade")) return;
        if (type.startsWith("sampler") || type.startsWith("usampler") || type.startsWith("isampler")) {
            sampler(type, name);
            return;
        }
        if (type.startsWith("image") || type.startsWith("uimage") || type.startsWith("iimage")) {
            // A render texture the program writes; the frontend binds it.
            if (COLOR_IMAGE.matcher(name).matches()) {
                program.colorImages.add(name);
                return;
            }
            warnings.add(program.name + ": image uniform " + name + " is not supported");
            return;
        }
        if (customUniforms.contains(name)) return;
        Std140Layout.Field f = frameField(name);
        if (f != null) {
            if (!f.type().glsl.equals(type)) {
                renames.put(name, "vkm_cast_" + name);
                constants.add("#define vkm_cast_" + name + " " + type + "(" + name + ")");
            }
            return;
        }
        if (ENTITY_UNIFORMS.contains(name)) return;
        if (!initializer.isEmpty() && array.isEmpty()) {
            // Declared with a default for the loaders that do not set it.
            constants.add("const " + type + " " + name + " = " + initializer + ";");
            return;
        }
        warnings.add(program.name + ": uniform " + name + " is unknown, it stays 0");
        constants.add(array.isEmpty() ? "const " + type + " " + name + " = " + type + "(0);" : type + " " + name + array + ";");
    }

    private void sampler(String type, String name) {
        String custom = program.customTextures.get(name);
        if (custom != null && (program.geometry || !custom.equals("gtexture"))) {
            renames.put(name, custom);
            program.samplers.add(custom);
            return;
        }
        String resolved = name;
        if (name.equals("shadow")) resolved = shadowAlias;
        else if (SAMPLER_ALIASES.containsKey(name)) resolved = SAMPLER_ALIASES.get(name);
        // In a geometry program unit 0 is the bound texture.
        if (program.geometry && resolved.equals("colortex0")) resolved = "gtexture";
        if (program.geometry && resolved.equals("depthtex0")) resolved = "depthtex1";
        // In a pass unit 0 is colortex0.
        if (!program.geometry && resolved.equals("gtexture")) resolved = "colortex0";
        // A comparison sampler is its own input: other programs may read the same map as depth.
        if (type.equals("sampler2DShadow")) resolved += "Hw";
        resolved = switch (resolved) {
            case "noisetex" -> "vkf_noisetex";
            default -> resolved;
        };
        if (!KNOWN_SAMPLER.matcher(resolved).matches()) {
            warnings.add(program.name + ": sampler " + name + " is unknown, it reads white");
            resolved = "vkf_white";
        }
        if (!resolved.equals(name)) renames.put(name, resolved);
        program.samplers.add(resolved);
    }

    private void attribute(String type, String name, String array) {
        if (HOST_ATTRIBUTES.contains(name)) {
            program.attributes.add(name);
            // The host's is a vec4, with the block's light in w.
            if (name.equals("at_midBlock") && type.equals("vec3")) renames.put(name, name + ".xyz");
            return;
        }
        if (ENTITY_UNIFORMS.contains(name) || name.equals("mc_chunkFade")) return;
        warnings.add(program.name + ": attribute " + name + " is unknown, it stays 0");
        constants.add("const " + type + " " + name + " = " + type + "(0);");
    }

    private void output(String type, String name, String layout) {
        int location = program.namedOutputs.size();
        if (layout != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("location=(\\d+)").matcher(layout);
            if (m.find()) location = Integer.parseInt(m.group(1));
        }
        program.namedOutputs.put(location, name);
        if (type.startsWith("u")) program.integerOutputs.put(location, "uvec4");
        else if (type.startsWith("i")) program.integerOutputs.put(location, "ivec4");
        program.fragData.add(location);
        // The output itself is a vec4: a narrower one is its first channels.
        renames.put(name, "vkm_fragData" + location + switch (type) {
            case "float", "int", "uint" -> ".x";
            case "vec2", "ivec2", "uvec2" -> ".xy";
            case "vec3", "ivec3", "uvec3" -> ".xyz";
            default -> "";
        });
    }

    record Slot(int location, int component, String interpolation) { }

    private void varying(String type, String name, String array, Set<String> qualifiers) {
        String declared = isHostName(name) ? "vkm_p_" + name : name;
        if (!declared.equals(name)) renames.put(name, declared);
        // "varying in" and "varying out" are the old spelling of a geometry shader.
        boolean input = stage == Stage.FRAGMENT || (stage != Stage.VERTEX && qualifiers.contains("in"));
        Slot slot;
        if (input) {
            slot = (stage == Stage.FRAGMENT ? program.varyings : program.previous).get(name);
            if (slot == null) {
                // Nothing writes it; Vulkan rejects an unwritten input.
                constants.add(array.isEmpty() ? "const " + type + " " + declared + " = " + type + "(0);"
                        : type + " " + declared + (array.equals("[]") ? "[32]" : array) + ";");
                return;
            }
        } else {
            // The outer dimension of a tessellation control output counts vertices, not locations.
            boolean perVertex = stage == Stage.TESS_CONTROL && !qualifiers.contains("patch");
            slot = allocate(type, perVertex ? "" : array, interpolation(type, qualifiers));
            program.varyings.put(name, slot);
            program.varyingTypes.put(name, new String[]{type, array});
        }
        StringBuilder d = new StringBuilder("layout(location = ").append(slot.location);
        if (slot.component > 0) d.append(", component = ").append(slot.component);
        d.append(") ").append(slot.interpolation);
        d.append(input ? "in " : "out ").append(type).append(' ').append(declared).append(array).append(';');
        constants.add(d.toString());
    }

    private static String interpolation(String type, Set<String> qualifiers) {
        if (qualifiers.contains("patch")) return "patch ";
        boolean integer = type.startsWith("int") || type.startsWith("ivec") || type.startsWith("uint") || type.startsWith("uvec");
        if (qualifiers.contains("flat") || integer) return "flat ";
        if (qualifiers.contains("noperspective")) return "noperspective ";
        if (qualifiers.contains("centroid")) return "centroid ";
        return "";
    }

    /**
     * GL packs varyings by component, Vulkan counts locations: maxVertexOutputComponents / 4
     * of them, and a GPU may fault rather than refuse more. Scalars and short vectors share a
     * location when their type and interpolation agree, which GLSL requires of a location.
     */
    private Slot allocate(String type, String array, String interpolation) {
        int size = !array.isEmpty() || type.startsWith("mat") ? 4
                : type.equals("float") || type.equals("int") || type.equals("uint") ? 1
                : Character.isDigit(type.charAt(type.length() - 1)) ? type.charAt(type.length() - 1) - '0' : 4;
        if (size == 4) {
            Slot slot = new Slot(program.nextLocation, 0, interpolation);
            program.nextLocation += slots(type, array);
            return slot;
        }
        String base = type.startsWith("i") ? "int" : type.startsWith("u") ? "uint" : "float";
        String kind = base + ' ' + interpolation;
        Slot open = program.openSlots.get(kind);
        if (open == null || open.component + size > 4) {
            open = new Slot(program.nextLocation++, 0, interpolation);
        }
        program.openSlots.put(kind, new Slot(open.location, open.component + size, interpolation));
        return open;
    }

    private int slots(String type, String array) {
        int base = type.startsWith("mat") ? type.charAt(3) - '0' : 1;
        if (array.isEmpty()) return base;
        String size = array.replaceAll("[\\[\\] ]", "");
        try {
            String value = macros.value(size);
            return base * Integer.parseInt(value != null ? value : size);
        } catch (NumberFormatException e) {
            warnings.add(program.name + ": varying array size " + size + " is not a number");
            return base * 8;
        }
    }

    private static List<List<Tok>> splitDeclarators(List<Tok> list) {
        List<List<Tok>> all = new ArrayList<>();
        List<Tok> cur = new ArrayList<>();
        int level = 0;
        for (Tok t : list) {
            if (t.is("(") || t.is("[")) level++;
            else if (t.is(")") || t.is("]")) level--;
            if (t.is(",") && level == 0) {
                all.add(cur);
                cur = new ArrayList<>();
            } else {
                cur.add(t);
            }
        }
        all.add(cur);
        return all;
    }

    private String directive(String line) {
        if (!line.startsWith("#define") && !line.startsWith("# define")) return line;
        // Macro bodies are code: gl_ names in them are rewritten too.
        List<Tok> toks = tokenize(line.substring(line.indexOf("define") + 6));
        StringBuilder b = new StringBuilder("#define");
        boolean first = true;
        for (int k = 0; k < toks.size(); k++) {
            if (toks.get(k).kind == 'i' && first) {
                b.append(toks.get(k).text);
                first = false;
            } else {
                b.append(rewrite(toks, k));
            }
        }
        return b.toString();
    }

    /** The token at {@code k} after the identifier rewrites. */
    private String rewrite(List<Tok> toks, int k) {
        Tok t = toks.get(k);
        if (t.kind != 'i') return t.text;
        String s = t.text;
        String next = nextText(toks, k);
        // Chunks do not fade in here. Iris supplies the name, a pack need not declare it.
        if (s.equals("mc_chunkFade")) return "1.0";
        // A member is not the global of the same name.
        Tok before = previousCode(toks, k);
        if (blockBody || (before != null && before.is("."))) return s;
        if (s.equals("texture") && !next.equals("(")) return renames.getOrDefault("texture", program.geometry ? "gtexture" : "colortex0");
        if (renames.containsKey(s) && !next.equals("(")) return renames.get(s);
        String fn = FUNCTIONS.get(s);
        if (fn != null && next.equals("(")) return fn;
        if (s.equals("gl_FragColor")) {
            program.fragData.add(0);
            return "vkm_fragData0";
        }
        if (s.equals("gl_FragData")) {
            int index = fragIndex(toks, k);
            if (index >= 0) {
                program.fragData.add(index);
                return "vkm_fragData" + index + SKIP;
            }
            return "vkm_fragDataArray";
        }
        if (s.equals("gl_FragCoord") && program.combination) return "vkm_FragCoord";
        if (stage == Stage.VERTEX) {
            String b = VERTEX_BUILTINS.get(s);
            if (b != null) return b;
            if (s.equals("gl_FrontColor")) {
                program.usesFrontColor = true;
                return "vkm_Color";
            }
            if (s.equals("gl_FogFragCoord")) {
                program.usesFogCoord = true;
                return "vkm_FogCoord";
            }
        } else {
            if (s.equals("gl_Color") || s.equals("gl_FrontColor")) {
                program.usesFrontColor = true;
                return "vkm_Color";
            }
            if (s.equals("gl_FrontColorIn")) {
                program.usesFrontColor = true;
                return "vkm_ColorIn";
            }
            if (s.equals("gl_TexCoordIn")) {
                program.usesTexCoord = true;
                return "vkm_TexCoordIn";
            }
            if (s.equals("gl_FogFragCoord")) {
                program.usesFogCoord = true;
                return "vkm_FogCoord";
            }
        }
        if (s.equals("gl_VerticesIn")) return "gl_in.length()";
        if (s.equals("gl_TexCoord")) {
            program.usesTexCoord = true;
            return "vkm_TexCoord";
        }
        String b = BUILTINS.get(s);
        if (b != null) return b;
        if (QUALIFIER_KEYWORDS.contains(s) && Character.isJavaIdentifierStart(next.isEmpty() ? ';' : next.charAt(0))) return s;
        if (RESERVED.contains(s)) return "vkm_r_" + s;
        return s;
    }

    private static final String SKIP = "\u0002";

    private static String nextText(List<Tok> toks, int k) {
        for (int i = k + 1; i < toks.size(); i++) {
            if (toks.get(i).kind != 'w') return toks.get(i).text;
        }
        return "";
    }

    /** {@code gl_FragData[<int>]}, or -1 for an index only the compiler can read. */
    private static int fragIndex(List<Tok> toks, int k) {
        int i = k + 1;
        while (i < toks.size() && toks.get(i).kind == 'w') i++;
        if (i + 2 < toks.size() && toks.get(i).is("[") && toks.get(i + 2).is("]") && toks.get(i + 1).kind == 'n') {
            return Integer.parseInt(toks.get(i + 1).text.replaceAll("[^0-9]", ""));
        }
        return -1;
    }

    /** Removes the {@code [n]} a resolved {@code gl_FragData} left behind. */
    static String finish(String text) {
        return text.replaceAll("\u0002\\s*\\[\\s*\\d+[uU]?\\s*\\]", "");
    }

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>(s.length() / 3);
        int i = 0, n = s.length();
        boolean lineStart = true;
        while (i < n) {
            char c = s.charAt(i);
            int start = i;
            if (c == '\n') {
                i++;
                out.add(new Tok('w', "\n"));
                lineStart = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                while (i < n && s.charAt(i) != '\n' && Character.isWhitespace(s.charAt(i))) i++;
                out.add(new Tok('w', s.substring(start, i)));
                continue;
            }
            if (c == '#' && lineStart) {
                while (i < n && s.charAt(i) != '\n') i++;
                out.add(new Tok('d', s.substring(start, i)));
                continue;
            }
            lineStart = false;
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                out.add(new Tok('i', s.substring(start, i)));
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.'
                        || ((s.charAt(i) == '+' || s.charAt(i) == '-') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E')))) i++;
                out.add(new Tok('n', s.substring(start, i)));
            } else {
                i++;
                out.add(new Tok('p', String.valueOf(c)));
            }
        }
        return out;
    }
}
