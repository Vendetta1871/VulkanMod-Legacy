package net.vesu.pack.bedrock;

import java.util.Map;

/** The deferred PBR pipeline behind Bedrock packs. Materials are in the neutral layout (D11). */
final class BedrockPrograms {

    static final String PACK_JSON = """
            {
              "format": 1, "id": "vv", "version": "1",
              "requires": { "vertex": ["normal", "tangent", "blockId"] },
              "blockIds": { "1": "minecraft:water minecraft:flowing_water" },
              "renderer": { "sceneTarget": "lit", "handDepth": 0.125, "unmappedBlockId": 0, "fullScreenOverwrite": true },
              "textures": [
                { "name": "albedo", "format": "RGBA8", "clear": [0, 0, 0, 0] },
                { "name": "gnormal", "format": "RGBA16F", "clear": [0, 0, 0, 0] },
                { "name": "material", "format": "RGBA8", "clear": [0, 0, 0, 0] },
                { "name": "lit", "format": "RGBA16F", "clear": [0, 0, 0, 1] },
                { "name": "luminance", "format": "R32F", "size": [1, 1], "history": true, "clear": false, "filter": "nearest" },
                { "name": "params", "format": "RGBA32F", "size": [36, 1], "history": true, "clear": false, "filter": "nearest" },
                { "name": "volume", "format": "RGBA16F", "scale": 0.5, "clear": false },
                { "name": "reflections", "format": "RGBA16F", "scale": 0.5, "clear": false, "history": true },
                { "name": "bloom1", "format": "RGBA16F", "scale": 0.5, "clear": false },
                { "name": "bloom2", "format": "RGBA16F", "scale": 0.25, "clear": false },
                { "name": "bloom3", "format": "RGBA16F", "scale": 0.125, "clear": false },
                { "name": "bloom4", "format": "RGBA16F", "scale": 0.0625, "clear": false },
                { "name": "bloomUp3", "format": "RGBA16F", "scale": 0.125, "clear": false },
                { "name": "bloomUp2", "format": "RGBA16F", "scale": 0.25, "clear": false },
                { "name": "bloomUp1", "format": "RGBA16F", "scale": 0.5, "clear": false }
              ],
              "shadow": { "resolution": 2048, "distance": 96, "intervalSize": 2, "sunPathRotation": 0, "hardwareFiltering": false },
              "gbuffers": {
                "terrain": { "vertex": "@DIR@geo.vsh", "fragment": "@DIR@geo.fsh", "targets": ["albedo", "gnormal", "material"], "inputs": ["normals", "specular"] },
                "entities": { "vertex": "@DIR@geo.vsh", "fragment": "@DIR@actor.fsh", "targets": ["albedo", "gnormal", "material"], "inputs": ["normals", "specular", "actorNormals", "actorSpecular"] },
                "block": { "vertex": "@DIR@geo.vsh", "fragment": "@DIR@geo.fsh", "targets": ["albedo", "gnormal", "material"], "inputs": ["normals", "specular"] },
                "hand": { "vertex": "@DIR@geo.vsh", "fragment": "@DIR@actor.fsh", "targets": ["albedo", "gnormal", "material"], "inputs": ["normals", "specular", "actorNormals", "actorSpecular"] },
                "water": { "vertex": "@DIR@water.vsh", "fragment": "@DIR@water.fsh", "targets": ["lit"], "inputs": ["depthtex1", "shadowtex0", "gnormalCopy", "params", "lit"], "blend": "default" },
                "clouds": { "vertex": "@DIR@clouds.vsh", "fragment": "@DIR@clouds.fsh", "targets": ["lit"], "inputs": ["params"], "blend": "default" },
                "weather": { "vertex": "@DIR@clouds.vsh", "fragment": "@DIR@weather.fsh", "targets": ["lit"], "inputs": ["params"], "blend": "default" },
                "skybasic": { "vertex": "@DIR@sky.vsh", "fragment": "@DIR@sky.fsh", "targets": ["lit"], "inputs": ["params"] },
                "skytextured": { "vertex": "@DIR@disc.vsh", "fragment": "@DIR@disc.fsh", "targets": ["lit"], "inputs": ["params"], "blend": @ADDITIVE@ },
                "shadow": { "vertex": "@DIR@shadow.vsh", "fragment": "@DIR@shadow.fsh", "targets": [] }
              },
              "commandLists": [
                { "stage": "BEGIN", "passes": [ { "name": "params", "fragment": "@DIR@params.fsh", "inputs": ["paramsPrev"], "targets": ["params"] } ] },
                { "stage": "DEFERRED", "passes": [ { "name": "lighting", "fragment": "@DIR@lighting.fsh",
                  "inputs": ["albedo", "gnormal", "material", "lit", "sceneDepth", "shadowtex0", "params"], "targets": ["lit"] },
                  { "name": "reflections", "fragment": "@DIR@reflections.fsh",
                  "inputs": ["albedo", "gnormal", "material", "lit", "sceneDepth", "motion", "reflectionsPrev", "params"], "targets": ["reflections"] },
                  { "name": "reflectionApply", "fragment": "@DIR@reflectionApply.fsh",
                  "inputs": ["lit", "reflections"], "targets": ["lit"] } ] },
                { "stage": "POST_SCENE", "passes": [ { "name": "histogram", "compute": "@DIR@histogram.csh", "inputs": ["lit"], "workGroups": { "local": [16, 16, 1] } },
                  { "name": "exposure", "fragment": "@DIR@exposure.fsh",
                  "inputs": ["luminancePrev"], "targets": ["luminance"] },
                  { "name": "volume", "fragment": "@DIR@volume.fsh",
                  "inputs": ["depthtex0", "shadowtex0", "params"], "targets": ["volume"] },
                  { "name": "volumeApply", "fragment": "@DIR@volumeApply.fsh", "inputs": ["lit", "volume"], "targets": ["lit"] },
                  { "name": "bloom1", "fragment": "@DIR@bloom1.fsh", "inputs": ["lit", "luminance"], "targets": ["bloom1"] },
                  { "name": "bloom2", "fragment": "@DIR@bloom2.fsh", "inputs": ["bloom1"], "targets": ["bloom2"] },
                  { "name": "bloom3", "fragment": "@DIR@bloom3.fsh", "inputs": ["bloom2"], "targets": ["bloom3"] },
                  { "name": "bloom4", "fragment": "@DIR@bloom4.fsh", "inputs": ["bloom3"], "targets": ["bloom4"] },
                  { "name": "bloomUp3", "fragment": "@DIR@bloomUp3.fsh", "inputs": ["bloom3", "bloom4"], "targets": ["bloomUp3"] },
                  { "name": "bloomUp2", "fragment": "@DIR@bloomUp2.fsh", "inputs": ["bloom2", "bloomUp3"], "targets": ["bloomUp2"] },
                  { "name": "bloomUp1", "fragment": "@DIR@bloomUp1.fsh", "inputs": ["bloom1", "bloomUp2"], "targets": ["bloomUp1"] } ] }
              ],
              "combination": { "fragment": "@DIR@grade.fsh", "inputs": ["lit", "luminance", "bloomUp1", "params"] }
            }
            """.replace("@ADDITIVE@", "[\"ONE\", \"ONE\", \"ZERO\", \"ONE\"]");

