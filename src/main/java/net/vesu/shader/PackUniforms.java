package net.vesu.shader;

import net.vesu.Env;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.vesu.Initializer;
import net.vesu.pack.expr.Expression;
import net.vesu.pack.host.FrameBlock;
import net.vesu.pack.host.Std140Layout;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * The custom uniforms of a pack ({@code uniform.*}, {@code variable.*}): compiled once, evaluated
 * in file order once per frame, into the {@code vkm_packUniforms} slots of the Frame block.
 */
public final class PackUniforms {

    private static final boolean DUMP = Env.get("VESU_FRAME_DUMP") != null;
    private int dumpFrames;

    private record Item(String name, Expression expression, double[] value, int slot) {
    }

    /** A value from the Frame block. */
    private record FrameBinding(double[] value, Std140Layout.Field field) {
    }

    private record Computed(double[] value, DoubleSupplier source) {
    }

    private final List<Item> items = new ArrayList<>();
    private final Map<String, double[]> variables = new HashMap<>();
    private final List<FrameBinding> frame = new ArrayList<>();
    private final List<Computed> computed = new ArrayList<>();
    private final double[] frameTime = new double[1];

    public PackUniforms(JsonArray list) {
        for (JsonElement e : list) {
            JsonObject u = e.getAsJsonObject();
            String name = u.get("name").getAsString();
            // By declared size: a vec3(a, b, c) of scalars flattens its arguments.
            double[] value = new double[componentsOf(u.has("type") ? u.get("type").getAsString() : "vec4")];
            Expression expression;
            try {
                expression = Expression.parse(u.get("expr").getAsString(), this::bind, frameTime);
            } catch (RuntimeException ex) {
                Initializer.LOGGER.warn("[shaderpacks] uniform {} stays 0: {}", name, ex.getMessage());
                variables.put(name, value);
                continue;
            }
            items.add(new Item(name, expression, value, u.has("slot") ? u.get("slot").getAsInt() : -1));
            variables.put(name, value);
        }
    }

    private double[] bind(String name) {
        double[] known = variables.get(name);
        if (known != null) return known;
        for (Std140Layout.Field f : FrameBlock.LAYOUT.fields()) {
            if (f.name().equals(name)) {
                double[] value = new double[components(f)];
                frame.add(new FrameBinding(value, f));
                variables.put(name, value);
                return value;
            }
        }
        double[] value = new double[1];
        Integer constant = constant(name);
        if (constant != null) {
            value[0] = constant;
            variables.put(name, value);
            return value;
        }
        DoubleSupplier source = supplier(name);
        if (source == null) {
            // As in OptiFine: the uniform is dropped, the shader sees 0, whatever else it computes.
            throw new IllegalArgumentException("unknown variable " + name);
        }
        variables.put(name, value);
        computed.add(new Computed(value, source));
        return value;
    }

    private static final List<String> CATEGORIES = List.of("NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS",
            "SAVANNA", "ICY", "THE_END", "BEACH", "FOREST", "OCEAN", "DESERT", "RIVER", "SWAMP", "MUSHROOM", "NETHER");
    private static final List<String> PRECIPITATION = List.of("NONE", "RAIN", "SNOW");

    private static Integer constant(String name) {
        if (name.startsWith("BIOME_")) return biomeId(name.substring(6).toLowerCase());
        int index = -1;
        if (name.startsWith("CAT_")) index = CATEGORIES.indexOf(name.substring(4));
        else if (name.startsWith("PPT_")) index = PRECIPITATION.indexOf(name.substring(4));
        return index >= 0 ? index : null;
    }

    /** {@code biome_category} of newer OptiFine and Iris, from the Forge biome dictionary. */
    private static String category(Biome biome) {
        if (hasType(biome, BiomeDictionary.Type.NETHER)) return "NETHER";
        if (hasType(biome, BiomeDictionary.Type.END)) return "THE_END";
        if (hasType(biome, BiomeDictionary.Type.MUSHROOM)) return "MUSHROOM";
        if (hasType(biome, BiomeDictionary.Type.BEACH)) return "BEACH";
        if (hasType(biome, BiomeDictionary.Type.RIVER)) return "RIVER";
        if (hasType(biome, BiomeDictionary.Type.OCEAN)) return "OCEAN";
        if (hasType(biome, BiomeDictionary.Type.SWAMP)) return "SWAMP";
        if (hasType(biome, BiomeDictionary.Type.JUNGLE)) return "JUNGLE";
        if (hasType(biome, BiomeDictionary.Type.MESA)) return "MESA";
        if (hasType(biome, BiomeDictionary.Type.SAVANNA)) return "SAVANNA";
        if (hasType(biome, BiomeDictionary.Type.SANDY)) return "DESERT";
        if (hasType(biome, BiomeDictionary.Type.CONIFEROUS)) return "TAIGA";
        if (hasType(biome, BiomeDictionary.Type.SNOWY)) return "ICY";
        if (hasType(biome, BiomeDictionary.Type.MOUNTAIN)) return "EXTREME_HILLS";
        if (hasType(biome, BiomeDictionary.Type.FOREST)) return "FOREST";
        if (hasType(biome, BiomeDictionary.Type.PLAINS)) return "PLAINS";
        return "NONE";
    }

