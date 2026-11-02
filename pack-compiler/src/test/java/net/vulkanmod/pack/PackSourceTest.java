package net.vulkanmod.pack;

import net.vulkanmod.pack.source.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackSourceTest {

    @TempDir
    Path dir;

    static void zip(Path file, String... pathAndText) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < pathAndText.length; i += 2) {
                zip.putNextEntry(new ZipEntry(pathAndText[i]));
                zip.write(pathAndText[i + 1].getBytes());
                zip.closeEntry();
            }
        }
    }

    @Test
    void zipWithoutDirectoryEntries() throws IOException {
        Path file = dir.resolve("My Pack.zip");
        zip(file, "shaders/final.fsh", "x", "pack.json", "{}");
        try (PackSource src = PackSource.zip(file)) {
            assertEquals("My Pack", src.id());
            assertTrue(src.exists("shaders/final.fsh"));
            assertTrue(src.exists("./shaders/../pack.json"));
            assertTrue(src.hasDirectory("shaders"));
            assertFalse(src.hasDirectory("lib"));
            assertFalse(src.exists("shaders"));
            assertEquals("x", src.read("shaders/final.fsh"));
            assertThrows(IOException.class, () -> src.read("missing"));
        }
    }

    @Test
    void normalize() {
        assertEquals("a/c", PackSource.normalize("/a/./b/../c"));
        assertEquals("x", PackSource.normalize("../../x"));
        assertEquals("a/b", PackSource.normalize("a\\b"));
    }

    @Test
    void detection() throws IOException {
        Path nat = dir.resolve("nat.zip");
        zip(nat, "pack.json", "{\"format\": 1}");
        Path postfx = dir.resolve("vpfx.zip");
        zip(postfx, "pack.json", "{\"name\": \"x\"}", "post_effect/main.json", "{}");
        Path of = dir.resolve("of.zip");
        zip(of, "shaders/composite.fsh", "x");
        Path br = dir.resolve("br.zip");
        zip(br, "manifest.json", "{}");
        try (PackSource a = PackSource.zip(nat); PackSource b = PackSource.zip(postfx);
             PackSource c = PackSource.zip(of); PackSource d = PackSource.zip(br)) {
            assertEquals(PackKind.NATIVE, PackKind.detect(a));
            assertEquals(PackKind.POST_EFFECT, PackKind.detect(b));
            assertEquals(PackKind.OPTIFINE, PackKind.detect(c));
            assertEquals(PackKind.BEDROCK, PackKind.detect(d));
        }
        assertEquals(PackKind.UNKNOWN, PackKind.detect(PackSource.directory(Files.createDirectory(dir.resolve("empty")))));
    }
}
