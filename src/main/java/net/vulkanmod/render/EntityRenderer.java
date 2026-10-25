package net.vulkanmod.render;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.Entity;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;

/**
 * Vulkan port of 1.12.2 entity rendering. Vanilla builds entity geometry through the
 * shared {@link BufferBuilder} (mob model parts are rebuilt into it every frame by
 * {@code ModelRendererM}, bypassing the GL display lists) and draws it through
 * {@code WorldVertexBufferUploader.draw}. This class hooks that draw: while an entity
 * is rendering it captures the finished geometry, mirrors the live GL matrices/state
 * and replays the batch in Vulkan with the entity's own texture.
 *
 * <p>Textures are downloaded from the GL on first use (the same {@code glGetTexImage}
 * path the atlas uses) and cached by GL texture id; mob skins never change, so the
 * cache is effectively permanent. The first pass renders mobs fullbright (directional
 * shading and the lightmap are layered on later).</p>
 */
public class EntityRenderer {

    private static Pipeline entityPipeline;   // OLDMODEL_POSITION_TEX_NORMAL: texture + fog
    private static Pipeline itemPipeline;     // ITEM: dropped-item models (atlas, vertex colour)
    private static Pipeline shadowPipeline;   // POSITION_TEX_COLOR: entity drop shadow
    private static Pipeline flatPipeline;     // POSITION_TEX: burning fire / map / glint quads
    private static Pipeline particlePipeline; // PARTICLE_POSITION_TEX_COLOR_LMAP: billboards
    private static Pipeline blockPipeline;    // BLOCK: held blocks (RenderLivingBase equip)

    private static final VertexFormat MODEL_FORMAT = DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL;
    private static final VertexFormat ITEM_FORMAT = DefaultVertexFormats.ITEM;
    private static final VertexFormat SHADOW_FORMAT = DefaultVertexFormats.POSITION_TEX_COLOR;
    private static final VertexFormat FLAT_FORMAT = DefaultVertexFormats.POSITION_TEX;
    private static final VertexFormat PARTICLE_FORMAT = DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP;
    private static final VertexFormat BLOCK_FORMAT = DefaultVertexFormats.BLOCK;

    /**
     * Nesting depth of entity-owned render brackets: {@code RenderManager.renderEntity} and
     * the block-entity pass both bracket it, and a TESR that renders an entity inside itself
     * (the spawner's mini-mob) must not clear the outer pass on return.
     */
    private static int entityPassDepth;

    /** True between the head and return of {@code EntityRenderer.renderHand} (first person). */
    private static boolean handPass;

    /** True between the head and return of {@code ParticleManager.renderParticles}. */
    private static boolean particlePass;

    /** True from the frame's start to the head of {@code GuiIngame.renderGameOverlay}. */
    private static boolean rainPass;

    /** The entity being rendered; supplies the constant lightmap sample. */
    private static Entity currentEntity;

    /**
     * Packed lightmap of the block entity being rendered (-1 outside the block-entity
     * pass). TESR draws (chest, sign, book, frame) carry no lightmap of their own and the
     * stale {@link #currentEntity} would light them like the last mob, so the dispatcher
     * bracket supplies the light at the tile entity's own position.
     */
    private static int tesrLight = -1;

    public static void setTesrLight(int packed) {
        tesrLight = packed;
    }

    private static final float[] tint = new float[3];

