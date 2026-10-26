package net.vulkanmod.vulkan.texture;

import java.nio.ByteBuffer;

public abstract class VTextureSelector {
    private static VulkanImage boundTexture;
    private static VulkanImage lightTexture;
    private static VulkanImage overlayTexture;

    private static final VulkanImage whiteTexture = VulkanImage.createWhiteTexture();

    private static int activeTexture = 0;

    public static void bindTexture(VulkanImage texture) {
        boundTexture = texture;
    }

    public static void bindTexture(int i, VulkanImage texture) {
        switch (i) {
            case 0 -> boundTexture = texture;
            case 1 -> lightTexture = texture;
            case 2 -> overlayTexture = texture;
        }
    }

    public static void setLightTexture(VulkanImage texture) {
        lightTexture = texture;
    }

    public static void setOverlayTexture(VulkanImage texture) { overlayTexture = texture; }

    public static void setActiveTexture(int activeTexture) {
        VTextureSelector.activeTexture = activeTexture;
    }

    public static VulkanImage getLightTexture() {
        return lightTexture;
    }

    public static VulkanImage getOverlayTexture() {
        return overlayTexture;
    }

    public static VulkanImage getBoundTexture() { return boundTexture; }

    public static VulkanImage getWhiteTexture() { return whiteTexture; }
}