    /**
     * The sky and fog of Vibrant Visuals: a Rayleigh term of the pack's sky colors and a
     * Henyey-Greenstein Mie term around the sun, in scene units where the sun's illuminance is
     * the pack's number. Rain replaces the sky color by a gray and removes direct light.
     */
    private static final String LIGHT = """
            const float VV_PHASE = 0.0795775;
            #define VV_ROW(i) texelFetch(params, ivec2(i, 0), 0)
            vec3 vv_sunColor() { return VV_ROW(0).rgb; }
            float vv_sunIlluminance() { return VV_ROW(0).a; }
            vec3 vv_moonColor() { return VV_ROW(1).rgb; }
            float vv_moonIlluminance() { return VV_ROW(1).a; }
            vec3 vv_ambientColor() { return VV_ROW(2).rgb; }
            float vv_ambientIlluminance() { return VV_ROW(2).a; }
            vec3 vv_skyZenith() { return VV_ROW(3).rgb; }
            float vv_rayleigh() { return VV_ROW(3).a; }
            vec3 vv_skyHorizon() { return VV_ROW(4).rgb; }
            float vv_sunMie() { return VV_ROW(4).a; }
            float vv_horizonMin() { return VV_ROW(5).x; }
            float vv_horizonStart() { return VV_ROW(5).y; }
            float vv_horizonMie() { return VV_ROW(5).z; }
            float vv_horizonMax() { return VV_ROW(5).w; }
            #define VV_FOG_AIR_START VV_ROW(6).x
            #define VV_FOG_AIR_END VV_ROW(6).y
            #define VV_FOG_WEATHER_START VV_ROW(6).z
            #define VV_FOG_WEATHER_END VV_ROW(6).w
            #define VV_WATER_COLOR VV_ROW(7).rgb
            #define VV_WATER_ALPHA VV_ROW(7).a
            vec3 vv_sunVector() { return normalize(mat3(gbufferModelViewInverse) * sunPosition); }
            float vv_moonMie() { return VV_ROW(20).x; }
            float vv_sunGlareShape() { return VV_ROW(20).y; }
            vec3 vv_skyClear(vec3 dir) {
                vec3 sun = vv_sunVector();
                float low = vv_horizonMin() - vv_horizonMax();
                float mixUp = 1.0 - smoothstep(low, vv_horizonStart(), dir.y);
                float mieUp = 1.0 - smoothstep(low, vv_horizonMie() - vv_horizonMax(), dir.y);
                float sunDot = dot(dir, sun), moonDot = dot(dir, -sun);
                float sunI = vv_sunIlluminance(), moonI = vv_moonIlluminance();
                vec3 horizon = vv_skyHorizon();
                vec3 rayleigh = mix(vv_skyZenith(), horizon, mixUp * mixUp * mixUp) * vv_rayleigh() * VV_PHASE
                        * 0.75 * (sunI * (sunDot * sunDot + 1.0) + moonI * (moonDot * moonDot + 1.0));
                float shape = max(vv_sunGlareShape(), 0.001);
                float sunGlow = clamp(pow(max(sunDot, 0.0), shape), 0.0, 1.0);
                float moonGlow = clamp(pow(max(moonDot, 0.0), shape), 0.0, 1.0);
                float sunK = 1.81 - 1.8 * sunGlow, moonK = 1.81 - 1.8 * moonGlow;
                vec3 mie = horizon * mieUp * mieUp * mieUp * VV_PHASE * 0.0361
                        * (vv_sunColor() * sunI * vv_sunMie() * sunGlow / (sunK * sqrt(sunK))
                        + vv_moonColor() * moonI * vv_moonMie() * moonGlow / (moonK * sqrt(moonK)));
                return rayleigh + mie;
            }
            float vv_lightScale() { return vv_sunIlluminance() + vv_moonIlluminance(); }
            vec3 vv_rainSky() { return VV_RAIN_SKY * vv_rayleigh() * VV_PHASE * 0.75 * vv_lightScale(); }
            vec3 vv_sky(vec3 dir) { return mix(vv_skyClear(dir), vv_rainSky(), rainStrength); }
            vec3 vv_ambient() {
                vec3 zenith = vv_skyZenith();
                vec3 clear = mix(zenith, vec3(dot(zenith, vec3(0.2126, 0.7152, 0.0722))), 0.4) * vv_rayleigh() * VV_PHASE * 0.75 * vv_lightScale();
                return mix(clear, vv_rainSky(), rainStrength) * VV_AMBIENT_GAIN * VV_ROW(14).x;
            }
            float vv_fogLayer(float dist, float start, float end) {
                float scale = end < 0.0 ? 1.0 : far;
                return clamp((dist / scale - start) / max(abs(end) - start, 1e-4), 0.0, 1.0);
            }
            float vv_fog(float dist) {
                if (isEyeInWater == 1) return vv_fogLayer(dist, VV_ROW(21).y, VV_ROW(21).z);
                if (isEyeInWater == 2) return vv_fogLayer(dist, VV_ROW(21).w, VV_ROW(22).x);
                if (isEyeInWater == 2 && vkm_lavaResistance == 1) return vv_fogLayer(dist, VV_ROW(22).y, VV_ROW(22).z);
                float air = vv_fogLayer(dist, VV_FOG_AIR_START, VV_FOG_AIR_END);
                float rain = vv_fogLayer(dist, VV_FOG_WEATHER_START, VV_FOG_WEATHER_END);
                return max(air, rain * rainStrength);
            }
            vec3 vv_fogColor(vec3 dir) {
                if (isEyeInWater == 1) return VV_ROW(23).rgb;
                if (isEyeInWater == 2) return VV_ROW(24).rgb;
                return vv_sky(dir);
            }
            """;

    /** A light per cell of 4 blocks, position and radius then color and power; 8 light indices per cluster of 8 blocks. */
    static final String LOCAL_LIGHT_RESOURCES = """
            { "images": [ { "name": "voxelImg", "sampler": "voxelTex", "format": "R8UI", "clear": true, "size": [128, 64, 128] } ],
              "buffers": [ { "binding": 0, "size": 524288 }, { "binding": 1, "size": 65536 }, { "binding": 2, "size": 65540 }, { "binding": 3, "size": 1024 } ] }
            """;

    static final String LOCAL_LIGHT_STAGE = """
            { "stage": "PREPARE", "passes": [
              { "name": "resourceReset", "compute": "@DIR@resourceReset.csh", "workGroups": { "local": [1, 1, 1], "fixed": [1, 1, 1] } },
              { "name": "lightGather", "compute": "@DIR@lightGather.csh", "workGroups": { "local": [4, 4, 4], "fixed": [8, 4, 8] } },
              { "name": "lightCluster", "compute": "@DIR@lightCluster.csh", "workGroups": { "local": [2, 2, 2], "fixed": [8, 4, 8] } } ] }
            """;

    private static final String LIGHT_BUFFERS = """
            layout(std430, set = 2, binding = 256) buffer VvLights { vec4 vvLights[]; };
            layout(std430, set = 2, binding = 257) buffer VvClusters { int vvClusters[]; };
            layout(std430, set = 2, binding = 258) buffer VvActiveLights { int vvActiveLights[]; };
            const ivec3 VV_VOXELS = ivec3(128, 64, 128);
            """;

    private static final String LUMINANCE_HISTOGRAM = """
            layout(std430, set = 2, binding = 259) buffer VvLuminanceHistogram { uint vvLuminanceHistogram[]; };
            """;

    /**
     * The game's deferred point lights: GGX with Smith visibility over Lambert, the inverse square
     * falling to 0 at the radius. Its shadow atlas becomes a walk through the voxel volume, with a
     * short march of the depth buffer for what the volume lacks: entities. The vanilla level is
     * the gate for lights past the ones that get a shadow.
     */
    private static final String POINT_LIGHTS = LIGHT_BUFFERS + """
            const float VV_SOLID = 255.0;
            float vv_voxelShadow(vec3 from, vec3 to) {
                vec3 d = to - from;
                float len = length(d);
                vec3 dir = d / max(len, 1e-4);
                ivec3 v = ivec3(floor(from)), end = ivec3(floor(to));
                ivec3 go = ivec3(sign(dir));
                vec3 delta = 1.0 / max(abs(dir), vec3(1e-5));
                vec3 t = (vec3(v) + max(vec3(go), vec3(0.0)) - from) / (dir + vec3(equal(dir, vec3(0.0))) * 1e-5);
                for (int i = 0; i < 24; i++) {
                    int axis = t.x < t.y ? (t.x < t.z ? 0 : 2) : (t.y < t.z ? 1 : 2);
                    if (t[axis] >= len) break;
                    v[axis] += go[axis];
                    t[axis] += delta[axis];
                    if (v == end) break;
                    if (texelFetch(voxelTex, v, 0).r == 255u) return 0.0;
                }
                return 1.0;
            }
            #ifdef VV_POINT_SHADOWS
            float vv_contactShadow(vec3 world, vec3 l, float dist) {
                vec3 view = (gbufferModelView * vec4(world, 1.0)).xyz, dir = mat3(gbufferModelView) * l;
                float step = min(dist, 3.0) / 8.0;
                for (int i = 1; i <= 8; i++) {
                    vec3 q = view + dir * step * float(i), s = vkm_viewToScreen(q);
                    if (any(lessThan(s, vec3(0.0))) || any(greaterThan(s, vec3(1.0)))) break;
                    float d = texture(sceneDepth, s.xy).r;
                    if (d < s.z && abs(vkm_screenToView(s.xy, d).z - q.z) < 0.35) return 0.0;
                }
                return 1.0;
            }
            #endif
            /** Adds the lights of this point's cluster. {@code tint} is their mean color and how much they reach it, the game's input for block ambient. */
            void vv_pointLights(vec3 world, vec3 n, vec3 v, vec3 base, float metal, float subsurface, float roughness, vec3 f0, float level,
                                inout vec3 diffuse, inout vec3 specular, out vec4 tint) {
                tint = vec4(0.0);
                vec3 p = world + fract(cameraPosition) + vec3(64.0, 32.0, 64.0);
                if (any(lessThan(p, vec3(0.0))) || any(greaterThanEqual(p, vec3(VV_VOXELS)))) return;
                ivec3 cluster = ivec3(p / 8.0);
                int first = (cluster.x + cluster.z * 16 + cluster.y * 256) * 8;
                float gate = smoothstep(0.0, 0.14, level), a2 = roughness * roughness, a4 = a2 * a2, k = a2 * 0.5;
                float nv = max(dot(n, v), 1e-3), weight = 0.0;
                vec3 sum = vec3(0.0);
                for (int i = 0; i < 8; i++) {
                    int id = vvClusters[first + i];
                    if (id < 0) break;
                    vec4 at = vvLights[id * 2], color = vvLights[id * 2 + 1];
                    vec3 toLight = at.xyz - p;
                    float d2 = dot(toLight, toLight), r2 = at.w * at.w;
                    if (d2 >= r2) continue;
                    float ratio = d2 / (r2 + 1e-4), falloff = clamp(1.0 - ratio * ratio, 0.0, 1.0);
                    float attenuation = falloff * falloff / max(d2, 0.1) * gate;
                    float dist = sqrt(d2);
                    vec3 l = toLight / max(dist, 1e-4);
                    float nl = dot(n, l);
                    float seen = 1.0;
                    if (i < 4) seen = vv_voxelShadow(p + n * 0.06, at.xyz);
                #ifdef VV_POINT_SHADOWS
                    if (seen > 0.0 && i < 2 && dot(world, world) < 576.0) seen = vv_contactShadow(world + n * 0.05, l, dist);
                #endif
                    // The game also shadows the colored block ambient; it must not leak through walls.
                    if (seen > 0.0) {
                        sum += color.rgb * attenuation;
                        weight += 1.0 - ratio;
                    }
                    if (seen == 0.0) continue;
                    vec3 energy = color.rgb * color.a * attenuation * VV_POINT_GAIN;
                    vec3 h = normalize(l + v);
                    float vh = max(dot(v, h), 0.0);
                    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
                    const float WRAP = 0.5;
                    float wrapped = max((dot(n, l) + WRAP) / ((1.0 + WRAP) * (1.0 + WRAP)), 0.0);
                    float front = mix(nl, wrapped, subsurface);
                    float back = subsurface * max((dot(-n, l) + WRAP) / ((1.0 + WRAP) * (1.0 + WRAP)), 0.0);
                    diffuse += base * (1.0 - metal) / 3.14159265 * (1.0 - fresnel) * energy * (front + back);
                    if (nl > 0.0) {
                        float nh = max(dot(n, h), 0.0);
                        float d = nh * nh * (a4 - 1.0) + 1.0;
                        float distribution = a4 / max(3.14159265 * d * d, 1e-4);
                        float visibility = nv / (nv * (1.0 - k) + k) * nl / (nl * (1.0 - k) + k) / (4.0 * nl * nv + 1e-4);
                        specular += fresnel * distribution * visibility * energy * nl;
                    }
                }
                if (weight > 1e-4) tint = vec4(sum / weight, clamp(weight, 0.0, 1.0));
            }
            /** Block ambient, in the color of the lights nearby. */
            vec3 vv_blockAmbient(vec4 tint) {
                float peak = max(tint.r, max(tint.g, tint.b));
                vec3 hue = peak > 1e-4 ? tint.rgb / peak * dot(VV_BLOCK_LIGHT, vec3(1.0)) / 3.0 : VV_BLOCK_LIGHT;
                return mix(VV_BLOCK_LIGHT, hue, 0.5) * VV_POINT_AMBIENT;
            }
            """;

