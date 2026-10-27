package net.vulkanmod.vulkan;

import net.minecraft.client.Minecraft;

/** World lighting inputs shared by the RT passes. */
final class RtLighting {

    private RtLighting() {}

    /**
     * {x, y, z, daylight}: direction towards the sun in world axes (vanilla's sky rotates
     * Y by -90 degrees, then X by the celestial angle) and daylight in 0..1.
     */
    static float[] sun() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return new float[]{0.0f, 1.0f, 0.0f, 1.0f};
        double angle = mc.world.getCelestialAngle(mc.getRenderPartialTicks()) * Math.PI * 2.0;
        float x = (float) -Math.sin(angle);
        float y = (float) Math.cos(angle);
        float daylight = Math.max(0.0f, Math.min(1.0f, y * 2.0f + 0.5f));
        return new float[]{x, y, 0.0f, daylight};
    }

    /** Rain and thunder strength 0..1 (overcast skies cast no sharp shadows). */
    static float overcast() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return 0.0f;
        float pt = mc.getRenderPartialTicks();
        return Math.max(mc.world.getRainStrength(pt), mc.world.getThunderStrength(pt));
    }

    /** Whether the current dimension has a sun (the Nether and the End do not). */
    static boolean hasSky() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.world != null && mc.world.provider.isSurfaceWorld();
    }
}
