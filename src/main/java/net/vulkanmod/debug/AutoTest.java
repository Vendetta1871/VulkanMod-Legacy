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
     * real save to reproduce bugs that only show up in that terrain.
     */
    private static final String WORLD_NAME =
            System.getenv().getOrDefault("VULKANMOD_AUTOTEST_WORLD", "vm_autotest");

    /**
     * Time-series repro: with {@code VULKANMOD_AUTOTEST_BURST=1} the world is entered exactly
     * as saved (time of day untouched) and a capture is taken roughly every second for five
     * seconds, to pin down when a rendering state changes after spawn.
     */
    private static final boolean BURST_MODE = System.getenv("VULKANMOD_AUTOTEST_BURST") != null;

    /** Player position captured on the first burst tick, to pin the camera still. */
    private static double[] pinPos;

    private static boolean waterFound;
    private static int waterX, waterY, waterZ;

    private static int stage;
    private static int ticks;
    private static int warmupTicks;

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
                    if (!titleCaptured && net.vulkanmod.render.GuiRenderer.getRenderedFrames() >= 3) {
                        titleCaptured = true;
                        capture(mc, "title");
                    }
                    if (titleCaptured && ++titleTicks >= 3) {
                        mc.gameSettings.pauseOnLostFocus = false;
                        WorldSettings settings = new WorldSettings(0x5EEDL, GameType.CREATIVE,
                                false, false, WorldType.DEFAULT);
                        settings.enableCommands();
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
                        mc.player.rotationPitch = 0; // look at the horizon
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

                if (WATER_MODE) {
                    if (++ticks == 5) {
                        scanForWater(mc);
                    } else if (ticks == 30) {
                        frameWater(mc);
                    } else if (ticks == 80) {
                        capture(mc, "water");
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
                } else if (ticks == 255 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
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

    /** Spawns a cow a few blocks in front of the client player, at eye level. */
    private static void spawnCow(Minecraft mc) {
        try {
            net.minecraft.entity.passive.EntityCow cow = new net.minecraft.entity.passive.EntityCow(mc.world);
            net.minecraft.util.math.Vec3d look = mc.player.getLook(1.0f);
            double x = mc.player.posX + look.x * 4.0;
            double y = mc.player.posY;
            double z = mc.player.posZ + look.z * 4.0;
            cow.setPosition(x, y, z);
            cow.rotationYawHead = mc.player.rotationYaw;
            mc.world.spawnEntity(cow);
            Initializer.LOGGER.info("[autotest] spawned cow at ({},{},{})",
                    String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z));
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[autotest] cow spawn failed", t);
        }
    }

    /**
     * Drives {@link #GUI_MODE}: title -> Options (dirt background) -> Language (scrollbar),
     * capturing each after it has rendered a few frames, then quitting.
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
            } else if (guiTicks >= 30 && System.getenv("VULKANMOD_AUTOTEST_STAY") == null) {
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

    /** Dumps the inputs the cloud layer is built from, for autotest forensics. */
    private static void logClouds(Minecraft mc) {
        try {
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
    private static void setWorldTime(long time) {        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
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

    private static void capture(Minecraft mc, String name) {
        Initializer.LOGGER.info("[autotest] capture {} clientTime={} yaw={} pitch={}", name,
                mc.world == null ? -1 : mc.world.getWorldTime() % 24000L,
                mc.player == null ? 0 : mc.player.rotationYaw,
                mc.player == null ? 0 : mc.player.rotationPitch);
        net.vulkanmod.render.TerrainRenderer.logState(name);
        try {
            net.vulkanmod.render.TextureBridge.dumpDebug(mc.gameDir);
        } catch (Throwable t) {
            Initializer.LOGGER.warn("[autotest] texture dump failed", t);
        }
        File out = new File(mc.gameDir, "vm_autotest_" + name + ".png");
        Drawer.requestCapture(out);
        Initializer.LOGGER.info("[autotest] capture requested -> {}", out);

        // Reference: what the vanilla GL pipeline renders into the main framebuffer
        try {
            net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.gameDir, "vm_gl_" + name + ".png",
                    mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        } catch (Exception e) {
            Initializer.LOGGER.warn("[autotest] GL reference failed", e);
        }
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : Integer.parseInt(value);
    }
}