    /** Marches the depth buffer in view space; returns the hit's uv and how much to trust it, 0 for none. */
    private static final String TRACE = """
            vec3 vv_trace(sampler2D depth, vec3 origin, vec3 dir) {
                float len = 0.2;
                vec3 p = origin;
                for (int i = 0; i < 28; i++) {
                    vec3 from = p;
                    p += dir * len;
                    len *= 1.18;
                    vec3 s = vkm_viewToScreen(p);
                    if (any(lessThan(s, vec3(0.0))) || any(greaterThan(s, vec3(1.0)))) break;
                    float d = texture(depth, s.xy).r;
                    if (d >= s.z || d >= 1.0) continue;
                    if (abs(vkm_screenToView(s.xy, d).z - p.z) > len * 2.0 + 0.3) continue;
                    vec3 a = from, b = p;
                    for (int j = 0; j < 5; j++) {
                        vec3 c = (a + b) * 0.5;
                        s = vkm_viewToScreen(c);
                        if (texture(depth, s.xy).r < s.z) b = c;
                        else a = c;
                    }
                    vec2 edge = min(s.xy, 1.0 - s.xy) * 12.0;
                    return vec3(s.xy, clamp(min(edge.x, edge.y), 0.0, 1.0));
                }
                return vec3(0.0);
            }
            """;

    /** Caustic sampling, shared by the two passes that can show it: water surface and deferred lighting. */
    private static final String CAUSTICS = """
            #ifdef VV_CAUSTICS
            #ifdef VV_CAUSTIC_FRAMES
            /** The original frame strip: 64 tiles of 32x32 sampled at worldXZ * scale. */
            float vv_caustic(vec2 p) {
                vec2 at = p * VV_CAUSTIC_SCALE;
                float frame = mod(floor(frameTimeCounter / VV_CAUSTIC_FRAME), VV_CAUSTIC_FRAMES);
                return texture(causticsMap, vec2(at.x, (at.y + frame) / VV_CAUSTIC_FRAMES)).r;
            }
            #else
            /** Light gathered by a moving surface: an iterated interference pattern, tiling every 2 pi. */
            float vv_caustic(vec2 p) {
                float t = floor(frameTimeCounter / VV_CAUSTIC_FRAME) * VV_CAUSTIC_FRAME * 0.5 + 23.0, c = 1.0;
                vec2 q = mod(p * VV_CAUSTIC_SCALE * 6.2831853, 6.2831853) - 250.0, i = q;
                for (int n = 0; n < 5; n++) {
                    float s = t * (1.0 - 3.5 / float(n + 1));
                    i = q + vec2(cos(s - i.x) + sin(s + i.y), sin(s - i.y) + cos(s + i.x));
                    c += 1.0 / length(vec2(q.x / (sin(i.x + s) / 0.005), q.y / (cos(i.y + s) / 0.005)));
                }
                c = 1.17 - pow(c / 5.0, 1.4);
                return pow(abs(c), 8.0);
            }
            #endif
            /**
             * The original modulation, quoted: pow(2 * texel * clamp(N.y, 0, 1), power) * (power + 1).
             * It weights only the sun light there; we weight the whole surface shading, whose gaps
             * would fall to black, so the strength is blended toward one - the floor stays bright
             * and the web reads as a shimmer, as in the reference. Vertical faces are untouched.
             */
            float vv_causticModulation(vec2 floorXZ, float upness) {
                float up = clamp(upness, 0.0, 1.0);
                float m = pow(2.0 * vv_caustic(floorXZ) * up, VV_CAUSTIC_POWER) * (VV_CAUSTIC_POWER + 1.0);
                return mix(1.0, m, 0.45 * up);
            }
            #endif
            """;

