package net.vesu.pack.source;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only file access to one pack: a directory, a zip file, or a folder on the classpath
 * (the built-in packs inside the mod jar). Paths are pack-relative with '/' separators;
 * every path is normalized first, so no source can be read outside its own root.
 */
public abstract class PackSource implements Closeable {

    private final String id;

    protected PackSource(String id) {
        this.id = id;
    }

    /** The pack id: the directory or zip name without extension, or the built-in folder name. */
    public String id() {
        return id;
    }

    /** True when {@code rel} names an existing file. */
    public abstract boolean exists(String rel);

    /** True when {@code rel} names a directory with at least one file below it. */
    public abstract boolean hasDirectory(String rel);

    /** Raw file bytes; throws when the file is missing. */
    public abstract byte[] readBytes(String rel) throws IOException;

    /** Names of the direct children of a directory, sorted; empty when it does not exist. */
    public List<String> list(String rel) {
        return List.of();
    }

    /** File text as UTF-8; throws when the file is missing. */
    public String read(String rel) throws IOException {
        return new String(readBytes(rel), StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
    }

    public static PackSource directory(Path dir) {
        return new DirSource(dir);
    }

    /** Opens a zip; the caller closes the source. */
    public static PackSource zip(Path file) throws IOException {
        return new ZipSource(file);
    }

    /**
     * Adds generated files to a source: a frontend's programs, which the runtime reads like
     * pack files. Closing the overlay closes the base.
     */
    public static PackSource overlay(PackSource base, Map<String, String> files) {
        return new PackSource(base.id()) {
            @Override
            public boolean exists(String rel) {
                return files.containsKey(normalize(rel)) || base.exists(rel);
            }

            @Override
            public boolean hasDirectory(String rel) {
                return base.hasDirectory(rel);
            }

            @Override
            public byte[] readBytes(String rel) throws IOException {
                String text = files.get(normalize(rel));
                return text != null ? text.getBytes(StandardCharsets.UTF_8) : base.readBytes(rel);
            }

            @Override
            public List<String> list(String rel) {
                return base.list(rel);
            }

            @Override
            public void close() throws IOException {
                base.close();
            }
        };
    }

    /** A folder on the classpath, e.g. {@code /assets/vesu/shaderpacks/<id>/}. */
    public static PackSource classpath(ClassLoader loader, String prefix, String id) {
        return new ClasspathSource(loader, prefix, id);
    }

    /**
     * Collapses {@code ./} and {@code ../} segments and strips a leading '/'. A ".." above
     * the root stays at the root, so a normalized path never leaves the pack.
     */
    public static String normalize(String rel) {
        String path = rel.replace('\\', '/');
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
                continue;
            }
            parts.addLast(part);
        }
        return String.join("/", parts);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class DirSource extends PackSource {
        private final Path dir;

        DirSource(Path dir) {
            super(dir.getFileName().toString());
            this.dir = dir.toAbsolutePath().normalize();
        }

        private Path resolve(String rel) {
            Path file = dir.resolve(normalize(rel)).normalize();
            // normalize() already keeps the path inside; this guards symlinked roots.
            return file.startsWith(dir) ? file : null;
        }

        @Override
        public boolean exists(String rel) {
            Path file = resolve(rel);
            return file != null && Files.isRegularFile(file);
        }

        @Override
        public boolean hasDirectory(String rel) {
            Path file = resolve(rel);
            return file != null && Files.isDirectory(file);
        }

        @Override
        public byte[] readBytes(String rel) throws IOException {
            Path file = resolve(rel);
            if (file == null || !Files.isRegularFile(file)) {
                throw new IOException("pack file not found: " + rel);
            }
            return Files.readAllBytes(file);
        }

        @Override
        public List<String> list(String rel) {
            Path file = resolve(rel);
            if (file == null || !Files.isDirectory(file)) return List.of();
            try (java.util.stream.Stream<Path> children = Files.list(file)) {
                return children.map(p -> p.getFileName().toString()).sorted().toList();
            } catch (IOException e) {
                return List.of();
            }
        }
    }

    private static final class ZipSource extends PackSource {
        private final ZipFile zip;

        ZipSource(Path file) throws IOException {
            super(stripExtension(file.getFileName().toString()));
            this.zip = new ZipFile(file.toFile());
        }

        @Override
        public boolean exists(String rel) {
            ZipEntry e = zip.getEntry(normalize(rel));
            return e != null && !e.isDirectory();
        }

        @Override
        public boolean hasDirectory(String rel) {
            String prefix = normalize(rel) + "/";
            // Zips do not always store directory entries, so look for any file below.
            for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
                if (normalize(it.nextElement().getName()).startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public byte[] readBytes(String rel) throws IOException {
            ZipEntry e = zip.getEntry(normalize(rel));
            if (e == null || e.isDirectory()) {
                throw new IOException("pack file not found: " + rel);
            }
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }

        @Override
        public List<String> list(String rel) {
            String prefix = normalize(rel) + "/";
            TreeSet<String> names = new TreeSet<>();
            for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
                String name = normalize(it.nextElement().getName());
                if (name.startsWith(prefix)) {
                    String rest = name.substring(prefix.length());
                    int slash = rest.indexOf('/');
                    names.add(slash < 0 ? rest : rest.substring(0, slash));
                }
            }
            return List.copyOf(names);
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    private static final class ClasspathSource extends PackSource {
        private final ClassLoader loader;
        private final String prefix;

        ClasspathSource(ClassLoader loader, String prefix, String id) {
            super(id);
            this.loader = loader;
            // ClassLoader resources take no leading '/'.
            String p = normalize(prefix);
            this.prefix = p.isEmpty() ? "" : p + "/";
        }

        @Override
        public boolean exists(String rel) {
            return loader.getResource(prefix + normalize(rel)) != null;
        }

        @Override
        public boolean hasDirectory(String rel) {
            // Classpath folders cannot be listed portably; built-in packs are native packs,
            // which are detected by their pack.json alone.
            return false;
        }

        @Override
        public byte[] readBytes(String rel) throws IOException {
            try (InputStream in = loader.getResourceAsStream(prefix + normalize(rel))) {
                if (in == null) {
                    throw new IOException("pack resource not found: " + rel);
                }
                return in.readAllBytes();
            }
        }
    }
}
