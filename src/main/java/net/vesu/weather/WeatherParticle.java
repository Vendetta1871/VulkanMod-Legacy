package net.vesu.weather;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A weather particle of a CUSTOM or BLOCK_MODEL definition. No vanilla collision: it falls under
 * its own gravity with air drag, is pushed by the wind, and looks ahead along its velocity each
 * tick; on a hit it bounces (if bouncy) or plays the impact animation (shrinks, or for velocity-
 * aligned streaks shortens into the surface) and dies. Drawn by orientation type: camera
 * billboard, upright sheet, flat sheet, or a streak stretched along its velocity.
 */
final class WeatherParticle extends Particle {

    private static final float DEG90 = (float) (Math.PI / 2.0);

    private final WeatherDef def;
    private final boolean surfaceSpawn;
    private final TextureAtlasSprite[] sprites;
    private int spriteIndex;
    private int genericCell;
    private float size, prevSize;
    private float roll, prevRoll;
    private final float spin;
    private final float baseTemperature;
    private BlockPos lastBlock;
    /** Impact animation progress: < 0 while flying, 1 → 0 while being absorbed. */
    private float progress = -1.0f, prevProgress = -1.0f;
    private int sideBounces;
    private float tintR = 1, tintG = 1, tintB = 1;
    private float distance;
    // Shrub (BLOCK_MODEL) model yaw, current and previous tick.
    private float yaw, prevYaw;

    WeatherParticle(World world, double x, double y, double z, WeatherDef def, boolean fromSky) {
        super(world, x, y, z);
        this.def = def;
        this.surfaceSpawn = def.spawnPos == WeatherDef.SpawnPos.WORLD_SURFACE;
        Weather.live++;
        this.canCollide = false;
        this.particleGravity = def.gravity;
        this.motionX = 0.0;
        this.motionY = fromSky ? -def.gravity : def.bounciness;
        this.motionZ = 0.0;
        float k = Weather.windMultiplier(world, def);
        long time = world.getTotalWorldTime();
        this.motionX += Weather.windX(x, y, time) * k * 10.0f;
        this.motionZ += Weather.windZ(z, y, time) * k * 10.0f;

        this.sprites = WeatherSprites.resolve(def);
        this.spriteIndex = sprites.length > 0 ? rand.nextInt(sprites.length) : 0;
        this.genericCell = rand.nextInt(3);
        if (sprites.length > 0) setParticleTexture(sprites[spriteIndex]);

        this.distance = cameraDistance();
        this.size = this.prevSize = scaledSize();
        this.particleAlpha = 0.0f;
        this.particleMaxAge = def.lifetime;
        this.spin = def.style == WeatherDef.Style.BLOCK_MODEL ? def.rotationAmount : def.rotationAmount * (2.0f * rand.nextFloat() - 1.0f);
        BlockPos pos = new BlockPos(x, y, z);
        this.baseTemperature = world.getBiome(pos).getDefaultTemperature();
        this.lastBlock = pos;
        float[] t = Weather.tint(world, def.tint, x, y, z, rand);
        tintR = t[0]; tintG = t[1]; tintB = t[2];
        if (def.rotation == WeatherDef.Rotation.HORIZONTAL) {
            roll = prevRoll = rand.nextInt(4) * DEG90;
        }
        yaw = prevYaw = yawOf();
        updateBox();
    }

    @Override
    public int getFXLayer() {
        return def.genericSprites ? 0 : 1;
    }

    @Override
    public boolean shouldDisableDepth() {
        return def.renderType == WeatherDef.RenderType.BLENDED;
    }

    @Override
    public void setExpired() {
        if (!isExpired) Weather.live--;
        super.setExpired();
    }

    private double camX, camY, camZ;