    /** Translucent blocks share this program; only blocks with id 1 are water. */
    private static final String WATER = """
            layout(location = 0) in vec4 color;
            layout(location = 1) in vec2 uv;
            layout(location = 2) in vec3 worldPos;
            layout(location = 3) in vec3 normalWorld;
            layout(location = 4) in vec2 lm;
            layout(location = 5) flat in float block;
            const vec3 VV_ABSORB_WATER = vec3(0.40, 0.06, 0.02), VV_ABSORB_CHLOROPHYLL = vec3(0.03, 0.015, 0.04),
                    VV_ABSORB_SEDIMENT = vec3(0.002, 0.004, 0.008), VV_ABSORB_CDOM = vec3(0.0, 0.02, 0.08);
            #ifdef VV_WAVES
            /** Bedrock's sharp waves: analytic slopes, accumulated once per octave. */
            vec2 vv_waveSlope(vec2 p) {
                float frequency = VV_WAVE_FREQUENCY, speed = VV_WAVE_SPEED, weight = 1.0, total = 0.0, angle = 0.0;
                vec2 slope = vec2(0.0);
                for (int i = 0; i < VV_WAVE_OCTAVES; i++) {
                    vec2 d = vec2(sin(angle), cos(angle));
                    float x = dot(d, p) * frequency + frameTimeCounter * 0.5 * speed;
                    float s = sin(x), c = cos(x), wave = pow(0.5 * (s + 1.0), VV_WAVE_SHAPE);
                    slope += -d * VV_WAVE_DEPTH * weight * frequency * VV_WAVE_SHAPE * c * wave / (s + 1.0001);
                    total += weight;
                    p -= d * weight * wave * c * VV_WAVE_PULL;
                    weight = mix(weight, 0.0, VV_WAVE_MIX);
                    frequency *= VV_WAVE_FREQUENCY_SCALING;
                    speed *= VV_WAVE_SPEED_SCALING;
                    angle += radians(VV_WAVE_DIRECTION);
                }
                return slope / max(total, 1e-4);
            }
            #endif
            float vv_waterShadow(vec3 world, vec3 n) {
                vec4 clip = shadowProjection * shadowModelView * vec4(world + n * 0.05, 1.0);
                vec3 s = clip.xyz / clip.w * 0.5 + 0.5;
                if (any(lessThan(s.xy, vec2(0.0))) || any(greaterThan(s.xy, vec2(1.0)))) return 1.0;
            #ifdef VV_SHADOW_BLOCKY
                return step(s.z - 0.0004, texture(shadowtex0, s.xy).r);
            #else
                float sum = 0.0, texel = 1.0 / 2048.0;
                for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++)
                    sum += step(s.z - 0.0004, texture(shadowtex0, s.xy + vec2(x, y) * texel).r);
                return sum / 9.0;
            #endif
            }
            vec3 vv_waterSun(vec3 world, vec3 n, vec3 v) {
                vec3 sun = vv_sunVector(), l = sun.y >= 0.0 ? sun : -sun;
                float nl = max(dot(n, l), 0.0), nv = max(dot(n, v), 1e-3);
                if (nl <= 0.0) return vec3(0.0);
                vec3 light = (sun.y >= 0.0 ? vv_sunColor() * vv_sunIlluminance()
                        : vv_moonColor() * vv_moonIlluminance() * VV_MOON_GAIN / VV_SUN_GAIN) * VV_SUN_GAIN * (1.0 - rainStrength);
                vec3 h = normalize(l + v);
                float alpha = 0.08 * 0.08, a2 = alpha * alpha, k = alpha * 0.5;
                float nh = max(dot(n, h), 0.0), vh = max(dot(v, h), 0.0);
                float d = nh * nh * (a2 - 1.0) + 1.0;
                float distribution = a2 / max(3.14159265 * d * d, 1e-4);
                float visibility = nv / (nv * (1.0 - k) + k + 1e-5) * nl / (nl * (1.0 - k) + k + 1e-5) / (4.0 * nl * nv + 1e-5);
                float fresnel = 0.02 + 0.98 * pow(1.0 - vh, 5.0);
                return vec3(fresnel * distribution * visibility) * light * nl * vv_waterShadow(world, n);
            }
            void main() {
                vec4 c = texture(gtexture, uv) * color;
                vec3 v = normalize(-worldPos), n = normalize(normalWorld);
                float light = lm.y * lm.y * lm.y, fog = vv_fog(length(worldPos));
                vec3 pointDiffuse = vec3(0.0), pointSpecular = vec3(0.0);
                vec4 lightTint;
                vv_pointLights(worldPos, n, v, vec3(0.0), 0.0, 0.0, 0.08, vec3(0.04), lm.x, pointDiffuse, pointSpecular, lightTint);
                vec3 ambient = vv_ambient() * light + vv_blockAmbient(lightTint) * pow(lm.x, 3.0);
                if (block != 1.0) {
                    // Glass and ice: their own color over the scene, with the sky in their sheen.
                    float sheen = 0.04 + 0.96 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
                    vec3 own = pow(c.rgb, vec3(2.2)) * ambient;
                    vkmOut_lit = vec4(mix(mix(own, vv_sky(reflect(-v, n)) * light, sheen) + pointSpecular, vv_fogColor(normalize(worldPos)), fog), max(max(c.a, sheen), fog));
                    return;
                }
                vec2 size = vec2(textureSize(lit, 0)), screen = gl_FragCoord.xy / size;
                vec3 view = (gbufferModelView * vec4(worldPos, 1.0)).xyz;
            #ifdef VV_WAVES
                // Bedrock evaluates the surface in a low-resolution prepass: one normal per coarse cell
                // of a screen-sized grid, averaged over the cell. That is what makes its ripples read
                // as pixels; the quantized position and the 3x3 average reproduce it.
                float cell = max(0.0625, 12.0 * (-view.z) / size.y);
                vec2 wavePos = floor((worldPos.xz + cameraPosition.xz) / cell + 0.5) * cell;
                vec2 slope = vec2(0.0);
                for (int i = 0; i < 9; i++)
                    slope += vv_waveSlope(wavePos + (vec2(float(i % 3), float(i / 3)) - 1.0) * cell * 0.34);
                slope /= 3.0;
                // The loop's raw slopes reach several units (its 1/(s+1) term nearly diverges); the
                // coarse prepass keeps the visible tilt modest, the clamp reproduces that.
                slope /= max(length(slope) / 0.1, 1.0);
                n = normalize(mix(n, normalize(vec3(slope.x, 1.0, slope.y)), n.y));
            #endif
                vec3 viewNormal = normalize(mat3(gbufferModelView) * n);
                // The original never bends the sample: what lies under the surface shows through undistorted.
                float behindDepth = texture(depthtex1, screen).r;
                vec3 behind = (gbufferModelViewInverse * vec4(vkm_screenToView(screen, behindDepth), 1.0)).xyz;
                float thickness = behindDepth >= 1.0 ? 64.0 : length(behind - worldPos);
                vec3 scene = texture(lit, screen).rgb;
            #ifdef VV_CAUSTICS
                // From above, the surface pixel carries the caustics of the floor beneath it; from
                // below, the lighting pass of the floor itself does (see lighting.fsh).
                if (behindDepth < 1.0 && isEyeInWater == 0 && vv_sunVector().y > 0.0 && rainStrength < 1.0) {
                    vec3 bedNormal = normalize(texture(gnormalCopy, screen).xyz);
                    scene *= vv_causticModulation(behind.xz + cameraPosition.xz, bedNormal.y);
                }
            #endif
                vec4 row = VV_ROW(13);
                vec3 tint = mix(vec3(1.0), VV_WATER_COLOR, row.w);
                vec3 extinction = exp(-(VV_ABSORB_WATER + row.x * VV_ABSORB_CHLOROPHYLL + row.y * VV_ABSORB_SEDIMENT + row.z * VV_ABSORB_CDOM) * thickness);
                vec3 scattered = VV_WATER_COLOR * (0.25 + 0.75 * dot(c.rgb, vec3(0.33))) * ambient;
                vec3 body = scene * tint * extinction + scattered * (1.0 - extinction);
                float f = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
                vec3 r = reflect(normalize(view), viewNormal);
                // Seen from below, water has no surface reflection or SSR march.
                vec3 reflected = n.y > 0.0 ? vv_sky(mat3(gbufferModelViewInverse) * r) * light * 0.35 : vec3(0.0);
                if (n.y > 0.0) {
                    vec3 hit = vv_trace(depthtex1, view + viewNormal * 0.02, r);
                    if (hit.z > 0.0) reflected = mix(reflected, texture(lit, hit.xy).rgb, hit.z);
                }
                vec3 surface = mix(body, reflected, f) + pointSpecular + vv_waterSun(worldPos, n, v);
                vkmOut_lit = vec4(mix(surface, vv_fogColor(normalize(worldPos)), fog), 1.0);
            }
            """;

