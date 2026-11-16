package net.vesu.weather;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.function.Predicate;

/**
 * Rain water running down the side of a block: a thin trickle on the wall face that stops and
 * goes, drips off where the wall ends and fades out.
 */
final class StreakParticle extends Particle {

    private final EnumFacing face;
    private final Predicate<IBlockState> walls;
    private boolean fading, dripped;

    StreakParticle(World world, double x, double y, double z, EnumFacing face, Predicate<IBlockState> walls, float[] tint) {
        super(world, x, y, z);
        Weather.live++;
        this.face = face;
        this.walls = walls;
        this.canCollide = false;
        this.motionX = 0.0;
        this.motionY = -0.1;
        this.motionZ = 0.0;
        this.particleGravity = 0.0f;
        this.particleAlpha = Weather.STREAK_OPACITY;
        this.particleScale = Weather.STREAK_SIZE;
        this.particleMaxAge = (int) (Weather.PARTICLE_DISTANCE * 100);
        this.particleRed = tint[0];
        this.particleGreen = tint[1];
        this.particleBlue = tint[2];
        TextureAtlasSprite sprite = WeatherSprites.get("streak");
        if (sprite != null) setParticleTexture(sprite);
        updateBox();
    }

    @Override
    public int getFXLayer() {
        return 1;
    }

    @Override
    public void setExpired() {
        if (!isExpired) Weather.live--;
        super.setExpired();
    }

    @Override
    public void onUpdate() {
        prevPosX = posX;
        prevPosY = posY;
        prevPosZ = posZ;
        if (particleAge++ >= particleMaxAge) {
            setExpired();
            return;
        }
        motionY -= 0.04 * particleGravity;
        posX += motionX;
        posY += motionY;
        posZ += motionZ;
        motionX *= 0.98;
        motionY *= 0.98;
        motionZ *= 0.98;

        if (fading) {
            particleGravity = 0.0f;
            motionY = 0.0;
            particleAlpha -= 0.1f;
            if (particleAlpha <= 0.0f) setExpired();
            return;
        }
        // Stop and go.
        if (particleAge % 10 == 0) particleGravity = rand.nextBoolean() ? rand.nextFloat() * 0.1f : 0.0f;

        Vec3d from = new Vec3d(posX, posY, posZ);
        Vec3d to = from.add(-face.getXOffset() * 0.06, 0.0, -face.getZOffset() * 0.06);
        RayTraceResult hit = world.rayTraceBlocks(from, to, false, true, false);
        BlockPos here = new BlockPos(posX, posY, posZ);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            if (!dripped) {
                dripped = true;
                world.spawnParticle(EnumParticleTypes.DRIP_WATER, posX, posY - 0.05, posZ, 0.0, 0.0, 0.0);
            }
            fading = true;
        } else if (!walls.test(world.getBlockState(hit.getBlockPos())) || Weather.hasFluid(world.getBlockState(here))) {
            fading = true;
        }
        updateBox();
    }

    private void updateBox() {
        setBoundingBox(new AxisAlignedBB(posX - 0.6, posY - 0.1, posZ - 0.6, posX + 0.6, posY + 1.1, posZ + 0.6));
    }

    @Override
    public void renderParticle(BufferBuilder buffer, Entity entity, float pt, float rotX, float rotZ, float rotYZ,
                               float rotXY, float rotXZ) {
        if (particleTexture == null || particleAlpha <= 0.0f) return;
        float ox = (float) (prevPosX + (posX - prevPosX) * pt - interpPosX);
        float oy = (float) (prevPosY + (posY - prevPosY) * pt - interpPosY);
        float oz = (float) (prevPosZ + (posZ - prevPosZ) * pt - interpPosZ);
        float s = particleScale;
        int light = getBrightnessForRender(pt);
        // The wall plane: R along the wall, U up; R x U must point away from the camera.
        float fx = face.getXOffset(), fz = face.getZOffset();
        float rx = -fz, rz = fx;
        if (ox * fx + oz * fz > 0.0f) {
            rx = -rx;
            rz = -rz;
        }
        WeatherParticle.quad(buffer, ox, oy + s, oz, rx * s, 0.0f, rz * s, 0.0f, s, 0.0f,
                particleTexture.getMinU(), particleTexture.getMaxU(), particleTexture.getMinV(), particleTexture.getMaxV(),
                particleRed, particleGreen, particleBlue, MathHelper.clamp(particleAlpha, 0.0f, 1.0f),
                light >> 16 & 0xFFFF, light & 0xFFFF);
    }
}