    // --- Deferred weather ---------------------------------------------------------------
    // Vanilla renders the rain/snow quads (renderRainSnow) BEFORE the translucent water
    // layer, so falling weather ends up behind the water surface and gets tinted by it -
    // snow over a lake turns blue, rain over water nearly vanishes. That is illogical: the
    // particles are in the air between the camera and the water, so they belong in front.
    // We capture the weather geometry during renderRainSnow but defer its draw to just after
    // the translucent layer (see flushWeather), where it blends over the water instead of
    // under it. The quads still depth-test against the solid terrain, so weather behind
    // hills stays hidden; it only stops being covered by the water.
    private static ByteBuffer weatherBuffer;
    // Scratch used to hand one batch to Drawer.draw starting at position 0 (Drawer's memcpy
    // reads the source from position 0, so a mid-buffer slice cannot be passed directly).
    private static ByteBuffer weatherScratch;
    private static int weatherVerts;
    private static boolean weatherPending;
    // The vertex count of the most recent flush, exposed for the STICKY autotest to assert the
    // weather is not being truncated frame-to-frame (see AutoTest). Zero when nothing was drawn.
    private static int lastWeatherVerts;
    // The weather is drawn in many small batches whose texture alternates between the snow and
    // rain sprites (vanilla samples the biome per column, so a view straddling a snow/rain biome
    // boundary mixes both). Each batch is buffered with its own texture and replayed as-is.
    private static final java.util.ArrayList<int[]> weatherBatch = new java.util.ArrayList<>();
    private static final java.util.ArrayList<VulkanImage> weatherBatchTex = new java.util.ArrayList<>();
    private static final float[] weatherTint = new float[4];
    private static final float[] weatherMV = new float[16];
    private static Pipeline.BlendState weatherBlend;
    private static boolean weatherDepthMask, weatherDepthTest, weatherCull;
    private static int weatherDepthFunc;

    /** True while a model display list is compiling, so the compile-time draw is skipped. */
    private static boolean compiling;

    /** GL texture id -> uploaded Vulkan image. */
    private static final HashMap<Integer, VulkanImage> textureCache = new HashMap<>();

    /** A/B knob: VULKANMOD_NO_ENTBATCH=1 falls back to the old per-quad entity draw. */
    public static final boolean BATCH_PARTS = System.getenv("VULKANMOD_NO_ENTBATCH") == null;

    /**
     * Fog and projection are set up once per world render and never change across the entity
     * pass, so they are snapshotted on the first model-part draw of a frame and reused. The
     * model-view still updates per part (it carries the per-part translate/rotate).
     */
    private static boolean frameStateCached;

    /** The entity skin resolves to the same image for every model part; cached per binding. */
    private static VulkanImage cachedEntityTexture;
    private static int cachedTextureId = -1;

    /**
     * The Cleanroom hurt/death overlay (RenderLivingBase sets a red constant through the
     * fixed-function combiner around the model draw). Mirrored as a shader-side red mix;
     * only the model pipeline applies it (shadows and overlays never get the tint).
     */
    private static boolean redOverlayActive;

    /**
     * Per-part batching: while a model part's boxes are being rebuilt, each quad would
     * otherwise flush as its own draw (6 per cube). All of a part's quads share the part's
     * model-view, texture and tint, so they are accumulated into {@link #staging} and issued
     * as a single draw when the part finishes. {@code ModelRendererM} brackets the rebuild
     * with {@link #beginPart()} / {@link #endPart()}.
     */
    private static boolean accumulating;
    private static ByteBuffer staging = MemoryUtil.memAlloc(1 << 14);
    private static int stagingVerts;

    private static final FloatBuffer float4 = MemoryUtil.memAllocFloat(4);
    private static final ByteBuffer bool1 = MemoryUtil.memAlloc(1);
    private static final java.nio.IntBuffer int1 = MemoryUtil.memAllocInt(1);

    private static boolean loggedFirst;

    public static void init() {
        entityPipeline = new Pipeline(MODEL_FORMAT, "entity");
        itemPipeline = new Pipeline(ITEM_FORMAT, "entity_item");
        shadowPipeline = new Pipeline(SHADOW_FORMAT, "entity_shadow");
        flatPipeline = new Pipeline(FLAT_FORMAT, "entity_flat");
        particlePipeline = new Pipeline(PARTICLE_FORMAT, "entity_particle");
        blockPipeline = new Pipeline(BLOCK_FORMAT, "terrain");
    }

