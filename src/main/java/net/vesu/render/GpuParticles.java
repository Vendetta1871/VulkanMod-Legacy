package net.vesu.render;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumType;
import net.minecraft.client.renderer.vertex.VertexFormatElement.EnumUsage;
import net.minecraft.util.math.Vec3d;
import net.vesu.Initializer;
import net.vesu.shader.PackManager;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * GPU particle quads (setting {@code gpuParticles}), after AsyncParticles' GPU rendering.
 * Vanilla's {@code Particle.renderParticle} builds every billboard on the CPU: four
 * {@code Vec3d}, an optional quaternion rotation and sixteen {@code BufferBuilder} calls for
 * 112 bytes of vertices. Here it writes one 44-byte record (camera-relative center, half size,
 * roll angle, UV rectangle, color, light) and {@code particle_gpu.vsh} expands it into the quad
 * with the same camera axes and rotation, drawn instanced over the shared quad index buffer.
 *
 * <p>The record is taken at the head of the base {@code renderParticle}, so particles whose
 * class adjusts its fields and then calls it (flame, portal, fireworks) go this way too;
 * classes that draw their quads themselves (block breaking, item pickup) keep the
 * {@code BufferBuilder} path. Each texture layer of {@code renderParticles} draws its CPU quads
 * first and then its records, with the layer's texture, blend and depth mask. Only without a
 * shader pack: a pack's particle programs read the vanilla vertex format.</p>
 */
public final class GpuParticles {

    /** Instance record, one per particle (vertex input rate INSTANCE, see Pipeline). */
    public static final VertexFormat FORMAT = new VertexFormat()
            .addElement(new VertexFormatElement(0, EnumType.FLOAT, EnumUsage.POSITION, 3))   // center
            .addElement(new VertexFormatElement(0, EnumType.FLOAT, EnumUsage.UV, 2))         // half size, angle
            .addElement(new VertexFormatElement(1, EnumType.FLOAT, EnumUsage.UV, 2))         // min U, min V
            .addElement(new VertexFormatElement(2, EnumType.FLOAT, EnumUsage.UV, 2))         // max U, max V
            .addElement(new VertexFormatElement(0, EnumType.UBYTE, EnumUsage.COLOR, 4))
            .addElement(new VertexFormatElement(3, EnumType.SHORT, EnumUsage.UV, 2));        // light
    private static final int STRIDE = 44;

    private static ByteBuffer buffer = MemoryUtil.memAlloc(STRIDE * 4096);
    private static int count;
    private static boolean accepting;
    /** Camera right, camera up (vanilla's rotation parameters) and view direction, as vec4s. */
    private static final float[] AXES = new float[12];

    private GpuParticles() {}

    /** Head of {@code renderParticles}: records are taken until its return. */
    public static void begin() {
        accepting = Initializer.CONFIG.gpuParticles && Initializer.isVulkanActive() && PackManager.active() == null
                && EntityRenderer.gpuParticlesReady();
        count = 0;
    }

    public static void end() {
        accepting = false;
        count = 0;
    }

    public static boolean accepting() {
        return accepting;
    }

    /** {@code Particle.renderParticle}, as a record. The fields are the particle's own. */
    public static void add(Particle particle, float partialTicks, float rotX, float rotZ, float rotYZ, float rotXY, float rotXZ,
                           double x, double y, double z, double prevX, double prevY, double prevZ, float scale,
                           TextureAtlasSprite sprite, int indexX, int indexY, float r, float g, float b, float a,
                           float angle, float prevAngle) {
        if ((count + 1) * STRIDE > buffer.capacity()) {
            buffer = MemoryUtil.memRealloc(buffer, buffer.capacity() * 2);
        }
        float u0, u1, v0, v1;
        if (sprite != null) {
            u0 = sprite.getMinU();
            u1 = sprite.getMaxU();
            v0 = sprite.getMinV();
            v1 = sprite.getMaxV();
        } else {
            u0 = indexX / 16.0f;
            u1 = u0 + 0.0624375f;
            v0 = indexY / 16.0f;
            v1 = v0 + 0.0624375f;
        }
        int light = particle.getBrightnessForRender(partialTicks);
        // Vanilla interpolates the roll this way (not from prevAngle to angle), and only when angle != 0.
        float roll = angle == 0.0f ? 0.0f : angle + (angle - prevAngle) * partialTicks;

        AXES[0] = rotX; AXES[1] = 0.0f; AXES[2] = rotYZ;
        AXES[4] = rotXY; AXES[5] = rotZ; AXES[6] = rotXZ;
        Vec3d view = Particle.cameraViewDir;
        if (view != null) {
            AXES[8] = (float) view.x; AXES[9] = (float) view.y; AXES[10] = (float) view.z;
        }

        int at = count * STRIDE;
        buffer.putFloat(at, (float) (prevX + (x - prevX) * partialTicks - Particle.interpPosX))
                .putFloat(at + 4, (float) (prevY + (y - prevY) * partialTicks - Particle.interpPosY))
                .putFloat(at + 8, (float) (prevZ + (z - prevZ) * partialTicks - Particle.interpPosZ))
                .putFloat(at + 12, 0.1f * scale)
                .putFloat(at + 16, roll)
                .putFloat(at + 20, u0).putFloat(at + 24, v0)
                .putFloat(at + 28, u1).putFloat(at + 32, v1)
                .put(at + 36, (byte) (int) (r * 255.0f)).put(at + 37, (byte) (int) (g * 255.0f))
                .put(at + 38, (byte) (int) (b * 255.0f)).put(at + 39, (byte) (int) (a * 255.0f))
                // The two light shorts in BufferBuilder.lightmap(j, k) order.
                .putShort(at + 40, (short) (light >> 16 & 0xFFFF)).putShort(at + 42, (short) (light & 0xFFFF));
        count++;
    }

    /** After the layer's {@code Tessellator.draw}: its records, with the layer's state. */
    public static void flush() {
        if (count == 0) return;
        buffer.position(0);
        buffer.limit(count * STRIDE);
        try {
            EntityRenderer.submitParticleInstances(buffer, count);
        } finally {
            buffer.clear();
            count = 0;
        }
    }

    /** UBO field {@code ParticleAxes}. */
    public static float[] axes() {
        return AXES;
    }
}
