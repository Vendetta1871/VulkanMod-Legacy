package net.vesu.lod;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.lmdb.MDBEnvInfo;
import org.lwjgl.util.lmdb.MDBVal;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.lwjgl.util.lmdb.LMDB.*;

/**
 * LMDB storage (AUDIT 5.5): one environment per LOD world with the named databases
 * {@code sections} (8-byte little-endian keys) and {@code mappings} (4-byte keys). Opened
 * without sync; {@link #flush} syncs. The map grows in 32 MiB steps on MDB_MAP_FULL (on
 * Windows the file is as large as the map, so the steps stay small).
 */
public final class LmdbStorage implements LodStorage {
    private static final long GROW = 32L << 20;

    private final long env;
    private final int sectionsDbi, mappingsDbi;
    /** Transactions hold the read side; growing the map needs the write side (no txn open). */
    private final ReentrantReadWriteLock resize = new ReentrantReadWriteLock();
    private final ReentrantLock writer = new ReentrantLock();
    private volatile boolean closed;

    public LmdbStorage(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            check(mdb_env_create(pp), "env_create");
            env = pp.get(0);
            check(mdb_env_set_maxdbs(env, 4), "set_maxdbs");
            long size = new File(dir, "data.mdb").length();
            check(mdb_env_set_mapsize(env, Math.max(GROW * 2, (size + GROW) / GROW * GROW)), "set_mapsize");
            check(mdb_env_open(env, dir.getAbsolutePath(), MDB_NOSYNC | MDB_NOTLS, 0644), "env_open");
            check(mdb_txn_begin(env, 0, 0, pp), "txn_begin");
            long txn = pp.get(0);
            IntBuffer ib = stack.mallocInt(1);
            check(mdb_dbi_open(txn, "sections", MDB_CREATE, ib), "dbi sections");
            sectionsDbi = ib.get(0);
            check(mdb_dbi_open(txn, "mappings", MDB_CREATE, ib), "dbi mappings");
            mappingsDbi = ib.get(0);
            check(mdb_txn_commit(txn), "commit");
        }
    }

    private static void check(int rc, String what) {
        if (rc != MDB_SUCCESS) throw new IllegalStateException("lmdb " + what + ": " + mdb_strerror(rc));
    }

    private static ByteBuffer longKey(MemoryStack stack, long key) {
        return stack.malloc(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0, key);
    }

    private static ByteBuffer intKey(MemoryStack stack, int key) {
        return stack.malloc(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, key);
    }

    @Override
    public <T> T getSection(long key, Function<ByteBuffer, T> reader) {
        resize.readLock().lock();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (closed) return null;
            PointerBuffer pp = stack.mallocPointer(1);
            check(mdb_txn_begin(env, 0, MDB_RDONLY, pp), "txn_begin ro");
            long txn = pp.get(0);
            try {
                MDBVal k = MDBVal.malloc(stack).mv_data(longKey(stack, key));
                MDBVal v = MDBVal.malloc(stack);
                int rc = mdb_get(txn, sectionsDbi, k, v);
                if (rc == MDB_NOTFOUND) return null;
                check(rc, "get");
                ByteBuffer data = v.mv_data();
                return data == null ? null : reader.apply(data);
            } finally {
                mdb_txn_abort(txn);
            }
        } finally {
            resize.readLock().unlock();
        }
    }

    @Override
    public boolean containsSection(long key) {
        Boolean b = getSection(key, d -> Boolean.TRUE);
        return b != null;
    }

    /** Runs one write transaction, growing the map and retrying on MDB_MAP_FULL. */
    private void write(java.util.function.ToIntFunction<Long> body) {
        writer.lock();
        try {
            while (true) {
                int rc;
                resize.readLock().lock();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    if (closed) return;
                    PointerBuffer pp = stack.mallocPointer(1);
                    check(mdb_txn_begin(env, 0, 0, pp), "txn_begin rw");
                    long txn = pp.get(0);
                    rc = body.applyAsInt(txn);
                    if (rc == MDB_SUCCESS) {
                        rc = mdb_txn_commit(txn);
                    } else {
                        mdb_txn_abort(txn);
                    }
                } finally {
                    resize.readLock().unlock();
                }
                if (rc == MDB_SUCCESS) return;
                if (rc != MDB_MAP_FULL) check(rc, "write");
                grow();
            }
        } finally {
            writer.unlock();
        }
    }

    private void grow() {
        resize.writeLock().lock();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MDBEnvInfo info = MDBEnvInfo.malloc(stack);
            check(mdb_env_info(env, info), "env_info");
            check(mdb_env_set_mapsize(env, info.me_mapsize() + GROW), "set_mapsize");
        } finally {
            resize.writeLock().unlock();
        }
    }

    @Override
    public void putSections(long[] keys, byte[][] values, int count) {
        write(txn -> {
            for (int i = 0; i < count; i++) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer value = MemoryUtil.memAlloc(values[i].length).put(values[i]).flip();
                    try {
                        int rc = mdb_put(txn, sectionsDbi, MDBVal.malloc(stack).mv_data(longKey(stack, keys[i])),
                                MDBVal.malloc(stack).mv_data(value), 0);
                        if (rc != MDB_SUCCESS) return rc;
                    } finally {
                        MemoryUtil.memFree(value);
                    }
                }
            }
            return MDB_SUCCESS;
        });
    }

    @Override
    public void removeSection(long key) {
        write(txn -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int rc = mdb_del(txn, sectionsDbi, MDBVal.malloc(stack).mv_data(longKey(stack, key)), null);
                return rc == MDB_NOTFOUND ? MDB_SUCCESS : rc;
            }
        });
    }

    @Override
    public void putMapping(int key, byte[] value) {
        write(txn -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer v = stack.malloc(Math.max(1, value.length)).put(value).flip();
                return mdb_put(txn, mappingsDbi, MDBVal.malloc(stack).mv_data(intKey(stack, key)),
                        MDBVal.malloc(stack).mv_data(v), 0);
            }
        });
    }

    @Override
    public void forEachMapping(BiConsumer<Integer, byte[]> consumer) {
        resize.readLock().lock();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (closed) return;
            PointerBuffer pp = stack.mallocPointer(1);
            check(mdb_txn_begin(env, 0, MDB_RDONLY, pp), "txn_begin ro");
            long txn = pp.get(0);
            try {
                check(mdb_cursor_open(txn, mappingsDbi, pp), "cursor_open");
                long cursor = pp.get(0);
                MDBVal k = MDBVal.malloc(stack), v = MDBVal.malloc(stack);
                int rc = mdb_cursor_get(cursor, k, v, MDB_FIRST);
                while (rc == MDB_SUCCESS) {
                    ByteBuffer kb = k.mv_data().order(ByteOrder.LITTLE_ENDIAN);
                    ByteBuffer vb = v.mv_data();
                    byte[] value = new byte[vb == null ? 0 : vb.remaining()];
                    if (vb != null) vb.get(value);
                    consumer.accept(kb.getInt(0), value);
                    rc = mdb_cursor_get(cursor, k, v, MDB_NEXT);
                }
                mdb_cursor_close(cursor);
            } finally {
                mdb_txn_abort(txn);
            }
        } finally {
            resize.readLock().unlock();
        }
    }

    @Override
    public void flush() {
        if (!closed) mdb_env_sync(env, true);
    }

    @Override
    public void close() {
        writer.lock();
        resize.writeLock().lock();
        try {
            if (closed) return;
            mdb_env_sync(env, true);
            closed = true;
            mdb_env_close(env);
        } finally {
            resize.writeLock().unlock();
            writer.unlock();
        }
    }
}
