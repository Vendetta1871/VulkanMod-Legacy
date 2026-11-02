package net.vesu.mixin.render;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.vesu.render.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Mob model parts are normally replayed from a GL display list ({@code glCallList}),
 * which never routes through the {@link BufferBuilder} the Vulkan capture reads. While
 * an entity is rendering this redirects the {@code callList} in {@code render} and
 * {@code renderWithRotation} to rebuild the boxes into the shared BufferBuilder instead,
 * so each quad flows through {@code WorldVertexBufferUploader.draw} and gets captured.
 * The per-part translate/rotate the original method applies before the call is left in
 * place, so the live GL modelview still carries the full part transform.
 *
 * <p>The display-list compile itself is flagged so its (recording-only) draws are not
 * captured with the wrong matrix.</p>
 *
 * <p>The same rebuild runs for the hand pass: the first-person arms are drawn through
 * {@code ModelRenderer.render} inside {@code renderHand}.</p>
 */
@Mixin(ModelRenderer.class)
public abstract class ModelRendererM {

    // Accessor instead of @Shadow: shadowed fields never reach the refmap and would
    // not bind under the SRG runtime names.
    @Accessor("cubeList")
    abstract List<ModelBox> vesu$getCubeList();

    private float vesu$scale;

    // Soft-implemented field merged into ModelRenderer: this part's rebuilt geometry,
    // replayed instead of rebuilt on the CPU every frame (M13). It dies with the part,
    // so a renderer reload (F3+A) drops the cache with the model.
    private EntityRenderer.PartCache vesu$partCache;

    /** Signature meaning "never cache or batch this part" (ModelBox subclass, R8). */
    private static final long VESU$NO_CACHE = Long.MIN_VALUE;

    @Inject(method = {"render(F)V", "renderWithRotation(F)V"}, at = @At("HEAD"))
    private void vesu$storeScale(float scale, CallbackInfo ci) {
        this.vesu$scale = scale;
    }

    @Redirect(method = {"render(F)V", "renderWithRotation(F)V"},
            at = @At(value = "INVOKE", target = "net/minecraft/client/renderer/GlStateManager.callList(I)V"))
    private void vesu$rebuildOrCallList(int list) {
        if (EntityRenderer.isEntityPass() || EntityRenderer.isHandPass()) {
            net.vesu.debug.FrameProfiler.entityRebuildBegin();
            float scale = this.vesu$scale;
            long signature = vesu$geometrySignature();
            boolean cacheable = signature != VESU$NO_CACHE;
            EntityRenderer.PartCache cache = this.vesu$partCache;
            if (cacheable && cache != null && cache.scale == scale && cache.signature == signature
                    && EntityRenderer.submitCachedPart(cache)) {
                // The boxes have not changed since the cache entry: replay the vertices
                // under this part's live matrix instead of rebuilding them (M13). A
                // refused replay (dropped frame, compile, colorMask(0)) falls through
                // to the normal capture path (R1).
                net.vesu.debug.FrameProfiler.entityRebuildEnd();
                return;
            }

            // Accumulate the part's quads (each box.render flushes one quad) and issue them
            // as a single draw - they all share this part's model-view, texture and tint.
            // Without batching the quads go out one draw each and there is no staging to
            // snapshot, so nothing is cached. Non-vanilla boxes go quad by quad and are
            // never cached (R8).
            boolean batch = EntityRenderer.BATCH_PARTS && cacheable;
            if (batch) {
                EntityRenderer.beginPart();
            }
            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            for (ModelBox box : this.vesu$getCubeList()) {
                box.render(buffer, scale);
            }
            if (batch) {
                EntityRenderer.endPart();
                this.vesu$partCache = EntityRenderer.recordPart(cache, scale, signature);
            }
            net.vesu.debug.FrameProfiler.entityRebuildEnd();
        } else if (net.vesu.render.GuiRenderer.isGuiPass()) {
            // Builtin item icons (the chest, the shulker box, ...) render their baked
            // boxes through the TESR dispatcher during the GUI pass, and so does the
            // inventory paper doll (GuiContainer.drawEntityOnScreen reaches renderEntity
            // with the entity window closed); rebuild them the same way so the batch
            // reaches the GUI capture instead of a display list.
            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            for (ModelBox box : this.vesu$getCubeList()) {
                box.render(buffer, this.vesu$scale);
            }
        } else {
            GlStateManager.callList(list);
        }
    }

    /**
     * Cheap fingerprint of the part's baked geometry: the identity of every box plus its
     * corner coordinates. The corners are final, so a model that changes shape (books,
     * beds) replaces the ModelBox objects in cubeList, which the identity hashes catch;
     * a subclass with a custom render cannot be fingerprinted at all, so such parts are
     * marked NO_CACHE and always rebuilt. Rotations ride the modelview and never touch
     * the boxes.
     */
    private long vesu$geometrySignature() {
        List<ModelBox> boxes = this.vesu$getCubeList();
        long h = 0x9E3779B97F4A7C15L * (boxes.size() + 1);
        for (ModelBox box : boxes) {
            if (box.getClass() != ModelBox.class) {
                return VESU$NO_CACHE;
            }
            h = h * 31 + System.identityHashCode(box);
            h = h * 31 + Float.floatToIntBits(box.posX1);
            h = h * 31 + Float.floatToIntBits(box.posY1);
            h = h * 31 + Float.floatToIntBits(box.posZ1);
            h = h * 31 + Float.floatToIntBits(box.posX2);
            h = h * 31 + Float.floatToIntBits(box.posY2);
            h = h * 31 + Float.floatToIntBits(box.posZ2);
        }
        return h == VESU$NO_CACHE ? h + 1 : h;
    }

    @Inject(method = "compileDisplayList(F)V", at = @At("HEAD"))
    private void vesu$compileStart(float scale, CallbackInfo ci) {
        EntityRenderer.setCompiling(true);
    }

    @Inject(method = "compileDisplayList(F)V", at = @At("RETURN"))
    private void vesu$compileEnd(float scale, CallbackInfo ci) {
        EntityRenderer.setCompiling(false);
    }
}
