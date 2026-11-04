package net.vesu.pack.host;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.vesu.pack.ir.TextureFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Images and storage buffers every program of a pack shares (pack-format.md, "Resources"), in
 * descriptor set {@link #SET}. They are not scheduled like render textures: a program writes
 * them whenever it wants, and the host puts a memory barrier before stages whose program text
 * uses the set.
 */
public final class Resources {

    public static final int SET = 2;
    /** An image takes two bindings, storage then sampler; buffers follow at this base. */
    public static final int BUFFER_BASE = 256;

    /** {@code width, height, depth}: 0 for an unused axis. With a scale, the size follows the render resolution. */
    public record Image(String name, String sampler, TextureFormat format, int width, int height, int depth,
                        float scaleX, float scaleY, boolean clear, Raw raw) {
        public int dimensions() {
            return scaleX > 0 ? 2 : depth > 0 ? 3 : height > 0 ? 2 : 1;
        }
    }

    /** A sampled image filled from a file of the pack, once: {@code file} holds rows of pixels of these components. */
    public record Raw(String file, int components, int componentBytes, boolean bgra, String pixelType) {
    }

    public record Buffer(int binding, long size) {
    }

    public final List<Image> images = new ArrayList<>();
    public final List<Buffer> buffers = new ArrayList<>();

    /** The {@code resources} section of a pack, empty when it has none. */
    public static Resources of(JsonObject raw) {
        Resources r = new Resources();
        if (raw == null || !raw.has("resources")) return r;
        JsonObject o = raw.getAsJsonObject("resources");
        if (o.has("images")) {
            for (JsonElement e : o.getAsJsonArray("images")) {
                JsonObject i = e.getAsJsonObject();
                TextureFormat format = TextureFormat.parse(i.get("format").getAsString());
                if (format == null) throw new IllegalArgumentException("image " + i.get("name") + ": unknown format");
                int[] size = new int[3];
                float[] scale = new float[2];
                if (i.has("size")) {
                    JsonArray a = i.getAsJsonArray("size");
                    for (int k = 0; k < a.size() && k < 3; k++) size[k] = a.get(k).getAsInt();
                }
                if (i.has("scale")) {
                    JsonArray a = i.getAsJsonArray("scale");
                    for (int k = 0; k < a.size() && k < 2; k++) scale[k] = a.get(k).getAsFloat();
                }
                Raw file = null;
                if (i.has("file")) {
                    JsonObject p = i.getAsJsonObject("pixels");
                    file = new Raw(i.get("file").getAsString(), p.get("components").getAsInt(), p.get("bytes").getAsInt(),
                            p.get("bgra").getAsBoolean(), p.get("type").getAsString());
                }
                r.images.add(new Image(i.get("name").getAsString(), i.has("sampler") ? i.get("sampler").getAsString() : null,
                        format, size[0], size[1], size[2], scale[0], scale[1],
                        file == null && (!i.has("clear") || i.get("clear").getAsBoolean()), file));
            }
        }
        if (o.has("buffers")) {
            for (JsonElement e : o.getAsJsonArray("buffers")) {
                JsonObject b = e.getAsJsonObject();
                r.buffers.add(new Buffer(b.get("binding").getAsInt(), b.get("size").getAsLong()));
            }
        }
        return r;
    }

    public boolean isEmpty() {
        return images.isEmpty() && buffers.isEmpty();
    }

    public static int storageBinding(int image) {
        return image * 2;
    }

    public static int samplerBinding(int image) {
        return image * 2 + 1;
    }

    public static int bufferBinding(int binding) {
        return BUFFER_BASE + binding;
    }

    /** Declarations of the images; a storage buffer's block comes from the program that uses it. */
    public String declarations() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < images.size(); i++) {
            Image m = images.get(i);
            String prefix = m.format().isInteger() ? "u" : "";
            String dims = m.dimensions() + "D";
            if (m.raw() == null) {
                sb.append("layout(set = ").append(SET).append(", binding = ").append(storageBinding(i)).append(", ")
                        .append(m.format().glslQualifier).append(") uniform ").append(prefix).append("image").append(dims)
                        .append(' ').append(m.name()).append(";\n");
            }
            if (m.sampler() != null) {
                sb.append("layout(set = ").append(SET).append(", binding = ").append(samplerBinding(i)).append(") uniform ")
                        .append(prefix).append("sampler").append(dims).append(' ').append(m.sampler()).append(";\n");
            }
        }
        if (!isEmpty()) sb.append(BOUNDED_FETCH);
        return sb.toString();
    }

    /**
     * Light that spreads through a volume leaves it through the fetches past its edge, which
     * read 0 under GL and are undefined in Vulkan. Only packs with resources pay for the check.
     */
    private static final String BOUNDED_FETCH;

    static {
        StringBuilder sb = new StringBuilder();
        for (String prefix : new String[]{"", "u", "i"}) {
            String result = prefix + "vec4";
            sb.append(result).append(" vkm_fetch(").append(prefix).append("sampler1D s, int p, int l) { return p >= 0 && p < textureSize(s, l) ? texelFetch(s, p, l) : ")
                    .append(result).append("(0); }\n");
            for (int d = 2; d <= 3; d++) {
                String coord = "ivec" + d;
                sb.append(result).append(" vkm_fetch(").append(prefix).append("sampler").append(d).append("D s, ").append(coord)
                        .append(" p, int l) { return all(greaterThanEqual(p, ").append(coord).append("(0))) && all(lessThan(p, textureSize(s, l))) ? texelFetch(s, p, l) : ")
                        .append(result).append("(0); }\n");
            }
        }
        BOUNDED_FETCH = sb.append("#define texelFetch(s, p, l) vkm_fetch(s, p, l)\n").toString();
    }
}
