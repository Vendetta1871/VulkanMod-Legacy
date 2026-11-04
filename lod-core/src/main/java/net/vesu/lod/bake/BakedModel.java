package net.vesu.lod.bake;

import net.vesu.lod.mesh.ModelMeta;

import java.util.Arrays;

/**
 * Derives the mesher metadata and the GPU record of one baked model from its face images
 * (AUDIT 5.14).
 *
 * <p>GPU record (64 B): {@code uint faceData[6]; uint flags; uint tint; uint customId;
 * uint faceColor[6]; uint material}. faceColor: the face's average RGBA8, alpha its coverage
 * (the vertex color of pack programs, AUDIT 5.16); material: the DH material id. faceData: bits 0..15 pixel bounds as nibbles (minU, minV, maxU, maxV);
 * 16..21 indentation in 1/64 (63 = 1.0); 22 alpha discard; 23 discard override; 24..25 tint
 * (1 partial, 2 full). flags: bit 0 tinted, 1 biome-dependent tint, 2 translucent, 3 shaded.</p>
 */
public final class BakedModel {
    public final long[][] images;
    public final long meta;
    public final int[] record = new int[16];
    /** RGBA8 (little-endian ABGR ints) tiles, 16x16 per face, mips filled later by the atlas. */
    public final int[][] tiles = new int[6][FaceBaker.PIXELS];
    /** Dedup key: identical faces, fluid and tint share one model id. */
    public final String dedupKey;

