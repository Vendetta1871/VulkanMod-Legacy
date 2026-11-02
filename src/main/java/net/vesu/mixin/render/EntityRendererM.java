package net.vesu.mixin.render;

import net.vesu.Initializer;
import net.vesu.debug.FrameProfiler;
import net.vesu.render.GuiRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.vesu.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the Vulkan frame at the start of the world render; it is closed and
 * presented in {@code Minecraft.updateDisplay} (see MinecraftM).
 *
 * <p>The GUI pass opens at the {@code setupOverlayRendering} calls inside
 * {@code updateCameraAndRender}: vanilla calls it right before the HUD
 * ({@code renderGameOverlay}) in the in-game branch and right before the current
 * screen ({@code ForgeHooksClient.drawScreen}) in the no-world branch, so both overlay
 * paths flip the flag while the Vulkan render pass is still recording (it ends in
 * {@code presentFrame}). The redirect is scoped to this method on purpose:
 * {@code renderWorldPass} also calls {@code setupOverlayRendering} for the fire
 * overlay, and opening the pass there would mis-capture the following world layers as
 * GUI. The next frame's HEAD closes the pass again.</p>
 */
@Mixin(net.minecraft.client.renderer.EntityRenderer.class)
public abstract class EntityRendererM implements net.vesu.render.IHandInScene {

    @org.spongepowered.asm.mixin.Unique private float vesu$partialTicks;
    @org.spongepowered.asm.mixin.Unique private int vesu$pass;
    /** The hand drew before the translucent layer this frame: vanilla's call only adds the overlays. */
    @org.spongepowered.asm.mixin.Unique private boolean vesu$handDrawn;
    /** Inside the early call, which leaves the overlays to vanilla's. */
    @org.spongepowered.asm.mixin.Unique private boolean vesu$drawingHand;

    @org.spongepowered.asm.mixin.gen.Invoker("renderHand")
    abstract void vesu$invokeRenderHand(float partialTicks, int pass);

    @Inject(method = "renderWorldPass(IFJ)V", at = @At("HEAD"))
    private void vesu$worldPassBegin(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        vesu$pass = pass;
        vesu$partialTicks = partialTicks;
        vesu$handDrawn = false;
    }

    /**
     * OptiFine's renderHand0: with a pack, the arm and held items draw before the translucent
     * layer, so {@code DEFERRED} sees them. Called at the head of that layer (RenderGlobalM),
     * before it enables the chunk vertex arrays, which a display list compile would read.
     * The matrices and state are the world's: both are put back for the layer.
     */
    @Override
    public void vesu$renderHandInScene() {
        if (vesu$handDrawn) return;
        vesu$handDrawn = true;
        GlStateManager.matrixMode(5889);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(5888);
        GlStateManager.pushMatrix();
        // The state of the translucent layer, not the one vanilla's renderHand runs in: without
        // the alpha test the empty outer layer of the skin covers the arm.
        boolean alphaTest = net.vesu.vulkan.VRenderSystem.alphaTest;
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(516, 0.1F);
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        vesu$drawingHand = true;
        vesu$invokeRenderHand(vesu$partialTicks, vesu$pass);
        vesu$drawingHand = false;
        if (!alphaTest) GlStateManager.disableAlpha();
        GlStateManager.matrixMode(5889);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(5888);
        GlStateManager.popMatrix();

        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(515);
        GlStateManager.enableCull();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.alphaFunc(516, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.depthMask(false);
        net.minecraft.client.Minecraft.getMinecraft().getTextureManager()
                .bindTexture(net.minecraft.client.renderer.texture.TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.shadeModel(7425);
        net.vesu.render.EntityRenderer.invalidateFrameState();
    }

    @Redirect(method = "renderHand(FI)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemRenderer;renderItemInFirstPerson(F)V"))
    private void vesu$handItems(net.minecraft.client.renderer.ItemRenderer self, float partialTicks) {
        if (vesu$drawingHand || !vesu$handDrawn) self.renderItemInFirstPerson(partialTicks);
    }

    @Redirect(method = "renderHand(FI)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemRenderer;renderOverlays(F)V"))
    private void vesu$handOverlays(net.minecraft.client.renderer.ItemRenderer self, float partialTicks) {
        if (!vesu$drawingHand) self.renderOverlays(partialTicks);
    }

