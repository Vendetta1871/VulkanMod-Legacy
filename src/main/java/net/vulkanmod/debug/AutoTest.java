package net.vulkanmod.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiLanguage;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.SkyRenderer;
import net.vulkanmod.vulkan.Drawer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;

/**
 * Headless-ish rendering autotest, modeled after the tacz-legacy one. With
 * {@code VULKANMOD_AUTOTEST=1} it opens a generated creative world from the main
 * menu, waits for the chunks to settle and saves Vulkan swapchain captures
 * ({@code vm_autotest_*.png} in the run directory) before quitting.
 *
 * <p>{@code VULKANMOD_AUTOTEST_WARMUP} sets the idle ticks before the first
 * capture; {@code VULKANMOD_AUTOTEST_STAY} keeps the game open afterwards.</p>
 */
public class AutoTest {

    public static final boolean ENABLED = System.getenv("VULKANMOD_AUTOTEST") != null;

    private static final int WARMUP = envInt("VULKANMOD_AUTOTEST_WARMUP", 120);

    /**
     * Water regression repro: with {@code VULKANMOD_AUTOTEST_WATER=1} the world logic is
     * replaced by a single check - find the nearest water block, frame it and capture, so
     * the translucent (water) chunk layer and its blend state are exercised.
     */
    private static final boolean WATER_MODE = System.getenv("VULKANMOD_AUTOTEST_WATER") != null;

    /** TEMP repro: noon + downpour framed on a water body, to compare weather-over-water. */
    private static final boolean RAINWATER_MODE = System.getenv("VULKANMOD_AUTOTEST_RAINWATER") != null;

    /**
     * Movement repro: with {@code VULKANMOD_AUTOTEST_FLYUP=1} the camera starts at the repro
     * perch and ascends a fixed step every tick (as if the player holds space to fly up),
     * capturing at several altitudes. A static teleport to the same height renders the weather
     * fine, so this isolates whether the reported "weather vanishes while flying up" is driven
     * by the per-tick position change rather than the absolute height.
     */
    private static final boolean FLYUP_MODE = System.getenv("VULKANMOD_AUTOTEST_FLYUP") != null;

    /**
     * Free-roam sandbox: with {@code VULKANMOD_AUTOTEST_FREE=1} the world is loaded, a full
     * downpour is forced, and the player is given flight — then the autotest does nothing else
     * (no captures, no time changes, no auto-exit). Used to reproduce a user-reported weather
     * bug by hand: fly to the spot, read the F3 coords, and check whether the "no weather" zone
     * is world-anchored (biome) or screen-anchored (render bug).
     */
    private static final boolean FREE_MODE = System.getenv("VULKANMOD_AUTOTEST_FREE") != null;

    /**
     * Sticky-cap regression test: with {@code VULKANMOD_AUTOTEST_STICKY=1} the player is parked at
     * a full-weather spot (A), moved to the low-altitude spot where the column count dips (B), then
     * returned to A (C). The old weather buffer left its limit at the previous frame's total, so a
     * frame whose weather was larger than the last overflowed that stale limit and got truncated -
     * pinning the weather at the lowest total seen (phase C stuck at B's count). With the limit
     * reset in {@code deferWeather}, phase C recovers to phase A's vertex count.
     */
    private static final boolean STICKY_MODE = System.getenv("VULKANMOD_AUTOTEST_STICKY") != null;

    /**
     * Window-resize regression test: with {@code VULKANMOD_AUTOTEST_RESIZE=1} the autotest drives
     * an SDL window resize and checks that {@code Minecraft.displayWidth/Height} follow it. VulkanMod
     * cancels {@code updateDisplay()}, which normally runs {@code checkWindowResize()}; without the
     * resize bookkeeping being redone, the size (and the open screen's scaled layout) go stale and
     * GUI buttons stop matching the cursor after a resize.
     */
    private static final boolean RESIZE_MODE = System.getenv("VULKANMOD_AUTOTEST_RESIZE") != null;

    /**
     * GUI regression repro: with {@code VULKANMOD_AUTOTEST_GUI=1} the title screen is
     * captured, then the Options screen (tiled dirt background) and the Language screen
     * (a scrolling list with a scrollbar) are opened and captured, then the game quits -
     * no world is loaded. Exercises the REPEAT texture addressing and the "texturing
     * disabled binds white" path.
     */
    private static final boolean GUI_MODE = System.getenv("VULKANMOD_AUTOTEST_GUI") != null;

    /**
     * Save folder to open instead of generating {@code vm_autotest}. Point it at a copy of a
     * real save to reproduce bugs that only show up in that terrain. The FPS bench uses its
     * own persistent {@code vm_autotest_bench}: a fresh world would measure chunk generation.
     */
    private static final String WORLD_NAME = System.getenv().getOrDefault("VULKANMOD_AUTOTEST_WORLD",
            System.getenv("VULKANMOD_AUTOTEST_FPS") != null ? "vm_autotest_bench" : "vm_autotest");
    /** True when this run had to generate the world, so FPS numbers include generation. */
    private static boolean worldGenerated;

    /**
     * Time-series repro: with {@code VULKANMOD_AUTOTEST_BURST=1} the world is entered exactly
     * as saved (time of day untouched) and a capture is taken roughly every second for five
     * seconds, to pin down when a rendering state changes after spawn.
     */
    private static final boolean BURST_MODE = System.getenv("VULKANMOD_AUTOTEST_BURST") != null;

    /** Player position captured on the first burst tick, to pin the camera still. */
    private static double[] pinPos;

    /**
     * Plant-placement repro: with {@code VULKANMOD_AUTOTEST_PLANT=1} a deterministic scene is
     * built next to the player (a stone pillar with a flower on top, a flower on a glass block
     * and a flower at the edge of a dug pit) and framed from the side, to measure whether
     * cross-model plants (grass/flowers) render at the top of their block or float above it.
     */
    private static final boolean PLANT_MODE = System.getenv("VULKANMOD_AUTOTEST_PLANT") != null;

    /**
     * Feature-gap probe: with {@code VULKANMOD_AUTOTEST_FEATURES=1} a deterministic scene of
     * render paths the port may not bracket yet is built next to the spawn: block entities
     * (chest, sign, furnace, enchanting table, spawner, skull, banner, torch), an item frame,
     * a dropped enchanted sword (glint), an end crystal, hotbar item icons incl. a filled map,
     * and worn armor. Captures: world view, third person, open inventory, first person again.
     * Run once with Vulkan and once with {@code VULKANMOD_DISABLE=1} and diff the pairs to see
     * exactly which render paths are missing under Vulkan.
     */
    private static final boolean FEATURES_MODE = System.getenv("VULKANMOD_AUTOTEST_FEATURES") != null;

    /**
     * Coordinate-driven repro: {@code VULKANMOD_AUTOTEST_GOTO="x y z"} teleports the camera a
     * few blocks from that block and looks at it, to reproduce a reported misplacement at a
     * known world position (the user reports grass floating one block up at specific blocks).
     */
    private static final String GOTO_SPEC = System.getenv("VULKANMOD_AUTOTEST_GOTO");

    /**
     * Exact-scene repro: {@code VULKANMOD_AUTOTEST_REPRO="x y z yaw pitch time"} places the
     * camera at the given position/rotation, pins the world time and captures one frame;
     * precipitation is forced only when {@code VULKANMOD_AUTOTEST_RAIN} is also set. Used to
     * reproduce a user-reported view pixel-for-pixel (run against a COPY of their world so
     * the real save is never touched).
     */
    private static final String REPRO_SPEC = System.getenv("VULKANMOD_AUTOTEST_REPRO");

    /**
     * Throughput benchmark: with {@code VULKANMOD_AUTOTEST_FPS=1} the framerate limiter and
     * vsync are disabled, the render distance is pinned, the camera is teleported (via
     * {@code VULKANMOD_AUTOTEST_GOTO}) and held perfectly still, and after the chunks settle
     * the debug FPS is sampled every tick for a fixed window. Reports avg/min/max so a
     * renderer change can be shown to be faster (or not) on an identical, static scene.
     *
     * <p>Tuning knobs: {@code VULKANMOD_AUTOTEST_RENDER} (render distance, default 8),
     * {@code _SETTLE} (ticks to let chunks build, default 250), {@code _MEASURE} (ticks to
     * sample, default 150).</p>
     */
    private static final boolean FPS_MODE = System.getenv("VULKANMOD_AUTOTEST_FPS") != null;

    /**
     * {@code VULKANMOD_AUTOTEST_RTTOGGLE=1}: in the world, switches the ray tracing preset the
     * way the Ray Tracing screen does: low (chunks reload, BLASes build), high, off; captures
     * after each step, then quits.
     */
    private static final boolean RT_TOGGLE_MODE = System.getenv("VULKANMOD_AUTOTEST_RTTOGGLE") != null;
    private static int rtToggleTicks;

    /**
     * {@code VULKANMOD_AUTOTEST_TORCH=1}: builds a closed stone room high in the air (no sky
     * light inside) with torches, glowstone and a pillar between them and the camera, moves
     * the player in and captures "torchRoom" twice (early and with the RT history settled).
     * Checks RT block light and its shadows; run with VULKANMOD_RT_BLOCK=0 for the vanilla look.
     */
    private static final boolean TORCH_MODE = System.getenv("VULKANMOD_AUTOTEST_TORCH") != null;
    private static int torchTicks;

