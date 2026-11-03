package net.vesu.lod;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** In-memory storage for tests and for sessions whose storage failed. */
public final class MemoryStorage implements LodStorage {
    private final Map<Long, byte[]> sections = new ConcurrentHashMap<>();
    private final Map<Integer, byte[]> mappings = new TreeMap<>();

    @Override
    public <T> T getSection(long key, Function<ByteBuffer, T> reader) {
        byte[] b = sections.get(key);
        return b == null ? null : reader.apply(ByteBuffer.wrap(b));
    }

    @Override
    public boolean containsSection(long key) {
        return sections.containsKey(key);
    }

    @Override
    public void putSections(long[] keys, byte[][] values, int count) {
        for (int i = 0; i < count; i++) sections.put(keys[i], values[i].clone());
    }

    @Override
    public void removeSection(long key) {
        sections.remove(key);
    }

    @Override
    public synchronized void putMapping(int key, byte[] value) {
        mappings.put(key, value.clone());
    }

    @Override
    public synchronized void forEachMapping(BiConsumer<Integer, byte[]> consumer) {
        mappings.forEach(consumer);
    }

    public int sectionCount() {
        return sections.size();
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