    private float cameraDistance() {
        Entity cam = Minecraft.getMinecraft().getRenderViewEntity();
        if (cam == null) return 0.0f;
        camX = cam.posX;
        camY = cam.posY + cam.getEyeHeight();
        camZ = cam.posZ;
        double dx = posX - camX, dy = posY - camY, dz = posZ - camZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private float scaledSize() {
        if (def.distanceScaling == 0.0f) return def.size;
        float s = def.size * (1.0f + distance * def.distanceScaling);
        if (def.distanceScaling == 1.0f) {
            s *= Math.min(Minecraft.getMinecraft().gameSettings.fovSetting, 70.0f) / 70.0f;
        }
        return s;
    }

    private float yawOf() {
        return (float) Math.atan2(-motionX, motionZ);
    }

    private void startImpact() {
        if (progress < 0.0f) progress = prevProgress = 1.0f;
    }

    @Override
    public void onUpdate() {
        prevPosX = posX;
        prevPosY = posY;
        prevPosZ = posZ;
        prevSize = size;
        prevRoll = roll;
        prevProgress = progress;
        prevYaw = yaw;
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

        distance = cameraDistance();
        float range = surfaceSpawn ? Weather.SURFACE_RANGE : Weather.PARTICLE_DISTANCE;
        if (distance > range + 0.5f) {
            setExpired();
            return;
        }

        BlockPos here = new BlockPos(posX, posY, posZ);
        if (!here.equals(lastBlock)) {
            lastBlock = here;
            float temperature = world.getBiome(here).getDefaultTemperature();
            if (temperature != baseTemperature && !def.precipitation.contains(Weather.precipitation(world, here))) {
                startImpact();
            } else {
                IBlockState s = world.getBlockState(here);
                if ((s.isFullCube() && !WeatherDef.weatherIgnored(s)) || Weather.hasFluid(s)) {
                    setExpired();
                    return;
                }
            }
        }

        float speed = (float) Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);

        float rain = world.getRainStrength(1.0f), thunder = world.getThunderStrength(1.0f);
        if (def.style != WeatherDef.Style.BLOCK_MODEL) {
            particleAlpha = alpha(range) * def.weather.level(rain, thunder);
        } else {
            particleAlpha = 1.0f;
        }

        if (def.distanceScaling != 0.0f && progress < 0.0f) size = scaledSize();
        if (def.rotationAmount != 0.0f) roll += spin * speed;
        if (def.animateSprites && sprites.length > 1) {
            spriteIndex = Math.floorMod(particleAge - 1, sprites.length - 1);
            setParticleTexture(sprites[spriteIndex]);
        }
        if (def.tint == WeatherDef.Tint.FOG && rain > 0.0f && rain < 1.0f) {
            float[] t = Weather.tint(world, def.tint, posX, posY, posZ, rand);
            tintR = t[0]; tintG = t[1]; tintB = t[2];
        }

        float k = Weather.windMultiplier(world, def);
        if (k != 0.0f) {
            long time = world.getTotalWorldTime();
            motionX += Weather.windX(posX, posY, time) * k;
            motionZ += Weather.windZ(posZ, posY, time) * k;
        }

        if (progress < 0.0f && speed > 1e-6f) collide(speed);
        if (progress >= 0.0f) {
            progress -= speed;
            if (def.rotation != WeatherDef.Rotation.RELATIVE_VELOCITY) size = Math.max(size - speed, 0.0f);
            if (progress <= 0.0f) {
                setExpired();
                return;
            }
        }
        if (def.style == WeatherDef.Style.BLOCK_MODEL && (motionX != 0.0 || motionZ != 0.0)) yaw = yawOf();
        updateBox();
    }