    private static void torchStage(Minecraft mc) {
        int t = ++torchTicks;
        int bx = 200, by = 150, bz = 200;
        if (t == 1) {
            IntegratedServer server = mc.getIntegratedServer();
            if (server == null) return;
            server.addScheduledTask(() -> {
                net.minecraft.world.World world = server.getWorld(0);
                BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
                for (int dx = 0; dx <= 14; dx++)
                    for (int dy = 0; dy <= 7; dy++)
                        for (int dz = 0; dz <= 14; dz++) {
                            boolean shell = dx == 0 || dx == 14 || dy == 0 || dy == 7 || dz == 0 || dz == 14;
                            world.setBlockState(c.setPos(bx + dx, by + dy, bz + dz),
                                    shell ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                for (int dy = 1; dy <= 4; dy++) world.setBlockState(c.setPos(bx + 7, by + dy, bz + 6), Blocks.STONE.getDefaultState());
                world.setBlockState(c.setPos(bx + 7, by + 1, bz + 3), Blocks.TORCH.getDefaultState());
                world.setBlockState(c.setPos(bx + 3, by + 1, bz + 9), Blocks.TORCH.getDefaultState());
                world.setBlockState(c.setPos(bx + 12, by + 3, bz + 2), Blocks.GLOWSTONE.getDefaultState());
                world.setBlockState(c.setPos(bx + 2, by + 1, bz + 12), Blocks.CRAFTING_TABLE.getDefaultState());
                world.setBlockState(c.setPos(bx + 2, by + 1, bz + 3), Blocks.REDSTONE_BLOCK.getDefaultState());
                world.setBlockState(c.setPos(bx + 11, by + 1, bz + 9), Blocks.REDSTONE_TORCH.getDefaultState());
            });
            serverCommand(mc, "time set 18000");
        } else if (t == 5) {
            serverCommand(mc, String.format("tp %s %.1f %.1f %.1f 180 25", mc.player.getName(), bx + 7.5, by + 1.0, bz + 12.5));
        } else if (t == 60) {
            // A light placed in the loaded world (its light crosses the x = 208 section border).
            IntegratedServer server = mc.getIntegratedServer();
            if (server != null) server.addScheduledTask(() -> server.getWorld(0).setBlockState(
                    new BlockPos(bx + 9, by + 1, bz + 4), Blocks.TORCH.getDefaultState()));
        } else if (t == 100) {
            capture(mc, "torchRoomEarly");
        } else if (t == 160) {
            capture(mc, "torchRoom");
            // Break the torch behind the pillar: its light (and the pillar's shadows) must go.
            IntegratedServer server = mc.getIntegratedServer();
            if (server != null) server.addScheduledTask(() -> server.getWorld(0).setBlockToAir(
                    new BlockPos(bx + 7, by + 1, bz + 3)));
        } else if (t == 220) {
            capture(mc, "torchRoomBroken");
        } else if (t >= 230) {
            Initializer.LOGGER.info("[autotest] done, shutting down");
            mc.shutdown();
        }
    }

    /**
     * {@code VULKANMOD_AUTOTEST_MOBSHADOW=1}: a stone platform high in the air under a low
     * morning sun with a zombie and a cow (no AI) on a polished quartz floor; captures
     * "mobShadow". Checks ray-traced entity shadows (replacing the round drop shadows) and
     * entity reflections in the floor.
     */
    private static final boolean MOBSHADOW_MODE = System.getenv("VULKANMOD_AUTOTEST_MOBSHADOW") != null;
    private static int mobShadowTicks;

    private static void mobShadowStage(Minecraft mc) {
        int t = ++mobShadowTicks;
        int bx = 260, by = 150, bz = 200;
        if (t == 1) {
            IntegratedServer server = mc.getIntegratedServer();
            if (server == null) return;
            server.addScheduledTask(() -> {
                net.minecraft.world.World world = server.getWorld(0);
                BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
                for (int dx = 0; dx <= 16; dx++)
                    for (int dz = 0; dz <= 16; dz++)
                        // Polished floor: mobs reflect in it (RT entity hits) besides casting shadows.
                        world.setBlockState(c.setPos(bx + dx, by, bz + dz), Blocks.QUARTZ_BLOCK.getDefaultState());
                net.minecraft.entity.monster.EntityZombie zombie = new net.minecraft.entity.monster.EntityZombie(world);
                zombie.setLocationAndAngles(bx + 8.5, by + 1, bz + 7.5, 90.0f, 0.0f);
                zombie.setNoAI(true);
                world.spawnEntity(zombie);
                net.minecraft.entity.passive.EntityCow cow = new net.minecraft.entity.passive.EntityCow(world);
                cow.setLocationAndAngles(bx + 5.5, by + 1, bz + 9.5, 0.0f, 0.0f);
                cow.setNoAI(true);
                world.spawnEntity(cow);
            });
            serverCommand(mc, "time set 1500");
            serverCommand(mc, "weather clear 100000");
        } else if (t == 5) {
            serverCommand(mc, String.format("tp %s %.1f %.1f %.1f 180 35", mc.player.getName(), bx + 8.5, by + 1.0, bz + 15.5));
        } else if (t == 120) {
            capture(mc, "mobShadow");
        } else if (t >= 130) {
            Initializer.LOGGER.info("[autotest] done, shutting down");
            mc.shutdown();
        }
    }

    /**
     * {@code VULKANMOD_AUTOTEST_RTWATER=1}: a water pool on a platform high in the air with stone
     * pillars and glass behind it, morning sun; captures "water" from a low viewing angle.
     * Checks RT reflections, the sun glint and refraction.
     */
    private static final boolean RT_WATER_MODE = System.getenv("VULKANMOD_AUTOTEST_RTWATER") != null;
    private static int waterTicks;

    private static void rtWaterStage(Minecraft mc) {
        int t = ++waterTicks;
        int bx = 320, by = 150, bz = 200;
        if (t == 1) {
            IntegratedServer server = mc.getIntegratedServer();
            if (server == null) return;
            server.addScheduledTask(() -> {
                net.minecraft.world.World world = server.getWorld(0);
                BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
                for (int dx = 0; dx <= 20; dx++)
                    for (int dz = 0; dz <= 24; dz++) {
                        world.setBlockState(c.setPos(bx + dx, by - 2, bz + dz), Blocks.SANDSTONE.getDefaultState(), 2);
                        boolean pool = dx >= 2 && dx <= 18 && dz >= 2 && dz <= 16;
                        world.setBlockState(c.setPos(bx + dx, by - 1, bz + dz),
                                pool ? Blocks.WATER.getDefaultState() : Blocks.STONE.getDefaultState(), 2);
                    }
                for (int i = 0; i < 4; i++)
                    for (int dy = 0; dy <= 5 + i; dy++)
                        world.setBlockState(c.setPos(bx + 3 + i * 5, by + dy, bz + 1), Blocks.STONEBRICK.getDefaultState(), 2);
                for (int dy = 0; dy <= 2; dy++)
                    world.setBlockState(c.setPos(bx + 10, by + dy, bz + 1), Blocks.GLASS.getDefaultState(), 2);
                world.setBlockState(c.setPos(bx + 6, by, bz + 20), Blocks.GLASS.getDefaultState(), 2);
            });
            serverCommand(mc, "time set 1000");
            serverCommand(mc, "weather clear 100000");
        } else if (t == 5) {
            serverCommand(mc, String.format("tp %s %.1f %.1f %.1f 180 12", mc.player.getName(), bx + 10.5, by + 1.0, bz + 21.5));
        } else if (t == 120) {
            capture(mc, "rtWater");
        } else if (t == 125) {
            // At the pool's edge looking down: the player's reflection and shadow.
            serverCommand(mc, String.format("tp %s %.1f %.1f %.1f 200 55", mc.player.getName(), bx + 10.5, by + 0.0, bz + 17.6));
        } else if (t == 190) {
            capture(mc, "rtWaterClose");
        } else if (t >= 200) {
            Initializer.LOGGER.info("[autotest] done, shutting down");
            mc.shutdown();
        }
    }

    /**
     * {@code VULKANMOD_AUTOTEST_MATERIALS=1}: a platform with metal and polished blocks, a
     * stained-glass canopy and a torch in a stained-glass box; captures "materialsDay" (sun
     * through the canopy) and "materialsNight" (the torch through its box).
     */
    private static final boolean MATERIALS_MODE = System.getenv("VULKANMOD_AUTOTEST_MATERIALS") != null;
    private static int materialsTicks;

    private static void materialsStage(Minecraft mc) {
        int t = ++materialsTicks;
        int bx = 380, by = 150, bz = 200;
        if (t == 1) {
            IntegratedServer server = mc.getIntegratedServer();
            if (server == null) return;
            server.addScheduledTask(() -> {
                net.minecraft.world.World world = server.getWorld(0);
                BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
                for (int dx = 0; dx <= 20; dx++)
                    for (int dz = 0; dz <= 20; dz++)
                        world.setBlockState(c.setPos(bx + dx, by - 1, bz + dz), Blocks.QUARTZ_BLOCK.getDefaultState(), 2);
                net.minecraft.block.state.IBlockState[] row = {Blocks.IRON_BLOCK.getDefaultState(),
                        Blocks.GOLD_BLOCK.getDefaultState(), Blocks.DIAMOND_BLOCK.getDefaultState(),
                        Blocks.EMERALD_BLOCK.getDefaultState(), Blocks.OBSIDIAN.getDefaultState(),
                        Blocks.STONE.getDefaultState()};
                for (int i = 0; i < row.length; i++) {
                    world.setBlockState(c.setPos(bx + 4 + i * 2, by, bz + 13), row[i], 2);
                    world.setBlockState(c.setPos(bx + 4 + i * 2, by + 1, bz + 13), row[i], 2);
                }
                // Stained-glass canopy (red, green, blue strips) on four pillars.
                net.minecraft.item.EnumDyeColor[] colors = {net.minecraft.item.EnumDyeColor.RED,
                        net.minecraft.item.EnumDyeColor.LIME, net.minecraft.item.EnumDyeColor.BLUE};
                for (int dx = 2; dx <= 10; dx++)
                    for (int dz = 2; dz <= 8; dz++)
                        world.setBlockState(c.setPos(bx + dx, by + 4, bz + dz), Blocks.STAINED_GLASS.getDefaultState()
                                .withProperty(net.minecraft.block.BlockStainedGlass.COLOR, colors[(dx - 2) / 3]), 2);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlockState(c.setPos(bx + 2, by + dy, bz + 2), Blocks.STONEBRICK.getDefaultState(), 2);
                    world.setBlockState(c.setPos(bx + 10, by + dy, bz + 2), Blocks.STONEBRICK.getDefaultState(), 2);
                    world.setBlockState(c.setPos(bx + 2, by + dy, bz + 8), Blocks.STONEBRICK.getDefaultState(), 2);
                    world.setBlockState(c.setPos(bx + 10, by + dy, bz + 8), Blocks.STONEBRICK.getDefaultState(), 2);
                }
                // A torch in a magenta glass box.
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = 0; dy <= 2; dy++)
                        for (int dz = -1; dz <= 1; dz++)
                            if (dx != 0 || dz != 0 || dy == 2)
                                world.setBlockState(c.setPos(bx + 16 + dx, by + dy, bz + 5 + dz), Blocks.STAINED_GLASS.getDefaultState()
                                        .withProperty(net.minecraft.block.BlockStainedGlass.COLOR, net.minecraft.item.EnumDyeColor.MAGENTA), 2);
                world.setBlockState(c.setPos(bx + 16, by, bz + 5), Blocks.TORCH.getDefaultState(), 3);
            });
            serverCommand(mc, "time set 5000");
            serverCommand(mc, "weather clear 100000");
        } else if (t == 5) {
            serverCommand(mc, String.format("tp %s %.1f %.1f %.1f 165 22", mc.player.getName(), bx + 8.5, by + 1.0, bz + 16.5));
        } else if (t == 120) {
            capture(mc, "materialsDay");
        } else if (t == 125) {
            serverCommand(mc, "time set 18000");
            serverCommand(mc, String.format("tp %s %.1f %.1f %.1f 180 35", mc.player.getName(), bx + 16.5, by + 1.0, bz + 12.5));
        } else if (t == 220) {
            capture(mc, "materialsNight");
        } else if (t >= 230) {
            Initializer.LOGGER.info("[autotest] done, shutting down");
            mc.shutdown();
        }
    }

    private static void rtToggleStage(Minecraft mc) {
        int t = ++rtToggleTicks;
        if (t == 20) {
            capture(mc, "rtBefore");
        } else if (t == 30 || t == 170 || t == 240) {
            String preset = t == 30 ? "low" : t == 170 ? "high" : "off";
            Initializer.CONFIG.applyRtPreset(preset);
            net.vulkanmod.vulkan.FrameTargets.applySettings();
            Initializer.LOGGER.info("[autotest] RT preset -> {}", preset);
        } else if (t == 160) {
            capture(mc, "rtNear");
        } else if (t == 230) {
            capture(mc, "rtFar");
        } else if (t == 270) {
            capture(mc, "rtOff");
        } else if (t >= 280) {
            Initializer.LOGGER.info("[autotest] done, shutting down");
            mc.shutdown();
        }
    }

    /**
     * Render-state boundary probe: with {@code VULKANMOD_AUTOTEST_STATE=1} the run captures
     * the cases the default scene cannot reach - terrain with clouds off, terrain above the
     * cloud plane, the survival HUD (hearts/hunger with absorption + poison tints), the
     * inventory paper doll inside the GUI pass, the F3+G chunk-border lines and the first
     * world frame after a GUI closed (the Nether/End leak path). Run once with Vulkan and
     * once with {@code VULKANMOD_DISABLE=1} and diff the pairs.
     */
    private static final boolean STATE_MODE = System.getenv("VULKANMOD_AUTOTEST_STATE") != null;

    /**
     * End-fight repro (bug E, dragon healing beams): moves the player to The End,
     * waits for the dragon, then pins the two client-side fields {@code RenderDragon}
     * reads for the healing beam ({@code hurtTime > 0} and a non-null
     * {@code healingEnderCrystal}) and places the camera sideways to the
     * crystal-to-head axis so the ribbon crosses the frame instead of pointing
     * away from the lens.
     */
    private static final boolean END_MODE = System.getenv("VULKANMOD_AUTOTEST_END") != null;
    /** Absolute camera column for STATE_MODE (near the fixed-seed world spawn). */
    private static final int PIN_X = 200, PIN_Z = 112;
    /** Surface Y of the STATE portal ring, set by the server task; -1 until built. */
    private static volatile int portalY = -1;
    private static int fpsSamples;
    private static long fpsSum, fpsMin = Long.MAX_VALUE, fpsMax;
    private static double pinX, pinY, pinZ;
    private static float pinYaw, pinPitch;
    private static boolean pinSet;

    private static int plantX, plantY, plantZ;

    private static boolean waterFound;
    private static int waterX, waterY, waterZ;

    private static int stage;
    private static int ticks;
    private static int warmupTicks;
    private static boolean measuring;
    private static int measureStart;

