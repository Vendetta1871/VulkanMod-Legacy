package net.vulkanmod.pack;

import net.vulkanmod.pack.source.IncludeExpander;
import net.vulkanmod.pack.source.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IncludeExpanderTest {

    @TempDir
    Path dir;

    private PackSource pack(String... pathAndText) throws IOException {
        for (int i = 0; i < pathAndText.length; i += 2) {
            Path f = dir.resolve(pathAndText[i]);
            Files.createDirectories(f.getParent());
            Files.writeString(f, pathAndText[i + 1]);
        }
        return PackSource.directory(dir);
    }

    @Test
    void enablesLineDirectivesAfterVersion() throws IOException {
        PackSource src = pack("programs/a.fsh", "// header\n#version 450\nvoid main() {}\n");
        String out = new IncludeExpander(src).expandProgram("programs/a.fsh");
        assertEquals("// header\n#version 450\n" + IncludeExpander.LINE_EXTENSION + "\n"
                + "#line 3 \"programs/a.fsh\"\nvoid main() {}\n", out);
    }

    @Test
    void preludeGoesAfterTheExtensionAndKeepsLineNumbers() throws IOException {
        PackSource src = pack("a.fsh", "#version 450\nvoid main() {}\n", "b.fsh", "void main() {}");
        IncludeExpander x = new IncludeExpander(src);
        assertEquals("#version 450\n" + IncludeExpander.LINE_EXTENSION + "\n// P\n#line 2 \"a.fsh\"\nvoid main() {}\n",
                x.expandProgram("a.fsh", "// P\n"));
        assertEquals("#version 450\n" + IncludeExpander.LINE_EXTENSION + "\n// P\n#line 1 \"b.fsh\"\nvoid main() {}",
                x.expandProgram("b.fsh", "// P"));
    }

    @Test
    void withoutVersionTheExtensionComesFirst() throws IOException {
        PackSource src = pack("a.fsh", "void main() {}");
        String out = new IncludeExpander(src).expandProgram("a.fsh");
        assertEquals(IncludeExpander.LINE_EXTENSION + "\n#line 1 \"a.fsh\"\nvoid main() {}", out);
    }

    @Test
    void includesResolveRelativeAndFromRoot() throws IOException {
        PackSource src = pack(
                "programs/a.fsh", "#version 450\n#include \"common.glsl\"\n#include <lib/root.glsl>\nx",
                "programs/common.glsl", "c1\nc2",
                "lib/root.glsl", "r1");
        String out = new IncludeExpander(src).expandProgram("programs/a.fsh");
        assertEquals("#version 450\n" + IncludeExpander.LINE_EXTENSION + "\n#line 2 \"programs/a.fsh\"\n"
                + "#line 1 \"programs/common.glsl\"\nc1\nc2\n#line 3 \"programs/a.fsh\"\n"
                + "#line 1 \"lib/root.glsl\"\nr1\n#line 4 \"programs/a.fsh\"\nx", out);
    }

    @Test
    void cycleIsSkippedAndKeepsNumbering() throws IOException {
        PackSource src = pack("a.glsl", "#include \"b.glsl\"", "b.glsl", "#include \"a.glsl\"\nb2");
        String out = new IncludeExpander(src).expand("a.glsl", src.read("a.glsl"));
        assertEquals("#line 1 \"b.glsl\"\n// include cycle skipped: a.glsl\nb2\n#line 2 \"a.glsl\"", out);
    }

    @Test
    void missingIncludeNamesTheLine() throws IOException {
        PackSource src = pack("a.fsh", "#version 450\n\n#include \"nope.glsl\"");
        IOException e = assertThrows(IOException.class, () -> new IncludeExpander(src).expandProgram("a.fsh"));
        assertTrue(e.getMessage().contains("a.fsh:3"), e.getMessage());
    }

    @Test
    void parentSegmentsCannotLeaveThePack() throws IOException {
        Files.writeString(dir.getParent().resolve("outside.glsl"), "secret");
        PackSource src = pack("a.fsh", "#include \"../outside.glsl\"");
        assertThrows(IOException.class, () -> new IncludeExpander(src).expand("a.fsh", src.read("a.fsh")));
    }

    @Test
    void crlfIncludeLinesAreRecognized() throws IOException {
        PackSource src = pack("a.fsh", "#include \"b.glsl\"\r\nx", "b.glsl", "b");
        String out = new IncludeExpander(src).expand("a.fsh", src.read("a.fsh"));
        assertTrue(out.startsWith("#line 1 \"b.glsl\"\nb\n#line 2 \"a.fsh\""), out);
    }
}
