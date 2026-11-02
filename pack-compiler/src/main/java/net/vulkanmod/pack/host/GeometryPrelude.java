package net.vulkanmod.pack.host;

import net.vulkanmod.pack.ir.IrPack;
import net.vulkanmod.pack.ir.TextureFormat;
import net.vulkanmod.pack.plan.FramePlan;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The generated declarations of a geometry program (host-interface.md, "Vertex inputs"). A
 * geometry program replaces one vanilla shader and draws with its vertex format and per-draw
 * descriptors, so those declarations are taken from the vanilla source itself, moved to set 1.
 * Set 0 holds what all geometry programs share: the {@code Frame} block and the sampled textures.
 */
public final class GeometryPrelude {

    /** Descriptor set of the vanilla per-draw resources. */
    public static final int DRAW_SET = 1;

    private static final Pattern ATTRIBUTE =
            Pattern.compile("layout\\(location\\s*=\\s*(\\d+)\\)\\s*in\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern STRUCT = Pattern.compile("(?s)struct\\s+\\w+\\s*\\{.*?\\};");
    private static final Pattern BLOCK = Pattern.compile(
            "(?s)layout\\(((?:std\\d+,\\s*)?)binding\\s*=\\s*(\\d+)\\)\\s*(uniform|readonly buffer)\\s+(\\w+)\\s*\\{(.*?)\\}\\s*;");
    private static final Pattern SAMPLER =
            Pattern.compile("layout\\(binding\\s*=\\s*(\\d+)\\)\\s*uniform\\s+sampler2D\\s+(\\w+)\\s*;");

    /** Vanilla attribute names and the names programs use. */
    private static final Map<String, String> ATTRIBUTES = Map.of(
            "Position", "vaPosition", "Color", "vaColor", "VertexColor", "vaColor", "UV0", "vaUV0",
            "UV2", "vaUV2", "LightMapCoord", "vaUV2", "Normal", "vaNormal");
    /** Attributes a format lacks are constants (host-interface.md). */
    private static final Map<String, String> ATTRIBUTE_DEFAULTS = new TreeMap<>(Map.of(
            "vaColor", "vec4(1.0)", "vaUV0", "vec2(0.0)", "vaUV2", "vkm_light", "vaNormal", "vec3(0.0, 1.0, 0.0)",
            "at_tangent", "vec4(1.0, 0.0, 0.0, 1.0)", "mc_midTexCoord", "vaUV0", "mc_Entity", "vec4(-1.0, 0.0, 0.0, 0.0)",
            "at_midBlock", "vec4(0.0)"));

    private GeometryPrelude() {
    }

    /** Slots of the batched path's shadow matrices start here; the main pass uses the slots below. */
    public static final int SHADOW_SLOT_BASE = 16384;

    /** The vertex prelude of {@code g} drawing with the vanilla vertex shader {@code hostSource}. */
    public static String vertex(FramePlan plan, IrPack.Gbuffer g, String hostSource) {
        boolean shadow = g.name.equals("shadow");
        StringBuilder sb = shared(plan);
        StringBuilder drawBlocks = new StringBuilder();
        copyDraw(hostSource, drawBlocks);

        Map<String, Boolean> present = new TreeMap<>();
        Matcher a = ATTRIBUTE.matcher(hostSource);
        boolean batched = false;
        while (a.find()) {
            String name = ATTRIBUTES.get(a.group(3));
            if (a.group(3).equals("ChunkSlot")) {
                name = "vkm_chunkSlot";
                batched = true;
            } else if (name == null) {
                throw new IllegalArgumentException("unknown vanilla attribute: " + a.group(3));
            }
            present.put(name, true);
            sb.append("layout(location = ").append(a.group(1)).append(") in ").append(a.group(2)).append(' ')
                    .append(name).append(";\n");
        }
        if (batched) extendedAttributes(plan.vertexFlags, sb, present);
        for (Map.Entry<String, String> d : ATTRIBUTE_DEFAULTS.entrySet()) {
            if (!present.containsKey(d.getKey())) {
                sb.append("#define ").append(d.getKey()).append(' ').append(d.getValue()).append('\n');
            }
        }
        sb.append(drawBlocks);
        if (batched) {
            String slot = shadow ? "int(vkm_chunkSlot) + " + SHADOW_SLOT_BASE : "int(vkm_chunkSlot)";
            sb.append("#define vkm_mvp chunks[").append(slot).append("].mvp\n");
            sb.append("#define vkm_modelView chunks[").append(slot).append("].modelView\n");
        } else {
            sb.append("#define vkm_mvp MVP\n");
            sb.append("#define vkm_modelView ").append(hostSource.contains("ModelViewMat") ? "ModelViewMat" : "gbufferModelView")
                    .append('\n');
        }
        if (shadow) sb.append("#define VKM_SHADOW_PASS\n");
        sb.append("vec4 vkm_position() { return vkm_mvp * vec4(vaPosition, 1.0); }\n");
        sb.append(Prelude.helpers());
        sb.append("// ---- end of prelude ----\n");
        return sb.toString();
    }

    /** The attributes after the batched path's slot, in the stream order of {@code ChunkBatcher}. */
    private static void extendedAttributes(int flags, StringBuilder sb, Map<String, Boolean> present) {
        int location = 5;
        if ((flags & FramePlan.VERTEX_NORMAL) != 0) {
            sb.append("layout(location = ").append(location++).append(") in vec4 vkm_normal;\n");
            sb.append("#define vaNormal vkm_normal.xyz\n");
            present.put("vaNormal", true);
        }
        if ((flags & FramePlan.VERTEX_TANGENT) != 0) {
            sb.append("layout(location = ").append(location++).append(") in vec4 at_tangent;\n");
            present.put("at_tangent", true);
        }
        if ((flags & FramePlan.VERTEX_MID_TEX) != 0) {
            sb.append("layout(location = ").append(location++).append(") in vec2 mc_midTexCoord;\n");
            present.put("mc_midTexCoord", true);
        }
        if ((flags & FramePlan.VERTEX_BLOCK) != 0) {
            sb.append("layout(location = ").append(location++).append(") in uvec2 vkm_block;\n");
            sb.append("#define mc_Entity vec4(float(vkm_block.x), float(vkm_block.y >> 8), float(vkm_block.y & 255u), 1.0)\n");
            present.put("mc_Entity", true);
        }
        if ((flags & FramePlan.VERTEX_MID_BLOCK) != 0) {
            sb.append("layout(location = ").append(location++).append(") in vec4 vkm_midBlock;\n");
            sb.append("#define at_midBlock (vkm_midBlock * 127.0)\n");
            present.put("at_midBlock", true);
        }
    }

    /** The fragment prelude of {@code g} drawing with the vanilla fragment shader {@code hostSource}. */
    public static String fragment(FramePlan plan, IrPack.Gbuffer g, String hostSource) {
        StringBuilder sb = shared(plan);
        copyDraw(hostSource, sb);
        for (String target : g.targets) {
            boolean integer = Prelude.isIntegerShadowColor(plan.pack, target)
                    || (plan.pack.texture(target) != null && isInteger(plan.pack.texture(target).format));
            sb.append("layout(location = ").append(plan.attachment(target)).append(") out ")
                    .append(integer ? "uvec4 " : "vec4 ").append("vkmOut_").append(target).append(";\n");
            // A name that is also sampled somewhere keeps meaning the sampler.
            if (!isInput(plan, target)) sb.append("#define ").append(target).append(" vkmOut_").append(target).append('\n');
        }
        sb.append("#define vkm_FragCoord gl_FragCoord\n");
        if (g.name.equals("shadow")) sb.append("#define VKM_SHADOW_PASS\n");
        if (hostSource.contains("AlphaFunc")) sb.append("#define VKM_ALPHA_TEST\n").append(ALPHA_TEST);
        sb.append(Prelude.helpers());
        sb.append("// ---- end of prelude ----\n");
        return sb.toString();
    }

    /**
     * The prelude of a stage between vertex and fragment. It has no vertex format and no per-draw
     * blocks, which belong to one stage each; samplers are visible to all.
     */
    public static String stage(FramePlan plan, IrPack.Gbuffer g, String hostFragment) {
        StringBuilder sb = shared(plan);
        copySamplers(hostFragment, sb);
        if (g.name.equals("shadow")) sb.append("#define VKM_SHADOW_PASS\n");
        sb.append(Prelude.helpers());
        sb.append("// ---- end of prelude ----\n");
        return sb.toString();
    }

    private static StringBuilder shared(FramePlan plan) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("// ---- VulkanMod host prelude (generated) ----\n");
        sb.append(FrameBlock.LAYOUT.glslDeclaration(Prelude.SET, Prelude.FRAME_BINDING, ""));
        var inputs = plan.geometryInputs.get(FramePlan.OPAQUE);
        for (int i = 0; i < inputs.size(); i++) {
            FramePlan.Input in = inputs.get(i);
            boolean integer = !in.isHost() && isInteger(plan.pack.textures.get(in.image().texture()).format);
            sb.append("layout(set = ").append(Prelude.SET).append(", binding = ").append(Prelude.inputBinding(i))
                    .append(") uniform ").append(integer ? "usampler2D " : Prelude.samplerType(plan.pack, in)).append(' ')
                    .append(in.name()).append(";\n");
        }
        for (int j = 0; j < plan.geometryWrites.size(); j++) {
            Prelude.storage(sb, plan.pack, plan.geometryWrites.get(j), Prelude.writeBinding(inputs.size(), j),
                    Prelude.storageImage(plan.geometryWrites.get(j)));
        }
        sb.append(Resources.of(plan.pack.raw).declarations());
        sb.append("""
                layout(push_constant) uniform vkm_DrawBlock {
                    vec4 vkm_tint;
                    vec4 entityColor;
                    ivec2 vkm_light;
                    int entityId;
                    int blockEntityId;
                    int renderStage;
                    int currentRenderedItemId;
                    vec4 vkm_material;
                };
                """);
        return sb;
    }