    private static boolean hasType(Biome biome, BiomeDictionary.Type type) {
        return BiomeDictionary.hasType(biome, type);
    }

    private static int componentsOf(String type) {
        return switch (type) {
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            default -> 1;
        };
    }

    private static int components(Std140Layout.Field f) {
        return switch (f.type().glsl) {
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            case "mat4" -> 16;
            default -> 1;
        };
    }

    private static int biomeId(String name) {
        for (Biome b : Biome.REGISTRY) {
            if (b.getRegistryName() != null && b.getRegistryName().getPath().equals(name)) return Biome.getIdForBiome(b);
        }
        return -1;
    }

    private static Entity viewer() {
        return Minecraft.getMinecraft().getRenderViewEntity();
    }

    private static Biome biome() {
        Entity e = viewer();
        return e == null ? null : e.world.getBiome(new BlockPos(e));
    }

    private static double flag(boolean b) {
        return b ? 1.0 : 0.0;
    }

    private static DoubleSupplier supplier(String name) {
        return switch (name) {
            case "biome_category" -> () -> biome() == null ? 0 : CATEGORIES.indexOf(category(biome()));
            case "biome_precipitation" -> () -> biome() == null ? 0
                    : biome().getEnableSnow() ? 2 : biome().canRain() ? 1 : 0;
            case "biome" -> () -> biome() == null ? 0 : Biome.getIdForBiome(biome());
            case "temperature" -> () -> biome() == null ? 0 : biome().getDefaultTemperature();
            case "rainfall" -> () -> biome() == null ? 0 : biome().getRainfall();
            case "is_on_ground" -> () -> flag(viewer() != null && viewer().onGround);
            case "is_sneaking" -> () -> flag(viewer() != null && viewer().isSneaking());
            case "is_sprinting" -> () -> flag(viewer() != null && viewer().isSprinting());
            case "is_in_water" -> () -> flag(viewer() != null && viewer().isInWater());
            case "is_in_lava" -> () -> flag(viewer() != null && viewer().isInLava());
            case "is_burning" -> () -> flag(viewer() != null && viewer().isBurning());
            case "is_invisible" -> () -> flag(viewer() != null && viewer().isInvisible());
            case "is_alive" -> () -> flag(viewer() != null && viewer().isEntityAlive());
            case "is_hurt" -> () -> flag(viewer() instanceof EntityLivingBase l && l.hurtTime > 0);
            case "is_riding" -> () -> flag(viewer() != null && viewer().isRiding());
            case "is_wet" -> () -> flag(viewer() != null && viewer().isWet());
            default -> null;
        };
    }

    /** Evaluates everything and writes the exposed values at {@code address}, 16 bytes per slot. */
    public void write(long address) {
        ByteBuffer data = FrameUniforms.data();
        for (FrameBinding b : frame) {
            int offset = b.field.offset();
            boolean integer = b.field.type().glsl.startsWith("i");
            if (b.value.length == 16) {
                for (int i = 0; i < 16; i++) b.value[i] = data.getFloat(offset + i * 4);
            } else {
                for (int i = 0; i < b.value.length; i++) {
                    b.value[i] = integer ? data.getInt(offset + i * 4) : data.getFloat(offset + i * 4);
                }
            }
        }
        frameTime[0] = data.getFloat(FrameBlock.LAYOUT.offset("frameTime"));
        for (Computed c : computed) c.value[0] = c.source.getAsDouble();
        for (Item item : items) {
            double[] r = item.expression.eval();
            for (int i = 0; i < item.value.length; i++) item.value[i] = i < r.length ? r[i] : r.length == 1 ? r[0] : 0.0;
            if (item.slot >= 0) {
                long at = address + item.slot * 16L;
                for (int i = 0; i < 4; i++) {
                    MemoryUtil.memPutFloat(at + i * 4L, (float) item.value[i < item.value.length ? i : 0]);
                }
            }
        }
        if (DUMP && dumpFrames++ % 240 == 120) {
            for (Item item : items) {
                Initializer.LOGGER.info("[pack-uniform] {} = {}", item.name, java.util.Arrays.toString(item.value));
            }
        }
    }
}
