package net.vesu.weather;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockGlass;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockSign;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One weather particle definition: where, when and how often it spawns, how it moves and how
 * it looks. {@link #BUILT_IN} is the fixed set Vesu ships, in spawn iteration order.
 */
final class WeatherDef {

    enum Style { CUSTOM, REGISTERED, BLOCK_MODEL, STREAK }

    enum SpawnPos { SKY, BLOCK_SIDES, BLOCK_BOTTOM, BLOCK_TOP, WORLD_SURFACE }

    enum Fade { IN_AND_OUT, OUT, DISTANCE }

    enum RenderType { OPAQUE, TRANSLUCENT, BLENDED }

    enum Tint { WATER, FOG, MAP, NONE }

    enum Rotation { COPY_CAMERA, RELATIVE_VELOCITY, WORLD_VELOCITY, VERTICAL, HORIZONTAL }

    enum Precip { NONE, RAIN, SNOW }

    /** Weather a definition needs, and the level its alpha is scaled by. */
    enum Weather {
        DURING_WEATHER, ONLY_DURING_NORMAL_WEATHER, ONLY_DURING_STORMY_WEATHER, AFTER_WEATHER, CLEAR, ALWAYS;

        boolean current(float rain, float thunder, int afterWeather) {
            return switch (this) {
                case DURING_WEATHER -> rain > 0.0f;
                case ONLY_DURING_NORMAL_WEATHER -> rain > 0.0f && thunder == 0.0f;
                case ONLY_DURING_STORMY_WEATHER -> thunder > 0.0f && rain > 0.9f;
                case AFTER_WEATHER -> afterWeather > 0;
                case CLEAR -> rain == 0.0f;
                case ALWAYS -> true;
            };
        }

        float level(float rain, float thunder) {
            return switch (this) {
                case DURING_WEATHER, ONLY_DURING_NORMAL_WEATHER -> rain;
                case ONLY_DURING_STORMY_WEATHER -> thunder;
                default -> 1.0f;
            };
        }
    }

    final String id;
    Style style = Style.CUSTOM;
    /** Vanilla particle id for REGISTERED. */
    int registeredParticle = -1;
    float density = 1.0f;
    Weather weather = Weather.DURING_WEATHER;
    Set<Precip> precipitation = EnumSet.of(Precip.RAIN);
    Predicate<Biome> biomes = b -> true;
    Predicate<IBlockState> blocks = s -> true;
    boolean needsSkyAccess = true;
    SpawnPos spawnPos = SpawnPos.SKY;
    float windStrength = 0.1f, stormWindStrength = 0.5f;
    float gravity = 0.1f;
    float rotationAmount = 0.0f;
    float bounciness = 0.0f;
    int lifetime = 3000;
    float opacity = 1.0f;
    Fade fade = Fade.DISTANCE;
    float size = 0.5f;
    float distanceScaling = 0.0f;
    RenderType renderType = RenderType.TRANSLUCENT;
    boolean animateSprites = false;
    /** Sprite names in the weather sprite set; for the dust types the particles.png cells instead. */
    String[] sprites = {};
    boolean genericSprites = false;
    Tint tint = Tint.NONE;
    Rotation rotation = Rotation.COPY_CAMERA;

    private WeatherDef(String id) {
        this.id = id;
    }

    // --- 1.12 equivalents of the block and biome groups the definitions name ---

    static boolean overworldBiome(Biome b) {
        return !BiomeDictionary.hasType(b, BiomeDictionary.Type.NETHER) && !BiomeDictionary.hasType(b, BiomeDictionary.Type.END)
                && !BiomeDictionary.hasType(b, BiomeDictionary.Type.VOID);
    }

    /** Sand-sounding blocks, terracotta, sandstone, coarse dirt. */
    static boolean sandy(IBlockState s) {
        Block b = s.getBlock();
        if (b.getSoundType() == SoundType.SAND && b != Blocks.SOUL_SAND) return true;
        if (b == Blocks.HARDENED_CLAY || b == Blocks.STAINED_HARDENED_CLAY) return true;
        if (b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE) return true;
        return b == Blocks.DIRT && s.getValue(BlockDirt.VARIANT) == BlockDirt.DirtType.COARSE_DIRT;
    }

    /** Natural ground: dirt-like blocks and the overworld stones. */
    static boolean ground(IBlockState s) {
        Block b = s.getBlock();
        return b == Blocks.DIRT || b == Blocks.GRASS || b == Blocks.MYCELIUM || b == Blocks.FARMLAND || b == Blocks.GRASS_PATH
                || b == Blocks.STONE || b == Blocks.GRAVEL || b == Blocks.CLAY;
    }

    static boolean water(IBlockState s) {
        return s.getMaterial() == Material.WATER;
    }

    static boolean wetBiome(Biome b) {
        return BiomeDictionary.hasType(b, BiomeDictionary.Type.WET) || BiomeDictionary.hasType(b, BiomeDictionary.Type.SPOOKY)
                || BiomeDictionary.hasType(b, BiomeDictionary.Type.SWAMP);
    }

    /** Plains and the windswept (extreme hills / shattered savanna) biomes. */
    static boolean plainsOrWindswept(Biome b) {
        net.minecraft.util.ResourceLocation id = b.getRegistryName();
        if (id == null) return false;
        String p = id.getPath();
        return p.equals("plains") || p.equals("mutated_plains") || p.contains("extreme_hills")
                || p.equals("mutated_savanna") || p.equals("mutated_savanna_rock");
    }

    /** Glass, panes and pickaxe-mined blocks: the walls streaks run down. */
    static boolean streakWall(IBlockState s) {
        Block b = s.getBlock();
        if (b instanceof BlockGlass || b instanceof BlockStainedGlass || b instanceof BlockPane) return true;
        String tool = b.getHarvestTool(s);
        if ("pickaxe".equals(tool)) return true;
        Material m = s.getMaterial();
        return m == Material.ROCK || m == Material.IRON || m == Material.ANVIL;
    }

    static boolean hotBase(IBlockState s) {
        Block b = s.getBlock();
        return s.getMaterial() == Material.LAVA || b == Blocks.NETHERRACK || b == Blocks.MAGMA;
    }

    /** Blocks the weather falls through: fences, signs, barriers. */
    static boolean weatherIgnored(IBlockState s) {
        Block b = s.getBlock();
        return b instanceof BlockFence || b instanceof BlockSign || b == Blocks.BARRIER;
    }

    private static String[] numbered(String prefix, int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = prefix + i;
        return out;
    }

    static final List<WeatherDef> BUILT_IN = new ArrayList<>();

    private static WeatherDef def(String id) {
        WeatherDef d = new WeatherDef(id);
        BUILT_IN.add(d);
        return d;
    }

    static {
        WeatherDef d;

        d = def("rain");
        d.gravity = 0.9f; d.windStrength = 0.4f; d.stormWindStrength = 0.8f; d.size = 0.75f;
        d.sprites = numbered("rain_", 16); d.tint = Tint.WATER; d.rotation = Rotation.RELATIVE_VELOCITY;

        d = def("heavy_rain");
        d.gravity = 1.0f; d.weather = Weather.ONLY_DURING_STORMY_WEATHER; d.windStrength = 0.4f; d.stormWindStrength = 0.8f;
        d.size = 0.75f; d.sprites = numbered("heavy_rain_", 4); d.tint = Tint.WATER; d.rotation = Rotation.RELATIVE_VELOCITY;

        d = def("snow");
        d.density = 0.3f; d.precipitation = EnumSet.of(Precip.SNOW); d.gravity = 0.05f; d.windStrength = 0.01f;
        d.stormWindStrength = 0.1f; d.rotationAmount = 0.4f; d.size = 0.75f; d.sprites = numbered("snow_", 16);

        d = def("heavy_snow");
        d.density = 0.4f; d.precipitation = EnumSet.of(Precip.SNOW); d.weather = Weather.ONLY_DURING_STORMY_WEATHER;
        d.gravity = 0.1f; d.windStrength = 0.01f; d.stormWindStrength = 0.1f; d.rotationAmount = 0.4f; d.size = 0.75f;
        d.sprites = numbered("heavy_snow_", 4);

        d = def("dust");
        d.precipitation = EnumSet.of(Precip.NONE); d.blocks = WeatherDef::sandy; d.biomes = WeatherDef::overworldBiome;
        d.gravity = 0.1f; d.windStrength = 0.7f; d.stormWindStrength = 1.0f; d.rotationAmount = 0.1f; d.size = 0.25f;
        d.genericSprites = true; d.tint = Tint.MAP;

        d = def("skittering_dust");
        d.density = 0.2f; d.precipitation = EnumSet.of(Precip.NONE); d.blocks = WeatherDef::sandy;
        d.biomes = WeatherDef::overworldBiome; d.spawnPos = SpawnPos.BLOCK_TOP; d.gravity = 0.5f; d.windStrength = 0.8f;
        d.stormWindStrength = 1.0f; d.bounciness = 0.15f; d.rotationAmount = 1.0f; d.lifetime = 60; d.size = 0.2f;
        d.renderType = RenderType.OPAQUE; d.genericSprites = true; d.tint = Tint.MAP;

        d = def("splatter");
        d.style = Style.REGISTERED; d.registeredParticle = net.minecraft.util.EnumParticleTypes.WATER_DROP.getParticleID();
        d.blocks = s -> s.getMaterial() != Material.LAVA; d.spawnPos = SpawnPos.BLOCK_TOP;
        d.windStrength = 0.1f; d.stormWindStrength = 0.2f;

        d = def("ripple");
        d.gravity = 0.0f; d.windStrength = 0.0f; d.stormWindStrength = 0.0f; d.blocks = WeatherDef::water;
        d.spawnPos = SpawnPos.BLOCK_TOP; d.animateSprites = true; d.sprites = numbered("ripple_", 8);
        d.rotation = Rotation.HORIZONTAL; d.size = 0.25f; d.fade = Fade.OUT; d.lifetime = 7; d.tint = Tint.WATER;

        d = def("smoke");
        d.style = Style.REGISTERED; d.registeredParticle = net.minecraft.util.EnumParticleTypes.SMOKE_NORMAL.getParticleID();
        d.blocks = WeatherDef::hotBase; d.spawnPos = SpawnPos.BLOCK_TOP;

        d = def("streak");
        d.style = Style.STREAK; d.density = 0.2f; d.blocks = WeatherDef::streakWall; d.spawnPos = SpawnPos.BLOCK_SIDES;

        d = def("shrub");
        d.style = Style.BLOCK_MODEL; d.density = 0.01f; d.bounciness = 0.15f; d.gravity = 0.5f; d.windStrength = 0.4f;
        d.rotationAmount = 1.0f; d.size = 1.0f; d.precipitation = EnumSet.of(Precip.NONE); d.blocks = WeatherDef::sandy;
        d.biomes = WeatherDef::overworldBiome; d.spawnPos = SpawnPos.WORLD_SURFACE;

        d = def("fog_after_rain");
        fog(d, false); d.weather = Weather.AFTER_WEATHER; d.biomes = b -> !plainsOrWindswept(b);

        d = def("vertical_fog_after_rain");
        fog(d, true); d.weather = Weather.AFTER_WEATHER; d.biomes = b -> !plainsOrWindswept(b);

        d = def("fog");
        fog(d, false); d.weather = Weather.ALWAYS; d.biomes = WeatherDef::wetBiome;

        d = def("vertical_fog");
        fog(d, true); d.weather = Weather.ALWAYS; d.biomes = WeatherDef::wetBiome;

        d = def("dust_haze");
        d.lifetime = 100; d.gravity = 0.1f; d.windStrength = 0.5f; d.stormWindStrength = 0.8f; d.rotationAmount = 0.1f;
        d.opacity = 0.4f; d.size = 0.4f; d.distanceScaling = 1.0f; d.sprites = new String[]{"fog_dithered"};
        d.precipitation = EnumSet.of(Precip.NONE); d.blocks = WeatherDef::sandy; d.biomes = WeatherDef::overworldBiome;
        d.spawnPos = SpawnPos.WORLD_SURFACE; d.bounciness = 0.15f; d.tint = Tint.MAP;
    }

    private static void fog(WeatherDef d, boolean vertical) {
        d.density = 0.5f; d.gravity = 0.0f; d.windStrength = 0.0f; d.stormWindStrength = 0.0f;
        d.blocks = WeatherDef::ground; d.spawnPos = SpawnPos.WORLD_SURFACE;
        d.sprites = new String[]{vertical ? "horizontal_fog" : "fog_translucent"};
        d.rotation = vertical ? Rotation.VERTICAL : Rotation.HORIZONTAL; d.renderType = RenderType.BLENDED;
        d.size = 2.0f; d.distanceScaling = 0.1f; d.fade = Fade.IN_AND_OUT; d.lifetime = 250; d.tint = Tint.FOG;
    }
}