    public static void setEntityPass(boolean value) {
        entityPassDepth = Math.max(0, entityPassDepth + (value ? 1 : -1));
    }

    public static boolean isEntityPass() {
        return entityPassDepth > 0;
    }

    public static void setHandPass(boolean value) {
        handPass = value;
    }

    public static void setParticlePass(boolean value) {
        particlePass = value;
    }

    public static void setRainPass(boolean value) {
        rainPass = value;
    }

    /** True while any pass whose draws this class replays is running. */
    public static boolean isActive() {
        return isEntityPass() || handPass || particlePass || rainPass;
    }

    /** The ITEM-format pipeline, shared with the GUI pass for item icons. */
    public static Pipeline getItemPipeline() {
        return itemPipeline;
    }

    /** The entity-model pipeline, shared with the GUI pass for builtin (TESR) item icons. */
    public static Pipeline getEntityPipeline() {
        return entityPipeline;
    }

    /** Clears the hurt/death tint state; GUI item draws must never inherit a stale one. */
    public static void clearRedOverlay() {
        redOverlayActive = false;
    }

    public static void setCurrentEntity(Entity entity) {
        currentEntity = entity;
        cachedEntityTexture = null;
        cachedTextureId = -1;
    }

    /** Called once per frame before the world render; forces a fresh fog/projection snapshot. */
    public static void onFrameBegin() {
        frameStateCached = false;
        // Safety reset: a throw between a pass's head and return must not leak the flag.
        handPass = false;
        particlePass = false;
        entityPassDepth = 0;
        tesrLight = -1;
        // Drop any weather left un-flushed by an aborted frame before re-capturing this one.
        weatherPending = false;
        weatherVerts = 0;
        weatherBatch.clear();
        weatherBatchTex.clear();
        // The rain pass spans from here to the HUD's head (GuiIngameM closes it):
        // renderRainSnow is private and unmapped, so it cannot be bracketed directly,
        // and the only particle-format batch in between that is not already the
        // particle pipeline's is the weather itself.
        rainPass = true;
    }

    public static void setCompiling(boolean value) {
        compiling = value;
    }

    /**
     * Called at the head of {@code WorldVertexBufferUploader.draw}. Returns false when the
     * batch is not an entity model draw, so the caller leaves the GL draw untouched.
     */
    public static boolean capture(BufferBuilder buffer) {
        if (!isActive() || compiling || !Drawer.isActive() || Drawer.skipRendering) {
            return false;
        }
        int vertexCount = buffer.getVertexCount();
        int drawMode = buffer.getDrawMode();
        VertexFormat format = buffer.getVertexFormat();
        // A fully masked-off batch writes no colour in GL (the boat's depth-only
        // noWater occluder); replaying it would paint a visible quad instead.
        if (VRenderSystem.colorMask == 0) {
            return false;
        }
        Pipeline pipeline = pipelineFor(format);
        if (vertexCount <= 0 || drawMode != 7 || pipeline == null) {
            return false;
        }

        try {
            if (accumulating && format == MODEL_FORMAT) {
                accumulate(buffer, vertexCount);
            } else {
                ByteBuffer data = buffer.getByteBuffer();
                int prev = data.position();
                data.position(0);
                submit(data, vertexCount, format, pipeline);
                data.position(prev);
            }
            return true;
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("entity", t);
            return false;
        }
    }

