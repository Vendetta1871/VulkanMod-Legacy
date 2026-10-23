package net.vulkanmod.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
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
                if (mc.currentScreen instanceof GuiMainMenu) {
                    if (!titleCaptured) {
                        titleCaptured = true;
                        capture(mc, "title");
                    }
                    mc.gameSettings.pauseOnLostFocus = false;
                    WorldSettings settings = new WorldSettings(0x5EEDL, GameType.CREATIVE,
                            false, false, WorldType.DEFAULT);
                    settings.enableCommands();
                    Initializer.LOGGER.info("[autotest] launching integrated world");
                    mc.launchIntegratedServer("vm_autotest", "vm_autotest", settings);
                    stage = 1;
                }
            }
            case 1 -> {
                if (mc.player != null && mc.world != null && mc.currentScreen == null && ++ticks > 40) {
                    IntegratedServer server = mc.getIntegratedServer();
                    if (server != null) {
                        server.addScheduledTask(() -> {
                            net.minecraft.world.World world = server.getWorld(0);
                            if (world != null) {
                                world.setWorldTime(6000); // noon (angle 0)
                            }
                        });
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
                if (warmupTicks < WARMUP) {
                    if (++warmupTicks % 40 == 0) {
                        Initializer.LOGGER.info("[autotest] warmup fps {} | {}", Minecraft.getDebugFPS(), focusState(mc));
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
