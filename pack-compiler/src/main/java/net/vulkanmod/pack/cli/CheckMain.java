package net.vulkanmod.pack.cli;

import net.vulkanmod.pack.PackKind;
import net.vulkanmod.pack.host.GeometryPrelude;
import net.vulkanmod.pack.host.Prelude;
import net.vulkanmod.pack.ir.IrPack;
import net.vulkanmod.pack.optifine.OptiFineFrontend;
import net.vulkanmod.pack.plan.FramePlan;
import net.vulkanmod.pack.plan.GeometryCategory;
import net.vulkanmod.pack.source.IncludeExpander;
import net.vulkanmod.pack.source.PackSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code vkpack check}: lowers an OptiFine pack and writes every program as the runtime would
 * compile it, one file per stage, for an offline compile with glslangValidator.
 * Usage: {@code CheckMain <pack dir> <vanilla shaders dir> <out dir> [NAME=VALUE...]}.
 */
public final class CheckMain {

    /** The vanilla shaders each category draws with, as {@code PackRuntime.categories} routes them. */
    private static final Map<GeometryCategory, String[]> ROUTES = new java.util.EnumMap<>(GeometryCategory.class);

    static {
        String[] model = {"entity", "entity_item", "terrain"};
        ROUTES.put(GeometryCategory.TERRAIN, new String[]{"terrain_batch"});
        ROUTES.put(GeometryCategory.WATER, new String[]{"terrain_batch"});
        ROUTES.put(GeometryCategory.SKY_BASIC, new String[]{"sky", "sky_vcol"});
        ROUTES.put(GeometryCategory.SKY_TEXTURED, new String[]{"sky_tex"});
        ROUTES.put(GeometryCategory.CLOUDS, new String[]{"clouds"});
        ROUTES.put(GeometryCategory.WORLD_BORDER, new String[]{"clouds"});
        ROUTES.put(GeometryCategory.ENTITIES, model);
        ROUTES.put(GeometryCategory.BLOCK, model);
        ROUTES.put(GeometryCategory.HAND, model);
        ROUTES.put(GeometryCategory.HAND_WATER, model);
        ROUTES.put(GeometryCategory.ARMOR_GLINT, new String[]{"entity_flat"});
        ROUTES.put(GeometryCategory.PARTICLES, new String[]{"entity_particle"});
        ROUTES.put(GeometryCategory.WEATHER, new String[]{"entity_particle"});
        ROUTES.put(GeometryCategory.LINES, new String[]{"debug_lines"});
        ROUTES.put(GeometryCategory.SHADOW, new String[]{"terrain_batch", "entity", "entity_item", "terrain"});
    }

    public static void main(String[] args) throws Exception {
        Path pack = Path.of(args[0]), vanilla = Path.of(args[1]), out = Path.of(args[2]);
        Map<String, String> chosen = new HashMap<>();
        for (int i = 3; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            chosen.put(kv[0], kv[1]);
        }
        Files.createDirectories(out);
        try (PackSource src = PackSource.directory(pack)) {
            PackKind kind = PackKind.detect(src);
            IrPack lowered;
            Map<String, String> programs;
            List<String> warnings;
            if (kind == PackKind.OPTIFINE) {
                var r = OptiFineFrontend.lower(src, pack.getFileName().toString(), chosen,
                        Map.of("MC_OS_LINUX", "", "MC_GL_VENDOR_NVIDIA", "", "MC_GL_RENDERER_GEFORCE", ""));
                lowered = r.pack();
                programs = r.programs();
                warnings = r.warnings();
            } else {
                throw new IllegalArgumentException("not an OptiFine pack");
            }
            new java.util.TreeSet<>(warnings).forEach(w -> System.out.println("warning: " + w));
            List<IrPack> variants = new ArrayList<>(lowered.scenes.values());
            variants.add(0, lowered);
            int n = 0;
            for (IrPack variant : variants) {
                FramePlan plan = FramePlan.build(variant);
                plan.warnings.forEach(w -> System.out.println("plan: " + w));
                PackSource files = PackSource.overlay(src, programs);
                IncludeExpander ex = new IncludeExpander(files);
                String tag = variant == lowered ? "world" : "v" + variants.indexOf(variant);
                for (FramePlan.Step s : plan.steps) {
                    String name = tag + "." + s.pass().name.replace('/', '_');
                    if (s.pass().isCompute()) {
                        Files.writeString(out.resolve(name + ".comp"), ex.expandProgram(s.pass().compute, Prelude.forStep(variant, s)));
                        continue;
                    }
                    Files.writeString(out.resolve(name + ".frag"), ex.expandProgram(s.pass().fragment, Prelude.forStep(variant, s)));
                    if (s.pass().vertex != null) {
                        Files.writeString(out.resolve(name + ".vert"), ex.expandProgram(s.pass().vertex, Prelude.forVertex(variant, s)));
                    }
                    n++;
                }
                for (Map.Entry<GeometryCategory, IrPack.Gbuffer> e : plan.routes.entrySet()) {
                    IrPack.Gbuffer g = e.getValue();
                    for (String shader : ROUTES.getOrDefault(e.getKey(), new String[0])) {
                        String vsh = Files.readString(vanilla.resolve(shader + ".vsh")), fsh = Files.readString(vanilla.resolve(shader + ".fsh"));
                        String name = tag + "." + e.getKey().name().toLowerCase() + "." + shader;
                        Files.writeString(out.resolve(name + ".vert"), ex.expandProgram(g.vertex, GeometryPrelude.vertex(plan, g, vsh)));
                        Files.writeString(out.resolve(name + ".frag"), ex.expandProgram(g.fragment, GeometryPrelude.fragment(plan, g, fsh)));
                        if (g.geometry != null) Files.writeString(out.resolve(name + ".geom"), ex.expandProgram(g.geometry, GeometryPrelude.stage(plan, g, fsh)));
                        if (g.tessControl != null) {
                            Files.writeString(out.resolve(name + ".tesc"), ex.expandProgram(g.tessControl, GeometryPrelude.stage(plan, g, fsh)));
                            Files.writeString(out.resolve(name + ".tese"), ex.expandProgram(g.tessEval, GeometryPrelude.stage(plan, g, fsh)));
                        }
                        n++;
                    }
                }
            }
            System.out.println(n + " programs written; textures " + lowered.textures.size() + ", vertex " + lowered.requiredVertex);
        }
    }
}