    // Input activity counters, sampled every tick and reported (then reset) by
    // focusState(). Instantaneous key state alone misses short presses, and the
    // player's own rotation/position deltas prove the SDL -> lwjgly -> Minecraft
    // input path works end to end, not just that events reach the shim.
    private static int wTicks;
    private static int spaceTicks;
    private static int turnTicks;
    private static int moveTicks;
    private static float lastYaw;
    private static float lastPitch;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static boolean haveLast;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (stage >= 2) {
            sampleInput(mc);
        }
        switch (stage) {
            case 0 -> {
                if (GUI_MODE) {
                    guiStage(mc);
                    return;
                }
                if (mc.currentScreen instanceof GuiMainMenu) {
                    // The long startup makes the timer accumulate a batch of ticks that all
                    // run inside the very first runGameLoop iteration, before its render
                    // section. Gating the launch on ticks alone would start the world before
                    // the menu ever renders a frame, so wait for the title to actually render
                    // a few frames (updateCameraAndRender calls) before capturing and leaving.
                    if (!titleCaptured && (Initializer.isVulkanActive()
                            ? net.vulkanmod.render.GuiRenderer.getRenderedFrames() >= 3
                            : ++titleTicks >= 3)) {
                        titleCaptured = true;
                        capture(mc, "title");
                    }
                    if (titleCaptured && ++titleTicks >= 3) {
                        mc.gameSettings.pauseOnLostFocus = false;
                        WorldSettings settings = new WorldSettings(0x5EEDL, GameType.CREATIVE,
                                false, false, WorldType.DEFAULT);
                        settings.enableCommands();
                        // Every run starts from a freshly generated world: a reused save
                        // keeps the dimension, position, effects and terrain edits of the
                        // previous mode (after END every later run started in the End).
                        if (WORLD_NAME.equals("vm_autotest")
                                && System.getenv("VULKANMOD_AUTOTEST_KEEP_WORLD") == null) {
                            mc.getSaveLoader().flushCache();
                            boolean deleted = mc.getSaveLoader().deleteWorldDirectory(WORLD_NAME);
                            Initializer.LOGGER.info("[autotest] fresh world {} (old save deleted: {})",
                                    WORLD_NAME, deleted);
                        }
                        worldGenerated = !new File(new File(mc.gameDir, "saves"), WORLD_NAME + "/level.dat").exists();
                        Initializer.LOGGER.info("[autotest] launching integrated world {}", WORLD_NAME);
                        mc.launchIntegratedServer(WORLD_NAME, WORLD_NAME, settings);
                        stage = 1;
                    }
                }
            }
            case 1 -> {
                if (mc.player != null && mc.world != null && mc.currentScreen == null && ++ticks > 40) {
                    if (!BURST_MODE) {
                        IntegratedServer server = mc.getIntegratedServer();
                        if (server != null) {
                            server.addScheduledTask(() -> {
                                net.minecraft.world.World world = server.getWorld(0);
                                if (world != null) {
                                    world.setWorldTime(6000); // noon (angle 0)
                                }
                                // Pin the weather too: the save persists rain across runs,
                                // which darkens sky/fog/lightmap and makes screenshots
                                // incomparable between runs.
                                server.getCommandManager().executeCommand(server, "weather clear 1000000");
                            });
                        }
                    }
                    Initializer.LOGGER.info("[autotest] in world, warming up {} ticks", WARMUP);
                    stage = 2;
                    ticks = 0;
                }
            }
            case 2 -> {
                if (mc.player == null) {
                    mc.shutdown();
                    return;
                }
                if (rainOn && mc.world != null) {
                    // The server ramps rain strength by 0.01/tick (~1000 ticks to a full
                    // downpour), so re-assert full rain on the client every tick: the rain
                    // window is immediate and identical between the Vulkan and GL runs.
                    mc.world.setRainStrength(1.0f);
                }
                if (BURST_MODE) {
                    // Let the world load and the player settle for ~1.2 s, then pin the camera
                    // still at eye level: without this the player falls through the
                    // still-loading terrain and sweeps the view, so frames cannot be compared.
                    if (++ticks >= 25 && pinPos == null) {
                        // Drop the camera to just above the water/terrain surface at the
                        // player's column: the saved spawn can be high in the air while the
                        // chunks around it are still building.
                        BlockPos top = mc.world.getTopSolidOrLiquidBlock(
                                new BlockPos(mc.player.posX, 0, mc.player.posZ));
                        pinPos = new double[]{mc.player.posX, top.getY() + 2.0, mc.player.posZ};
                    }
                    if (pinPos != null) {
                        mc.player.posX = pinPos[0];
                        mc.player.posY = pinPos[1];
                        mc.player.posZ = pinPos[2];
                        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
                        mc.player.rotationPitch = 22; // look down at the shore: pits + plants
                    }
                    if (ticks >= 25 && ticks % 20 == 5 && ticks <= 85) {
                        capture(mc, "burst" + (ticks / 20 + 1));
                    }
                    if (ticks >= 95 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }
                if (warmupTicks < WARMUP) {
                    if (++warmupTicks % 40 == 0) {
                        Initializer.LOGGER.info("[autotest] warmup fps {} | {}", Minecraft.getDebugFPS(), focusState(mc));
                    }
                    return;
                }

                if (FPS_MODE) {
                    fpsStage(mc);
                    return;
                }

                if (RT_TOGGLE_MODE) {
                    rtToggleStage(mc);
                    return;
                }

                if (TORCH_MODE) {
                    torchStage(mc);
                    return;
                }

                if (MOBSHADOW_MODE) {
                    mobShadowStage(mc);
                    return;
                }

                if (RT_WATER_MODE) {
                    rtWaterStage(mc);
                    return;
                }

                if (MATERIALS_MODE) {
                    materialsStage(mc);
                    return;
                }

                if (STATE_MODE) {
                    int t = ++ticks;
                    // Effect particles (portal block sparkles, potion swirls around the
                    // player) land randomly and pollute the GL/Vulkan diff pairs - the
                    // engine is wiped every tick so captures stay clean.
                    if (mc.effectRenderer != null) {
                        mc.effectRenderer.clearEffects(mc.world);
                    }
                    if (t == 5) {
                        // Pin the camera to an absolute column: the world-spawn search
                        // jitters by a block between runs, which shifts near-field
                        // parallax and makes the Vulkan/GL pair non-comparable.
                        int topY = mc.world.getTopSolidOrLiquidBlock(
                                new BlockPos(PIN_X, 0, PIN_Z)).getY();
                        if (topY < 0) {
                            // Right after world entry the PIN column's chunk can still be
                            // empty: getTopSolidOrLiquidBlock walks an empty chunk down to
                            // y=-1 and the pin would drop the camera to y=1 inside the
                            // ground. Retry next tick until the column reports a surface.
                            --ticks;
                            return;
                        }
                        mc.player.capabilities.allowFlying = true;
                        mc.player.capabilities.isFlying = true;
                        tpPlayer(mc, PIN_X + 0.5, topY + 2.0, PIN_Z + 0.5);
                        mc.player.rotationYaw = 0.0f;
                        mc.player.rotationPitch = 30.0f;
                        mc.gameSettings.clouds = 0;
                    } else if (t == 25) {
                        // N1 case 1: clouds off - neither pass re-reads GL this frame,
                        // so a leaked sky depthMask(false) would draw terrain without
                        // depth writes.
                        capture(mc, "stateCloudsOff");
                    } else if (t == 35) {
                        // N1 case 2: above the cloud plane, still with clouds off.
                        mc.player.capabilities.allowFlying = true;
                        mc.player.capabilities.isFlying = true;
                        tpPlayer(mc, mc.player.posX, 130.0, mc.player.posZ);
                    } else if (t == 55) {
                        capture(mc, "stateHighY");
                    } else if (t == 58) {
                        // Survival HUD (hearts + hunger bar): a real server-side
                        // gamemode switch is required because shouldDrawHUD reads
                        // the controller's own capabilities copy, which only the
                        // server's abilities packet updates. Land first - the
                        // survival switch disables flight, and from the cloud
                        // pin that is a fatal drop. Absorption adds a second row
                        // of hearts and poison tints them; both also light up
                        // Cleanroom's status-effect indicator column.
                        int landY = mc.world.getTopSolidOrLiquidBlock(
                                new BlockPos(PIN_X, 0, PIN_Z)).getY();
                        tpPlayer(mc, PIN_X + 0.5, landY + 1.0, PIN_Z + 0.5);
                        serverCommand(mc, "gamemode survival " + mc.player.getName());
                        serverCommand(mc, "effect " + mc.player.getName() + " absorption 60 1");
                        serverCommand(mc, "effect " + mc.player.getName() + " poison 60 0");
                    } else if (t == 70) {
                        capture(mc, "stateHud");
                    } else if (t == 75) {
                        // N9: the paper doll is real 3D geometry inside the GUI pass;
                        // with the GUI's depth-off override leaking into it, the back
                        // arm paints over the body and the face turns inside-out.
                        // Cleanroom's GuiInventory redirects to the doll-less creative
                        // screen while the client is creative, so flip the client-side
                        // controller to survival for the capture (server stays creative).
                        mc.playerController.setGameType(GameType.SURVIVAL);
                        mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiInventory(mc.player));
                    } else if (t == 95) {
                        Initializer.LOGGER.info("[autotest] state screen at capture: {}",
                                mc.currentScreen == null ? "null" : mc.currentScreen.getClass().getSimpleName());
                        capture(mc, "stateInvDoll");
                    } else if (t == 105) {
                        mc.displayGuiScreen(null);
                        serverCommand(mc, "gamemode creative " + mc.player.getName());
                    } else if (t == 110) {
                        // Back above the treeline for the border and postGui captures.
                        int hudTopY = mc.world.getTopSolidOrLiquidBlock(
                                new BlockPos(PIN_X, 0, PIN_Z)).getY();
                        tpPlayer(mc, PIN_X + 0.5, hudTopY + 2.0, PIN_Z + 0.5);
                        mc.player.capabilities.allowFlying = true;
                        mc.player.capabilities.isFlying = true;
                        mc.player.motionY = 0;
                        // F3+G chunk borders: POSITION_COLOR line lists replayed
                        // through DebugLinesRenderer.
                        mc.debugRenderer.toggleChunkBorders();
                    } else if (t == 115) {
                        capture(mc, "stateBorders");
                    } else if (t == 120) {
                        mc.debugRenderer.toggleChunkBorders();
                        // End portal planes (bug D): build a real portal - 3x3 portal
                        // blocks ringed by 12 end_portal_frame blocks - so the TESR's
                        // shouldRenderFace culling matches what players actually see.
                        // The camera looks down at the ring from a corner: a plane that
                        // lies flat vs one that stands up is unambiguous from there.
                        // Built on the server: the client heightmap can lag behind and put
                        // the ring under the surface, out of view (R26). The view corridor
                        // up to the camera is cleared so grass or leaves cannot hide it.
                        portalY = -1;
                        IntegratedServer server = mc.getIntegratedServer();
                        if (server != null) {
                            server.addScheduledTask(() -> {
                                net.minecraft.world.World world = server.getWorld(0);
                                int cx = PIN_X + 8, cz = PIN_Z;
                                int cy = world.getTopSolidOrLiquidBlock(new BlockPos(cx, 0, cz)).getY() - 1;
                                BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
                                for (int dx = -2; dx <= 6; dx++) {
                                    for (int dz = -2; dz <= 6; dz++) {
                                        for (int dy = 1; dy <= 6; dy++) {
                                            world.setBlockState(c.setPos(cx + dx, cy + dy, cz + dz),
                                                    Blocks.AIR.getDefaultState());
                                        }
                                    }
                                }
                                for (int dx = -2; dx <= 2; dx++) {
                                    for (int dz = -2; dz <= 2; dz++) {
                                        boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                                        world.setBlockState(c.setPos(cx + dx, cy, cz + dz), ring
                                                ? Blocks.END_PORTAL_FRAME.getDefaultState()
                                                : Blocks.END_PORTAL.getDefaultState());
                                    }
                                }
                                portalY = cy;
                            });
                        }
                    } else if (t == 124 && portalY >= 0) {
                        int cx = PIN_X + 8, cz = PIN_Z, cy = portalY;
                        Initializer.LOGGER.info("[autotest] STATE portal at {},{},{}", cx, cy, cz);
                        // Command feedback in the chat covers the ring.
                        mc.ingameGUI.getChatGUI().clearChatMessages(false);
                        tpPlayer(mc, cx + 5.5, cy + 4.5, cz + 5.5);
                        double dx = cx + 0.5 - (cx + 5.5);
                        double dy = cy + 1.0 - (cy + 4.5);
                        double dz = cz + 0.5 - (cz + 5.5);
                        mc.player.rotationYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        mc.player.rotationPitch = (float) Math.toDegrees(
                                Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
                    } else if (t == 133) {
                        if (portalY < 0) {
                            Initializer.LOGGER.warn("[autotest] STATE portal was not built in time");
                        }
                        capture(mc, "statePortal");
                    } else if (t == 135) {
                        // The GUI->world boundary: the Nether/End failure mode (no sky
                        // or cloud pass to mask a leaked "depthFunc ALWAYS").
                        capture(mc, "statePostGui");
                    } else if (t == 145 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (END_MODE) {
                    endStage(mc);
                    return;
                }

                if (REPRO_SPEC != null) {
                    if (++ticks == 5) {
                        doRepro(mc);
                    } else if (ticks == 45 && System.getenv("VULKANMOD_AUTOTEST_RAIN") != null) {
                        // Rain used to be unconditional here for the weather repro; it is
                        // opt-in now so plain scene repros (e.g. the world border pair)
                        // capture clear-weather frames.
                        startRain(mc);
                    } else if (ticks == 95) {
                        logWeatherDiag(mc);
                        capture(mc, "repro");
                    } else if (ticks == 110 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (FLYUP_MODE) {
                    int t = ++ticks;
                    if (t == 5) {
                        // Start at the repro perch (or a default over the lake) and enable flight.
                        double sx = -370.65, sy = 106.01, sz = -518.47;
                        float syaw = -38.4f, spitch = 0.0f;
                        if (REPRO_SPEC != null) {
                            String[] p = REPRO_SPEC.trim().split("\\s+");
                            if (p.length >= 5) {
                                sx = Double.parseDouble(p[0]); sy = Double.parseDouble(p[1]); sz = Double.parseDouble(p[2]);
                                syaw = Float.parseFloat(p[3]); spitch = Float.parseFloat(p[4]);
                            }
                        }
                        mc.player.capabilities.isFlying = true;
                        mc.player.capabilities.allowFlying = true;
                        mc.player.setPositionAndRotation(sx, sy, sz, syaw, spitch);
                        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
                        setWorldTime(6651L);
                    } else if (t == 45) {
                        startRain(mc);
                    } else if (t > 45) {
                        // Ascend like holding space. The integrated server owns the player's
                        // authoritative position, so a client-side posY bump gets snapped back
                        // next tick; drive the ascent with a /tp on the server (authoritative)
                        // and mirror it on the client so the render-time interpolation is smooth.
                        double targetY = 106.01 + (t - 45) * 0.2;
                        IntegratedServer server = mc.getIntegratedServer();
                        if (server != null) {
                            final double ty = targetY;
                            final double tx = mc.player.posX, tz = mc.player.posZ;
                            final String who = mc.player.getName();
                            server.addScheduledTask(() -> server.getCommandManager().executeCommand(server,
                                    String.format("tp %s %.3f %.3f %.3f", who, tx, ty, tz)));
                        }
                        mc.player.lastTickPosX = mc.player.posX;
                        mc.player.lastTickPosY = mc.player.posY;
                        mc.player.lastTickPosZ = mc.player.posZ;
                        mc.player.posY = targetY;
                        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
                        mc.player.rotationYaw = -38.4f;
                        mc.player.rotationPitch = 0.0f;
                    }
                    if (t == 70 || t == 110 || t == 150 || t == 190) {
                        Initializer.LOGGER.info("[autotest] FLYUP capture at y={}",
                                String.format("%.1f", mc.player.posY));
                        capture(mc, "fly" + t);
                    }
                    if (t == 210 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (FREE_MODE) {
                    if (++ticks == 5) {
                        mc.player.capabilities.allowFlying = true;
                        mc.player.capabilities.isFlying = true;
                        startRain(mc);
                        Initializer.LOGGER.info(
                                "[autotest] FREE mode: downpour forced, flight enabled. Fly to the spot "
                                + "where weather vanishes, read the F3 coords, and check if the empty "
                                + "zone is world-anchored (biome) or screen-anchored (render bug).");
                    }
                    // Stay open indefinitely; the user closes the window when done.
                    return;
                }

                if (STICKY_MODE) {
                    int t = ++ticks;
                    // A: full-weather perch over the lake at height; B: the low spot where the
                    // column count dips; C: back to A. If the buffer limit is not reset between
                    // frames, C is pinned at B's (lower) vertex count instead of recovering to A.
                    final double ax = -370.65, ay = 135.0, az = -518.47;
                    final double bx = -359.3, by = 95.0, bz = -527.5;
                    if (t == 5) {
                        mc.player.capabilities.allowFlying = true;
                        mc.player.capabilities.isFlying = true;
                        mc.player.setPositionAndRotation(ax, ay, az, -38.4f, 0.0f);
                        setWorldTime(6651L);
                    } else if (t == 45) {
                        startRain(mc);
                    } else if (t == 90) {
                        Initializer.LOGGER.info("[autotest] STICKY phase A (full) y={} verts={}",
                                String.format("%.1f", mc.player.posY), net.vulkanmod.render.EntityRenderer.lastWeatherVerts());
                        capture(mc, "stickyA");
                    } else if (t == 110) {
                        tpPlayer(mc, bx, by, bz);
                    } else if (t == 170) {
                        Initializer.LOGGER.info("[autotest] STICKY phase B (dip) y={} verts={}",
                                String.format("%.1f", mc.player.posY), net.vulkanmod.render.EntityRenderer.lastWeatherVerts());
                        capture(mc, "stickyB");
                    } else if (t == 190) {
                        tpPlayer(mc, ax, ay, az);
                    } else if (t == 250) {
                        Initializer.LOGGER.info("[autotest] STICKY phase C (recover) y={} verts={}",
                                String.format("%.1f", mc.player.posY), net.vulkanmod.render.EntityRenderer.lastWeatherVerts());
                        capture(mc, "stickyC");
                    }
                    if (t == 270 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (RESIZE_MODE) {
                    int t = ++ticks;
                    if (t == 5) {
                        Initializer.LOGGER.info("[autotest] RESIZE initial mc={}x{} display={}x{} win=0x{}",
                                mc.displayWidth, mc.displayHeight,
                                org.lwjgl.opengl.Display.getWidth(), org.lwjgl.opengl.Display.getHeight(),
                                Long.toHexString(org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentWindow()));
                    } else if (t == 30) {
                        org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(
                                org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentWindow(), 1000, 700);
                    } else if (t == 75) {
                        Initializer.LOGGER.info("[autotest] RESIZE after->1000x700 mc={}x{} display={}x{}",
                                mc.displayWidth, mc.displayHeight,
                                org.lwjgl.opengl.Display.getWidth(), org.lwjgl.opengl.Display.getHeight());
                    } else if (t == 95) {
                        org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(
                                org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentWindow(), 800, 600);
                    } else if (t == 140) {
                        Initializer.LOGGER.info("[autotest] RESIZE after->800x600 mc={}x{} display={}x{}",
                                mc.displayWidth, mc.displayHeight,
                                org.lwjgl.opengl.Display.getWidth(), org.lwjgl.opengl.Display.getHeight());
                    }
                    if (t == 160 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (GOTO_SPEC != null) {
                    if (++ticks == 5) {
                        gotoTarget(mc);
                    } else if (ticks >= 30 && ticks <= 270 && ticks % 15 == 0) {
                        // Static camera at a fluid-rich spot: the flowing fluid keeps
                        // re-meshing nearby chunks, which intermittently corrupts the cutout
                        // layer. Frequent captures let us diff consecutive frames to catch a
                        // ghost and correlate it with the modelview/range detectors.
                        capture(mc, "watch" + (ticks / 15));
                    } else if (ticks == 290 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (PLANT_MODE) {
                    if (++ticks == 5) {
                        buildPlantScene(mc);
                    } else if (ticks == 40) {
                        framePlant(mc);
                    } else if (ticks == 90) {
                        capture(mc, "plant");
                    } else if (ticks == 100 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (FEATURES_MODE) {
                    if (++ticks == 5) {
                        buildFeatureScene(mc);
                    } else if (ticks >= 25 && featBuilt && !featFramed) {
                        // Hover north of the row and look south along it: every test prop is
                        // in one frame, with the dropped item and crystal in the foreground.
                        featFramed = true;
                        mc.player.capabilities.allowFlying = true;
                        mc.player.capabilities.isFlying = true;
                        tpPlayer(mc, featX + 4.5, featY + 6.0, featZ - 10.0);
                        // look=(0,-0.5,-0.87) at yaw 180 proved the 1.12.2 convention: yaw 0
                        // faces +Z, so the scene 10 blocks south (+Z) is dead ahead at yaw 0.
                        mc.player.rotationYaw = 0.0f;
                        mc.player.rotationPitch = 30.0f;
                        // The renderer reads the CLIENT inventory; the server-side loadout
                        // sends no slot packets, so mirror it here.
                        applyFeatureLoadout(mc.player);
                    } else if (ticks == 89) {
                        net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(featX, featY, featZ);
                        StringBuilder hot = new StringBuilder();
                        for (int i = 0; i < 9; i++) {
                            net.minecraft.item.ItemStack st = mc.player.inventory.mainInventory.get(i);
                            hot.append(st.isEmpty() ? "-" : st.getItem().getRegistryName()).append(' ');
                        }
                        Initializer.LOGGER.info("[autotest] FEATURES readback floor={} chest={} dist={}"
                                        + " current={} mainHand={} offhand={} look={}",
                                mc.world.getBlockState(p).getBlock().getRegistryName(),
                                mc.world.getBlockState(p.up()).getBlock().getRegistryName(),
                                String.format("%.1f", mc.player.getDistance(p.east(4).getX() + 0.5, p.getY() + 1, p.getZ() + 0.5)),
                                mc.player.inventory.currentItem, mc.gameSettings.mainHand,
                                mc.player.inventory.offHandInventory.get(0),
                                String.format("%.2f %.2f %.2f", mc.player.getLook(1.0f).x,
                                        mc.player.getLook(1.0f).y, mc.player.getLook(1.0f).z));
                        Initializer.LOGGER.info("[autotest] FEATURES hotbar: {}", hot);
                    } else if (ticks == 90) {
                        capture(mc, "featWorld");
                    } else if (ticks == 100) {
                        mc.gameSettings.thirdPersonView = 1;
                    } else if (ticks == 120) {
                        capture(mc, "featThird");
                    } else if (ticks == 130) {
                        mc.gameSettings.thirdPersonView = 0;
                        // Same creative redirect as STATE_MODE: survival client-side so
                        // the paper-doll screen actually opens (GuiContainerCreative has
                        // no doll to capture).
                        mc.playerController.setGameType(GameType.SURVIVAL);
                        mc.displayGuiScreen(new net.minecraft.client.gui.inventory.GuiInventory(mc.player));
                    } else if (ticks == 160) {
                        capture(mc, "featInv");
                    } else if (ticks == 170) {
                        mc.displayGuiScreen(null);
                        mc.playerController.setGameType(GameType.CREATIVE);
                        mc.player.capabilities.allowFlying = true;
                    } else if (ticks == 195) {
                        capture(mc, "featHand");
                    } else if (ticks == 215 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (RAINWATER_MODE) {
                    if (++ticks == 4) {
                        // Pin to the known perch so scanForWater resolves the exact same pool
                        // in both the Vulkan and the GL reference run - the two captures then
                        // frame identical water and only the renderer differs.
                        mc.player.capabilities.isFlying = true;
                        mc.player.setPosition(-423.5, 85.0, -433.5);
                        setWorldTime(6000L);
                    } else if (ticks == 6) {
                        scanForWater(mc);
                    } else if (ticks == 30) {
                        frameWater(mc);
                    } else if (ticks == 60) {
                        startRain(mc);
                    } else if (ticks == 95) {
                        capture(mc, "rainwater");
                    } else if (ticks == 105 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (WATER_MODE) {
                    if (++ticks == 5) {
                        scanForWater(mc);
                    } else if (ticks == 30) {
                        frameWater(mc);
                    } else if (ticks == 80) {
                        capture(mc, "rtWater");
                    } else if (ticks == 90 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                        Initializer.LOGGER.info("[autotest] done, shutting down");
                        mc.shutdown();
                    }
                    return;
                }

                if (++ticks == 5) {
                    capture(mc, "world");
                } else if (ticks == 8) {
                    // Face horizontally and drop a cow a few blocks ahead; it is a simple
                    // single-texture mob, ideal for verifying the entity capture pipeline.
                    mc.player.rotationYaw = 0.0f;
                    mc.player.rotationPitch = 0.0f;
                    spawnCow(mc);
                } else if (ticks == 25) {
                    capture(mc, "entity");
                } else if (ticks == 30) {
                    mc.gameSettings.thirdPersonView = 2;
                } else if (ticks == 50) {
                    capture(mc, "third_person");
                } else if (ticks == 60) {
                    mc.gameSettings.thirdPersonView = 0;
                }
                // Cloud capture: look up at the cloud layer (y=128) from the surface. The
                // world time is still noon here (set in stage 1), so the clouds read bright
                // white. The renderer draws the fast path for any cloud setting, so the
                // user's clouds option is left untouched.
                else if (ticks == 61) {
                    logClouds(mc);
                } else if (ticks == 62) {
                    mc.player.rotationYaw = 0.0f;
                    mc.player.rotationPitch = -40.0f;
                } else if (ticks == 64) {
                    cloudAnchorX = mc.player.posX;
                    capture(mc, "clouds");
                }
                // Sky captures: noon sun overhead, sunset glow at the horizon and the
                // midnight moon + stars. World time is changed on the server so the
                // client syncs it instead of being overwritten back; the integrated
                // server only broadcasts the time every 20 ticks, so leave >=30 ticks
                // between the set and the capture and log the synced client time.
                else if (ticks == 65) {
                    mc.player.rotationYaw = 0.0f;
                    mc.player.rotationPitch = -75.0f;
                } else if (ticks == 70) {
                    SkyRenderer.logCelestial(mc, mc.getRenderPartialTicks());
                    capture(mc, "sky_day");
                } else if (ticks == 75) {
                    // Just after sunset (12000): the sun disk sits slightly below the
                    // western horizon and the glow fan is active (it only renders while
                    // cos(angle*2PI) is within [0.395, 0.8] of the sunset colors).
                    setWorldTime(12300L);
                } else if (ticks == 105) {
                    // Face west so the setting sun and the glow fan are in view.
                    mc.player.rotationYaw = 90.0f;
                    mc.player.rotationPitch = -10.0f;
                } else if (ticks == 110) {
                    SkyRenderer.logCelestial(mc, mc.getRenderPartialTicks());
                    capture(mc, "sky_sunset");
                } else if (ticks == 115) {
                    setWorldTime(18000L);
                } else if (ticks == 145) {
                    mc.player.rotationPitch = -75.0f;
                } else if (ticks == 150) {
                    SkyRenderer.logCelestial(mc, mc.getRenderPartialTicks());
                    capture(mc, "sky_night");
                }
                // World-anchoring test, taken as the LAST capture so the slow synchronous
                // write of the previous capture cannot swallow it via tick catch-up. Reset
                // to noon, look up at the clouds, then translate the camera +256 in X. A
                // correctly world-anchored cloud field pans by ~half the screen relative to
                // the "clouds" capture taken at the original position; a camera-locked UV
                // would render a pixel-identical pattern.
                else if (ticks == 152) {
                    setWorldTime(6000L);
                    mc.player.rotationYaw = 0.0f;
                    mc.player.rotationPitch = -40.0f;
                } else if (ticks == 168) {
                    mc.player.setPosition(cloudAnchorX + 256.0, mc.player.posY, mc.player.posZ);
                } else if (ticks == 172) {
                    Initializer.LOGGER.info("[autotest] clouds_moved playerX={} (was {})",
                            String.format("%.1f", mc.player.posX), String.format("%.1f", cloudAnchorX));
                    capture(mc, "clouds_moved");
                }
                // Lightmap scaling check, taken as the LAST capture. At noon the terrain
                // lightmap saturates to ~white, so doubling it is invisible; at midnight it
                // sits near 0.23, where a doubled sample reads twice as bright as vanilla's
                // plain multiply. Comparing the terrain against its GL reference here shows
                // whether the shader's lightmap scaling matches the fixed-function path.
                else if (ticks == 175) {
                    setWorldTime(18000L);
                    // Pin to the benchmark perch instead of the clouds-moved position: the
                    // player drifts there between runs (physics differs from the GL
                    // reference run), which made the night pair non-comparable.
                    mc.player.setPosition(-423.5, 85.0, -433.5);
                    mc.player.rotationYaw = 0.0f;
                    mc.player.rotationPitch = 45.0f;
                } else if (ticks == 205) {
                    capture(mc, "night_terrain");
                } else if (ticks == 236) {
                    spawnCow(mc);
                } else if (ticks == 240) {
                    // The same cow as the daylight "entity" capture, now at midnight: its
                    // constant lightmap sample should darken it onto the terrain instead of
                    // leaving it lit as if it were noon.
                    capture(mc, "night_entity");
                } else if (ticks == 249) {
                    // A midnight enderman holding a grass block: the additive eye layer
                    // must render full-bright while the body follows the entity's dark
                    // light, and the held block (BLOCK format) must go through the
                    // terrain pipeline instead of a GL fallback.
                    spawnNightEnderman(mc);
                    // Aim in the same tick: staring provokes endermen and they close in
                    // within a few ticks, so the capture has to follow immediately.
                    aimAtEnderman(mc);
                } else if (ticks == 251) {
                    capture(mc, "night_enderman");
                } else if (ticks == 252) {
                    // Gone before the rain pair so a late teleport cannot pollute it.
                    if (nightEnderman != null) {
                        nightEnderman.setDead();
                    }
                } else if (ticks == 253) {
                    startRain(mc);
                } else if (ticks == 261) {
                    // The weather quads through the Vulkan pipeline: full-bright streaks
                    // with vertex alpha 0.25 over the darkened rainy-night terrain.
                    capture(mc, "rain");
                } else if (ticks == 269 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                    Initializer.LOGGER.info("[autotest] done, shutting down");
                    mc.shutdown();
                }
                // While staying open for manual inspection, keep reporting the input state
                // so a "no mouse/keyboard" report can be diagnosed from the log alone.
                if (System.getenv("VULKANMOD_AUTOTEST_STAY") != null && ticks % 40 == 0) {
                    Initializer.LOGGER.info("[autotest] {}", focusState(mc));
                }
            }
            default -> {
            }
        }
    }

    /** Client-side enderman for the night capture (eyes + held block), aimed at by the camera. */
    private static net.minecraft.entity.monster.EntityEnderman nightEnderman;
    private static double enderX, enderY, enderZ;

    /** True once the autotest forced a downpour; re-asserts the client strength each tick. */
    private static boolean rainOn;

    /**
     * Starts a downpour: the server command flips the weather flag (dark sky, rainy
     * fog), while the client rain strength is re-asserted every tick in stage 2 to
     * skip the server's ~1000-tick natural ramp-up.
     */
    private static int featX, featY, featZ;
    private static boolean featBuilt, featFramed;

    /**
     * Builds the FEATURES_MODE scene on the server: a stone floor with a row of block-entity
     * blocks (chest, sign, furnace, enchanting table, spawner, torch on stone, skull, banner),
     * a wall pillar with an apple item frame, a dropped enchanted diamond sword and an end
     * crystal in front, plus a hotbar loadout (enchanted sword in hand, block/item/map icons)
     * and worn diamond chestplate + boots. The anchor is logged so the camera can frame it.
     */
    private static void buildFeatureScene(Minecraft mc) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        server.addScheduledTask(() -> {
            net.minecraft.world.World world = server.getWorld(0);
            if (world == null) {
                return;
            }
            // Fixed absolute anchor (near the lake spawn of the repro save): the player is
            // still falling at scene-build time, so a player-relative anchor would land
            // somewhere different in every run and the Vulkan/GL captures would not match.
            int bx = -598, bz = 610;
            BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
            int py = 75;
            // Deterministic across runs: wipe the footprint+corridor column first (old saves
            // stacked platforms here), then build at a fixed height above the lake surface.
            // The wipe starts at the platform: carving the terrain below opened a pit whose
            // skylight was not yet propagated at capture time, so it rendered black. Flag 2
            // keeps the client sync but skips the expensive neighbor updates.
            for (int dx = -1; dx <= 9; dx++) {
                for (int dz = -12; dz <= 1; dz++) {
                    for (int dy = py; dy <= 170; dy++) {
                        world.setBlockState(c.setPos(bx + dx, dy, bz + dz), Blocks.AIR.getDefaultState(), 2);
                    }
                }
            }
            // Drop the props previous runs spawned (the block wipe cannot remove entities).
            for (net.minecraft.entity.Entity e : new java.util.ArrayList<>(world.loadedEntityList)) {
                if ((e instanceof net.minecraft.entity.item.EntityItem
                        || e instanceof net.minecraft.entity.item.EntityEnderCrystal
                        || e instanceof net.minecraft.entity.item.EntityItemFrame)
                        && Math.abs(e.posX - (bx + 4.5)) < 24 && Math.abs(e.posZ - bz) < 24
                        && e.posY > 50 && e.posY < 180) {
                    e.setDead();
                }
            }
            for (int dx = -1; dx <= 9; dx++) {
                for (int dz = -3; dz <= 1; dz++) {
                    world.setBlockState(c.setPos(bx + dx, py, bz + dz), Blocks.STONE.getDefaultState());
                }
            }
            // Block-entity row, west to east.
            world.setBlockState(c.setPos(bx, py + 1, bz), Blocks.CHEST.getDefaultState());
            world.setBlockState(c.setPos(bx + 1, py + 1, bz), Blocks.STANDING_SIGN.getDefaultState());
            world.setBlockState(c.setPos(bx + 2, py + 1, bz), Blocks.FURNACE.getDefaultState()
                    .withProperty(net.minecraft.block.BlockHorizontal.FACING, net.minecraft.util.EnumFacing.NORTH));
            world.setBlockState(c.setPos(bx + 3, py + 1, bz), Blocks.ENCHANTING_TABLE.getDefaultState());
            world.setBlockState(c.setPos(bx + 4, py + 1, bz), Blocks.MOB_SPAWNER.getDefaultState());
            world.setBlockState(c.setPos(bx + 5, py + 1, bz), Blocks.STONE.getDefaultState());
            world.setBlockState(c.setPos(bx + 5, py + 2, bz), Blocks.TORCH.getDefaultState());
            world.setBlockState(c.setPos(bx + 6, py + 1, bz), Blocks.SKULL.getDefaultState());
            world.setBlockState(c.setPos(bx + 7, py + 1, bz), Blocks.STANDING_BANNER.getDefaultState());
            // Wall pillar with an apple item frame on its north face (toward the camera).
            for (int dy = 1; dy <= 3; dy++) {
                world.setBlockState(c.setPos(bx + 9, py + dy, bz), Blocks.STONE.getDefaultState());
            }
            net.minecraft.entity.item.EntityItemFrame frame = new net.minecraft.entity.item.EntityItemFrame(
                    world, new BlockPos(bx + 9, py + 2, bz), net.minecraft.util.EnumFacing.NORTH);
            frame.setDisplayedItem(new net.minecraft.item.ItemStack(net.minecraft.init.Items.APPLE));
            world.spawnEntity(frame);
            // Dropped enchanted sword (tests the glint overlay) and an end crystal (beam).
            net.minecraft.item.ItemStack sword = new net.minecraft.item.ItemStack(net.minecraft.init.Items.DIAMOND_SWORD);
            sword.addEnchantment(net.minecraft.init.Enchantments.SHARPNESS, 5);
            world.spawnEntity(new net.minecraft.entity.item.EntityItem(world,
                    bx + 1.5, py + 2.5, bz - 2.0, sword.copy()));
            net.minecraft.entity.item.EntityEnderCrystal crystal = new net.minecraft.entity.item.EntityEnderCrystal(world);
            crystal.setPosition(bx + 5.5, py + 1.0, bz - 2.0);
            world.spawnEntity(crystal);
            // Loadout on the server (drives the armor equipment sync + advancement).
            net.minecraft.entity.player.EntityPlayer sp = world.getPlayerEntityByName(mc.player.getName());
            if (sp != null) {
                applyFeatureLoadout(sp);
            }
            featX = bx;
            featY = py;
            featZ = bz;
            featBuilt = true;
            Initializer.LOGGER.info("[autotest] FEATURES_MODE scene built at {},{},{}", bx, py + 1, bz);
        });
    }

    /**
     * Puts the FEATURES_MODE hotbar loadout on a player: enchanted sword in hand, block/item
     * icons incl. a filled map, diamond chestplate + boots worn. The server-side set alone is
     * not enough - {@code setInventorySlotContents} sends no sync packet to the client, so the
     * same loadout is applied to the client player directly too (the renderer reads the client
     * inventory for the hand, hotbar and GUI icons).
     */
    private static void applyFeatureLoadout(net.minecraft.entity.player.EntityPlayer sp) {
        net.minecraft.item.ItemStack sword = new net.minecraft.item.ItemStack(net.minecraft.init.Items.DIAMOND_SWORD);
        sword.addEnchantment(net.minecraft.init.Enchantments.SHARPNESS, 5);
        // Stone in hand (not the sword): a 3D block item is unmistakable when held, so the
        // captures settle whether the first-person item model renders correctly. NOTE: in
        // 1.12.2 the hotbar is mainInventory 0..8 (36..44 would hit armor/offhand slots!).
        sp.inventory.setInventorySlotContents(0, new net.minecraft.item.ItemStack(Blocks.STONE));
        sp.inventory.setInventorySlotContents(1, sword);
        sp.inventory.setInventorySlotContents(2, new net.minecraft.item.ItemStack(Blocks.CHEST));
        sp.inventory.setInventorySlotContents(3, new net.minecraft.item.ItemStack(
                net.minecraft.item.Item.getItemFromBlock(Blocks.STANDING_SIGN)));
        sp.inventory.setInventorySlotContents(4, new net.minecraft.item.ItemStack(Blocks.TORCH));
        sp.inventory.setInventorySlotContents(5, new net.minecraft.item.ItemStack(net.minecraft.init.Items.APPLE));
        sp.inventory.setInventorySlotContents(6, new net.minecraft.item.ItemStack(net.minecraft.init.Items.BOW));
        sp.inventory.setInventorySlotContents(7, new net.minecraft.item.ItemStack(net.minecraft.init.Items.FILLED_MAP));
        sp.inventory.setInventorySlotContents(8, new net.minecraft.item.ItemStack(net.minecraft.init.Items.COMPASS));
        sp.setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot.CHEST,
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.DIAMOND_CHESTPLATE));
        sp.setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot.FEET,
                new net.minecraft.item.ItemStack(net.minecraft.init.Items.DIAMOND_BOOTS));
        // Clear whatever the save left in the offhand so the captures only show the loadout.
        sp.setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot.OFFHAND, net.minecraft.item.ItemStack.EMPTY);
        sp.inventory.currentItem = 0;
    }

    /**
     * Moves the player authoritatively. The integrated server owns the position, so a client-side
     * set is snapped back next tick; drive it with a server-side /tp and mirror it on the client so
     * the render-time interpolation stays smooth.
     */
    /**
     * Runs a console command on the integrated server from the client thread.
     * Singleplayer shares the JVM, so the task lands on the next server tick and
     * the usual packet round trip updates the client state.
     */
    private static void serverCommand(Minecraft mc, String command) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server != null) {
            server.addScheduledTask(() -> server.getCommandManager().executeCommand(server, command));
        }
    }

    private static void tpPlayer(Minecraft mc, double x, double y, double z) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server != null) {
            final String who = mc.player.getName();
            server.addScheduledTask(() -> server.getCommandManager().executeCommand(server,
                    String.format("tp %s %.3f %.3f %.3f", who, x, y, z)));
        }
        mc.player.lastTickPosX = mc.player.posX;
        mc.player.lastTickPosY = mc.player.posY;
        mc.player.lastTickPosZ = mc.player.posZ;
        mc.player.setPosition(x, y, z);
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
    }

    // --- END_MODE (dragon healing beams, bug E) ---

    private static net.minecraft.entity.boss.EntityDragon endDragon;
    private static int endArmedAt = -1;
    private static net.minecraft.entity.item.EntityEnderCrystal endBeamCrystal;

    private static void endStage(Minecraft mc) {
        int t = ++ticks;
        if (mc.effectRenderer != null && mc.world != null) {
            mc.effectRenderer.clearEffects(mc.world); // keep the beam frame clean
        }
        if (t == 5) {
            IntegratedServer server = mc.getIntegratedServer();
            if (server != null) {
                final java.util.UUID who = mc.player.getUniqueID();
                server.addScheduledTask(() -> {
                    net.minecraft.entity.player.EntityPlayerMP mp =
                            server.getPlayerList().getPlayerByUUID(who);
                    if (mp != null) {
                        server.getPlayerList().transferPlayerToDimension(mp, 1,
                                new net.minecraft.world.Teleporter(server.getWorld(1)));
                    }
                });
            }
            Initializer.LOGGER.info("[autotest] end: dimension change scheduled");
            return;
        }
        if (mc.world == null || mc.world.provider.getDimension() != 1) {
            return; // The End is still loading
        }
        if (endDragon == null || endDragon.isDead) {
            endDragon = null;
            for (net.minecraft.entity.Entity e : mc.world.loadedEntityList) {
                if (e instanceof net.minecraft.entity.boss.EntityDragon d && !d.isDead) {
                    endDragon = d;
                    Initializer.LOGGER.info("[autotest] end: dragon found at {}, {}, {}",
                            d.posX, d.posY, d.posZ);
                    break;
                }
            }
            if (endDragon == null) {
                if (t % 40 == 0) {
                    Initializer.LOGGER.info("[autotest] end: waiting for dragon (t={})", t);
                }
                return;
            }
        }

        // RenderDragon draws the beam while hurtTime > 0 and healingEnderCrystal is
        // non-null - both plain client-side fields the renderer reads directly, so
        // pinning them here (Phase.END, after the world tick) reproduces the healing
        // state without waiting for the fight AI to reach it on its own.
        endDragon.hurtTime = 3;
        if (endBeamCrystal == null || endBeamCrystal.isDead) {
            // Pick the farthest crystal once and latch it: a near one hides the short
            // ribbon inside the dragon's own body volume (depth-rejected), a far one
            // makes the beam cross open sky. The dragon AI re-detects a healing crystal
            // on its own, so the pin below re-asserts the choice every tick.
            net.minecraft.entity.item.EntityEnderCrystal farthest = null;
            double best = -1.0;
            for (net.minecraft.entity.Entity e : mc.world.loadedEntityList) {
                if (e instanceof net.minecraft.entity.item.EntityEnderCrystal c && !c.isDead) {
                    double d2 = c.getDistanceSq(endDragon);
                    if (d2 > best) {
                        best = d2;
                        farthest = c;
                    }
                }
            }
            endBeamCrystal = farthest;
        }
        endDragon.healingEnderCrystal = endBeamCrystal;

        if (endArmedAt < 0) {
            endArmedAt = t;
            mc.player.capabilities.allowFlying = true;
            mc.player.capabilities.isFlying = true;
            Initializer.LOGGER.info("[autotest] end: camera armed, healing crystal {}",
                    endDragon.healingEnderCrystal == null ? "MISSING" : "set");
        }

        // Frame the beam from the side: stand off the crystal-to-head axis (a camera on
        // the axis foreshortens the ribbon to a dot) and look at its midpoint. Rotation
        // is client-authoritative, so aiming every tick cannot rubber-band.
        double ax, ay, az;
        if (endDragon.healingEnderCrystal != null) {
            ax = endDragon.healingEnderCrystal.posX - endDragon.posX;
            ay = endDragon.healingEnderCrystal.posY - (endDragon.posY + 2.0);
            az = endDragon.healingEnderCrystal.posZ - endDragon.posZ;
        } else {
            ax = ay = az = 0.0;
        }
        double horiz = Math.sqrt(ax * ax + az * az);
        double px = 0.0, pz = 0.0;
        if (horiz > 1.0e-3) {
            px = az / horiz;
            pz = -ax / horiz;
        }
        double midX = endDragon.posX + ax * 0.5;
        double midY = endDragon.posY + 2.0 + ay * 0.5;
        double midZ = endDragon.posZ + az * 0.5;
        if ((t - endArmedAt) % 5 == 1) {
            tpPlayer(mc, midX + px * 30.0, midY + 4.0, midZ + pz * 30.0);
        }
        double dx = midX - mc.player.posX;
        double dy = midY - mc.player.posY;
        double dz = midZ - mc.player.posZ;
        mc.player.rotationYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.rotationPitch = (float) Math.toDegrees(
                Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;

        if (t == endArmedAt + 40) {
            capture(mc, "endBeam");
        } else if (t == endArmedAt + 50 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
            Initializer.LOGGER.info("[autotest] done, shutting down");
            mc.shutdown();
        }
    }

    private static void startRain(Minecraft mc) {
        rainOn = true;
        mc.world.setRainStrength(1.0f);
        IntegratedServer server = mc.getIntegratedServer();
        if (server != null) {
            server.addScheduledTask(() ->
                    server.getCommandManager().executeCommand(server, "weather rain 1000000"));
        }
        Initializer.LOGGER.info("[autotest] downpour started");
    }

    /** Spawns a held-grass-block enderman on the ground 6 blocks along the view axis. */
    private static void spawnNightEnderman(Minecraft mc) {
        // The night perch slopes down to the south and has a small pool near the
        // crosshair: take the first dry column (an enderman in water takes damage and
        // teleports frantically, which would desync the GL reference pair).
        enderY = 0;
        for (double lat : new double[]{0, 2, -2, 4, -4}) {
            double x = mc.player.posX + lat;
            double z = mc.player.posZ + 6.0;
            int y = 120;
            while (y > 0 && !mc.world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z))
                    .getMaterial().blocksMovement()) {
                y--;
            }
            if (y > 0 && !mc.world.getBlockState(new net.minecraft.util.math.BlockPos(x, y + 1, z))
                    .getMaterial().isLiquid()) {
                enderX = x;
                enderZ = z;
                enderY = y + 1;
                break;
            }
        }
        if (enderY <= 0) {
            Initializer.LOGGER.warn("[autotest] no dry column for the enderman, skipping");
            return;
        }
        net.minecraft.entity.monster.EntityEnderman enderman =
                new net.minecraft.entity.monster.EntityEnderman(mc.world);
        enderman.setHeldBlockState(net.minecraft.init.Blocks.GRASS.getDefaultState());
        enderman.setPosition(enderX, enderY, enderZ);
        // Face the camera so the eye layer is visible.
        double toPlayer = Math.toDegrees(Math.atan2(
                -(mc.player.posX - enderX), mc.player.posZ - enderZ));
        enderman.rotationYaw = enderman.rotationYawHead = (float) toPlayer;
        nightEnderman = enderman;
        mc.world.spawnEntity(enderman);
        Initializer.LOGGER.info("[autotest] spawned enderman at ({},{},{})",
                String.format("%.1f", enderX), String.format("%.1f", enderY),
                String.format("%.1f", enderZ));
    }

    /** Points the camera so the enderman fills the lower frame without the crosshair
     *  hitting it: a stared-at enderman provokes and teleports to the player before the
     *  capture. */
    private static void aimAtEnderman(Minecraft mc) {
        if (nightEnderman == null) {
            return;
        }
        double dx = enderX - mc.player.posX;
        double dy = enderY + 1.7 - (mc.player.posY + 1.62);
        double dz = enderZ - mc.player.posZ;
        mc.player.rotationYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.rotationPitch = (float) -Math.toDegrees(
                Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))) - 12.0f;
    }

    /** Spawns a cow a few blocks in front of the client player, at eye level. */
    private static void spawnCow(Minecraft mc) {
        try {
            net.minecraft.entity.passive.EntityCow cow = new net.minecraft.entity.passive.EntityCow(mc.world);
            net.minecraft.util.math.Vec3d look = mc.player.getLook(1.0f);
            double x = mc.player.posX + look.x * 3.0;
            double y = mc.player.posY;
            double z = mc.player.posZ + look.z * 3.0;
            cow.setPosition(x, y, z);
            cow.rotationYawHead = mc.player.rotationYaw;
            // A custom name makes vanilla draw a name tag (immediate-mode FontRenderer
            // text inside the entity pass), which the entity captures then verify.
            cow.setCustomNameTag("Bessie");
            // Vanilla only draws a mob's name tag when it is crosshair-targeted or the
            // always-render flag is set (a name tag item sets it in-game).
            cow.setAlwaysRenderNameTag(true);
            mc.world.spawnEntity(cow);
            Initializer.LOGGER.info("[autotest] spawned cow at ({},{},{})",
                    String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z));
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[autotest] cow spawn failed", t);
        }
    }

    /**
     * Drives {@link #GUI_MODE}: title -> Options (dirt background) -> Language (scrollbar)
     * -> Video Settings -> Upscaling, capturing each after it has rendered a few frames,
     * then quitting.
     */
    private static int guiTicks;

    private static void guiStage(Minecraft mc) {
        if (mc.currentScreen instanceof GuiMainMenu) {
            if (!titleCaptured && net.vulkanmod.render.GuiRenderer.getRenderedFrames() >= 3) {
                titleCaptured = true;
                capture(mc, "title");
            }
            if (titleCaptured && ++titleTicks == 3) {
                mc.displayGuiScreen(new GuiOptions(mc.currentScreen, mc.gameSettings));
            }
        } else if (mc.currentScreen instanceof GuiOptions) {
            if (++guiTicks == 4) {
                capture(mc, "options");
            } else if (guiTicks == 10) {
                mc.displayGuiScreen(new GuiLanguage(mc.currentScreen, mc.gameSettings, mc.getLanguageManager()));
            }
        } else if (mc.currentScreen instanceof GuiLanguage) {
            if (++guiTicks == 24) {
                capture(mc, "scrollbar");
            } else if (guiTicks == 30) {
                mc.displayGuiScreen(new net.minecraft.client.gui.GuiVideoSettings(mc.currentScreen, mc.gameSettings));
            }
        } else if (mc.currentScreen instanceof net.minecraft.client.gui.GuiVideoSettings) {
            // The "Upscaling..." button added by GuiVideoSettingsM, then its screen.
            if (++guiTicks == 36) {
                capture(mc, "videoSettings");
            } else if (guiTicks == 37) {
                // Scroll to the end of the option list, where VulkanMod's rows are.
                ((net.vulkanmod.mixin.render.GuiVideoSettingsAccessor) mc.currentScreen)
                        .vulkanmod$getOptionsRowList().scrollBy(1000);
            } else if (guiTicks == 39) {
                capture(mc, "videoSettingsEnd");
            } else if (guiTicks == 44) {
                mc.displayGuiScreen(new net.vulkanmod.config.gui.UpscalingSettingsScreen(mc.currentScreen));
            }
        } else if (mc.currentScreen instanceof net.vulkanmod.config.gui.UpscalingSettingsScreen) {
            if (++guiTicks == 50) {
                capture(mc, "upscaling");
            } else if (guiTicks == 54) {
                mc.displayGuiScreen(new net.vulkanmod.config.gui.RayTracingSettingsScreen(mc.currentScreen));
            }
        } else if (mc.currentScreen instanceof net.vulkanmod.config.gui.RayTracingSettingsScreen) {
            if (++guiTicks == 60) {
                capture(mc, "rayTracing");
            } else if (guiTicks >= 66 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                Initializer.LOGGER.info("[autotest] done, shutting down");
                mc.shutdown();
            }
        }
    }

    /**
     * Scans outward from the player for the nearest water source block (server thread) and
     * records it in {@link #waterX}/{@link #waterY}/{@link #waterZ}. Used by WATER_MODE to
     * frame a body of water for the translucent-layer capture.
     */
    private static void scanForWater(Minecraft mc) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        double px = mc.player.posX, pz = mc.player.posZ;
        server.addScheduledTask(() -> {
            net.minecraft.world.World world = server.getWorld(0);
            if (world == null) {
                return;
            }
            int cx = (int) Math.floor(px), cz = (int) Math.floor(pz);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
            outer:
            for (int r = 2; r <= 160; r++) {
                for (int a = 0; a < 360; a += 10) {
                    double rad = Math.toRadians(a);
                    int x = cx + (int) (Math.cos(rad) * r);
                    int z = cz + (int) (Math.sin(rad) * r);
                    for (int y = 72; y >= 40; y--) {
                        // Only open-sky surface water: air for several blocks above (so it
                        // is a lit lake, not a shaded pool or cave) and full skylight, so the
                        // capture is bright enough to judge the water's transparency.
                        if (world.getBlockState(cursor.setPos(x, y, z)).getBlock() != Blocks.WATER) {
                            continue;
                        }
                        boolean openSky = true;
                        for (int dy = 1; dy <= 6; dy++) {
                            if (!world.isAirBlock(above.setPos(x, y + dy, z))) {
                                openSky = false;
                                break;
                            }
                        }
                        if (openSky && world.getLightFromNeighborsFor(EnumSkyBlock.SKY, cursor) >= 15) {
                            waterX = x;
                            waterY = y;
                            waterZ = z;
                            waterFound = true;
                            Initializer.LOGGER.info("[autotest] WATER_MODE found water at {},{},{}", x, y, z);
                            break outer;
                        }
                    }
                }
            }
            if (!waterFound) {
                Initializer.LOGGER.info("[autotest] WATER_MODE no natural water nearby, building a pool");
                buildWaterPool(world, cx, cz);
            }
        });
    }

    /**
     * Deterministic fallback for {@link #scanForWater}: carves a shallow 5x5 water pool on
     * the terrain surface a few blocks from the player (clearing any foliage above it), so
     * WATER_MODE always has a lit, open-sky translucent body to frame even on a dry spawn.
     */
    private static void buildWaterPool(net.minecraft.world.World world, int cx, int cz) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int r = 3; r <= 40 && !waterFound; r++) {
            for (int a = 0; a < 360 && !waterFound; a += 30) {
                double rad = Math.toRadians(a);
                int bx = cx + (int) (Math.cos(rad) * r);
                int bz = cz + (int) (Math.sin(rad) * r);
                // Topmost GROUND/rock/sand block with air above = the terrain surface (skips
                // tree canopies, which a naive "any solid" scan would stop on).
                int sy = -1;
                for (int y = 72; y >= 58; y--) {
                    net.minecraft.block.state.IBlockState state = world.getBlockState(cursor.setPos(bx, y, bz));
                    net.minecraft.block.material.Material m = state.getMaterial();
                    boolean ground = m == net.minecraft.block.material.Material.GROUND
                            || m == net.minecraft.block.material.Material.ROCK
                            || m == net.minecraft.block.material.Material.SAND;
                    // The cell above may be air or replaceable (grass tufts, flowers, snow);
                    // the water placement overwrites those, so they must not disqualify the spot.
                    if (ground && world.getBlockState(cursor.setPos(bx, y + 1, bz)).getBlock().isReplaceable(world, cursor)) {
                        sy = y;
                        break;
                    }
                }
                if (sy < 0) {
                    continue;
                }
                // Lay a 5x5 sheet of water one block above the chosen surface, clearing the
                // column above it. On gently sloping ground the sheet is roughly one block
                // deep; viewed from above, transparency reads as a tint over the bright
                // bottom, whereas an opaque layer would be solid blue.
                // A 1-block stone rim around a 5x5 water interior holds the sources in place
                // (open water would spread over the lip and drain down the cleared shaft).
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        boolean rim = Math.max(Math.abs(dx), Math.abs(dz)) == 3;
                        world.setBlockState(cursor.setPos(bx + dx, sy + 1, bz + dz),
                                rim ? Blocks.STONE.getDefaultState() : Blocks.WATER.getDefaultState());
                        for (int dy = 2; dy <= 14; dy++) {
                            world.setBlockState(cursor.setPos(bx + dx, sy + dy, bz + dz), Blocks.AIR.getDefaultState());
                        }
                    }
                }
                waterX = bx;
                waterY = sy + 1;
                waterZ = bz;
                waterFound = true;
                Initializer.LOGGER.info("[autotest] WATER_MODE built pool at {},{},{}", bx, sy + 1, bz);
            }
        }
        if (!waterFound) {
            Initializer.LOGGER.warn("[autotest] WATER_MODE could not build a pool (no flat surface found)");
        }
    }

