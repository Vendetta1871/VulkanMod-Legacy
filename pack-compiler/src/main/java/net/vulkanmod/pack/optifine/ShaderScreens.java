package net.vulkanmod.pack.optifine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The option pages of {@code shaders.properties} ({@code screen}, {@code sliders}, {@code profile.*}). */
public final class ShaderScreens {

    /** Entries of each page by name, "" is the main one: an option, {@code [page]}, {@code <empty>} or {@code <profile>}. */
    public final Map<String, List<String>> pages = new LinkedHashMap<>();
    public final Map<String, String> profiles = new LinkedHashMap<>();
    public final List<String> sliders = new ArrayList<>();
    /** {@code lang/en_US.lang}: {@code option.X}, {@code value.X.v}, {@code screen.X}. */
    public final Map<String, String> lang = new LinkedHashMap<>();

    /**
     * The values a profile sets: {@code NAME}, {@code !NAME}, {@code NAME=VALUE}, {@code NAME:VALUE}
     * and {@code profile.OTHER}, in order.
     */
    public void apply(String profile, Map<String, String> out) {
        String text = profiles.get(profile);
        if (text == null) return;
        for (String token : text.trim().split("\\s+")) {
            if (token.startsWith("profile.")) {
                apply(token.substring(8), out);
            } else if (token.startsWith("!")) {
                out.put(token.substring(1), "false");
            } else {
                int at = token.indexOf('=') >= 0 ? token.indexOf('=') : token.indexOf(':');
                if (at > 0) out.put(token.substring(0, at), token.substring(at + 1));
                else if (!token.isEmpty()) out.put(token, "true");
            }
        }
    }

    static ShaderScreens parse(ShaderProperties p, Map<String, OptiFineFrontend.Option> options) {
        ShaderScreens s = new ShaderScreens();
        String main = p.get("screen");
        s.pages.put("", main != null ? List.of(main.trim().split("\\s+")) : new ArrayList<>(options.keySet()));
        p.under("screen.").forEach((k, v) -> {
            if (!k.endsWith(".columns")) s.pages.put(k, List.of(v.trim().split("\\s+")));
        });
        s.profiles.putAll(p.under("profile."));
        String sliders = p.get("sliders");
        if (sliders != null) s.sliders.addAll(List.of(sliders.trim().split("\\s+")));
        return s;
    }
}
