package net.vesu.lod;

import java.util.Arrays;
import java.util.function.Consumer;

/** A small open-addressing {@code long -> V} map (no boxing). Not thread-safe. */
public final class LongMap<V> {
    private static final long EMPTY = Long.MIN_VALUE + 0x5A5A;
    private long[] keys;
    private Object[] values;
    private int size, mask;

    public LongMap() {
        this(16);
    }

    public LongMap(int capacity) {
        int c = Integer.highestOneBit(Math.max(4, capacity) * 2 - 1);
        keys = new long[c];
        Arrays.fill(keys, EMPTY);
        values = new Object[c];
        mask = c - 1;
    }

    public int size() {
        return size;
    }

    private int slot(long k) {
        return (int) SectionKey.mix(k) & mask;
    }

    @SuppressWarnings("unchecked")
    public V get(long k) {
        for (int i = slot(k); ; i = (i + 1) & mask) {
            long c = keys[i];
            if (c == EMPTY) return null;
            if (c == k) return (V) values[i];
        }
    }

    @SuppressWarnings("unchecked")
    public V put(long k, V v) {
        if (k == EMPTY) throw new IllegalArgumentException("reserved key");
        if ((size + 1) * 4 > keys.length * 3) grow();
        for (int i = slot(k); ; i = (i + 1) & mask) {
            long c = keys[i];
            if (c == EMPTY) {
                keys[i] = k;
                values[i] = v;
                size++;
                return null;
            }
            if (c == k) {
                V old = (V) values[i];
                values[i] = v;
                return old;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public V remove(long k) {
        for (int i = slot(k); ; i = (i + 1) & mask) {
            long c = keys[i];
            if (c == EMPTY) return null;
            if (c == k) {
                V old = (V) values[i];
                // Backward-shift deletion keeps probe chains intact.
                int gap = i;
                for (int j = (i + 1) & mask; keys[j] != EMPTY; j = (j + 1) & mask) {
                    int home = slot(keys[j]);
                    if (((j - home) & mask) >= ((j - gap) & mask)) {
                        keys[gap] = keys[j];
                        values[gap] = values[j];
                        gap = j;
                    }
                }
                keys[gap] = EMPTY;
                values[gap] = null;
                size--;
                return old;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void forEachValue(Consumer<V> c) {
        for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY) c.accept((V) values[i]);
    }

    public interface Entry<V> {
        void accept(long key, V value);
    }

    @SuppressWarnings("unchecked")
    public void forEach(Entry<V> c) {
        for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY) c.accept(keys[i], (V) values[i]);
    }

    public void clear() {
        Arrays.fill(keys, EMPTY);
        Arrays.fill(values, null);
        size = 0;
    }

    private void grow() {
        long[] ok = keys;
        Object[] ov = values;
        keys = new long[ok.length * 2];
        Arrays.fill(keys, EMPTY);
        values = new Object[ok.length * 2];
        mask = keys.length - 1;
        size = 0;
        for (int i = 0; i < ok.length; i++) {
            if (ok[i] == EMPTY) continue;
            for (int j = slot(ok[i]); ; j = (j + 1) & mask) {
                if (keys[j] == EMPTY) {
                    keys[j] = ok[i];
                    values[j] = ov[i];
                    size++;
                    break;
                }
            }
        }
    }
}
