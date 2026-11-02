package net.vulkanmod.pack.source;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code #include} directives against a {@link PackSource}, recursively. Quoted
 * paths resolve against the including file's directory and angle-bracket paths against the
 * pack root, so a pack can ship a {@code lib/} folder like OptiFine packs do. (The host's
 * shaderc include callback only serves the mod's own shaders.)
 *
 * <p>The result keeps compiler errors pointing at the original file and line: every
 * included text is preceded by {@code #line 1 "<file>"} and followed by
 * {@code #line <next line> "<including file>"}. Named {@code #line} directives need
 * {@code GL_GOOGLE_cpp_style_line_directive}, which {@link #expandProgram} enables right
 * after the {@code #version} line.</p>
 */
public final class IncludeExpander {

    /** The extension that allows {@code #line <n> "<file>"}. */
    public static final String LINE_EXTENSION = "#extension GL_GOOGLE_cpp_style_line_directive : require";

    /** Matches {@code #include "x.glsl"} and {@code #include <x.glsl>}, one per line. */
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^[ \\t]*#[ \\t]*include[ \\t]*([<\"])([^>\"]+)[>\"][ \\t]*$");

    private static final Pattern VERSION_PATTERN = Pattern.compile("^[ \\t]*#[ \\t]*version\\b.*$");

    private static final int MAX_INCLUDE_DEPTH = 32;

    private final PackSource source;

    public IncludeExpander(PackSource source) {
        this.source = source;
    }

    /**
     * Reads a program and expands its includes. The line-directive extension goes right
     * after {@code #version} (or first, when there is none), followed by a {@code #line}
     * that restores the numbering of the program's own lines.
     */
    public String expandProgram(String rel) throws IOException {
        return expandProgram(rel, null);
    }

    /**
     * As {@link #expandProgram(String)}, with the host {@code prelude} placed after the
     * extension line. A program without {@code #version} then gets {@code #version 450}.
     */
    public String expandProgram(String rel, String prelude) throws IOException {
        String path = PackSource.normalize(rel);
        String expanded = expand(path, source.read(path));

        String[] lines = expanded.split("\n", -1);
        StringBuilder out = new StringBuilder(expanded.length() + 128);
        int versionLine = -1;
        for (int i = 0; i < lines.length; i++) {
            // Only directives before any code can be #version; a later match is not one.
            if (VERSION_PATTERN.matcher(lines[i]).matches()) {
                versionLine = i;
                break;
            }
            String trimmed = lines[i].trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                break;
            }
        }
        if (versionLine < 0 && prelude != null) {
            out.append("#version 450\n");
        }
        for (int i = 0; i <= versionLine; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append(LINE_EXTENSION).append('\n');
        if (prelude != null) {
            out.append(prelude);
            if (!prelude.endsWith("\n")) out.append('\n');
        }
        out.append(lineDirective(versionLine + 2, path)).append('\n');
        for (int i = versionLine + 1; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** Expands includes in {@code text}, the contents of {@code rel}. */
    public String expand(String rel, String text) throws IOException {
        Deque<String> chain = new ArrayDeque<>();
        String path = PackSource.normalize(rel);
        chain.push(path);
        return expand(path, text, chain, 0);
    }

    private String expand(String requesting, String text, Deque<String> chain, int depth) throws IOException {
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new IOException("include chain deeper than " + MAX_INCLUDE_DEPTH + " at " + requesting);
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = INCLUDE_PATTERN.matcher(stripCr(line));
            if (!m.matches()) {
                out.append(line);
            } else {
                String requested = m.group(2);
                String path = m.group(1).equals("\"")
                        ? PackSource.normalize(dirOf(requesting) + requested)
                        : PackSource.normalize(requested);
                if (chain.contains(path)) {
                    // One line in place of one line: the numbering stays intact.
                    out.append("// include cycle skipped: ").append(path);
                } else {
                    String included;
                    try {
                        included = source.read(path);
                    } catch (IOException e) {
                        throw new IOException(requesting + ":" + (i + 1) + ": cannot include " + path, e);
                    }
                    chain.push(path);
                    out.append(lineDirective(1, path)).append('\n')
                            .append(expand(path, included, chain, depth + 1)).append('\n')
                            .append(lineDirective(i + 2, requesting));
                    chain.pop();
                }
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** {@code #line n "file"}: the line after it is line {@code n} of {@code file}. */
    static String lineDirective(int line, String file) {
        return "#line " + line + " \"" + file + "\"";
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }
}
