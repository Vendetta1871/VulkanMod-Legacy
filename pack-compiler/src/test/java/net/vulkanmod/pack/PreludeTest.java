package net.vulkanmod.pack;

import com.google.gson.JsonParser;
import net.vulkanmod.pack.host.Prelude;
import net.vulkanmod.pack.ir.IrPack;
import net.vulkanmod.pack.plan.FramePlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreludeTest {

    private static final FramePlan PLAN = FramePlan.build(IrPack.parse("test", JsonParser.parseString("""
            { "format": 1,
              "textures": [ { "name": "bloom", "format": "RGBA16F" }, { "name": "ids", "format": "R32UI" } ],
              "commandLists": [ { "passes": [
                  { "name": "down", "compute": "d.csh", "inputs": ["sceneColor", "ids"], "writes": ["bloom"],
                    "workGroups": { "local": [16, 4] } } ] } ],
              "combination": { "fragment": "c.fsh", "inputs": ["sceneColor", "bloom"] } }""").getAsJsonObject()));

    @Test
    void computePassDeclaresInputsWritesAndLocalSize() {
        String p = Prelude.forStep(PLAN.pack, PLAN.steps.get(0));
        assertTrue(p.contains("uniform vkm_FrameBlock {"));
        assertTrue(p.contains("layout(set = 0, binding = 1) uniform sampler2D sceneColor;"));
        assertTrue(p.contains("layout(set = 0, binding = 2) uniform usampler2D ids;"));
        assertTrue(p.contains("layout(set = 0, binding = 3, rgba16f) uniform image2D bloom;"));
        assertTrue(p.contains("layout(local_size_x = 16, local_size_y = 4, local_size_z = 1) in;"));
        assertFalse(p.contains("vkm_texCoord"));
    }

    @Test
    void readWhileWriteStorageImageGetsTheOutSuffix() {
        FramePlan plan = FramePlan.build(IrPack.parse("test", JsonParser.parseString("""
                { "format": 1, "textures": [ { "name": "b" } ],
                  "commandLists": [ { "passes": [ { "compute": "x.csh", "inputs": ["b"], "writes": ["b"] } ] } ],
                  "combination": { "fragment": "c.fsh", "inputs": ["b"] } }""").getAsJsonObject()));
        String p = Prelude.forStep(plan.pack, plan.steps.get(0));
        assertTrue(p.contains("uniform sampler2D b;"));
        assertTrue(p.contains("uniform image2D bOut;"));
    }

    @Test
    void combinationFlipsFragCoord() {
        String p = Prelude.forStep(PLAN.pack, PLAN.steps.get(1));
        assertTrue(p.contains("layout(location = 0) in vec2 vkm_texCoord;"));
        assertTrue(p.contains("#define vkm_FragCoord vec4(gl_FragCoord.x, vkm_passSize.y - gl_FragCoord.y"));
        assertEquals(3, Prelude.writeBinding(2, 0));
    }
}