    /**
     * Places the client camera a couple of blocks above and back from the found water block
     * and points it at the surface, so the capture shows the water top and its transparency.
     */
    private static void frameWater(Minecraft mc) {
        if (!waterFound) {
            Initializer.LOGGER.warn("[autotest] WATER_MODE nothing to frame (no water found)");
            return;
        }
        // Hover almost directly above the pool (a hair off-centre so the yaw stays defined)
        // and look nearly straight down. Being high and overhead keeps the camera clear of
        // the surrounding tree canopy, and the cleared column (see buildWaterPool) opens a
        // shaft to the water so the surface and its transparency are unobstructed.
        double tx = waterX + 0.5, ty = waterY + 1.0, tz = waterZ + 0.5;
        mc.player.capabilities.isFlying = true;
        mc.player.setPosition(tx + 0.6, waterY + 11.0, tz + 0.6);
        double dx = tx - mc.player.posX;
        double dy = ty - (mc.player.posY + mc.player.getEyeHeight());
        double dz = tz - mc.player.posZ;
        double xz = Math.sqrt(dx * dx + dz * dz);
        mc.player.rotationYaw = (float) ((-Math.atan2(-dx, -dz) - Math.PI) * 180.0 / Math.PI);
        mc.player.rotationPitch = (float) (Math.atan2(-dy, xz) * 180.0 / Math.PI);
        Initializer.LOGGER.info("[autotest] WATER_MODE framing water at {},{},{} yaw={} pitch={}",
                waterX, waterY, waterZ, String.format("%.1f", mc.player.rotationYaw),
                String.format("%.1f", mc.player.rotationPitch));
    }