    private float alpha(float range) {
        float a;
        int age = particleAge, life = particleMaxAge;
        switch (def.fade) {
            case IN_AND_OUT: {
                int h = Math.max(1, life / 2);
                a = def.opacity * (age < h ? age / (float) h : (life - age) / (float) h);
                break;
            }
            case OUT:
                a = def.opacity * (life - age) / (float) Math.max(1, life / 2);
                break;
            default:
                a = distance <= range * 2.0f / 3.0f ? def.opacity
                        : def.opacity * Math.max(0.0f, (range - distance) / (range / 3.0f));
        }
        if (def.rotation == WeatherDef.Rotation.VERTICAL || def.rotation == WeatherDef.Rotation.HORIZONTAL) {
            double dx = posX - camX, dy = posY - camY, dz = posZ - camZ;
            float theta = (float) Math.abs(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            if (def.rotation == WeatherDef.Rotation.VERTICAL) {
                a = Math.min(a, 1.0f - Math.min(theta, (float) Math.toRadians(45)) / (float) Math.toRadians(45));
            } else {
                a = Math.min(a, Math.min(theta, 0.4f) / 0.4f);
            }
        }
        return a;
    }

    /** Looks ahead along the velocity: bounce or begin the impact animation. */
    private void collide(float speed) {
        double len = size + speed;
        Vec3d from = new Vec3d(posX, posY, posZ);
        Vec3d to = from.add(motionX / speed * len, motionY / speed * len, motionZ / speed * len);
        RayTraceResult hit = world.rayTraceBlocks(from, to, true, true, false);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return;
        IBlockState s = world.getBlockState(hit.getBlockPos());
        if (WeatherDef.weatherIgnored(s)) return;
        if (def.bounciness != 0.0f && !Weather.fluidSource(s)) {
            float b = def.bounciness * speed * 6.0f;
            EnumFacing face = hit.sideHit;
            if (face.getAxis() != EnumFacing.Axis.Y) {
                if (++sideBounces > 3) {
                    startImpact();
                    return;
                }
                motionY += 0.5f * b;
            }
            motionX += face.getXOffset() * b;
            motionY += face.getYOffset() * b;
            motionZ += face.getZOffset() * b;
        } else {
            startImpact();
        }
    }

    private void updateBox() {
        double e = Math.max(size, 0.1) * (def.style == WeatherDef.Style.BLOCK_MODEL ? 1.5 : 1.2);
        setBoundingBox(new AxisAlignedBB(posX - e, posY - e, posZ - e, posX + e, posY + e, posZ + e));
    }

    // --- drawing ------------------------------------------------------------------------------

    @Override
    public void renderParticle(BufferBuilder buffer, Entity entity, float pt, float rotX, float rotZ, float rotYZ,
                               float rotXY, float rotXZ) {
        float ox = (float) (prevPosX + (posX - prevPosX) * pt - interpPosX);
        float oy = (float) (prevPosY + (posY - prevPosY) * pt - interpPosY);
        float oz = (float) (prevPosZ + (posZ - prevPosZ) * pt - interpPosZ);
        float s = prevSize + (size - prevSize) * pt;
        float r = prevRoll + (roll - prevRoll) * pt;
        int light = getBrightnessForRender(pt);
        int lj = light >> 16 & 0xFFFF, lk = light & 0xFFFF;

        if (def.style == WeatherDef.Style.BLOCK_MODEL) {
            float dYaw = yaw - prevYaw;
            if (dYaw > Math.PI) dYaw -= 2.0f * (float) Math.PI;
            else if (dYaw < -Math.PI) dYaw += 2.0f * (float) Math.PI;
            renderShrub(buffer, ox, oy, oz, s, r, prevYaw + dYaw * pt, lj, lk);
            return;
        }
        float a = MathHelper.clamp(particleAlpha, 0.0f, 1.0f);
        if (a <= 0.0f || s <= 0.0f) return;

        float u0, u1, v0, v1;
        if (def.genericSprites) {
            u0 = genericCell / 16.0f; u1 = u0 + 0.0624375f; v0 = 0.0f; v1 = 0.0624375f;
        } else if (particleTexture != null) {
            u0 = particleTexture.getMinU(); u1 = particleTexture.getMaxU();
            v0 = particleTexture.getMinV(); v1 = particleTexture.getMaxV();
        } else {
            return;
        }

        // R ("left") and U axes with R x U pointing away from the camera, as vanilla's billboard.
        float rx, ry, rz, ux, uy, uz;
        float hr = s, hu = s;
        switch (def.rotation) {
            case VERTICAL: {
                float l = (float) Math.sqrt(ox * ox + oz * oz);
                float nx = l > 1e-5f ? ox / l : 0.0f, nz = l > 1e-5f ? oz / l : 1.0f;
                rx = nz; ry = 0.0f; rz = -nx;
                ux = 0.0f; uy = 1.0f; uz = 0.0f;
                break;
            }
            case HORIZONTAL: {
                rx = MathHelper.cos(r); ry = 0.0f; rz = MathHelper.sin(r);
                float sign = oy < 0.0f ? -1.0f : 1.0f;
                ux = MathHelper.sin(r) * sign; uy = 0.0f; uz = -MathHelper.cos(r) * sign;
                r = 0.0f;
                break;
            }
            case RELATIVE_VELOCITY:
            case WORLD_VELOCITY: {
                double vx = posX - prevPosX, vy = posY - prevPosY, vz = posZ - prevPosZ;
                if (def.rotation == WeatherDef.Rotation.RELATIVE_VELOCITY) {
                    vx -= entity.posX - entity.lastTickPosX;
                    vy -= entity.posY - entity.lastTickPosY;
                    vz -= entity.posZ - entity.lastTickPosZ;
                }
                double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (vl < 1e-4) { vx = 0; vy = -1; vz = 0; vl = 1; }
                ux = (float) (vx / vl); uy = (float) (vy / vl); uz = (float) (vz / vl);
                // R = U x o, normalized: R x U then lies along o (away from the camera).
                float cx = uy * oz - uz * oy, cy = uz * ox - ux * oz, cz = ux * oy - uy * ox;
                float cl = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (cl < 1e-6f) return;
                rx = cx / cl; ry = cy / cl; rz = cz / cl;
                float stretch = MathHelper.clamp((float) (vl * vl), 0.25f, 1.0f);
                if (progress >= 0.0f) stretch = Math.min(stretch, prevProgress + (progress - prevProgress) * pt);
                hu = s * stretch;
                r = 0.0f;
                break;
            }
            default:
                rx = rotX; ry = 0.0f; rz = rotYZ;
                ux = rotXY; uy = rotZ; uz = rotXZ;
        }
        if (r != 0.0f) {
            float c = MathHelper.cos(r), sn = MathHelper.sin(r);
            float nrx = rx * c + ux * sn, nry = ry * c + uy * sn, nrz = rz * c + uz * sn;
            float nux = ux * c - rx * sn, nuy = uy * c - ry * sn, nuz = uz * c - rz * sn;
            rx = nrx; ry = nry; rz = nrz; ux = nux; uy = nuy; uz = nuz;
        }
        rx *= hr; ry *= hr; rz *= hr;
        ux *= hu; uy *= hu; uz *= hu;
        quad(buffer, ox, oy, oz, rx, ry, rz, ux, uy, uz, u0, u1, v0, v1, tintR, tintG, tintB, a, lj, lk);
    }

    /** Corners (-R-U)(u1,v1), (-R+U)(u1,v0), (+R+U)(u0,v0), (+R-U)(u0,v1): vanilla's billboard layout. */
    static void quad(BufferBuilder b, float x, float y, float z, float rx, float ry, float rz, float ux, float uy, float uz,
                     float u0, float u1, float v0, float v1, float cr, float cg, float cb, float ca, int lj, int lk) {
        b.pos(x - rx - ux, y - ry - uy, z - rz - uz).tex(u1, v1).color(cr, cg, cb, ca).lightmap(lj, lk).endVertex();
        b.pos(x - rx + ux, y - ry + uy, z - rz + uz).tex(u1, v0).color(cr, cg, cb, ca).lightmap(lj, lk).endVertex();
        b.pos(x + rx + ux, y + ry + uy, z + rz + uz).tex(u0, v0).color(cr, cg, cb, ca).lightmap(lj, lk).endVertex();
        b.pos(x + rx - ux, y + ry - uy, z + rz - uz).tex(u0, v1).color(cr, cg, cb, ca).lightmap(lj, lk).endVertex();
    }

    // Dead-bush cross model, two diagonal planes over the unit block.
    private static final float[][] CROSS = {
            {0, 0, 0}, {0, 1, 0}, {1, 1, 1}, {1, 0, 1},
            {0, 0, 1}, {0, 1, 1}, {1, 1, 0}, {1, 0, 0}};

    /**
     * Tumbleweed: the bush model rotated by {@code roll} about its local X axis, scaled, offset on
     * the off-center rolling pivot, turned to face its motion, at the particle position.
     */
    private void renderShrub(BufferBuilder b, float ox, float oy, float oz, float s, float roll, float yawRad, int lj, int lk) {
        TextureAtlasSprite sprite = WeatherSprites.deadBush();
        if (sprite == null) return;
        float cr = MathHelper.cos(roll), sr = MathHelper.sin(roll);
        float cy = MathHelper.cos(yawRad), sy = MathHelper.sin(yawRad);
        float ty = -0.7f * MathHelper.cos(roll + 0.8f), tz = -0.7f * MathHelper.cos(roll + 5.5f);
        float[] p = new float[24];
        for (int i = 0; i < 8; i++) {
            float x = CROSS[i][0], y = CROSS[i][1], z = CROSS[i][2];
            float y1 = y * cr - z * sr, z1 = y * sr + z * cr;
            x *= s; y1 *= s; z1 *= s;
            y1 += ty; z1 += tz;
            // Yaw about +Y.
            float x2 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;
            p[i * 3] = ox + x2; p[i * 3 + 1] = oy + y1; p[i * 3 + 2] = oz + z2;
        }
        float u0 = sprite.getMinU(), u1 = sprite.getMaxU(), v0 = sprite.getMinV(), v1 = sprite.getMaxV();
        float[] us = {u0, u0, u1, u1}, vs = {v1, v0, v0, v1};
        for (int q = 0; q < 2; q++) {
            for (int side = 0; side < 2; side++) {
                for (int k = 0; k < 4; k++) {
                    int c = side == 0 ? k : 3 - k;
                    int i = q * 4 + c;
                    b.pos(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]).tex(us[c], vs[c]).color(1.0f, 1.0f, 1.0f, 1.0f)
                            .lightmap(lj, lk).endVertex();
                }
            }
        }
    }
}
