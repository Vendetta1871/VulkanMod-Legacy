package net.vulkanmod.pack.ir;

/**
 * Texture formats a pack may declare (pack-format.md, "Textures"), with their GLSL
 * storage-image qualifier. The Vulkan format lives in the mod, which owns the Vulkan side.
 */
public enum TextureFormat {
    RGBA8("rgba8"),
    RGBA16("rgba16"),
    RGBA16F("rgba16f"),
    RGBA32F("rgba32f"),
    R8("r8"),
    R16F("r16f"),
    R32F("r32f"),
    RG8("rg8"),
    RG16F("rg16f"),
    RG32F("rg32f"),
    R11G11B10F("r11f_g11f_b10f"),
    RGB10A2("rgb10_a2"),
    R8UI("r8ui"),
    R16UI("r16ui"),
    RG8UI("rg8ui"),
    RG16UI("rg16ui"),
    RG32UI("rg32ui"),
    RGBA8UI("rgba8ui"),
    RGBA16UI("rgba16ui"),
    R32UI("r32ui"),
    RGBA32UI("rgba32ui");

    /** The {@code layout(<qualifier>)} of a storage image in this format. */
    public final String glslQualifier;

    TextureFormat(String glslQualifier) {
        this.glslQualifier = glslQualifier;
    }

    public boolean isInteger() {
        return glslQualifier.endsWith("ui");
    }

    /** A format by its GLSL name, as Iris's {@code image.<name>} lines give it; null when unknown. */
    public static TextureFormat ofQualifier(String name) {
        for (TextureFormat f : values()) {
            if (f.glslQualifier.equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    /**
     * Parses a pack format name. RGB formats without alpha map to the RGBA format of the
     * same channel width, as OptiFine's {@code colortexNFormat} values do; null when unknown.
     */
    public static TextureFormat parse(String name) {
        return switch (name) {
            case "RGB8" -> RGBA8;
            case "RGB16" -> RGBA16;
            case "RGB16F" -> RGBA16F;
            case "RGB32F" -> RGBA32F;
            default -> {
                for (TextureFormat f : values()) {
                    if (f.name().equals(name)) yield f;
                }
                yield null;
            }
        };
    }
}