    /**
     * Builds a deterministic plant test row on the server: a 3-tall stone pillar with a red
     * flower on top, a glass block with a red flower on top (transparent, so the flower's base
     * is visible against the glass edge), tall grass on the ground, and a 2-deep pit with a
     * flower on its rim. All at a logged anchor so {@link #framePlant} can aim at it.
     */
    private static void buildPlantScene(Minecraft mc) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) {
            return;
        }
        double px = mc.player.posX, pz = mc.player.posZ;
        server.addScheduledTask(() -> {
            net.minecraft.world.World world = server.getWorld(0);
            if (world == null) {
                return;
            }
            int bx = (int) Math.floor(px) + 3, bz = (int) Math.floor(pz) + 6;
            BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
            int sy = -1;
            for (int y = 90; y >= 40; y--) {
                net.minecraft.block.material.Material m =
                        world.getBlockState(c.setPos(bx, y, bz)).getMaterial();
                if (m == net.minecraft.block.material.Material.GROUND
                        || m == net.minecraft.block.material.Material.ROCK
                        || m == net.minecraft.block.material.Material.SAND) {
                    sy = y;
                    break;
                }
            }
            if (sy < 0) {
                Initializer.LOGGER.warn("[autotest] PLANT_MODE no ground surface found");
                return;
            }
            // Clear the build volume so foliage/terrain never overlaps the test blocks.
            for (int dx = -1; dx <= 7; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 1; dy <= 8; dy++) {
                        world.setBlockState(c.setPos(bx + dx, sy + dy, bz + dz), Blocks.AIR.getDefaultState());
                    }
                }
            }
            // Pillar (top face at sy+4) + flower: the flower base must sit exactly on sy+4.
            for (int dy = 1; dy <= 3; dy++) {
                world.setBlockState(c.setPos(bx, sy + dy, bz), Blocks.STONE.getDefaultState());
            }
            world.setBlockState(c.setPos(bx, sy + 4, bz), Blocks.RED_FLOWER.getDefaultState());
            // Glass (top face at sy+2) + flower: transparent support, base clearly readable.
            world.setBlockState(c.setPos(bx + 2, sy + 1, bz), Blocks.GLASS.getDefaultState());
            world.setBlockState(c.setPos(bx + 2, sy + 2, bz), Blocks.RED_FLOWER.getDefaultState());
            // Tall grass straight on the ground.
            world.setBlockState(c.setPos(bx + 4, sy + 1, bz), Blocks.TALLGRASS.getDefaultState());
            // A 2-deep pit with a flower on the near rim: a vertical offset makes it float.
            world.setBlockState(c.setPos(bx + 6, sy, bz), Blocks.AIR.getDefaultState());
            world.setBlockState(c.setPos(bx + 6, sy + 1, bz), Blocks.AIR.getDefaultState());
            world.setBlockState(c.setPos(bx + 5, sy + 1, bz), Blocks.RED_FLOWER.getDefaultState());
            plantX = bx;
            plantY = sy;
            plantZ = bz;
            Initializer.LOGGER.info("[autotest] PLANT_MODE built scene at {},{},{}", bx, sy, bz);
        });
    }

    /** Aims the camera horizontally along the plant row from a few blocks in front of it. */
    private static void framePlant(Minecraft mc) {
        if (plantY < 0) {
            Initializer.LOGGER.warn("[autotest] PLANT_MODE nothing to frame");
            return;
        }
        double tx = plantX + 3.0, ty = plantY + 2.5, tz = plantZ + 0.5;
        mc.player.capabilities.isFlying = true;
        mc.player.setPosition(plantX + 3.0, plantY + 3.0, plantZ - 8.0);
        double dx = tx - mc.player.posX;
        double dy = ty - (mc.player.posY + mc.player.getEyeHeight());
        double dz = tz - mc.player.posZ;
        double xz = Math.sqrt(dx * dx + dz * dz);
        mc.player.rotationYaw = (float) ((-Math.atan2(-dx, -dz) - Math.PI) * 180.0 / Math.PI);
        mc.player.rotationPitch = (float) (Math.atan2(-dy, xz) * 180.0 / Math.PI);
        Initializer.LOGGER.info("[autotest] PLANT_MODE framing at {},{},{} yaw={} pitch={}",
                plantX, plantY, plantZ, String.format("%.1f", mc.player.rotationYaw),
                String.format("%.1f", mc.player.rotationPitch));
    }

    /**
     * Debug: logs exactly what {@code renderRainSnow} sees at the player's column at capture
     * time, so a V-vs-GL weather-type mismatch can be attributed to the CPU decision (biome) or
     * to the draw. The snow/rain branch is {@code getTemperatureAtHeight(temp, precipY) < 0.15}.
     */
    private static void logWeatherDiag(Minecraft mc) {
        if (mc.world == null || mc.player == null) {
            return;
        }
        net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(mc.player);
        net.minecraft.world.biome.Biome b = mc.world.getBiome(p);
        int ph = mc.world.getPrecipitationHeight(p).getY();
        float t = b.getTemperature(p);
        float th = mc.world.getBiomeProvider().getTemperatureAtHeight(t, ph);
        Initializer.LOGGER.info("[autotest] WEATHERDIAG biome={} temp={} precipY={} tempAtH={} snowCheck={} raining={} strength={}",
                b.getBiomeName(), t, ph, th, th < 0.15f, mc.world.isRaining(), mc.world.getRainStrength(1.0f));
    }

    /**
     * Teleports the camera a few blocks from the {@code VULKANMOD_AUTOTEST_GOTO} block and
     * points it at that block, logging the block and the block above it (the plant) so the
     * capture can be correlated with the reported misplacement.
     */
    private static void doRepro(Minecraft mc) {
        String[] p = REPRO_SPEC.trim().split("\\s+");
        if (p.length < 5) {
            Initializer.LOGGER.warn("[autotest] REPRO needs \"x y z yaw pitch [time]\", got: {}", REPRO_SPEC);
            return;
        }
        double x = Double.parseDouble(p[0]);
        double y = Double.parseDouble(p[1]);
        double z = Double.parseDouble(p[2]);
        float yaw = Float.parseFloat(p[3]);
        float pitch = Float.parseFloat(p[4]);
        mc.player.capabilities.isFlying = true;
        mc.player.capabilities.allowFlying = true;
        mc.player.setPositionAndRotation(x, y, z, yaw, pitch);
        mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        if (p.length >= 6) {
            setWorldTime(Long.parseLong(p[5]));
        }
        Initializer.LOGGER.info("[autotest] REPRO camera at {},{},{} yaw={} pitch={}",
                x, y, z, yaw, pitch);
    }

    private static void gotoTarget(Minecraft mc) {
        String[] parts = GOTO_SPEC.trim().split("\\s+");
        if (parts.length < 3) {
            Initializer.LOGGER.warn("[autotest] GOTO needs \"x y z\", got: {}", GOTO_SPEC);
            return;
        }
        int tx = Integer.parseInt(parts[0]);
        int ty = Integer.parseInt(parts[1]);
        int tz = Integer.parseInt(parts[2]);
        mc.player.capabilities.isFlying = true;
        mc.player.capabilities.allowFlying = true;
        // Stand 4 blocks to the +Z side, a little above the block, look back at its top face.
        mc.player.setPosition(tx + 0.5, ty + 1.6, tz + 4.5);
        double dx = (tx + 0.5) - mc.player.posX;
        double dy = (ty + 0.5) - (mc.player.posY + mc.player.getEyeHeight());
        double dz = (tz + 0.5) - mc.player.posZ;
        double xz = Math.sqrt(dx * dx + dz * dz);
        mc.player.rotationYaw = (float) ((-Math.atan2(-dx, -dz) - Math.PI) * 180.0 / Math.PI);
        mc.player.rotationPitch = (float) (Math.atan2(-dy, xz) * 180.0 / Math.PI);
        net.minecraft.util.math.BlockPos p = new net.minecraft.util.math.BlockPos(tx, ty, tz);
        Initializer.LOGGER.info(
                "[autotest] GOTO to {},{},{} yaw={} pitch={} | block={} above={} chunk={}{}",
                tx, ty, tz, String.format("%.1f", mc.player.rotationYaw),
                String.format("%.1f", mc.player.rotationPitch),
                mc.world.getBlockState(p).getBlock().getLocalizedName(),
                mc.world.getBlockState(p.up()).getBlock().getLocalizedName(),
                tx >> 4, tz >> 4);
    }

    /**
     * Drives {@link #FPS_MODE}: pins the benchmark configuration, frames a fixed scene, holds
     * the camera perfectly still, then samples the debug FPS for a window and reports the
     * average/min/max. The scene is static (no re-meshing during sampling), so the number is a
     * pure terrain render-throughput measure comparable across renderer builds.
     */
    private static void fpsStage(Minecraft mc) {
        int tick = ++ticks;
        int settle = envInt("VULKANMOD_AUTOTEST_SETTLE", 250);
        int measure = envInt("VULKANMOD_AUTOTEST_MEASURE", 150);

        if (tick == 1) {
            // Fullscreen for a representative, compositor-free measurement (the user's
            // requirement): do it before the settle window so the swapchain resize settles.
            // VULKANMOD_AUTOTEST_NOFULLSCREEN keeps the small window (resolution probe).
            if (!mc.isFullScreen() && System.getenv("VULKANMOD_AUTOTEST_NOFULLSCREEN") == null) {
                mc.toggleFullscreen();
            }
            mc.gameSettings.pauseOnLostFocus = false;
            mc.gameSettings.limitFramerate = Integer.MAX_VALUE;
            mc.gameSettings.enableVsync = false;
            try { org.lwjgl.opengl.Display.setVSyncEnabled(false); } catch (Throwable ignored) { }
            // The swapchain present mode does not follow enableVsync by itself (M8).
            if (Initializer.isVulkanActive()) {
                net.vulkanmod.vulkan.Vulkan.setVsync(false);
            }
            int rd = envInt("VULKANMOD_AUTOTEST_RENDER", 8);
            mc.gameSettings.renderDistanceChunks = rd;
            setWorldTime(6000L); // noon: full-bright, removes time-of-day variance
            Initializer.LOGGER.info("[autotest] FPS bench render={} settle={} measure={}",
                    rd, settle, measure);
        }
        if (tick == 5) {
            // Frame a wide, static view: stand at the GOTO coords (or elevated at spawn) and
            // look horizontally so the frustum spans as many chunks as possible - the
            // draw-call-heavy case that chunk batching targets. Yaw/pitch are overridable.
            mc.player.capabilities.isFlying = true;
            mc.player.capabilities.allowFlying = true;
            if (GOTO_SPEC != null) {
                String[] p = GOTO_SPEC.trim().split("\\s+");
                mc.player.setPosition(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            } else {
                mc.player.setPosition(mc.player.posX, Math.max(mc.player.posY, 85), mc.player.posZ);
            }
            mc.player.rotationYaw = envInt("VULKANMOD_AUTOTEST_YAW", 45);
            mc.player.rotationPitch = envInt("VULKANMOD_AUTOTEST_PITCH", 0);
        }
        if (tick == 6) {
            pinX = mc.player.posX; pinY = mc.player.posY; pinZ = mc.player.posZ;
            pinYaw = mc.player.rotationYaw; pinPitch = mc.player.rotationPitch;
            pinSet = true;
        }
        if (tick == 10 && System.getenv("VULKANMOD_AUTOTEST_RAIN") != null) {
            // Benchmark with the weather pass active: the rain quads are a batched
            // POSITION_TEX_COLOR draw every frame - exactly what the GL fallback
            // stalled on (framerate in the twenties during a downpour).
            startRain(mc);
        }
        // Re-apply the pinned transform every tick so neither gravity nor residual motion
        // can drift the view; a moving camera would re-mesh chunks and skew the sample.
        if (pinSet) {
            mc.player.capabilities.isFlying = true;
            mc.player.capabilities.allowFlying = true;
            mc.player.setPosition(pinX, pinY, pinZ);
            mc.player.rotationYaw = pinYaw;
            mc.player.rotationPitch = pinPitch;
            mc.player.motionX = mc.player.motionY = mc.player.motionZ = 0;
        }

        // Optional: spawn client-side mobs in front of the camera (added to loadedEntityList
        // only, never to the world/chunk save) so the entity render path can be inspected.
        // Spawns a minute before settling so the one-time costs (new pipeline creation, the
        // atlas texture download) are paid well before the capture and the FPS window.
        if (tick == settle - 60 && System.getenv("VULKANMOD_AUTOTEST_SPAWN") != null) {
            spawnTestMobs(mc, envInt("VULKANMOD_AUTOTEST_SPAWN", 4));
        }

        // One-shot capture in the settled, fullscreen state so the benchmark view can be
        // inspected for missing chunks (the swapchain capture works at any resolution).
        if (tick == settle && System.getenv("VULKANMOD_AUTOTEST_FPS_CAPTURE") != null) {
            capture(mc, "fps_fullscreen");
        }
        // Second frame a few ticks later: the rain offset advances with rendererUpdateCount,
        // so a moving streak proves the weather pass animates (a static diff = frozen rain).
        if (tick == settle + 6 && System.getenv("VULKANMOD_AUTOTEST_FPS_CAPTURE") != null) {
            capture(mc, "fps_rain_b");
        }
        // Dump the exact per-section MVP matrices of the frame right after settling.
        if (tick == settle && net.vulkanmod.render.TerrainRenderer.DUMP_MVP) {
            net.vulkanmod.render.TerrainRenderer.armMatrixDump();
        }

        // Measurement window. Without a settle-time capture it opens right after settling
        // (same as before). The capture stalls the game loop for a second+ and its catch-up
        // update zeroes debugFPS (fpsCounter had no frames in that second), while client
        // ticks freeze during the stall - a fixed tick offset would sample the dead zone
        // right after the stall. So wait for the FPS counter to come back, with a hard
        // fallback so the run always terminates even if it never does.
        if (tick > settle && !measuring
                && (Minecraft.getDebugFPS() > 0 || tick > settle + 120)) {
            measuring = true;
            // Two more seconds after the counter comes back: its first post-stall value is
            // the frame count of the stall second, the second one can still be a partial
            // boundary; only the third reflects the steady rate.
            measureStart = tick + 40;
        }
        if (measuring && tick > measureStart && tick <= measureStart + measure) {
            int fps = Minecraft.getDebugFPS();
            if (fps > 0) {
                fpsSamples++;
                fpsSum += fps;
                if (fps < fpsMin) fpsMin = fps;
                if (fps > fpsMax) fpsMax = fps;
            }
        }
        if (measuring && tick == measureStart + measure + 3) {
            double avg = fpsSamples == 0 ? 0 : (double) fpsSum / fpsSamples;
            Initializer.LOGGER.info("[autotest] FPS RESULT avg={} min={} max={} samples={} res={}x{} fs={} pos={},{},{}",
                    String.format("%.1f", avg), fpsMin == Long.MAX_VALUE ? 0 : fpsMin, fpsMax,
                    fpsSamples, mc.displayWidth, mc.displayHeight, mc.isFullScreen(),
                    String.format("%.1f", mc.player.posX),
                    String.format("%.1f", mc.player.posY), String.format("%.1f", mc.player.posZ));
            if (worldGenerated) {
                Initializer.LOGGER.warn("[autotest] FPS RESULT is not representative: {} was generated "
                        + "during this run; run the bench again", WORLD_NAME);
            }
            // Second atlas dump ~measure frames after the capture one: the animated tiles must
            // have advanced, proving the tile mirror is live (not stale at the stitched frame).
            try {
                net.vulkanmod.render.TextureBridge.dumpDebug(mc.gameDir);
            } catch (Throwable t) {
                Initializer.LOGGER.warn("[autotest] final texture dump failed", t);
            }
            if (System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
                Initializer.LOGGER.info("[autotest] done, shutting down");
                mc.shutdown();
            }
        }
    }

    /** Dumps the inputs the cloud layer is built from, for autotest forensics. */
    private static void logClouds(Minecraft mc) {        try {
            net.minecraft.util.math.Vec3d colour = mc.world.getCloudColour(mc.getRenderPartialTicks());
            Initializer.LOGGER.info(
                    "[autotest] clouds setting={} shouldRender={} cloudHeight={} playerY={} eye=({},{},{}) colour=({},{},{})",
                    mc.gameSettings.clouds, mc.gameSettings.shouldRenderClouds(),
                    mc.world.provider.getCloudHeight(),
                    String.format("%.1f", mc.player.posY),
                    String.format("%.1f", mc.player.posX), String.format("%.1f", mc.player.posY),
                    String.format("%.1f", mc.player.posZ),
                    String.format("%.3f", colour.x), String.format("%.3f", colour.y), String.format("%.3f", colour.z));
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[autotest] cloud log failed", t);
        }
    }

    /** Sets the overworld time on the integrated server so the client syncs it. */
    private static void setWorldTime(long time) {
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server != null) {
            server.addScheduledTask(() -> {
                net.minecraft.world.World world = server.getWorld(0);
                if (world != null) {
                    world.setWorldTime(time);
                }
            });
        }
    }

    /** Latch input activity for the interval between {@link #focusState} reports. */
    private static void sampleInput(Minecraft mc) {
        try {
            if (org.lwjgl.input.Keyboard.isKeyDown(17)) { // 'W'
                wTicks++;
            }
            if (org.lwjgl.input.Keyboard.isKeyDown(57)) { // space
                spaceTicks++;
            }
            net.minecraft.client.entity.EntityPlayerSP p = mc.player;
            if (p != null) {
                if (haveLast) {
                    if (Math.abs(p.rotationYaw - lastYaw) > 0.1f || Math.abs(p.rotationPitch - lastPitch) > 0.1f) {
                        turnTicks++;
                    }
                    double dx = p.posX - lastX, dy = p.posY - lastY, dz = p.posZ - lastZ;
                    if (dx * dx + dy * dy + dz * dz > 0.0025) {
                        moveTicks++;
                    }
                }
                lastYaw = p.rotationYaw;
                lastPitch = p.rotationPitch;
                lastX = p.posX;
                lastY = p.posY;
                lastZ = p.posZ;
                haveLast = true;
            }
        } catch (Throwable ignored) {
        }
    }

    /** Snapshot of the window/input focus chain, for diagnosing dead input. */
    private static String focusState(Minecraft mc) {
        try {
            String keys = String.format("fwd=%b back=%b left=%b right=%b jump=%b sneak=%b rawW=%b rawSpace=%b lmb=%b",
                    mc.gameSettings.keyBindForward.isKeyDown(), mc.gameSettings.keyBindBack.isKeyDown(),
                    mc.gameSettings.keyBindLeft.isKeyDown(), mc.gameSettings.keyBindRight.isKeyDown(),
                    mc.gameSettings.keyBindJump.isKeyDown(), mc.gameSettings.keyBindSneak.isKeyDown(),
                    org.lwjgl.input.Keyboard.isKeyDown(17), org.lwjgl.input.Keyboard.isKeyDown(57),
                    org.lwjgl.input.Mouse.isButtonDown(0));
            String act = String.format("input[w=%d sp=%d turn=%d move=%d] pos=%.1f,%.1f,%.1f yaw=%.1f pitch=%.1f",
                    wTicks, spaceTicks, turnTicks, moveTicks,
                    mc.player == null ? 0 : mc.player.posX, mc.player == null ? 0 : mc.player.posY,
                    mc.player == null ? 0 : mc.player.posZ, mc.player == null ? 0 : mc.player.rotationYaw,
                    mc.player == null ? 0 : mc.player.rotationPitch);
            wTicks = spaceTicks = turnTicks = moveTicks = 0;
            return String.format("active=%b inGameFocus=%b mouseGrabbed=%b screen=%s sdlFocus=%s | %s | %s",
                    org.lwjgl.opengl.Display.isActive(), mc.inGameHasFocus, org.lwjgl.input.Mouse.isGrabbed(),
                    mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName(),
                    Long.toHexString(org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardFocus()), keys, act);
        } catch (Throwable t) {
            return "focusState unavailable: " + t;
        }
    }

    private static int titleTicks;
    private static boolean titleCaptured;
    private static double cloudAnchorX;

    /**
     * Spawns client-side-only cows in front of the camera for entity-render inspection. They
     * are added to {@code loadedEntityList} (so RenderGlobal draws them) but never to the
     * chunk entity lists or the save, so they vanish on reload and cannot corrupt the world.
     */
    private static void spawnTestMobs(Minecraft mc, int n) {
        if (mc.world == null || mc.player == null) {
            return;
        }
        double yaw = Math.toRadians(mc.player.rotationYaw);
        for (int i = 0; i < n; ++i) {
            net.minecraft.entity.passive.EntityCow cow = new net.minecraft.entity.passive.EntityCow(mc.world);
            placeInFront(mc, cow, 3.0 + i * 1.7, 0.0);
        }
        Initializer.LOGGER.info("[autotest] spawned {} client-side cows", n);

        // Extra coverage for the entity-layer passes: a hurt cow (red overlay), an
        // enderman (additive eye pass on a second texture) and a dropped sword
        // (ITEM-format model drawn from the atlas).
        net.minecraft.entity.passive.EntityCow hurtCow = new net.minecraft.entity.passive.EntityCow(mc.world);
        placeInFront(mc, hurtCow, 4.5, -2.5);
        hurtCow.hurtTime = 1000; // stays >0 through the capture window

        net.minecraft.entity.monster.EntityEnderman enderman = new net.minecraft.entity.monster.EntityEnderman(mc.world);
        placeInFront(mc, enderman, 4.5, 2.5);

        net.minecraft.item.Item sword = net.minecraft.item.Item.REGISTRY
                .getObject(new net.minecraft.util.ResourceLocation("minecraft:diamond_sword"));
        if (sword != null) {
            double ix = mc.player.posX - Math.sin(yaw) * 2.2;
            double iz = mc.player.posZ + Math.cos(yaw) * 2.2;
            int swordY = (int) groundY(mc, ix, iz);
            net.minecraft.entity.item.EntityItem item = new net.minecraft.entity.item.EntityItem(
                    mc.world, ix, swordY + 0.2, iz, new net.minecraft.item.ItemStack(sword));
            item.setNoGravity(true);
            item.lifespan = 6000;
            mc.world.loadedEntityList.add(item);
        }
        Initializer.LOGGER.info("[autotest] spawned hurt cow, enderman and dropped sword");
    }

    /** Puts a client-side entity on the ground in the player's view (dist ahead, lateral right). */
    private static void placeInFront(Minecraft mc, net.minecraft.entity.Entity entity, double dist, double lateral) {
        double yaw = Math.toRadians(mc.player.rotationYaw);
        double x = mc.player.posX - Math.sin(yaw) * dist + Math.cos(yaw) * lateral;
        double z = mc.player.posZ + Math.cos(yaw) * dist + Math.sin(yaw) * lateral;
        // Drop onto the ground: scan down from just above the player until a block that
        // stops movement; otherwise the entity hangs at the player's eye-level Y (the
        // pinned camera can be well above the terrain in front).
        double y = groundY(mc, x, z);
        entity.setPosition(x, y, z);
        entity.prevPosX = x; entity.prevPosY = y; entity.prevPosZ = z;
        entity.lastTickPosX = x; entity.lastTickPosY = y; entity.lastTickPosZ = z;
        entity.rotationYaw = mc.player.rotationYaw + 180.0f;
        entity.rotationPitch = 0.0f;
        if (entity instanceof net.minecraft.entity.EntityLivingBase living) {
            // Face the camera head-on (the head yaw is what the model renders).
            living.rotationYawHead = entity.rotationYaw;
            living.renderYawOffset = entity.rotationYaw;
            living.prevRenderYawOffset = entity.rotationYaw;
        }
        mc.world.loadedEntityList.add(entity);
    }

    /** Y of the surface to stand on: scan down from above the player to the first solid block. */
    private static double groundY(Minecraft mc, double x, double z) {
        int y = (int) mc.player.posY + 2;
        while (y > 0 && !mc.world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)).getMaterial().blocksMovement()) {
            y--;
        }
        return y + 1;
    }

    private static void capture(Minecraft mc, String name) {
        Initializer.LOGGER.info("[autotest] capture {} clientTime={} yaw={} pitch={}", name,
                mc.world == null ? -1 : mc.world.getWorldTime() % 24000L,
                mc.player == null ? 0 : mc.player.rotationYaw,
                mc.player == null ? 0 : mc.player.rotationPitch);
        if (Initializer.isVulkanActive()) {
            net.vulkanmod.render.TerrainRenderer.logState(name);
            try {
                net.vulkanmod.render.TextureBridge.dumpDebug(mc.gameDir);
            } catch (Throwable t) {
                Initializer.LOGGER.warn("[autotest] texture dump failed", t);
            }
            File out = new File(mc.gameDir, "vm_autotest_" + name + ".png");
            Drawer.requestCapture(out);
            Initializer.LOGGER.info("[autotest] capture requested -> {}", out);
        }

        // Reference: what the vanilla GL pipeline renders into the main framebuffer. In
        // Vulkan mode this readback (plus the PNG encode) is a ~1s stall and the frame it
        // captures is never presented anyway - the proper reference comes from a
        // VULKANMOD_DISABLE=1 run. Kept behind a knob for the crack-detector workflow.
        if (!Initializer.isVulkanActive() || System.getenv("VULKANMOD_AUTOTEST_GLREF") != null) {
            try {
                net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.gameDir, "vm_gl_" + name + ".png",
                        mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            } catch (Exception e) {
                Initializer.LOGGER.warn("[autotest] GL reference failed", e);
            }
        }
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : Integer.parseInt(value);
    }
}
