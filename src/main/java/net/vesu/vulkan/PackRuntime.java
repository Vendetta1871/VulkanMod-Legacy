package net.vesu.vulkan;

import net.vesu.Env;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.vesu.Initializer;
import net.vesu.debug.FrameProfiler;
import net.vesu.pack.host.FrameBlock;
import net.minecraft.util.BlockRenderLayer;
import net.vesu.pack.host.GeometryPrelude;
import net.vesu.pack.host.Prelude;
import net.vesu.pack.plan.GeometryCategory;
import net.vesu.render.ChunkBatcher;
import net.vesu.pack.host.Resources;
import net.vesu.render.EntityRenderer;
import net.vesu.render.TerrainRenderer;
import net.vesu.pack.ir.IrPack;
import net.vesu.pack.ir.TextureFormat;
import net.vesu.pack.plan.FramePlan;
import net.vesu.pack.source.IncludeExpander;
import net.vesu.pack.source.PackSource;
import net.vesu.shader.FrameUniforms;
import net.vesu.shader.PackManager;
import net.vesu.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Executes one pack's command-list passes (docs/shaderpacks, M1): the {@code POST_SCENE}
 * passes and the {@code combination}, which writes the host scene color. A pack with scene
 * variants has one runtime per variant, and only the running one holds its images.
 * Built from the pack's {@link FramePlan}: images, pipelines and descriptor sets are made
 * once (D6) and recreated with the frame targets on resize.
 *
 * <p>Runs between the end of the SCENE phase and the upscale. The scene renders in pack
 * space (GL row order) while a pack runs; the combination draws with the host's negative
 * viewport, which is the one flip back to Vulkan row order (D4). Barriers come from a small
 * per-image state tracker. With no pack selected nothing here is touched.</p>
 */
public final class PackRuntime {

    /** The runtimes of the active pack and its scene variants, built together. */
    private static final Map<IrPack, PackRuntime> runtimes = new IdentityHashMap<>();
    private static PackRuntime current;
    /** The pack the runtimes (or the failed attempt) were built for. */
    private static IrPack builtFor;
    private static boolean rtWarned;
    private static volatile IrPack warmed, warming;
    /** VESU_PACK_SCENE: a forced scene variant, or "cycle" to walk through them (autotests). */
    private static final String SCENE_DEBUG = Env.get("VESU_PACK_SCENE");

    /** Id of the pack compiling in the background, or null. Until it is done the frame stays as it was. */
    public static String compiling() {
        IrPack pack = warming;
        return pack == null ? null : pack.id;
    }

    /** Percent of the background compile. */
    public static int compilePercent() {
        int total = compileTotal.get();
        return total == 0 ? 0 : Math.min(99, compileDone.get() * 100 / total);
    }

    private static final java.util.concurrent.atomic.AtomicInteger compileDone = new java.util.concurrent.atomic.AtomicInteger(),
            compileTotal = new java.util.concurrent.atomic.AtomicInteger();
    /** The pack the player was told about, and when the switch started. */
    private static IrPack announced;
    private static long announcedAt;
    /** The pack whose pause was announced: it is built a frame later, so the message gets drawn first. */
    private static IrPack building;

    private static boolean tellsPlayer() {
        return !net.vesu.debug.AutoTest.ENABLED || net.vesu.debug.AutoTest.BACKGROUND_PACKS;
    }

