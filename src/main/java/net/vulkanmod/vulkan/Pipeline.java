package net.vulkanmod.vulkan;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.vulkanmod.vulkan.memory.UniformBuffers;
import net.vulkanmod.vulkan.shader.Field;
import net.vulkanmod.vulkan.shader.PushConstant;
import net.vulkanmod.vulkan.shader.UBO;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

import static net.vulkanmod.vulkan.ShaderSPIRVUtils.*;
import static net.vulkanmod.vulkan.Vulkan.getSwapChainImages;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

/**
 * A graphics pipeline bound to a vertex format, with per-state pipeline variants
 * (blend/depth/logic-op/color-mask/cull) and per-frame descriptor pools.
 *
 * <p>Pipeline layout (UBOs, samplers, push constants) is described by the shader's
 * JSON, in the same format the original VulkanMod used.
 */
public class Pipeline {
    public static final BlendState DEFAULT_BLEND_STATE = defaultBlend();
    public static final DepthState DEFAULT_DEPTH_STATE = defaultDepthState();
    public static final LogicOpState DEFAULT_LOGICOP_STATE = new LogicOpState(false, 0);
    public static final ColorMask DEFAULT_COLORMASK = new ColorMask(true, true, true, true);
    public static final BlendState NO_BLEND_STATE = new BlendState(false, 0, 0, 0, 0);

    private static final VkDevice device = Vulkan.getDevice();

    // Driver pipeline cache persisted across launches (config/vulkanmod/pipeline-cache.vkc).
    // These must be declared BEFORE pipelineCache: its initializer (createPipelineCache ->
    // loadPipelineCacheData) reads them, and static initializers run in declaration order -
    // with the path declared later it was still null there, so the file was never even
    // opened (the real root cause of N7, on top of the wrong header offsets).
    private static final java.nio.file.Path PIPELINE_CACHE_FILE =
            java.nio.file.Paths.get("config", "vulkanmod", "pipeline-cache.vkc");

    // Warm-up sidecar (H11): the state keys every pipeline reached, so the next launch
    // pre-creates those variants during loading instead of at draw time. R11/R12: the
    // file carries a version header, keys age out when a session does not reach them,
    // each pipeline keeps at most MAX_VARIANTS_PER_PIPELINE entries, and every key is
    // range-checked before it reaches vkCreateGraphicsPipelines.
    private static final java.nio.file.Path VARIANTS_FILE =
            java.nio.file.Paths.get("config", "vulkanmod", "pipeline-variants.txt");
    private static final String VARIANTS_HEADER = "# vulkanmod pipeline-variants v2";
    private static final int MAX_VARIANT_AGE = 8;
    private static final int MAX_VARIANTS_PER_PIPELINE = 128;
    // Exact text of the loaded file; the save skips the write while it still matches.
    private static String loadedVariantsText;
    // recordId -> (state key -> age in sessions). Declared after loadedVariantsText:
    // loadRecordedVariants assigns it.
    private static final Map<String, Map<Long, Integer>> RECORDED_VARIANTS = loadRecordedVariants();

    /**
     * Trailer appended to the cache file: "VMPC" + blob length, little-endian (N13). The
     * atomic rename already keeps a torn write out of the file; the trailer additionally
     * catches a zero-filled file (the rename survived a power loss but the data blocks
     * did not) before the blob reaches the driver.
     */
    private static final int CACHE_TRAILER_MAGIC = 0x43504D56;

    /**
     * Set whenever a variant was created; the blob is written once at shutdown instead
     * of synchronously on every miss, which hitched the frame during warm-up (N13).
     */
    private static boolean cacheDirty;

    // R12: false when the driver cache started empty. Then every created variant grows
    // the blob (so the save must run even without a draw-time miss), and pre-warm only
    // replays the keys reached in the last session instead of the whole aged union.
    private static boolean pipelineCacheWarm;

    private static final long pipelineCache = createPipelineCache();
    private static final int imagesSize = getSwapChainImages().size();

    /** Shared driver cache handle, so compute pipelines land in the same persisted blob. */
    static long getPipelineCache() {
        return pipelineCache;
    }

    // L1: whether the optional R8G8B8_SNORM can be used for the NORMAL attribute.
    private static final boolean R8G8B8_NORMAL_SUPPORTED =
            Vulkan.vertexBufferFormatSupported(VK_FORMAT_R8G8B8_SNORM);

    private String path;
    // R12: id under which this pipeline records its reached keys. Two pipelines that
    // share a shader path ("clouds", "terrain") get distinct ids so their recordings do
    // not pre-warm each other's union.
    private final String recordId;
    // R12: state keys reached at draw time this session; saved back with age 0.
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet reachedKeys =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private long descriptorSetLayout;
    private long pipelineLayout;
    // Graphics pipeline variants keyed by the packed state long (see packStateKey):
    // allocation-free per-draw lookup instead of PipelineState object churn.
    private Map<Long, Long> graphicsPipelines = new HashMap<>();
    private long lastStateKey = Long.MIN_VALUE;
    private long lastStateHandle;
    private VertexFormat vertexFormat;

    private final long[] descriptorPools = new long[imagesSize];
    // Descriptor set cached per frame slot, valid while the bound texture triple and the
    // UBO buffer generation are unchanged (the UBO bindings are dynamic, so per-draw data
    // changes do not invalidate them). Cleared on pool reset/resize.
    private final long[] cachedSets = new long[imagesSize];
    private final VulkanImage[] cachedTex0 = new VulkanImage[imagesSize];
    private final VulkanImage[] cachedTex1 = new VulkanImage[imagesSize];
    private final VulkanImage[] cachedTex2 = new VulkanImage[imagesSize];
    private final int[] cachedGen = new int[imagesSize];
    private int descriptorCount = 500;
    // Pools replaced by a mid-frame resize: {long[] handles, Integer framesAged}. Static
    // so one per-frame pass can age every pipeline's pools, including pipelines that stop
    // being bound before their superseded pools would otherwise be retired.
    private static final java.util.ArrayList<Object[]> pendingPoolDestruction = new java.util.ArrayList<>();

    private final List<UBO> UBOs = new ArrayList<>();
    // R13: per-draw needsUpload results, reused by the upload loop.
    private boolean[] uboUpload = new boolean[0];
    private final List<String> samplers = new ArrayList<>();
    private final List<String> ssbos = new ArrayList<>();
    private final PushConstant pushConstant = new PushConstant();

    private Consumer<Integer> resetDescriptorPoolFun = defaultResetDPFun();

    private long vertShaderModule = 0;
    private long fragShaderModule = 0;

    /** Every live Pipeline, so Vulkan.cleanUp can destroy them all at teardown. */
    private static final java.util.ArrayList<Pipeline> INSTANCES = new java.util.ArrayList<>();

    /** Destroys every pipeline's shader modules, descriptor pools, variants and layout. */
    public static void cleanUpAll() {
        for(Pipeline pipeline : INSTANCES) {
            pipeline.cleanUp();
        }
        INSTANCES.clear();

        // The shared cache is only referenced at pipeline-creation time, so it goes last.
        vkDestroyPipelineCache(device, pipelineCache, null);
    }

    public Pipeline(VertexFormat vertexFormat, String path) {
        this(vertexFormat, path, path);
    }