    static void write(Map<String, String> out, String dir, String settings, String params) {
        settings += """
                #define VV_SUN_GAIN 0.45
                #define VV_MOON_GAIN 0.15
                #define VV_AMBIENT_GAIN 1.35
                #define VV_BLOCK_LIGHT (vec3(1.0, 0.75, 0.5) * 0.4)
                #define VV_POINT_GAIN 1.2
                #define VV_POINT_AMBIENT 0.9
                #define VV_EMISSIVE 6.0
                #define VV_CLOUD_ALPHA 0.5
                #define VV_VOLUME_GAIN 4.0
                """;
        out.put(dir + "params.fsh", params + """
                layout(location = 0) out vec4 outColor;
                void main() {
                    // One row per setting, once a frame; the history blends biomes as the game does.
                    int i = int(gl_FragCoord.x);
                    vec4 v = vv_row(i), prev = texelFetch(paramsPrev, ivec2(i, 0), 0);
                    bool first = texelFetch(paramsPrev, ivec2(12, 0), 0).x <= 0.0;
                    outColor = first ? v : mix(prev, v, 1.0 - exp(-frameTime * 4.0));
                }
                """);
        bloom(out, dir);
        out.put(dir + "geo.vsh", """
                layout(location = 0) out vec4 color;
                layout(location = 1) out vec2 uv;
                layout(location = 2) out vec2 lm;
                layout(location = 3) out mat3 tbn;
                layout(location = 6) out vec3 worldPos;
                void main() {
                    gl_Position = vkm_position();
                    worldPos = (gbufferModelViewInverse * vkm_modelView * vec4(vaPosition, 1.0)).xyz;
                    color = vaColor * vkm_tint;
                    uv = vaUV0;
                    lm = vec2(vaUV2) / 240.0;
                    mat3 toWorld = mat3(gbufferModelViewInverse) * mat3(vkm_modelView);
                    vec3 n = normalize(toWorld * vaNormal);
                    vec3 t = normalize(toWorld * at_tangent.xyz);
                    tbn = mat3(t, cross(n, t) * at_tangent.w, n);
                }
                """);
        out.put(dir + "actor.fsh", settings + """
                layout(location = 0) in vec4 color;
                layout(location = 1) in vec2 uv;
                layout(location = 2) in vec2 lm;
                layout(location = 3) in mat3 tbn;
                layout(location = 6) in vec3 worldPos;
                void main() {
                    vec4 c = texture(gtexture, uv);
                #ifdef VKM_ALPHA_TEST
                    vkm_alphaTest(c.a);
                #endif
                    vec3 n = normalize(tbn[2]);
                    vec4 m = VV_PBR_ACTOR;
                    if (vkm_material.z < 0.0) {
                        // Held items and blocks: the sprites of the block atlas.
                        n = normalize(tbn * (texture(normals, uv).xyz * 2.0 - 1.0));
                        vec4 s = texture(specular, uv);
                        if (s != vec4(0.0)) m = s;
                    } else if (vkm_material.z > 0.0) {
                        // A model's own texture: its rectangle of the actor atlas. Models carry no tangents.
                        vec2 at = vkm_material.xy + fract(uv) * vkm_material.zw;
                        m = texture(actorSpecular, at);
                        if (m == vec4(0.0)) m = VV_PBR_ACTOR;
                        vec3 dx = dFdx(worldPos), dy = dFdy(worldPos);
                        vec2 ux = dFdx(uv), uy = dFdy(uv);
                        vec3 t = dx * uy.y - dy * ux.y, b = dy * ux.x - dx * uy.x;
                        float scale = max(length(t), length(b));
                        if (scale > 0.0) n = normalize(mat3(t / scale, b / scale, n) * (texture(actorNormals, at).xyz * 2.0 - 1.0));
                    }
                    // Bedrock packs metalness and subsurface scattering in the albedo alpha.
                    albedo = vec4(c.rgb * color.rgb, 1.0 - m.a * 0.498039);
                    gnormal = vec4(n, lm.y);
                    material = vec4(m.r, m.g, m.b, lm.x);
                }
                """);
        out.put(dir + "geo.fsh", settings + """
                layout(location = 0) in vec4 color;
                layout(location = 1) in vec2 uv;
                layout(location = 2) in vec2 lm;
                layout(location = 3) in mat3 tbn;
                void main() {
                    vec4 c = texture(gtexture, uv);
                #ifdef VKM_ALPHA_TEST
                    vkm_alphaTest(c.a);
                #endif
                    vec3 n = normalize(tbn * (texture(normals, uv).xyz * 2.0 - 1.0));
                    albedo = vec4(c.rgb * color.rgb, 1.0 - texture(specular, uv).a * 0.498039);
                    gnormal = vec4(n, lm.y);
                    vec4 m = texture(specular, uv);
                    if (m == vec4(0.0)) m = VV_PBR_BLOCK;
                    material = vec4(m.r, m.g, m.b, lm.x);
                }
                """);
        out.put(dir + "shadow.vsh", settings + """
                layout(location = 0) out vec2 uv;
                void main() {
                    gl_Position = vkm_position();
                    uv = vaUV0;
                    // 0 air, then each kind of light, 255 what stops light. Blocks that do not stop it have their own id.
                    int id = int(mc_Entity.x);
                    if (id >= 0 && id != 1 && id != VV_LIGHT_KINDS + 2) {
                        vec3 world = (shadowModelViewInverse * vkm_modelView * vec4(vaPosition, 1.0)).xyz;
                        ivec3 voxel = ivec3(floor(world + fract(cameraPosition) + at_midBlock.xyz / 64.0)) + ivec3(64, 32, 64);
                        if (all(greaterThanEqual(voxel, ivec3(0))) && all(lessThan(voxel, ivec3(128, 64, 128)))) {
                            imageStore(voxelImg, voxel, uvec4(id >= 2 ? uint(id - 1) : 255u));
                        }
                    }
                }
                """);
        out.put(dir + "resourceReset.csh", LIGHT_BUFFERS + LUMINANCE_HISTOGRAM + """
                void main() {
                    vvActiveLights[0] = 0;
                    for (int i = 0; i < 256; i++) vvLuminanceHistogram[i] = 0u;
                }
                """);
        out.put(dir + "histogram.csh", LUMINANCE_HISTOGRAM + """
                layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
                shared uint localHistogram[256];
                void main() {
                    int local = int(gl_LocalInvocationIndex);
                    localHistogram[local] = 0u;
                    barrier();
                    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                    if (all(lessThan(p, ivec2(vkm_passSize)))) {
                        vec3 c = texture(lit, (vec2(p) + 0.5) / vkm_passSize).rgb;
                        float l = clamp(log2(max(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.00095)), -3.0, 17.0);
                        atomicAdd(localHistogram[clamp(1 + int((l + 3.0) * (255.0 / 20.0)), 1, 255)], 1u);
                    }
                    barrier();
                    if (localHistogram[local] > 0u) atomicAdd(vvLuminanceHistogram[local], localHistogram[local]);
                }
                """);

        out.put(dir + "lightGather.csh", settings + LIGHT_BUFFERS + """
                void main() {
                    ivec3 cell = ivec3(gl_GlobalInvocationID);
                    vec3 sum = vec3(0.0), color = vec3(0.0);
                    float radius = 0.0, count = 0.0;
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            for (int x = 0; x < 4; x++) {
                                ivec3 v = cell * 4 + ivec3(x, y, z);
                                uint id = texelFetch(voxelTex, v, 0).r;
                                if (id == 0u || id > uint(VV_LIGHT_KINDS)) continue;
                                vec4 kind = VV_LIGHT_COLORS[id - 1u];
                                sum += vec3(v) + 0.5;
                                color += kind.rgb;
                                radius = max(radius, kind.a);
                                count += 1.0;
                            }
                        }
                    }
                    int slot = (cell.x + cell.z * 32 + cell.y * 1024) * 2;
                    vvLights[slot] = count > 0.0 ? vec4(sum / count, radius) : vec4(0.0);
                    // A lake of lava is one light per cell, brighter for its size.
                    vvLights[slot + 1] = count > 0.0 ? vec4(color / count, sqrt(count)) : vec4(0.0);
                    if (count > 0.0) {
                        int id = cell.x + cell.z * 32 + cell.y * 1024;
                        vvActiveLights[atomicAdd(vvActiveLights[0], 1) + 1] = id;
                    }
                }
                """);
        out.put(dir + "lightCluster.csh", LIGHT_BUFFERS + """
                void main() {
                    ivec3 cluster = ivec3(gl_GlobalInvocationID);
                    vec3 center = vec3(cluster) * 8.0 + 4.0;
                    int best[8];
                    float score[8];
                    for (int i = 0; i < 8; i++) {
                        best[i] = -1;
                        score[i] = 0.0;
                    }
                    // Only cells that actually contain a light participate; empty volumes cost no million-cell scan.
                    int count = min(vvActiveLights[0], 16384);
                    for (int n = 0; n < count; n++) {
                        int id = vvActiveLights[n + 1];
                        vec4 at = vvLights[id * 2];
                        float d = max(length(at.xyz - center) - 6.93, 0.0);
                        if (d >= at.w) continue;
                        float s = vvLights[id * 2 + 1].a / (1.0 + d * d);
                        for (int i = 0; i < 8; i++) {
                            if (s <= score[i]) continue;
                            int moved = best[i];
                            float kept = score[i];
                            best[i] = id;
                            score[i] = s;
                            id = moved;
                            s = kept;
                        }
                    }
                    int first = (cluster.x + cluster.z * 16 + cluster.y * 256) * 8;
                    for (int i = 0; i < 8; i++) vvClusters[first + i] = best[i];
                }
                """);
        out.put(dir + "shadow.fsh", """
                layout(location = 0) in vec2 uv;
                void main() {
                #if defined(VKM_ALPHA_TEST) && defined(VKM_GTEXTURE)
                    vkm_alphaTest(texture(gtexture, uv).a);
                #endif
                }
                """);
        out.put(dir + "sky.vsh", """
                layout(location = 0) out vec3 viewDir;
                layout(location = 1) out vec4 color;
                void main() {
                    gl_Position = vkm_position();
                    viewDir = (vkm_modelView * vec4(vaPosition, 1.0)).xyz;
                    color = vaColor * vkm_tint;
                }
                """);
        out.put(dir + "sky.fsh", settings + LIGHT + """
                layout(location = 0) in vec3 viewDir;
                layout(location = 1) in vec4 color;
                #ifdef VV_CUBEMAP
                vec3 cubemapColor(vec3 d) {
                    // Front, right, back, left, up, down; front looks north.
                    vec3 a = abs(d);
                    if (a.y >= a.x && a.y >= a.z) {
                        vec2 uv = d.xz / a.y * 0.5 + 0.5;
                        return d.y > 0.0 ? texture(cubemap4, vec2(uv.x, uv.y)).rgb : texture(cubemap5, vec2(uv.x, 1.0 - uv.y)).rgb;
                    }
                    if (a.z >= a.x) {
                        vec2 uv = vec2(d.x * sign(-d.z), -d.y) / a.z * 0.5 + 0.5;
                        return d.z < 0.0 ? texture(cubemap0, uv).rgb : texture(cubemap2, uv).rgb;
                    }
                    vec2 uv = vec2(d.z * sign(d.x) * -1.0, -d.y) / a.x * 0.5 + 0.5;
                    return d.x > 0.0 ? texture(cubemap1, uv).rgb : texture(cubemap3, uv).rgb;
                }
                #endif
                void main() {
                    vec3 c = vv_sky(normalize(mat3(gbufferModelViewInverse) * viewDir));
                #ifdef VV_CUBEMAP
                    vec3 dir = normalize(mat3(gbufferModelViewInverse) * viewDir);
                    vec4 row = VV_ROW(19);
                    vec3 skyLight = vv_ambient() * row.y, sunLight = vv_sunColor() * vv_sunIlluminance() * VV_SUN_GAIN * row.z / 3.14159;
                    vec3 cube = pow(cubemapColor(dir), vec3(2.2)) * (row.x + skyLight + sunLight);
                    // With scattering the air stands in front of the picture, thickest at the horizon.
                    c = row.w > 0.5 ? mix(cube, c, pow(1.0 - clamp(dir.y, 0.0, 1.0), 4.0)) : cube;
                #endif
                    // Stars are the only gray vertices of this category.
                    if (color.r == color.g && color.g == color.b && color.r > 0.0) c += color.rgb * 0.05 * (1.0 - rainStrength);
                    vkmOut_lit = vec4(c, 1.0);
                }
                """);
        out.put(dir + "disc.vsh", """
                layout(location = 0) out vec2 uv;
                layout(location = 1) out vec3 worldDir;
                layout(location = 2) out vec4 color;
                void main() {
                    gl_Position = vkm_position();
                    uv = vaUV0;
                    color = vaColor;
                    worldDir = mat3(gbufferModelViewInverse) * (vkm_modelView * vec4(vaPosition, 1.0)).xyz;
                }
                """);
        out.put(dir + "disc.fsh", settings + LIGHT + """
                layout(location = 0) in vec2 uv;
                layout(location = 1) in vec3 worldDir;
                layout(location = 2) in vec4 color;
                void main() {
                    vec4 c = texture(gtexture, uv) * color;
                    bool sun = dot(normalize(worldDir), vv_sunVector()) > 0.0;
                    vec3 tone = sun ? vv_sunColor() * vv_sunIlluminance() : vv_moonColor() * vv_moonIlluminance();
                    vkmOut_lit = vec4(pow(c.rgb, vec3(2.2)) * tone * c.a, 1.0);
                }
                """);
        out.put(dir + "exposure.fsh", LUMINANCE_HISTOGRAM + """
                layout(location = 0) out vec4 outColor;
                const float MIN_LOG = -3.0, RANGE_LOG = 20.0;
                void main() {
                    // average_luminance.json of the game: the log mean of the 80..100 percentile.
                    float total = 0.0;
                    for (int i = 1; i < 256; i++) total += float(vvLuminanceHistogram[i]);
                    float low = total * 0.8, seen = 0.0, sum = 0.0, count = 0.0;
                    for (int i = 1; i < 256; i++) {
                        float take = clamp(seen + float(vvLuminanceHistogram[i]), low, total) - clamp(seen, low, total);
                        sum += take * (MIN_LOG + float(i - 1) * (RANGE_LOG / 255.0));
                        count += take;
                        seen += float(vvLuminanceHistogram[i]);
                    }
                    float now = clamp(exp2(sum / max(count, 1.0)), 0.00095, 16.0);
                    float prev = texture(luminancePrev, vec2(0.5)).r;
                    outColor = vec4(prev > 0.0 ? mix(prev, now, 1.0 - exp(-frameTime)) : now, 0.0, 0.0, 1.0);
                }
                """);
        out.put(dir + "clouds.vsh", """
                layout(location = 0) out vec4 color;
                layout(location = 1) out vec2 uv;
                void main() {
                    gl_Position = vkm_position();
                    color = vaColor;
                    uv = vaUV0;
                }
                """);
        out.put(dir + "clouds.fsh", settings + LIGHT + """
                layout(location = 0) in vec4 color;
                layout(location = 1) in vec2 uv;
                void main() {
                    vec4 c = color;
                #ifdef VKM_GTEXTURE
                    c *= texture(gtexture, uv);
                #endif
                    vec3 sun = vv_sunColor() * vv_sunIlluminance() * VV_SUN_GAIN / 3.14159 * (1.0 - rainStrength);
                    vkmOut_lit = vec4(c.rgb * (vv_ambient() + sun * 0.4), c.a * VV_CLOUD_ALPHA);
                }
                """);
        out.put(dir + "weather.fsh", settings + LIGHT + """
                layout(location = 0) in vec4 color;
                layout(location = 1) in vec2 uv;
                void main() {
                    vec4 c = texture(gtexture, uv) * color;
                    vkmOut_lit = vec4(vv_ambient() * vec3(0.8, 0.9, 1.2), c.a * 0.5);
                }
                """);
        out.put(dir + "lighting.fsh", settings + LIGHT + "#define VV_POINT_SHADOWS\n" + POINT_LIGHTS + CAUSTICS + """
                layout(location = 0) out vec4 outColor;
                const float PI = 3.14159265;
                float ggx(float nh, float a) {
                    float d = nh * nh * (a * a - 1.0) + 1.0;
                    return a * a / max(PI * d * d, 1e-4);
                }
                float shadow(vec3 world, vec3 n) {
                #ifdef VV_SHADOW_BLOCKY
                    // Every texel of a block shades as one, by the point at its center.
                    world = (floor((world + n * 0.02 + cameraPosition) * VV_SHADOW_TEXEL) + 0.5) / VV_SHADOW_TEXEL - cameraPosition;
                #endif
                    vec4 clip = shadowProjection * shadowModelView * vec4(world + n * 0.05, 1.0);
                    vec3 s = clip.xyz / clip.w * 0.5 + 0.5;
                    if (any(lessThan(s.xy, vec2(0.0))) || any(greaterThan(s.xy, vec2(1.0)))) return 1.0;
                #ifdef VV_SHADOW_BLOCKY
                    return step(s.z - 0.0004, texture(shadowtex0, s.xy).r);
                #endif
                    float sum = 0.0, texel = 1.0 / 2048.0;
                    for (int x = -1; x <= 1; x++) {
                        for (int y = -1; y <= 1; y++) {
                            sum += step(s.z - 0.0004, texture(shadowtex0, s.xy + vec2(x, y) * texel).r);
                        }
                    }
                    return sum / 9.0;
                }
                void main() {
                    float d = texture(sceneDepth, vkm_texCoord).r;
                    vec4 a = texture(albedo, vkm_texCoord);
                    if (d >= 1.0 || a.a == 0.0) {
                        outColor = texture(lit, vkm_texCoord);
                        return;
                    }
                    vec4 g = texture(gnormal, vkm_texCoord), m = texture(material, vkm_texCoord);
                    vec3 n = normalize(g.xyz);
                    vec3 view = vkm_screenToView(vkm_texCoord, d);
                    vec3 world = (gbufferModelViewInverse * vec4(view, 1.0)).xyz;
                    vec3 v = normalize(-world);
                    vec3 sun = vv_sunVector();
                    bool isDay = sun.y >= 0.0;
                    vec3 l = isDay ? sun : -sun;
                    vec3 lightColor = (isDay ? vv_sunColor() * vv_sunIlluminance() : vv_moonColor() * vv_moonIlluminance() * VV_MOON_GAIN / VV_SUN_GAIN) * VV_SUN_GAIN;
                    lightColor *= 1.0 - rainStrength;
                    float roughness = max(m.r, 0.05), metal = m.g, emissive = m.b;
                    float nl = max(dot(n, l), 0.0), nv = max(dot(n, v), 1e-3);
                    float subsurface = clamp(2.007874 * (0.498039 - a.a), 0.0, 1.0);
                    float vis = nl > 0.0 || subsurface > 0.0 ? shadow(world, n) : 0.0;
                    vec3 base = pow(a.rgb, vec3(2.2));
                    vec3 f0 = mix(vec3(0.04), base, metal);
                    vec3 h = normalize(l + v);
                    float vh = max(dot(v, h), 0.0);
                    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
                    float alpha = roughness * roughness, k = alpha * 0.5;
                    float visibility = nv / (nv * (1.0 - k) + k + 1e-5) * nl / (nl * (1.0 - k) + k + 1e-5) / (4.0 * nl * nv + 1e-5);
                    vec3 specularLight = fresnel * ggx(max(dot(n, h), 0.0), alpha) * visibility;
                    const float WRAP = 0.5;
                    float wrapped = max((dot(n, l) + WRAP) / ((1.0 + WRAP) * (1.0 + WRAP)), 0.0);
                    float front = mix(nl, wrapped, subsurface);
                    float back = subsurface * max((dot(-n, l) + WRAP) / ((1.0 + WRAP) * (1.0 + WRAP)), 0.0);
                    vec3 diffuse = base * (1.0 - metal) / PI;
                    vec3 diffuseLight = diffuse * ((1.0 - fresnel) * front + back) * vis;
                    vec3 direct = diffuseLight * lightColor + specularLight * lightColor * nl * vis;
                    float sky = g.a * g.a * g.a;
                    vec3 pointDiffuse = vec3(0.0), pointSpecular = vec3(0.0);
                    vec4 lightTint;
                    vv_pointLights(world, n, v, base, metal, subsurface, max(roughness, 0.05), f0, m.a, pointDiffuse, pointSpecular, lightTint);
                    vec3 ambient = max(vv_ambient() * sky + vv_blockAmbient(lightTint) * pow(m.a, 3.0), vv_ambientColor() * vv_ambientIlluminance());
                    vec3 glow = mix(base, vec3(dot(base, vec3(0.2126, 0.7152, 0.0722))), VV_ROW(14).y);
                    vec3 color = direct + pointDiffuse + pointSpecular + base * (1.0 - metal) * ambient + glow * emissive * VV_EMISSIVE;
                #ifdef VV_CAUSTICS
                    // Under water no surface pixel exists to carry the caustics. The original modulates
                    // the floor's directional light, which dominates its shading there; our floor is
                    // ambient-heavy, so the whole color is modulated to read the same. The pattern is
                    // sampled in absolute XZ, so it stays pinned to the world.
                    if (isEyeInWater == 1 && sun.y > 0.0 && rainStrength < 1.0)
                        color *= vv_causticModulation(world.xz + cameraPosition.xz, n.y);
                #endif
                    outColor = vec4(mix(color, vv_fogColor(normalize(world)), vv_fog(length(world))), 1.0);
                }
                """);
        out.put(dir + "water.vsh", """
                layout(location = 0) out vec4 color;
                layout(location = 1) out vec2 uv;
                layout(location = 2) out vec3 worldPos;
                layout(location = 3) out vec3 normalWorld;
                layout(location = 4) out vec2 lm;
                layout(location = 5) flat out float block;
                void main() {
                    gl_Position = vkm_position();
                    color = vaColor;
                    uv = vaUV0;
                    lm = vec2(vaUV2) / 240.0;
                    worldPos = (gbufferModelViewInverse * vkm_modelView * vec4(vaPosition, 1.0)).xyz;
                    normalWorld = normalize(mat3(gbufferModelViewInverse) * mat3(vkm_modelView) * vaNormal);
                    block = mc_Entity.x;
                }
                """);
        out.put(dir + "water.fsh", settings + LIGHT + TRACE + POINT_LIGHTS + CAUSTICS + WATER);
        out.put(dir + "reflections.fsh", settings + LIGHT + TRACE + """
                layout(location = 0) out vec4 outColor;
                vec3 vv_clampToHistory(vec3 c, vec2 uv) {
                    vec2 texel = 1.0 / vec2(textureSize(reflectionsPrev, 0));
                    vec3 mean = vec3(0.0), square = vec3(0.0);
                    for (int y = -1; y <= 1; y++) {
                        for (int x = -1; x <= 1; x++) {
                            vec3 s = texture(reflectionsPrev, uv + vec2(x, y) * texel).rgb;
                            mean += s;
                            square += s * s;
                        }
                    }
                    mean /= 9.0;
                    vec3 deviation = sqrt(max(square / 9.0 - mean * mean, vec3(0.0)));
                    return clamp(c, mean - deviation * 1.25, mean + deviation * 1.25);
                }
                void main() {
                    float d = texture(sceneDepth, vkm_texCoord).r;
                    vec4 m = texture(material, vkm_texCoord);
                    float glossy = 1.0 - m.r / 0.5;
                    if (d >= 1.0 || glossy <= 0.0) {
                        outColor = vec4(0.0);
                        return;
                    }
                    // Deferred emission and albedo need opaque geometry; read them only after the cheap reject.
                    vec4 a = texture(albedo, vkm_texCoord), g = texture(gnormal, vkm_texCoord);
                    if (a.a < 0.25) {
                        outColor = vec4(0.0);
                        return;
                    }
                    vec3 view = vkm_screenToView(vkm_texCoord, d), v = normalize(view);
                    vec3 n = normalize(mat3(gbufferModelView) * g.xyz);
                    vec3 r = reflect(v, n);
                    vec3 base = pow(a.rgb, vec3(2.2)), f0 = mix(vec3(0.04), base, m.g);
                    float sky = g.a * g.a * g.a;
                    vec3 reflected = vv_sky(mat3(gbufferModelViewInverse) * r) * sky;
                    vec3 hit = vv_trace(sceneDepth, view + n * 0.03, r);
                    if (hit.z > 0.0) reflected = mix(reflected, texture(lit, hit.xy).rgb, hit.z);
                    float nv = clamp(dot(-v, n), 0.0, 1.0), roughness = max(m.r, 0.05);
                    // Split-sum environment BRDF, the analytic approximation of the game's LUT.
                    vec4 t = roughness * vec4(-1.04, -1.04, -1.04, 0.33) + vec4(1.0, 0.783, 0.759, 0.34);
                    float a004 = min(t.x * t.x, exp2(-9.28 * nv)) * t.x + t.y;
                    vec2 brdf = vec2(-1.04, 1.04) * a004 + t.zw;
                    float fog = vv_fog(length(view));
                    vec3 indirect = reflected * (f0 * brdf.x + brdf.y) * glossy * (1.0 - fog);
                    vec2 motion = texture(motion, vkm_texCoord).xy;
                    vec2 previousUV = vkm_texCoord + motion;
                    float historyValid = (frameCounter > 1 && all(greaterThanEqual(previousUV, vec2(0.0)))
                            && all(lessThanEqual(previousUV, vec2(1.0))) && dot(motion, motion) < 0.05) ? 0.75 : 0.0;

                    if (historyValid > 0.0) indirect = vv_clampToHistory(indirect, previousUV);
                    outColor = vec4(mix(indirect, texture(reflectionsPrev, previousUV).rgb, historyValid), 1.0);
                }
                """);
        out.put(dir + "reflectionApply.fsh", """
                layout(location = 0) out vec4 outColor;
                void main() {
                    outColor = vec4(texture(lit, vkm_texCoord).rgb + texture(reflections, vkm_texCoord).rgb, 1.0);
                }
                """);
        out.put(dir + "volume.fsh", settings + LIGHT + """
                layout(location = 0) out vec4 outColor;
                const int STEPS = 12;
                float density(float y) {
                    vec4 air = VV_ROW(15);
                    float d = air.w > 0.5 ? air.x : air.x * clamp((air.y - y) / max(air.y - air.z, 0.001), 0.0, 1.0);
                    return d;
                }
                float sunVisible(vec3 p) {
                    vec4 clip = shadowProjection * shadowModelView * vec4(p, 1.0);
                    vec3 s = clip.xyz / clip.w * 0.5 + 0.5;
                    if (any(lessThan(s.xy, vec2(0.0))) || any(greaterThan(s.xy, vec2(1.0)))) return 1.0;
                    return step(s.z - 0.0004, texture(shadowtex0, s.xy).r);
                }
                void main() {
                    float d = texture(depthtex0, vkm_texCoord).r;
                    vec3 world = (gbufferModelViewInverse * vec4(vkm_screenToView(vkm_texCoord, d), 1.0)).xyz;
                    float dist = min(length(world), far), dt = dist / float(STEPS);
                    vec3 dir = normalize(world), sun = vv_sunVector();
                    bool isDay = sun.y >= 0.0;
                    vec3 l = isDay ? sun : -sun;
                    vec3 light = (isDay ? vv_sunColor() * vv_sunIlluminance() : vv_moonColor() * vv_moonIlluminance()) * VV_SUN_GAIN * (1.0 - rainStrength);
                    bool water = isEyeInWater == 1;
                    float g = water ? VV_ROW(17).z : VV_ROW(16).a;
                    float c = dot(dir, l), k = 1.0 + g * g - 2.0 * g * c;
                    float phase = VV_PHASE * (1.0 - g * g) / (k * sqrt(k));
                    float scatter = dot(water ? VV_ROW(18).rgb : VV_ROW(16).rgb, vec3(0.2126, 0.7152, 0.0722));
                    float absorb = water ? VV_ROW(17).a : VV_ROW(17).x;
                    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))) + float(frameCounter) * 0.618034);
                    vec3 ambient = vv_ambient() * VV_PHASE, scattered = vec3(0.0);
                    float transmittance = 1.0;
                    for (int i = 0; i < STEPS; i++) {
                        vec3 p = dir * (float(i) + jitter) * dt;
                        float rho = water ? VV_ROW(17).y : density(p.y + cameraPosition.y);
                        float s = rho * scatter;
                        scattered += transmittance * s * VV_VOLUME_GAIN * dt * (light * phase * sunVisible(p) + ambient);
                        transmittance *= exp(-(s + rho * absorb) * dt);
                    }
                    outColor = vec4(scattered, transmittance);
                }
                """);
        out.put(dir + "volumeApply.fsh", """
                layout(location = 0) out vec4 outColor;
                void main() {
                    // Four taps hide the dither of the half-size march.
                    vec2 t = 1.0 / vec2(textureSize(volume, 0)), uv = vkm_texCoord;
                    vec4 v = (texture(volume, uv + t * vec2(0.5, 1.5)) + texture(volume, uv + t * vec2(-1.5, 0.5))
                            + texture(volume, uv + t * vec2(1.5, -0.5)) + texture(volume, uv + t * vec2(-0.5, -1.5))) * 0.25;
                    outColor = vec4(texture(lit, uv).rgb * v.a + v.rgb, 1.0);
                }
                """);
        out.put(dir + "grade.fsh", settings + """
                #define VV_ROW(i) texelFetch(params, ivec2(i, 0), 0)
                layout(location = 0) out vec4 outColor;
                vec3 vv_xyz(vec3 uv) {
                    return vec3(uv.x * uv.z / uv.y, uv.z, (1.0 - uv.x - uv.y) * uv.z / uv.y);
                }
                vec3 vv_temperature(vec3 c) {
                    if (VV_ROW(20).z < 0.5) return c;
                    float t = VV_ROW(20).w;
                    vec2 xy = vec2((0.8601177334785461 + 0.0001541182573419064 * t + 1.2864121856637212e-7 * t * t)
                            / (1.0 + 0.0008424202096648514 * t + 7.081451371959701e-7 * t * t),
                            (0.317398726940155 + 4.228062607580796e-5 * t + 4.204816761443908e-8 * t * t)
                            / (1.0 - 2.8974181986995973e-5 * t + 1.6145605741257896e-7 * t * t));
                    vec3 uv = vec3(vec2(3.0 * xy.x, 2.0 * xy.y) / (2.0 * xy.x - 8.0 * xy.y + 4.0), 1.0);
                    mat3 xyzToLms = transpose(mat3(vec3(0.7328, 0.4296, -0.1624), vec3(-0.7036, 1.6975, 0.0061), vec3(0.003, 0.0136, 0.9834)));
                    vec3 reference = xyzToLms * vv_xyz(vec3(0.31272, 0.32903, 1.0));
                    vec3 target = xyzToLms * vv_xyz(uv);
                    vec3 ratio = VV_ROW(21).x < 0.5 ? reference / target : target / reference;
                    mat3 lmsToRgb = transpose(mat3(vec3(2.859, -1.629, -0.025), vec3(-0.21, 1.158, 0.0), vec3(-0.042, -0.118, 1.069)));
                    mat3 rgbToLms = transpose(mat3(vec3(0.39, 0.55, 0.009), vec3(0.071, 0.963, 0.001), vec3(0.023, 0.128, 0.936)));
                    return lmsToRgb * (mat3(ratio.x, 0.0, 0.0, 0.0, ratio.y, 0.0, 0.0, 0.0, ratio.z) * rgbToLms) * c;
                }
                vec3 vv_selectGrade(vec3 mid, vec3 high, vec3 shadow, float luma, float average) {
                    float highAt = average * max(VV_ROW(35).x, 1.0);
                    float shadowAt = average * clamp(VV_ROW(35).y, 0.0, 0.999);
                    if (luma >= highAt) return high;
                    if (luma <= shadowAt) return shadow;
                    if (luma < average && average > shadowAt)
                        return mix(shadow, mid, clamp((luma - shadowAt) / (average - shadowAt), 0.0, 1.0));
                    if (luma > average && highAt > average)
                        return mix(mid, high, clamp((luma - average) / (highAt - average), 0.0, 1.0));
                    return mid;
                }
                vec3 vv_hableCurve(vec3 v) {
                    return (v * (v * 0.15 + 0.05) + 0.004) / (v * (v * 0.15 + 0.5) + 0.06) - 1.0 / 15.0;
                }
                vec3 tonemap(vec3 x) {
                    const float WHITE = 32.0;
                #if defined(VV_TONEMAP_REINHARD)
                    return x * (1.0 + x / (WHITE * WHITE)) / (1.0 + x);
                #elif defined(VV_TONEMAP_REINHARD_LUMA) || defined(VV_TONEMAP_REINHARD_LUMINANCE)
                    float luma = max(dot(x, vec3(0.2126, 0.7152, 0.0722)), 1e-5);
                    return x * ((luma * (1.0 + luma / (WHITE * WHITE)) / (1.0 + luma)) / luma);
                #elif defined(VV_TONEMAP_HABLE)
                    return vv_hableCurve(x * 2.0) / vv_hableCurve(vec3(WHITE * WHITE));
                #elif defined(VV_TONEMAP_ACES)
                    mat3 acesIn = transpose(mat3(vec3(0.59719, 0.35458, 0.04823), vec3(0.076, 0.90834, 0.01566), vec3(0.0284, 0.13383, 0.83777)));
                    mat3 acesOut = transpose(mat3(vec3(1.60475, -0.53108, -0.07367), vec3(-0.10208, 1.10813, -0.00605), vec3(-0.00327, -0.07276, 1.07602)));
                    vec3 a = acesIn * x;
                    return clamp(acesOut * clamp((a * (a + 0.0245786) - 0.000090537) / (a * (a * 0.983729 + 0.432951) + 0.238081), 0.0, 1.0), 0.0, 1.0);
                #else
                    // "generic": a curve through mid gray and a white point on the peak channel, with
                    // the hue kept until the peak is close to white.
                    const float a = 1.05;
                    float mid = pow(0.18, a), top = pow(WHITE, a), b = (top - mid / 0.18) / (top - mid), d = top * (1.0 - b);
                    float peak = max(max(x.r, x.g), max(x.b, 1e-5)), p = pow(peak, a), y = p / (b * p + d);
                    return mix(x / peak, vec3(1.0), pow(y, 32.0)) * y;
                #endif
                }
                void main() {
                    vec3 c = max(texture(lit, vkm_texCoord).rgb + texture(bloomUp1, vkm_texCoord).rgb * 0.02, vec3(0.0));
                    // The full histogram replaces the old 16x9 estimate; /5 remains the calibration
                    // measured against the game's pre-exposure pipeline.
                    float average = max(texture(luminance, vec2(0.5)).r, 0.125) / 5.0;
                    // The game chooses its grading range from the input luminance, before grading.
                    float sourceLuma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    vec3 contrast = vv_selectGrade(VV_ROW(8).rgb, VV_ROW(25).rgb, VV_ROW(30).rgb, sourceLuma, average);
                    vec3 saturation = vv_selectGrade(VV_ROW(11).rgb, VV_ROW(28).rgb, VV_ROW(33).rgb, sourceLuma, average);
                    vec3 gain = vv_selectGrade(VV_ROW(9).rgb, VV_ROW(26).rgb, VV_ROW(31).rgb, sourceLuma, average);
                    vec3 offset = vv_selectGrade(VV_ROW(10).rgb, VV_ROW(27).rgb, VV_ROW(32).rgb, sourceLuma, average);
                    c = vv_temperature(c);
                    c = average * pow(max(c, vec3(0.0)) / average, contrast);
                    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    c = max(mix(vec3(luma), c, saturation) * gain + offset, vec3(0.0));
                    c = clamp(tonemap(c * (0.18 / average)), 0.0, 1.0);
                    // Gamma follows the output of the selected tone-mapping operator.
                    float outputLuma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                    vec3 gamma = vv_selectGrade(VV_ROW(12).rgb, VV_ROW(29).rgb, VV_ROW(34).rgb, outputLuma, 0.18);
                    vec3 encoded = mix(12.92 * c, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
                    outColor = vec4(pow(encoded, 2.2 / gamma), 1.0);
                }
                """);
    }