    /**
     * @param translucentLayer the model is drawn in the translucent layer
     * @param forceSolid       leaves: opaque at distance, holes filled
     * @param fluid            a liquid (culls its own kind, self-lit top)
     * @param opaqueCube       the game's own full-opaque-cube flag
     * @param emission         light emission 0..15
     * @param tintKind         0 none, 1 constant tint, 2 biome-dependent tint
     * @param tint             the constant tint color (ARGB) or the biome table base
     * @param shaded           the model uses face shading (diffuse lighting)
     */
    public BakedModel(long[][] images, boolean translucentLayer, boolean forceSolid, boolean fluid, boolean opaqueCube,
                      int emission, int tintKind, int tint, boolean shaded, int customId) {
        this.images = images;
        int[] faces = new int[6];
        boolean anyTint = false, translucent = false, discard = false;
        int globals = 0;
        StringBuilder key = new StringBuilder(64);
        int coveringFaces = 0;
        for (int f = 0; f < 6; f++) {
            long[] img = images[f];
            int written = 0, tinted = 0;
            long depthSum = 0;
            int minU = 16, minV = 16, maxU = -1, maxV = -1;
            boolean faceTranslucent = false;
            int r = 0, g = 0, b = 0;
            for (int i = 0; i < FaceBaker.PIXELS; i++) {
                long p = img[i];
                if (p == FaceBaker.CLEAR) continue;
                int a = (int) (p >>> 24) & 0xFF;
                written++;
                depthSum += p >>> 40;
                if ((p >>> 39 & 1) != 0) tinted++;
                if (a < 255) faceTranslucent = translucentLayer;
                int u = i & 15, v = i >> 4;
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
                r += (int) p & 0xFF;
                g += (int) (p >>> 8) & 0xFF;
                b += (int) (p >>> 16) & 0xFF;
            }
            if (forceSolid && written > 0) {
                // Leaves: holes take the face's average color, darkened like the fast-graphics look.
                int ar = r / written * 3 / 4, ag = g / written * 3 / 4, ab = b / written * 3 / 4;
                long fill = 0xFF000000L | ab << 16 | ag << 8 | ar | 1L << 32 | (tinted > 0 ? 1L << 39 : 0);
                for (int i = 0; i < FaceBaker.PIXELS; i++) {
                    if (img[i] == FaceBaker.CLEAR) img[i] = fill;
                    else img[i] |= 0xFF000000L;
                }
                minU = minV = 0;
                maxU = maxV = 15;
                written = FaceBaker.PIXELS;
                if (tinted > 0) tinted = FaceBaker.PIXELS;
            }
            for (int i = 0; i < FaceBaker.PIXELS; i++) {
                long p = img[i];
                tiles[f][i] = p == FaceBaker.CLEAR ? 0 : (int) p;
            }
            if (written > 0) {
                int cover = Math.min(255, written * 255 / FaceBaker.PIXELS);
                int n = Math.max(1, Math.min(written, FaceBaker.PIXELS));
                record[9 + f] = cover << 24 | Math.min(255, b / n) << 16 | Math.min(255, g / n) << 8 | Math.min(255, r / n);
            }
            if (written == 0) {
                faces[f] = ModelMeta.NO_FACE;
                key.append('-');
                continue;
            }
            float depth = (float) depthSum / written / 0xFFFFFE;
            if (forceSolid) depth = 0;
            boolean covers = minU == 0 && minV == 0 && maxU == 15 && maxV == 15;
            boolean occludes = !faceTranslucent && !translucentLayer && depth < 0.1f && written > FaceBaker.PIXELS * 9 / 10;
            int fb = 0;
            if (occludes) fb |= ModelMeta.F_OCCLUDES;
            if (covers) {
                fb |= ModelMeta.F_COVERS;
                coveringFaces++;
            }
            if (depth < 0.3f) fb |= ModelMeta.F_OCCLUDABLE;
            if (depth > 0.01f || translucentLayer) fb |= ModelMeta.F_SELF_LIT;
            faces[f] = fb;
            anyTint |= tinted > 0;
            translucent |= faceTranslucent;
            boolean needsDiscard = written < FaceBaker.PIXELS * 9 / 10 || (!forceSolid && hasHoles(img));
            discard |= needsDiscard;
            int tintMode = tinted == 0 ? 0 : tinted == written ? 2 : 1;
            int indent = Math.min(Math.round(depth * 64), 62);
            record[f] = minU | minV << 4 | maxU << 8 | maxV << 12 | indent << 16 | (needsDiscard ? 1 << 22 : 0)
                    | (!covers && !faceTranslucent ? 1 << 23 : 0) | tintMode << 24;
            key.append(f).append(':').append(Arrays.hashCode(img)).append(',');
        }
        if (anyTint && tintKind != 0) globals |= tintKind == 2 ? ModelMeta.TINTED : 0;
        if (translucent || (translucentLayer && !forceSolid)) globals |= ModelMeta.TRANSLUCENT;
        // Cross models: faces that are not on the block's surface and not full: draw both sides.
        boolean allFacesDeep = true;
        for (int f = 0; f < 6; f++) if (faces[f] != ModelMeta.NO_FACE && (faces[f] & ModelMeta.F_SELF_LIT) == 0) allFacesDeep = false;
        if (allFacesDeep && !fluid && (globals & ModelMeta.TRANSLUCENT) == 0 && coveringFaces < 6) globals |= ModelMeta.DOUBLE_SIDED;
        if (fluid) globals |= ModelMeta.IS_FLUID | ModelMeta.HAS_FLUID | ModelMeta.CULLS_OWN;
        if (coveringFaces == 6) globals |= ModelMeta.CULLS_OWN;
        boolean allOccluding = true;
        for (int f = 0; f < 6; f++) if (faces[f] == ModelMeta.NO_FACE || (faces[f] & ModelMeta.F_OCCLUDES) == 0) allOccluding = false;
        if ((opaqueCube || forceSolid || allOccluding) && (globals & ModelMeta.TRANSLUCENT) == 0 && !fluid) {
            globals |= ModelMeta.OPAQUE;
            // A fully opaque model is lit from the side it faces: no face is self-lit.
            for (int f = 0; f < 6; f++) if (faces[f] != ModelMeta.NO_FACE) faces[f] = (faces[f] & ~ModelMeta.F_SELF_LIT) | ModelMeta.F_OCCLUDES;
        }
        this.meta = ModelMeta.of(faces, globals, emission);
        record[6] = (anyTint && tintKind != 0 ? 1 : 0) | (anyTint && tintKind == 2 ? 2 : 0)
                | ((globals & ModelMeta.TRANSLUCENT) != 0 ? 4 : 0) | (shaded ? 8 : 0);
        record[7] = anyTint && tintKind != 0 ? tint : -1;
        record[8] = customId;
        key.append('|').append(globals).append('|').append(tintKind).append('|').append(tint).append('|')
                .append(shaded).append('|').append(customId).append('|').append(emission);
        this.dedupKey = key.toString();
    }

    private static boolean hasHoles(long[] img) {
        for (long p : img) if (p != FaceBaker.CLEAR && ((p >>> 24) & 0xFF) == 0) return true;
        return false;
    }

    /** The record's tint (biome table base) once the model has a slot. */
    public void setTint(int tint) {
        record[7] = tint;
    }
}
