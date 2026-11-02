package net.vesu.config;

import com.cleanroommc.client.sdl.SDL;
import com.cleanroommc.client.sdl.Window;
import com.cleanroommc.client.sdl.video.DisplayMode;
import net.minecraftforge.common.ForgeEarlyConfig;
import net.vesu.Initializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Exclusive fullscreen video modes of the display the window is on, for the Fullscreen
 * Resolution option. A mode is stored as {@code WIDTHxHEIGHT@HZ}; null is the desktop mode,
 * which is what Cleanroom picks by itself.
 */
public final class DisplayModes {

    /** Cleanroom's borderless setting covers the display instead of changing its mode. */
    public static boolean supported() {
        return !ForgeEarlyConfig.WINDOW_BORDERLESS_REPLACES_FULLSCREEN && window() != null;
    }

    /** Every mode of the window's display, largest and fastest first, without duplicates. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        Window window = window();
        if (window == null) return names;
        try {
            List<DisplayMode> modes = new ArrayList<>(window.display().modes());
            modes.sort(Comparator.comparingInt((DisplayMode m) -> m.width() * m.height())
                    .thenComparingDouble(DisplayMode::refreshRate).reversed());
            for (DisplayMode m : modes) {
                String name = name(m);
                if (!names.contains(name)) names.add(name);
            }
        } catch (RuntimeException e) {
            Initializer.LOGGER.warn("Could not list the display modes", e);
        }
        return names;
    }

    public static String name(DisplayMode m) {
        return m.width() + "x" + m.height() + "@" + Math.round(m.refreshRate());
    }

    /** "1920x1080 60Hz" (short enough for a slider row), or "Current" for null. */
    public static String label(String name) {
        if (name == null) return "Current";
        return name.replace("@", " ") + "Hz";
    }

    /**
     * Applies the configured mode when the window is fullscreen, after Cleanroom entered its
     * own (the desktop mode). {@code fromOption}: the option changed while fullscreen, so the
     * desktop mode is set again when the option went back to Current.
     */
    public static void apply(boolean fullscreen, boolean fromOption) {
        Window window = window();
        if (!fullscreen || window == null || !supported()) return;
        String wanted = Initializer.CONFIG.fullscreenMode;
        if (wanted == null && !fromOption) return;
        try {
            DisplayMode mode = find(window, wanted);
            if (mode == null && wanted != null) {
                Initializer.LOGGER.warn("Fullscreen mode {} is not offered by the display, using the desktop mode", wanted);
            }
            if (mode != null) window.fullscreenMode(mode);
            else window.exclusiveFullscreen();
        } catch (RuntimeException e) {
            Initializer.LOGGER.warn("Could not set the fullscreen mode {}", wanted, e);
        }
    }

    private static DisplayMode find(Window window, String name) {
        if (name == null) return null;
        for (DisplayMode m : window.display().modes()) {
            if (name(m).equals(name.toLowerCase(Locale.ROOT))) return m;
        }
        return null;
    }

    private static Window window() {
        try {
            return SDL.window();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private DisplayModes() {
    }
}
