package net.vulkanmod;

import net.minecraft.client.Minecraft;
import net.vulkanmod.config.Config;
import net.vulkanmod.render.CloudRenderer;
import net.vulkanmod.render.EntityRenderer;
import net.vulkanmod.render.GuiRenderer;
import net.vulkanmod.render.SkyRenderer;
import net.vulkanmod.render.TerrainRenderer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLVideo;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central entry point for the renderer, ported from the Fabric Initializer of the
 * original VulkanMod to the Forge/Cleanroom mod lifecycle.
 */
public class Initializer {

    public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

    public static final String VERSION = VulkanModMod.VERSION;
    public static Config CONFIG;

    private static boolean initialized;
    private static boolean vulkanActive;
    private static boolean initGaveUp;
    private static int initAttempts;

    /** Diagnostic knob: VULKANMOD_DISABLE=1 keeps the game on the vanilla GL renderer. */
    private static final boolean DISABLED = System.getenv("VULKANMOD_DISABLE") != null;

    /** Thrown while the game window has not been created yet; initialization is retried. */
    private static class WindowNotReady extends Exception {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        LOGGER.info("== VulkanMod ==");

        Path configPath = Paths.get("config", "vulkanmod_settings.json").toAbsolutePath();
        CONFIG = Config.load(configPath);
        if (CONFIG == null) {
            CONFIG = new Config();
            CONFIG.write(configPath);
        }
    }

    /**
     * Initializes the Vulkan renderer once the game window exists. Called from a mixin
     * at the end of {@code Minecraft.init()} and retried every frame until the window
     * shows up. On hard failure the game keeps running with the vanilla OpenGL renderer.
     */
    public static void initVulkan() {
        if (vulkanActive || initGaveUp) {
            return;
        }
        if (DISABLED) {
            initGaveUp = true;
            LOGGER.info("Vulkan renderer disabled via VULKANMOD_DISABLE, staying on the vanilla GL renderer");
            return;
        }

        // Mod preInit may fire after Minecraft.init(); make sure the config exists
        // before the Vulkan classes touch it.
        init();

        initAttempts++;
        try {
            long window = findSdlWindow();
            LOGGER.info("SDL window handle: 0x{}", Long.toHexString(window));

            // Guard: our LWJGL's SDL instance must be the one that owns the window,
            // otherwise every SDL call would crash on a foreign window pointer.
            if (SDLVideo.SDL_GL_GetCurrentWindow() == 0L && SDLKeyboard.SDL_GetKeyboardFocus() == 0L) {
                if (initAttempts % 300 == 0) {
                    LOGGER.warn("Our SDL instance (LWJGL {}) does not see the game window yet (attempt {})",
                            org.lwjgl.Version.getVersion(), initAttempts);
                }
                return;
            }

            VRenderSystem.setWindow(window);
            VRenderSystem.initRenderer();

            new Drawer();
            TerrainRenderer.init();
            SkyRenderer.init();
            CloudRenderer.init();
            EntityRenderer.init();
            GuiRenderer.init();

            vulkanActive = true;
            LOGGER.info("Vulkan renderer initialized (swapchain format {})",
                    net.vulkanmod.vulkan.Vulkan.getSwapChainImageFormat());
        } catch (WindowNotReady e) {
            if (initAttempts == 1 || initAttempts % 120 == 0) {
                LOGGER.info("Game window not available yet (attempt {})", initAttempts);
            }
            if (initAttempts > 1200) {
                initGaveUp = true;
                LOGGER.error("No game window after {} attempts, staying on OpenGL", initAttempts);
            }
        } catch (Throwable t) {
            initGaveUp = true;
            LOGGER.error("Vulkan initialization failed, falling back to OpenGL", t);
        }
    }

    public static boolean isVulkanActive() {
        return vulkanActive;
    }

