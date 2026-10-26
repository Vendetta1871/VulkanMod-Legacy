package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.RenderGlobal;
import net.vulkanmod.Initializer;
import net.vulkanmod.debug.FrameProfiler;
import net.vulkanmod.render.CloudRenderer;
import net.vulkanmod.render.SkyRenderer;
import net.vulkanmod.render.WorldBorderRenderer;
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
    abstract int vulkanmod$getCloudTickCounter();

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$renderSky(float partialTicks, int pass, CallbackInfo ci) {
        FrameProfiler.skyBegin();
        if (Initializer.isVulkanActive() && SkyRenderer.render(partialTicks, pass)) {
            // The TAIL hook below never runs for a cancelled method; close the profile span here.
            FrameProfiler.skyEnd();
            ci.cancel();
        }
    }

    @Inject(method = "renderSky(FI)V", at = @At("TAIL"))
    private void vulkanmod$renderSkyEnd(float partialTicks, int pass, CallbackInfo ci) {
        FrameProfiler.skyEnd();
    }

    @Inject(method = "renderClouds(FIDDD)V", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$renderClouds(float partialTicks, int pass, double x, double y, double z,
                                        CallbackInfo ci) {
        FrameProfiler.cloudsBegin();
        if (Initializer.isVulkanActive()
                && CloudRenderer.render(partialTicks, pass, this.vulkanmod$getCloudTickCounter(), x, y, z)) {
            FrameProfiler.cloudsEnd();
            ci.cancel();
        }
    }

    @Inject(method = "renderClouds(FIDDD)V", at = @At("TAIL"))
    private void vulkanmod$renderCloudsEnd(float partialTicks, int pass, double x, double y, double z,
                                           CallbackInfo ci) {
        FrameProfiler.cloudsEnd();
    }

    @Inject(method = "renderWorldBorder(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"))
    private void vulkanmod$renderWorldBorder(net.minecraft.entity.Entity player, float partialTicks,
                                             CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            WorldBorderRenderer.render(player, partialTicks);
        }
    }

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void vulkanmod$renderEntitiesBegin(net.minecraft.entity.Entity renderViewEntity,
                                               net.minecraft.client.renderer.culling.ICamera camera, float partialTicks,
                                               CallbackInfo ci) {
        FrameProfiler.entitiesBegin();
        // The block-entity (TESR) pass runs inside renderEntities but its dispatcher
        // methods are Cleanroom-added (unmapped), so the entity capture window spans the
        // whole span: everything drawn here is entity or block-entity geometry.
        if (Initializer.isVulkanActive()) {
            net.vulkanmod.render.EntityRenderer.setEntityPass(true);
        }
    }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void vulkanmod$renderEntitiesEnd(net.minecraft.entity.Entity renderViewEntity,
                                             net.minecraft.client.renderer.culling.ICamera camera, float partialTicks,
                                             CallbackInfo ci) {
        FrameProfiler.entitiesEnd();
        if (Initializer.isVulkanActive()) {
            net.vulkanmod.render.EntityRenderer.setEntityPass(false);
        }
    }
}
