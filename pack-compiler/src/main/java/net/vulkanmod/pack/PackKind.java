package net.vulkanmod.pack;

import net.vulkanmod.pack.source.PackSource;

import java.io.IOException;

/**
 * Which format a pack is in. Detection only looks at marker files; parsing (and its
 * errors) belongs to the frontend of that format. Order matters: a native pack.json wins,
 * because a resource pack may ship a pack.json too, but without "format".
 */
public enum PackKind {
    /** {@code pack.json} with a "format" field (docs/shaderpacks/pack-format.md). */
    NATIVE("Native"),
    /** A {@code shaders/} folder (OptiFine / Iris). */
    OPTIFINE("OptiFine"),
    /** {@code post_effect/} chains (Minecraft 26.x), with or without a Vulcade manifest. */
    POST_EFFECT("post_effect"),
    /** {@code manifest.json} of a Bedrock resource pack. */
    BEDROCK("Bedrock"),
    UNKNOWN("unknown");

    public final String label;

    PackKind(String label) {
        this.label = label;
    }

    public static PackKind detect(PackSource src) {
        if (src.exists("pack.json") && hasFormatField(src)) {
            return NATIVE;
        }
        if (src.hasDirectory("shaders")) {
            return OPTIFINE;
        }
        if (src.hasDirectory("post_effect") || src.exists("vulcade.json")) {
            return POST_EFFECT;
        }
        for (String namespace : src.list("assets")) {
            if (src.hasDirectory("assets/" + namespace + "/post_effect")) {
                return POST_EFFECT;
            }
        }
        if (src.exists("manifest.json")) {
            return BEDROCK;
        }
        return UNKNOWN;
    }

    private static boolean hasFormatField(PackSource src) {
        try {
            // Cheap textual check: detection must not fail on a pack the frontend will
            // later reject with a proper message.
            return src.read("pack.json").contains("\"format\"");
        } catch (IOException e) {
            return false;
        }
    }
}
