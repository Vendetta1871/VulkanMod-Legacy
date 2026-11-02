package net.vulkanmod.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.pack.ir.IrPack;
import net.vulkanmod.pack.ir.TextureFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IrPackTest {

    private static IrPack parse(String json) {
        return IrPack.parse("test", JsonParser.parseString(json).getAsJsonObject());
    }

    private static String error(String json) {
        return assertThrows(IllegalArgumentException.class, () -> parse(json)).getMessage();
    }

    @Test
    void minimalPostPack() {
        IrPack p = parse("""
                { "format": 1, "name": "Bloom",
                  "textures": [ { "name": "bloom", "format": "RGBA16F", "scale": 0.5, "mipmaps": true },
                                { "name": "taa", "history": true, "clear": [1, 0, 0, 1] } ],
                  "commandLists": [ { "stage": "POST_SCENE", "passes": [
                      { "name": "down", "compute": "programs/down.csh", "inputs": ["sceneColor"], "writes": ["bloom"],
                        "workGroups": { "scale": 0.5, "local": [8, 8] } },
                      { "fragment": "programs/taa.fsh", "inputs": ["sceneColor", "taaPrev", "taa"], "targets": ["taa"],
                        "mipmaps": [] } ] } ],
                  "combination": { "fragment": "programs/final.fsh", "inputs": ["taa", "bloom"] } }""");
        assertEquals("Bloom", p.name);
        assertEquals(TextureFormat.RGBA16F, p.texture("bloom").format);
        assertNull(p.texture("taa").clearColor, "history textures are never cleared");
        assertEquals(2, p.passes(IrPack.Stage.POST_SCENE).size());
        assertTrue(p.passes(IrPack.Stage.DEFERRED).isEmpty());
        IrPack.Pass taa = p.passes(IrPack.Stage.POST_SCENE).get(1);
        assertEquals("programs/taa.fsh", taa.name);
        assertTrue(taa.readsWhileWriting("taa"));
        assertEquals(0.5f, p.passes(IrPack.Stage.POST_SCENE).get(0).workGroupScale);
        assertNotNull(p.combination);
    }

    @Test
    void formatIsRequiredAndVersioned() {
        assertTrue(error("{}").contains("no \"format\""));
        assertTrue(error("{\"format\": 2}").contains("newer"));
        assertTrue(error("{\"format\": 1.5}").contains("integer"));
    }

    @Test
    void reservedAndInvalidNames() {
        assertTrue(error("{\"format\":1,\"textures\":[{\"name\":\"sceneColor\"}]}").contains("reserved"));
        assertTrue(error("{\"format\":1,\"textures\":[{\"name\":\"vkm_x\"}]}").contains("reserved"));
        assertTrue(error("{\"format\":1,\"textures\":[{\"name\":\"aPrev\"}]}").contains("history"));
        assertTrue(error("{\"format\":1,\"textures\":[{\"name\":\"9x\"}]}").contains("invalid"));
        assertTrue(error("{\"format\":1,\"textures\":[{\"name\":\"a\"},{\"name\":\"a\"}]}").contains("duplicate"));
    }

    @Test
    void passValidation() {
        String tex = "\"textures\":[{\"name\":\"a\"}],";
        assertTrue(error("{\"format\":1," + tex + "\"commandLists\":[{\"stage\":\"LATER\",\"passes\":[]}]}")
                .contains("unknown command list stage"));
        assertTrue(error("{\"format\":1," + tex + "\"commandLists\":[{\"passes\":[{\"fragment\":\"f\",\"inputs\":[\"b\"]}]}]}")
                .contains("undeclared"));
        assertTrue(error("{\"format\":1," + tex + "\"commandLists\":[{\"passes\":[{\"fragment\":\"f\",\"targets\":[\"sceneColor\"]}]}]}")
                .contains("host texture"));
        assertTrue(error("{\"format\":1," + tex + "\"commandLists\":[{\"passes\":[{\"fragment\":\"f\",\"inputs\":[\"aPrev\"]}]}]}")
                .contains("undeclared"), "Prev needs history");
        assertTrue(error("{\"format\":1," + tex + "\"commandLists\":[{\"passes\":[{\"compute\":\"c\",\"targets\":[\"a\"]}]}]}")
                .contains("use writes"));
        assertTrue(error("{\"format\":1," + tex + "\"commandLists\":[{\"passes\":[{\"fragment\":\"f\",\"blend\":\"maybe\"}]}]}")
                .contains("blend"));
        assertTrue(error("{\"format\":1," + tex + "\"commandLists\":[{\"passes\":[{\"fragment\":\"f\",\"mipmaps\":[\"a\"]}]}]}")
                .contains("without mipmaps"));
        assertTrue(error("{\"format\":1," + tex + "\"combination\":{\"fragment\":\"f\",\"targets\":[\"a\"]}}")
                .contains("combination"));
    }

    @Test
    void computeMayOnlyWrite() {
        // A SETUP pass that generates a texture has no inputs.
        IrPack p = parse("{\"format\":1,\"textures\":[{\"name\":\"noise\",\"clear\":false}],"
                + "\"commandLists\":[{\"stage\":\"SETUP\",\"passes\":[{\"compute\":\"gen.csh\",\"writes\":[\"noise\"],"
                + "\"workGroups\":{\"fixed\":[16,16]}}]}]}");
        assertArrayEquals(new int[]{16, 16, 1}, p.passes(IrPack.Stage.SETUP).get(0).fixedGroups);
        assertNull(p.texture("noise").clearColor);
    }

    @Test
    void explicitBlend() {
        JsonObject o = JsonParser.parseString("{\"format\":1,\"textures\":[{\"name\":\"a\"}],\"commandLists\":[{\"passes\":["
                + "{\"fragment\":\"f\",\"targets\":[\"a\"],\"blend\":[\"ONE\",\"ONE\",\"ONE\",\"ZERO\"]}]}]}").getAsJsonObject();
        IrPack.Pass pass = IrPack.parse("t", o).passes(IrPack.Stage.POST_SCENE).get(0);
        assertTrue(pass.blend.enabled);
        assertEquals("ZERO", pass.blend.factors[3]);
    }
}