    /**
     * The pipeline for a captured draw's format, gated by the active pass: mob models,
     * shadows and flat overlay quads only inside the entity pass; item models (ITEM)
     * inside the entity pass (dropped items) and the hand pass (first-person items and
     * held blocks, both re-emitted as ITEM); particle billboards only inside the
     * particle pass; the rain/snow quads (particle format, per-vertex lightmap)
     * inside the rain pass; held blocks (BLOCK, the enderman's) inside the entity
     * pass.
     */
    private static Pipeline pipelineFor(VertexFormat format) {
        if (format == MODEL_FORMAT) {
            return isEntityPass() ? entityPipeline : null;
        }
        if (format == ITEM_FORMAT) {
            return isEntityPass() || handPass ? itemPipeline : null;
        }
        if (format == SHADOW_FORMAT) {
            return isEntityPass() ? shadowPipeline : null;
        }
        if (format == FLAT_FORMAT) {
            return isEntityPass() ? flatPipeline : null;
        }
        if (format == PARTICLE_FORMAT) {
            // Cleanroom builds the weather quads in the particle format (per-vertex
            // lightmap from getCombinedLight), so the rain pass reuses the particle
            // pipeline: ColorModulator * vertexColor * texture * lightmap + fog.
            return particlePass || rainPass ? particlePipeline : null;
        }
        if (format == BLOCK_FORMAT) {
            // Held blocks (the enderman's) go through the terrain shader: the BLOCK
            // vertices carry their own lightmap UVs, so the CPU lightmap multiply is
            // skipped for this pipeline in applyDrawState.
            return isEntityPass() ? blockPipeline : null;
        }
        return null;
    }

    /** Appends one captured quad to the per-part staging buffer (no draw yet). */
    private static void accumulate(BufferBuilder buffer, int vertexCount) {
        int stride = MODEL_FORMAT.getSize();
        int bytes = vertexCount * stride;
        int off = stagingVerts * stride;
        int need = off + bytes;
        if (staging.capacity() < need) {
            MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAlloc(Math.max(need * 2, 1 << 14));
        }
        ByteBuffer src = buffer.getByteBuffer().duplicate();
        src.position(0);
        src.limit(bytes);
        staging.position(off);
        staging.put(src);
        stagingVerts += vertexCount;
    }

    /** Opens a per-part accumulation window (called before the box rebuild). */
    public static void beginPart() {
        accumulating = true;
        stagingVerts = 0;
    }

    /** Closes the accumulation window and issues the part as one draw (its model-view). */
    public static void endPart() {
        accumulating = false;
        if (stagingVerts <= 0) {
            return;
        }
        int n = stagingVerts;
        stagingVerts = 0;
        staging.position(0);
        staging.limit(n * MODEL_FORMAT.getSize());
        submit(staging, n, MODEL_FORMAT, entityPipeline);
    }

    /** Replays {@code data} (position 0, {@code vertexCount} verts of {@code format}) in Vulkan. */
    private static void submit(ByteBuffer data, int vertexCount, VertexFormat format, Pipeline pipeline) {
        net.vulkanmod.debug.FrameProfiler.entityVerts(vertexCount);
        net.vulkanmod.debug.FrameProfiler.entityDrawBegin();
        VulkanImage texture = entityTexture();
        if (texture == null) {
            net.vulkanmod.debug.FrameProfiler.entityDrawEnd();
            return;
        }

        Drawer drawer = Drawer.getInstance();

        // --- state mirrored from GL at the entity draw ---
        // Projection and fog are frame-constants (set up once per world render): snapshot on
        // the first model part of the frame, reuse for the rest. The model-view carries the
        // per-part translate/rotate, so it (and MVP) must update every part.
        if (!frameStateCached) {
            VRenderSystem.updateProjectionMatrix();
            snapshotFog();
            frameStateCached = true;
        }
        VRenderSystem.updateModelViewMatrix();
        VRenderSystem.calculateMVP();

        VRenderSystem.depthMask(boolState(GL11.GL_DEPTH_WRITEMASK));
        if (boolState(GL11.GL_DEPTH_TEST)) {
            VRenderSystem.enableDepthTest();
        } else {
            VRenderSystem.disableDepthTest();
        }
        GL11.glGetIntegerv(GL11.GL_DEPTH_FUNC, int1);
        VRenderSystem.depthFunc(int1.get(0));

        // Solid entities keep back-face culling on; mirror the live state.
        if (boolState(GL11.GL_CULL_FACE)) {
            VRenderSystem.enableCull();
        } else {
            VRenderSystem.disableCull();
        }

        // The current GL colour is the entity tint (white for most mobs, the dye colour
        // for a sheep), matching the ColorMaterial diffuse of the fixed-function path,
        // modulated by the entity's single constant lightmap sample.
        GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, float4);
        tint[0] = float4.get(0);
        tint[1] = float4.get(1);
        tint[2] = float4.get(2);

