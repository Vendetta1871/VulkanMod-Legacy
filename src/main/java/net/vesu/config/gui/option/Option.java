package net.vesu.config.gui.option;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One row of {@link net.vesu.config.gui.VesuOptionsScreen}. An option holds a pending value
 * that the screen edits; {@link #apply()} writes it to the live setting, {@link #revert()}
 * reads the live setting back. Nothing reaches the game until Apply or Done, like Sodium.
 */
public abstract class Option {

    /** Shown under the tooltip, as in Sodium. */
    public enum Impact {
        LOW("Low"), MEDIUM("Medium"), HIGH("High"), VARIES("Varies");

        public final String label;

        Impact(String label) {
            this.label = label;
        }
    }

    /** Where the live value lives: vanilla options.txt or Vesu's own settings file. */
    public enum Storage { VANILLA, VESU }

    public final String name;
    public final Storage storage;
    private String tooltip = "";
    private Impact impact;
    private BooleanSupplier available = () -> true;
    private Supplier<String> unavailableReason = () -> null;
    private boolean restart;
    private Runnable onChange;

    protected Option(String name, Storage storage) {
        this.name = name;
        this.storage = storage;
    }

    public Option tooltip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public Option impact(Impact impact) {
        this.impact = impact;
        return this;
    }

    /** The option is greyed out and ignores input while {@code available} is false. */
    public Option availableWhen(BooleanSupplier available, String reason) {
        return availableWhen(available, () -> reason);
    }

    /** As above, with a reason that depends on why the option is unavailable. */
    public Option availableWhen(BooleanSupplier available, Supplier<String> reason) {
        this.available = available;
        this.unavailableReason = reason;
        return this;
    }

    /** The new value only takes effect after a game restart. */
    public Option restart() {
        this.restart = true;
        return this;
    }

    /** Runs after every edit of the pending value (options that depend on each other). */
    public Option onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    public String tooltip() { return tooltip; }
    public Impact impact() { return impact; }
    public boolean available() { return available.getAsBoolean(); }
    public String unavailableReason() { return unavailableReason.get(); }
    public boolean needsRestart() { return restart; }

    protected void changed(boolean byUser) {
        if (byUser && onChange != null) onChange.run();
    }

    /** The pending value as shown on the right of the row. */
    public abstract String valueText();

    /** The pending value differs from the live one. */
    public abstract boolean modified();

    /** Pending value to the live setting; only called when {@link #modified()}. */
    public abstract void apply();

    /** Live setting to the pending value. */
    public abstract void revert();

    /**
     * Follows a live value changed outside the screen (F11 toggles fullscreen) while the
     * pending value is unedited, so leaving the screen does not undo it.
     */
    public void sync() {
    }

    /** A fixed list of values, stepped through by clicking. */
    public static class Cycle<T> extends Option {
        private final T[] values;
        private final Function<T, String> label;
        private final Supplier<T> getter;
        private final Consumer<T> setter;
        private final java.util.Set<T> skipped = new java.util.HashSet<>();
        private T pending;
        /** The live value when the pending one was last taken from it. */
        private T base;

        public Cycle(String name, Storage storage, T[] values, Function<T, String> label,
                     Supplier<T> getter, Consumer<T> setter) {
            super(name, storage);
            this.values = values;
            this.label = label;
            this.getter = getter;
            this.setter = setter;
            this.pending = this.base = getter.get();
        }

        public T pending() { return pending; }

        /** Values that are listed but not offered yet: cycling steps over them. */
        @SafeVarargs
        public final Cycle<T> skip(T... values) {
            skipped.addAll(java.util.Arrays.asList(values));
            return this;
        }

        /** Sets the pending value without firing {@link #onChange}: for linked options. */
        public void set(T value) {
            this.pending = value;
        }

        /** One step forward ({@code direction} 1) or back (-1). */
        public void cycle(int direction) {
            int i = -1;   // a value outside the list (a custom preset) steps to the first or last one
            for (int k = 0; k < values.length; k++) {
                if (Objects.equals(values[k], pending)) i = k;
            }
            if (i < 0) i = direction > 0 ? -1 : values.length;
            for (int n = 0; n < values.length; n++) {
                i = Math.floorMod(i + direction, values.length);
                if (!skipped.contains(values[i])) break;
            }
            pending = values[i];
            changed(true);
        }

        @Override public String valueText() { return label.apply(pending); }
        @Override public boolean modified() { return !Objects.equals(pending, getter.get()); }
        @Override public void apply() { setter.accept(pending); }
        @Override public void revert() { pending = base = getter.get(); }

        @Override
        public void sync() {
            T live = getter.get();
            if (Objects.equals(live, base)) return;
            if (Objects.equals(pending, base)) pending = live;
            base = live;
        }
    }

    /** On or off, drawn as a check box. */
    public static class Toggle extends Cycle<Boolean> {
        private static final Boolean[] VALUES = {false, true};

        public Toggle(String name, Storage storage, Supplier<Boolean> getter, Consumer<Boolean> setter) {
            super(name, storage, VALUES, v -> v ? "On" : "Off", getter, setter);
        }
    }

    /** A number between {@code min} and {@code max}, snapped to {@code step}. */
    public static class Slider extends Option {
        public final float min, max, step;
        private final Function<Float, String> format;
        private final Supplier<Float> getter;
        private final Consumer<Float> setter;
        private float pending;
        private float base;

        public Slider(String name, Storage storage, float min, float max, float step,
                      Function<Float, String> format, Supplier<Float> getter, Consumer<Float> setter) {
            super(name, storage);
            this.min = min;
            this.max = max;
            this.step = step;
            this.format = format;
            this.getter = getter;
            this.setter = setter;
            this.pending = this.base = snap(getter.get());
        }

        public float pending() { return pending; }

        /** 0..1 position of the pending value on the track. */
        public float fraction() {
            return max > min ? (pending - min) / (max - min) : 0.0f;
        }

        public void setFraction(float fraction) {
            float v = snap(min + Math.max(0.0f, Math.min(1.0f, fraction)) * (max - min));
            if (v != pending) {
                pending = v;
                changed(true);
            }
        }

        /** Sets the pending value without firing {@link #onChange}: for linked options. */
        public void set(float value) {
            this.pending = snap(value);
        }

        private float snap(float v) {
            if (Float.isNaN(v)) v = min;
            if (step > 0.0f) v = min + Math.round((v - min) / step) * step;
            return Math.max(min, Math.min(max, v));
        }

        @Override public String valueText() { return format.apply(pending); }
        @Override public boolean modified() { return Math.abs(pending - snap(getter.get())) > 1e-4f; }
        @Override public void apply() { setter.accept(pending); }
        @Override public void revert() { pending = base = snap(getter.get()); }

        @Override
        public void sync() {
            float live = snap(getter.get());
            if (live == base) return;
            if (pending == base) pending = live;
            base = live;
        }
    }

    /**
     * One choice of a hidden {@link Cycle}, drawn as its own row with a check box: the shader
     * pack list. The cycle carries the pending value and applies it.
     */
    public static class Radio extends Option {
        private final Cycle<String> target;
        public final String value;
        private final String format;
        private final Supplier<String> info;

        public Radio(String name, Cycle<String> target, String value, String format, Supplier<String> info) {
            super(name, target.storage);
            this.target = target;
            this.value = value;
            this.format = format;
            this.info = info;
        }

        public boolean selected() { return Objects.equals(target.pending(), value); }

        public void select() {
            if (!selected()) {
                target.set(value);
                changed(true);
            }
        }

        @Override public String tooltip() { return info.get(); }
        @Override public String valueText() { return format; }
        @Override public boolean modified() { return selected() && target.modified(); }
        @Override public void apply() { }
        @Override public void revert() { }
    }

    /** A button row: runs {@code action} with the options screen, holds no value. */
    public static class Action extends Option {
        private final java.util.function.Consumer<net.minecraft.client.gui.GuiScreen> action;

        public Action(String name, java.util.function.Consumer<net.minecraft.client.gui.GuiScreen> action) {
            super(name, Storage.VESU);
            this.action = action;
        }

        public void run(net.minecraft.client.gui.GuiScreen screen) { action.accept(screen); }

        @Override public String valueText() { return ""; }
        @Override public boolean modified() { return false; }
        @Override public void apply() { }
        @Override public void revert() { }
    }
}
