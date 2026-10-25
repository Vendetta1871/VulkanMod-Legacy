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
    private static final long pipelineCache = createPipelineCache();
    private static final int imagesSize = getSwapChainImages().size();

    private String path;
    private long descriptorSetLayout;
    private long pipelineLayout;
    private Map<PipelineState, Long> graphicsPipelines = new HashMap<>();
    private VertexFormat vertexFormat;

    private final long[] descriptorPools = new long[imagesSize];
    private int descriptorCount = 500;

    private final List<UBO> UBOs = new ArrayList<>();
    private final List<String> samplers = new ArrayList<>();
    private final List<String> ssbos = new ArrayList<>();
    private final PushConstant pushConstant = new PushConstant();

    private Consumer<Integer> resetDescriptorPoolFun = defaultResetDPFun();

    private long vertShaderModule = 0;
    private long fragShaderModule = 0;

    public Pipeline(VertexFormat vertexFormat, String path) {
        this.path = path;

        createDescriptorSetLayout(path);
        createPipelineLayout();
        graphicsPipelines.computeIfAbsent(new PipelineState(DEFAULT_BLEND_STATE, DEFAULT_DEPTH_STATE, DEFAULT_LOGICOP_STATE, DEFAULT_COLORMASK),
                (pipelineState) -> createGraphicsPipeline(vertexFormat, pipelineState));
        createDescriptorPool(descriptorCount);
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
            inputAssembly.topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
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
            dynamicStates.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

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
            pipelineInfo.renderPass(Vulkan.getRenderPass());
            pipelineInfo.subpass(0);
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE);
            pipelineInfo.basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);

            if(vkCreateGraphicsPipelines(device, pipelineCache, pipelineInfo, null, pGraphicsPipeline) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create graphics pipeline");
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
                uboLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
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
            int size =  UBOs.size() + samplers.size() + ssbos.size();

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.create(size);

            int i;
            for(i = 0; i < UBOs.size(); ++i) {
                VkDescriptorPoolSize uniformBufferPoolSize = poolSizes.get(i);
                uniformBufferPoolSize.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                uniformBufferPoolSize.descriptorCount(descriptorCount);
            }

            for(; i < UBOs.size() + samplers.size(); ++i) {
                VkDescriptorPoolSize textureSamplerPoolSize = poolSizes.get(i);
                textureSamplerPoolSize.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                textureSamplerPoolSize.descriptorCount(descriptorCount);
            }

            for(; i < size; ++i) {
                VkDescriptorPoolSize storageBufferPoolSize = poolSizes.get(i);
                storageBufferPoolSize.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                storageBufferPoolSize.descriptorCount(descriptorCount);
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

        this.resetDescriptorPoolFun = i -> {};

        graphicsPipelines.forEach((state, pipeline) -> {
            vkDestroyPipeline(device, pipeline, null);
        });
        graphicsPipelines.clear();

        vkDestroyPipelineLayout(device, pipelineLayout, null);
    }

    public long createDescriptorSets(VkCommandBuffer commandBuffer, int frame, UniformBuffers uniformBuffers) {
        DescriptorSetsUnit dsUnit = new DescriptorSetsUnit(commandBuffer, frame, uniformBuffers);
        return dsUnit.descriptorSet;
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
                description.format(count == 3 ? VK_FORMAT_R8G8B8_SNORM : VK_FORMAT_R8G8B8A8_SNORM);
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

    private static long createPipelineCache() {
        try(MemoryStack stack = stackPush()) {

            VkPipelineCacheCreateInfo cacheCreateInfo = VkPipelineCacheCreateInfo.create();
            cacheCreateInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            LongBuffer pPipelineCache = stack.mallocLong(1);

            if(vkCreatePipelineCache(device, cacheCreateInfo, null, pPipelineCache) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline cache");
            }

            return pPipelineCache.get(0);
        }
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
    }

    public long getHandle(PipelineState state) {
        return graphicsPipelines.computeIfAbsent(state, state1 -> createGraphicsPipeline(this.vertexFormat, state1));
    }

    public PushConstant getPushConstant() { return pushConstant; }

    public long getLayout() { return pipelineLayout; }

    public String getPath() { return path; }

    private class DescriptorSetsUnit {
        private long descriptorSet;

        private DescriptorSetsUnit(VkCommandBuffer commandBuffer, int frame, UniformBuffers uniformBuffers) {

            allocateDescriptorSet(frame);
            updateDescriptorSet(commandBuffer, frame, uniformBuffers);
        }

        private void allocateDescriptorSet(int frame) {
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
                    createDescriptorPool(descriptorCount);
                    allocateDescriptorSet(frame);

                    //TODO: free old descriptorPool when its relative cmdBuffer has finished drawing

                    System.out.println("resized DescriptorPool to: " + descriptorCount);
                    return;
                }
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to allocate descriptor sets. Result:" + result);
                }

                descriptorSet = pDescriptorSet.get(0);
            }
        }

        private void updateDescriptorSet(VkCommandBuffer commandBuffer, int frame, UniformBuffers uniformBuffers) {
            try(MemoryStack stack = stackPush()) {

                VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.calloc(UBOs.size() + samplers.size() + ssbos.size(), stack);
                VkDescriptorBufferInfo.Buffer[] bufferInfos = new VkDescriptorBufferInfo.Buffer[UBOs.size()];

                int currentOffset = uniformBuffers.getUsedBytes();

                int i = 0;
                for(UBO ubo : UBOs) {

                    ubo.update();
                    uniformBuffers.uploadUBO(ubo.getBuffer(), currentOffset, frame);

                    bufferInfos[i] = VkDescriptorBufferInfo.calloc(1, stack);
                    bufferInfos[i].buffer(uniformBuffers.getId(frame));
                    bufferInfos[i].offset(currentOffset);
                    bufferInfos[i].range(ubo.getSize());

                    VkWriteDescriptorSet uboDescriptorWrite = descriptorWrites.get(i);
                    uboDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                    uboDescriptorWrite.dstBinding(ubo.getBinding());
                    uboDescriptorWrite.dstArrayElement(0);
                    uboDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                    uboDescriptorWrite.descriptorCount(1);
                    uboDescriptorWrite.pBufferInfo(bufferInfos[i]);
                    uboDescriptorWrite.dstSet(descriptorSet);

                    // The upload aligns to minUniformBufferOffsetAlignment, so the next
                    // free offset must be taken from the ring buffer itself.
                    currentOffset = uniformBuffers.getUsedBytes();
                    ++i;
                }

                VkDescriptorImageInfo.Buffer[] imageInfos = new VkDescriptorImageInfo.Buffer[samplers.size()];

                if (samplers.size() > 0) {
                    VulkanImage texture = VTextureSelector.getBoundTexture();
                    if (texture == null) texture = VTextureSelector.getWhiteTexture();
                    texture.readOnlyLayout();

                    imageInfos[0] = VkDescriptorImageInfo.calloc(1, stack);
                    imageInfos[0].imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    imageInfos[0].imageView(texture.getTextureImageView());
                    imageInfos[0].sampler(texture.getTextureSampler());

                    VkWriteDescriptorSet samplerDescriptorWrite = descriptorWrites.get(i);
                    samplerDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                    samplerDescriptorWrite.dstBinding(i);
                    samplerDescriptorWrite.dstArrayElement(0);
                    samplerDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                    samplerDescriptorWrite.descriptorCount(1);
                    samplerDescriptorWrite.pImageInfo(imageInfos[0]);
                    samplerDescriptorWrite.dstSet(descriptorSet);

                    ++i;

                    if (samplers.size() > 1) {
                        texture = VTextureSelector.getLightTexture();
                        if (texture == null) texture = VTextureSelector.getWhiteTexture();
                        texture.readOnlyLayout();

                        imageInfos[1] = VkDescriptorImageInfo.calloc(1, stack);
                        imageInfos[1].imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        imageInfos[1].imageView(texture.getTextureImageView());
                        imageInfos[1].sampler(texture.getTextureSampler());

                        samplerDescriptorWrite = descriptorWrites.get(i);
                        samplerDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                        samplerDescriptorWrite.dstBinding(i);
                        samplerDescriptorWrite.dstArrayElement(0);
                        samplerDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                        samplerDescriptorWrite.descriptorCount(1);
                        samplerDescriptorWrite.pImageInfo(imageInfos[1]);
                    samplerDescriptorWrite.dstSet(descriptorSet);

                        ++i;

                        if (samplers.size() > 2) {
                            texture = VTextureSelector.getOverlayTexture();
                            if (texture == null) texture = VTextureSelector.getWhiteTexture();
                            texture.readOnlyLayout();

                            imageInfos[2] = VkDescriptorImageInfo.calloc(1, stack);
                            imageInfos[2].imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                            imageInfos[2].imageView(texture.getTextureImageView());
                            imageInfos[2].sampler(texture.getTextureSampler());

                            samplerDescriptorWrite = descriptorWrites.get(i);
                            samplerDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                            samplerDescriptorWrite.dstBinding(i);
                            samplerDescriptorWrite.dstArrayElement(0);
                            samplerDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                            samplerDescriptorWrite.descriptorCount(1);
                            samplerDescriptorWrite.pImageInfo(imageInfos[2]);
                    samplerDescriptorWrite.dstSet(descriptorSet);
                        }
                    }
                }

                for(String name : ssbos) {
                    net.vulkanmod.vulkan.shader.SSBOResources.Binding b =
                            net.vulkanmod.vulkan.shader.SSBOResources.get(name);
                    if (b == null) {
                        ++i;
                        continue;
                    }

                    VkDescriptorBufferInfo.Buffer ssboInfo = VkDescriptorBufferInfo.calloc(1, stack);
                    ssboInfo.buffer(b.buffer());
                    ssboInfo.offset(b.offset());
                    ssboInfo.range(b.range());

                    VkWriteDescriptorSet ssboWrite = descriptorWrites.get(i);
                    ssboWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                    ssboWrite.dstBinding(i);
                    ssboWrite.dstArrayElement(0);
                    ssboWrite.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                    ssboWrite.descriptorCount(1);
                    ssboWrite.pBufferInfo(ssboInfo);
                    ssboWrite.dstSet(descriptorSet);

                    ++i;
                }

                vkUpdateDescriptorSets(device, descriptorWrites, null);
            }
        }
    }

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

        public PipelineState(BlendState blendState, DepthState depthState, LogicOpState logicOpState, ColorMask colorMask) {
            this.blendState = blendState;
            this.depthState = depthState;
            this.logicOpState = logicOpState;
            this.colorMask = colorMask;
            this.cullState = VRenderSystem.cull;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PipelineState that = (PipelineState) o;
            return blendState.equals(that.blendState) && depthState.equals(that.depthState) && logicOpState.equals(that.logicOpState) && (cullState == that.cullState) && colorMask.equals(that.colorMask);
        }

        @Override
        public int hashCode() {
            // Allocation-free (Objects.hash would allocate an Object[] + boxes on every draw lookup).
            int result = 1;
            result = 31 * result + blendState.hashCode();
            result = 31 * result + depthState.hashCode();
            result = 31 * result + logicOpState.hashCode();
            result = 31 * result + (cullState ? 1231 : 1237);
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
            this.logicOp = op;
        }

        public void setLogicOp(int glLogicOp) {
            // GL_OR_REVERSE is 0x150B; other ops can be mapped as they become needed.
            if (glLogicOp == 0x150B) {
                this.logicOp = VK_LOGIC_OP_OR_REVERSE;
            } else {
                this.logicOp = glLogicOp;
            }
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
            this.depthTest = depthTest;
            this.depthMask = depthMask;
            this.function = glToVulkan(function);
        }

        private int glToVulkan(int value) {
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
