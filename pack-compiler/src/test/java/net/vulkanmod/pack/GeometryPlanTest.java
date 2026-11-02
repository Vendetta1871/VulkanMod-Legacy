package net.vulkanmod.pack;

import com.google.gson.JsonParser;
import net.vulkanmod.pack.host.GeometryPrelude;
import net.vulkanmod.pack.ir.IrPack;
import net.vulkanmod.pack.plan.FramePlan;
import net.vulkanmod.pack.plan.GeometryCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryPlanTest {

    private static FramePlan plan(String body) {
        return FramePlan.build(IrPack.parse("test", JsonParser.parseString(
                "{ \"format\": 1, \"combination\": { \"fragment\": \"c.fsh\" }, " + body + " }").getAsJsonObject()));
    }

    private static final String G = "\"vertex\": \"v.vsh\", \"fragment\": \"f.fsh\"";
    private static final String TEX = """
            "textures": [ { "name": "albedo", "format": "RGBA8" }, { "name": "normal" }, { "name": "ids", "format": "R32UI" },
                          { "name": "half", "scale": 0.5 } ],
            """;

    @Test
    void categoriesFollowTheFallbackChains() {
        FramePlan p = plan(TEX + "\"gbuffers\": { \"textured_lit\": {" + G + ", \"targets\": [\"albedo\"] },"
                + " \"terrain\": {" + G + ", \"targets\": [\"albedo\", \"normal\"] } }");
        assertEquals("terrain", p.routes.get(GeometryCategory.TERRAIN).name);
        assertEquals("terrain", p.routes.get(GeometryCategory.WATER).name);
        assertEquals("terrain", p.routes.get(GeometryCategory.BLOCK).name);
        assertEquals("textured_lit", p.routes.get(GeometryCategory.ENTITIES).name);
        assertEquals("textured_lit", p.routes.get(GeometryCategory.HAND).name);
        assertNull(p.routes.get(GeometryCategory.SKY_BASIC), "no chain entry: vanilla shader");
        assertNull(p.routes.get(GeometryCategory.LINES));
    }

    @Test
    void attachmentsAreTheUnionWithTheSceneFirst() {
        FramePlan p = plan(TEX + "\"gbuffers\": { \"terrain\": {" + G + ", \"targets\": [\"normal\", \"sceneColor\"] },"
                + " \"water\": {" + G + ", \"targets\": [\"albedo\", \"normal\"] } }");
        assertEquals(java.util.List.of("normal", "albedo"), p.geometryTargets);
        assertEquals(0, p.attachment("sceneColor"));
        assertEquals(1, p.attachment("normal"));
        assertEquals(2, p.attachment("albedo"));
    }

    @Test
    void translucentGeometryFollowsTheDeferredPingPong() {
        FramePlan p = plan(TEX + """
                "gbuffers": { "terrain": { "vertex": "v", "fragment": "f", "targets": ["albedo"] },
                              "water": { "vertex": "v", "fragment": "f", "targets": ["albedo"], "inputs": ["normal"] } },
                "commandLists": [
                  { "stage": "DEFERRED", "passes": [ { "fragment": "d.fsh", "inputs": ["albedo", "sceneDepth"], "targets": ["albedo"] } ] },
                  { "stage": "POST_SCENE", "passes": [ { "fragment": "p.fsh", "inputs": ["albedo"], "targets": ["normal"] } ] } ]
                """);
        assertEquals(1, p.deferredSteps);
        assertEquals(0, p.geometryImages[FramePlan.OPAQUE][0]);
        assertEquals(1, p.geometryImages[FramePlan.TRANSLUCENT][0]);
        assertEquals(new FramePlan.ImageRef(0, 1), p.steps.get(1).inputs().get(0).image(), "post reads what water drew");
    }

    @Test
    void depthCopyOnlyWhenRead() {
        assertFalse(plan(TEX + "\"gbuffers\": { \"water\": {" + G + ", \"targets\": [\"albedo\"] } }").depthCopy);
        assertTrue(plan(TEX + "\"gbuffers\": { \"water\": {" + G + ", \"targets\": [\"albedo\"], \"inputs\": [\"depthtex1\"] } }").depthCopy);
    }

    @Test
    void extendedAttributesFollowTheSlot() {
        FramePlan p = plan("\"requires\": { \"vertex\": [\"blockId\", \"normal\"] }, \"gbuffers\": { \"terrain\": {" + G
                + ", \"targets\": [\"sceneColor\"] } }");
        assertEquals(FramePlan.VERTEX_NORMAL | FramePlan.VERTEX_BLOCK, p.vertexFlags);
        String batched = GeometryPrelude.vertex(p, p.pack.gbuffers.values().iterator().next(), "layout(location = 0) in vec3 Position;\nlayout(location = 4) in float ChunkSlot;\n");
        assertTrue(batched.contains("layout(location = 5) in vec4 vkm_normal;"));
        assertTrue(batched.contains("layout(location = 6) in uvec2 vkm_block;"), "stream order, not request order");
        assertTrue(batched.contains("#define at_tangent vec4(1.0, 0.0, 0.0, 1.0)"));
        String plain = GeometryPrelude.vertex(p, p.pack.gbuffers.values().iterator().next(), "layout(location = 0) in vec3 Position;\n");
        assertTrue(plain.contains("#define mc_Entity vec4(-1.0, 0.0, 0.0, 0.0)"), "only the arena has them");
        assertThrows(IllegalArgumentException.class, () -> plan("\"requires\": { \"vertex\": [\"lightmap\"] }"));
    }

    @Test
    void rejectsWhatCannotRun() {
        assertThrows(IllegalArgumentException.class, () -> plan("\"gbuffers\": { \"gbuffers_terrain\": {" + G + ", \"targets\": [\"sceneColor\"] } }"));
        assertThrows(IllegalArgumentException.class, () -> plan(TEX + "\"gbuffers\": { \"terrain\": {" + G + ", \"targets\": [\"half\"] } }"));
        assertEquals(java.util.List.of(0), plan(TEX + "\"gbuffers\": { \"terrain\": {" + G + ", \"targets\": [\"albedo\"], \"inputs\": [\"albedo\"] } }").snapshots);
    }

    @Test
    void preludeUsesTheVanillaInterfaceInSetOne() {
        FramePlan p = plan(TEX + "\"gbuffers\": { \"terrain\": {" + G + ", \"targets\": [\"albedo\", \"ids\"], \"inputs\": [\"normal\"] } }");
        String vsh = """
                layout(location = 0) in vec3 Position;
                layout(location = 1) in vec4 Color;
                layout(location = 4) in float ChunkSlot;
                layout(binding = 0) uniform UniformBufferObject {
                    mat4 MVP;
                };
                struct ChunkMatrix { mat4 mvp; mat4 modelView; };
                layout(std430, binding = 4) readonly buffer ChunkMatrices {
                    ChunkMatrix chunks[];
                };
                """;
        String v = GeometryPrelude.vertex(p, p.pack.gbuffers.values().iterator().next(), vsh);
        assertTrue(v.contains("layout(location = 0) in vec3 vaPosition;"));
        assertTrue(v.contains("layout(location = 1) in vec4 vaColor;"));
        assertTrue(v.contains("layout(location = 4) in float vkm_chunkSlot;"));
        assertTrue(v.contains("#define vaUV0 vec2(0.0)"), "missing attributes are constants");
        assertTrue(v.contains("layout(set = 1, binding = 0) uniform UniformBufferObject {"));
        assertTrue(v.contains("layout(std430, set = 1, binding = 4) readonly buffer ChunkMatrices {"));
        assertTrue(v.contains("#define vkm_mvp chunks[int(vkm_chunkSlot)].mvp"));
        assertTrue(v.contains("layout(set = 0, binding = 1) uniform sampler2D normal;"));

        String f = GeometryPrelude.fragment(p, p.pack.gbuffers.get("terrain"), """
                layout(binding = 1) uniform UniformBufferObject { float AlphaFunc; float AlphaRef; };
                layout(binding = 2) uniform sampler2D Sampler0;
                layout(binding = 3) uniform sampler2D Sampler2;
                """);
        assertTrue(f.contains("layout(set = 1, binding = 2) uniform sampler2D gtexture;"));
        assertTrue(f.contains("layout(set = 1, binding = 3) uniform sampler2D lightmap;"));
        assertTrue(f.contains("layout(location = 1) out vec4 vkmOut_albedo;"));
        assertTrue(f.contains("layout(location = 2) out uvec4 vkmOut_ids;"));
        assertTrue(f.contains("void vkm_alphaTest"));
    }

    @Test
    void shadowProgramGetsItsOwnMatricesAndTargets() {
        String json = """
                {"format": 1, "textures": [{"name": "a", "format": "RGBA8"}],
                 "shadow": {"resolution": 512, "hardwareFiltering": true},
                 "gbuffers": {"terrain": {"vertex": "v", "fragment": "f", "targets": ["a"], "inputs": ["shadowtex0"]},
                              "shadow": {"vertex": "v", "fragment": "f", "targets": ["shadowcolor1"]}},
                 "combination": {"fragment": "f", "inputs": ["a", "shadowcolor0", "shadowtex1"]}}""";
        FramePlan p = FramePlan.build(net.vulkanmod.pack.ir.IrPack.parse("t", com.google.gson.JsonParser.parseString(json).getAsJsonObject()));
        assertEquals(java.util.List.of("shadowcolor0", "shadowcolor1"), p.shadowTargets);
        assertTrue(p.shadowDepthCopy);
        assertTrue(p.hasGeometry());
        assertEquals(1, p.attachment("shadowcolor1"));
        String v = GeometryPrelude.vertex(p, p.pack.gbuffers.get("shadow"),
                "layout(location = 0) in vec3 Position;\nlayout(location = 4) in float ChunkSlot;\n");
        assertTrue(v.contains("int(vkm_chunkSlot) + 16384"));
        String f = GeometryPrelude.fragment(p, p.pack.gbuffers.get("shadow"), "");
        assertTrue(f.contains("layout(location = 1) out vec4 vkmOut_shadowcolor1;"));
        assertTrue(f.contains("uniform sampler2DShadow shadowtex0;"));
    }

    @Test
    void shadowTexturesNeedAShadowSection() {
        String json = """
                {"format": 1, "textures": [{"name": "a", "format": "RGBA8"}],
                 "combination": {"fragment": "f", "inputs": ["shadowtex0"]}}""";
        assertThrows(IllegalArgumentException.class, () -> FramePlan.build(
                net.vulkanmod.pack.ir.IrPack.parse("t", com.google.gson.JsonParser.parseString(json).getAsJsonObject())));
    }

    /** A list that decides attachments must iterate the same in every JVM, or the SPIR-V cache misses on each launch. */
    @org.junit.jupiter.api.Test
    void programsHaveAFixedOrder() {
        org.junit.jupiter.api.Assertions.assertEquals("basic", net.vulkanmod.pack.plan.GeometryCategory.PROGRAMS.iterator().next());
    }
}
