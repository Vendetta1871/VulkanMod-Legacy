package net.vulkanmod.pack.host;

import net.vulkanmod.pack.ir.IrPack;
import net.vulkanmod.pack.ir.TextureFormat;
import net.vulkanmod.pack.plan.FramePlan;

/**
 * The generated declarations in front of every pack program (D3, host-interface.md). A
 * program uses host names without declaring them; the bindings below are the host's and
 * may change without breaking packs.
 *
 * <p>Command-list passes use one descriptor set: binding 0 is the {@code Frame} block,
 * then one combined image sampler per input in the pass's {@code inputs} order, then one
 * storage image per {@code writes} entry. The {@code Pass} block is a push constant.
 * A storage image is named after its texture, or {@code <name>Out} when the pass also
 * samples that texture (read-while-write: the sampler reads the old image).</p>
 */
public final class Prelude {

    public static final int SET = 0;
    public static final int FRAME_BINDING = 0;
    /** Size of the {@code Pass} push-constant block. */
    public static final int PASS_BLOCK_SIZE = 16;

    private Prelude() {
    }

    /** Binding of input {@code i} of a pass. */
    public static int inputBinding(int i) {
        return FRAME_BINDING + 1 + i;
    }

    /** Binding of storage image {@code j} of a pass with {@code inputs} sampled inputs. */
    public static int writeBinding(int inputs, int j) {
        return FRAME_BINDING + 1 + inputs + j;
    }

    /** The prelude of one scheduled pass (fragment or compute). */
    public static String forStep(IrPack pack, FramePlan.Step step) {
        IrPack.Pass pass = step.pass();
        StringBuilder sb = new StringBuilder(4096);
        commonBlocks(sb, pack);
        inputs(sb, pack, step);
        for (int j = 0; j < pass.writes.size(); j++) {
            String name = pass.isCompute() ? storageName(pass, pass.writes.get(j)) : storageImage(pass.writes.get(j));
            storage(sb, pack, pass.writes.get(j), writeBinding(step.inputs().size(), j), name);
        }
        if (pass.isCompute()) {
            sb.append("layout(local_size_x = ").append(pass.localSize[0])
                    .append(", local_size_y = ").append(pass.localSize[1])
                    .append(", local_size_z = ").append(pass.localSize[2]).append(") in;\n");
        } else {
            sb.append("layout(location = 0) in vec2 vkm_texCoord;\n");
            if (step.combination()) {
                // The combination draws into the host image in Vulkan row order (the flip
                // point, D4); this keeps the bottom-left origin packs expect.
                sb.append("#define vkm_FragCoord vec4(gl_FragCoord.x, vkm_passSize.y - gl_FragCoord.y, gl_FragCoord.zw)\n");
            } else {
                sb.append("#define vkm_FragCoord gl_FragCoord\n");
            }
        }
        if (frameInstance(pack).isEmpty()) sb.append(HELPERS);
        sb.append("// ---- end of prelude ----\n");
        return sb.toString();
    }

    static void storage(StringBuilder sb, IrPack pack, String texture, int binding, String name) {
        TextureFormat format = pack.texture(texture).format;
        sb.append("layout(set = ").append(SET).append(", binding = ").append(binding).append(", ").append(format.glslQualifier)
                .append(") uniform ").append(isInteger(format) ? "uimage2D " : "image2D ").append(name).append(";\n");
    }

    /** GLSL name of a render texture a fragment or geometry program stores to, unrelated to its sampler. */
    public static String storageImage(String texture) {
        return "vkmImg_" + texture;
    }

    /** The prelude of a pass's own vertex program or its stages after that: the same blocks, which use the same bindings. */
    public static String forVertex(IrPack pack, FramePlan.Step step) {
        StringBuilder sb = new StringBuilder(2048);
        commonBlocks(sb, pack);
        inputs(sb, pack, step);
        for (int j = 0; j < step.pass().writes.size(); j++) {
            storage(sb, pack, step.pass().writes.get(j), writeBinding(step.inputs().size(), j), storageImage(step.pass().writes.get(j)));
        }
        sb.append("// ---- end of prelude ----\n");
        return sb.toString();
    }

