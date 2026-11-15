#!/usr/bin/env python3
"""Converts NVIDIA's RTX Remix Materials pack (Minecraft 1.21.1, LabPBR _n/_s maps plus the mod's
_sss maps, CC BY 4.0) into a Minecraft 1.12 resource pack: textures/block -> textures/blocks
under the 1.12 names (the 1.13 flattening renamed most of them).

    tools/remix_materials.py RTX-Remix-Materials-<version>.zip <output dir>

Only block textures are converted (the RT passes read the block atlas); the 1.12 name list comes
from the vanilla client jar, so a texture that 1.12 does not have is reported and skipped.
"""
import io, json, os, sys, zipfile

COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "silver",
          "cyan", "purple", "blue", "brown", "green", "red", "black"]
WOODS = {"oak": "oak", "spruce": "spruce", "birch": "birch", "jungle": "jungle", "acacia": "acacia",
         "big_oak": "dark_oak"}

def new_color(c):
    return "light_gray" if c == "silver" else c

def rename_table():
    """1.12 texture name -> 1.21 texture name."""
    m = {}
    for c in COLORS:
        n = new_color(c)
        m["concrete_" + c] = n + "_concrete"
        m["concrete_powder_" + c] = n + "_concrete_powder"
        m["glass_" + c] = n + "_stained_glass"
        m["glass_pane_top_" + c] = n + "_stained_glass_pane_top"
        m["glazed_terracotta_" + c] = n + "_glazed_terracotta"
        m["hardened_clay_stained_" + c] = n + "_terracotta"
        m["shulker_top_" + c] = n + "_shulker_box"
        m["wool_colored_" + c] = n + "_wool"
    for old, new in WOODS.items():
        m["leaves_" + old] = new + "_leaves"
        m["log_" + old] = new + "_log"
        m["log_" + old + "_top"] = new + "_log_top"
        m["planks_" + old] = new + "_planks"
    for old, new in {"oak": "oak", "spruce": "spruce", "birch": "birch", "jungle": "jungle",
                     "acacia": "acacia", "roofed_oak": "dark_oak"}.items():
        m["sapling_" + old] = new + "_sapling"
    for old, new in {"wood": "oak", "iron": "iron", "spruce": "spruce", "birch": "birch",
                     "jungle": "jungle", "acacia": "acacia", "dark_oak": "dark_oak"}.items():
        m["door_%s_lower" % old] = new + "_door_bottom"
        m["door_%s_upper" % old] = new + "_door_top"
    for crop, stages in {"wheat": 8, "carrots": 4, "potatoes": 4, "beetroots": 4, "cocoa": 3, "nether_wart": 3}.items():
        for i in range(stages):
            m["%s_stage_%d" % (crop, i)] = "%s_stage%d" % (crop, i)
    for a, b in {"fern": "large_fern", "grass": "tall_grass", "paeonia": "peony", "rose": "rose_bush",
                 "syringa": "lilac", "sunflower": "sunflower"}.items():
        m["double_plant_%s_bottom" % a] = b + "_bottom"
        m["double_plant_%s_top" % a] = b + "_top"
    m["double_plant_sunflower_back"] = "sunflower_back"
    m["double_plant_sunflower_front"] = "sunflower_front"
    for a, b in {"allium": "allium", "blue_orchid": "blue_orchid", "dandelion": "dandelion",
                 "houstonia": "azure_bluet", "oxeye_daisy": "oxeye_daisy", "rose": "poppy",
                 "tulip_orange": "orange_tulip", "tulip_pink": "pink_tulip", "tulip_red": "red_tulip",
                 "tulip_white": "white_tulip"}.items():
        m["flower_" + a] = b
    for s in ("andesite", "diorite", "granite"):
        m["stone_" + s] = s
        m["stone_%s_smooth" % s] = "polished_" + s
    m.update({
        "anvil_base": "anvil", "anvil_top_damaged_0": "anvil_top", "anvil_top_damaged_1": "chipped_anvil_top",
        "anvil_top_damaged_2": "damaged_anvil_top", "brick": "bricks", "cobblestone_mossy": "mossy_cobblestone",
        "comparator_off": "comparator", "deadbush": "dead_bush", "dirt_podzol_side": "podzol_side",
        "dirt_podzol_top": "podzol_top", "dispenser_front_horizontal": "dispenser_front",
        "dropper_front_horizontal": "dropper_front", "end_bricks": "end_stone_bricks",
        "endframe_eye": "end_portal_frame_eye", "endframe_side": "end_portal_frame_side",
        "endframe_top": "end_portal_frame_top", "farmland_dry": "farmland", "farmland_wet": "farmland_moist",
        "fire_layer_0": "fire_0", "fire_layer_1": "fire_1", "furnace_front_off": "furnace_front",
        "grass_path_side": "dirt_path_side", "grass_path_top": "dirt_path_top", "grass_side": "grass_block_side",
        "grass_side_overlay": "grass_block_side_overlay", "grass_side_snowed": "grass_block_snow",
        "grass_top": "grass_block_top", "hardened_clay": "terracotta", "ice_packed": "packed_ice",
        "itemframe_background": "item_frame", "melon_stem_connected": "attached_melon_stem",
        "melon_stem_disconnected": "melon_stem", "pumpkin_stem_connected": "attached_pumpkin_stem",
        "pumpkin_stem_disconnected": "pumpkin_stem", "mob_spawner": "spawner",
        "mushroom_block_skin_brown": "brown_mushroom_block", "mushroom_block_skin_red": "red_mushroom_block",
        "mushroom_block_skin_stem": "mushroom_stem", "mushroom_brown": "brown_mushroom",
        "mushroom_red": "red_mushroom", "nether_brick": "nether_bricks", "noteblock": "note_block",
        "observer_back_lit": "observer_back_on", "piston_top_normal": "piston_top", "portal": "nether_portal",
        "prismarine_dark": "dark_prismarine", "prismarine_rough": "prismarine",
        "pumpkin_face_off": "carved_pumpkin", "pumpkin_face_on": "jack_o_lantern",
        "quartz_block_chiseled": "chiseled_quartz_block", "quartz_block_chiseled_top": "chiseled_quartz_block_top",
        "quartz_block_lines": "quartz_pillar", "quartz_block_lines_top": "quartz_pillar_top",
        "quartz_ore": "nether_quartz_ore", "rail_activator": "activator_rail",
        "rail_activator_powered": "activator_rail_on", "rail_detector": "detector_rail",
        "rail_detector_powered": "detector_rail_on", "rail_golden": "powered_rail",
        "rail_golden_powered": "powered_rail_on", "rail_normal": "rail", "rail_normal_turned": "rail_corner",
        "red_nether_brick": "red_nether_bricks", "red_sandstone_carved": "chiseled_red_sandstone",
        "red_sandstone_normal": "red_sandstone", "red_sandstone_smooth": "cut_red_sandstone",
        "sandstone_carved": "chiseled_sandstone", "sandstone_normal": "sandstone", "sandstone_smooth": "cut_sandstone",
        "redstone_lamp_off": "redstone_lamp", "redstone_torch_on": "redstone_torch", "reeds": "sugar_cane",
        "repeater_off": "repeater", "slime": "slime_block", "sponge_wet": "wet_sponge",
        "stone_slab_side": "smooth_stone_slab_side", "stone_slab_top": "smooth_stone",
        "stonebrick": "stone_bricks", "stonebrick_carved": "chiseled_stone_bricks",
        "stonebrick_cracked": "cracked_stone_bricks", "stonebrick_mossy": "mossy_stone_bricks",
        "tallgrass": "short_grass", "torch_on": "torch", "trip_wire": "tripwire", "trip_wire_source": "tripwire_hook",
        "waterlily": "lily_pad", "web": "cobweb", "trapdoor": "oak_trapdoor",
    })
    return m

