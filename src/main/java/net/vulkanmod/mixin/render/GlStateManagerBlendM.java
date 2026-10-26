package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the blend enable bit and factors from the vanilla {@link GlStateManager}
 * calls. The Kirino GL layer does not answer {@code glGetBooleanv(GL_BLEND)}
 * (measured: false while the HUD was mid-blend), so the GUI pass cannot mirror
 * blending by querying GL; every 1.12.2 render path toggles it through this class
 * anyway. VRenderSystem already stores the state for {@code getBlendState()}.
 */
@Mixin(GlStateManager.class)
public class GlStateManagerBlendM {

    @Inject(method = "enableBlend", at = @At("HEAD"))
    private static void vulkanmod$enableBlend(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.enableBlend();
        }
    }

    @Inject(method = "disableBlend", at = @At("HEAD"))
    private static void vulkanmod$disableBlend(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.disableBlend();
        }
    }

    @Inject(method = "tryBlendFuncSeparate(IIII)V", at = @At("HEAD"))
    private static void vulkanmod$blendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        }
    }

    /**
     * {@code blendFunc(II)} sets the RGB and alpha factors together and does not route
     * through {@code tryBlendFuncSeparate}; the enderman eye pass (ONE, ONE) uses it.
     */
    @Inject(method = "blendFunc(II)V", at = @At("HEAD"))
    private static void vulkanmod$blendFunc2(int srcRgb, int dstRgb, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.tryBlendFuncSeparate(srcRgb, dstRgb, srcRgb, dstRgb);
        }
    }

    @Inject(method = "color(FFF)V", at = @At("HEAD"))
    private static void vulkanmod$color3(float r, float g, float b, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.trackedColorR = r;
            VRenderSystem.trackedColorG = g;
            VRenderSystem.trackedColorB = b;
            VRenderSystem.trackedColorA = 1;
        }
    }

    @Inject(method = "color(FFFF)V", at = @At("HEAD"))
    private static void vulkanmod$color4(float r, float g, float b, float a, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.trackedColorR = r;
            VRenderSystem.trackedColorG = g;
            VRenderSystem.trackedColorB = b;
            VRenderSystem.trackedColorA = a;
        }
    }

    /**
     * Tracks the texture id bound to GL_TEXTURE_2D. Every draw-time bind in 1.12.2
     * (entity skins, the block atlas, the font, GUI atlases) goes through this method,
     * so the entity/GUI texture mirrors can resolve their Vulkan image from the tracked
     * value instead of a {@code glGetInteger(GL_TEXTURE_BINDING_2D)} round-trip per draw.
     */
    @Inject(method = "bindTexture(I)V", at = @At("HEAD"))
    private static void vulkanmod$bindTexture(int texture, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.trackedBoundTexture = texture;
        }
    }

    @Inject(method = "enableTexture2D", at = @At("HEAD"))
    private static void vulkanmod$enableTexture2D(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.textureEnabled = true;
        }
    }

    @Inject(method = "disableTexture2D", at = @At("HEAD"))
    private static void vulkanmod$disableTexture2D(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.textureEnabled = false;
        }
    }

    /**
     * The boat's {@code noWater} occluder is drawn with the colour mask fully off
     * (depth only, to clip water inside the hull). The pipelines bake the mask into
     * their state, so the capture needs the tracked value to recognise and skip such
     * batches instead of painting them.
     */
    @Inject(method = "colorMask(ZZZZ)V", at = @At("HEAD"))
    private static void vulkanmod$colorMask(boolean red, boolean green, boolean blue, boolean alpha, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.colorMask(red, green, blue, alpha);
        }
    }

    /*
     * Texture matrix mirror. The matrix ops run for the modelview/projection stacks too,
     * so each hook only acts while the texture mode is current (a static int compare);
     * VRenderSystem keeps the stack in sync with the calls Kirino sees.
     */
    @Inject(method = "matrixMode(I)V", at = @At("HEAD"))
    private static void vulkanmod$matrixMode(int mode, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.texMatrixMode(mode);
        }
    }

    @Inject(method = "setActiveTexture(I)V", at = @At("HEAD"))
    private static void vulkanmod$setActiveTexture(int texture, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.texMatrixActiveTexture(texture);
        }
    }

    @Inject(method = "pushMatrix()V", at = @At("HEAD"))
    private static void vulkanmod$pushMatrix(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.texMatrixPush();
        }
    }

    @Inject(method = "popMatrix()V", at = @At("HEAD"))
    private static void vulkanmod$popMatrix(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.texMatrixPop();
        }
    }

    @Inject(method = "loadIdentity()V", at = @At("HEAD"))
    private static void vulkanmod$loadIdentity(CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.texMatrixLoadIdentity();
        }
    }

    @Inject(method = "translate(FFF)V", at = @At("HEAD"))
    private static void vulkanmod$translate(float x, float y, float z, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.texMatrixTranslate(x, y, z);
        }
    }

    @Inject(method = "rotate(FFFF)V", at = @At("HEAD"))
    private static void vulkanmod$rotate(float angle, float x, float y, float z, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.texMatrixRotate(angle, x, y, z);
        }
    }

    @Inject(method = "scale(FFF)V", at = @At("HEAD"))
    private static void vulkanmod$scale(float x, float y, float z, CallbackInfo ci) {
        if (Initializer.isVulkanActive()) {
            VRenderSystem.texMatrixScale(x, y, z);
        }
    }
}
