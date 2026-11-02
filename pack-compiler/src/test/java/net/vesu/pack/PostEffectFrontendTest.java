package net.vesu.pack;

import net.vesu.pack.ir.IrPack;
import net.vesu.pack.plan.FramePlan;
import net.vesu.pack.postfx.PostEffectFrontend;
import net.vesu.pack.source.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PostEffectFrontendTest {

    @TempDir
    Path dir;

    private void write(String rel, String text) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private PostEffectFrontend.Result lower(String profile) throws IOException {
        try (PackSource src = PackSource.directory(dir)) {
            return PostEffectFrontend.lower(src, "test", profile);
        }
    }

    private static final String BLUR = """
            #version 330
            #moj_import <minecraft:globals.glsl>
            uniform sampler2D InSampler;
            layout(std140) uniform SamplerInfo {
                vec2 OutSize;
                vec2 InSize;
            };
            layout(std140) uniform BlurConfig {
                vec2 BlurDir;
                float Radius; // pixels
            };
            in vec2 texCoord;
            out vec4 fragColor;
            void main() { fragColor = texture(InSampler, texCoord + BlurDir * Radius / InSize) * GameTime; }
            """;

    private static String pass(String shader, String in, String out, String extra) {
        return """
                { "vertex_shader": "minecraft:core/screenquad", "fragment_shader": "%s",
                  "inputs": [ { "sampler_name": "In", "target": "%s", "bilinear": true } ],
                  "output": "%s" %s }""".formatted(shader, in, out, extra);
    }

    @Test
    void blocksBecomeConstantsByPosition() throws IOException {
        write("assets/t/shaders/post/blur.fsh", BLUR);
        // The second pass has no value names: 26.2 packs entries in array order.
        write("assets/t/post_effect/c.json", "{ \"targets\": { \"swap\": {} }, \"passes\": ["
                + pass("t:post/blur", "minecraft:main", "swap", """
                , "uniforms": { "BlurConfig": [ { "name": "BlurDir", "type": "vec2", "value": [1.0, 0.0] },
                                                { "name": "Radius", "type": "float", "value": 4 } ] }""") + ","
                + pass("t:post/blur", "swap", "minecraft:main", """
                , "uniforms": { "BlurConfig": [ { "type": "vec2", "value": [0.0, 1.0] },
                                                { "type": "float", "value": 8.5 } ] }""") + "] }");
        PostEffectFrontend.Result r = lower(null);
        assertEquals(1, r.pack().passes(IrPack.Stage.POST_SCENE).size());
        IrPack.Pass first = r.pack().passes(IrPack.Stage.POST_SCENE).get(0);
        assertEquals(List.of("sceneColor"), first.inputs);
        assertEquals(Boolean.TRUE, first.linearInputs.get("sceneColor"));

        String a = r.programs().get(first.fragment);
        assertTrue(a.startsWith("#version 450\n"));
        assertTrue(a.contains("const vec2 BlurDir = vec2(1.0, 0.0);"), a);
        assertTrue(a.contains("const float Radius = 4.0;"), a);
        assertTrue(a.contains("#define InSampler sceneColor"), a);
        assertTrue(a.contains("#define OutSize vkm_passSize"), a);
        assertTrue(a.contains("#define InSize vec2(textureSize(sceneColor, 0))"), a);
        assertTrue(a.contains("#define texCoord vkm_texCoord"), a);
        assertTrue(a.contains("#define GameTime (float(vkm_frame.worldTime) / 24000.0)"), a);
        assertTrue(a.contains("layout(location = 0) out vec4 fragColor;"), a);
        assertFalse(a.contains("uniform sampler2D"), a);

        String b = r.programs().get(r.pack().combination.fragment);
        assertTrue(b.contains("const float Radius = 8.5;"), b);
        assertTrue(b.contains("#define InSampler pfx0_0_swap_t"), b);
    }

    @Test
    void identicalPassesShareOneProgram() throws IOException {
        write("assets/t/shaders/post/copy.fsh", """
                #version 150
                uniform sampler2D InSampler;
                in vec2 texCoord;
                out vec4 fragColor;
                void main() { fragColor = texture(InSampler, texCoord); }
                """);
        write("assets/t/post_effect/c.json", "{ \"targets\": { \"a\": {}, \"b\": {} }, \"passes\": ["
                + pass("t:post/copy", "minecraft:main", "a", "") + "," + pass("t:post/copy", "minecraft:main", "b", "") + ","
                + pass("t:post/copy", "b", "minecraft:main", "") + "] }");
        PostEffectFrontend.Result r = lower(null);
        List<IrPack.Pass> post = r.pack().passes(IrPack.Stage.POST_SCENE);
        assertEquals(post.get(0).fragment, post.get(1).fragment);
    }

    @Test
    void mainFollowsItsWritesAndDepthIsReversedOnce() throws IOException {
        write("assets/t/shaders/post/copy.fsh", """
                #version 330
                uniform sampler2D InSampler;
                uniform sampler2D InDepthSampler;
                in vec2 texCoord;
                out vec4 fragColor;
                void main() { fragColor = texture(InSampler, texCoord) * texture(InDepthSampler, texCoord).r; }
                """);
        String twoInputs = """
                { "vertex_shader": "minecraft:core/screenquad", "fragment_shader": "t:post/copy",
                  "inputs": [ { "sampler_name": "In", "target": "%s" },
                              { "sampler_name": "InDepth", "target": "minecraft:main", "use_depth_buffer": true } ],
                  "output": "%s" }""";
        write("assets/t/post_effect/c.json", "{ \"targets\": { \"swap\": {} }, \"passes\": ["
                + twoInputs.formatted("minecraft:main", "swap") + "," + twoInputs.formatted("swap", "minecraft:main") + ","
                + twoInputs.formatted("minecraft:main", "swap") + "," + twoInputs.formatted("swap", "minecraft:main") + "] }");
        IrPack pack = lower(null).pack();
        List<IrPack.Pass> post = pack.passes(IrPack.Stage.POST_SCENE);
        assertEquals(4, post.size(), "reversed depth + three passes, the fourth is the combination");
        assertEquals(List.of("sceneDepth"), post.get(0).inputs);
        assertEquals("R32F", pack.texture(post.get(0).targets.get(0)).format.name());
        assertTrue(post.get(1).inputs.contains("sceneColor"));
        assertEquals("pfx_main_t", post.get(2).targets.get(0));
        assertTrue(post.get(3).inputs.contains("pfx_main_t"), "reads main after the write");
        FramePlan plan = FramePlan.build(pack);
        assertFalse(plan.combinationReadsScene);
        assertEquals(1, plan.imageCount[pack.textures.indexOf(pack.texture("pfx_main_t"))]);
    }

    @Test
    void manifestSelectsProfileAndScenes() throws IOException {
        write("assets/t/shaders/post/copy.fsh", """
                #version 330
                uniform sampler2D InSampler;
                in vec2 texCoord;
                out vec4 fragColor;
                void main() { fragColor = texture(InSampler, texCoord); }
                """);
        write("assets/t/post_effect/world.json", "{ \"passes\": [" + pass("t:post/copy", "minecraft:main", "minecraft:main", "") + "] }");
        write("vulcade.json", """
                { "name": "T", "default_profile": "high", "profiles": {
                    "low":  { "world": ["t:world"] },
                    "high": { "world": ["t:world"], "underwater": ["t:world"], "nether": ["t:world"] } } }""");
        // main is not read and written here: it reads the scene and is the combination.
        PostEffectFrontend.Result r = lower(null);
        assertEquals("T", r.pack().name);
        assertEquals(java.util.Set.of("underwater", "nether"), r.pack().scenes.keySet());
        assertSame(r.pack().scenes.get("nether"), r.pack().forScene("nether"));
        assertSame(r.pack(), r.pack().forScene("end"));
        assertTrue(lower("low").pack().scenes.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> lower("nope"));
    }

    @Test
    void unavailableTargetsAreReported() throws IOException {
        write("assets/t/shaders/post/copy.fsh", """
                #version 330
                uniform sampler2D InSampler;
                in vec2 texCoord;
                out vec4 fragColor;
                void main() { fragColor = texture(InSampler, texCoord); }
                """);
        write("assets/t/post_effect/c.json", "{ \"passes\": ["
                + pass("t:post/copy", "minecraft:translucent", "minecraft:main", "") + "] }");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> lower(null));
        assertTrue(e.getMessage().contains("minecraft:translucent"), e.getMessage());
    }

    @Test
    void detectsBothLayouts() throws IOException {
        write("assets/t/post_effect/c.json", "{}");
        try (PackSource src = PackSource.directory(dir)) {
            assertEquals(PackKind.POST_EFFECT, PackKind.detect(src));
        }
    }

    @Test
    void customVertexGetsLocations() throws IOException {
        write("assets/t/shaders/post/v.vsh", """
                #version 330
                out vec2 texCoord;
                out vec2 scaledCoord;
                void main() {
                    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(uv * 2.0 - 1.0, 0, 1);
                    texCoord = uv;
                    scaledCoord = uv * 0.5;
                }
                """);
        write("assets/t/shaders/post/f.fsh", """
                #version 330
                uniform sampler2D InSampler;
                in vec2 texCoord;
                in vec2 scaledCoord;
                out vec4 fragColor;
                void main() { fragColor = texture(InSampler, scaledCoord); }
                """);
        write("assets/t/post_effect/c.json", """
                { "passes": [ { "vertex_shader": "t:post/v", "fragment_shader": "t:post/f",
                  "inputs": [ { "sampler_name": "In", "target": "minecraft:main" } ], "output": "minecraft:main" } ] }""");
        PostEffectFrontend.Result r = lower(null);
        String vs = r.programs().get(r.pack().combination.vertex);
        String fs = r.programs().get(r.pack().combination.fragment);
        assertTrue(vs.contains("layout(location = 0) out vec2 texCoord;"), vs);
        assertTrue(vs.contains("layout(location = 1) out vec2 scaledCoord;"), vs);
        assertTrue(vs.contains("gl_VertexIndex"), vs);
        assertTrue(fs.contains("layout(location = 1) in vec2 scaledCoord;"), fs);
    }
}
