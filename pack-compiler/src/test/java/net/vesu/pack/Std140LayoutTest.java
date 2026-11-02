package net.vesu.pack;

import net.vesu.pack.host.FrameBlock;
import net.vesu.pack.host.Std140Layout;
import org.junit.jupiter.api.Test;

import static net.vesu.pack.host.Std140Layout.Type.*;
import static org.junit.jupiter.api.Assertions.*;

class Std140LayoutTest {

    @Test
    void std140Rules() {
        Std140Layout l = Std140Layout.builder("B")
                .add("a", FLOAT)      // 0
                .add("b", VEC3)       // 16 (vec3 aligns to 16)
                .add("c", FLOAT)      // 28 (fills the vec3's fourth slot)
                .add("d", VEC2)       // 32
                .add("e", INT)        // 40
                .add("f", MAT4)       // 48
                .add("g", FLOAT, 3)   // 112, stride 16
                .add("h", IVEC2)      // 160
                .build();
        assertEquals(0, l.offset("a"));
        assertEquals(16, l.offset("b"));
        assertEquals(28, l.offset("c"));
        assertEquals(32, l.offset("d"));
        assertEquals(40, l.offset("e"));
        assertEquals(48, l.offset("f"));
        assertEquals(112, l.offset("g"));
        assertEquals(16, l.field("g").stride());
        assertEquals(160, l.offset("h"));
        assertEquals(176, l.size());
    }

    @Test
    void glslDeclaration() {
        Std140Layout l = Std140Layout.builder("B").add("m", MAT4).add("v", VEC4, 2).build();
        assertEquals("layout(std140, set = 0, binding = 3) uniform B {\n    mat4 m;\n    vec4 v[2];\n};\n",
                l.glslDeclaration(0, 3));
    }

    @Test
    void frameBlockFitsTheMinimumUboRange() {
        // maxUniformBufferRange is at least 16 KB on every Vulkan device.
        assertTrue(FrameBlock.LAYOUT.size() <= 16384, "Frame block is " + FrameBlock.LAYOUT.size() + " bytes");
        assertEquals(0, FrameBlock.LAYOUT.size() % 16);
        assertEquals(0, FrameBlock.LAYOUT.offset("gbufferModelView"));
        // vec3 + float pairs share one 16-byte slot.
        assertEquals(FrameBlock.LAYOUT.offset("cameraPosition") + 12, FrameBlock.LAYOUT.offset("eyeAltitude"));
    }
}
