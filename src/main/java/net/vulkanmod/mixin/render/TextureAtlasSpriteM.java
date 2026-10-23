package net.vulkanmod.mixin.render;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.vulkanmod.render.TextureBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Flags the block atlas for re-download only when an animated sprite (water, lava, fire,
 * ...) actually changes the pixels it uploads to GL.
 *
 * <p>{@code updateAnimation()} runs once per game tick for every animated sprite, but it
 * only re-uploads a frame when the frame index advances (a few times per second) or, for
 * interpolated animations, every tick. Tracking the {@code frameCounter} transition lets
 * the Vulkan atlas mirror re-download at that low rate instead of every frame.</p>
 */
@Mixin(TextureAtlasSprite.class)
public class TextureAtlasSpriteM {

    @Shadow
    protected int frameCounter;

    @Shadow
    private AnimationMetadataSection animationMetadata;

    @Unique
    private int vulkanmod$prevFrameCounter = -1;

    @Inject(method = "updateAnimation", at = @At("HEAD"))
    private void vulkanmod$captureFrame(CallbackInfo ci) {
        vulkanmod$prevFrameCounter = this.frameCounter;
    }

    @Inject(method = "updateAnimation", at = @At("RETURN"))
    private void vulkanmod$detectFrameChange(CallbackInfo ci) {
        boolean advanced = this.frameCounter != vulkanmod$prevFrameCounter;
        boolean interpolated = this.animationMetadata != null && this.animationMetadata.isInterpolate();
        if (advanced || interpolated) {
            TextureBridge.markAtlasDirty();
        }
    }
}
