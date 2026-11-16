package net.vesu.vulkan.rt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.vesu.Initializer;

import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Vesu's own material set: {@code assets/vesu/materials.json} (a resource pack can replace it)
 * maps sprite names to material properties, and the LabPBR maps of a sprite without its own
 * {@code _s}/{@code _n} are derived from those properties and the sprite's loaded texture, so
 * they follow whatever resource pack draws the albedo.
 *
 * <p>Rule fields (first rule whose {@code match} regex finds the sprite path, e.g.
 * {@code blocks/stone}, wins; missing fields take the defaults below):
 * <ul>
 * <li>{@code smoothness}: perceptual smoothness 0..1; {@code smoothnessVar}: how much brighter
 *     (higher) texels are smoother than darker ones (crevices).</li>
 * <li>{@code f0}: dielectric reflectance at normal incidence.</li>
 * <li>{@code metal}: {@code iron}/{@code gold}/{@code albedo} (LabPBR 230/231/255, F0 from the
 *     albedo) on the texels of {@code metalMask}: {@code all}, {@code gray} (unsaturated texels),
 *     {@code yellow} (gold-coloured texels), {@code ore} (texels that differ from stone).</li>
 * <li>{@code maskF0}: like {@code f0}, but only on the texels of {@code metalMask} (gems in ores).</li>
 * <li>{@code porosity} 0..1, {@code subsurface} 0..1 (LabPBR blue 0..64 / 65..255).</li>
 * <li>{@code emission}: {@code {mask, from, to, peak, byValue}}: mask {@code all},
 *     {@code bright} (value from..to), {@code red} (red-dominant), {@code pale}
 *     (unsaturated and bright); emission = peak x mask (x value when byValue).</li>
 * <li>{@code normal}: strength of the relief derived from the texture (0: flat);
 *     {@code ao}: strength of the occlusion of its crevices.</li>
 * </ul>
 */
final class RtMaterialRules {

    static final ResourceLocation LOCATION = new ResourceLocation("vesu", "materials.json");

    static final class Emission {
        String mask = "all";
        float from = 0.0f, to = 1.0f, peak = 0.0f;
        boolean byValue;
    }

    static final class Rule {
        Pattern match;
        float smoothness = Float.NaN, smoothnessVar = 0.0f, f0 = 0.04f, maskF0 = Float.NaN;
        String metal, metalMask = "all";
        float porosity, subsurface;
        Emission emission;
        float normal = 1.0f, ao = 1.0f;
    }

    private RtMaterialRules() {}

    /** The rules of the current resources, in order; empty when the file is missing or broken. */
    static List<Rule> load(Minecraft mc) {
        List<Rule> rules = new ArrayList<>();
        try (IResource res = mc.getResourceManager().getResource(LOCATION)) {
            JsonObject root = new JsonParser().parse(
                    new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray list = root.getAsJsonArray("rules");
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                Rule r = new Rule();
                r.match = Pattern.compile(o.get("match").getAsString());
                r.smoothness = num(o, "smoothness", r.smoothness);
                r.smoothnessVar = num(o, "smoothnessVar", r.smoothnessVar);
                r.f0 = num(o, "f0", r.f0);
                r.maskF0 = num(o, "maskF0", r.maskF0);
                if (o.has("metal")) r.metal = o.get("metal").getAsString();
                if (o.has("metalMask")) r.metalMask = o.get("metalMask").getAsString();
                r.porosity = num(o, "porosity", 0.0f);
                r.subsurface = num(o, "subsurface", 0.0f);
                r.normal = num(o, "normal", r.normal);
                r.ao = num(o, "ao", r.ao);
                if (o.has("emission")) {
                    JsonObject em = o.getAsJsonObject("emission");
                    Emission x = new Emission();
                    if (em.has("mask")) x.mask = em.get("mask").getAsString();
                    x.from = num(em, "from", x.from);
                    x.to = num(em, "to", x.to);
                    x.peak = num(em, "peak", x.peak);
                    x.byValue = em.has("byValue") && em.get("byValue").getAsBoolean();
                    r.emission = x;
                }
                rules.add(r);
            }
        } catch (Exception e) {
            Initializer.LOGGER.warn("[rt] material rules {} unavailable: {}", LOCATION, e.toString());
        }
        return rules;
    }

    private static float num(JsonObject o, String key, float fallback) {
        return o.has(key) ? o.get(key).getAsFloat() : fallback;
    }

    static Rule find(List<Rule> rules, String spritePath) {
        for (Rule r : rules) {
            if (r.match.matcher(spritePath).find()) return r;
        }
        return null;
    }