    // Accessors instead of @Shadow: shadowed fields never reach the refmap and would
    // not bind under the SRG runtime names.
    @Accessor("fogColorRed")
    abstract float vesu$getFogColorRed();

    @Accessor("fogColorGreen")
    abstract float vesu$getFogColorGreen();

    @Accessor("fogColorBlue")
    abstract float vesu$getFogColorBlue();

    /**
     * The frame clear color. Vanilla clears the world framebuffer with
     * {@code fogColorRed/Green/Blue} (set here via {@code GlStateManager.clearColor}),
     * while GL_FOG_COLOR is only written later, per pass, in {@code setupFogColor} -
     * reading it at frame start would grab whatever fog the last pass of the previous
     * frame left behind (weather, overlays), which is lighter than the world fog.
     * Capturing the fields here mirrors the exact value vanilla clears with; the
     * Vulkan pass consumes it next frame (the clear happens before this method runs).
     */
    @Inject(method = "updateFogColor(F)V", at = @At("TAIL"))
    private void vesu$captureClearColor(float partialTicks, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.setClearColor(vesu$getFogColorRed(), vesu$getFogColorGreen(),
                    vesu$getFogColorBlue());
        }
    }

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void vesu$beginFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameProfiler.renderBegin();
        GuiRenderer.recordFrameBegin();
        GuiRenderer.setGuiPass(false);
        net.vesu.render.EntityRenderer.onFrameBegin();
        net.vesu.render.ChunkBatcher.applyVertexFlags();
        FrameProfiler.syncBegin();
        Initializer.beginFrame();
        FrameProfiler.syncEnd();
    }

    @Inject(method = "renderHand(FI)V", at = @At("HEAD"))
    private void vesu$beginHand(float partialTicks, int pass, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            // B3: the hand draws at display resolution over the upscaled world. A pack's
            // passes see it instead, so there it stays in the scene.
            if (net.vesu.vulkan.FrameTargets.handInScene()) net.vesu.vulkan.Drawer.setHandDepth(true);
            else net.vesu.vulkan.Drawer.enterDisplayPhase();
            net.vesu.render.EntityRenderer.setHandPass(true);
        }
    }

    @Inject(method = "renderHand(FI)V", at = @At("RETURN"))
    private void vesu$endHand(float partialTicks, int pass, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            net.vesu.render.EntityRenderer.setHandPass(false);
            net.vesu.vulkan.Drawer.setHandDepth(false);
        }
    }

    @Inject(method = "updateCameraAndRender", at = @At("TAIL"))
    private void vesu$endFrame(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameProfiler.renderEnd();
    }

    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void vesu$worldBegin(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameProfiler.worldBegin();
    }

    @Inject(method = "renderWorld", at = @At("TAIL"))
    private void vesu$worldEnd(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameProfiler.worldEnd();
        if (Initializer.isVulkanActive()) {
            // A no-op without a selected shader pack (or VESU_FRAME_DUMP).
            net.vesu.shader.FrameUniforms.onWorldRendered(partialTicks);
        }
    }

    @Redirect(method = "updateCameraAndRender",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;setupOverlayRendering()V"))
    private void vesu$beginGui(net.minecraft.client.renderer.EntityRenderer self) {
        if (Initializer.isVulkanActive()) {
            // The weather has been drawn by now (it runs inside renderWorldPass), so the
            // rain window closes here. Cleanroom routes the HUD through GuiIngameForge,
            // which overrides renderGameOverlay without calling super, so this call site
            // is the only reliable "HUD begins" point.
            net.vesu.render.EntityRenderer.setRainPass(false);
            GuiRenderer.setGuiPass(true);
            // B3: the GUI draws at display resolution (no-op when the hand switched already).
            net.vesu.vulkan.Drawer.enterDisplayPhase();
        }
        self.setupOverlayRendering();
    }
}
