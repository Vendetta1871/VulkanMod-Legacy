package net.vesu.lod;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.lwjgl.util.zstd.Zstd.*;

/**
 * Section blob (AUDIT 5.5), little-endian: {@code u64 key; u64 meta} (bits 0..15 palette
 * size, 16..23 child mask); {@code u16[32768]} palette indices in {@code y<<10|z<<5|x}
 * order; {@code u64[paletteSize]} palette of full voxel words. Stored as one format byte
 * followed by the blob compressed with zstd level 1.
 */
public final class SectionCodec {
    public static final byte FORMAT = 1;
    private static final int RAW_MAX = 16 + WorldSection.SIZE * 2 + 65535 * 8;

    private static final class Scratch {
        final ByteBuffer raw = MemoryUtil.memAlloc(RAW_MAX).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer packed = MemoryUtil.memAlloc((int) ZSTD_compressBound(RAW_MAX) + 1);
        final long cctx = ZSTD_createCCtx(), dctx = ZSTD_createDCtx();
        final long[] keys = new long[1 << 17];
        final int[] vals = new int[1 << 17];
        final int[] stamp = new int[1 << 17];
        final long[] palette = new long[65536];
        int gen;
    }

    // Per-thread scratch: the worker threads live for the session, so the native buffers do too.
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private SectionCodec() {}

    public static byte[] serialize(long key, long[] data, int childMask) {
        Scratch s = SCRATCH.get();
        if (++s.gen == 0) {
            Arrays.fill(s.stamp, 0);
            s.gen = 1;
        }
        int gen = s.gen, mask = s.keys.length - 1;
        ByteBuffer raw = s.raw.clear();
        int idxBase = 16;
        int n = 0;
        long last = 0;
        int lastIdx = -1;
        for (int i = 0; i < WorldSection.SIZE; i++) {
            long v = data[i];
            int idx;
            if (v == last && lastIdx >= 0) {
                idx = lastIdx;
            } else {
                int h = (int) SectionKey.mix(v) & mask;
                while (true) {
                    if (s.stamp[h] != gen) {
                        if (n >= 65535) throw new IllegalStateException("palette overflow in " + SectionKey.toString(key));
                        s.stamp[h] = gen;
                        s.keys[h] = v;
                        s.vals[h] = n;
                        s.palette[n] = v;
                        idx = n++;
                        break;
                    }
                    if (s.keys[h] == v) {
                        idx = s.vals[h];
                        break;
                    }
                    h = (h + 1) & mask;
                }
                last = v;
                lastIdx = idx;
            }
            raw.putShort(idxBase + i * 2, (short) idx);
        }
        raw.putLong(0, key);
        raw.putLong(8, (long) n | (long) (childMask & 0xFF) << 16);
        int palBase = idxBase + WorldSection.SIZE * 2;
        for (int i = 0; i < n; i++) raw.putLong(palBase + i * 8, s.palette[i]);
        raw.limit(palBase + n * 8).position(0);

        ByteBuffer packed = s.packed.clear();
        packed.put(0, FORMAT);
        long size = ZSTD_compressCCtx(s.cctx, packed.position(1), raw, 1);
        if (ZSTD_isError(size)) throw new IllegalStateException("zstd: " + ZSTD_getErrorName(size));
        byte[] out = new byte[(int) size + 1];
        packed.position(0).get(out);
        return out;
    }

    /** Decoded header of a blob. */
    public record Header(long key, int childMask) {}

    /**
     * Expands a blob into {@code data}. Returns the header (key and child mask), or null when
     * the format is unknown.
     */
    public static Header deserialize(ByteBuffer blob, long[] data) {
        Scratch s = SCRATCH.get();
        ByteBuffer in = blob.duplicate();
        byte format = in.get(in.position());
        if (format != FORMAT) return null;
        ByteBuffer src = in.position(in.position() + 1).slice();
        if (!src.isDirect()) {
            ByteBuffer d = MemoryUtil.memAlloc(src.remaining());
            d.put(src).flip();
            try {
                return expand(s, d, data);
            } finally {
                MemoryUtil.memFree(d);
            }
        }
        return expand(s, src, data);
    }

    private static Header expand(Scratch s, ByteBuffer src, long[] data) {
        ByteBuffer raw = s.raw.clear();
        long size = ZSTD_decompressDCtx(s.dctx, raw, src);
        if (ZSTD_isError(size)) throw new IllegalStateException("zstd: " + ZSTD_getErrorName(size));
        long key = raw.getLong(0);
        long meta = raw.getLong(8);
        int n = (int) (meta & 0xFFFF);
        int palBase = 16 + WorldSection.SIZE * 2;
        for (int i = 0; i < n; i++) s.palette[i] = raw.getLong(palBase + i * 8);
        for (int i = 0; i < WorldSection.SIZE; i++) {
            data[i] = s.palette[raw.getShort(16 + i * 2) & 0xFFFF];
        }
        return new Header(key, (int) (meta >>> 16) & 0xFF);
    }
}