    private static float smoothstep(float a, float b, float x) {
        if (b <= a) return x >= a ? 1.0f : 0.0f;
        float t = Math.min(Math.max((x - a) / (b - a), 0.0f), 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float ch(ByteBuffer b, int i, int c) {
        return (b.get(i * 4 + c) & 0xFF) / 255.0f;
    }

    /**
     * Writes the LabPBR specular ({@code spec}) and normal ({@code nrm}) texels of one sprite from
     * its rule and albedo texels ({@code albedo}, RGBA8, w x h; {@code stone}: the stone texture
     * at the same size for ore masks, may be null). Relief: the texture's luminance as height,
     * wrapped at the edges (block faces tile), flat on transparent texels. No height (alpha 255): a
     * texture's brightness is no depth to displace it by (parallax is for packs' height maps).
     */
    static void derive(Rule r, ByteBuffer albedo, ByteBuffer stone, int w, int h,
                       float defaultSmoothness, ByteBuffer spec, ByteBuffer nrm) {
        int n = w * h;
        float[] height = new float[n];
        boolean[] solid = new boolean[n];
        float mean = 0.0f;
        int count = 0;
        for (int i = 0; i < n; i++) {
            float lr = ch(albedo, i, 0), lg = ch(albedo, i, 1), lb = ch(albedo, i, 2);
            height[i] = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
            solid[i] = ch(albedo, i, 3) > 0.5f;
            if (solid[i]) {
                mean += height[i];
                count++;
            }
        }
        mean = count > 0 ? mean / count : 0.5f;
        for (int i = 0; i < n; i++) {
            if (!solid[i]) height[i] = mean;
        }
        // Slopes in block units: a texel step is 1/w of a block; a 16x texture's step is the unit.
        float k = r.normal * (float) Math.sqrt(w / 16.0);
        float smooth = Float.isNaN(r.smoothness) ? defaultSmoothness : r.smoothness;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float red = ch(albedo, i, 0), green = ch(albedo, i, 1), blue = ch(albedo, i, 2);
                float value = Math.max(red, Math.max(green, blue));
                float sat = value > 1e-4f ? (value - Math.min(red, Math.min(green, blue))) / value : 0.0f;
                boolean masked = switch (r.metalMask) {
                    case "gray" -> sat < 0.18f && value > 0.2f;
                    case "yellow" -> red > 0.45f && green > 0.3f && blue < green * 0.8f && red >= green;
                    case "ore" -> stone != null && Math.max(Math.abs(red - ch(stone, i, 0)),
                            Math.max(Math.abs(green - ch(stone, i, 1)), Math.abs(blue - ch(stone, i, 2)))) > 0.12f;
                    default -> true;
                };
                // Heights of the neighbours, wrapped.
                float hl = height[y * w + (x + w - 1) % w], hr = height[y * w + (x + 1) % w];
                float hu = height[((y + h - 1) % h) * w + x], hd = height[((y + 1) % h) * w + x];
                float hc = height[i];
                float s = smooth + r.smoothnessVar * (hc - mean) * 2.0f;
                int g;
                if (r.metal != null && masked) {
                    g = switch (r.metal) {
                        case "iron" -> 230;
                        case "gold" -> 231;
                        default -> 255;
                    };
                } else {
                    float f0 = !Float.isNaN(r.maskF0) && masked && !"all".equals(r.metalMask) ? r.maskF0 : r.f0;
                    g = Math.round(Math.min(Math.max(f0, 0.0f), 0.89f) * 255.0f);
                    if (!Float.isNaN(r.maskF0) && masked && !"all".equals(r.metalMask)) s = Math.max(s, smooth + 0.4f);
                }
                int b = r.subsurface > 0.0f ? 65 + Math.round(Math.min(r.subsurface, 1.0f) * 190.0f)
                        : Math.round(Math.min(r.porosity, 1.0f) * 64.0f);
                int a = 255;
                if (r.emission != null && solid[i]) {
                    Emission em = r.emission;
                    float m = switch (em.mask) {
                        case "bright" -> smoothstep(em.from, em.to, value);
                        case "red" -> smoothstep(em.from, em.to, red - Math.max(green, blue));
                        case "pale" -> smoothstep(em.from, em.to, value) * (sat < 0.25f ? 1.0f : 0.0f);
                        default -> 1.0f;
                    };
                    float e = em.peak * m * (em.byValue ? value : 1.0f);
                    if (e > 0.5f / 254.0f) a = Math.min(Math.round(e * 254.0f), 254);
                }
                int o = i * 4;
                spec.put(o, (byte) Math.round(Math.min(Math.max(s, 0.0f), 1.0f) * 255.0f))
                        .put(o + 1, (byte) g).put(o + 2, (byte) b).put(o + 3, (byte) a);

                float nx = 0.0f, ny = 0.0f, occ = 1.0f;
                if (solid[i] && k > 0.0f) {
                    // DirectX convention (green towards +v): the shader's bitangent is dP/dv.
                    nx = -(hr - hl) * 0.5f * k;
                    ny = -(hd - hu) * 0.5f * k;
                    float len = (float) Math.sqrt(nx * nx + ny * ny + 1.0f);
                    nx /= len;
                    ny /= len;
                }
                if (solid[i] && r.ao > 0.0f) {
                    float cavity = Math.max((hl + hr + hu + hd) * 0.25f - hc, 0.0f);
                    occ = Math.max(1.0f - r.ao * cavity * 2.0f, 0.7f);
                }
                nrm.put(o, (byte) Math.round((nx * 0.5f + 0.5f) * 255.0f))
                        .put(o + 1, (byte) Math.round((ny * 0.5f + 0.5f) * 255.0f))
                        .put(o + 2, (byte) Math.round(occ * 255.0f))
                        .put(o + 3, (byte) 255);
            }
        }
    }
}