    /**
     * Opens the Vulkan frame for the upcoming world render. Called from the head of
     * {@code EntityRenderer.updateCameraAndRender}; the frame is closed and presented
     * in {@code Minecraft.updateDisplay} (see {@link #presentFrame()}).
     */
    public static void beginFrame() {
        if (!vulkanActive) {
            return;
        }
        try {
            net.vulkanmod.vulkan.Vulkan.pollWindowResize();
            VRenderSystem.updateClearColorFromFog();
            net.vulkanmod.render.TextureBridge.beginFrame();
            net.vulkanmod.render.TerrainRenderer.onFrameBegin();
            Drawer.getInstance().initiateRenderPass();
        } catch (Throwable t) {
            reportFrameError("begin", t);
        }
    }

    /**
     * Closes the Vulkan frame started by {@link #beginFrame()} and presents it.
     * Frames in which the world was not rendered (e.g. while the window is unfocused)
     * present an empty clear frame.
     *
     * <p>{@code Minecraft.updateDisplay()} is cancelled while Vulkan is active (see
     * {@code MinecraftM}), which also skips its {@code Display.update()} call. That call
     * is what pumps the SDL event queue feeding the LWJGL2 {@code Keyboard}/{@code Mouse}
     * shim, so without an explicit pump here keyboard and mouse input would go dead.
     * {@link org.lwjgl.opengl.Display#processMessages()} runs just the pump (no GL swap),
     * keeping Vulkan as the sole presenter.</p>
     */
    public static void presentFrame() {
        try {
            org.lwjgl.opengl.Display.processMessages();
            org.lwjgl.input.Mouse.updateCursor();

            Drawer drawer = Drawer.getInstance();
            if (!drawer.isActive()) {
                drawer.initiateRenderPass();
            }
            drawer.endRenderPass();
            drawer.submitDraw();
        } catch (Throwable t) {
            reportFrameError("present", t);
        }
    }

    private static int frameErrors;

    private static void reportFrameError(String what, Throwable t) {
        if (++frameErrors <= 5) {
            LOGGER.error("Vulkan frame error ({}), attempt {}", what, frameErrors, t);
        }
    }

    /** Called by a mixin when the window framebuffer size may have changed. */
    public static void onFramebufferSize(int width, int height) {
        if (!vulkanActive) {
            return;
        }
        if (width != lastWidth || height != lastHeight) {
            lastWidth = width;
            lastHeight = height;
            Drawer.shouldRecreate = true;
        }
    }

    private static int lastWidth = -1;
    private static int lastHeight = -1;

    /** Handle reported by the mixin on Cleanroom's SDL window builder. */
    private static long capturedWindowHandle;

    public static void onWindowCreated(long sdlWindowHandle) {
        capturedWindowHandle = sdlWindowHandle;
        LOGGER.info("SDL window created: 0x{}", Long.toHexString(sdlWindowHandle));
    }

    /**
     * Locates the SDL window handle. Cleanroom's own SDL window stack is preferred
     * (its {@code Window.main()}), then the lwjgly window bridge, then SDL itself.
     */
    private static long findSdlWindow() throws WindowNotReady {
        if (capturedWindowHandle != 0L) {
            return capturedWindowHandle;
        }

        long window = findCleanroomWindow();
        if (window != 0L) {
            return window;
        }

        try {
            if (com.cleanroommc.lwjgly.LWJGLY.hasWindowBridge()) {
                window = com.cleanroommc.lwjgly.LWJGLY.windowBridge().handle();
                if (window != 0L) {
                    return window;
                }
            }
        } catch (LinkageError ignored) {
        }

        window = SDLVideo.SDL_GL_GetCurrentWindow();
        if (window != 0L) {
            return window;
        }

        window = SDLKeyboard.SDL_GetKeyboardFocus();
        if (window != 0L) {
            return window;
        }

        throw new WindowNotReady();
    }

    /** Reads {@code com.cleanroommc.client.sdl.Window.main().handle()} reflectively. */
    private static long findCleanroomWindow() {
        try {
            Class<?> windowClass = Class.forName("com.cleanroommc.client.sdl.Window", false,
                    Minecraft.class.getClassLoader());
            Method main = windowClass.getDeclaredMethod("main");
            main.setAccessible(true);
            Object window = main.invoke(null);
            if (window != null) {
                return (long) windowClass.getMethod("handle").invoke(window);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return 0L;
    }

    private Initializer() {
    }
}
