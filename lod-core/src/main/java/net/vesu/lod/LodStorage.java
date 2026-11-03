package net.vesu.lod;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

/**
 * Persistent store of one LOD world (AUDIT 5.5): section blobs by key and the registry
 * mappings. Implementations are thread-safe; writes may be batched by the caller.
 */
public interface LodStorage extends AutoCloseable {
    /** Reads one section blob, or null. The buffer is valid only during {@code reader}. */
    <T> T getSection(long key, java.util.function.Function<ByteBuffer, T> reader);

    boolean containsSection(long key);

    /** Writes several sections in one transaction. */
    void putSections(long[] keys, byte[][] values, int count);

    void removeSection(long key);

    void putMapping(int key, byte[] value);

    void forEachMapping(BiConsumer<Integer, byte[]> consumer);

    /** Writes buffered state to disk (fsync). */
    void flush();

    @Override
    void close();
}
