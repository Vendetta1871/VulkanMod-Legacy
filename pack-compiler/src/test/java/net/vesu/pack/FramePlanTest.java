package net.vesu.pack;

import com.google.gson.JsonParser;
import net.vesu.pack.ir.IrPack;
import net.vesu.pack.plan.FramePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FramePlanTest {

    private static FramePlan plan(String json) {
        return FramePlan.build(IrPack.parse("test", JsonParser.parseString(json).getAsJsonObject()));
    }

    private static final String TEXTURES = """
            "textures": [ { "name": "a" }, { "name": "b", "scale": 0.5 }, { "name": "h", "history": true } ],
            """;

    @Test
    void hdrSceneIsOptIn() {
        String combination = "\"combination\": { \"fragment\": \"c.fsh\" }";
        assertFalse(plan("{ \"format\": 1, " + combination + " }").hdrScene);
        assertFalse(plan("{ \"format\": 1, \"renderer\": { \"hdr\": false }, " + combination + " }").hdrScene);
        assertTrue(plan("{ \"format\": 1, \"renderer\": { \"hdr\": true }, " + combination + " }").hdrScene);
    }

    @Test
    void pingPongAlternatesAndSettles() {
        FramePlan p = plan("{ \"format\": 1, " + TEXTURES + """
                "commandLists": [ { "stage": "POST_SCENE", "passes": [
                    { "name": "w",  "fragment": "w.fsh",  "inputs": ["sceneColor"], "targets": ["a"] },
                    { "name": "f1", "fragment": "f.fsh",  "inputs": ["a"], "targets": ["a"] },
                    { "name": "f2", "compute":  "f.csh",  "inputs": ["a"], "writes": ["a"] },
                    { "name": "r",  "fragment": "r.fsh",  "inputs": ["a"], "targets": ["b"] } ] } ],
                "combination": { "fragment": "c.fsh", "inputs": ["a", "b"] } }""");
        assertEquals(2, p.imageCount[0], "a is read while written");
        assertEquals(1, p.imageCount[1]);
        List<FramePlan.Step> s = p.steps;
        assertEquals(new FramePlan.ImageRef(0, 0), s.get(0).outputs().get(0));
        assertEquals(new FramePlan.ImageRef(0, 0), s.get(1).inputs().get(0).image());
        assertEquals(new FramePlan.ImageRef(0, 1), s.get(1).outputs().get(0));
        assertEquals(new FramePlan.ImageRef(0, 1), s.get(2).inputs().get(0).image());
        assertEquals(new FramePlan.ImageRef(0, 0), s.get(2).outputs().get(0));
        assertEquals(new FramePlan.ImageRef(0, 0), s.get(3).inputs().get(0).image());
        assertTrue(s.get(4).combination());
        assertEquals(new FramePlan.ImageRef(0, 0), s.get(4).inputs().get(0).image());
        assertEquals(0, p.finalImage[0]);
        assertEquals(List.of("sceneColor"), List.copyOf(p.hostInputs));
        assertFalse(p.combinationReadsScene);
    }

    @Test
    void historyReadsThePrevImage() {
        FramePlan p = plan("{ \"format\": 1, " + TEXTURES + """
                "commandLists": [ { "passes": [
                    { "name": "t", "fragment": "t.fsh", "inputs": ["sceneColor", "hPrev"], "targets": ["h"] } ] } ],
                "combination": { "fragment": "c.fsh", "inputs": ["h", "sceneColor"] } }""");
        assertEquals(new FramePlan.ImageRef(2, FramePlan.PREV), p.steps.get(0).inputs().get(1).image());
        assertTrue(p.hasHistory(2));
        assertTrue(p.combinationReadsScene);
    }

    @Test
    void shadowCompRunsInThePrologue() {
        FramePlan p = plan("{ \"format\": 1, " + TEXTURES + """
                "commandLists": [ { "stage": "SHADOW_COMP", "passes": [ { "fragment": "d.fsh", "targets": ["a"] } ] } ],
                "combination": { "fragment": "c.fsh" } }""");
        assertEquals(2, p.steps.size());
        assertEquals(1, p.prologueSteps);
        assertTrue(p.warnings.isEmpty());
    }

    @Test
    void rejectsWhatTheRuntimeCannotRun() {
        String err = assertThrows(IllegalArgumentException.class, () -> plan("{ \"format\": 1, " + TEXTURES + """
                "commandLists": [ { "passes": [ { "fragment": "x.fsh", "targets": ["a", "b"] } ] } ],
                "combination": { "fragment": "c.fsh" } }""")).getMessage();
        assertTrue(err.contains("differ in size"), err);
        err = assertThrows(IllegalArgumentException.class, () -> plan("{ \"format\": 1, " + TEXTURES + """
                "combination": { "fragment": "c.fsh", "inputs": ["shadowtex0"] } }""")).getMessage();
        assertTrue(err.contains("needs a \"shadow\" section"), err);
        err = assertThrows(IllegalArgumentException.class, () -> plan("{ \"format\": 1, \"textures\": [] }"))
                .getMessage();
        assertTrue(err.contains("combination"), err);
    }
}