    /** The game's blur pyramid: five taps down, a tent up, bright pixels only on the first level. */
    private static void bloom(Map<String, String> out, String dir) {
        String down = """
                layout(location = 0) out vec4 outColor;
                vec3 tap(vec2 uv) {
                    vec3 c = texture(SOURCE, uv).rgb;
                    return c * LIMIT;
                }
                void main() {
                    vec2 d = 1.5 / vec2(textureSize(SOURCE, 0));
                    vec2 uv = vkm_texCoord;
                    vec3 c = tap(uv) * 0.5 + (tap(uv + d) + tap(uv - d) + tap(uv + vec2(d.x, -d.y)) + tap(uv + vec2(-d.x, d.y))) * 0.125;
                    outColor = vec4(c, 1.0);
                }
                """;
        String limit = "step(texture(luminance, vec2(0.5)).r, dot(c, vec3(0.2126, 0.7152, 0.0722)))";
        out.put(dir + "bloom1.fsh", down.replace("SOURCE", "lit").replace("LIMIT", limit));
        for (int i = 2; i <= 4; i++) out.put(dir + "bloom" + i + ".fsh", down.replace("SOURCE", "bloom" + (i - 1)).replace("LIMIT", "1.0"));
        String up = """
                layout(location = 0) out vec4 outColor;
                void main() {
                    vec2 d = 2.0 / vec2(textureSize(LOW, 0)), uv = vkm_texCoord;
                    vec3 c = (texture(LOW, uv + d * 0.5).rgb + texture(LOW, uv + vec2(-d.x, d.y) * 0.5).rgb
                            + texture(LOW, uv + vec2(d.x, -d.y) * 0.5).rgb + texture(LOW, uv - d * 0.5).rgb) * 0.166
                            + (texture(LOW, uv + d).rgb + texture(LOW, uv + vec2(-d.x, d.y)).rgb
                            + texture(LOW, uv + vec2(d.x, -d.y)).rgb + texture(LOW, uv - d).rgb) * 0.083;
                    outColor = vec4(texture(HIGH, uv).rgb + c, 1.0);
                }
                """;
        out.put(dir + "bloomUp3.fsh", up.replace("LOW", "bloom4").replace("HIGH", "bloom3"));
        out.put(dir + "bloomUp2.fsh", up.replace("LOW", "bloomUp3").replace("HIGH", "bloom2"));
        out.put(dir + "bloomUp1.fsh", up.replace("LOW", "bloomUp2").replace("HIGH", "bloom1"));
    }

    private BedrockPrograms() {
    }
}