    private static boolean isInput(FramePlan plan, String target) {
        for (FramePlan.Input in : plan.geometryInputs.get(FramePlan.OPAQUE)) {
            if (in.name().equals(target)) return true;
        }
        return false;
    }

    /** The vanilla shader's blocks and samplers, in set {@link #DRAW_SET}, its samplers under their pack names. */
    private static void copyDraw(String source, StringBuilder sb) {
        Matcher st = STRUCT.matcher(source);
        while (st.find()) sb.append(st.group()).append('\n');
        Matcher b = BLOCK.matcher(source);
        while (b.find()) {
            sb.append("layout(").append(b.group(1)).append("set = ").append(DRAW_SET).append(", binding = ")
                    .append(b.group(2)).append(") ").append(b.group(3)).append(' ').append(b.group(4)).append(" {")
                    .append(b.group(5)).append("};\n");
        }
        copySamplers(source, sb);
    }

    private static void copySamplers(String source, StringBuilder sb) {
        Matcher s = SAMPLER.matcher(source);
        while (s.find()) {
            String name = switch (s.group(2)) {
                case "Sampler0" -> "gtexture";
                case "Sampler2" -> "lightmap";
                default -> throw new IllegalArgumentException("unknown vanilla sampler: " + s.group(2));
            };
            sb.append("layout(set = ").append(DRAW_SET).append(", binding = ").append(s.group(1))
                    .append(") uniform sampler2D ").append(name).append(";\n");
            sb.append(name.equals("lightmap") ? "#define VKM_LIGHTMAP\n" : "#define VKM_GTEXTURE\n");
        }
    }

    private static boolean isInteger(TextureFormat f) {
        return f.isInteger();
    }

    /** The live GL alpha test of the draw; {@code AlphaFunc} 0 means off. */
    private static final String ALPHA_TEST = """
            void vkm_alphaTest(float a) {
                int f = int(AlphaFunc);
                if (f == 0 || f == 519) return;
                bool keep = f == 516 ? a > AlphaRef : f == 518 ? a >= AlphaRef : f == 513 ? a < AlphaRef
                        : f == 515 ? a <= AlphaRef : f == 514 ? abs(a - AlphaRef) < 0.0039
                        : f == 517 ? abs(a - AlphaRef) >= 0.0039 : false;
                if (!keep) discard;
            }
            """;
}