        // The CPU lightmap multiply is only for the entity model pipeline, whose vertices
        // carry no lightmap. The BLOCK (enderman-held block) and PARTICLE (billboards and
        // the weather) pipelines sample the lightmap in the shader from their own per-vertex
        // UV2, so multiplying the tint by an entity's light on top of that double-applies a
        // light that is not theirs: the rain and particles inherited the last mob's lightmap
        // and turned black whenever that mob stood in the dark.
        int packed = (pipeline == blockPipeline || pipeline == particlePipeline) ? -1 : packedLight();
        if (packed >= 0) {
            // RenderManager.renderEntityStatic turns the packed light into the lightmap
            // unit's texture coordinate: block * 16 across, sky * 16 up.
            TextureBridge.multiplyLightmap(packed % 65536 / 16, packed / 65536 / 16, tint);
        }

        VRenderSystem.setShaderColor(tint[0], tint[1], tint[2], float4.get(3));

        // The Cleanroom hurt/death overlay tints the model red (the fixed-function
        // combiner constant RenderLivingBase sets around the model draw); only the
        // model pipeline carries the tint, matching vanilla's scope.
        redOverlayActive = pipeline == entityPipeline && isHurtOrDying();

        VRenderSystem.setShaderPipeline(pipeline);
        // Mirror the live blend state instead of forcing NO_BLEND: the eye pass blends
        // additively (ONE, ONE), items/shadows/see-through models with SRC_ALPHA, and the
        // plain model pass has blending off - all flow through GlStateManagerBlendM.
        Drawer.currentBlendState = VRenderSystem.getBlendState();
        VTextureSelector.bindTexture(texture);

        // The weather is not drawn here: it is buffered and replayed after the translucent
        // water layer so falling snow/rain is not hidden or tinted by the water. Only the
        // weather (rainPass) is deferred - the real particle billboards (particlePass) draw
        // in place. See deferWeather / flushWeather.
        if (rainPass && !particlePass && pipeline == particlePipeline) {
            deferWeather(data, vertexCount, texture);
            net.vulkanmod.debug.FrameProfiler.entityDrawEnd();
            return;
        }

        drawer.draw(data, 7, format, vertexCount);