def vanilla_names():
    jar = None
    for p in ("/storage/.gradle/caches/retro_futura_gradle/mc-vanilla/1.12.2/client.jar",
              os.path.expanduser("~/.gradle/caches/retro_futura_gradle/mc-vanilla/1.12.2/client.jar")):
        if os.path.isfile(p):
            jar = p
            break
    if jar is None:
        sys.exit("the 1.12.2 client jar was not found in the Gradle cache")
    prefix = "assets/minecraft/textures/blocks/"
    with zipfile.ZipFile(jar) as z:
        return sorted(n[len(prefix):-4] for n in z.namelist()
                      if n.startswith(prefix) and n.endswith(".png") and "/" not in n[len(prefix):])

def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, out = sys.argv[1], sys.argv[2]
    table = rename_table()
    prefix = "assets/minecraft/textures/block/"
    with zipfile.ZipFile(src) as z:
        names = set(z.namelist())
        dst = os.path.join(out, "assets/minecraft/textures/blocks")
        os.makedirs(dst, exist_ok=True)
        converted, missing = 0, []
        for old in vanilla_names():
            new = table.get(old, old)
            if prefix + new + ".png" not in names:
                missing.append(old)
                continue
            for suffix in ("", "_n", "_s", "_sss"):
                for ext in (".png", ".png.mcmeta"):
                    entry = prefix + new + suffix + ext
                    if entry in names:
                        with open(os.path.join(dst, old + suffix + ext), "wb") as f:
                            f.write(z.read(entry))
            converted += 1
        for extra in ("LICENSE.txt", "README.txt", "pack.png"):
            if extra in names:
                with open(os.path.join(out, extra), "wb") as f:
                    f.write(z.read(extra))
    with open(os.path.join(out, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"pack_format": 3, "description":
                   "RTX Remix Materials (NVIDIA, CC BY 4.0) for 1.12, converted for Vesu"}}, f, indent=2)
    print("%d block textures converted; %d without a pack texture: %s"
          % (converted, len(missing), " ".join(missing)))

if __name__ == "__main__":
    main()
