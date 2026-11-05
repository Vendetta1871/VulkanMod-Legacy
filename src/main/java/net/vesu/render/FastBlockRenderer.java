package net.vesu.render;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;
import net.minecraftforge.client.model.pipeline.VertexLighterSmoothAo;
import net.vesu.Env;
import net.vesu.Initializer;

import java.util.List;

/**
 * Fast chunk-build path for {@code ForgeBlockModelRenderer.render} (setting {@code fastBlockRenderer}),
 * after Celeritas' block renderer. The Forge light pipeline unpacks every element of every vertex
 * into floats through a chain of {@code IVertexConsumer}s and packs it back, and its smooth lighting
 * looks up a 3x3x3 neighbourhood (block state, opacity, packed light, AO) for every block with a
 * visible face: 27 world reads per block, most of them repeated by the neighbours.
 *
 * <p>This path produces the same bytes. Quads in the standard {@code ITEM} format are written into
 * the {@code BLOCK} chunk buffer straight from their int data, with Forge's arithmetic in Forge's
 * order (light interpolation, AO, tint, face shade, rounding), and the neighbourhood comes from a
 * per-section cache of 18^3 cells, each read once per rebuild. Vesu's pack hooks of the lighters
 * (separate AO, plain shade) apply the same way. A block with any other quad format, a buffer in
 * another format, or a world that is not a chunk-build cache goes to Forge.
 * {@code VESU_FASTBLOCK_VERIFY=1} renders every block both ways and logs differences.</p>
 */
public final class FastBlockRenderer {

    private static final boolean VERIFY = Env.get("VESU_FASTBLOCK_VERIFY") != null;
    private static final EnumFacing[] SIDES = EnumFacing.values();
    private static final int CACHE = 18;

    /** Per worker thread: the neighbourhood cache and the scratch of one block. */
    private static final class Context {
        // Cache of the section being built (+1 block around): opacity < 15, sky, block, AO.
        final int[] stamp = new int[CACHE * CACHE * CACHE];
        final byte[] cellS = new byte[CACHE * CACHE * CACHE], cellB = new byte[CACHE * CACHE * CACHE];
        final boolean[] cellT = new boolean[CACHE * CACHE * CACHE];
        final float[] cellAo = new float[CACHE * CACHE * CACHE];
        int generation;
        IBlockAccess cacheWorld;
        int ox, oy, oz;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // BlockInfo of the block.
        final boolean[] t = new boolean[27];
        final int[] s = new int[27], b = new int[27];
        final float[] ao = new float[27];
        final float[] skyLight = new float[24], blockLight = new float[24];
        final int[] packed = new int[7];
        boolean full;
        float shx, shy, shz;
        int cachedTint, cachedMultiplier;

        final int[] out = new int[28];
        final float[] nx = new float[4], ny = new float[4], nz = new float[4];
        @SuppressWarnings("unchecked")
        final List<BakedQuad>[] lists = new List[7];

        // Verify mode.
        BufferBuilder scratchFast, scratchForge;
        long checked, mismatched;
    }

    private static final ThreadLocal<Context> CONTEXT = ThreadLocal.withInitial(Context::new);
    private static BlockColors colors;

    private FastBlockRenderer() {}

    private static boolean enabled() {
        return Initializer.CONFIG.fastBlockRenderer;
    }

    /**
     * Head of {@code ForgeBlockModelRenderer.render}: the result to return, or null to let Forge
     * render the block.
     */
    public static Boolean render(VertexLighterFlat lighter, IBlockAccess world, IBakedModel model, IBlockState state, BlockPos pos,
                                 BufferBuilder buffer, boolean checkSides, long rand) {
        if (!enabled() || !(world instanceof ChunkCache) || buffer.getVertexFormat() != DefaultVertexFormats.BLOCK) return null;
        Context c = CONTEXT.get();
        boolean smooth = lighter instanceof VertexLighterSmoothAo;
        if (!gather(c, world, model, state, pos, checkSides, rand)) return null;
        if (VERIFY) verify(c, lighter, world, model, state, pos, checkSides, rand, smooth);
        return emit(c, world, state, pos, buffer, smooth);
    }

