package net.vesu.vulkan;

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

    /**
     * Scene units: physical radiance (the units of NVIDIA's RTX Remix for Minecraft, whose
     * light rig this follows) times this scale, so a sunlit white surface lands near vanilla's
     * white and the raster passes (water, particles, hand) stay in range.
     */
    static final float SCENE_SCALE = 0.1f;
    private static final float SUN_LUMINANCE = 30.0f, MOON_LUMINANCE = 0.06f;
    private static final float RAIN_SUN = 0.075f, THUNDER_SUN = 0.03f;
    private static final float RAIN_MOON = 0.0225f, THUNDER_MOON = 0.0375f;
    /** Overcast sky radiance (physical) that replaces the clear sky in rain. */
    private static final float[] OVERCAST = {0.2393801f, 0.26890025f, 0.31064802f};

    /**
     * Irradiance of the direct sun (moon at night) at normal incidence, scene units: the source
     * luminance through the atmosphere along the air mass of its elevation (Rayleigh 0.145,
     * 0.34, 0.58 plus Mie 0.08 optical depth) - slightly warm at noon, orange at sunset - dimmed
     * by rain and thunder. Zero below the horizon.
     */
    static float[] directLight(float[] sun) {
        boolean day = sun[1] >= 0.0f;
        float h = Math.abs(sun[1]);
        float rain = rainStrength(), thunder = thunderStrength();
        float[] t = transmittance(h);
        float[] out = new float[3];
        if (day) {
            float weather = lerp(1.0f, lerp(RAIN_SUN, THUNDER_SUN, thunder) / SUN_LUMINANCE, rain);
            for (int i = 0; i < 3; i++) out[i] = SUN_LUMINANCE * t[i] * weather * SCENE_SCALE;
        } else {
            float weather = lerp(1.0f, lerp(RAIN_MOON, THUNDER_MOON, thunder) / MOON_LUMINANCE, rain);
            float[] tint = {0.85f, 0.9f, 1.0f};
            for (int i = 0; i < 3; i++) out[i] = MOON_LUMINANCE * tint[i] * t[i] * weather * SCENE_SCALE;
        }
        return out;
    }

    private static float[] transmittance(float h) {
        if (h <= 0.0f) return new float[3];
        double zenithDeg = Math.toDegrees(Math.acos(Math.min(1.0f, h)));
        double airMass = 1.0 / (h + 0.15 * Math.pow(93.885 - zenithDeg, -1.253));
        double[] depth = {0.145 + 0.08, 0.34 + 0.08, 0.58 + 0.08};
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) t[i] = (float) Math.exp(-depth[i] * airMass);
        return t;
    }

    /**
     * The sun's source radiance times the atmosphere's scattering scale (16 by day, 70 at night,
     * so the moonlit sky stays visible), scene units: the atmosphere.glsl source parameter.
     */
    static float skySource(float[] sun) {
        float night = 1.0f - smoothstep(-0.15f, 0.05f, sun[1]);
        float scale = 16.0f * (1.0f - night) + 70.0f * night;
        return SUN_LUMINANCE * scale * SCENE_SCALE * (1.0f - 0.85f * rainStrength());
    }

    /**
     * Mean single-scattered radiance of the upper sky (the multiple-scattering term of
     * atmosphere.glsl), scene units, from the same model on 32 directions; w: the rain blend.
     * In rain the overcast radiance replaces the clear sky.
     */
    static float[] skyMean(float[] sun) {
        float source = skySource(sun);
        float[] sum = new float[3];
        for (int i = 0; i < 32; i++) {
            double u = ((i % 8) * 32 + 0.5) / 256.0, v = ((i / 8) * 16 + 0.5) / 128.0;
            double theta = v * Math.PI, phi = u * 2.0 * Math.PI - Math.PI * 0.5;
            double[] d = {Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)};
            float[] s = Atmosphere.single(d, new double[]{sun[0], sun[1], sun[2]}, source);
            for (int c = 0; c < 3; c++) sum[c] += s[c] / 32.0f;
        }
        float rain = rainStrength();
        for (int c = 0; c < 3; c++) sum[c] = lerp(sum[c], OVERCAST[c] * SCENE_SCALE, rain);
        return new float[]{sum[0], sum[1], sum[2], rain};
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - e0) / (e1 - e0)));
        return t * t * (3.0f - 2.0f * t);
    }

    static float rainStrength() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.world == null ? 0.0f : mc.world.getRainStrength(mc.getRenderPartialTicks());
    }

    static float thunderStrength() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.world == null ? 0.0f : mc.world.getThunderStrength(mc.getRenderPartialTicks());
    }

    /** CPU twin of atmosphere.glsl (atmSingle): single scattering of the sun and the moon. */
    private static final class Atmosphere {
        static final double EARTH = 6371000.0, TOP = 6471000.0, HR = 8000.0, HM = 1200.0;
        static final double[] BR = {5.5e-6, 13.0e-6, 22.4e-6};
        static final double BM = 21.0e-6, G = 0.9, HALO = 10.35;
        static final double[] MOON = {0.85 * 0.002, 0.9 * 0.002, 0.002};

        static double sphere(double[] o, double[] d, double r) {
            double b = dot(o, d), c = dot(o, o) - r * r, disc = b * b - c;
            if (disc < 0.0) return -1.0;
            double s = Math.sqrt(disc), far = -b + s;
            if (far < 0.0) return -1.0;
            double near = -b - s;
            return near < 0.0 ? far : near;
        }

        static double[] lightT(double[] p, double[] l) {
            double r = len(p), mu = dot(p, l) / r;
            if (mu < 0.0 && r * r * (1.0 - mu * mu) < EARTH * EARTH) return new double[3];
            double length = sphere(p, l, TOP);
            if (length <= 0.0) return new double[]{1, 1, 1};
            double step = length / 6.0, dr = 0.0, dm = 0.0;
            for (int i = 0; i < 6; i++) {
                double t = (i + 0.5) * step;
                double h = Math.max(len(new double[]{p[0] + l[0] * t, p[1] + l[1] * t, p[2] + l[2] * t}) - EARTH, 0.0);
                dr += Math.exp(-h / HR);
                dm += Math.exp(-h / HM);
            }
            double[] out = new double[3];
            for (int c = 0; c < 3; c++) out[c] = Math.exp(-(BR[c] * dr + BM * dm) * step);
            return out;
        }

        static float[] single(double[] dir, double[] sunDir, float source) {
            double sl = len(sunDir);
            double[] s = {sunDir[0] / sl, sunDir[1] / sl, sunDir[2] / sl}, m = {-s[0], -s[1], -s[2]};
            double[] o = {0.0, EARTH + 1.0, 0.0};
            double length = sphere(o, dir, TOP), step = length / 12.0, odR = 0.0, odM = 0.0;
            double[] sr = new double[3], sm = new double[3], mr = new double[3], mm = new double[3];
            for (int i = 0; i < 12; i++) {
                double t = (i + 0.5) * step;
                double[] p = {o[0] + dir[0] * t, o[1] + dir[1] * t, o[2] + dir[2] * t};
                double h = Math.max(len(p) - EARTH, 0.0);
                double rr = Math.exp(-h / HR) * step, rm = Math.exp(-h / HM) * step;
                odR += rr;
                odM += rm;
                double[] ts = lightT(p, s), tm = lightT(p, m);
                for (int c = 0; c < 3; c++) {
                    double v = Math.exp(-(BR[c] * odR + BM * odM));
                    sr[c] += rr * ts[c] * v; sm[c] += rm * ts[c] * v;
                    mr[c] += rr * tm[c] * v; mm[c] += rm * tm[c] * v;
                }
            }
            double cs = dot(dir, s);
            float[] out = new float[3];
            for (int c = 0; c < 3; c++) {
                double sun = BR[c] * sr[c] * phaseR(cs) + BM * sm[c] * phaseM(cs) * HALO;
                double moon = (BR[c] * mr[c] * phaseR(-cs) + BM * mm[c] * phaseM(-cs)) * MOON[c];
                out[c] = (float) ((sun + moon) * source);
            }
            return out;
        }

        static double phaseR(double c) { return 3.0 / (16.0 * Math.PI) * (1.0 + c * c); }

        static double phaseM(double c) {
            double g2 = G * G;
            return 3.0 / (8.0 * Math.PI) * ((1.0 - g2) * (1.0 + c * c)) / ((2.0 + g2) * Math.pow(1.0 + g2 - 2.0 * G * c, 1.5));
        }

        static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

        static double len(double[] a) { return Math.sqrt(dot(a, a)); }
    }

    /**
     * Vanilla's sky light white of a fully lit surface (World.getSunBrightness): 0.2 at night up
     * to 1 at noon, dimmed by rain and thunder (each 5/16). Divides the physical white level when
     * vanilla-lit pixels convert to scene units.
     */
    static float vanillaWhite(float[] sun) {
        float day = sun[3] * (1.0f - rainStrength() * 5.0f / 16.0f) * (1.0f - thunderStrength() * 5.0f / 16.0f);
        return 0.2f + 0.8f * day;
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
