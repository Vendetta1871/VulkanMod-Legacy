package net.vesu.mixin.render;

import net.minecraft.client.renderer.RenderGlobal;
import net.vesu.Initializer;
import net.vesu.debug.FrameProfiler;
import net.vesu.render.CloudRenderer;
import net.vesu.render.SkyRenderer;
import net.vesu.render.WorldBorderRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Vulkan sky and clouds at the head of {@code RenderGlobal.renderSky} and
 * {@code renderClouds} and cancels the vanilla GL layer when the Vulkan renderer has
 * fully replaced it (the GL sky and clouds would render into buffers that are never
 * presented). Custom (modded) sky/cloud renderers keep the GL path.
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalM {

    // Accessor instead of @Shadow: shadowed fields never reach the refmap and would
    // not bind under the SRG runtime names.
    @Accessor("cloudTickCounter")
    abstract int vesu$getCloudTickCounter();

    /** LOD terrain (AUDIT 5.15): opaque LODs after vanilla's last opaque layer, translucent ones before its translucent layer. */
    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"))
    private void vesu$lodTranslucent(net.minecraft.util.BlockRenderLayer layer, double partialTicks, int pass,
                                     net.minecraft.entity.Entity entity,
                                     org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> ci) {
        if (layer == net.minecraft.util.BlockRenderLayer.TRANSLUCENT && Initializer.isVulkanActive()) {
            net.vesu.lod.mc.LodSystem.renderTranslucent();
        } else if (layer == net.minecraft.util.BlockRenderLayer.SOLID && Initializer.isVulkanActive()) {
            net.vesu.lod.mc.LodSystem.renderPack();
        }
    }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("RETURN"))
    private void vesu$lodOpaque(net.minecraft.util.BlockRenderLayer layer, double partialTicks, int pass,
                                net.minecraft.entity.Entity entity,
                                org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> ci) {
        if (layer == net.minecraft.util.BlockRenderLayer.CUTOUT && Initializer.isVulkanActive()) {
            net.vesu.lod.mc.LodSystem.renderOpaque();
        }
    }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"))
    private void vesu$beforeLayer(net.minecraft.util.BlockRenderLayer layer, double partialTicks, int pass,
                                       net.minecraft.entity.Entity entity,
                                       org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> ci) {
        if (layer == net.minecraft.util.BlockRenderLayer.TRANSLUCENT && Initializer.isVulkanActive()
                && net.vesu.vulkan.FrameTargets.handInScene()) {
            net.vesu.vulkan.Drawer.beforeHand();
            if (!net.vesu.render.EntityRenderer.heldTranslucent()) vesu$drawHand();
        }
    }

    /** A translucent held block goes after the layer, OptiFine's {@code renderHand1}. */
    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("RETURN"), remap = true)
    private void vesu$afterLayer(net.minecraft.util.BlockRenderLayer layer, double partialTicks, int pass,
                                      net.minecraft.entity.Entity entity,
                                      org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> ci) {
        if (layer == net.minecraft.util.BlockRenderLayer.TRANSLUCENT && Initializer.isVulkanActive()
                && net.vesu.vulkan.FrameTargets.handInScene() && net.vesu.render.EntityRenderer.heldTranslucent()) {
            vesu$drawHand();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static void vesu$drawHand() {
        ((net.vesu.render.IHandInScene) net.minecraft.client.Minecraft.getMinecraft().entityRenderer)
                .vesu$renderHandInScene();
    }

    @Inject(method = "drawBlockDamageTexture", at = @At("HEAD"), remap = true)
    private void vesu$damageBegin(CallbackInfo ci) {
        net.vesu.render.EntityRenderer.setDamagePass(true);
    }

    @Inject(method = "drawBlockDamageTexture", at = @At("RETURN"), remap = true)
    private void vesu$damageEnd(CallbackInfo ci) {
        net.vesu.render.EntityRenderer.setDamagePass(false);
    }

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true)
    private void vesu$renderSky(float partialTicks, int pass, CallbackInfo ci) {
        FrameProfiler.skyBegin();
        net.vesu.vulkan.Drawer.tintFromShaderColor = true;
        boolean drawn = Initializer.isVulkanActive() && SkyRenderer.render(partialTicks, pass);
        net.vesu.vulkan.Drawer.tintFromShaderColor = false;
        if (drawn) {
            // The TAIL hook below never runs for a cancelled method; close the profile span here.
            FrameProfiler.skyEnd();
            ci.cancel();
        }
    }

    @Inject(method = "renderSky(FI)V", at = @At("TAIL"))
    private void vesu$renderSkyEnd(float partialTicks, int pass, CallbackInfo ci) {
        FrameProfiler.skyEnd();
    }

    @Inject(method = "renderClouds(FIDDD)V", at = @At("HEAD"), cancellable = true)
    private void vesu$renderClouds(float partialTicks, int pass, double x, double y, double z,
                                        CallbackInfo ci) {
        FrameProfiler.cloudsBegin();
        if (Initializer.isVulkanActive() && net.vesu.shader.PackManager.cloudsOff()) {
            FrameProfiler.cloudsEnd();
            ci.cancel();
            return;
        }
        if (Initializer.isVulkanActive()
                && CloudRenderer.render(partialTicks, pass, this.vesu$getCloudTickCounter(), x, y, z)) {
            FrameProfiler.cloudsEnd();
            ci.cancel();
        }
    }

    @Inject(method = "renderClouds(FIDDD)V", at = @At("TAIL"))
    private void vesu$renderCloudsEnd(float partialTicks, int pass, double x, double y, double z,
                                           CallbackInfo ci) {
        FrameProfiler.cloudsEnd();
    }

    @Inject(method = "renderWorldBorder(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"))
    private void vesu$renderWorldBorder(net.minecraft.entity.Entity player, float partialTicks,
                                             CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            WorldBorderRenderer.render(player, partialTicks);
        }
    }

    /**
     * In first person vanilla never renders the player; with RT its model still goes into the
     * RT scene (not on screen), so it casts shadows.
     */
    private static void renderFirstPersonPlayerForRt(net.minecraft.entity.Entity viewer, float partialTicks) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (!net.vesu.vulkan.rt.RtScene.enabled() || mc.gameSettings.thirdPersonView != 0
                || !(viewer instanceof net.minecraft.entity.player.EntityPlayer player) || player.isPlayerSleeping()
                || player.isSpectator()) {
            return;
        }
        net.vesu.render.EntityRenderer.setRtOnly(true);
        try {
            mc.getRenderManager().renderEntityStatic(player, partialTicks, false);
        } finally {
            net.vesu.render.EntityRenderer.setRtOnly(false);
        }
    }

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void vesu$renderEntitiesBegin(net.minecraft.entity.Entity renderViewEntity,
                                               net.minecraft.client.renderer.culling.ICamera camera, float partialTicks,
                                               CallbackInfo ci) {
        FrameProfiler.entitiesBegin();
        net.vesu.render.ParticleCulling.setCamera(camera);
        net.vesu.render.OcclusionCuller.setCamera(renderViewEntity, partialTicks);
        // The block-entity (TESR) pass runs inside renderEntities but its dispatcher
        // methods are Cleanroom-added (unmapped), so the entity capture window spans the
        // whole span: everything drawn here is entity or block-entity geometry.
        if (Initializer.isVulkanActive()) {
            net.vesu.render.EntityRenderer.setEntityPass(true);
        }
    }

    // See VisibleSections. The tile entity loop comes second; entities are drawn per section.
    @org.spongepowered.asm.mixin.injection.Redirect(method = "renderEntities", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;", ordinal = 0))
    private java.util.List<?> vesu$sectionsWithEntities(net.minecraft.client.renderer.RenderGlobal self) {
        return net.vesu.render.VisibleSections.withEntities(((RenderGlobalAccessor) (Object) self).vesu$getRenderInfos());
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "renderEntities", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;", ordinal = 1))
    private java.util.List<?> vesu$sectionsWithTiles(net.minecraft.client.renderer.RenderGlobal self) {
        return net.vesu.render.VisibleSections.withTileEntities(((RenderGlobalAccessor) (Object) self).vesu$getRenderInfos());
    }

    @org.spongepowered.asm.mixin.injection.Redirect(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;"))
    private java.util.List<?> vesu$sectionsOfLayer(net.minecraft.client.renderer.RenderGlobal self, net.minecraft.util.BlockRenderLayer layer,
                                                        double partialTicks, int pass, net.minecraft.entity.Entity entity) {
        return net.vesu.render.VisibleSections.forLayer(((RenderGlobalAccessor) (Object) self).vesu$getRenderInfos(), layer);
    }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void vesu$renderEntitiesEnd(net.minecraft.entity.Entity renderViewEntity,
                                             net.minecraft.client.renderer.culling.ICamera camera, float partialTicks,
                                             CallbackInfo ci) {
        FrameProfiler.entitiesEnd();
        if (Initializer.isVulkanActive()) {
            renderFirstPersonPlayerForRt(renderViewEntity, partialTicks);
            if (net.vesu.vulkan.ShadowPass.active()) {
                net.vesu.vulkan.ShadowPass.recording().renderUnseenEntities(renderViewEntity, partialTicks);
            }
            net.vesu.render.EntityRenderer.setEntityPass(false);
        }
    }

    // --- Camera-section visibility cache ---
    // setupTerrain floods again on every frame the camera moves, and every flood starts with
    // getVisibleFacings: a VisGraph of the camera's section built from its 4096 blocks. The result
    // depends only on the camera block and that section's blocks, so it is kept until the camera
    // changes block, a block or light change touches its section (markBlocksForUpdate) or the
    // renderers reload. Chunks loading elsewhere, constant while flying, leave it alone.
    private long vesu$facingsPos = Long.MIN_VALUE;
    private java.util.Set<net.minecraft.util.EnumFacing> vesu$facings;

    @Inject(method = "getVisibleFacings", at = @At("HEAD"), cancellable = true)
    private void vesu$cachedFacings(net.minecraft.util.math.BlockPos pos,
                                    org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.Set<net.minecraft.util.EnumFacing>> cir) {
        if (vesu$facings != null && pos.toLong() == vesu$facingsPos) {
            cir.setReturnValue(java.util.EnumSet.copyOf(vesu$facings));
        }
    }

    @Inject(method = "getVisibleFacings", at = @At("RETURN"))
    private void vesu$storeFacings(net.minecraft.util.math.BlockPos pos,
                                   org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.Set<net.minecraft.util.EnumFacing>> cir) {
        java.util.Set<net.minecraft.util.EnumFacing> set = cir.getReturnValue();
        vesu$facings = set.isEmpty() ? java.util.EnumSet.noneOf(net.minecraft.util.EnumFacing.class) : java.util.EnumSet.copyOf(set);
        vesu$facingsPos = pos.toLong();
    }

    @Inject(method = "markBlocksForUpdate", at = @At("HEAD"))
    private void vesu$blocksChanged(int x1, int y1, int z1, int x2, int y2, int z2, boolean now, CallbackInfo ci) {
        if (vesu$facings == null) return;
        net.minecraft.util.math.BlockPos p = net.minecraft.util.math.BlockPos.fromLong(vesu$facingsPos);
        int sx = p.getX() & ~15, sy = p.getY() & ~15, sz = p.getZ() & ~15;
        if (x2 >= sx && x1 <= sx + 15 && y2 >= sy && y1 <= sy + 15 && z2 >= sz && z1 <= sz + 15) vesu$facings = null;
    }

    @Inject(method = "loadRenderers", at = @At("HEAD"))
    private void vesu$renderersReloaded(CallbackInfo ci) {
        vesu$facings = null;
    }
}
