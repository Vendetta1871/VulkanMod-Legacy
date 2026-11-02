package net.vesu;

/**
 * Environment knobs (tests, autotest runs, diagnostics). Every variable is named
 * {@code VESU_*}; the {@code VULKANMOD_*} spelling from before the rename is still read.
 */
public final class Env {

    private static final String PREFIX = "VESU_", LEGACY_PREFIX = "VULKANMOD_";

    /** The variable's value, or null when neither spelling is set. */
    public static String get(String name) {
        String v = System.getenv(name);
        if (v == null && name.startsWith(PREFIX)) {
            v = System.getenv(LEGACY_PREFIX + name.substring(PREFIX.length()));
        }
        return v;
    }

    public static String getOrDefault(String name, String fallback) {
        String v = get(name);
        return v != null ? v : fallback;
    }

    private Env() {
    }
}