    /** @param recordId key of the warm-up recording; distinct for pipelines sharing a shader. */
    public Pipeline(VertexFormat vertexFormat, String path, String recordId) {
        this.path = path;
        this.recordId = recordId;

        this.vertexFormat = vertexFormat;

        createDescriptorSetLayout(path);
        createPipelineLayout();
        long defaultKey = packStateKey(DEFAULT_BLEND_STATE, DEFAULT_DEPTH_STATE.depthTest,
                DEFAULT_DEPTH_STATE.depthMask, DEFAULT_DEPTH_STATE.function, DEFAULT_LOGICOP_STATE,
                DEFAULT_COLORMASK.colorMask, VRenderSystem.cull, VRenderSystem.topology);
        graphicsPipelines.put(defaultKey, createGraphicsPipeline(vertexFormat, unpackStateKey(defaultKey)));
        lastStateKey = defaultKey;
        lastStateHandle = graphicsPipelines.get(defaultKey);

        // Warm-up (H11): the variants this pipeline reached at draw time in the previous
        // sessions are created here, during loading, instead of mid-frame. R11: a stored
        // key is range-checked before it reaches vkCreateGraphicsPipelines - a corrupted
        // or hand-edited file must not cause invalid usage. R12: after a cold driver
        // cache only the keys reached in the last session (age 0) are replayed, so a
        // driver update does not compile the whole aged union on the main thread.
        Map<Long, Integer> recorded = RECORDED_VARIANTS.get(recordId);
        if (recorded != null) {
            int prewarmed = 0;
            for (java.util.Iterator<Map.Entry<Long, Integer>> it = recorded.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Integer> entry = it.next();
                long key = entry.getKey();
                if (graphicsPipelines.containsKey(key)) {
                    continue;
                }
                if (!pipelineCacheWarm && entry.getValue() > 0) {
                    continue;
                }
                if (!isValidStateKey(key)) {
                    it.remove();
                    discardVariantsFile();
                    net.vulkanmod.Initializer.LOGGER.warn("[pipeline] {} dropped invalid variant key {}", recordId, key);
                    continue;
                }
                try {
                    graphicsPipelines.put(key, createGraphicsPipeline(vertexFormat, unpackStateKey(key)));
                    ++prewarmed;
                } catch (RuntimeException e) {
                    // The recording is unusable for this build/driver: drop it now and
                    // rewrite the file without it (R11), the variant is created on demand.
                    it.remove();
                    discardVariantsFile();
                }
            }
            if (prewarmed > 0) {
                net.vulkanmod.Initializer.LOGGER.info("[pipeline] {} pre-warmed {} variant(s)", recordId, prewarmed);
            }
        }

        createDescriptorPool(descriptorCount);

        INSTANCES.add(this);
    }

    /**
     * Packs the full dynamic pipeline state (blend, depth, logic-op, color-mask, cull,
     * topology) into a long map key, so a per-draw state lookup allocates nothing.
     * Field widths: blend enabled 1 bit, four Vulkan blend factors (0..15) 4 bits each,
     * depth test/mask 1 bit each, Vulkan compare op (0..8) 4 bits, cull 1 bit, logic-op
     * enabled 1 bit, logic op (0..16) 5 bits, color mask 4 bits, topology (0..5) 3 bits.
     */
    public static long packStateKey(BlendState blend, boolean depthTest, boolean depthMask,
                                    int vkDepthFunc, LogicOpState logicOp,
                                    int colorMask, boolean cull, int topology) {
        long key = 0;
        if (blend.enabled) key |= 1L;
        key |= (long) blend.srcRgbFactor << 1;
        key |= (long) blend.dstRgbFactor << 5;
        key |= (long) blend.srcAlphaFactor << 9;
        key |= (long) blend.dstAlphaFactor << 13;
        if (depthTest) key |= 1L << 17;
        if (depthMask) key |= 1L << 18;
        key |= (long) vkDepthFunc << 19;
        if (cull) key |= 1L << 23;
        if (logicOp.enabled) key |= 1L << 24;
        key |= (long) logicOp.getLogicOp() << 25;
        key |= (long) colorMask << 30;
        key |= (long) topology << 34;
        return key;
    }

    /** Rebuilds the state objects from a packed key, for a cache-miss pipeline creation. */
    private static PipelineState unpackStateKey(long key) {
        boolean blendEnabled = (key & 1L) != 0;
        BlendState blend = new BlendState(blendEnabled,
                (int) (key >>> 1) & 0xF, (int) (key >>> 5) & 0xF,
                (int) (key >>> 9) & 0xF, (int) (key >>> 13) & 0xF);
        DepthState depth = new DepthState((key & (1L << 17)) != 0, (key & (1L << 18)) != 0,
                (int) (key >>> 19) & 0xF, true);
        LogicOpState logicOp = new LogicOpState((key & (1L << 24)) != 0, (int) (key >>> 25) & 0x1F);
        ColorMask colorMask = new ColorMask((int) (key >>> 30) & 0xF);
        return new PipelineState(blend, depth, logicOp, colorMask,
                (key & (1L << 23)) != 0, (int) (key >>> 34) & 0x7);
    }

    /** Handle of the pipeline variant for a packed state key, creating it on first use. */
    public long getHandle(long stateKey) {
        // R12: every key reached at draw time is saved back with age 0, so a variant
        // that keeps being used never ages out of the recording.
        reachedKeys.add(stateKey);
        if (stateKey == lastStateKey) {
            return lastStateHandle;
        }
        Long handle = graphicsPipelines.get(stateKey);
        if (handle == null) {
            handle = createGraphicsPipeline(vertexFormat, unpackStateKey(stateKey));
            graphicsPipelines.put(stateKey, handle);
            // N13: the driver blob is several MB - writing it here hitched the frame on
            // every miss. Just mark it; Vulkan.cleanUp flushes the cache at shutdown.
            cacheDirty = true;
        }
        lastStateKey = stateKey;
        lastStateHandle = handle;
        return handle;
    }