    private static void inputs(StringBuilder sb, IrPack pack, FramePlan.Step step) {
        for (int i = 0; i < step.inputs().size(); i++) {
            FramePlan.Input in = step.inputs().get(i);
            String type = samplerType(pack, in);
            if (!in.isHost() && isInteger(pack.textures.get(in.image().texture()).format)) {
                type = "usampler2D";
            }
            sb.append("layout(set = ").append(SET).append(", binding = ").append(inputBinding(i))
                    .append(") uniform ").append(type).append(' ').append(in.name()).append(";\n");
        }
    }

    private static void commonBlocks(StringBuilder sb, IrPack pack) {
        sb.append("// ---- VulkanMod host prelude (generated) ----\n");
        sb.append(FrameBlock.LAYOUT.glslDeclaration(SET, FRAME_BINDING, frameInstance(pack)));
        sb.append("layout(push_constant) uniform vkm_PassBlock {\n")
                .append("    vec2 vkm_passSize;\n")
                .append("    int vkm_passIndex;\n")
                .append("    int vkm_isShadowPass;\n")
                .append("};\n");
        sb.append(Resources.of(pack.raw).declarations());
    }

    /** {@code renderer.frameInstance}: frontends whose shaders may reuse Frame's member names set it. */
    private static String frameInstance(IrPack pack) {
        return pack.renderer.has("frameInstance") ? pack.renderer.get("frameInstance").getAsString() : "";
    }

    /** Comparison samplers for the shadow depth under {@code shadow.hardwareFiltering}. */
    static String samplerType(IrPack pack, FramePlan.Input in) {
        if (in.isHost() && in.host().endsWith("Hw")) return "sampler2DShadow";
        boolean depth = "shadowtex0".equals(in.host()) || "shadowtex1".equals(in.host());
        if (in.isHost() && isIntegerShadowColor(pack, in.host())) return "usampler2D";
        return depth && pack.shadow != null && pack.shadow.hardwareFiltering ? "sampler2DShadow" : "sampler2D";
    }

    /** A {@code shadowcolor} with an integer format reads and writes as unsigned. */
    static boolean isIntegerShadowColor(IrPack pack, String name) {
        int index = IrPack.SHADOW_COLORS.indexOf(name);
        return index >= 0 && pack.shadow != null && pack.shadow.colorFormats[index].isInteger();
    }

    /** GLSL name of the storage image a compute pass writes for {@code texture}. */
    public static String storageName(IrPack.Pass pass, String texture) {
        return pass.inputs.contains(texture) ? texture + "Out" : texture;
    }

    /** Vertex stage of every full-screen fragment pass: one triangle, texcoord (0,0) bottom-left. */
    public static String hostVertexShader() {
        return """
                #version 450
                layout(location = 0) out vec2 vkm_texCoord;
                void main() {
                    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                    // Pack space: the positive viewport puts NDC y = -1 on row 0, the bottom.
                    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
                    vkm_texCoord = uv;
                }
                """;
    }

    /** Depth helpers, also for geometry programs (GeometryPrelude). */
    static String helpers() {
        return HELPERS;
    }

    private static boolean isInteger(TextureFormat f) {
        return f.isInteger();
    }

    /** Depth helpers over GL window depth (host-interface.md, "Helpers"). */
    private static final String HELPERS = """
            #define vkm_hostUV(uv) (vkm_rtEnabled != 0 ? vec2((uv).x, 1.0 - (uv).y) : (uv))
            float vkm_linearDepth(float d) {
                return gbufferProjection[3][2] / ((d * 2.0 - 1.0) + gbufferProjection[2][2]);
            }
            vec3 vkm_screenToView(vec2 uv, float d) {
                vec4 p = gbufferProjectionInverse * vec4(vec3(uv, d) * 2.0 - 1.0, 1.0);
                return p.xyz / p.w;
            }
            vec3 vkm_viewToScreen(vec3 p) {
                vec4 c = gbufferProjection * vec4(p, 1.0);
                return c.xyz / c.w * 0.5 + 0.5;
            }
            """;
}
