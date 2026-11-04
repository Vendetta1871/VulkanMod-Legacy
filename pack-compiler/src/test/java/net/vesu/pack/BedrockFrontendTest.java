package net.vesu.pack;

import net.vesu.pack.bedrock.BedrockFrontend;
import net.vesu.pack.plan.GeometryCategory;
import net.vesu.pack.source.PackSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BedrockFrontendTest {

    @TempDir
    Path dir;

    private void write(String rel, String text) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private BedrockFrontend.Result lower() throws IOException {
        try (PackSource src = PackSource.directory(dir)) {
            return BedrockFrontend.lower(src, "test");
        }
    }

    private String program(String suffix) throws IOException {
        return lower().programs().entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix)).findFirst().orElseThrow().getValue();
    }

    private String params() throws IOException {
        return program("params.fsh");
    }

    private void pack(String biomeLights) throws IOException {
        write("manifest.json", "{ \"format_version\": 2, \"header\": { \"name\": \"t\", \"uuid\": \"00000000-0000-0000-0000-000000000000\", \"version\": [1, 0, 0] },"
                + " \"modules\": [ { \"type\": \"resources\", \"uuid\": \"00000000-0000-0000-0000-000000000001\", \"version\": [1, 0, 0] } ],"
                + " \"capabilities\": [\"pbr\"] }");
        write("lighting/global.json", "{ \"minecraft:lighting_settings\": { \"description\": { \"identifier\": \"minecraft:default_lighting\" },"
                + " \"ambient\": { \"illuminance\": 0.25 }, \"directional_lights\": { \"orbital\": { \"sun\": { \"illuminance\": 20.0 }, \"moon\": { \"illuminance\": 0.5 } } } } }");
        write("lighting/swamp.json", "{ \"minecraft:lighting_settings\": { \"description\": { \"identifier\": \"minecraft:swamp_lighting\" },"
                + " \"directional_lights\": " + biomeLights + " } }");
        write("biomes/swampland.client_biome.json", "{ \"minecraft:client_biome\": { \"components\": {"
                + " \"minecraft:lighting_identifier\": { \"lighting_identifier\": \"minecraft:swamp_lighting\" } } } }");
    }

    /** Most biome files of the game keep their sun directly under directional_lights: it must not read as no sun. */
    @Test
    void biomeLightingWithoutOrbitalKeepsItsSun() throws IOException {
        pack("{ \"sun\": { \"illuminance\": 77.0 }, \"moon\": { \"illuminance\": 0.25 } }");
        String params = params();
        assertTrue(params.contains("77.0"), "the biome's sun");
    }

    /** A list that decides attachments must iterate the same in every JVM, or the SPIR-V cache misses on each launch. */
    @Test
    void programsHaveAFixedOrder() {
        assertEquals("basic", GeometryCategory.PROGRAMS.iterator().next());
    }

    @Test
    void blockyShadowsAreTheDefault() throws IOException {
        pack("{ \"sun\": { \"illuminance\": 20.0 } }");
        assertTrue(program("water.fsh").contains("#define VV_SHADOW_BLOCKY"));
    }

    @Test
    void defaultBiomeAcceptsBlockComments() throws IOException {
        pack("{ \"sun\": { \"illuminance\": 20.0 } }");
        write("biomes_client.json", "/* vanilla header */\n{ \"biomes\": { \"default\": { \"water_surface_color\": \"#44AFF5\" } } }");
        String params = params();
        assertTrue(params.contains("case 20"), "atmosphere and temperature rows");
        assertTrue(params.contains("case 24"), "medium fog rows");
        assertTrue(params.contains("case 35"), "grading range thresholds");
    }

    @Test
    void gradingRangesAndHalfResolutionReflectionsArePlanned() throws IOException {
        pack("{ \"sun\": { \"illuminance\": 20.0 } }");
        write("color_grading/color_grading.json", "{ \"minecraft:color_grading_settings\": {"
                + " \"color_grading\": { \"midtones\": { \"contrast\": [1, 1, 1] },"
                + " \"highlights\": { \"contrast\": [2, 2, 2] }, \"shadows\": { \"contrast\": [0.5, 0.5, 0.5] },"
                + " \"highlightsMin\": 1.5, \"shadowsMax\": 0.4 }, \"tone_mapping\": { \"operator\": \"generic\" } } }");
        String params = params();
        assertTrue(params.contains("case 25"), "highlight grading");
        assertTrue(params.contains("case 30"), "shadow grading");
        assertTrue(params.contains("case 35"), "grading thresholds");
        var reflections = lower().pack().texture("reflections");
        assertNotNull(reflections);
        assertEquals(0.5f, reflections.scale, 1e-6f);
        assertTrue(reflections.history, "temporal accumulation");
    }

    @Test
    void localLightBuffersCoverTheWholeVolume() throws IOException {
        pack("{ \"sun\": { \"illuminance\": 20.0 } }");
        var resources = lower().pack().raw.getAsJsonObject("resources");
        long clusters = 0, active = 0, histogram = 0;
        for (var element : resources.getAsJsonArray("buffers")) {
            var buffer = element.getAsJsonObject();
            if (buffer.get("binding").getAsInt() == 1) clusters = buffer.get("size").getAsLong();
            if (buffer.get("binding").getAsInt() == 2) active = buffer.get("size").getAsLong();
            if (buffer.get("binding").getAsInt() == 3) histogram = buffer.get("size").getAsLong();
        }
        assertEquals(16 * 8 * 16 * 8 * Integer.BYTES, clusters);
        assertEquals(32 * 16 * 32, (active - Integer.BYTES) / Integer.BYTES);
        assertEquals(256 * Integer.BYTES, histogram);
    }
}