    /** The quad lists Forge would pipe, in its call order; false when one is not in the ITEM format. */
    private static boolean gather(Context c, IBlockAccess world, IBakedModel model, IBlockState state, BlockPos pos,
                                  boolean checkSides, long rand) {
        List<BakedQuad> quads = model.getQuads(state, null, rand);
        if (!quads.isEmpty() && !standard(quads)) return false;
        c.lists[0] = quads.isEmpty() ? null : quads;
        for (int i = 0; i < 6; i++) {
            EnumFacing side = SIDES[i];
            quads = model.getQuads(state, side, rand);
            List<BakedQuad> kept = null;
            if (!quads.isEmpty() && (!checkSides || state.shouldSideBeRendered(world, pos, side))) {
                if (!standard(quads)) return false;
                kept = quads;
            }
            c.lists[i + 1] = kept;
        }
        return true;
    }

    private static boolean standard(List<BakedQuad> quads) {
        for (int i = 0, n = quads.size(); i < n; i++) {
            if (quads.get(i).getFormat() != DefaultVertexFormats.ITEM) return false;
        }
        return true;
    }

    private static boolean emit(Context c, IBlockAccess world, IBlockState state, BlockPos pos, BufferBuilder buffer, boolean smooth) {
        boolean empty = true;
        c.cachedTint = -1;
        for (List<BakedQuad> quads : c.lists) {
            if (quads == null) continue;
            if (empty) {
                updateBlockInfo(c, world, state, pos, smooth);
                empty = false;
            }
            for (int i = 0, n = quads.size(); i < n; i++) {
                quad(c, world, state, pos, buffer, quads.get(i), smooth);
            }
        }
        return !empty;
    }

    // ---- BlockInfo ----

    private static void updateBlockInfo(Context c, IBlockAccess world, IBlockState state, BlockPos pos, boolean smooth) {
        Vec3d offset = state.getOffset(world, pos);
        c.shx = (float) offset.x;
        c.shy = (float) offset.y;
        c.shz = (float) offset.z;
        if (smooth) updateLightMatrix(c, world, state, pos);
        else updateFlatLighting(c, world, state, pos);
    }

    private static void updateFlatLighting(Context c, IBlockAccess world, IBlockState state, BlockPos pos) {
        c.full = state.isFullCube();
        c.packed[0] = state.getPackedLightmapCoords(world, pos);
        for (EnumFacing side : SIDES) {
            // The block's own state at the neighbour's position, as Forge does.
            c.packed[side.ordinal() + 1] = state.getPackedLightmapCoords(world, pos.offset(side));
        }
    }

    private static int idx(int x, int y, int z) {
        return (x * 3 + y) * 3 + z;
    }

