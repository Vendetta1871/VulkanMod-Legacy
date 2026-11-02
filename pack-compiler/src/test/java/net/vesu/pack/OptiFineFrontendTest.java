package net.vesu.pack;

import net.vesu.pack.ir.IrPack;
import net.vesu.pack.optifine.OptiFineFrontend;
import net.vesu.pack.source.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OptiFineFrontendTest {

    private static final String PASS = """
            #version 120
            uniform sampler2D colortex0;
            varying vec2 tc;
            void main() {
                /* DRAWBUFFERS:04 */
                gl_FragData[0] = texture2D(colortex0, tc);
                gl_FragData[1] = vec4(1.0);
            }
            """;
    private static final String VERTEX = """
            #version 120
            varying vec2 tc;
            void main() { gl_Position = ftransform(); tc = gl_MultiTexCoord0.xy; }
            """;

    @TempDir
    Path dir;

    private void write(String rel, String text) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private void passes(String folder, String... names) throws IOException {
        for (String n : names) {
            write("shaders/" + folder + n + ".fsh", PASS);
            write("shaders/" + folder + n + ".vsh", VERTEX);
        }
    }

    private IrPack lower() throws IOException {
        try (PackSource src = PackSource.directory(dir)) {
            return OptiFineFrontend.lower(src, "test", Map.of(), Map.of()).pack();
        }
    }

    private static List<String> composite(IrPack pack) {
        return pack.commandLists.values().stream().flatMap(List::stream).map(p -> p.name).toList();
    }

    @Test
    void enabledAcceptsLiteralsAndOptions() throws IOException {
        passes("", "composite", "composite1", "composite2", "final");
        write("shaders/shaders.properties", """
                program.composite1.enabled=false
                program.world0/composite2.enabled=!FANCY
                """);
        write("shaders/composite.fsh", "#version 120\n#define FANCY // fancier\n#ifdef FANCY\n#endif\n" + PASS.substring(PASS.indexOf('\n') + 1));
        assertEquals(List.of("composite"), composite(lower()));
    }

    @Test
    void flipReachesThePass() throws IOException {
        passes("", "composite", "final");
        write("shaders/shaders.properties", """
                flip.composite.colortex0=false
                flip.composite.colortex4=true
                """);
        IrPack.Pass pass = lower().commandLists.values().iterator().next().get(0);
        assertEquals(Map.of("colortex0", false, "colortex4", true), pass.flip);
    }

    @Test
    void blendOfOneBufferReachesThePass() throws IOException {
        passes("", "composite", "final");
        write("shaders/shaders.properties", """
                blend.composite=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO
                blend.composite.colortex4=off
                """);
        IrPack.Pass pass = lower().commandLists.values().iterator().next().get(0);
        assertTrue(pass.blend.enabled);
        assertFalse(pass.targetBlend.get("colortex4").enabled);
    }
}