    private long createGraphicsPipeline(VertexFormat vertexFormat, PipelineState state) {
        this.vertexFormat = vertexFormat;

        try(MemoryStack stack = stackPush()) {

            // Compile the GLSL shaders into SPIR-V at runtime using org.lwjgl.util.shaderc.

            if(vertShaderModule == 0 || fragShaderModule == 0) {
                SPIRV vertShaderSPIRV = compileShaderFile(path + ".vsh", ShaderKind.VERTEX_SHADER);
                SPIRV fragShaderSPIRV = compileShaderFile(path + ".fsh", ShaderKind.FRAGMENT_SHADER);

                vertShaderModule = createShaderModule(vertShaderSPIRV.bytecode());
                fragShaderModule = createShaderModule(fragShaderSPIRV.bytecode());

                // vkCreateShaderModule copies the SPIR-V, so the shaderc results can go.
                vertShaderSPIRV.free();
                fragShaderSPIRV.free();
            }

            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.create(2);

            VkPipelineShaderStageCreateInfo vertShaderStageInfo = shaderStages.get(0);

            vertShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            vertShaderStageInfo.stage(VK_SHADER_STAGE_VERTEX_BIT);
            vertShaderStageInfo.module(vertShaderModule);
            vertShaderStageInfo.pName(entryPoint);

            VkPipelineShaderStageCreateInfo fragShaderStageInfo = shaderStages.get(1);

            fragShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            fragShaderStageInfo.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            fragShaderStageInfo.module(fragShaderModule);
            fragShaderStageInfo.pName(entryPoint);

            // ===> VERTEX STAGE <===

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.create();
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            vertexInputInfo.pVertexBindingDescriptions(getBindingDescription(vertexFormat));
            vertexInputInfo.pVertexAttributeDescriptions(getAttributeDescriptions(vertexFormat));

            // ===> ASSEMBLY STAGE <===

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.create();
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(state.topology);
            inputAssembly.primitiveRestartEnable(false);

            // ===> VIEWPORT & SCISSOR

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.create();
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);

            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            // ===> RASTERIZATION STAGE <===

            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.create();
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.depthClampEnable(false);
            rasterizer.rasterizerDiscardEnable(false);
            rasterizer.polygonMode(VK_POLYGON_MODE_FILL);
            rasterizer.lineWidth(1.0f);

            if(state.cullState) rasterizer.cullMode(VK_CULL_MODE_BACK_BIT);
            else rasterizer.cullMode(VK_CULL_MODE_NONE);

            // The viewport is flipped (negative height); Vulkan determines primitive
            // orientation independently of the viewport sign, so the counter-clockwise
            // winding of GL quads stays the front face.
            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            rasterizer.depthBiasEnable(true);

            // ===> MULTISAMPLING <===

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.create();
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            multisampling.sampleShadingEnable(false);
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // ===> DEPTH TEST <===

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.create();
            depthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            depthStencil.depthTestEnable(state.depthState.depthTest);
            depthStencil.depthWriteEnable(state.depthState.depthMask);
            depthStencil.depthCompareOp(state.depthState.function);
            depthStencil.depthBoundsTestEnable(false);
            depthStencil.minDepthBounds(0.0f); // Optional
            depthStencil.maxDepthBounds(1.0f); // Optional
            depthStencil.stencilTestEnable(false);

            // ===> COLOR BLENDING <===

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.create(1);
            colorBlendAttachment.colorWriteMask(state.colorMask.colorMask);

            if(state.blendState.enabled) {
                colorBlendAttachment.blendEnable(true);
                colorBlendAttachment.srcColorBlendFactor(state.blendState.srcRgbFactor);
                colorBlendAttachment.dstColorBlendFactor(state.blendState.dstRgbFactor);
                colorBlendAttachment.colorBlendOp(VK_BLEND_OP_ADD);
                colorBlendAttachment.srcAlphaBlendFactor(state.blendState.srcAlphaFactor);
                colorBlendAttachment.dstAlphaBlendFactor(state.blendState.dstAlphaFactor);
                colorBlendAttachment.alphaBlendOp(VK_BLEND_OP_ADD);
            }
            else {
                colorBlendAttachment.blendEnable(false);
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.create();
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlending.logicOpEnable(state.logicOpState.enabled);
            colorBlending.logicOp(state.logicOpState.getLogicOp());
            colorBlending.pAttachments(colorBlendAttachment);
            colorBlending.blendConstants(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));

            // ===> DYNAMIC STATES <===

            VkPipelineDynamicStateCreateInfo dynamicStates = VkPipelineDynamicStateCreateInfo.create();
            dynamicStates.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);
            // Line topologies honour glLineWidth through the dynamic line-width state
            // (the wideLines device feature is enabled for this); triangle pipelines keep
            // the static width so they never need a vkCmdSetLineWidth before drawing.
            if (state.topology == VK_PRIMITIVE_TOPOLOGY_LINE_LIST || state.topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP) {
                dynamicStates.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT,
                        VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_LINE_WIDTH));
            } else {
                dynamicStates.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT,
                        VK_DYNAMIC_STATE_SCISSOR));
            }

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.create(1);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            pipelineInfo.pStages(shaderStages);
            pipelineInfo.pVertexInputState(vertexInputInfo);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.pDynamicState(dynamicStates);
            pipelineInfo.layout(pipelineLayout);
            // B4: dynamic rendering - the pipeline names its attachment formats instead
            // of a render pass. Scene and display targets share the formats, so one
            // pipeline serves both phases.
            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                    .colorAttachmentCount(1)
                    .pColorAttachmentFormats(stack.ints(FrameTargets.COLOR_FORMAT))
                    .depthAttachmentFormat(FrameTargets.depthFormat())
                    .stencilAttachmentFormat(VK_FORMAT_UNDEFINED);
            pipelineInfo.pNext(renderingInfo.address());
            pipelineInfo.renderPass(VK_NULL_HANDLE);
            pipelineInfo.subpass(0);
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE);
            pipelineInfo.basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);

            if(vkCreateGraphicsPipelines(device, pipelineCache, pipelineInfo, null, pGraphicsPipeline) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create graphics pipeline");
            }

            // R12: on a warm driver cache a pre-warm creation hits the blob and grows
            // nothing (a draw-time miss marks dirty in getHandle); only when the cache
            // started cold does every creation add to the blob that must be saved.
            if (!pipelineCacheWarm) {
                cacheDirty = true;
            }
            return pGraphicsPipeline.get(0);
        }
    }

    private void createDescriptorSetLayout(String path) {
        JsonObject jsonObject;

        try (InputStream stream = Pipeline.class.getResourceAsStream("/assets/vulkanmod/shaders/" + path + ".json")) {
            if (stream == null) {
                throw new RuntimeException("Missing pipeline descriptor: shaders/" + path + ".json");
            }
            jsonObject = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read pipeline descriptor: shaders/" + path + ".json", e);
        }

        JsonArray jsonUbos = getArrayOrNull(jsonObject, "UBOs");
        JsonArray jsonSamplers = getArrayOrNull(jsonObject, "samplers");
        JsonArray jsonSsbos = getArrayOrNull(jsonObject, "SSBOs");
        JsonArray jsonPushConstants = getArrayOrNull(jsonObject, "PushConstants");

        int bindingsSize = 0; //UBOs + sampler

        if (jsonUbos != null) {

            for (JsonElement jsonelement : jsonUbos) {
                this.parseUboNode(jsonelement);
            }
            bindingsSize = jsonUbos.size();
        }

        if(jsonSamplers != null) {

            for (JsonElement jsonelement : jsonSamplers) {
                this.parseSamplerNode(jsonelement);
            }
            bindingsSize += jsonSamplers.size();
        }

        if(jsonSsbos != null) {

            for (JsonElement jsonelement : jsonSsbos) {
                this.parseSsboNode(jsonelement);
            }
            bindingsSize += jsonSsbos.size();
        }

        if(jsonPushConstants != null) {

            this.parsePushConstantNode(jsonPushConstants);
        }

        try(MemoryStack stack = stackPush()) {

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.create(bindingsSize);

            int i = 0;
            for(UBO ubo : this.UBOs) {
                VkDescriptorSetLayoutBinding uboLayoutBinding = bindings.get(ubo.getBinding());
                uboLayoutBinding.binding(ubo.getBinding());
                uboLayoutBinding.descriptorCount(1);
                uboLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC);
                uboLayoutBinding.pImmutableSamplers(null);
                uboLayoutBinding.stageFlags(ubo.getFlags());

                ++i;
            }

            for(String s : this.samplers) {
                VkDescriptorSetLayoutBinding samplerLayoutBinding = bindings.get(i);
                samplerLayoutBinding.binding(i);
                samplerLayoutBinding.descriptorCount(1);
                samplerLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                samplerLayoutBinding.pImmutableSamplers(null);
                samplerLayoutBinding.stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS);

                ++i;
            }

            for(String s : this.ssbos) {
                VkDescriptorSetLayoutBinding ssboLayoutBinding = bindings.get(i);
                ssboLayoutBinding.binding(i);
                ssboLayoutBinding.descriptorCount(1);
                ssboLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                ssboLayoutBinding.pImmutableSamplers(null);
                ssboLayoutBinding.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);

                ++i;
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.create();
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(bindings);

            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);

            if(vkCreateDescriptorSetLayout(device, layoutInfo, null, pDescriptorSetLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor set layout");
            }
            descriptorSetLayout = pDescriptorSetLayout.get(0);
        }
    }

    private static JsonArray getArrayOrNull(JsonObject object, String member) {
        return object.has(member) ? object.getAsJsonArray(member) : null;
    }

    private void createPipelineLayout() {
        try(MemoryStack stack = stackPush()) {
            // ===> PIPELINE LAYOUT CREATION <===

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.create();
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            pipelineLayoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));

            if(pushConstant.getSize() > 0) {
                VkPushConstantRange.Buffer pushConstantRange = VkPushConstantRange.create(1);
                pushConstantRange.size(pushConstant.getSize());
                pushConstantRange.offset(0);
                pushConstantRange.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);

                pipelineLayoutInfo.pPushConstantRanges(pushConstantRange);
            }

            LongBuffer pPipelineLayout = stack.longs(VK_NULL_HANDLE);

            if(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline layout");
            }

            pipelineLayout = pPipelineLayout.get(0);
        }
    }

    private void createDescriptorPool(int descriptorCount) {

        try(MemoryStack stack = stackPush()) {
            // One pool size entry per descriptor TYPE. Duplicate entries of the same
            // type are not summed by the driver, so each type's count must cover every
            // binding of that type in every set: a set takes UBOs.size() dynamic UBO
            // descriptors, so the per-type count is descriptorCount * bindings (N4).
            int types = (UBOs.size() > 0 ? 1 : 0) + (samplers.size() > 0 ? 1 : 0)
                    + (ssbos.size() > 0 ? 1 : 0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.create(types);

            int i = 0;
            if (UBOs.size() > 0) {
                poolSizes.get(i++).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                        .descriptorCount(descriptorCount * UBOs.size());
            }
            if (samplers.size() > 0) {
                poolSizes.get(i++).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(descriptorCount * samplers.size());
            }
            if (ssbos.size() > 0) {
                poolSizes.get(i++).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(descriptorCount * ssbos.size());
            }

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.create();
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(descriptorCount);

            LongBuffer pDescriptorPool = stack.mallocLong(1);

            for(i = 0; i < imagesSize; ++i) {
                if(vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create descriptor pool");
                }
                descriptorPools[i] = pDescriptorPool.get(0);
            }
        }
    }

    public void cleanUp() {
        vkDestroyShaderModule(device, vertShaderModule, null);
        vkDestroyShaderModule(device, fragShaderModule, null);

        for(long descriptorPool : descriptorPools)
            vkDestroyDescriptorPool(device, descriptorPool, null);

        for(Object[] entry : pendingPoolDestruction) {
            for(long pool : (long[]) entry[0]) {
                vkDestroyDescriptorPool(device, pool, null);
            }
        }
        pendingPoolDestruction.clear();

        this.resetDescriptorPoolFun = i -> {};

        graphicsPipelines.forEach((state, pipeline) -> {
            vkDestroyPipeline(device, pipeline, null);
        });
        graphicsPipelines.clear();

        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
    }

    /**
     * Uploads this pipeline's UBO data into the frame slot's ring buffer and binds the
     * descriptor set with the resulting dynamic offsets.
     *
     * <p>The UBO bindings are {@code UNIFORM_BUFFER_DYNAMIC} and point at the whole
     * per-frame UBO buffer, so the descriptor set only depends on (frame slot, bound
     * textures, buffer generation) - not on the per-draw data. It is therefore allocated
     * and written once per texture change instead of once per draw, and the per-draw
     * cost collapses to the UBO memcpy plus {@code vkCmdBindDescriptorSets} with the
     * fresh offsets.</p>
     */
    public void bindDescriptorSets(VkCommandBuffer commandBuffer, int frame, UniformBuffers uniformBuffers) {

        try(MemoryStack stack = stackPush()) {

            // vkCmdBindDescriptorSets takes the dynamic offsets as uint32_t entries
            // (NOT VkDeviceSize), so the offsets are an IntBuffer.
            IntBuffer offsets = stack.callocInt(UBOs.size());

            // Reserve room for every UBO of this pipeline before uploading any of them:
            // a resize in the middle of the loop would replace the ring buffer and bump
            // the generation after the first UBO already went into the old one, so its
            // dynamic offset would read uninitialized memory in the new buffer (N6).
            long window = uniformBuffers.getResetCount();
            int generation = uniformBuffers.getGeneration();
            // R13: needsUpload is a memcmp; evaluate it once per UBO per draw instead of
            // once per loop, and hand the result to the upload loop.
            if (uboUpload.length < UBOs.size()) {
                uboUpload = new boolean[UBOs.size()];
            }
            int total = 0;
            for (int i = 0; i < UBOs.size(); ++i) {
                UBO ubo = UBOs.get(i);
                ubo.update();
                // H10: an UBO whose data is byte-identical to the upload it already made
                // in this frame window reuses that ring offset - no new slot, no memcpy.
                uboUpload[i] = ubo.needsUpload(window, generation);
                if (uboUpload[i]) {
                    total += uniformBuffers.alignedSize(ubo.getBuffer().remaining());
                }
            }
            uniformBuffers.ensureCapacity(total);
            if (uniformBuffers.getGeneration() != generation) {
                // The resize dropped every recorded offset, so the skipped uploads must
                // be counted too before the next loop may not resize mid-way (N6 again).
                generation = uniformBuffers.getGeneration();
                total = 0;
                for(UBO ubo : UBOs) {
                    total += uniformBuffers.alignedSize(ubo.getBuffer().remaining());
                }
                uniformBuffers.ensureCapacity(total);
                generation = uniformBuffers.getGeneration();
                java.util.Arrays.fill(uboUpload, true);
            }

            for (int i = 0; i < UBOs.size(); ++i) {
                UBO ubo = UBOs.get(i);
                int offset;
                if (uboUpload[i]) {
                    offset = uniformBuffers.uploadUBO(ubo.getBuffer(), frame);
                    ubo.recordUpload(window, generation, offset);
                } else {
                    offset = ubo.getLastOffset();
                }
                // The dynamic-offset array is indexed by the order of the dynamic
                // bindings in the layout (ascending binding number, contiguous from 0).
                offsets.put(ubo.getBinding(), offset);
            }

            long descriptorSet = getDescriptorSet(frame, uniformBuffers);

            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipelineLayout, 0, stack.longs(descriptorSet), offsets);
        }
    }

    private long getDescriptorSet(int frame, UniformBuffers uniformBuffers) {

        // Pipelines without samplers must not re-allocate their set on every unrelated
        // texture change: texture0 stays null, so the cache key ignores the bound
        // texture entirely (N21).
        VulkanImage texture0 = null, texture1 = null, texture2 = null;
        if (!samplers.isEmpty()) {
            texture0 = VTextureSelector.getBoundTexture();
            if (texture0 == null) texture0 = VTextureSelector.getWhiteTexture();
            if (samplers.size() > 1) {
                texture1 = VTextureSelector.getLightTexture();
                if (texture1 == null) texture1 = VTextureSelector.getWhiteTexture();
            }
            if (samplers.size() > 2) {
                texture2 = VTextureSelector.getOverlayTexture();
                if (texture2 == null) texture2 = VTextureSelector.getWhiteTexture();
            }

            // Keep the per-draw layout guarantee: a cached set records
            // SHADER_READ_ONLY_OPTIMAL, so every bound image must be in it.
            texture0.readOnlyLayout();
            if (texture1 != null) texture1.readOnlyLayout();
            if (texture2 != null) texture2.readOnlyLayout();
        }

        // A freed image must never reach a descriptor write (its view is destroyed or
        // scheduled for it) and a set cached against one must never be reused (N15).
        if (freed(texture0)) texture0 = VTextureSelector.getWhiteTexture();
        if (freed(texture1)) texture1 = VTextureSelector.getWhiteTexture();
        if (freed(texture2)) texture2 = VTextureSelector.getWhiteTexture();

        int generation = uniformBuffers.getGeneration();
        long set = cachedSets[frame];
        if (set != 0 && cachedTex0[frame] == texture0 && cachedTex1[frame] == texture1
                && cachedTex2[frame] == texture2 && cachedGen[frame] == generation
                && !freed(cachedTex0[frame]) && !freed(cachedTex1[frame])
                && !freed(cachedTex2[frame])) {
            return set;
        }

        set = allocateDescriptorSet(frame);
        writeDescriptorSet(set, frame, uniformBuffers, texture0, texture1, texture2);

        cachedSets[frame] = set;
        cachedTex0[frame] = texture0;
        cachedTex1[frame] = texture1;
        cachedTex2[frame] = texture2;
        cachedGen[frame] = generation;
        return set;
    }

    private static boolean freed(VulkanImage image) {
        return image != null && image.isFreed();
    }

    private long allocateDescriptorSet(int frame) {
        try(MemoryStack stack = stackPush()) {

            LongBuffer layouts = stack.mallocLong(1);
            layouts.put(0, descriptorSetLayout);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(descriptorPools[frame]);
            allocInfo.pSetLayouts(layouts);

            LongBuffer pDescriptorSet = stack.mallocLong(1);

            int result = vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
            if (result == VK_ERROR_OUT_OF_POOL_MEMORY || result == VK_ERROR_FRAGMENTED_POOL) {
                descriptorCount *= 2;
                // The old pools may still back sets referenced by in-flight command
                // buffers, so they cannot be destroyed here; keep them until every
                // frame-in-flight boundary has passed its fence (see
                // {@link #processPendingDescriptorPools()}).
                pendingPoolDestruction.add(new Object[]{descriptorPools.clone(), 0});
                createDescriptorPool(descriptorCount);
                // The pools were replaced: sets cached against the old ones must not be
                // reused (the freshly allocated set is written below).
                java.util.Arrays.fill(cachedSets, 0);
                // The retry must target the NEW pool of this frame slot; allocInfo
                // still points at the exhausted old one otherwise (N4).
                allocInfo.descriptorPool(descriptorPools[frame]);
                result = vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to allocate descriptor sets after pool resize. Result:" + result);
                }
                System.out.println("resized DescriptorPool to: " + descriptorCount);
                return pDescriptorSet.get(0);
            }
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate descriptor sets. Result:" + result);
            }

            return pDescriptorSet.get(0);
        }
    }

    private void writeDescriptorSet(long descriptorSet, int frame, UniformBuffers uniformBuffers,
                                    VulkanImage texture0, VulkanImage texture1, VulkanImage texture2) {
        try(MemoryStack stack = stackPush()) {

            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(UBOs.size() + samplers.size() + ssbos.size(), stack);

            // 'binding' numbers the descriptor set layout (contiguous UBOs, samplers,
            // SSBOs, see createDescriptorSetLayout); 'writes' indexes the write array.
            // They diverge when an SSBO has no registered buffer: its binding stays
            // reserved but no write is emitted (M23 - a zeroed VkWriteDescriptorSet
            // would make vkUpdateDescriptorSets read garbage).
            int binding = 0;
            int writes = 0;
            for(UBO ubo : UBOs) {
                // Dynamic UBO: the descriptor covers the whole per-frame buffer (offset 0);
                // the per-draw region is selected by the dynamic offset at bind time. The
                // range is the UBO's own size, so offset+range always stays inside it.
                VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack);
                uboInfo.buffer(uniformBuffers.getId(frame));
                uboInfo.offset(0);
                uboInfo.range(ubo.getSize());

                VkWriteDescriptorSet uboDescriptorWrite = descriptorWrites.get(writes);
                uboDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                uboDescriptorWrite.dstBinding(ubo.getBinding());
                uboDescriptorWrite.dstArrayElement(0);
                uboDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC);
                uboDescriptorWrite.descriptorCount(1);
                uboDescriptorWrite.pBufferInfo(uboInfo);
                uboDescriptorWrite.dstSet(descriptorSet);

                ++binding;
                ++writes;
            }

            VulkanImage[] textures = { texture0, texture1, texture2 };
            for (int s = 0; s < samplers.size(); ++s) {
                VulkanImage texture = textures[s];

                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                imageInfo.imageView(texture.getTextureImageView());
                imageInfo.sampler(texture.getTextureSampler());

                VkWriteDescriptorSet samplerDescriptorWrite = descriptorWrites.get(writes);
                samplerDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                samplerDescriptorWrite.dstBinding(binding);
                samplerDescriptorWrite.dstArrayElement(0);
                samplerDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                samplerDescriptorWrite.descriptorCount(1);
                samplerDescriptorWrite.pImageInfo(imageInfo);
                samplerDescriptorWrite.dstSet(descriptorSet);

                ++binding;
                ++writes;
            }

            for(String name : ssbos) {
                net.vulkanmod.vulkan.shader.SSBOResources.Binding b =
                        net.vulkanmod.vulkan.shader.SSBOResources.get(name);
                // Reserve the binding even without a buffer, but emit no write for it.
                final int ssboBinding = binding++;
                if (b == null) {
                    continue;
                }

                VkDescriptorBufferInfo.Buffer ssboInfo = VkDescriptorBufferInfo.calloc(1, stack);
                ssboInfo.buffer(b.buffer());
                ssboInfo.offset(b.offset());
                ssboInfo.range(b.range());

                VkWriteDescriptorSet ssboWrite = descriptorWrites.get(writes);
                ssboWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                ssboWrite.dstBinding(ssboBinding);
                ssboWrite.dstArrayElement(0);
                ssboWrite.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                ssboWrite.descriptorCount(1);
                ssboWrite.pBufferInfo(ssboInfo);
                ssboWrite.dstSet(descriptorSet);

                ++writes;
            }

            descriptorWrites.limit(writes);
            vkUpdateDescriptorSets(device, descriptorWrites, null);
        }
    }

    private static VkVertexInputBindingDescription.Buffer getBindingDescription(VertexFormat vertexFormat) {

        VkVertexInputBindingDescription.Buffer bindingDescription =
                VkVertexInputBindingDescription.create(1);

        bindingDescription.binding(0);
        bindingDescription.stride(vertexFormat.getSize());
        bindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        return bindingDescription;
    }

    /**
     * Maps the 1.12.2 vertex format elements to Vulkan vertex attributes. The attribute
     * location is the element index, so the ported shaders must declare their inputs
     * with the matching {@code layout(location = N)}.
     */
    private static VkVertexInputAttributeDescription.Buffer getAttributeDescriptions(VertexFormat vertexFormat) {

        ImmutableList<VertexFormatElement> elements = ImmutableList.copyOf(vertexFormat.getElements());

        int size = elements.size();

        VkVertexInputAttributeDescription.Buffer attributeDescriptions =
                VkVertexInputAttributeDescription.create(size);

        for(int i = 0; i < size; ++i) {
            VkVertexInputAttributeDescription description = attributeDescriptions.get(i);
            description.binding(0);
            description.location(i);
            description.offset(vertexFormat.getOffset(i));

            VertexFormatElement.EnumType type = elements.get(i).getType();
            VertexFormatElement.EnumUsage usage = elements.get(i).getUsage();
            int count = elements.get(i).getElementCount();

            if(usage == VertexFormatElement.EnumUsage.POSITION && type == VertexFormatElement.EnumType.FLOAT) {
                description.format(count == 3 ? VK_FORMAT_R32G32B32_SFLOAT : VK_FORMAT_R32G32B32A32_SFLOAT);
            }
            else if(usage == VertexFormatElement.EnumUsage.COLOR && type == VertexFormatElement.EnumType.UBYTE) {
                description.format(VK_FORMAT_R8G8B8A8_UNORM);
            }
            else if(usage == VertexFormatElement.EnumUsage.UV) {
                if(type == VertexFormatElement.EnumType.FLOAT) {
                    description.format(VK_FORMAT_R32G32_SFLOAT);
                }
                else if(type == VertexFormatElement.EnumType.SHORT) {
                    description.format(VK_FORMAT_R16G16_SINT);
                }
                else if(type == VertexFormatElement.EnumType.USHORT) {
                    description.format(VK_FORMAT_R16G16_UINT);
                }
                else {
                    throw new RuntimeException("Unknown UV format: " + type);
                }
            }
            else if(usage == VertexFormatElement.EnumUsage.NORMAL) {
                // R8G8B8_SNORM is not a mandatory vertex-buffer format (L1). Every desktop
                // driver supports it, but where one does not, fall back to the mandatory
                // R8G8B8A8_SNORM: the 4th byte is then whatever follows the normal in the
                // stream (vanilla pads ITEM to 4 bytes) and the shaders only read .xyz.
                description.format(count == 3 && R8G8B8_NORMAL_SUPPORTED
                        ? VK_FORMAT_R8G8B8_SNORM : VK_FORMAT_R8G8B8A8_SNORM);
            }
            else if(usage == VertexFormatElement.EnumUsage.PADDING) {
                // Padded bytes are skipped by pointing the attribute at a zeroed location;
                // 1.12.2 uses PADDING elements for alignment (e.g. the normal's 4th byte).
                description.format(VK_FORMAT_R8_SNORM);
            }
            else if(type == VertexFormatElement.EnumType.FLOAT) {
                description.format(switch (count) {
                    case 1 -> VK_FORMAT_R32_SFLOAT;
                    case 2 -> VK_FORMAT_R32G32_SFLOAT;
                    case 3 -> VK_FORMAT_R32G32B32_SFLOAT;
                    default -> VK_FORMAT_R32G32B32A32_SFLOAT;
                });
            }
            else {
                throw new RuntimeException("Unknown vertex attribute: " + usage + " " + type);
            }
        }

        return attributeDescriptions.rewind();
    }

    private void parseUboNode(JsonElement jsonelement) {
        JsonObject jsonobject = jsonelement.getAsJsonObject();
        int binding = jsonobject.get("binding").getAsInt();
        int type = this.getTypeFromString(jsonobject.get("type").getAsString());
        JsonArray fields = jsonobject.getAsJsonArray("fields");

        UBO ubo = new UBO(binding, type);

        for (JsonElement jsonelement2 : fields) {
            JsonObject jsonobject2 = jsonelement2.getAsJsonObject();
            String name = jsonobject2.get("name").getAsString();
            String type2 = jsonobject2.get("type").getAsString();
            int j = jsonobject2.get("count").getAsInt();

            ubo.addField(Field.createField(type2, name, j, ubo));
        }

        UBOs.add(ubo);
    }

    private void parseSamplerNode(JsonElement jsonelement) {
        JsonObject jsonobject = jsonelement.getAsJsonObject();
        String name = jsonobject.get("name").getAsString();

        samplers.add(name);
    }

    private void parseSsboNode(JsonElement jsonelement) {
        JsonObject jsonobject = jsonelement.getAsJsonObject();
        String name = jsonobject.get("name").getAsString();

        ssbos.add(name);
    }

    private void parsePushConstantNode(JsonArray jsonArray) {

        for(JsonElement jsonelement : jsonArray) {
            JsonObject jsonobject2 = jsonelement.getAsJsonObject();

            String name = jsonobject2.get("name").getAsString();
            String type2 = jsonobject2.get("type").getAsString();
            int j = jsonobject2.get("count").getAsInt();

            pushConstant.addField(Field.createField(type2, name, j, pushConstant));
        }

        pushConstant.allocateBuffer();
    }

    private int getTypeFromString(String s) {
        return switch (s) {
            case "vertex" -> VK_SHADER_STAGE_VERTEX_BIT;
            case "fragment" -> VK_SHADER_STAGE_FRAGMENT_BIT;
            case "all" -> VK_SHADER_STAGE_ALL_GRAPHICS;

            default -> throw new RuntimeException("cannot identify type..");
        };
    }

    private static long createShaderModule(ByteBuffer spirvCode) {

        try(MemoryStack stack = stackPush()) {

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.create();

            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(spirvCode);

            LongBuffer pShaderModule = stack.mallocLong(1);

            if(vkCreateShaderModule(device, createInfo, null, pShaderModule) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create shader module");
            }

            return pShaderModule.get(0);
        }
    }

    // The cache file paths, the trailer magic and the dirty flag are declared up top,
    // before pipelineCache (whose initializer needs them).

    private static long createPipelineCache() {
        // The persisted blob can be megabytes, so it goes into a dedicated off-heap
        // buffer rather than the 64 KB MemoryStack. vkCreatePipelineCache copies it.
        byte[] initial = loadPipelineCacheData();
        pipelineCacheWarm = initial != null;
        net.vulkanmod.Initializer.LOGGER.info("[pipeline-cache] {}",
                initial != null ? "loaded " + initial.length + " bytes" : "cold start");
        ByteBuffer initialData = null;
        try(MemoryStack stack = stackPush()) {

            VkPipelineCacheCreateInfo cacheCreateInfo = VkPipelineCacheCreateInfo.create();
            cacheCreateInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            if (initial != null) {
                initialData = org.lwjgl.system.MemoryUtil.memAlloc(initial.length);
                initialData.put(initial);
                initialData.flip();
                cacheCreateInfo.pInitialData(initialData);
            }

            LongBuffer pPipelineCache = stack.mallocLong(1);

            if(vkCreatePipelineCache(device, cacheCreateInfo, null, pPipelineCache) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline cache");
            }

            return pPipelineCache.get(0);
        } finally {
            if (initialData != null) {
                org.lwjgl.system.MemoryUtil.memFree(initialData);
            }
        }
    }

    /**
     * Reads the persisted cache and validates it: the trailer (N13), then the driver's
     * own header - VkPipelineCacheHeaderVersionOne, little-endian, with headerSize (32)
     * at 0, headerVersion (1) at 4, vendorID at 8, deviceID at 12 and the pipeline-cache
     * UUID at 16..31 (N7; the old code read all of them four bytes early and rejected
     * every file). A driver or GPU change discards the file. The blob handed to the
     * driver is the file minus its trailer.
     */
    private static byte[] loadPipelineCacheData() {
        try {
            if (!java.nio.file.Files.isRegularFile(PIPELINE_CACHE_FILE)) {
                return null;
            }
            byte[] data = java.nio.file.Files.readAllBytes(PIPELINE_CACHE_FILE);
            String reject = null;
            if (data.length < 32 + 8) {
                reject = "too short (" + data.length + " bytes)";
            }
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (reject == null && buf.getInt(data.length - 8) != CACHE_TRAILER_MAGIC) {
                reject = "trailer magic";
            }
            int blobLength = buf.getInt(data.length - 4);
            if (reject == null && blobLength != data.length - 8) {
                reject = "trailer length " + blobLength + " vs file " + (data.length - 8);
            }
            // headerSize, VK_PIPELINE_CACHE_HEADER_VERSION_ONE
            if (reject == null && (buf.getInt(0) < 32 || buf.getInt(4) != 1)) {
                reject = "header size/version " + buf.getInt(0) + "/" + buf.getInt(4);
            }
            if (reject == null && buf.getInt(8) != Vulkan.deviceProperties.vendorID()) {
                reject = String.format("vendor 0x%04x vs 0x%04x", buf.getInt(8),
                        Vulkan.deviceProperties.vendorID());
            }
            if (reject == null && buf.getInt(12) != Vulkan.deviceProperties.deviceID()) {
                reject = String.format("device 0x%04x vs 0x%04x", buf.getInt(12),
                        Vulkan.deviceProperties.deviceID());
            }
            java.nio.ByteBuffer uuid = Vulkan.deviceProperties.pipelineCacheUUID();
            for (int i = 0; reject == null && i < 16; ++i) {
                if (data[16 + i] != uuid.get(i)) {
                    reject = "UUID byte " + i;
                }
            }
            if (reject != null) {
                net.vulkanmod.Initializer.LOGGER.info("[pipeline-cache] rejected: {}", reject);
                return null;
            }
            return java.util.Arrays.copyOf(data, blobLength);
        } catch (Exception e) {
            net.vulkanmod.Initializer.LOGGER.info("[pipeline-cache] rejected: {}", e.toString());
            return null;
        }
    }

    /**
     * Exports the driver cache blob and the recorded variant keys to disk so the next
     * launch seeds its pipeline cache and pre-warms its variants. Called once at
     * shutdown (Vulkan.cleanUp, before the cache is destroyed) when something was
     * created since the last save (N13).
     */
    public static void savePipelineCache() {
        // R12: the variants file has its own change detection (it is rewritten only when
        // its text changed), so it must run even when the driver blob stayed the same.
        saveRecordedVariants();

        if (!cacheDirty) {
            return;
        }
        cacheDirty = false;

        // The cache blob is megabytes - far beyond the 64 KB MemoryStack - so it is
        // fetched into a dedicated off-heap buffer.
        ByteBuffer pData = null;
        try(MemoryStack stack = stackPush()) {
            org.lwjgl.PointerBuffer pSize = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(device, pipelineCache, pSize, null) != VK_SUCCESS) {
                return;
            }
            int size = (int) pSize.get(0);
            if (size <= 0) {
                return;
            }
            pData = org.lwjgl.system.MemoryUtil.memAlloc(size);
            if (vkGetPipelineCacheData(device, pipelineCache, pSize, pData) != VK_SUCCESS) {
                return;
            }
            byte[] out = new byte[size + 8];
            pData.position(0);
            pData.get(out, 0, size);
            java.nio.ByteBuffer.wrap(out, size, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(CACHE_TRAILER_MAGIC).putInt(size);
            writeAtomic(PIPELINE_CACHE_FILE, out);
        } catch (Exception e) {
            // Cache persistence is best-effort; a failed write only costs warm-up time.
        } finally {
            if (pData != null) {
                org.lwjgl.system.MemoryUtil.memFree(pData);
            }
        }
    }

    /**
     * Writes through a temp file and renames over the target, so a crash mid-write can
     * never leave a partial file where the loader expects a complete one (N13).
     */
    private static void writeAtomic(java.nio.file.Path file, byte[] bytes) throws java.io.IOException {
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        java.nio.file.Files.write(tmp, bytes);
        try {
            java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Range-check of a packed state key before it reaches vkCreateGraphicsPipelines
     * (R11): a corrupted, hand-edited or old-layout recording must not cause invalid
     * usage. Field layout: see {@link #packStateKey}.
     */
    static boolean isValidStateKey(long key) {
        // Everything above the 3 used topology bits must be zero.
        if ((key >>> 37) != 0) {
            return false;
        }
        // The four blend factors: the port only mirrors values up to
        // VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA (9), plus VK_BLEND_FACTOR_SRC_ALPHA_SATURATE
        // (10) is accepted; anything above (SRC1/DEST1 factors without the matching
        // features, or garbage) invalidates the record.
        for (int shift = 1; shift <= 13; shift += 4) {
            int factor = (int) (key >>> shift) & 0xF;
            if (factor > VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA && factor != VK_BLEND_FACTOR_SRC_ALPHA_SATURATE) {
                return false;
            }
        }
        if (((int) (key >>> 19) & 0xF) > VK_COMPARE_OP_ALWAYS) {
            return false;
        }
        boolean logicOpEnabled = (key & (1L << 24)) != 0;
        if (((int) (key >>> 25) & 0x1F) > VK_LOGIC_OP_SET
                || (logicOpEnabled && !Vulkan.logicOpSupported)) {
            return false;
        }
        // Color mask (4 bits) is free-form; the topology must be one of the line/triangle
        // list modes the port packs (0..5).
        int topology = (int) (key >>> 34) & 0x7;
        return topology >= VK_PRIMITIVE_TOPOLOGY_LINE_LIST && topology <= VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    }

    /** Drops the in-memory recording and the file, so a bad entry cannot repeat (R11). */
    private static void discardVariantsFile() {
        loadedVariantsText = null;
        try {
            java.nio.file.Files.deleteIfExists(VARIANTS_FILE);
        } catch (java.io.IOException ignored) {
        }
    }

    /**
     * One "<recordId> <state-key> <age>" line per recorded variant (H11, R12): keys
     * reached this session get age 0, the others age by one; entries older than
     * MAX_VARIANT_AGE sessions are dropped and each pipeline keeps at most
     * MAX_VARIANTS_PER_PIPELINE (youngest first). The file is rewritten only when its
     * text changed, so a shutdown without new variants leaves it (and its mtime) alone.
     */
    private static void saveRecordedVariants() {
        try {
            Map<String, Map<Long, Integer>> out = new java.util.TreeMap<>();
            RECORDED_VARIANTS.forEach((id, keys) -> {
                Map<Long, Integer> aged = out.computeIfAbsent(id, k -> new java.util.HashMap<>());
                keys.forEach((key, age) -> {
                    if (age < MAX_VARIANT_AGE) {
                        aged.merge(key, age + 1, Math::min);
                    }
                });
            });
            for (Pipeline p : INSTANCES) {
                Map<Long, Integer> aged = out.computeIfAbsent(p.recordId, k -> new java.util.HashMap<>());
                for (long key : p.reachedKeys) {
                    aged.put(key, 0);
                }
            }

            StringBuilder sb = new StringBuilder(VARIANTS_HEADER).append('\n');
            out.forEach((id, keys) -> keys.entrySet().stream()
                    .sorted(Map.Entry.<Long, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .limit(MAX_VARIANTS_PER_PIPELINE)
                    .forEach(e -> sb.append(id).append(' ').append(e.getKey()).append(' ')
                            .append(e.getValue()).append('\n')));
            String text = sb.toString();
            if (text.equals(loadedVariantsText)) {
                return;
            }
            writeAtomic(VARIANTS_FILE, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            loadedVariantsText = text;
        } catch (Exception e) {
            // Best-effort warm-up data; losing it only costs the next launch's pre-warm.
        }
    }

    /**
     * Reads the versioned sidecar (R11): a file with any other first line is deleted
     * unread, a damaged line marks the text as not-reusable so the save rewrites it,
     * and duplicate keys keep the smallest age.
     */
    private static Map<String, Map<Long, Integer>> loadRecordedVariants() {
        Map<String, Map<Long, Integer>> map = new HashMap<>();
        boolean clean = false;
        String text = null;
        try {
            List<String> lines = java.nio.file.Files.readAllLines(VARIANTS_FILE);
            clean = !lines.isEmpty() && VARIANTS_HEADER.equals(lines.get(0));
            if (clean) {
                for (String line : lines.subList(1, lines.size())) {
                    String[] parts = line.split(" ");
                    if (parts.length != 3) {
                        clean = false;
                        continue;
                    }
                    try {
                        long key = Long.parseLong(parts[1]);
                        int age = Integer.parseInt(parts[2]);
                        if (age < 0 || age > MAX_VARIANT_AGE) {
                            clean = false;
                            continue;
                        }
                        map.computeIfAbsent(parts[0], k -> new java.util.HashMap<>())
                           .merge(key, age, Math::min);
                    } catch (NumberFormatException ignored) {
                        clean = false;
                    }
                }
            }
            if (clean) {
                text = String.join("\n", lines) + "\n";
            }
        } catch (Exception e) {
            // No usable recording; variants will be created at draw time like before.
        }
        if (!clean && java.nio.file.Files.exists(VARIANTS_FILE)) {
            // Old format or damaged lines: start the recording over (R11).
            discardVariantsFile();
            map.clear();
        }
        loadedVariantsText = text;
        return map;
    }

    private Consumer<Integer> defaultResetDPFun() {
        Consumer<Integer> fun = (i) -> {
            if(vkResetDescriptorPool(device, descriptorPools[i], 0) != VK_SUCCESS) {
                throw new RuntimeException("Failed to reset descriptor pool");
            }
        };
        return fun;
    }

    public void resetDescriptorPool(int i) {
        this.resetDescriptorPoolFun.accept(i);
        // The pool's sets are gone (or the pipeline is being torn down): drop the cache.
        cachedSets[i] = 0;
        cachedTex0[i] = null;
        cachedTex1[i] = null;
        cachedTex2[i] = null;
    }

    /**
     * Destroys descriptor pools superseded by a resize once they have aged past the
     * swapchain image count. Called once per frame (from {@code Drawer.resetDescriptors}),
     * so by then every image's fence has been waited at least once since the pools were
     * replaced and no in-flight command buffer can still reference their sets.
     */
    public static void processPendingDescriptorPools() {
        if (pendingPoolDestruction.isEmpty()) {
            return;
        }
        var it = pendingPoolDestruction.iterator();
        while (it.hasNext()) {
            Object[] entry = it.next();
            int age = (Integer) entry[1] + 1;
            if (age > imagesSize) {
                for (long pool : (long[]) entry[0]) {
                    vkDestroyDescriptorPool(device, pool, null);
                }
                it.remove();
            } else {
                entry[1] = age;
            }
        }
    }

    public PushConstant getPushConstant() { return pushConstant; }

    public long getLayout() { return pipelineLayout; }

    public String getPath() { return path; }

    public static BlendState defaultBlend() {
        // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO
        return new BlendState(0x0302, 0x0303, 1, 0);
    }

    public static DepthState defaultDepthState() {
        return new DepthState(true, true, 515);
    }

    public static ColorMask defaultColorMask() { return new ColorMask(true, true, true, true); }

    public static class PipelineState {
        final BlendState blendState;
        final DepthState depthState;
        final ColorMask colorMask;
        final LogicOpState logicOpState;
        final boolean cullState;
        final int topology;

        public PipelineState(BlendState blendState, DepthState depthState, LogicOpState logicOpState, ColorMask colorMask) {
            this(blendState, depthState, logicOpState, colorMask, VRenderSystem.cull, VRenderSystem.topology);
        }

        public PipelineState(BlendState blendState, DepthState depthState, LogicOpState logicOpState,
                             ColorMask colorMask, boolean cullState, int topology) {
            this.blendState = blendState;
            this.depthState = depthState;
            this.logicOpState = logicOpState;
            this.colorMask = colorMask;
            this.cullState = cullState;
            this.topology = topology;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PipelineState that = (PipelineState) o;
            return blendState.equals(that.blendState) && depthState.equals(that.depthState) && logicOpState.equals(that.logicOpState) && (cullState == that.cullState) && colorMask.equals(that.colorMask) && topology == that.topology;
        }

        @Override
        public int hashCode() {
            // Allocation-free (Objects.hash would allocate an Object[] + boxes on every draw lookup).
            int result = 1;
            result = 31 * result + blendState.hashCode();
            result = 31 * result + depthState.hashCode();
            result = 31 * result + logicOpState.hashCode();
            result = 31 * result + (cullState ? 1231 : 1237);
            result = 31 * result + topology;
            return result;
        }
    }

    public static class BlendState {
        public final boolean enabled;
        public final int srcRgbFactor;
        public final int dstRgbFactor;
        public final int srcAlphaFactor;
        public final int dstAlphaFactor;
        public final int blendOp = 0;

        public BlendState(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
            this(true, glToVulkan(srcRgb), glToVulkan(dstRgb), glToVulkan(srcAlpha), glToVulkan(dstAlpha));
        }

        protected BlendState(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
            this.enabled = enabled;
            this.srcRgbFactor = srcRgb;
            this.dstRgbFactor = dstRgb;
            this.srcAlphaFactor = srcAlpha;
            this.dstAlphaFactor = dstAlpha;
        }

        private static int glToVulkan(int value) {
            return switch (value) {
                case 1 -> VK_BLEND_FACTOR_ONE;
                case 0 -> VK_BLEND_FACTOR_ZERO;
                case 771 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                case 770 -> VK_BLEND_FACTOR_SRC_ALPHA;
                case 775 -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
                case 769 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
                case 774 -> VK_BLEND_FACTOR_DST_COLOR;
                case 768 -> VK_BLEND_FACTOR_SRC_COLOR;
                case 772 -> VK_BLEND_FACTOR_DST_ALPHA;
                case 773 -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
                case 776 -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
                default -> throw new RuntimeException("unknown blend factor: " + value);
            };
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlendState blendState = (BlendState) o;
            if(this.enabled != blendState.enabled) return false;
            return srcRgbFactor == blendState.srcRgbFactor && dstRgbFactor == blendState.dstRgbFactor && srcAlphaFactor == blendState.srcAlphaFactor && dstAlphaFactor == blendState.dstAlphaFactor && blendOp == blendState.blendOp;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + srcRgbFactor;
            result = 31 * result + dstRgbFactor;
            result = 31 * result + srcAlphaFactor;
            result = 31 * result + dstAlphaFactor;
            result = 31 * result + blendOp;
            return result;
        }
    }

    public static class LogicOpState {
        public final boolean enabled;
        private int logicOp;

        public LogicOpState(boolean enable, int op) {
            this.enabled = enable;
            this.logicOp = toVkLogicOp(op);
        }

        /**
         * The GL logic-op enums (GL_CLEAR..GL_SET = 0x1500..0x1510) map onto the Vulkan
         * values by subtracting the base (N22); the packed state key gives the op only
         * 5 bits, so the raw enum spilled into the color-mask and topology fields.
         * Values already in Vulkan range (rebuilt from a packed key) pass through.
         */
        private static int toVkLogicOp(int op) {
            if (op >= 0x1500) {
                int vk = op - 0x1500;
                assert vk <= VK_LOGIC_OP_SET;
                return vk;
            }
            return op;
        }

        public void setLogicOp(int glLogicOp) {
            this.logicOp = toVkLogicOp(glLogicOp);
        }

        public int getLogicOp() {
            return logicOp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LogicOpState logicOpState = (LogicOpState) o;
            if(this.enabled != logicOpState.enabled) return false;
            return logicOp == logicOpState.logicOp;
        }

        public int hashCode() {
            int result = 1;
            result = 31 * result + (enabled ? 1231 : 1237);
            result = 31 * result + logicOp;
            return result;
        }
    }

    public static class ColorMask {
        public final int colorMask;

        public ColorMask(boolean r, boolean g, boolean b, boolean a) {
            this.colorMask = (r ? VK_COLOR_COMPONENT_R_BIT : 0) | (g ? VK_COLOR_COMPONENT_G_BIT : 0) | (b ? VK_COLOR_COMPONENT_B_BIT : 0) | (a ? VK_COLOR_COMPONENT_A_BIT : 0);
        }

        public ColorMask(int mask) {
            this.colorMask = mask;
        }

        public static int getColorMask(boolean r, boolean g, boolean b, boolean a) {
            return (r ? VK_COLOR_COMPONENT_R_BIT : 0) | (g ? VK_COLOR_COMPONENT_G_BIT : 0) | (b ? VK_COLOR_COMPONENT_B_BIT : 0) | (a ? VK_COLOR_COMPONENT_A_BIT : 0);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ColorMask colorMask = (ColorMask) o;
            return this.colorMask == colorMask.colorMask;
        }
    }

    public static class DepthState {
        public final boolean depthTest;
        public final boolean depthMask;
        public final int function;

        public DepthState(boolean depthTest, boolean depthMask, int function) {
            this(depthTest, depthMask, glToVulkan(function), true);
        }

        /** {@code vkNative}: when true, {@code function} is already a Vulkan compare op. */
        DepthState(boolean depthTest, boolean depthMask, int function, boolean vkNative) {
            this.depthTest = depthTest;
            this.depthMask = depthMask;
            this.function = function;
        }

        public static int depthFuncToVulkan(int value) {
            return glToVulkan(value);
        }

        private static int glToVulkan(int value) {
            return switch (value) {
                case 512 -> VK_COMPARE_OP_NEVER;
                case 513 -> VK_COMPARE_OP_LESS;
                case 514 -> VK_COMPARE_OP_EQUAL;
                case 515 -> VK_COMPARE_OP_LESS_OR_EQUAL;
                case 516 -> VK_COMPARE_OP_GREATER;
                case 517 -> VK_COMPARE_OP_NOT_EQUAL;
                case 518 -> VK_COMPARE_OP_GREATER_OR_EQUAL;
                case 519 -> VK_COMPARE_OP_ALWAYS;
                default -> throw new RuntimeException("unknown depth function: " + value);
            };
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DepthState that = (DepthState) o;
            return depthTest == that.depthTest && depthMask == that.depthMask && function == that.function;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + (depthTest ? 1231 : 1237);
            result = 31 * result + (depthMask ? 1231 : 1237);
            result = 31 * result + function;
            return result;
        }
    }
}
