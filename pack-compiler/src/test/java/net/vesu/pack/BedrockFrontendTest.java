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

    private String params() throws IOException {
        try (PackSource src = PackSource.directory(dir)) {
            return BedrockFrontend.lower(src, "test").programs().entrySet().stream()
                    .filter(e -> e.getKey().endsWith("params.fsh")).findFirst().orElseThrow().getValue();
        }
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
}