        if (!loggedFirst) {
            loggedFirst = true;
            Initializer.LOGGER.info("[entity] first model draw: {} verts, tex {}", vertexCount, texture);
        }
        net.vulkanmod.debug.FrameProfiler.entityDrawEnd();
    }

    /**
     * The entity's packed light exactly as {@code RenderManager.renderEntityStatic}
     * computes it before handing the entity to its renderer.
     */
    /**
     * Buffers one weather batch (rain/snow quads) captured during {@code renderRainSnow}. The
     * weather is not one draw: vanilla samples the biome per column, so a view that straddles a
     * snow/rain biome boundary emits many small batches whose texture alternates between the
     * snow and rain sprites. Each batch is appended to a growable buffer (copying the existing
     * contents on growth) and recorded with its own texture; {@link #flushWeather()} replays
     * them after the translucent layer. The draw state (model-view, tint, blend, depth) is
     * constant across the weather, so it is snapshotted once from the first batch.
     */
    private static void deferWeather(ByteBuffer data, int vertexCount, VulkanImage texture) {
        int stride = PARTICLE_FORMAT.getSize();
        int bytes = vertexCount * stride;
        int off = weatherVerts * stride;
        int need = off + bytes;
        if (weatherBuffer == null || weatherBuffer.capacity() < need) {
            ByteBuffer grown = MemoryUtil.memAlloc(Math.max(need * 2, 1 << 16));
            if (weatherBuffer != null) {
                weatherBuffer.position(0);
                weatherBuffer.limit(off);
                grown.put(weatherBuffer);
                MemoryUtil.memFree(weatherBuffer);
            }
            weatherBuffer = grown;
        }
        // flushWeather replays each batch by setting this buffer's limit to that batch's end, so
        // after a frame the limit is left at that frame's total, not at the capacity. ByteBuffer.put
        // bounds against the limit, so without this reset the next frame's weather is capped at the
        // previous frame's total: once the column count dips (e.g. descending, where vanilla culls
        // the tall columns) the limit drops with it and every later frame overflows it, silently
        // dropping the rest of the weather - sticky patchiness that never recovers. The grow above
        // only checks capacity, which stays large enough, so the limit is otherwise never restored.
        weatherBuffer.limit(weatherBuffer.capacity());
        ByteBuffer src = data.duplicate();
        src.position(0);
        src.limit(bytes);
        weatherBuffer.position(off);
        weatherBuffer.put(src);
        weatherBatch.add(new int[]{weatherVerts, vertexCount});
        weatherBatchTex.add(texture);
        weatherVerts += vertexCount;

        if (!weatherPending) {
            VRenderSystem.modelViewMatrix.asFloatBuffer().position(0).limit(16).get(weatherMV);
            GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, float4);
            weatherTint[0] = float4.get(0);
            weatherTint[1] = float4.get(1);
            weatherTint[2] = float4.get(2);
            weatherTint[3] = float4.get(3);
            weatherBlend = VRenderSystem.getBlendState();
            weatherDepthMask = boolState(GL11.GL_DEPTH_WRITEMASK);
            weatherDepthTest = boolState(GL11.GL_DEPTH_TEST);
            GL11.glGetIntegerv(GL11.GL_DEPTH_FUNC, int1);
            weatherDepthFunc = int1.get(0);
            weatherCull = boolState(GL11.GL_CULL_FACE);
        }
        weatherPending = true;
    }

    /**
     * Replays the buffered weather after the translucent water layer, one batch at a time so
     * each keeps its own snow/rain texture. Called from {@code TerrainRenderer.onChunkLayerEnd}
     * for the translucent layer, which runs every frame (even with no water on screen), so the
     * weather is never dropped. The model-view captured at {@code renderRainSnow} time is
     * restored so the quads land exactly where they did.
     */
    public static void flushWeather() {
        if (!weatherPending) {
            return;
        }
        weatherPending = false;
        int n = weatherVerts;
        lastWeatherVerts = n;
        int batches = weatherBatch.size();
        weatherVerts = 0;
        if (n <= 0 || weatherBuffer == null || batches == 0
                || !Drawer.isActive() || Drawer.skipRendering) {
            weatherBatch.clear();
            weatherBatchTex.clear();
            return;
        }
        try {
            VRenderSystem.modelViewMatrix.position(0);
            VRenderSystem.modelViewMatrix.asFloatBuffer().position(0).limit(16).put(weatherMV);
            VRenderSystem.updateProjectionMatrix();
            VRenderSystem.calculateMVP();

            VRenderSystem.depthMask(weatherDepthMask);
            if (weatherDepthTest) {
                VRenderSystem.enableDepthTest();
            } else {
                VRenderSystem.disableDepthTest();
            }
            VRenderSystem.depthFunc(weatherDepthFunc);
            if (weatherCull) {
                VRenderSystem.enableCull();
            } else {
                VRenderSystem.disableCull();
            }
            VRenderSystem.setShaderColor(weatherTint[0], weatherTint[1], weatherTint[2], weatherTint[3]);
            VRenderSystem.setShaderPipeline(particlePipeline);
            Drawer.currentBlendState = weatherBlend;

            int stride = PARTICLE_FORMAT.getSize();
            if (weatherScratch == null || weatherScratch.capacity() < n * stride) {
                weatherScratch = MemoryUtil.memAlloc(Math.max(n * stride, 1 << 16));
            }
            for (int i = 0; i < batches; i++) {
                int[] vr = weatherBatch.get(i);
                int start = vr[0] * stride;
                int bytes = vr[1] * stride;
                // Drawer.draw reads its source from position 0, so stage this batch there.
                weatherBuffer.position(start);
                weatherBuffer.limit(start + bytes);
                weatherScratch.clear();
                weatherScratch.limit(bytes);
                weatherScratch.put(weatherBuffer);
                weatherScratch.limit(bytes).position(0);
                VTextureSelector.bindTexture(weatherBatchTex.get(i));
                Drawer.getInstance().draw(weatherScratch, 7, PARTICLE_FORMAT, vr[1]);
            }
        } catch (Throwable t) {
            TerrainRenderer.reportOnce("weather flush", t);
        } finally {
            weatherBatch.clear();
            weatherBatchTex.clear();
        }
    }

    /** Vertex count of the most recent {@link #flushWeather()}; used by the STICKY autotest. */
    public static int lastWeatherVerts() {
        return lastWeatherVerts;
    }

    private static int packedLight() {
        // First-person hand: the held item/block is lit by the viewer's own light. The hand
        // pass runs after the entity pass, so currentEntity is stale here - without this the
        // held item inherited the last mob's lightmap and went black when that mob stood in
        // the dark (the ITEM format carries no UV2, so the shader cannot supply the light).
        if (handPass) {
            Entity view = net.minecraft.client.Minecraft.getMinecraft().getRenderViewEntity();
            return view == null ? -1 : view.getBrightnessForRender();
        }
        // The eye layers (spider, enderman, ender dragon) blend additively (ONE/ONE)
        // and force the lightmap unit to full-bright (61680) so eyes glow in the dark.
        // That blend is unique to them in the entity pass, so it doubles as the pass
        // detector - the CPU lightmap multiply follows it instead of the entity's light.
        if (VRenderSystem.blendEnabled && VRenderSystem.blendSrcRgb == 1 && VRenderSystem.blendDstRgb == 1) {
            return 61680;
        }
        // Block-entity draws (chest/sign/book/frame models) light from their own position,
        // set by the dispatcher bracket - never from the stale last-rendered mob.
        if (tesrLight >= 0) {
            return tesrLight;
        }
        if (currentEntity == null) {
            return -1;
        }
        int packed = currentEntity.getBrightnessForRender();
        if (currentEntity.isBurning()) {
            packed = 15728880; // full block and sky light while alight
        }
        return packed;
    }

    /**
     * Resolves the texture bound on unit 0 for this draw. The skin is constant across an
     * entity's model parts, but later passes rebind (the enderman eye texture, the atlas
     * for items, shadow.png for the shadow), so the cache is keyed by the GL binding id
     * rather than held for the whole entity.
     */
    private static VulkanImage entityTexture() {
        int texId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (texId == 0) {
            return null;
        }
        if (cachedEntityTexture != null && texId == cachedTextureId && matchesLiveSize(cachedEntityTexture)) {
            return cachedEntityTexture;
        }
        // The block atlas is already mirrored for the terrain (tile-by-tile, animated);
        // binding the mirror beats a 16 MB snapshot that also freezes animated tiles.
        if (texId == TextureBridge.getAtlasGlId() && TextureBridge.getAtlasImage() != null) {
            cachedEntityTexture = TextureBridge.getAtlasImage();
            cachedTextureId = texId;
            return cachedEntityTexture;
        }
        VulkanImage image = textureCache.get(texId);
        if (image == null || !matchesLiveSize(image)) {
            image = downloadTexture(texId);
            if (image != null) {
                textureCache.put(texId, image);
            }
        }
        if (image != null) {
            cachedEntityTexture = image;
            cachedTextureId = texId;
        }
        return image;
    }

    /** True while RenderLivingBase would have the red hurt/death combiner overlay active. */
    private static boolean isHurtOrDying() {
        if (!(currentEntity instanceof net.minecraft.entity.EntityLivingBase living)) {
            return false;
        }
        return living.hurtTime > 0 || living.deathTime > 0;
    }

    /** UBO source for the {@code RedOverlay} field (0.3 matches the combiner constant). */
    public static float getRedOverlay() {
        return redOverlayActive ? 0.3f : 0.0f;
    }

    /**
     * A cached image is only trusted while the live texture still has the same size: GL
     * reuses deleted texture ids (see {@code AbstractTextureM}); the queried texture is
     * the current binding, so no rebind is needed.
     */
    private static boolean matchesLiveSize(VulkanImage image) {
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        return w == image.getWidth() && h == image.getHeight();
    }

    /** Forgets the Vulkan image mirrored from a GL texture that is being deleted. */
    public static void onTextureDeleted(int texId) {
        textureCache.remove(texId);
        if (cachedTextureId == texId) {
            cachedEntityTexture = null;
            cachedTextureId = -1;
        }
    }

    private static VulkanImage downloadTexture(int texId) {
        int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBinding);
            return null;
        }

        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        // Mirror the texture's live wrap mode. The weather texture scrolls its V
        // coordinate by a negative, time-based offset (renderRainSnow), which only
        // animates under GL_REPEAT; clamped to edge the streaks freeze. Entity skins
        // and the particle atlas keep whatever mode they were uploaded with.
        int wrapS = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S);
        int wrapT = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBinding);
        boolean clamp = wrapS != GL11.GL_REPEAT && wrapT != GL11.GL_REPEAT;
        pixels.position(0);
        pixels.limit(width * height * 4);

        // NEAREST filtering, clamped addressing, single mip level - the vanilla look:
        // every texel stays a crisp pixel (the GL default these textures are sampled
        // with). The Kirino reference happens to sample LINEAR, but that is a property
        // of the translation layer, not the target; the user wants the crisp pixels.
        VulkanImage image = new VulkanImage(1, width, height, 4, false, clamp);
        image.uploadSubTextureAsync(0, width, height, 0, 0, 4, 0, 0, width, pixels);
        MemoryUtil.memFree(pixels);
        return image;
    }

    // --- helpers (mirroring the SkyRenderer/CloudRenderer ones) ---

    private static void snapshotFog() {
        if (!boolState(GL11.GL_FOG)) {
            VRenderSystem.setFogState(VRenderSystem.FOG_NONE);
            return;
        }
        GL11.glGetIntegerv(GL11.GL_FOG_MODE, int1);
        int mode = switch (int1.get(0)) {
            case GL11.GL_EXP -> VRenderSystem.FOG_EXP;
            case GL11.GL_EXP2 -> VRenderSystem.FOG_EXP2;
            default -> VRenderSystem.FOG_LINEAR;
        };
        VRenderSystem.setFogState(mode);

        GL11.glGetFloatv(GL11.GL_FOG_COLOR, float4);
        VRenderSystem.setFogShaderColor(float4.get(0), float4.get(1), float4.get(2), float4.get(3));
        GL11.glGetFloatv(GL11.GL_FOG_START, float4);
        VRenderSystem.setFogStart(float4.get(0));
        GL11.glGetFloatv(GL11.GL_FOG_END, float4);
        VRenderSystem.setFogEnd(float4.get(0));
        GL11.glGetFloatv(GL11.GL_FOG_DENSITY, float4);
        VRenderSystem.setFogDensity(float4.get(0));
    }

    private static boolean boolState(int pname) {
        GL11.glGetBooleanv(pname, bool1);
        return bool1.get(0) != 0;
    }

    private EntityRenderer() {
    }
}