    private static void updateLightMatrix(Context c, IBlockAccess world, IBlockState state, BlockPos pos) {
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        int sx0 = (px & ~15) - 1, sy0 = (py & ~15) - 1, sz0 = (pz & ~15) - 1;
        if (world != c.cacheWorld || sx0 != c.ox || sy0 != c.oy || sz0 != c.oz) {
            c.cacheWorld = world;
            c.ox = sx0;
            c.oy = sy0;
            c.oz = sz0;
            if (++c.generation == 0) {
                java.util.Arrays.fill(c.stamp, 0);
                c.generation = 1;
            }
        }
        for (int x = 0; x <= 2; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = 0; z <= 2; z++) {
                    int wx = px + x - 1, wy = py + y - 1, wz = pz + z - 1;
                    int cx = wx - c.ox, cy = wy - c.oy, cz = wz - c.oz;
                    int i = idx(x, y, z);
                    if (cx >= 0 && cx < CACHE && cy >= 0 && cy < CACHE && cz >= 0 && cz < CACHE) {
                        int ci = (cx * CACHE + cy) * CACHE + cz;
                        if (c.stamp[ci] != c.generation) {
                            readCell(c, world, wx, wy, wz);
                            c.cellT[ci] = c.t[0];
                            c.cellS[ci] = (byte) c.s[0];
                            c.cellB[ci] = (byte) c.b[0];
                            c.cellAo[ci] = c.ao[0];
                            c.stamp[ci] = c.generation;
                        }
                        c.t[i] = c.cellT[ci];
                        c.s[i] = c.cellS[ci];
                        c.b[i] = c.cellB[ci];
                        c.ao[i] = c.cellAo[ci];
                    } else {
                        readCell(c, world, wx, wy, wz);
                        c.t[i] = c.t[0];
                        c.s[i] = c.s[0];
                        c.b[i] = c.b[0];
                        c.ao[i] = c.ao[0];
                    }
                }
            }
        }
        // readCell wrote slot 0 as scratch: restore it.
        {
            int wx = px - 1, wy = py - 1, wz = pz - 1;
            int cx = wx - c.ox, cy = wy - c.oy, cz = wz - c.oz;
            if (cx >= 0 && cx < CACHE && cy >= 0 && cy < CACHE && cz >= 0 && cz < CACHE) {
                int ci = (cx * CACHE + cy) * CACHE + cz;
                c.t[0] = c.cellT[ci];
                c.s[0] = c.cellS[ci];
                c.b[0] = c.cellB[ci];
                c.ao[0] = c.cellAo[ci];
            } else {
                readCell(c, world, wx, wy, wz);
            }
        }

        int center = idx(1, 1, 1);
        for (EnumFacing side : SIDES) {
            if (!state.doesSideBlockRendering(world, pos, side)) {
                int i = idx(side.getXOffset() + 1, side.getYOffset() + 1, side.getZOffset() + 1);
                c.s[i] = Math.max(c.s[center] - 1, c.s[i]);
                c.b[i] = Math.max(c.b[center] - 1, c.b[i]);
            }
        }

        int[] s = c.s, b = c.b;
        boolean[] t = c.t;
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    int x1 = x * 2, y1 = y * 2, z1 = z * 2;
                    int sxyz = s[idx(x1, y1, z1)], bxyz = b[idx(x1, y1, z1)];
                    boolean txyz = t[idx(x1, y1, z1)];
                    int sxz = s[idx(x1, 1, z1)], sxy = s[idx(x1, y1, 1)], syz = s[idx(1, y1, z1)];
                    int bxz = b[idx(x1, 1, z1)], bxy = b[idx(x1, y1, 1)], byz = b[idx(1, y1, z1)];
                    boolean txz = t[idx(x1, 1, z1)], txy = t[idx(x1, y1, 1)], tyz = t[idx(1, y1, z1)];
                    int sx = s[idx(x1, 1, 1)], sy = s[idx(1, y1, 1)], sz = s[idx(1, 1, z1)];
                    int bx = b[idx(x1, 1, 1)], by = b[idx(1, y1, 1)], bz = b[idx(1, 1, z1)];
                    boolean tx = t[idx(x1, 1, 1)], ty = t[idx(1, y1, 1)], tz = t[idx(1, 1, z1)];
                    int o = (x * 2 + y) * 2 + z;
                    c.skyLight[o] = combine(sx, sxz, sxy, txz || txy ? sxyz : sx, tx, txz, txy, txz || txy ? txyz : tx);
                    c.blockLight[o] = combine(bx, bxz, bxy, txz || txy ? bxyz : bx, tx, txz, txy, txz || txy ? txyz : tx);
                    c.skyLight[8 + o] = combine(sy, sxy, syz, txy || tyz ? sxyz : sy, ty, txy, tyz, txy || tyz ? txyz : ty);
                    c.blockLight[8 + o] = combine(by, bxy, byz, txy || tyz ? bxyz : by, ty, txy, tyz, txy || tyz ? txyz : ty);
                    c.skyLight[16 + o] = combine(sz, syz, sxz, tyz || txz ? sxyz : sz, tz, tyz, txz, tyz || txz ? txyz : tz);
                    c.blockLight[16 + o] = combine(bz, byz, bxz, tyz || txz ? bxyz : bz, tz, tyz, txz, tyz || txz ? txyz : tz);
                }
            }
        }
    }

    /** One neighbourhood cell into slot 0 of the block arrays. */
    private static void readCell(Context c, IBlockAccess world, int x, int y, int z) {
        BlockPos.MutableBlockPos p = c.cursor.setPos(x, y, z);
        IBlockState state = world.getBlockState(p);
        c.t[0] = state.getLightOpacity(world, p) < 15;
        int brightness = state.getPackedLightmapCoords(world, p);
        c.s[0] = (brightness >> 0x14) & 0xF;
        c.b[0] = (brightness >> 0x04) & 0xF;
        c.ao[0] = state.getAmbientOcclusionLightValue();
    }

    private static float combine(int c, int s1, int s2, int s3, boolean t0, boolean t1, boolean t2, boolean t3) {
        if (c == 0 && !t0) c = Math.max(0, Math.max(s1, s2) - 1);
        if (s1 == 0 && !t1) s1 = Math.max(0, c - 1);
        if (s2 == 0 && !t2) s2 = Math.max(0, c - 1);
        if (s3 == 0 && !t3) s3 = Math.max(0, Math.max(s1, s2) - 1);
        return (float) (c + s1 + s2 + s3) * 0x20 / (4 * 0xFFFF);
    }

    // ---- One quad ----

    private static void quad(Context c, IBlockAccess world, IBlockState state, BlockPos pos, BufferBuilder buffer, BakedQuad quad,
                             boolean smooth) {
        int[] d = quad.getVertexData();
        int tint = quad.hasTintIndex() ? quad.getTintIndex() : -1;
        int multiplier = -1;
        if (tint != -1) {
            if (c.cachedTint != tint) {
                if (colors == null) colors = Minecraft.getMinecraft().getBlockColors();
                c.cachedTint = tint;
                c.cachedMultiplier = colors.colorMultiplier(state, world, pos, tint);
            }
            multiplier = c.cachedMultiplier;
        }
        boolean diffuse = quad.shouldApplyDiffuseLighting();

        float[] nx = c.nx, ny = c.ny, nz = c.nz;
        float n0x = (float) (byte) d[6] / 127, n0y = (float) (byte) (d[6] >> 8) / 127, n0z = (float) (byte) (d[6] >> 16) / 127;
        if (n0x != -1 || n0y != -1 || n0z != -1) {
            for (int v = 0; v < 4; v++) {
                int n = d[v * 7 + 6];
                nx[v] = (float) (byte) n / 127;
                ny[v] = (float) (byte) (n >> 8) / 127;
                nz[v] = (float) (byte) (n >> 16) / 127;
            }
        } else {
            // Forge's generated normal: (p2 - p0) x (p3 - p1), normalized (vecmath Vector3f).
            float ax = f(d, 3, 0) - f(d, 1, 0), ay = f(d, 3, 1) - f(d, 1, 1), az = f(d, 3, 2) - f(d, 1, 2);
            float bx = f(d, 2, 0) - f(d, 0, 0), by = f(d, 2, 1) - f(d, 0, 1), bz = f(d, 2, 2) - f(d, 0, 2);
            float cx = by * az - bz * ay, cy = bz * ax - bx * az, cz = bx * ay - by * ax;
            float norm = (float) (1.0 / Math.sqrt(cx * cx + cy * cy + cz * cz));
            cx *= norm;
            cy *= norm;
            cz *= norm;
            for (int v = 0; v < 4; v++) {
                nx[v] = cx;
                ny[v] = cy;
                nz[v] = cz;
            }
        }

        boolean colorDisabled = buffer.isColorDisabled();
        boolean separateAo = ChunkBatcher.separateAo, plainShade = ChunkBatcher.plainShade;
        boolean anaglyph = net.minecraft.client.renderer.EntityRenderer.anaglyphEnable;
        int[] out = c.out;
        for (int v = 0; v < 4; v++) {
            int base = v * 7;
            float px = Float.intBitsToFloat(d[base]) + c.shx;
            float py = Float.intBitsToFloat(d[base + 1]) + c.shy;
            float pz = Float.intBitsToFloat(d[base + 2]) + c.shz;
            float x = px - .5f, y = py - .5f, z = pz - .5f;
            x += nx[v] * .5f;
            y += ny[v] * .5f;
            z += nz[v] * .5f;

            float bl, sl;
            if (smooth) {
                long light = calcLightmap(c, x, y, z);
                bl = Float.intBitsToFloat((int) light);
                sl = Float.intBitsToFloat((int) (light >>> 32));
            } else {
                int brightness = c.packed[flatSide(c, nx[v], ny[v], nz[v], x, y, z)];
                bl = ((float) ((brightness >> 0x04) & 0xF) * 0x20) / 0xFFFF;
                sl = ((float) ((brightness >> 0x14) & 0xF) * 0x20) / 0xFFFF;
            }

            int col = d[base + 3];
            float r = (float) (col & 0xFF) / 255, g = (float) (col >> 8 & 0xFF) / 255;
            float bb = (float) (col >> 16 & 0xFF) / 255, a = (float) (col >>> 24) / 255;
            if (tint != -1) {
                r *= (float) (multiplier >> 0x10 & 0xFF) / 0xFF;
                g *= (float) (multiplier >> 0x8 & 0xFF) / 0xFF;
                bb *= (float) (multiplier & 0xFF) / 0xFF;
            }
            if (smooth) {
                float ao = getAo(c, x, y, z);
                if (separateAo) {
                    a = ao;
                } else {
                    r *= ao;
                    g *= ao;
                    bb *= ao;
                }
            }
            if (diffuse) {
                float shade = plainShade ? 1.0f : Math.min(nx[v] * nx[v] * 0.6f + ny[v] * ny[v] * ((3f + ny[v]) / 4f) + nz[v] * nz[v] * 0.8f, 1f);
                r *= shade;
                g *= shade;
                bb *= shade;
            }
            if (anaglyph) {
                float r0 = r;
                r = (r0 * 30 + g * 59 + bb * 11) / 100;
                g = (r0 * 3 + g * 7) / 10;
                bb = (r0 * 3 + bb * 7) / 10;
            }
            int color = colorDisabled ? 0xFFFFFFFF
                    : (Math.round(r * 255) & 0xFF) | (Math.round(g * 255) & 0xFF) << 8
                    | (Math.round(bb * 255) & 0xFF) << 16 | (Math.round(a * 255) & 0xFF) << 24;

            out[base] = Float.floatToRawIntBits(px);
            out[base + 1] = Float.floatToRawIntBits(py);
            out[base + 2] = Float.floatToRawIntBits(pz);
            out[base + 3] = color;
            out[base + 4] = d[base + 4];
            out[base + 5] = d[base + 5];
            out[base + 6] = (Math.round(bl * 32767) & 0xFFFF) | (Math.round(sl * 32767) & 0xFFFF) << 16;
        }
        buffer.addVertexData(out);
        buffer.putPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    private static float f(int[] d, int v, int i) {
        return Float.intBitsToFloat(d[v * 7 + i]);
    }

    /** VertexLighterFlat.updateLightmap: the packed light slot. */
    private static int flatSide(Context c, float nx, float ny, float nz, float x, float y, float z) {
        final float e1 = 1f - 1e-2f;
        final float e2 = 0.95f;
        boolean full = c.full;
        if ((full || y < -e1) && ny < -e2) return EnumFacing.DOWN.ordinal() + 1;
        if ((full || y > e1) && ny > e2) return EnumFacing.UP.ordinal() + 1;
        if ((full || z < -e1) && nz < -e2) return EnumFacing.NORTH.ordinal() + 1;
        if ((full || z > e1) && nz > e2) return EnumFacing.SOUTH.ordinal() + 1;
        if ((full || x < -e1) && nx < -e2) return EnumFacing.WEST.ordinal() + 1;
        if ((full || x > e1) && nx > e2) return EnumFacing.EAST.ordinal() + 1;
        return 0;
    }

    /** VertexLighterSmoothAo.calcLightmap; block light in the low, sky light in the high 32 bits. */
    private static long calcLightmap(Context c, float x, float y, float z) {
        x *= 2;
        y *= 2;
        z *= 2;
        float l2 = x * x + y * y + z * z;
        if (l2 > 6 - 2e-2f) {
            float s = (float) Math.sqrt((6 - 2e-2f) / l2);
            x *= s;
            y *= s;
            z *= s;
        }
        float ax = x > 0 ? x : -x;
        float ay = y > 0 ? y : -y;
        float az = z > 0 ? z : -z;
        float e1 = 1 + 1e-4f;
        if (ax > 2 - 1e-4f && ay <= e1 && az <= e1) {
            x = x < 0 ? -2 + 1e-4f : 2 - 1e-4f;
        } else if (ay > 2 - 1e-4f && az <= e1 && ax <= e1) {
            y = y < 0 ? -2 + 1e-4f : 2 - 1e-4f;
        } else if (az > 2 - 1e-4f && ax <= e1 && ay <= e1) {
            z = z < 0 ? -2 + 1e-4f : 2 - 1e-4f;
        }
        ax = x > 0 ? x : -x;
        ay = y > 0 ? y : -y;
        az = z > 0 ? z : -z;
        if (ax <= e1 && ay + az > 3f - 1e-4f) {
            float s = (3f - 1e-4f) / (ay + az);
            y *= s;
            z *= s;
        } else if (ay <= e1 && az + ax > 3f - 1e-4f) {
            float s = (3f - 1e-4f) / (az + ax);
            z *= s;
            x *= s;
        } else if (az <= e1 && ax + ay > 3f - 1e-4f) {
            float s = (3f - 1e-4f) / (ax + ay);
            x *= s;
            y *= s;
        } else if (ax + ay + az > 4 - 1e-4f) {
            float s = (4 - 1e-4f) / (ax + ay + az);
            x *= s;
            y *= s;
            z *= s;
        }
        float[] blockLight = c.blockLight, skyLight = c.skyLight;
        float bl = 0f, sl = 0f, s = 0f;
        for (int ix = 0; ix <= 1; ix++) {
            for (int iy = 0; iy <= 1; iy++) {
                for (int iz = 0; iz <= 1; iz++) {
                    int o = (ix * 2 + iy) * 2 + iz;
                    float vx = x * (1 - ix * 2);
                    float vy = y * (1 - iy * 2);
                    float vz = z * (1 - iz * 2);
                    float s3 = vx + vy + vz + 4;
                    float sx = vy + vz + 3;
                    float sy = vz + vx + 3;
                    float sz = vx + vy + 3;
                    float bx = (2 * vx + vy + vz + 6) / (s3 * sy * sz * (vx + 2));
                    s += bx;
                    bl += bx * blockLight[o];
                    sl += bx * skyLight[o];
                    float by = (2 * vy + vz + vx + 6) / (s3 * sz * sx * (vy + 2));
                    s += by;
                    bl += by * blockLight[8 + o];
                    sl += by * skyLight[8 + o];
                    float bz = (2 * vz + vx + vy + 6) / (s3 * sx * sy * (vz + 2));
                    s += bz;
                    bl += bz * blockLight[16 + o];
                    sl += bz * skyLight[16 + o];
                }
            }
        }
        bl /= s;
        sl /= s;
        bl = MathHelper.clamp(bl, 0f, 15f * 0x20 / 0xFFFF);
        sl = MathHelper.clamp(sl, 0f, 15f * 0x20 / 0xFFFF);
        return (Float.floatToRawIntBits(bl) & 0xFFFFFFFFL) | (long) Float.floatToRawIntBits(sl) << 32;
    }

    /** VertexLighterSmoothAo.getAo. */
    private static float getAo(Context c, float x, float y, float z) {
        int sx = x < 0 ? 1 : 2;
        int sy = y < 0 ? 1 : 2;
        int sz = z < 0 ? 1 : 2;
        if (x < 0) x++;
        if (y < 0) y++;
        if (z < 0) z++;
        float[] ao = c.ao;
        float a = 0;
        a += ao[idx(sx - 1, sy - 1, sz - 1)] * (1 - x) * (1 - y) * (1 - z);
        a += ao[idx(sx - 1, sy - 1, sz)] * (1 - x) * (1 - y) * (0 + z);
        a += ao[idx(sx - 1, sy, sz - 1)] * (1 - x) * (0 + y) * (1 - z);
        a += ao[idx(sx - 1, sy, sz)] * (1 - x) * (0 + y) * (0 + z);
        a += ao[idx(sx, sy - 1, sz - 1)] * (0 + x) * (1 - y) * (1 - z);
        a += ao[idx(sx, sy - 1, sz)] * (0 + x) * (1 - y) * (0 + z);
        a += ao[idx(sx, sy, sz - 1)] * (0 + x) * (0 + y) * (1 - z);
        a += ao[idx(sx, sy, sz)] * (0 + x) * (0 + y) * (0 + z);
        return MathHelper.clamp(a, 0, 1);
    }

    // ---- Verify mode ----

    private static void verify(Context c, VertexLighterFlat lighter, IBlockAccess world, IBakedModel model, IBlockState state,
                               BlockPos pos, boolean checkSides, long rand, boolean smooth) {
        if (c.scratchFast == null) {
            c.scratchFast = new BufferBuilder(1 << 16);
            c.scratchForge = new BufferBuilder(1 << 16);
        }
        BufferBuilder fast = c.scratchFast, forge = c.scratchForge;
        fast.begin(7, DefaultVertexFormats.BLOCK);
        forge.begin(7, DefaultVertexFormats.BLOCK);
        emit(c, world, state, pos, fast, smooth);
        net.minecraft.client.renderer.BlockModelRenderer renderer = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelRenderer();
        VERIFYING.set(Boolean.TRUE);
        try {
            if (smooth) renderer.renderModelSmooth(world, model, state, pos, forge, checkSides, rand);
            else renderer.renderModelFlat(world, model, state, pos, forge, checkSides, rand);
        } finally {
            VERIFYING.set(Boolean.FALSE);
        }
        fast.finishDrawing();
        forge.finishDrawing();
        int bytes = fast.getVertexCount() * 28;
        boolean same = fast.getVertexCount() == forge.getVertexCount();
        java.nio.ByteBuffer fb = fast.getByteBuffer(), gb = forge.getByteBuffer();
        int firstDiff = -1;
        for (int i = 0; same && i < bytes; i++) {
            if (fb.get(i) != gb.get(i)) {
                same = false;
                firstDiff = i;
            }
        }
        c.checked++;
        if (!same) {
            c.mismatched++;
            if (c.mismatched <= 20) {
                Initializer.LOGGER.warn("[fastblock] mismatch {} at {} (smooth={}): {} vs {} vertices, first byte {}", state, pos, smooth,
                        fast.getVertexCount(), forge.getVertexCount(), firstDiff);
            }
        }
        if (c.checked % 200000 == 0) {
            Initializer.LOGGER.info("[fastblock] {}: {} blocks checked, {} differ", Thread.currentThread().getName(), c.checked, c.mismatched);
        }
    }

    /** True while verify mode runs the Forge path, which must not come back here. */
    private static final ThreadLocal<Boolean> VERIFYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean verifying() {
        return VERIFY && VERIFYING.get();
    }
}
