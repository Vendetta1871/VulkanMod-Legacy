package net.vulkanmod.pack.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A uniform block laid out by the std140 rules, in declaration order. The single source of
 * truth for a host block: the GLSL declaration packs see and the byte offsets the host
 * writes are both generated from it, so the two cannot drift apart.
 */
public final class Std140Layout {

    public enum Type {
        FLOAT("float", 4, 4), INT("int", 4, 4), UINT("uint", 4, 4),
        VEC2("vec2", 8, 8), IVEC2("ivec2", 8, 8),
        VEC3("vec3", 16, 12), IVEC3("ivec3", 16, 12),
        VEC4("vec4", 16, 16), IVEC4("ivec4", 16, 16),
        MAT4("mat4", 16, 64);

        public final String glsl;
        final int align;
        final int size;

        Type(String glsl, int align, int size) {
            this.glsl = glsl;
            this.align = align;
            this.size = size;
        }
    }

    /** One member: name, type, array length (0 = not an array) and byte offset. */
    public record Field(String name, Type type, int count, int offset) {
        /** Bytes between array elements (std140: rounded up to 16). */
        public int stride() {
            return roundUp(type.size, 16);
        }
    }

    private final String blockName;
    private final List<Field> fields;
    private final int size;

    private Std140Layout(String blockName, List<Field> fields, int size) {
        this.blockName = blockName;
        this.fields = fields;
        this.size = size;
    }

    public String blockName() {
        return blockName;
    }

    public List<Field> fields() {
        return fields;
    }

    /** Block size in bytes, rounded up to the 16-byte block alignment. */
    public int size() {
        return size;
    }

    /** Byte offset of a member; throws for unknown names (a host/layout mismatch is a bug). */
    public int offset(String name) {
        return field(name).offset;
    }

    public Field field(String name) {
        for (Field f : fields) {
            if (f.name.equals(name)) return f;
        }
        throw new IllegalArgumentException("no member " + name + " in " + blockName);
    }

    /**
     * The GLSL declaration. The block has no instance name, so its members are plain
     * identifiers (OptiFine names stay unchanged in translated packs).
     */
    public String glslDeclaration(int set, int binding) {
        return glslDeclaration(set, binding, "");
    }

    /** {@code instance}: the block's instance name, or empty for global member names. */
    public String glslDeclaration(int set, int binding, String instance) {
        StringBuilder sb = new StringBuilder();
        sb.append("layout(std140, set = ").append(set).append(", binding = ").append(binding)
                .append(") uniform ").append(blockName).append(" {\n");
        for (Field f : fields) {
            sb.append("    ").append(f.type.glsl).append(' ').append(f.name);
            if (f.count > 0) sb.append('[').append(f.count).append(']');
            sb.append(";\n");
        }
        return sb.append('}').append(instance.isEmpty() ? "" : " " + instance).append(";\n").toString();
    }

    public static Builder builder(String blockName) {
        return new Builder(blockName);
    }

    public static final class Builder {
        private final String blockName;
        private final List<Field> fields = new ArrayList<>();
        private int offset;

        private Builder(String blockName) {
            this.blockName = blockName;
        }

        public Builder add(String name, Type type) {
            return add(name, type, 0);
        }

        public Builder add(String name, Type type, int count) {
            for (Field f : fields) {
                if (f.name.equals(name)) throw new IllegalArgumentException("duplicate member " + name);
            }
            int align = count > 0 ? 16 : type.align;
            int size = count > 0 ? roundUp(type.size, 16) * count : type.size;
            offset = roundUp(offset, align);
            fields.add(new Field(name, type, count, offset));
            offset += size;
            return this;
        }

        public Std140Layout build() {
            return new Std140Layout(blockName, Collections.unmodifiableList(new ArrayList<>(fields)),
                    roundUp(offset, 16));
        }
    }

    static int roundUp(int v, int a) {
        return (v + a - 1) / a * a;
    }
}