    private static void chat(net.minecraft.util.text.TextFormatting color, String text) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.ingameGUI == null || mc.player == null) return;
        net.minecraft.util.text.TextComponentString line = new net.minecraft.util.text.TextComponentString("[Shaders] " + text);
        line.getStyle().setColor(color);
        mc.ingameGUI.getChatGUI().printChatMessage(line);
    }

    /** Called at frame start: the runtime for this frame, or null (no pack, failed, or RT on). */
    static PackRuntime forFrame() {
        IrPack pack = PackManager.active();
        if (pack != builtFor) {
            if (pack == null) {
                rebuild(null);
            } else if (!warmedUp(pack)) {
                // The pack compiles in the background; the frame stays as it was until then.
                showCompiling(pack);
                pack = builtFor;
            } else if (tellsPlayer() && announced == pack && building != pack) {
                building = pack;
                chat(net.minecraft.util.text.TextFormatting.YELLOW, "Building " + pack.name + ", the game may stop for a moment");
                pack = builtFor;
            } else {
                rebuild(pack);
                if (tellsPlayer() && announced == pack) finishAnnouncement(pack);
            }
        }
        if (runtimes.isEmpty()) return null;
        if (PackManager.pausedByRt(pack)) {
            if (!rtWarned) {
                rtWarned = true;
                Initializer.LOGGER.warn("[shaderpacks] {} is paused while ray tracing is on", pack.id);
            }
            return null;
        }
        rtWarned = false;
        IrPack variant = pack.forScene(pack.scenes.isEmpty() ? null : currentScene());
        PackRuntime wanted = runtimes.get(variant);
        if (wanted == null) {
            // Dimensions are built on first entry: a pack can have three full program sets.
            Vulkan.waitIdle();
            wanted = buildVariant(pack, variant);
            if (wanted == null) return null;
        }
        if (wanted != current) {
            if (current != null) {
                Vulkan.waitIdle();
                current.destroyImages();
            }
            current = wanted;
            current.activatePrograms();
            Initializer.LOGGER.info("[shaderpacks] {}: scene variant {}", pack.id, current.pack.name);
        }
        return current;
    }

    /**
     * True once the programs of every variant of {@code pack} are in the SPIR-V disk cache
     * (D10): a cold pack takes tens of seconds, so that happens on a thread of its own.
     * Autotests compile in place, their captures need the pack from the first frame.
     */
    /** A pack can take a minute on its first run: without this the switch looks like it did nothing. */
    private static void showCompiling(IrPack pack) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc.ingameGUI == null || mc.player == null) return;
        if (announced != pack) {
            announced = pack;
            announcedAt = System.nanoTime();
            chat(net.minecraft.util.text.TextFormatting.YELLOW, "Compiling " + pack.name + ": the picture changes when it is done");
        }
        mc.ingameGUI.setOverlayMessage("Compiling " + pack.name + ": " + (warming == pack ? compilePercent() : 0) + "%", false);
    }

    private static void finishAnnouncement(IrPack pack) {
        String error = PackManager.activeError();
        if (error != null) {
            chat(net.minecraft.util.text.TextFormatting.RED, pack.name + " failed: " + error);
        } else {
            chat(net.minecraft.util.text.TextFormatting.GREEN, pack.name + " is on, it took " + (System.nanoTime() - announcedAt) / 1_000_000_000L + " s");
        }
        announced = null;
    }

    private static boolean warmedUp(IrPack pack) {
        if (pack == warmed || (net.vesu.debug.AutoTest.ENABLED && !net.vesu.debug.AutoTest.BACKGROUND_PACKS)) return true;
        if (warming != null) return false;
        warming = pack;
        compileDone.set(0);
        compileTotal.set(0);
        Thread thread = new Thread(() -> {
            long start = System.nanoTime();
            try (PackSource src = PackManager.openActive()) {
                IncludeExpander expander = new IncludeExpander(src);
                precompile(pack, FramePlan.build(pack), expander);
                for (IrPack variant : pack.scenes.values()) precompile(variant, FramePlan.build(variant), expander);
                Initializer.LOGGER.info("[shaderpacks] {} compiled in {} ms", pack.id, (System.nanoTime() - start) / 1_000_000);
            } catch (IOException | RuntimeException e) {
                // The build that follows reports it, with the usual message.
                Initializer.LOGGER.warn("[shaderpacks] {}: background compile stopped: {}", pack.id, e.toString());
            }
            warmed = pack;
            warming = null;
        }, "Vesu pack compile");
        thread.setDaemon(true);
        thread.start();
        return false;
    }

    /** The scene variant name for this frame, or null for the base pack. Water wins over the dimension. */
    private static String currentScene() {
        if (SCENE_DEBUG != null) {
            if (!SCENE_DEBUG.equals("cycle")) return SCENE_DEBUG;
            int step = (int) (Drawer.frameCounter() / 90 % (IrPack.SCENES.size() + 1));
            return step == IrPack.SCENES.size() ? null : IrPack.SCENES.get(step);
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc.getRenderViewEntity();
        if (mc.world == null || viewer == null) return null;
        if (viewer.isInsideOfMaterial(Material.WATER)) return "underwater";
        int dimension = mc.world.provider.getDimension();
        return dimension == -1 ? "nether" : dimension == 1 ? "end" : null;
    }

    private static void rebuild(IrPack pack) {
        if (!runtimes.isEmpty()) {
            Vulkan.waitIdle();
            destroyRuntimes();
        }
        builtFor = pack;
        rtWarned = false;
        if (pack == null) return;
        buildVariant(pack, pack);
    }

    private static PackRuntime buildVariant(IrPack pack, IrPack variant) {
        long start = System.nanoTime();
        try (PackSource src = PackManager.openActive()) {
            PackRuntime runtime = new PackRuntime(FramePlan.build(variant), src);
            runtimes.put(variant, runtime);
            for (String w : runtime.plan.warnings) Initializer.LOGGER.warn("[shaderpacks] {}: {}", pack.id, w);
            Initializer.LOGGER.info("[shaderpacks] {} ready: {}, {} pass(es) in {} ms", pack.id, variant.name,
                    runtime.steps.length, (System.nanoTime() - start) / 1_000_000);
            return runtime;
        } catch (IOException | RuntimeException e) {
            destroyRuntimes();
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            Initializer.LOGGER.error("[shaderpacks] {} failed to build:\n{}", pack.id, msg);
            PackManager.reportRuntimeError(msg);
            return null;
        }
    }

    /** Device idle (shutdown or swapchain teardown). */
    static void destroy() {
        net.vesu.shader.PbrAtlases.destroy();
        destroyRuntimes();
        builtFor = null;
    }

    private static void destroyRuntimes() {
        for (PackRuntime r : runtimes.values()) r.destroyAll();
        runtimes.clear();
        current = null;
    }

    /** True when the pack asked for an RGBA16F scene ({@code renderer.hdr}). */
    boolean hdrScene() {
        return plan.hdrScene;
    }

    private int sceneFormat() {
        return plan.hdrScene ? FrameTargets.HDR_SCENE_FORMAT : FrameTargets.COLOR_FORMAT;
    }

    /** True when the runtime of this frame samples the motion vectors. */
    boolean needsMotion() {
        return plan.hostInputs.contains("motion");
    }

    // ------------------------------------------------------------------------------------

    /** One image of a pack texture with its tracked state. */
    static final class Image {
        long image, memory, view, level0View;
        int width, height, mips, format;
        int layout = VK_IMAGE_LAYOUT_UNDEFINED;
        long stage = VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, access;
        boolean pendingClear, pendingUpload;
    }

    private final FramePlan plan;
    private final IrPack pack;
    private final StepGpu[] steps;
    private final Image[][] images;
    private final int[] vkFormats;
    private final int[] usages;
    private final long hostVertexModule;
    /** Decoded RGBA pixels of the file textures, by texture; null for render textures. */
    private final ByteBuffer[] filePixels;
    /** Staging buffers of this allocation's uploads, freed with the images. */
    private final List<long[]> staging = new ArrayList<>();
    private final long descriptorPool;
    private final long[] frameUbo, frameUboMemory, frameUboAddress;
    /** Center depth readback per frame in flight, or null: only packs that read centerDepthSmooth pay. */
    private long[] centerDepth, centerDepthMemory, centerDepthAddress;
    private float centerDepthHalflife, centerDepthSmooth = 1.0f;
    private long centerDepthNanos;
    private final long linearSampler, nearestSampler, linearMipSampler, nearestMipSampler, linearRepeatSampler, nearestRepeatSampler;
    /** Copy of the scene color when the combination samples what it overwrites. */
    private final StorageImage sceneCopy;
    private final boolean postReadsScene, readsDepth, deferredReadsScene, deferredReadsDepth;
    /** Depth before the translucent layer, for depthtex1. */
    private Image depthCopy;
    /** Depth before the hand, for depthtex2. */
    private Image handDepthCopy;
    private final float handDepth;
    /** The shadow map, null without one. */
    private final ShadowPass shadow;
    /** Custom uniforms, evaluated per frame into the tail of the Frame block; null without any. */
    private final net.vesu.shader.PackUniforms uniforms;

    // Geometry (null or 0 without gbuffers programs).
    private long mrtBits;
    /** Texture of each attachment after the scene color. */
    private int[] geometryTextures = new int[0];
    private boolean[] clearOnBegin = new boolean[0];
    private long geometrySetLayout;
    /** [OPAQUE or TRANSLUCENT][frame in flight]. */
    private long[][] geometrySets;
    private int geometryPhase;
    private final List<Pipeline.Program> programs = new ArrayList<>();
    private final Map<Pipeline, Pipeline.Program[]> programTables = new IdentityHashMap<>();
    /** This frame's clears are armed and images exist; the Frame block is uploaded; DEFERRED ran. */
    private boolean frameStarted, frameUploaded, deferredDone, timerStarted, setupDone;
    private boolean[] setsWritten;
    private int boundGeneration = -1;

    private final GpuPassTimer timer;
    /** Null without {@code resources}. */
    private final PackResources resources;

    private static final class StepGpu {
        final FramePlan.Step step;
        final long setLayout, pipelineLayout, pipeline;
        final int pushStages;
        final ComputePipeline compute;
        final long[] sets;
        final boolean usesResources;

        StepGpu(FramePlan.Step step, long setLayout, long pipelineLayout, long pipeline, int pushStages,
                ComputePipeline compute, long[] sets, boolean usesResources) {
            this.step = step;
            this.setLayout = setLayout;
            this.pipelineLayout = pipelineLayout;
            this.pipeline = pipeline;
            this.pushStages = pushStages;
            this.compute = compute;
            this.sets = sets;
            this.usesResources = usesResources;
        }
    }

    private PackRuntime(FramePlan plan, PackSource src) throws IOException {
        this.plan = plan;
        this.pack = plan.pack;
        resources = PackResources.of(Resources.of(pack.raw), src);
        sceneCopy = new StorageImage(sceneFormat(), VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        VkDevice device = Vulkan.getDevice();
        int frames = Drawer.framesInFlight();
        int textureCount = pack.textures.size();

        vkFormats = new int[textureCount];
        usages = new int[textureCount];
        for (int t = 0; t < textureCount; t++) {
            vkFormats[t] = vkFormat(pack.textures.get(t).format);
            usages[t] = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        }
        for (FramePlan.Step s : plan.steps) {
            for (FramePlan.ImageRef out : s.outputs()) {
                usages[out.texture()] |= s.pass().isCompute()
                        ? VK_IMAGE_USAGE_STORAGE_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            }
            for (FramePlan.ImageRef stored : s.storage()) usages[stored.texture()] |= VK_IMAGE_USAGE_STORAGE_BIT;
        }
        for (FramePlan.ImageRef stored : plan.geometryStorage.get(FramePlan.OPAQUE)) usages[stored.texture()] |= VK_IMAGE_USAGE_STORAGE_BIT;
        for (int t = 0; t < textureCount; t++) checkFormatSupport(t);
        images = new Image[textureCount][];
        filePixels = new ByteBuffer[textureCount];
        for (int t = 0; t < textureCount; t++) {
            if (pack.textures.get(t).file != null) filePixels[t] = decodePng(pack.textures.get(t), src);
        }

        for (String t : plan.geometryTargets) {
            usages[plan.pack.textures.indexOf(pack.texture(t))] |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        }
        uniforms = pack.raw.has("uniforms") ? new net.vesu.shader.PackUniforms(pack.raw.getAsJsonArray("uniforms")) : null;
        boolean scene = false, depth = false, deferredScene = false, deferredDepth = false;
        for (int i = plan.prologueSteps; i < plan.steps.size(); i++) {
            FramePlan.Step s = plan.steps.get(i);
            boolean deferred = i < plan.prologueSteps + plan.deferredSteps;
            for (FramePlan.Input in : s.inputs()) {
                if ("sceneColor".equals(in.host())) {
                    if (deferred) deferredScene = true;
                    else if (!s.combination()) scene = true;
                }
                if ("sceneDepth".equals(in.host()) || "depthtex0".equals(in.host())) {
                    if (deferred) deferredDepth = true;
                    else depth = true;
                }
            }
        }
        postReadsScene = scene;
        readsDepth = depth;
        deferredReadsScene = deferredScene;
        deferredReadsDepth = deferredDepth;
        handDepth = pack.renderer.has("handDepth") ? pack.renderer.get("handDepth").getAsFloat() : 0.125f;

        linearSampler = createSampler(VK_FILTER_LINEAR, false);
        nearestSampler = createSampler(VK_FILTER_NEAREST, false);
        linearMipSampler = createSampler(VK_FILTER_LINEAR, true);
        nearestMipSampler = createSampler(VK_FILTER_NEAREST, true);
        linearRepeatSampler = createSampler(VK_FILTER_LINEAR, false, VK_SAMPLER_ADDRESS_MODE_REPEAT);
        nearestRepeatSampler = createSampler(VK_FILTER_NEAREST, false, VK_SAMPLER_ADDRESS_MODE_REPEAT);

        frameUbo = new long[frames];
        frameUboMemory = new long[frames];
        frameUboAddress = new long[frames];
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            for (int f = 0; f < frames; f++) {
                MemoryManager.createBufferHostAccess(FrameBlock.LAYOUT.size(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                        pBuffer, pMemory);
                frameUbo[f] = pBuffer.get(0);
                frameUboMemory[f] = pMemory.get(0);
                frameUboAddress[f] = MemoryManager.Map(frameUboMemory[f]).get(0);
            }
            if (pack.renderer.has("centerDepthHalflife")) {
                centerDepthHalflife = pack.renderer.get("centerDepthHalflife").getAsFloat();
                centerDepth = new long[frames];
                centerDepthMemory = new long[frames];
                centerDepthAddress = new long[frames];
                for (int f = 0; f < frames; f++) {
                    MemoryManager.createBufferHostAccess(4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, pBuffer, pMemory);
                    centerDepth[f] = pBuffer.get(0);
                    centerDepthMemory[f] = pMemory.get(0);
                    centerDepthAddress[f] = MemoryManager.Map(centerDepthMemory[f]).get(0);
                    MemoryUtil.memPutFloat(centerDepthAddress[f], 1.0f);
                }
            }
        }

        // One pool for every pass x frame in flight.
        int samplers = 0, storages = 0;
        for (FramePlan.Step s : plan.steps) {
            samplers += s.inputs().size();
            storages += s.pass().isCompute() ? s.outputs().size() : 0;
        }
        samplers += plan.geometryInputs.get(FramePlan.OPAQUE).size() * 2;
        int setCount = (plan.steps.size() + 2) * frames;
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(setCount);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(Math.max(1, samplers * frames));
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(Math.max(1, storages * frames));
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(setCount).pPoolSizes(sizes), null, p), "pack descriptor pool");
            descriptorPool = p.get(0);
        }

        ShaderSPIRVUtils.SPIRV vs = ShaderSPIRVUtils.compileShader("vkm_host_triangle.vsh", Prelude.hostVertexShader(),
                ShaderSPIRVUtils.ShaderKind.VERTEX_SHADER);
        hostVertexModule = createModule(vs.bytecode());
        vs.free();

        shadow = plan.shadowProgram != null ? new ShadowPass(plan) : null;
        IncludeExpander expander = new IncludeExpander(src);
        steps = new StepGpu[plan.steps.size()];
        try {
            if (warmed == null || !warmed.id.equals(pack.id)) precompile(pack, plan, expander);
            for (int i = 0; i < steps.length; i++) {
                steps[i] = buildStep(plan.steps.get(i), expander, frames);
            }
            if (!plan.routes.isEmpty() || plan.sceneTarget != null) buildGeometry(expander, frames);
            if (plan.routes.containsKey(GeometryCategory.LOD_OPAQUE) && geometrySetLayout != 0L) buildLod(expander);
        } catch (RuntimeException | IOException e) {
            destroyAll();
            throw e;
        }
        timer = FrameProfiler.ENABLED ? new GpuPassTimer(pack.id, steps.length, frames) : null;
    }

    private record CompileJob(String name, String source, ShaderSPIRVUtils.ShaderKind kind) {
    }

    private record MiddleStage(String program, ShaderSPIRVUtils.ShaderKind kind) {
    }

    /** The stages of {@code g} between vertex and fragment, tessellation pair first. */
    private static List<MiddleStage> middleStages(IrPack.Gbuffer g) {
        return middleStages(g.tessControl, g.tessEval, g.geometry);
    }

    private static List<MiddleStage> middleStages(String tessControl, String tessEval, String geometry) {
        List<MiddleStage> stages = new ArrayList<>(3);
        if (tessControl != null) {
            stages.add(new MiddleStage(tessControl, ShaderSPIRVUtils.ShaderKind.TESS_CONTROL_SHADER));
            stages.add(new MiddleStage(tessEval, ShaderSPIRVUtils.ShaderKind.TESS_EVAL_SHADER));
        }
        if (geometry != null) stages.add(new MiddleStage(geometry, ShaderSPIRVUtils.ShaderKind.GEOMETRY_SHADER));
        return stages;
    }

    /**
     * Compiles every program on all cores into the SPIR-V disk cache; the building below then
     * finds them there. A large pack has hundreds of (program, vanilla pipeline) pairs.
     */
    private static void precompile(IrPack pack, FramePlan plan, IncludeExpander expander) throws IOException {
        List<CompileJob> jobs = new ArrayList<>();
        for (FramePlan.Step s : plan.steps) {
            IrPack.Pass pass = s.pass();
            String program = pass.isCompute() ? pass.compute : pass.fragment;
            jobs.add(new CompileJob(pack.id + "/" + program, expander.expandProgram(program, Prelude.forStep(pack, s)),
                    pass.isCompute() ? ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER : ShaderSPIRVUtils.ShaderKind.FRAGMENT_SHADER));
            if (pass.vertex != null) {
                jobs.add(new CompileJob(pack.id + "/" + pass.vertex, expander.expandProgram(pass.vertex, Prelude.forVertex(pack, s)),
                        ShaderSPIRVUtils.ShaderKind.VERTEX_SHADER));
            }
            for (MiddleStage m : middleStages(pass.tessControl, pass.tessEval, pass.geometry)) {
                jobs.add(new CompileJob(pack.id + "/" + m.program, expander.expandProgram(m.program, Prelude.forVertex(pack, s)), m.kind));
            }
        }
        for (Pipeline pipeline : Pipeline.routable()) {
            java.util.Set<IrPack.Gbuffer> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (GeometryCategory c : categories(pipeline.routeKind())) {
                IrPack.Gbuffer g = plan.routes.get(c);
                if (g == null || !seen.add(g)) continue;
                jobs.add(new CompileJob(pack.id + "/" + g.vertex + "#" + pipeline.getPath(),
                        expander.expandProgram(g.vertex, GeometryPrelude.vertex(plan, g, hostSource(pipeline.getPath() + ".vsh"))),
                        ShaderSPIRVUtils.ShaderKind.VERTEX_SHADER));
                jobs.add(new CompileJob(pack.id + "/" + g.fragment + "#" + pipeline.getPath(),
                        expander.expandProgram(g.fragment, GeometryPrelude.fragment(plan, g, hostSource(pipeline.getPath() + ".fsh"))),
                        ShaderSPIRVUtils.ShaderKind.FRAGMENT_SHADER));
                for (MiddleStage m : middleStages(g)) {
                    jobs.add(new CompileJob(pack.id + "/" + m.program + "#" + pipeline.getPath(),
                            expander.expandProgram(m.program, GeometryPrelude.stage(plan, g, hostSource(pipeline.getPath() + ".fsh"))), m.kind));
                }
            }
        }
        compileTotal.addAndGet(jobs.size());
        // Errors surface in the serial pass, with the usual message.
        jobs.parallelStream().forEach(j -> {
            try {
                ShaderSPIRVUtils.compilePackShader(j.name, j.source, j.kind).free();
            } catch (RuntimeException ignored) {
                // Reported by the build that follows.
            }
            compileDone.incrementAndGet();
        });
    }

    /** The categories a vanilla pipeline can draw (runtime.md, "Draw categories"). */
    private static GeometryCategory[] categories(Pipeline.RouteKind kind) {
        return switch (kind) {
            case TERRAIN -> new GeometryCategory[]{GeometryCategory.TERRAIN, GeometryCategory.WATER, GeometryCategory.SHADOW};
            case SKY -> new GeometryCategory[]{GeometryCategory.SKY_BASIC};
            case SKY_TEXTURED -> new GeometryCategory[]{GeometryCategory.SKY_TEXTURED};
            case CLOUDS -> new GeometryCategory[]{GeometryCategory.CLOUDS};
            case BORDER -> new GeometryCategory[]{GeometryCategory.WORLD_BORDER};
            case MODEL -> new GeometryCategory[]{GeometryCategory.ENTITIES, GeometryCategory.BLOCK, GeometryCategory.HAND,
                    GeometryCategory.HAND_WATER, GeometryCategory.SHADOW, GeometryCategory.DAMAGED_BLOCK,
                    GeometryCategory.ENTITIES_GLOWING, GeometryCategory.SPIDER_EYES, GeometryCategory.BEACON_BEAM,
                    GeometryCategory.ENTITIES_TRANSLUCENT};
            case GLINT -> new GeometryCategory[]{GeometryCategory.ARMOR_GLINT};
            case PARTICLE -> new GeometryCategory[]{GeometryCategory.PARTICLES, GeometryCategory.WEATHER};
            case LINES -> new GeometryCategory[]{GeometryCategory.LINES};
            case NONE -> new GeometryCategory[0];
        };
    }

    private static String hostSource(String file) throws IOException {
        try (java.io.InputStream in = Pipeline.class.getResourceAsStream("/assets/vesu/shaders/" + file)) {
            if (in == null) throw new IOException("missing shader: " + file);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Compiles every (program, vanilla pipeline) pair the routes use and the shared set (D6). */
    private void buildGeometry(IncludeExpander expander, int frames) throws IOException {
        VkDevice device = Vulkan.getDevice();
        int attachments = plan.geometryTargets.size() + firstAttachment();
        if (attachments > 1 && !Vulkan.getDeviceInfo().features.independentBlend) {
            throw new IllegalArgumentException("this GPU cannot write several geometry targets (independentBlend)");
        }
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), props);
            if (attachments > props.limits().maxColorAttachments()) {
                throw new IllegalArgumentException("this GPU has " + props.limits().maxColorAttachments()
                        + " color attachments, the pack needs " + attachments);
            }
        }
        geometryTextures = new int[plan.geometryTargets.size()];
        clearOnBegin = new boolean[geometryTextures.length];
        int[] formats = new int[attachments];
        formats[0] = sceneFormat();
        for (int i = 0; i < geometryTextures.length; i++) {
            geometryTextures[i] = pack.textures.indexOf(pack.texture(plan.geometryTargets.get(i)));
            formats[i + firstAttachment()] = vkFormats[geometryTextures[i]];
        }
        mrtBits = Pipeline.registerMrt(formats);
        attachmentFormats = formats;

        List<FramePlan.Input> inputs = plan.geometryInputs.get(FramePlan.OPAQUE);
        int stages = VK_SHADER_STAGE_ALL_GRAPHICS;
        try (MemoryStack stack = stackPush()) {
            int stored = plan.geometryWrites.size();
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1 + inputs.size() + stored, stack);
            b.get(0).binding(Prelude.FRAME_BINDING).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).stageFlags(stages);
            for (int i = 0; i < inputs.size(); i++) {
                b.get(1 + i).binding(Prelude.inputBinding(i)).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(stages);
            }
            for (int j = 0; j < stored; j++) {
                b.get(1 + inputs.size() + j).binding(Prelude.writeBinding(inputs.size(), j))
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(stages);
            }
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, p), "pack geometry set layout");
            geometrySetLayout = p.get(0);

            geometrySets = new long[2][frames];
            LongBuffer layouts = stack.mallocLong(frames);
            for (int f = 0; f < frames; f++) layouts.put(f, geometrySetLayout);
            LongBuffer out = stack.mallocLong(frames);
            for (int phase = 0; phase < 2; phase++) {
                ComputeSets.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(descriptorPool).pSetLayouts(layouts), out), "pack geometry sets");
                for (int f = 0; f < frames; f++) geometrySets[phase][f] = out.get(f);
            }
        }

        for (Pipeline pipeline : Pipeline.routable()) {
            Pipeline.Program[] table = new Pipeline.Program[GeometryCategory.values().length];
            Map<IrPack.Gbuffer, Pipeline.Program> built = new IdentityHashMap<>();
            for (GeometryCategory c : categories(pipeline.routeKind())) {
                IrPack.Gbuffer g = plan.routes.get(c);
                if (g == null) continue;
                // Blob shadows under entities do not belong in a shadow map.
                if (c == GeometryCategory.SHADOW && pipeline.recordId().equals("entity_shadow")) continue;
                Pipeline.Program program = built.get(g);
                if (program == null) {
                    program = buildProgram(pipeline, g, expander);
                    built.put(g, program);
                    programs.add(program);
                }
                table[c.ordinal()] = program;
            }
            programTables.put(pipeline, table);
        }
    }

    private int attachmentCount() {
        return plan.geometryTargets.size() + firstAttachment();
    }

    // ------------------------------------------------------------------------------------
    // Distant terrain (AUDIT 5.16, contract B)

    private final LodPackProgram[] lodPrograms = new LodPackProgram[2];
    /** Distant terrain goes through the pack's terrain and water programs, into the scene depth. */
    private boolean lodThroughTerrain;
    private int[] attachmentFormats;
    /** The distant terrain's depth, in its own projection (dhDepthTex0/1); null without DH programs or inputs. */
    private Image dhDepth;
    private boolean dhDepthFresh;
    /** dhDepthTex1: the distant terrain's depth before dh_water; null without dh_water. */
    private Image dhDepthOpaque;
    /** The pack's shadow program against the LOD host source: distant terrain in the shadow map (lodPackShadows). */
    private LodPackProgram lodShadow;

    /** Compiles dh_terrain and dh_water against the LOD host source. A failure leaves the pack without LODs. */
    private void buildLod(IncludeExpander expander) {
        GeometryCategory[] categories = {GeometryCategory.LOD_OPAQUE, GeometryCategory.LOD_TRANSLUCENT};
        for (int i = 0; i < 2; i++) {
            IrPack.Gbuffer g = plan.routes.get(categories[i]);
            if (g == null) continue;
            boolean terrain = !g.name.contains("dh_");
            if (i == 0) {
                lodThroughTerrain = terrain;
            } else if (terrain != lodThroughTerrain) {
                // Both through the DH programs or both through the pack's own: one depth for both.
                continue;
            }
            if (g.geometry != null || g.tessControl != null) {
                Initializer.LOGGER.warn("[shaderpacks] {}: {} has stages the LOD renderer cannot run; no distant terrain through it",
                        pack.id, g.name);
                continue;
            }
            ShaderSPIRVUtils.SPIRV vs = null, fs = null;
            try {
                vs = ShaderSPIRVUtils.compilePackShader(pack.id + "/" + g.vertex + "#lod",
                        expander.expandProgram(g.vertex, GeometryPrelude.vertex(plan, g, hostSource("lod/pack_lod.vsh"))),
                        ShaderSPIRVUtils.ShaderKind.VERTEX_SHADER);
                fs = ShaderSPIRVUtils.compilePackShader(pack.id + "/" + g.fragment + "#lod",
                        expander.expandProgram(g.fragment, GeometryPrelude.fragment(plan, g, hostSource("lod/pack_lod.fsh"))),
                        ShaderSPIRVUtils.ShaderKind.FRAGMENT_SHADER);
                int mask = 0;
                for (String t : g.targets) mask |= 1 << plan.attachment(t);
                Pipeline.BlendState[] targetBlend = null;
                if (!g.targetBlend.isEmpty()) {
                    targetBlend = new Pipeline.BlendState[attachmentCount()];
                    for (Map.Entry<String, IrPack.Blend> e : g.targetBlend.entrySet()) {
                        if (g.targets.contains(e.getKey())) targetBlend[plan.attachment(e.getKey())] = blendState(e.getValue());
                    }
                }
                lodPrograms[i] = new LodPackProgram(vs.bytecode(), fs.bytecode(), mask, blendState(g.blend), targetBlend);
            } catch (RuntimeException | IOException e) {
                Initializer.LOGGER.warn("[shaderpacks] {}: {} cannot draw distant terrain: {}", pack.id, g.name, e.getMessage());
            } finally {
                if (vs != null) vs.free();
                if (fs != null) fs.free();
            }
        }
        buildLodShadow(expander);
    }

    /** The shadow program for distant terrain in the shadow map; a failure leaves the map without it. */
    private void buildLodShadow(IncludeExpander expander) {
        IrPack.Gbuffer g = plan.shadowProgram;
        if (shadow == null || g == null || g.geometry != null || g.tessControl != null) return;
        ShaderSPIRVUtils.SPIRV vs = null, fs = null;
        try {
            vs = ShaderSPIRVUtils.compilePackShader(pack.id + "/" + g.vertex + "#lod",
                    expander.expandProgram(g.vertex, GeometryPrelude.vertex(plan, g, hostSource("lod/pack_lod.vsh"))),
                    ShaderSPIRVUtils.ShaderKind.VERTEX_SHADER);
            fs = ShaderSPIRVUtils.compilePackShader(pack.id + "/" + g.fragment + "#lod",
                    expander.expandProgram(g.fragment, GeometryPrelude.fragment(plan, g, hostSource("lod/pack_lod.fsh"))),
                    ShaderSPIRVUtils.ShaderKind.FRAGMENT_SHADER);
            int mask = 0;
            for (String t : g.targets) {
                int at = plan.shadowTargets.indexOf(t);
                if (at >= 0) mask |= 1 << at;
            }
            lodShadow = new LodPackProgram(vs.bytecode(), fs.bytecode(), mask, null, null);
        } catch (RuntimeException | IOException e) {
            Initializer.LOGGER.warn("[shaderpacks] {}: {} cannot draw distant terrain: {}", pack.id, g.name, e.getMessage());
        } finally {
            if (vs != null) vs.free();
            if (fs != null) fs.free();
        }
    }

    /** Distant terrain draws through the pack's terrain programs (no DH programs), into the scene depth. */
    boolean lodThroughTerrain() {
        return lodPrograms[0] != null && lodThroughTerrain;
    }

    LodPackProgram lodProgram(boolean translucent) {
        return lodPrograms[translucent ? 1 : 0];
    }

    LodPackProgram lodShadowProgram() {
        return lodShadow;
    }

    ShadowPass shadowPass() {
        return shadow;
    }

    int[] attachmentFormats() {
        return attachmentFormats;
    }

    long geometrySetLayout() {
        return geometrySetLayout;
    }

    long resourceSetLayout() {
        return resources != null ? resources.setLayout() : 0L;
    }

    long dhDepthView() {
        return dhDepth != null ? dhDepth.view : 0L;
    }

    long dhDepthImage() {
        return dhDepth != null ? dhDepth.image : 0L;
    }

    private boolean wantsDhDepth() {
        return (lodPrograms[0] != null && !lodThroughTerrain) || plan.hostInputs.contains("dhDepthTex0") || plan.hostInputs.contains("dhDepthTex1");
    }

    /** Each frame, before any geometry: no distant terrain yet, so the depth is all far. */
    private void clearDhDepth(VkCommandBuffer cmd, MemoryStack stack) {
        if (dhDepth != null) clearDepth(cmd, dhDepth.image, stack);
        if (dhDepthOpaque != null) clearDepth(cmd, dhDepthOpaque.image, stack);
    }

    private static void clearDepth(VkCommandBuffer cmd, long image, MemoryStack stack) {
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(b.get(0), image, VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT, 0,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, b, stack);
        VkClearDepthStencilValue clear = VkClearDepthStencilValue.calloc(stack).depth(1.0f).stencil(0);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1);
        vkCmdClearDepthStencilImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
        FrameTargets.imageBarrier(b.get(0), image, VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                        | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, b, stack);
    }

    /** Rendering is not active, before dh_water: dhDepthTex1 keeps the depth without distant water. */
    void snapshotDhDepth(VkCommandBuffer cmd, MemoryStack stack) {
        if (dhDepthOpaque == null) return;
        long read = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        long tests = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
        long rw = VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(b.get(0), dhDepth.image, VK_IMAGE_ASPECT_DEPTH_BIT, read | tests, 0,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        FrameTargets.imageBarrier(b.get(1), dhDepthOpaque.image, VK_IMAGE_ASPECT_DEPTH_BIT, read, 0,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, b, stack);
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
        region.get(0).extent().width(dhDepth.width).height(dhDepth.height).depth(1);
        vkCmdCopyImage(cmd, dhDepth.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dhDepthOpaque.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        FrameTargets.imageBarrier(b.get(0), dhDepth.image, VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, 0, read | tests, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | rw,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        FrameTargets.imageBarrier(b.get(1), dhDepthOpaque.image, VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT, read, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, b, stack);
    }

    private long dhDepthTex1View() {
        return (dhDepthOpaque != null ? dhDepthOpaque : dhDepth).view;
    }

    /** Rendering is not active: the distant terrain's depth becomes an attachment, or readable again. */
    void dhDepthBarrier(VkCommandBuffer cmd, boolean toAttachment, MemoryStack stack) {
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
        long read = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        long tests = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
        long rw = VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        if (toAttachment) {
            FrameTargets.imageBarrier(b.get(0), dhDepth.image, VK_IMAGE_ASPECT_DEPTH_BIT, read | tests, rw, tests, rw,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        } else {
            FrameTargets.imageBarrier(b.get(0), dhDepth.image, VK_IMAGE_ASPECT_DEPTH_BIT, tests, VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    read | tests, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT | rw,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        FrameTargets.pipelineBarrier(cmd, b, stack);
    }

    private Pipeline.Program buildProgram(Pipeline pipeline, IrPack.Gbuffer g, IncludeExpander expander) throws IOException {
        DeviceFeatures features = Vulkan.getDeviceInfo().features;
        if ((g.geometry != null && !features.geometryShader) || (g.tessControl != null && !features.tessellationShader)) {
            throw new IOException(g.name + " needs a shader stage this GPU does not have");
        }
        List<ShaderSPIRVUtils.SPIRV> compiled = new ArrayList<>();
        try {
            ShaderSPIRVUtils.SPIRV vs = ShaderSPIRVUtils.compilePackShader(pack.id + "/" + g.vertex + "#" + pipeline.getPath(),
                    expander.expandProgram(g.vertex, GeometryPrelude.vertex(plan, g, hostSource(pipeline.getPath() + ".vsh"))),
                    ShaderSPIRVUtils.ShaderKind.VERTEX_SHADER);
            compiled.add(vs);
            ShaderSPIRVUtils.SPIRV fs = ShaderSPIRVUtils.compilePackShader(pack.id + "/" + g.fragment + "#" + pipeline.getPath(),
                    expander.expandProgram(g.fragment, GeometryPrelude.fragment(plan, g, hostSource(pipeline.getPath() + ".fsh"))),
                    ShaderSPIRVUtils.ShaderKind.FRAGMENT_SHADER);
            compiled.add(fs);
            Map<ShaderSPIRVUtils.ShaderKind, ByteBuffer> middle = new java.util.EnumMap<>(ShaderSPIRVUtils.ShaderKind.class);
            for (MiddleStage m : middleStages(g)) {
                ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compilePackShader(pack.id + "/" + m.program + "#" + pipeline.getPath(),
                        expander.expandProgram(m.program, GeometryPrelude.stage(plan, g, hostSource(pipeline.getPath() + ".fsh"))), m.kind);
                compiled.add(spv);
                middle.put(m.kind, spv.bytecode());
            }
            int mask = 0;
            for (String t : g.targets) mask |= 1 << plan.attachment(t);
            Pipeline.BlendState[] targetBlend = null;
            if (!g.targetBlend.isEmpty()) {
                targetBlend = new Pipeline.BlendState[attachmentCount()];
                for (Map.Entry<String, IrPack.Blend> e : g.targetBlend.entrySet()) {
                    if (g.targets.contains(e.getKey())) targetBlend[plan.attachment(e.getKey())] = blendState(e.getValue());
                }
            }
            return pipeline.createProgram(vs.bytecode(), fs.bytecode(), middle.get(ShaderSPIRVUtils.ShaderKind.GEOMETRY_SHADER),
                    middle.get(ShaderSPIRVUtils.ShaderKind.TESS_CONTROL_SHADER), middle.get(ShaderSPIRVUtils.ShaderKind.TESS_EVAL_SHADER),
                    geometrySetLayout, resources != null ? resources.setLayout() : 0L, mask, blendState(g.blend), targetBlend);
        } finally {
            compiled.forEach(ShaderSPIRVUtils.SPIRV::free);
        }
    }

    private static Pipeline.BlendState blendState(IrPack.Blend blend) {
        if (blend == null) return null;
        if (!blend.enabled) return Pipeline.NO_BLEND_STATE;
        return new Pipeline.BlendState(true, blendFactor(blend.factors[0]), blendFactor(blend.factors[1]),
                blendFactor(blend.factors[2]), blendFactor(blend.factors[3]));
    }

    private void activatePrograms() {
        for (Map.Entry<Pipeline, Pipeline.Program[]> e : programTables.entrySet()) e.getKey().packPrograms = e.getValue();
    }

    // ------------------------------------------------------------------------------------
    // Geometry, per frame

    boolean hasGeometry() {
        return plan.hasGeometry();
    }

    /** The set of the geometry programs before {@code DEFERRED}: the shadow program's. */
    long opaqueSet() {
        return geometrySets[FramePlan.OPAQUE][Drawer.getCurrentFrame() % frameUbo.length];
    }

    /** Opens the frame's shadow command buffer. File textures it samples are uploaded there, ahead of everything. */
    void beginShadow(MemoryStack stack) {
        if (shadow == null) return;
        startFrame();
        VkCommandBuffer cmd = shadow.open();
        for (String name : plan.shadowProgram.inputs) {
            int t = pack.textures.indexOf(pack.texture(name));
            Image img = images[t][0];
            if (img.pendingUpload) {
                upload(cmd, img, filePixels[t], stack);
                Barriers b = new Barriers(1, stack);
                b.use(img, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, false);
                b.flush(cmd);
            }
        }
        if (resources != null) resources.begin(cmd, stack);
        recordBegin(cmd, stack);
        // The shadow program runs first: what it stores to must be ready here.
        Barriers stored = new Barriers(plan.geometryWrites.size(), stack);
        for (FramePlan.ImageRef ref : plan.geometryStorage.get(FramePlan.OPAQUE)) {
            clearIfPending(cmd, image(ref), stack);
            stored.use(image(ref), VK_IMAGE_LAYOUT_GENERAL, GEOMETRY_STORAGE_STAGES, STORAGE_ACCESS, false);
        }
        stored.flush(cmd);
        shadow.start(stack);
    }

    /** {@code SETUP} and {@code BEGIN} come before the shadow pass, so they go first in the buffer that runs first. */
    private void recordBegin(VkCommandBuffer cmd, MemoryStack stack) {
        if (plan.prologueSteps == 0) return;
        uploadFrame(cmd);
        uploadFiles(cmd, stack);
        int frame = Drawer.getCurrentFrame() % frameUbo.length;
        for (int s = 0; s < plan.prologueSteps; s++) {
            IrPack.Stage stage = steps[s].step.stage();
            if (stage != IrPack.Stage.SETUP && stage != IrPack.Stage.BEGIN || stage == IrPack.Stage.SETUP && setupDone) continue;
            recordStep(cmd, steps[s], frame, stack);
            if (timer != null) timer.mark(cmd, s);
        }
        setupDone = true;
    }

    void shadowTranslucent(MemoryStack stack) {
        if (shadow != null) shadow.enterTranslucent(stack);
    }

    int vertexFlags() {
        return plan.vertexFlags;
    }

    long mrtBits() {
        return mrtBits;
    }

    float handDepth() {
        return handDepth;
    }

    /** {@code renderStage} of a pack program's draw, a {@code MC_RENDER_STAGE_*} value. */
    static int renderStage(Pipeline pipeline) {
        return switch (pipeline.routeKind()) {
            case TERRAIN -> {
                BlockRenderLayer layer = TerrainRenderer.currentLayer;
                if (layer == null) yield 0;
                yield switch (layer) {
                    case SOLID -> 8;
                    case CUTOUT_MIPPED -> 9;
                    case CUTOUT -> 10;
                    case TRANSLUCENT -> 17;
                };
            }
            case SKY, SKY_TEXTURED -> net.vesu.render.SkyRenderer.stage;
            case CLOUDS -> 20;
            case BORDER -> 22;
            case MODEL, GLINT -> switch (EntityRenderer.category()) {
                case DAMAGED_BLOCK -> 13;
                case HAND -> 16;
                case HAND_WATER -> 23;
                case BLOCK, BEACON_BEAM -> 12;
                default -> 11;
            };
            case PARTICLE -> EntityRenderer.isParticlePass() ? 19 : 21;
            case LINES -> 14;
            case NONE -> 0;
        };
    }

    /** The program of the draw {@code pipeline} is about to do, or null for its vanilla shader. */
    Pipeline.Program program(Pipeline pipeline) {
        // Entity drop shadows keep their own shader. Routed into a pack's G-buffer they
        // would be re-shaded by the pack's lighting pass into a solid black disc; the blob
        // draws are instead buffered by EntityRenderer and blended into the relit scene
        // right after the DEFERRED passes (see flushEntityBlobs).
        if (pipeline.recordId().equals("entity_shadow")) return null;
        Pipeline.Program[] table = pipeline.packPrograms;
        if (table == null) return null;
        GeometryCategory category = switch (pipeline.routeKind()) {
            // The frame a pack starts in, the arena still holds vertices of the old format.
            case TERRAIN -> ChunkBatcher.vertexFlags() != plan.vertexFlags ? null
                    : TerrainRenderer.currentLayer == BlockRenderLayer.TRANSLUCENT
                    ? GeometryCategory.WATER : GeometryCategory.TERRAIN;
            case SKY -> GeometryCategory.SKY_BASIC;
            case SKY_TEXTURED -> GeometryCategory.SKY_TEXTURED;
            case CLOUDS -> GeometryCategory.CLOUDS;
            case BORDER -> GeometryCategory.WORLD_BORDER;
            case MODEL -> EntityRenderer.category();
            case GLINT -> GeometryCategory.ARMOR_GLINT;
            case PARTICLE -> EntityRenderer.isParticlePass() ? GeometryCategory.PARTICLES : GeometryCategory.WEATHER;
            case LINES -> GeometryCategory.LINES;
            case NONE -> null;
        };
        return category == null ? null : table[category.ordinal()];
    }

    /** The shared set of the geometry programs for this frame and phase. */
    long geometrySet() {
        return geometrySets[geometryPhase][Drawer.getCurrentFrame() % frameUbo.length];
    }

    /** The attachment of the first geometry target: 1 after the scene color, 0 when a texture replaces it. */
    private int firstAttachment() {
        return plan.sceneTarget != null ? 0 : 1;
    }

    int colorAttachments() {
        return geometryTextures.length + firstAttachment();
    }

    /** Attachment 0 is the pack's {@code renderer.sceneTarget}. */
    boolean replacesSceneColor() {
        return plan.sceneTarget != null;
    }

    /** Fills the color attachments of the scene's rendering info, except a host scene color. */
    void writeAttachments(VkRenderingAttachmentInfo.Buffer color) {
        for (int i = 0; i < geometryTextures.length; i++) {
            int t = geometryTextures[i];
            VkRenderingAttachmentInfo a = color.get(i + firstAttachment()).sType$Default()
                    .imageView(images[t][plan.geometryImages[geometryPhase][i]].level0View)
                    .imageLayout(attachedStorage(geometryPhase, i) ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(clearOnBegin[i] ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            if (clearOnBegin[i]) setClear(a.clearValue().color(), t);
            clearOnBegin[i] = false;
        }
    }

    /** The prologue left after {@link #beginShadow}, in the main command buffer, ahead of the scene. */
    void recordPrologue(VkCommandBuffer cmd, MemoryStack stack) {
        if (shadow == null) {
            if (resources == null && plan.prologueSteps == 0) return;
            startFrame();
            if (resources != null) resources.begin(cmd, stack);
            recordBegin(cmd, stack);
        }
        if (dhDepthFresh) {
            // A pass before the scene reads the last frame's DH depth: a new image has none yet.
            dhDepthFresh = false;
            clearDhDepth(cmd, stack);
        }
        if (plan.prologueSteps == 0) return;
        uploadFrame(cmd);
        uploadFiles(cmd, stack);
        int frame = Drawer.getCurrentFrame() % frameUbo.length;
        for (int s = 0; s < plan.prologueSteps; s++) {
            IrPack.Stage stage = steps[s].step.stage();
            if (stage == IrPack.Stage.SETUP || stage == IrPack.Stage.BEGIN) continue;
            recordStep(cmd, steps[s], frame, stack);
            if (timer != null) timer.mark(cmd, s);
        }
    }

    void bindResources(VkCommandBuffer cmd, int bindPoint, long layout) {
        if (resources == null) return;
        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(cmd, bindPoint, layout, Resources.SET, stack.longs(resources.set()), null);
        }
    }

    void beginGeometry(VkCommandBuffer cmd, MemoryStack stack) {
        startFrame();
        clearDhDepth(cmd, stack);
        if (resources != null) resources.barrier(cmd, stack);
        uploadFiles(cmd, stack);
        geometryPhase = FramePlan.OPAQUE;
        for (Image depth : new Image[]{depthCopy, handDepthCopy}) {
            if (depth != null && depth.layout == VK_IMAGE_LAYOUT_UNDEFINED) {
                VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
                FrameTargets.imageBarrier(b.get(0), depth.image, FrameTargets.depthAspect(),
                        VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                FrameTargets.pipelineBarrier(cmd, b, stack);
                depth.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            }
        }
        Barriers barriers = geometryBarriers(cmd, FramePlan.OPAQUE, stack);
        for (int i = 0; i < geometryTextures.length; i++) {
            Image img = images[geometryTextures[i]][plan.geometryImages[FramePlan.OPAQUE][i]];
            clearOnBegin[i] = pack.textures.get(geometryTextures[i]).clearColor != null || img.pendingClear;
            img.pendingClear = false;
            useAttachment(barriers, img, FramePlan.OPAQUE, i, clearOnBegin[i]);
        }
        barriers.flush(cmd);
    }

    private static final long GEOMETRY_STORAGE_STAGES = VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_PRE_RASTERIZATION_SHADERS_BIT
            | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
    private static final long STORAGE_ACCESS = VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
    private static final long ATTACHMENT_ACCESS = VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;

    /** A geometry target that a program also stores to: it spends the phase in GENERAL. */
    private boolean attachedStorage(int phase, int target) {
        return plan.geometryStorage.get(phase).contains(new FramePlan.ImageRef(geometryTextures[target], plan.geometryImages[phase][target]));
    }

    private void useAttachment(Barriers barriers, Image img, int phase, int target, boolean discard) {
        if (attachedStorage(phase, target)) {
            barriers.use(img, VK_IMAGE_LAYOUT_GENERAL, GEOMETRY_STORAGE_STAGES, STORAGE_ACCESS | ATTACHMENT_ACCESS, discard);
        } else {
            barriers.use(img, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, ATTACHMENT_ACCESS, discard);
        }
    }

    /** Sampled textures of the geometry programs, ready to read. */
    private Barriers geometryBarriers(VkCommandBuffer cmd, int phase, MemoryStack stack) {
        List<FramePlan.Input> inputs = plan.geometryInputs.get(phase);
        for (FramePlan.Input in : inputs) {
            if (!in.isHost()) clearIfPending(cmd, image(in.image()), stack);
        }
        List<FramePlan.ImageRef> stored = plan.geometryStorage.get(phase);
        Barriers barriers = new Barriers(inputs.size() + geometryTextures.length + stored.size(), stack);
        java.util.Set<FramePlan.ImageRef> attached = new java.util.HashSet<>();
        for (int i = 0; i < geometryTextures.length; i++) attached.add(new FramePlan.ImageRef(geometryTextures[i], plan.geometryImages[phase][i]));
        for (FramePlan.ImageRef ref : stored) {
            if (attached.contains(ref)) continue;
            clearIfPending(cmd, image(ref), stack);
            barriers.use(image(ref), VK_IMAGE_LAYOUT_GENERAL, GEOMETRY_STORAGE_STAGES, STORAGE_ACCESS, false);
        }
        for (FramePlan.Input in : inputs) {
            if (!in.isHost() && !stored.contains(in.image())) barriers.use(image(in.image()), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, false);
        }
        return barriers;
    }

    /** True when the frame must leave rendering before the translucent layer. */
    boolean usesTranslucentHook() {
        return plan.deferredSteps > 0 || plan.depthCopy || !plan.snapshots.isEmpty() || centerDepth != null;
    }

    /** Textures from the pack and generated ones, once, before anything samples them. */
    private void uploadFiles(VkCommandBuffer cmd, MemoryStack stack) {
        for (int t = 0; t < images.length; t++) {
            if (filePixels[t] != null && images[t][0].pendingUpload) upload(cmd, images[t][0], filePixels[t], stack);
        }
    }

    /** Rendering is not active. The DEFERRED passes and the depth copy, once per frame. */
    void recordDeferred(VkCommandBuffer cmd, MemoryStack stack) {
        if (deferredDone) return;
        deferredDone = true;
        startFrame();
        uploadFrame(cmd);
        uploadFiles(cmd, stack);
        if (plan.depthCopy) FrameTargets.copySceneDepth(cmd, depthCopy.image, stack);
        if (centerDepth != null) {
            FrameTargets.copyCenterDepth(cmd, centerDepth[Drawer.getCurrentFrame() % centerDepth.length], stack);
        }
        if (plan.deferredSteps == 0) return;
        if (deferredReadsDepth) FrameTargets.sceneDepthToShaderRead(cmd, stack);
        if (deferredReadsScene) sceneBarrier(cmd, true, stack);
        int frame = Drawer.getCurrentFrame() % frameUbo.length;
        for (int s = plan.prologueSteps; s < plan.prologueSteps + plan.deferredSteps; s++) {
            recordStep(cmd, steps[s], frame, stack);
            if (timer != null) timer.mark(cmd, s);
        }
        if (deferredReadsScene) sceneBarrier(cmd, false, stack);
    }

    private static void sceneBarrier(VkCommandBuffer cmd, boolean toRead, MemoryStack stack) {
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
        long attachmentStage = VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
        long readStage = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        long attachmentAccess = VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
        if (toRead) {
            FrameTargets.imageBarrier(b.get(0), FrameTargets.scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                    attachmentStage, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, readStage, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        } else {
            FrameTargets.imageBarrier(b.get(0), FrameTargets.scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                    readStage, 0, attachmentStage, attachmentAccess,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        FrameTargets.pipelineBarrier(cmd, b, stack);
    }

    /** After the DEFERRED passes: the images the translucent geometry draws into. */
    void resumeGeometry(VkCommandBuffer cmd, MemoryStack stack) {
        if (!hasGeometry()) return;
        geometryPhase = FramePlan.TRANSLUCENT;
        for (int t : plan.snapshots) {
            int target = plan.geometryTargets.indexOf(pack.textures.get(t).name);
            copyImage(cmd, images[t][plan.geometryImages[FramePlan.TRANSLUCENT][target]], images[t][FramePlan.SNAPSHOT], stack);
        }
        Barriers barriers = geometryBarriers(cmd, FramePlan.TRANSLUCENT, stack);
        for (int i = 0; i < geometryTextures.length; i++) {
            useAttachment(barriers, images[geometryTextures[i]][plan.geometryImages[FramePlan.TRANSLUCENT][i]], FramePlan.TRANSLUCENT, i, false);
        }
        barriers.flush(cmd);
    }

    /** Once per frame: images, descriptors and the clears of this frame. */
    private void startFrame() {
        if (frameStarted) return;
        frameStarted = true;
        if (boundGeneration != FrameTargets.generation()) {
            allocateImages();
            boundGeneration = FrameTargets.generation();
        }
        if (shadow != null) shadow.ensureImages();
        // Views change only with the images; sets of a slot are written once per allocation.
        int slot = Drawer.getCurrentFrame() % frameUbo.length;
        if (setsWritten == null) setsWritten = new boolean[frameUbo.length];
        // A restitch replaces the material atlases under the sets.
        if (usesMaterials()) {
            int atlases = net.vesu.shader.PbrAtlases.generation();
            if (atlases != setsAtlases) {
                setsAtlases = atlases;
                java.util.Arrays.fill(setsWritten, false);
            }
        }
        if (!setsWritten[slot]) {
            setsWritten[slot] = true;
            writeSets(slot);
        }
        for (int t = 0; t < images.length; t++) {
            if (pack.textures.get(t).clearColor == null) continue;
            for (int i = 0; i < plan.imageCount[t]; i++) {
                if (!isOpaqueTarget(t, i)) images[t][i].pendingClear = true;
            }
        }
    }

    /** Geometry clears this image with a load op when rendering starts. */
    private boolean isOpaqueTarget(int texture, int image) {
        for (int i = 0; i < geometryTextures.length; i++) {
            if (geometryTextures[i] == texture && plan.geometryImages[FramePlan.OPAQUE][i] == image) return true;
        }
        return false;
    }

    /**
     * Once per frame, before the submit. Commands run after the submit, so geometry drawn earlier
     * sees it too. The block is written only from the DEFERRED point on: the prologue (SETUP, BEGIN,
     * SHADOW_COMP) is recorded when the frame opens, before the camera and the frame block of this
     * frame exist, and a copy taken there left every value a frame behind the geometry.
     */
    private void uploadFrame(VkCommandBuffer cmd) {
        if (timer != null && !timerStarted) {
            timerStarted = true;
            timer.begin(cmd);
        }
        if (frameUploaded || !deferredDone) return;
        frameUploaded = true;
        int frame = Drawer.getCurrentFrame() % frameUbo.length;
        FrameUniforms.ensureFilled();
        MemoryUtil.memCopy(MemoryUtil.memAddress(FrameUniforms.data()), frameUboAddress[frame], FrameBlock.LAYOUT.size());
        if (uniforms != null) uniforms.write(frameUboAddress[frame] + FrameBlock.LAYOUT.offset("vkm_packUniforms"));
        if (centerDepth != null) {
            MemoryUtil.memPutFloat(frameUboAddress[frame] + FrameBlock.LAYOUT.offset("centerDepthSmooth"), smoothCenterDepth(frame));
        }
    }

    /** This slot's fence has passed, so its readback is complete. Same fade as OptiFine. */
    private float smoothCenterDepth(int frame) {
        float depth;
        if (FrameTargets.depthFormat() == VK_FORMAT_D32_SFLOAT || FrameTargets.depthFormat() == VK_FORMAT_D32_SFLOAT_S8_UINT) {
            depth = MemoryUtil.memGetFloat(centerDepthAddress[frame]);
        } else {
            depth = (MemoryUtil.memGetInt(centerDepthAddress[frame]) & 0xFFFFFF) / 16777215.0f;
        }
        long now = System.nanoTime();
        float ms = centerDepthNanos == 0 ? 0 : (now - centerDepthNanos) / 1e6f;
        centerDepthNanos = now;
        float keep = (float) Math.exp(Math.log(0.5) * ms * 0.01 / Math.max(centerDepthHalflife, 1e-3f));
        centerDepthSmooth = centerDepthSmooth * keep + depth * (1 - keep);
        return centerDepthSmooth;
    }

    /** Raw program text mentions a shared resource only when the pass itself uses it. */
    private boolean usesResources(String program) {
        if (resources == null) return false;
        if (java.util.regex.Pattern.compile("(?s).*\\bset\\s*=\\s*2\\b.*").matcher(program).matches()) return true;
        Resources definitions = Resources.of(pack.raw);
        for (Resources.Image image : definitions.images) {
            if (program.contains(image.name()) || image.sampler() != null && program.contains(image.sampler())) return true;
        }
        return false;
    }

    private StepGpu buildStep(FramePlan.Step step, IncludeExpander expander, int frames) throws IOException {
        VkDevice device = Vulkan.getDevice();
        IrPack.Pass pass = step.pass();
        boolean compute = pass.isCompute();
        List<MiddleStage> middle = middleStages(pass.tessControl, pass.tessEval, pass.geometry);
        int stageBit = compute ? VK_SHADER_STAGE_COMPUTE_BIT : !middle.isEmpty() ? VK_SHADER_STAGE_ALL_GRAPHICS : pass.vertex != null
                ? VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT : VK_SHADER_STAGE_FRAGMENT_BIT;
        String program = compute ? pass.compute : pass.fragment;
        boolean stepUsesResources = usesResources(expander.readProgram(program));
        String source = expander.expandProgram(program, Prelude.forStep(pack, step));
        String name = pack.id + "/" + program;
        ShaderSPIRVUtils.SPIRV spv = ShaderSPIRVUtils.compilePackShader(name, source,
                compute ? ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER : ShaderSPIRVUtils.ShaderKind.FRAGMENT_SHADER);

        long setLayout;
        try (MemoryStack stack = stackPush()) {
            int inputs = step.inputs().size();
            int writes = step.storage().size();
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1 + inputs + writes, stack);
            b.get(0).binding(Prelude.FRAME_BINDING).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).stageFlags(stageBit);
            for (int i = 0; i < inputs; i++) {
                b.get(1 + i).binding(Prelude.inputBinding(i)).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(stageBit);
            }
            for (int j = 0; j < writes; j++) {
                b.get(1 + inputs + j).binding(Prelude.writeBinding(inputs, j))
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(stageBit);
            }
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(b), null, p), "pack set layout");
            setLayout = p.get(0);
        }

        long[] sets = new long[frames];
        try (MemoryStack stack = stackPush()) {
            LongBuffer layouts = stack.mallocLong(frames);
            for (int f = 0; f < frames; f++) layouts.put(f, setLayout);
            LongBuffer out = stack.mallocLong(frames);
            ComputeSets.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(layouts), out), "pack descriptor sets");
            for (int f = 0; f < frames; f++) sets[f] = out.get(f);
        }

        try {
            if (compute) {
                ComputePipeline cp = new ComputePipeline(spv, stepSetLayouts(setLayout), Prelude.PASS_BLOCK_SIZE);
                return new StepGpu(step, setLayout, cp.layout(), cp.handle(), stageBit, cp, sets, stepUsesResources);
            }
            long layout = createPipelineLayout(setLayout, stageBit);
            int[] formats;
            if (step.combination()) {
                formats = new int[]{sceneFormat()};
            } else {
                formats = new int[step.outputs().size()];
                for (int i = 0; i < formats.length; i++) formats[i] = vkFormats[step.outputs().get(i).texture()];
            }
            long vertexModule = hostVertexModule;
            if (pass.vertex != null) {
                ShaderSPIRVUtils.SPIRV vs = ShaderSPIRVUtils.compilePackShader(pack.id + "/" + pass.vertex,
                        expander.expandProgram(pass.vertex, Prelude.forVertex(pack, step)),
                        ShaderSPIRVUtils.ShaderKind.VERTEX_SHADER);
                vertexModule = createModule(vs.bytecode());
                vs.free();
            }
            DeviceFeatures features = Vulkan.getDeviceInfo().features;
            if ((pass.geometry != null && !features.geometryShader) || (pass.tessControl != null && !features.tessellationShader)) {
                throw new IOException(pass.name + " needs a shader stage this GPU does not have");
            }
            Map<ShaderSPIRVUtils.ShaderKind, Long> modules = new java.util.EnumMap<>(ShaderSPIRVUtils.ShaderKind.class);
            long pipeline;
            try {
                for (MiddleStage m : middle) {
                    ShaderSPIRVUtils.SPIRV code = ShaderSPIRVUtils.compilePackShader(pack.id + "/" + m.program,
                            expander.expandProgram(m.program, Prelude.forVertex(pack, step)), m.kind);
                    modules.put(m.kind, createModule(code.bytecode()));
                    code.free();
                }
                pipeline = createGraphicsPipeline(vertexModule, modules, spv.bytecode(), layout, formats, pass);
            } finally {
                if (vertexModule != hostVertexModule) vkDestroyShaderModule(device, vertexModule, null);
                modules.values().forEach(m -> vkDestroyShaderModule(device, m, null));
            }
            return new StepGpu(step, setLayout, layout, pipeline, stageBit, null, sets, stepUsesResources);
        } finally {
            spv.free();
        }
    }

    // ------------------------------------------------------------------------------------
    // Per frame

    /**
     * Records the passes. Called after the SCENE phase ended (rendering is not active) and
     * the motion vectors were recorded; leaves the scene color in COLOR_ATTACHMENT_OPTIMAL
     * holding the combination's output in Vulkan row order, as the upscale expects.
     */
    void record(VkCommandBuffer cmd, MemoryStack stack) {
        if (shadow != null) shadow.finish(stack);
        startFrame();
        uploadFrame(cmd);
        recordDeferred(cmd, stack);
        int frame = Drawer.getCurrentFrame() % frameUbo.length;

        RenderTarget scene = FrameTargets.scene;
        if (readsDepth) FrameTargets.sceneDepthToShaderRead(cmd, stack);
        int sceneLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        if (plan.combinationReadsScene) {
            copySceneColor(cmd, stack);
            sceneLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        } else if (postReadsScene) {
            VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
            FrameTargets.imageBarrier(b.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            FrameTargets.pipelineBarrier(cmd, b, stack);
            sceneLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }
        if (needsMotion() && MotionVectorPass.enabled()) {
            // The motion pass made its writes visible to compute; fragment passes read them too.
            VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
            FrameTargets.imageBarrier(b.get(0), MotionVectorPass.motionVectors.image, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            FrameTargets.pipelineBarrier(cmd, b, stack);
        }

        uploadFiles(cmd, stack);

        for (int s = plan.prologueSteps + plan.deferredSteps; s < steps.length; s++) {
            StepGpu g = steps[s];
            if (g.step.combination()) {
                copyHistory(cmd, stack);
                recordCombination(cmd, g, frame, sceneLayout, stack);
            } else {
                recordStep(cmd, g, frame, stack);
            }
            if (timer != null) timer.mark(cmd, s);
        }
        frameStarted = frameUploaded = deferredDone = timerStarted = false;
    }

    private void recordStep(VkCommandBuffer cmd, StepGpu g, int frame, MemoryStack stack) {
        FramePlan.Step step = g.step;
        IrPack.Pass pass = step.pass();
        boolean compute = pass.isCompute();
        long readStage = compute ? VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
        if (resources != null && g.usesResources) resources.barrier(cmd, stack);
        boolean stores = !compute && !step.storage().isEmpty();

        // Clears of images this pass touches first by sampling or a storage write.
        for (FramePlan.Input in : step.inputs()) {
            if (!in.isHost()) clearIfPending(cmd, image(in.image()), stack);
        }
        regenerateMipsBefore(cmd, step, stack);
        if (compute) {
            for (FramePlan.ImageRef out : step.outputs()) clearIfPending(cmd, image(out), stack);
        }

        Barriers barriers = new Barriers(step.inputs().size() + step.outputs().size() + step.storage().size(), stack);
        if (stores) useStorage(cmd, barriers, step, stack);
        for (FramePlan.Input in : step.inputs()) {
            if (!in.isHost() && !(stores && step.storage().contains(in.image()))) {
                barriers.use(image(in.image()), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, readStage, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, false);
            }
        }
        // Bedrock VV declares that every fullscreen pass writes every pixel; preserving old
        // attachments only adds bandwidth. Other packs keep the conservative load behavior.
        boolean discardOld = !stores && pack.renderer.has("fullScreenOverwrite")
                && pack.renderer.get("fullScreenOverwrite").getAsBoolean();
        boolean[] clearOnLoad = new boolean[step.outputs().size()];
        for (int i = 0; i < step.outputs().size(); i++) {
            Image img = image(step.outputs().get(i));
            if (compute) {
                barriers.use(img, VK_IMAGE_LAYOUT_GENERAL, readStage, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, false);
            } else if (!(stores && step.storage().contains(step.outputs().get(i)))) {
                clearOnLoad[i] = img.pendingClear && !discardOld;
                barriers.use(img, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        ATTACHMENT_ACCESS, img.pendingClear);
                img.pendingClear = false;
            }
        }
        barriers.flush(cmd);

        int[] size = passSize(step);
        ByteBuffer push = stack.calloc(Prelude.PASS_BLOCK_SIZE);
        push.putFloat(0, size[0]).putFloat(4, size[1]).putInt(8, step.index()).putInt(12, 0);

        if (compute) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g.pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g.pipelineLayout, 0, stack.longs(g.sets[frame]), null);
            bindResources(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g.pipelineLayout);
            vkCmdPushConstants(cmd, g.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            int[] groups = pass.fixedGroups;
            if (groups == null) {
                groups = new int[]{ceilDiv(size[0], pass.localSize[0]), ceilDiv(size[1], pass.localSize[1]), 1};
            }
            if (pass.indirect != null && resources != null) {
                vkCmdDispatchIndirect(cmd, resources.buffer(pass.indirect[0]), pass.indirect[1]);
            } else {
                vkCmdDispatch(cmd, groups[0], groups[1], groups[2]);
            }
        } else {
            Image first = image(step.outputs().get(0));
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(step.outputs().size(), stack);
            for (int i = 0; i < step.outputs().size(); i++) {
                FramePlan.ImageRef ref = step.outputs().get(i);
                VkRenderingAttachmentInfo a = color.get(i).sType$Default()
                        .imageView(image(ref).level0View)
                        .imageLayout(stores && step.storage().contains(ref) ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(clearOnLoad[i] ? VK_ATTACHMENT_LOAD_OP_CLEAR : discardOld ? VK_ATTACHMENT_LOAD_OP_DONT_CARE : VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                if (clearOnLoad[i]) setClear(a.clearValue().color(), ref.texture());
            }
            beginRendering(cmd, color, first.width, first.height, size[0], size[1], false, stack);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g.pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g.pipelineLayout, 0, stack.longs(g.sets[frame]), null);
            bindResources(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g.pipelineLayout);
            vkCmdPushConstants(cmd, g.pipelineLayout, g.pushStages, 0, push);
            vkCmdDraw(cmd, 3, 1, 0, 0);
            VkCompat.endRendering(cmd);
        }

        for (int t : step.mipmaps()) {
            int written = -1;
            for (FramePlan.ImageRef out : step.outputs()) if (out.texture() == t) written = out.image();
            generateMips(cmd, images[t][written >= 0 ? written : 0], stack);
        }
    }

    /** What a fragment pass stores to spends the pass in GENERAL, also when it samples or attaches the same image. */
    private void useStorage(VkCommandBuffer cmd, Barriers barriers, FramePlan.Step step, MemoryStack stack) {
        for (FramePlan.ImageRef ref : step.storage()) {
            clearIfPending(cmd, image(ref), stack);
            barriers.use(image(ref), VK_IMAGE_LAYOUT_GENERAL, GEOMETRY_STORAGE_STAGES, STORAGE_ACCESS | ATTACHMENT_ACCESS, false);
        }
    }

    /** OptiFine's genCompositeMipmap: the buffers a program reads with mips, right before it draws. */
    private void regenerateMipsBefore(VkCommandBuffer cmd, FramePlan.Step step, MemoryStack stack) {
        for (FramePlan.ImageRef ref : step.mipmapsBefore()) {
            generateMips(cmd, image(ref), stack);
        }
    }

    private void recordCombination(VkCommandBuffer cmd, StepGpu g, int frame, int sceneLayout, MemoryStack stack) {
        FramePlan.Step step = g.step;
        RenderTarget scene = FrameTargets.scene;
        for (FramePlan.Input in : step.inputs()) {
            if (!in.isHost()) clearIfPending(cmd, image(in.image()), stack);
        }
        regenerateMipsBefore(cmd, step, stack);
        Barriers barriers = new Barriers(step.inputs().size() + step.storage().size() + 1, stack);
        useStorage(cmd, barriers, step, stack);
        for (FramePlan.Input in : step.inputs()) {
            if (!in.isHost() && !step.storage().contains(in.image())) barriers.use(image(in.image()), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, false);
        }
        barriers.flush(cmd);
        // The scene color becomes the combination's attachment; its old contents are not needed.
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(b.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT
                        | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                sceneLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                        : VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, b, stack);

        VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
        color.get(0).sType$Default().imageView(scene.colorView())
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        beginRendering(cmd, color, scene.width(), scene.height(), scene.width(), scene.height(), true, stack);
        ByteBuffer push = stack.calloc(Prelude.PASS_BLOCK_SIZE);
        push.putFloat(0, scene.width()).putFloat(4, scene.height());
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g.pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g.pipelineLayout, 0, stack.longs(g.sets[frame]), null);
        bindResources(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, g.pipelineLayout);
        vkCmdPushConstants(cmd, g.pipelineLayout, g.pushStages, 0, push);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        VkCompat.endRendering(cmd);
    }

    private void copySceneColor(VkCommandBuffer cmd, MemoryStack stack) {
        RenderTarget scene = FrameTargets.scene;
        VkImageMemoryBarrier2.Buffer pre = VkImageMemoryBarrier2.calloc(2, stack);
        FrameTargets.imageBarrier(pre.get(0), scene.colorImage(), VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        // The previous frame's passes sampled the copy.
        FrameTargets.imageBarrier(pre.get(1), sceneCopy.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, pre, stack);
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.extent().set(scene.width(), scene.height(), 1);
        vkCmdCopyImage(cmd, scene.colorImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                sceneCopy.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        VkImageMemoryBarrier2.Buffer post = VkImageMemoryBarrier2.calloc(1, stack);
        FrameTargets.imageBarrier(post.get(0), sceneCopy.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, post, stack);
    }

    /** History textures: the final image of this frame becomes next frame's {@code <name>Prev}. */
    private void copyHistory(VkCommandBuffer cmd, MemoryStack stack) {
        for (int t = 0; t < images.length; t++) {
            if (plan.hasHistory(t)) {
                copyImage(cmd, images[t][plan.finalImage[t]], images[t][FramePlan.PREV], stack);
            } else if (pack.textures.get(t).clearColor == null && pack.textures.get(t).file == null && plan.finalImage[t] != 0) {
                // A kept texture starts the next frame with this frame's result, as an unflipped OptiFine buffer.
                copyImage(cmd, images[t][plan.finalImage[t]], images[t][0], stack);
            }
        }
    }

    private void copyImage(VkCommandBuffer cmd, Image src, Image dst, MemoryStack stack) {
        clearIfPending(cmd, src, stack);
        Barriers pre = new Barriers(2, stack);
        pre.use(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_READ_BIT, false);
        dst.pendingClear = false;
        pre.use(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT, true);
        pre.flush(cmd);
        VkImageCopy.Buffer region = VkImageCopy.calloc(src.mips, stack);
        for (int m = 0; m < src.mips; m++) {
            region.get(m).srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, m, 0, 1);
            region.get(m).dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, m, 0, 1);
            region.get(m).extent().set(Math.max(1, src.width >> m), Math.max(1, src.height >> m), 1);
        }
        vkCmdCopyImage(cmd, src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    private void clearIfPending(VkCommandBuffer cmd, Image img, MemoryStack stack) {
        if (!img.pendingClear) return;
        img.pendingClear = false;
        int t = textureOf(img);
        Barriers b = new Barriers(1, stack);
        b.use(img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT, true);
        b.flush(cmd);
        VkClearColorValue value = VkClearColorValue.calloc(stack);
        setClear(value, t);
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .set(VK_IMAGE_ASPECT_COLOR_BIT, 0, img.mips, 0, 1);
        vkCmdClearColorImage(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, value, range);
    }

    private void setClear(VkClearColorValue value, int texture) {
        float[] c = pack.textures.get(texture).clearColor;
        if (c == null) return; // First use of a kept texture: zeros, from the calloc'd value.
        TextureFormat f = pack.textures.get(texture).format;
        boolean fog = pack.textures.get(texture).clearFog;
        for (int i = 0; i < 4; i++) {
            if (f.isInteger()) value.uint32(i, (int) c[i]);
            else value.float32(i, fog && i < 3 ? VRenderSystem.clearColor.get(i) : c[i]);
        }
    }

    static void generateMips(VkCommandBuffer cmd, Image img, MemoryStack stack) {
        if (img.mips <= 1) return;
        // Level 0 holds the pass output; every other level is rebuilt from the one above.
        VkImageMemoryBarrier2.Buffer start = VkImageMemoryBarrier2.calloc(2, stack);
        mipBarrier(start.get(0), img, 0, 1, img.stage, img.access, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_READ_BIT, img.layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        mipBarrier(start.get(1), img, 1, img.mips - 1, img.stage, 0, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, start, stack);
        for (int m = 1; m < img.mips; m++) {
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, m - 1, 0, 1);
            blit.srcOffsets(1).set(Math.max(1, img.width >> (m - 1)), Math.max(1, img.height >> (m - 1)), 1);
            blit.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, m, 0, 1);
            blit.dstOffsets(1).set(Math.max(1, img.width >> m), Math.max(1, img.height >> m), 1);
            vkCmdBlitImage(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
            VkImageMemoryBarrier2.Buffer next = VkImageMemoryBarrier2.calloc(1, stack);
            mipBarrier(next.get(0), img, m, 1, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            FrameTargets.pipelineBarrier(cmd, next, stack);
        }
        VkImageMemoryBarrier2.Buffer end = VkImageMemoryBarrier2.calloc(1, stack);
        mipBarrier(end.get(0), img, 0, img.mips, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT | VK_ACCESS_2_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        FrameTargets.pipelineBarrier(cmd, end, stack);
        img.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        img.stage = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
        img.access = VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
    }

    private static void mipBarrier(VkImageMemoryBarrier2 b, Image img, int base, int count, long srcStage,
                                   long srcAccess, long dstStage, long dstAccess, int oldLayout, int newLayout) {
        FrameTargets.imageBarrier(b, img.image, VK_IMAGE_ASPECT_COLOR_BIT, srcStage, srcAccess, dstStage, dstAccess,
                oldLayout, newLayout);
        b.subresourceRange().baseMipLevel(base).levelCount(count);
    }

    /** Batches layout transitions from each image's tracked state. */
    static final class Barriers {
        private final VkImageMemoryBarrier2.Buffer buf;
        private int n;

        Barriers(int max, MemoryStack stack) {
            buf = VkImageMemoryBarrier2.calloc(Math.max(1, max), stack);
        }

        /** {@code discard}: the old contents are not needed (UNDEFINED as the old layout). */
        void use(Image img, int layout, long stage, long access, boolean discard) {
            boolean readOnly = (access & WRITES) == 0 && (img.access & WRITES) == 0;
            if (img.layout == layout && readOnly && !discard) {
                img.stage |= stage;
                img.access |= access;
                return;
            }
            FrameTargets.imageBarrier(buf.get(n++), img.image, VK_IMAGE_ASPECT_COLOR_BIT, img.stage,
                    img.access & WRITES, stage, access, discard ? VK_IMAGE_LAYOUT_UNDEFINED : img.layout, layout);
            buf.get(n - 1).subresourceRange().levelCount(img.mips);
            img.layout = layout;
            img.stage = stage;
            img.access = access;
        }

        void flush(VkCommandBuffer cmd) {
            if (n == 0) return;
            VkCompat.pipelineBarrier2(cmd, VkDependencyInfo.calloc(MemoryStack.stackGet()).sType$Default()
                    .pImageMemoryBarriers(buf.limit(n)));
        }

        private static final long WRITES = VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT
                | VK_ACCESS_2_TRANSFER_WRITE_BIT;
    }

    private static void beginRendering(VkCommandBuffer cmd, VkRenderingAttachmentInfo.Buffer color, int areaW, int areaH,
                                       int viewW, int viewH, boolean hostOrder, MemoryStack stack) {
        VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(color);
        info.renderArea().offset().set(0, 0);
        info.renderArea().extent().set(areaW, areaH);
        VkCompat.beginRendering(cmd, info);
        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        if (hostOrder) {
            viewport.x(0.0f).y(viewH).width(viewW).height(-viewH);
        } else {
            // Pack space: positive viewport, row 0 is the bottom (D4).
            viewport.x(0.0f).y(0.0f).width(viewW).height(viewH);
        }
        viewport.minDepth(0.0f).maxDepth(1.0f);
        vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.extent().set(Math.min(viewW, areaW), Math.min(viewH, areaH));
        vkCmdSetScissor(cmd, 0, scissor);
    }

    /** Viewport (fragment) or dispatch base (compute) size of a pass. */
    private int[] passSize(FramePlan.Step step) {
        IrPack.Pass pass = step.pass();
        RenderTarget scene = FrameTargets.scene;
        if (pass.isCompute()) {
            float s = pass.workGroupScale;
            return new int[]{Math.max(1, Math.round(scene.width() * s)), Math.max(1, Math.round(scene.height() * s))};
        }
        if (pass.scale != null) {
            return new int[]{Math.max(1, Math.round(scene.width() * pass.scale)),
                    Math.max(1, Math.round(scene.height() * pass.scale))};
        }
        Image first = image(step.outputs().get(0));
        return new int[]{first.width, first.height};
    }

    private Image image(FramePlan.ImageRef ref) {
        return images[ref.texture()][ref.image()];
    }

    private int textureOf(Image img) {
        for (int t = 0; t < images.length; t++) {
            for (Image i : images[t]) if (i == img) return t;
        }
        throw new IllegalStateException("image not owned by the pack");
    }

    // ------------------------------------------------------------------------------------
    // Resources

    /** (Re)creates every pack image at the current scene size; the device is idle (resize). */
    private void allocateImages() {
        destroyImages();
        RenderTarget scene = FrameTargets.scene;
        for (int t = 0; t < images.length; t++) {
            IrPack.TextureDef def = pack.textures.get(t);
            int w = def.width > 0 ? def.width : Math.max(1, Math.round(scene.width() * def.scale));
            int h = def.height > 0 ? def.height : Math.max(1, Math.round(scene.height() * def.scale));
            int mips = def.mipmaps ? 32 - Integer.numberOfLeadingZeros(Math.max(w, h)) : 1;
            int count = plan.imageCount[t] + (def.history ? 1 : 0);
            boolean snapshot = plan.snapshots.contains(t);
            images[t] = new Image[snapshot ? FramePlan.SNAPSHOT + 1 : def.history ? FramePlan.PREV + 1 : plan.imageCount[t]];
            for (int i = 0; i < images[t].length; i++) {
                if (i >= plan.imageCount[t] && !(i == FramePlan.PREV && def.history) && i != FramePlan.SNAPSHOT) continue;
                images[t][i] = createImage(w, h, mips, vkFormats[t], usages[t]);
                // A kept texture still starts defined; a file texture is uploaded instead.
                images[t][i].pendingClear = def.file == null;
                images[t][i].pendingUpload = def.file != null;
            }
            // History or a snapshot with one working image: the indices between are unused.
            for (int i = 0; i < images[t].length; i++) if (images[t][i] == null) images[t][i] = images[t][0];
        }
        if (resources != null) resources.ensure(scene.width(), scene.height());
        if (plan.combinationReadsScene) sceneCopy.ensure(scene.width(), scene.height());
        if (plan.depthCopy) depthCopy = createDepthImage(scene.width(), scene.height());
        if (plan.handDepthCopy) handDepthCopy = createDepthImage(scene.width(), scene.height());
        if (wantsDhDepth()) {
            dhDepth = createDhDepthImage(scene.width(), scene.height());
            if (lodPrograms[1] != null && !lodThroughTerrain) dhDepthOpaque = createDhDepthImage(scene.width(), scene.height());
            dhDepthFresh = true;
        }
    }

    private static Image createDhDepthImage(int w, int h) {
        Image img = new Image();
        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            MemoryManager.createImage(w, h, 1, LodPackBridge.DEPTH_FORMAT, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            img.image = pImage.get(0);
            img.memory = pMemory.get(0);
        }
        img.view = Vulkan.createImageView(img.image, LodPackBridge.DEPTH_FORMAT, VK_IMAGE_ASPECT_DEPTH_BIT, 1);
        img.width = w;
        img.height = h;
        return img;
    }

    /** Called right before the hand, inside rendering: depthtex2 has no hand in it. */
    boolean wantsHandDepth() {
        return plan.handDepthCopy;
    }

    void copyHandDepth(VkCommandBuffer cmd, MemoryStack stack) {
        FrameTargets.copySceneDepth(cmd, handDepthCopy.image, stack);
    }

    private static Image createDepthImage(int w, int h) {
        Image img = new Image();
        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            MemoryManager.createImage(w, h, 1, FrameTargets.depthFormat(), VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            img.image = pImage.get(0);
            img.memory = pMemory.get(0);
        }
        img.view = Vulkan.createImageView(img.image, FrameTargets.depthFormat(), VK_IMAGE_ASPECT_DEPTH_BIT, 1);
        return img;
    }

    static Image createImage(int w, int h, int mips, int format, int usage) {
        Image img = new Image();
        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pMemory = stack.mallocPointer(1);
            MemoryManager.createImage(w, h, mips, format, VK_IMAGE_TILING_OPTIMAL, usage,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pMemory);
            img.image = pImage.get(0);
            img.memory = pMemory.get(0);
        }
        img.view = Vulkan.createImageView(img.image, format, VK_IMAGE_ASPECT_COLOR_BIT, mips);
        img.level0View = mips == 1 ? img.view : Vulkan.createImageView(img.image, format, VK_IMAGE_ASPECT_COLOR_BIT, 1);
        img.width = w;
        img.height = h;
        img.mips = mips;
        img.format = format;
        return img;
    }

    /** Points the frame's sets at the current images (cheap; view handles change on resize only). */
    private int setsAtlases;

    private boolean usesMaterials() {
        return plan.hostInputs.contains("normals") || plan.hostInputs.contains("specular") || plan.hostInputs.contains("actorNormals");
    }

    private void writeSets(int frame) {
        try (MemoryStack stack = stackPush()) {
            if (geometrySets != null) {
                for (int phase = 0; phase < 2; phase++) writeGeometrySet(geometrySets[phase][frame], phase, frame, stack);
            }
            for (StepGpu g : steps) {
                FramePlan.Step step = g.step;
                int inputs = step.inputs().size();
                int writes = step.storage().size();
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1 + inputs + writes, stack);
                VkDescriptorBufferInfo.Buffer ubo = VkDescriptorBufferInfo.calloc(1, stack);
                ubo.get(0).buffer(frameUbo[frame]).offset(0).range(FrameBlock.LAYOUT.size());
                w.get(0).sType$Default().dstSet(g.sets[frame]).dstBinding(Prelude.FRAME_BINDING)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(ubo);
                for (int i = 0; i < inputs; i++) {
                    FramePlan.Input in = step.inputs().get(i);
                    VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                    info.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    if (in.isHost()) {
                        switch (in.host()) {
                            case "sceneColor" -> info.get(0)
                                    .sampler(step.pass().linearInputs.getOrDefault(in.name(), true) ? linearSampler : nearestSampler)
                                    .imageView(plan.combinationReadsScene && step.stage() != IrPack.Stage.DEFERRED
                                            ? sceneCopy.view : FrameTargets.scene.colorView());
                            case "sceneDepth", "depthtex0" -> info.get(0).sampler(nearestSampler).imageView(FrameTargets.scene.depthView());
                            case "depthtex1" -> info.get(0).sampler(nearestSampler).imageView(depthCopy.view);
                            case "normals" -> info.get(0).sampler(nearestSampler).imageView(net.vesu.shader.PbrAtlases.normals().getTextureImageView());
                            case "actorNormals" -> info.get(0).sampler(nearestSampler).imageView(net.vesu.shader.PbrAtlases.actorNormals().getTextureImageView());
                            case "actorSpecular" -> info.get(0).sampler(nearestSampler).imageView(net.vesu.shader.PbrAtlases.actorSpecular().getTextureImageView());
                            case "specular" -> info.get(0).sampler(nearestSampler).imageView(net.vesu.shader.PbrAtlases.specular().getTextureImageView());
                            case "depthtex2" -> info.get(0).sampler(nearestSampler).imageView(handDepthCopy.view);
                            case "dhDepthTex0" -> info.get(0).sampler(nearestSampler).imageView(dhDepth.view);
                            case "dhDepthTex1" -> info.get(0).sampler(nearestSampler).imageView(dhDepthTex1View());
                            case "shadowtex0", "shadowtex1", "shadowtex0Hw", "shadowtex1Hw", "shadowcolor0", "shadowcolor1", "shadowcolor2",
                                 "shadowcolor3", "shadowcolor4", "shadowcolor5", "shadowcolor6", "shadowcolor7" -> shadow.bind(info.get(0), in.host());
                            default -> info.get(0).sampler(nearestSampler).imageView(MotionVectorPass.motionVectors.view);
                        }
                    } else {
                        IrPack.TextureDef def = pack.textures.get(in.image().texture());
                        Image img = image(in.image());
                        Boolean wanted = step.pass().linearInputs.get(in.name());
                        boolean linear = (wanted != null ? wanted : def.linearFilter) && !isInteger(def.format);
                        info.get(0).sampler(samplerFor(def, img, linear, !step.pass().baseLevelInputs.contains(in.name()))).imageView(img.view);
                        if (!step.pass().isCompute() && step.storage().contains(in.image())) info.get(0).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    }
                    w.get(1 + i).sType$Default().dstSet(g.sets[frame]).dstBinding(Prelude.inputBinding(i))
                            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(info);
                }
                for (int j = 0; j < writes; j++) {
                    VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                    info.get(0).imageView(image(step.storage().get(j)).level0View).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    w.get(1 + inputs + j).sType$Default().dstSet(g.sets[frame]).dstBinding(Prelude.writeBinding(inputs, j))
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(info);
                }
                vkUpdateDescriptorSets(Vulkan.getDevice(), w, null);
            }
        }
    }

    private void writeGeometrySet(long set, int phase, int frame, MemoryStack stack) {
        List<FramePlan.Input> inputs = plan.geometryInputs.get(phase);
        List<FramePlan.ImageRef> stored = plan.geometryStorage.get(phase);
        VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1 + inputs.size() + stored.size(), stack);
        VkDescriptorBufferInfo.Buffer ubo = VkDescriptorBufferInfo.calloc(1, stack);
        ubo.get(0).buffer(frameUbo[frame]).offset(0).range(FrameBlock.LAYOUT.size());
        w.get(0).sType$Default().dstSet(set).dstBinding(Prelude.FRAME_BINDING)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(ubo);
        for (int i = 0; i < inputs.size(); i++) {
            FramePlan.Input in = inputs.get(i);
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            if (in.isHost() && in.host().startsWith("shadow")) {
                shadow.bind(info.get(0), in.host());
            } else if (in.isHost() && in.host().equals("gnormalCopy")) {
                info.get(0).sampler(nearestSampler).imageView(images[pack.textures.indexOf(pack.texture("gnormal"))][FramePlan.SNAPSHOT].level0View);
            } else if (in.isHost() && (in.host().equals("normals") || in.host().equals("specular") || in.host().startsWith("actor"))) {
                net.vesu.vulkan.texture.VulkanImage atlas = switch (in.host()) {
                    case "normals" -> net.vesu.shader.PbrAtlases.normals();
                    case "specular" -> net.vesu.shader.PbrAtlases.specular();
                    case "actorNormals" -> net.vesu.shader.PbrAtlases.actorNormals();
                    default -> net.vesu.shader.PbrAtlases.actorSpecular();
                };
                info.get(0).sampler(nearestSampler).imageView(atlas.getTextureImageView());
            } else if (in.isHost() && in.host().startsWith("dhDepthTex")) {
                info.get(0).sampler(nearestSampler).imageView(in.host().equals("dhDepthTex1") ? dhDepthTex1View() : dhDepth.view);
            } else if (in.isHost()) {
                info.get(0).sampler(nearestSampler).imageView(in.host().equals("depthtex2") ? handDepthCopy.view : depthCopy.view);
            } else {
                IrPack.TextureDef def = pack.textures.get(in.image().texture());
                Image img = image(in.image());
                boolean linear = def.linearFilter && !isInteger(def.format);
                info.get(0).sampler(samplerFor(def, img, linear, !pack.renderer.has("baseLevelGeometry"))).imageView(img.view);
            }
            if (!in.isHost() && stored.contains(in.image())) info.get(0).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            w.get(1 + i).sType$Default().dstSet(set).dstBinding(Prelude.inputBinding(i))
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(info);
        }
        for (int j = 0; j < stored.size(); j++) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
            info.get(0).imageView(image(stored.get(j)).level0View).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            w.get(1 + inputs.size() + j).sType$Default().dstSet(set).dstBinding(Prelude.writeBinding(inputs.size(), j))
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(info);
        }
        vkUpdateDescriptorSets(Vulkan.getDevice(), w, null);
    }

    private void checkFormatSupport(int t) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(Vulkan.getDevice().getPhysicalDevice(), vkFormats[t], props);
            int have = props.optimalTilingFeatures();
            int need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if ((usages[t] & VK_IMAGE_USAGE_STORAGE_BIT) != 0) need |= VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
            if ((usages[t] & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0) need |= VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
            if (pack.textures.get(t).mipmaps) need |= VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
            if ((have & need) != need) {
                throw new IllegalArgumentException("texture " + pack.textures.get(t).name + ": format "
                        + pack.textures.get(t).format + " is not supported by this GPU for its use");
            }
            TextureFormat f = pack.textures.get(t).format;
            boolean baseStorage = f == TextureFormat.RGBA8 || f == TextureFormat.RGBA16F || f == TextureFormat.RGBA32F
                    || f == TextureFormat.R32F || f == TextureFormat.R32UI || f == TextureFormat.RGBA32UI;
            if ((usages[t] & VK_IMAGE_USAGE_STORAGE_BIT) != 0 && !baseStorage) {
                // The other formats need shaderStorageImageExtendedFormats, which is not enabled.
                throw new IllegalArgumentException("texture " + pack.textures.get(t).name + ": compute passes can"
                        + " write RGBA8, RGBA16F, RGBA32F, R32F, R32UI and RGBA32UI only, not " + f);
            }
        }
    }

    static int vkFormat(TextureFormat f) {
        return switch (f) {
            case RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA16 -> VK_FORMAT_R16G16B16A16_UNORM;
            case RGBA16F -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case RGBA32F -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case R8 -> VK_FORMAT_R8_UNORM;
            case R16F -> VK_FORMAT_R16_SFLOAT;
            case R32F -> VK_FORMAT_R32_SFLOAT;
            case RG8 -> VK_FORMAT_R8G8_UNORM;
            case RG16F -> VK_FORMAT_R16G16_SFLOAT;
            case RG32F -> VK_FORMAT_R32G32_SFLOAT;
            case R11G11B10F -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case RGB10A2 -> VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case R8UI -> VK_FORMAT_R8_UINT;
            case R16UI -> VK_FORMAT_R16_UINT;
            case RG8UI -> VK_FORMAT_R8G8_UINT;
            case RG16UI -> VK_FORMAT_R16G16_UINT;
            case RG32UI -> VK_FORMAT_R32G32_UINT;
            case RGBA8UI -> VK_FORMAT_R8G8B8A8_UINT;
            case RGBA16UI -> VK_FORMAT_R16G16B16A16_UINT;
            case R32UI -> VK_FORMAT_R32_UINT;
            case RGBA32UI -> VK_FORMAT_R32G32B32A32_UINT;
        };
    }

    private static boolean isInteger(TextureFormat f) {
        return f.isInteger();
    }

    private long samplerFor(IrPack.TextureDef def, Image img, boolean linear, boolean levels) {
        if (img.mips > 1 && levels) return linear ? linearMipSampler : nearestMipSampler;
        if (def.repeat) return linear ? linearRepeatSampler : nearestRepeatSampler;
        return linear ? linearSampler : nearestSampler;
    }

    static long createSampler(int filter, boolean mips) {
        return createSampler(filter, mips, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
    }

    private static long createSampler(int filter, boolean mips, int address) {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter)
                    .mipmapMode(filter == VK_FILTER_LINEAR ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(address).addressModeV(address).addressModeW(address)
                    .maxLod(mips ? VK_LOD_CLAMP_NONE : 0.0f);
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateSampler(Vulkan.getDevice(), info, null, p), "pack sampler");
            return p.get(0);
        }
    }

    private static long createModule(ByteBuffer code) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateShaderModule(Vulkan.getDevice(), VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code), null, p), "pack shader module");
            return p.get(0);
        }
    }

    private long[] stepSetLayouts(long setLayout) {
        return resources == null ? new long[]{setLayout} : new long[]{setLayout, resources.emptyLayout(), resources.setLayout()};
    }

    private long createPipelineLayout(long setLayout, int stages) {
        try (MemoryStack stack = stackPush()) {
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(stages).offset(0).size(Prelude.PASS_BLOCK_SIZE);
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreatePipelineLayout(Vulkan.getDevice(), VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(stepSetLayouts(setLayout))).pPushConstantRanges(range), null, p),
                    "pack pipeline layout");
            return p.get(0);
        }
    }

    private long createGraphicsPipeline(long vertexModule, Map<ShaderSPIRVUtils.ShaderKind, Long> middle, ByteBuffer fragment,
                                        long layout, int[] formats, IrPack.Pass pass) {
        long fsModule = createModule(fragment);
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2 + middle.size(), stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fsModule).pName(stack.UTF8("main"));
            int stage = 2;
            for (Map.Entry<ShaderSPIRVUtils.ShaderKind, Long> m : middle.entrySet()) {
                int bit = switch (m.getKey()) {
                    case TESS_CONTROL_SHADER -> VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
                    case TESS_EVAL_SHADER -> VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;
                    default -> VK_SHADER_STAGE_GEOMETRY_BIT;
                };
                stages.get(stage++).sType$Default().stage(bit).module(m.getValue()).pName(stack.UTF8("main"));
            }
            boolean patches = pass.tessControl != null;

            VkPipelineColorBlendAttachmentState.Buffer attachments =
                    VkPipelineColorBlendAttachmentState.calloc(formats.length, stack);
            for (int i = 0; i < formats.length; i++) {
                VkPipelineColorBlendAttachmentState a = attachments.get(i).colorWriteMask(0xF);
                IrPack.Blend blend = i < pass.targets.size() ? pass.targetBlend.getOrDefault(pass.targets.get(i), pass.blend) : pass.blend;
                if (blend.enabled) {
                    a.blendEnable(true)
                            .srcColorBlendFactor(blendFactor(blend.factors[0])).dstColorBlendFactor(blendFactor(blend.factors[1]))
                            .colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(blendFactor(blend.factors[2])).dstAlphaBlendFactor(blendFactor(blend.factors[3]))
                            .alphaBlendOp(VK_BLEND_OP_ADD);
                }
            }

            VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                    .colorAttachmentCount(formats.length).pColorAttachmentFormats(stack.ints(formats))
                    .depthAttachmentFormat(VK_FORMAT_UNDEFINED).stencilAttachmentFormat(VK_FORMAT_UNDEFINED);

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType$Default()
                    .pNext(rendering.address())
                    .pStages(stages)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default())
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                            .topology(patches ? VK_PRIMITIVE_TOPOLOGY_PATCH_LIST : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pTessellationState(patches ? VkPipelineTessellationStateCreateInfo.calloc(stack).sType$Default().patchControlPoints(3) : null)
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                            .viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                            .pAttachments(attachments))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                    .layout(layout)
                    .renderPass(VK_NULL_HANDLE)
                    .basePipelineIndex(-1);
            LongBuffer p = stack.mallocLong(1);
            ComputeSets.check(vkCreateGraphicsPipelines(Vulkan.getDevice(), Pipeline.getPipelineCache(), info, null, p),
                    "pack pipeline");
            return p.get(0);
        } finally {
            vkDestroyShaderModule(Vulkan.getDevice(), fsModule, null);
        }
    }

    private static int blendFactor(String name) {
        return switch (name) {
            case "ZERO" -> VK_BLEND_FACTOR_ZERO;
            case "ONE" -> VK_BLEND_FACTOR_ONE;
            case "SRC_COLOR" -> VK_BLEND_FACTOR_SRC_COLOR;
            case "ONE_MINUS_SRC_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case "DST_COLOR" -> VK_BLEND_FACTOR_DST_COLOR;
            case "ONE_MINUS_DST_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case "SRC_ALPHA" -> VK_BLEND_FACTOR_SRC_ALPHA;
            case "ONE_MINUS_SRC_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case "DST_ALPHA" -> VK_BLEND_FACTOR_DST_ALPHA;
            case "ONE_MINUS_DST_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case "SRC_ALPHA_SATURATE" -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            default -> throw new IllegalArgumentException("unknown blend factor " + name);
        };
    }

    private void destroyImages() {
        for (int t = 0; t < images.length; t++) {
            if (images[t] == null) continue;
            List<Image> seen = new ArrayList<>();
            for (Image img : images[t]) {
                if (img == null || seen.contains(img)) continue;
                seen.add(img);
                VkDevice device = Vulkan.getDevice();
                if (img.level0View != img.view) vkDestroyImageView(device, img.level0View, null);
                vkDestroyImageView(device, img.view, null);
                MemoryManager.freeImage(img.image, img.memory);
            }
            images[t] = null;
        }
        sceneCopy.destroy();
        if (shadow != null) shadow.destroyImages();
        for (Image depth : new Image[]{depthCopy, handDepthCopy, dhDepth, dhDepthOpaque}) {
            if (depth == null) continue;
            vkDestroyImageView(Vulkan.getDevice(), depth.view, null);
            MemoryManager.freeImage(depth.image, depth.memory);
        }
        depthCopy = handDepthCopy = dhDepth = dhDepthOpaque = null;
        for (long[] s : staging) MemoryManager.freeBuffer(s[0], s[1]);
        staging.clear();
        boundGeneration = -1;
        setsWritten = null;
        setupDone = false;
        frameStarted = frameUploaded = deferredDone = timerStarted = false;
    }

    private void destroyAll() {
        VkDevice device = Vulkan.getDevice();
        destroyImages();
        for (Pipeline.Program p : programs) p.destroy();
        for (LodPackProgram p : lodPrograms) if (p != null) p.free();
        if (lodShadow != null) lodShadow.free();
        programTables.keySet().forEach(p -> p.packPrograms = null);
        if (geometrySetLayout != 0L) vkDestroyDescriptorSetLayout(device, geometrySetLayout, null);
        if (shadow != null) shadow.destroy();
        if (steps != null) {
            for (StepGpu g : steps) {
                if (g == null) continue;
                if (g.compute != null) {
                    g.compute.cleanUp();
                } else {
                    vkDestroyPipeline(device, g.pipeline, null);
                    vkDestroyPipelineLayout(device, g.pipelineLayout, null);
                }
                vkDestroyDescriptorSetLayout(device, g.setLayout, null);
            }
        }
        if (descriptorPool != 0L) vkDestroyDescriptorPool(device, descriptorPool, null);
        if (resources != null) resources.destroy();
        if (hostVertexModule != 0L) vkDestroyShaderModule(device, hostVertexModule, null);
        for (int f = 0; f < frameUbo.length; f++) {
            if (frameUbo[f] != 0L) MemoryManager.freeBuffer(frameUbo[f], frameUboMemory[f]);
            if (centerDepth != null) MemoryManager.freeBuffer(centerDepth[f], centerDepthMemory[f]);
        }
        for (long s : new long[]{linearSampler, nearestSampler, linearMipSampler, nearestMipSampler, linearRepeatSampler, nearestRepeatSampler}) {
            if (s != 0L) vkDestroySampler(device, s, null);
        }
        if (timer != null) timer.destroy();
        for (ByteBuffer pixels : filePixels) {
            if (pixels != null) MemoryUtil.memFree(pixels);
        }
    }

    /** Reads a PNG into tightly packed RGBA8; the size must be the one the pack declared. */
    private static ByteBuffer decodePng(IrPack.TextureDef def, PackSource src) throws IOException {
        if (def.file.startsWith("vkm:")) return generated(def);
        BufferedImage png = ImageIO.read(new ByteArrayInputStream(src.readBytes(def.file)));
        if (png == null || png.getWidth() != def.width || png.getHeight() != def.height) {
            throw new IOException(def.file + ": not a " + def.width + "x" + def.height + " PNG");
        }
        ByteBuffer pixels = MemoryUtil.memAlloc(def.width * def.height * 4);
        for (int y = 0; y < def.height; y++) {
            for (int x = 0; x < def.width; x++) {
                int argb = png.getRGB(x, y);
                pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        return pixels.flip();
    }

    /** {@code vkm:noise} is OptiFine's random noise texture; {@code vkm:solid:rrggbbaa} one color. */
    private static ByteBuffer generated(IrPack.TextureDef def) {
        ByteBuffer pixels = MemoryUtil.memAlloc(def.width * def.height * 4);
        if (def.file.equals("vkm:noise")) {
            java.util.Random random = new java.util.Random(1234L);
            while (pixels.hasRemaining()) {
                pixels.put((byte) random.nextInt(256)).put((byte) random.nextInt(256)).put((byte) random.nextInt(256)).put((byte) 255);
            }
        } else {
            int rgba = Integer.parseUnsignedInt(def.file.substring("vkm:solid:".length()), 16);
            while (pixels.hasRemaining()) pixels.putInt(Integer.reverseBytes(rgba));
        }
        return pixels.flip();
    }

    private void upload(VkCommandBuffer cmd, Image img, ByteBuffer pixels, MemoryStack stack) {
        img.pendingUpload = false;
        LongBuffer pBuffer = stack.mallocLong(1);
        PointerBuffer pMemory = stack.mallocPointer(1);
        MemoryManager.createBufferHostAccess(pixels.remaining(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, pBuffer, pMemory);
        staging.add(new long[]{pBuffer.get(0), pMemory.get(0)});
        MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), MemoryManager.Map(pMemory.get(0)).get(0), pixels.remaining());
        Barriers b = new Barriers(1, stack);
        b.use(img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT,
                VK_ACCESS_2_TRANSFER_WRITE_BIT, true);
        b.flush(cmd);
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.get(0).imageExtent().set(img.width, img.height, 1);
        vkCmdCopyBufferToImage(cmd, pBuffer.get(0), img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /**
     * GPU time per pack pass (VESU_PROFILE=1), logged every 120 frames as
     * {@code [gpu] pack <id>: <pass>=<ms> ...}. Read back when the frame slot comes round again.
     */
    private final class GpuPassTimer {
        private final String id;
        private final int marks, slots;
        private final long pool;
        private final boolean[] written;
        private final double[] sums;
        private int frames;

        GpuPassTimer(String id, int passes, int slots) {
            this.id = id;
            this.marks = passes + 1;
            this.slots = slots;
            this.written = new boolean[slots];
            this.sums = new double[passes];
            try (MemoryStack stack = stackPush()) {
                LongBuffer p = stack.mallocLong(1);
                ComputeSets.check(vkCreateQueryPool(Vulkan.getDevice(), VkQueryPoolCreateInfo.calloc(stack)
                        .sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(slots * marks), null, p),
                        "pack query pool");
                pool = p.get(0);
            }
        }

        void begin(VkCommandBuffer cmd) {
            int slot = Drawer.getCurrentFrame() % slots;
            if (written[slot]) collect(slot);
            vkCmdResetQueryPool(cmd, pool, slot * marks, marks);
            VkCompat.writeTimestamp2(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, slot * marks);
            written[slot] = true;
        }

        void mark(VkCommandBuffer cmd, int pass) {
            int slot = Drawer.getCurrentFrame() % slots;
            VkCompat.writeTimestamp2(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, pool, slot * marks + pass + 1);
        }

        private void collect(int slot) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer t = stack.mallocLong(marks);
                if (vkGetQueryPoolResults(Vulkan.getDevice(), pool, slot * marks, marks, t, 8,
                        VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) return;
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(Vulkan.getDevice().getPhysicalDevice(), props);
                double period = props.limits().timestampPeriod();
                for (int i = 0; i < sums.length; i++) sums[i] += (t.get(i + 1) - t.get(i)) * period / 1e6;
            }
            if (++frames % 120 != 0) return;
            StringBuilder b = new StringBuilder("[gpu] pack ").append(id).append(':');
            double total = 0;
            for (int i = 0; i < sums.length; i++) {
                b.append(' ').append(steps[i].step.pass().name.replace(' ', '_')).append('=')
                        .append(String.format("%.3f", sums[i] / 120));
                total += sums[i];
                sums[i] = 0;
            }
            b.append(" total=").append(String.format("%.3f", total / 120));
            Initializer.LOGGER.info(b.toString());
        }

        void destroy() {
            vkDestroyQueryPool(Vulkan.getDevice(), pool, null);
        }
    }
}
