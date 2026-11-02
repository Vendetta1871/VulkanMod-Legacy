package net.vulkanmod.shader;

import net.minecraft.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * Block names of 1.13 and later for a 1.12 registry. An Iris pack is written for a flattened
 * game: {@code red_stained_glass}, {@code redstone_torch:lit=false}, {@code oak_leaves}. On 1.12
 * these are a variant of another block or a separate block, so without a translation the
 * entries tag nothing and, for example, every stained glass keeps the id of the white one.
 */
final class LegacyBlockNames {

    /** Name that does not exist on 1.12 -> legacy tokens (space separated), each may carry selectors. */
    private static final Map<String, String> NAMES = new HashMap<>();
    /** {@code name:property=value} of a state that is a separate block on 1.12 -> legacy tokens. */
    private static final Map<String, String> STATES = new HashMap<>();

    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};

    static {
        for (String color : COLORS) {
            String legacy = color.equals("light_gray") ? "silver" : color;
            NAMES.put(color + "_wool", "wool:color=" + legacy);
            NAMES.put(color + "_stained_glass", "stained_glass:color=" + legacy);
            NAMES.put(color + "_stained_glass_pane", "stained_glass_pane:color=" + legacy);
            NAMES.put(color + "_terracotta", "stained_hardened_clay:color=" + legacy);
            NAMES.put(color + "_concrete", "concrete:color=" + legacy);
            NAMES.put(color + "_concrete_powder", "concrete_powder:color=" + legacy);
            NAMES.put(color + "_carpet", "carpet:color=" + legacy);
            NAMES.put(color + "_bed", "bed");
            NAMES.put(color + "_banner", "standing_banner");
            NAMES.put(color + "_wall_banner", "wall_banner");
        }
        NAMES.put("light_gray_glazed_terracotta", "silver_glazed_terracotta");
        NAMES.put("light_gray_shulker_box", "silver_shulker_box");
        NAMES.put("terracotta", "hardened_clay");

        for (String wood : WOODS) {
            boolean second = wood.equals("acacia") || wood.equals("dark_oak");
            NAMES.put(wood + "_planks", "planks:variant=" + wood);
            NAMES.put(wood + "_sapling", "sapling:type=" + wood);
            NAMES.put(wood + "_log", (second ? "log2" : "log") + ":variant=" + wood + ":axis=x,y,z");
            NAMES.put(wood + "_wood", (second ? "log2" : "log") + ":variant=" + wood + ":axis=none");
            NAMES.put(wood + "_leaves", (second ? "leaves2" : "leaves") + ":variant=" + wood);
            NAMES.put(wood + "_slab", "wooden_slab:variant=" + wood + " double_wooden_slab:variant=" + wood);
        }
        NAMES.put("oak_fence", "fence");
        NAMES.put("oak_fence_gate", "fence_gate");
        NAMES.put("oak_door", "wooden_door");
        NAMES.put("oak_trapdoor", "trapdoor");
        NAMES.put("oak_pressure_plate", "wooden_pressure_plate");
        NAMES.put("oak_button", "wooden_button");
        NAMES.put("oak_sign", "standing_sign");
        NAMES.put("sign", "standing_sign");
        NAMES.put("oak_wall_sign", "wall_sign");

        NAMES.put("granite", "stone:variant=granite");
        NAMES.put("polished_granite", "stone:variant=smooth_granite");
        NAMES.put("diorite", "stone:variant=diorite");
        NAMES.put("polished_diorite", "stone:variant=smooth_diorite");
        NAMES.put("andesite", "stone:variant=andesite");
        NAMES.put("polished_andesite", "stone:variant=smooth_andesite");
        NAMES.put("coarse_dirt", "dirt:variant=coarse_dirt");
        NAMES.put("podzol", "dirt:variant=podzol");
        NAMES.put("grass_block", "grass");
        NAMES.put("dirt_path", "grass_path");
        NAMES.put("red_sand", "sand:variant=red_sand");
        NAMES.put("chiseled_sandstone", "sandstone:type=chiseled_sandstone");
        NAMES.put("cut_sandstone", "sandstone:type=smooth_sandstone");
        NAMES.put("chiseled_red_sandstone", "red_sandstone:type=chiseled_red_sandstone");
        NAMES.put("cut_red_sandstone", "red_sandstone:type=smooth_red_sandstone");
        NAMES.put("stone_bricks", "stonebrick:variant=stonebrick");
        NAMES.put("mossy_stone_bricks", "stonebrick:variant=mossy_stonebrick");
        NAMES.put("cracked_stone_bricks", "stonebrick:variant=cracked_stonebrick");
        NAMES.put("chiseled_stone_bricks", "stonebrick:variant=chiseled_stonebrick");
        NAMES.put("infested_stone", "monster_egg:variant=stone");
        NAMES.put("infested_cobblestone", "monster_egg:variant=cobblestone");
        NAMES.put("infested_stone_bricks", "monster_egg:variant=stone_brick");
        NAMES.put("infested_mossy_stone_bricks", "monster_egg:variant=mossy_brick");
        NAMES.put("infested_cracked_stone_bricks", "monster_egg:variant=cracked_brick");
        NAMES.put("infested_chiseled_stone_bricks", "monster_egg:variant=chiseled_brick");
        NAMES.put("bricks", "brick_block");
        NAMES.put("nether_bricks", "nether_brick");
        NAMES.put("red_nether_bricks", "red_nether_brick");
        NAMES.put("end_stone_bricks", "end_bricks");
        NAMES.put("prismarine_bricks", "prismarine:variant=prismarine_bricks");
        NAMES.put("dark_prismarine", "prismarine:variant=dark_prismarine");
        NAMES.put("chiseled_quartz_block", "quartz_block:variant=chiseled");
        NAMES.put("quartz_pillar", "quartz_block:variant=lines_x,lines_y,lines_z");
        NAMES.put("cobblestone_wall", "cobblestone_wall:variant=cobblestone");
        NAMES.put("mossy_cobblestone_wall", "cobblestone_wall:variant=mossy_cobblestone");
        NAMES.put("wet_sponge", "sponge:wet=true");
        NAMES.put("magma_block", "magma");
        NAMES.put("nether_quartz_ore", "quartz_ore");
        NAMES.put("snow_block", "snow");
        NAMES.put("melon", "melon_block");
        NAMES.put("slime_block", "slime");
        NAMES.put("note_block", "noteblock");
        NAMES.put("spawner", "mob_spawner");
        NAMES.put("nether_portal", "portal");
        NAMES.put("jack_o_lantern", "lit_pumpkin");
        NAMES.put("carved_pumpkin", "pumpkin");
        NAMES.put("powered_rail", "golden_rail");
        NAMES.put("moving_piston", "piston_extension");
        NAMES.put("wall_torch", "torch:facing=north,south,east,west");
        NAMES.put("redstone_wall_torch", "redstone_torch:facing=north,south,east,west unlit_redstone_torch:facing=north,south,east,west");
        NAMES.put("repeater", "unpowered_repeater powered_repeater");
        NAMES.put("comparator", "unpowered_comparator powered_comparator");

        NAMES.put("short_grass", "tallgrass:type=tall_grass");
        NAMES.put("fern", "tallgrass:type=fern");
        NAMES.put("dead_bush", "deadbush");
        NAMES.put("sugar_cane", "reeds");
        NAMES.put("cobweb", "web");
        NAMES.put("lily_pad", "waterlily");
        NAMES.put("dandelion", "yellow_flower");
        NAMES.put("poppy", "red_flower:type=poppy");
        NAMES.put("blue_orchid", "red_flower:type=blue_orchid");
        NAMES.put("allium", "red_flower:type=allium");
        NAMES.put("azure_bluet", "red_flower:type=houstonia");
        NAMES.put("red_tulip", "red_flower:type=red_tulip");
        NAMES.put("orange_tulip", "red_flower:type=orange_tulip");
        NAMES.put("white_tulip", "red_flower:type=white_tulip");
        NAMES.put("pink_tulip", "red_flower:type=pink_tulip");
        NAMES.put("oxeye_daisy", "red_flower:type=oxeye_daisy");
        NAMES.put("sunflower", "double_plant:variant=sunflower");
        NAMES.put("lilac", "double_plant:variant=syringa");
        NAMES.put("tall_grass", "double_plant:variant=double_grass");
        NAMES.put("large_fern", "double_plant:variant=double_fern");
        NAMES.put("rose_bush", "double_plant:variant=double_rose");
        NAMES.put("peony", "double_plant:variant=paeonia");

        STATES.put("furnace:lit=true", "lit_furnace");
        STATES.put("furnace:lit=false", "furnace");
        STATES.put("redstone_ore:lit=true", "lit_redstone_ore");
        STATES.put("redstone_ore:lit=false", "redstone_ore");
        STATES.put("redstone_lamp:lit=true", "lit_redstone_lamp");
        STATES.put("redstone_lamp:lit=false", "redstone_lamp");
        STATES.put("redstone_torch:lit=true", "redstone_torch");
        STATES.put("redstone_torch:lit=false", "unlit_redstone_torch");
        STATES.put("redstone_wall_torch:lit=true", "redstone_torch:facing=north,south,east,west");
        STATES.put("redstone_wall_torch:lit=false", "unlit_redstone_torch:facing=north,south,east,west");
        STATES.put("repeater:powered=true", "powered_repeater");
        STATES.put("repeater:powered=false", "unpowered_repeater");
        STATES.put("comparator:powered=true", "powered_comparator");
        STATES.put("comparator:powered=false", "unpowered_comparator");
        STATES.put("daylight_detector:inverted=true", "daylight_detector_inverted");
        STATES.put("daylight_detector:inverted=false", "daylight_detector");
    }

    private LegacyBlockNames() {
    }

    /**
     * The 1.12 tokens for a {@code block.properties} token of a flattened game, or null when the
     * token resolves as written (or is not a vanilla name). Selectors the translation does not
     * consume stay on every resulting token.
     */
    static String[] translate(String token) {
        String rest = token.startsWith("minecraft:") ? token.substring("minecraft:".length()) : token;
        String[] parts = rest.split(":");
        String name = parts[0];
        if (name.isEmpty() || name.matches("\\d+") || parts.length > 1 && !parts[1].contains("=") && !parts[1].matches("[\\d,]+")) {
            return null; // a numeric id, or a domain other than minecraft
        }
        String base = null;
        StringBuilder kept = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            String state = base == null ? STATES.get(name + ":" + parts[i]) : null;
            if (state != null) {
                base = state;
            } else {
                kept.append(':').append(parts[i]);
            }
        }
        if (base == null) {
            if (Block.getBlockFromName(name) != null) return null;
            base = NAMES.get(name);
            if (base == null) return null;
        }
        String[] tokens = base.split(" ");
        for (int i = 0; i < tokens.length; i++) tokens[i] += kept;
        return tokens;
    }
}
