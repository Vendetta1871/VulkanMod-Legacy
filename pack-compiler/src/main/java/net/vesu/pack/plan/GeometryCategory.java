package net.vesu.pack.plan;

import net.vesu.pack.ir.IrPack;

import java.util.Map;
import java.util.Set;

/**
 * What a draw is, for program routing (runtime.md, "Draw categories"). Each category has a
 * fallback chain of program names, the OptiFine G5 program backups; the first program the
 * pack defines is used. A category with none draws with the vanilla shader into the scene color.
 */
public enum GeometryCategory {
    SKY_BASIC("skybasic", "basic"),
    SKY_TEXTURED("skytextured", "textured", "basic"),
    CLOUDS("clouds", "textured", "basic"),
    TERRAIN("terrain", "textured_lit", "textured", "basic"),
    WATER("water", "terrain", "textured_lit", "textured", "basic"),
    ENTITIES("entities", "textured_lit", "textured", "basic"),
    BLOCK("block", "terrain", "textured_lit", "textured", "basic"),
    ARMOR_GLINT("armor_glint", "textured", "basic"),
    PARTICLES("textured", "basic"),
    WEATHER("weather", "textured_lit", "textured", "basic"),
    WORLD_BORDER("textured_lit", "textured", "basic"),
    LINES("basic"),
    HAND("hand", "textured_lit", "textured", "basic"),
    HAND_WATER("hand_water", "hand", "textured_lit", "textured", "basic"),
    /** The shadow map: runs in place of terrain and entity shaders, in its own pass (runtime.md). */
    SHADOW("shadow"),
    DAMAGED_BLOCK("damagedblock", "terrain", "textured_lit", "textured", "basic"),
    ENTITIES_GLOWING("entities_glowing", "entities", "textured_lit", "textured", "basic"),
    SPIDER_EYES("spidereyes", "textured", "basic"),
    BEACON_BEAM("beaconbeam", "textured", "basic"),
    ENTITIES_TRANSLUCENT("entities_translucent", "entities", "textured_lit", "textured", "basic"),
    /** Distant terrain (AUDIT 5.16, contract B): Iris' Distant Horizons programs. Drawn by the LOD renderer. */
    LOD_OPAQUE("dh_terrain"),
    LOD_TRANSLUCENT("dh_water");

    /** Every program name a pack may define. */
    /** In a fixed order: it decides the attachments, and {@code Set.of} iterates differently in each JVM, which defeats the SPIR-V cache. */
    public static final Set<String> PROGRAMS = new java.util.LinkedHashSet<>(java.util.List.of("basic", "textured", "textured_lit", "skybasic", "skytextured",
            "clouds", "terrain", "damagedblock", "water", "block", "beaconbeam", "entities", "entities_glowing", "entities_translucent",
            "armor_glint", "spidereyes", "hand", "hand_water", "weather", "shadow", "dh_terrain", "dh_water"));

    /** The program file of a program name: {@code gbuffers_<name>}, except the shadow and DH programs. */
    public static String fileName(String name) {
        return name.equals("shadow") || name.startsWith("dh_") ? name : "gbuffers_" + name;
    }

    /** Drawn by the LOD renderer, not by a vanilla pipeline. */
    public boolean isLod() {
        return this == LOD_OPAQUE || this == LOD_TRANSLUCENT;
    }

    private final String[] chain;

    GeometryCategory(String... chain) {
        this.chain = chain;
    }

    /** The first program of the chain the pack defines, or null. */
    public IrPack.Gbuffer resolve(Map<String, IrPack.Gbuffer> programs) {
        for (String name : chain) {
            IrPack.Gbuffer g = programs.get(name);
            if (g != null) return g;
        }
        return null;
    }
}
