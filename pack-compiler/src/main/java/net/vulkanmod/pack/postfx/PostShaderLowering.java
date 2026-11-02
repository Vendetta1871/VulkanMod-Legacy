package net.vulkanmod.pack.postfx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers one 26.2 post shader (GLSL 330/150, loose samplers, std140 blocks by name) to the
 * GLSL 450 a pack program is (frontends.md, "26.2 post_effect frontend"). Works on the
 * declarations only, which is all these shaders have that Vulkan GLSL rejects. Line numbers
 * of the original file are kept, so compiler errors point at it.
 *
 * <ul>
 *   <li>Blocks with JSON values become constants of that one pass. 26.2 packs values in
 *       array order, so they are matched by position; a pack need not name them.</li>
 *   <li>{@code SamplerInfo} members map by position: {@code OutSize}, then one size per input.</li>
 *   <li>Engine blocks ({@code Globals}, {@code Projection}, {@code Fog}, {@code Lighting}) map
 *       onto the {@code Frame} block, which these packs get under {@link #FRAME_INSTANCE}.</li>
 * </ul>
 */
final class PostShaderLowering {

    /** Instance name of the Frame block: its members must not clash with a shader's globals. */
    static final String FRAME_INSTANCE = "vkm_frame";

    private static final String F = FRAME_INSTANCE + ".";
    private static final Map<String, String> ENGINE_FIELDS = Map.ofEntries(
            Map.entry("CameraBlockPos", "ivec3(floor(" + F + "cameraPosition))"),
            Map.entry("CameraOffset", "(" + F + "cameraPosition - floor(" + F + "cameraPosition))"),
            Map.entry("ScreenSize", "vec2(" + F + "viewWidth, " + F + "viewHeight)"),
            Map.entry("GlintAlpha", "1.0"),
            Map.entry("GameTime", "(float(" + F + "worldTime) / 24000.0)"),
            Map.entry("MenuBlurRadius", "5"),
            Map.entry("UseRgss", "0"),
            Map.entry("ProjMat", F + "gbufferProjection"),
            Map.entry("FogColor", "vec4(" + F + "fogColor, 1.0)"),
            Map.entry("FogEnvironmentalStart", F + "fogStart"),
            Map.entry("FogEnvironmentalEnd", F + "fogEnd"),
            Map.entry("FogRenderDistanceStart", F + "fogStart"),
            Map.entry("FogRenderDistanceEnd", F + "fogEnd"),
            Map.entry("FogSkyEnd", F + "far"),
            Map.entry("FogCloudsEnd", F + "far"),
            Map.entry("Light0_Direction", "vec3(0.2, 1.0, -0.7)"),
            Map.entry("Light1_Direction", "vec3(-0.2, 1.0, 0.7)"));

    private static final Pattern VERSION = Pattern.compile("^[ \\t]*#[ \\t]*version\\b.*$", Pattern.MULTILINE);
    private static final Pattern BLOCK = Pattern.compile(
            "layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)\\s*\\{([^}]*)\\}\\s*(\\w*)\\s*;");
    private static final Pattern SAMPLER = Pattern.compile(
            "^[ \\t]*uniform\\s+(\\w*sampler\\w*)\\s+(\\w+)\\s*;", Pattern.MULTILINE);
    private static final Pattern LOOSE_UNIFORM = Pattern.compile("^[ \\t]*uniform\\s+\\w+\\s+\\w+", Pattern.MULTILINE);
    private static final Pattern VARYING = Pattern.compile(
            "^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?(in|out)\\s+(\\w+)\\s+(\\w+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MEMBER = Pattern.compile("(\\w+)\\s+([\\w\\s,\\[\\]]+)");
    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private PostShaderLowering() {
    }

    /** One member of a uniform block. */
    private record Member(String type, String name, int count) {
    }

    /** Output locations of a vertex shader: {@code texCoord} first, then declaration order. */
    static Map<String, Integer> varyings(String vertexSource) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("texCoord", 0);
        Matcher m = VARYING.matcher(vertexSource);
        while (m.find()) {
            if (m.group(1).equals("out") && !out.containsKey(m.group(3))) out.put(m.group(3), out.size());
        }
        return out;
    }

    /**
     * @param source    the shader with its imports expanded
     * @param file      its path, for error messages and {@code #line}
     * @param samplers  shader sampler name → pack input name
     * @param uniforms  the pass's {@code uniforms} object, or null
     * @param varyings  locations from {@link #varyings}; only texCoord with the host triangle
     * @param fragment  fragment or vertex stage
     * @param warnings  receives notes about blocks the pack left out
     */
    static String lower(String source, String file, Map<String, String> samplers, JsonObject uniforms,
                        Map<String, Integer> varyings, boolean fragment, List<String> warnings) {
        StringBuilder header = new StringBuilder();
        String text = VERSION.matcher(source).replaceFirst("");

        Matcher sampler = SAMPLER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (sampler.find()) {
            if (!sampler.group(1).equals("sampler2D")) {
                throw new IllegalArgumentException(file + ": unsupported sampler type " + sampler.group(1));
            }
            String input = samplers.get(sampler.group(2));
            if (input == null) {
                throw new IllegalArgumentException(file + ": sampler " + sampler.group(2) + " has no input in the pass");
            }
            header.append("#define ").append(sampler.group(2)).append(' ').append(input).append('\n');
            sampler.appendReplacement(out, "");
        }
        text = sampler.appendTail(out).toString();

        Matcher block = BLOCK.matcher(text);
        out = new StringBuilder();
        while (block.find()) {
            if (!block.group(3).isEmpty()) {
                throw new IllegalArgumentException(file + ": named uniform block instances are not supported: "
                        + block.group(1));
            }
            lowerBlock(block.group(1), parseMembers(block.group(2), file), samplers, uniforms, header, file, warnings);
            block.appendReplacement(out, Matcher.quoteReplacement(newlines(block.group())));
        }
        text = block.appendTail(out).toString();

        Matcher loose = LOOSE_UNIFORM.matcher(COMMENTS.matcher(text).replaceAll(""));
        if (loose.find()) {
            throw new IllegalArgumentException(file + ": loose uniform not supported: " + loose.group().trim());
        }

        Matcher varying = VARYING.matcher(text);
        out = new StringBuilder();
        boolean output = false;
        while (varying.find()) {
            boolean in = varying.group(1).equals("in");
            String name = varying.group(3), type = varying.group(2);
            String decl;
            if (fragment && !in) {
                if (output) throw new IllegalArgumentException(file + ": a post pass has one output");
                output = true;
                decl = "layout(location = 0) out " + type + ' ' + name + ';';
            } else if (fragment && name.equals("texCoord")) {
                header.append("#define texCoord vkm_texCoord\n");
                decl = "";
            } else {
                if (!fragment && in) {
                    throw new IllegalArgumentException(file + ": vertex inputs are not supported: " + name);
                }
                Integer location = varyings.get(name);
                if (location == null) {
                    throw new IllegalArgumentException(file + ": input " + name + " is not written by the vertex shader");
                }
                decl = "layout(location = " + location + ") " + varying.group(1) + ' ' + type + ' ' + name + ';';
            }
            varying.appendReplacement(out, Matcher.quoteReplacement(decl));
        }
        text = varying.appendTail(out).toString();

        text = text.replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex")
                .replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex");
        if (fragment) text = text.replaceAll("\\bgl_FragCoord\\b", "vkm_FragCoord");
        return "#version 450\n" + header + "#line 1 \"" + file + "\"\n" + text;
    }

    private static void lowerBlock(String name, List<Member> members, Map<String, String> samplers, JsonObject uniforms,
                                   StringBuilder header, String file, List<String> warnings) {
        if (name.equals("SamplerInfo")) {
            lowerSamplerInfo(members, samplers, header, file);
            return;
        }
        if (uniforms != null && uniforms.has(name)) {
            lowerConstants(name, members, uniforms.getAsJsonArray(name), header, file, warnings);
            return;
        }
        for (Member m : members) {
            String engine = ENGINE_FIELDS.get(m.name);
            if (engine != null) {
                header.append("#define ").append(m.name).append(' ').append(engine).append('\n');
            } else {
                warnings.add(file + ": block " + name + " has no values, " + m.name + " is 0");
                header.append("const ").append(m.type).append(' ').append(m.name).append(" = ")
                        .append(zero(m, file)).append(";\n");
            }
        }
    }

    private static void lowerSamplerInfo(List<Member> members, Map<String, String> samplers, StringBuilder header,
                                         String file) {
        if (members.isEmpty()) return;
        header.append("#define ").append(members.get(0).name).append(" vkm_passSize\n");
        List<String> inputs = new ArrayList<>(samplers.values());
        for (int i = 1; i < members.size(); i++) {
            if (i > inputs.size()) {
                throw new IllegalArgumentException(file + ": SamplerInfo member " + members.get(i).name
                        + " has no input in the pass");
            }
            header.append("#define ").append(members.get(i).name).append(" vec2(textureSize(")
                    .append(inputs.get(i - 1)).append(", 0))\n");
        }
    }

    private static void lowerConstants(String block, List<Member> members, JsonArray values, StringBuilder header,
                                       String file, List<String> warnings) {
        int next = 0;
        for (Member m : members) {
            int n = Math.max(1, m.count);
            List<String> elements = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (next >= values.size()) {
                    warnings.add(file + ": block " + block + " has no value for " + m.name + ", it is 0");
                    elements.add(zero(new Member(m.type, m.name, 0), file));
                } else {
                    elements.add(literal(m, values.get(next++).getAsJsonObject().get("value"), file));
                }
            }
            header.append("const ").append(m.type).append(' ').append(m.name);
            if (m.count > 0) {
                header.append('[').append(m.count).append("] = ").append(m.type).append('[').append(m.count).append("](")
                        .append(String.join(", ", elements)).append(')');
            } else {
                header.append(" = ").append(elements.get(0));
            }
            header.append(";\n");
        }
    }

    private static String literal(Member m, JsonElement value, String file) {
        int expected = components(m.type, file);
        List<JsonElement> parts = new ArrayList<>();
        if (value.isJsonArray()) value.getAsJsonArray().forEach(parts::add);
        else parts.add(value);
        if (parts.size() != expected) {
            throw new IllegalArgumentException(file + ": " + m.name + " is " + m.type + ", its value has "
                    + parts.size() + " component(s)");
        }
        boolean integer = m.type.equals("int") || m.type.equals("ivec2") || m.type.equals("ivec3") || m.type.equals("ivec4");
        List<String> items = new ArrayList<>();
        for (JsonElement e : parts) {
            items.add(integer ? Integer.toString(e.getAsInt()) : number(e.getAsDouble()));
        }
        return expected == 1 ? items.get(0) : m.type + '(' + String.join(", ", items) + ')';
    }

    private static String number(double d) {
        String s = Double.toString(d);
        return s.contains("E") ? new java.math.BigDecimal(d).toPlainString() : s;
    }

    private static String zero(Member m, String file) {
        if (m.count > 0) throw new IllegalArgumentException(file + ": array " + m.name + " needs values");
        return m.type.equals("int") ? "0" : m.type.equals("float") ? "0.0" : m.type + "(0)";
    }

    private static int components(String type, String file) {
        return switch (type) {
            case "int", "float" -> 1;
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            case "mat4" -> 16;
            default -> throw new IllegalArgumentException(file + ": unsupported uniform type " + type);
        };
    }

    private static List<Member> parseMembers(String body, String file) {
        List<Member> members = new ArrayList<>();
        for (String statement : COMMENTS.matcher(body).replaceAll("").split(";")) {
            String s = statement.trim();
            if (s.isEmpty()) continue;
            Matcher m = MEMBER.matcher(s);
            if (!m.matches()) throw new IllegalArgumentException(file + ": cannot read block member: " + s);
            for (String declarator : m.group(2).split(",")) {
                String d = declarator.trim();
                int bracket = d.indexOf('[');
                members.add(bracket < 0 ? new Member(m.group(1), d, 0)
                        : new Member(m.group(1), d.substring(0, bracket).trim(),
                        Integer.parseInt(d.substring(bracket + 1, d.indexOf(']')).trim())));
            }
        }
        return members;
    }

    private static String newlines(String text) {
        return "\n".repeat((int) text.chars().filter(c -> c == '\n').count());
    }
}
