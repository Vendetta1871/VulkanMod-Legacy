package net.vulkanmod.pack.optifine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code shaders.properties} after its own {@code #if} preprocessor (frontends.md). Later keys
 * replace earlier ones. Lines with a trailing backslash continue on the next line.
 */
final class ShaderProperties {

    final Map<String, String> values = new LinkedHashMap<>();

    static ShaderProperties parse(String text, GlslPreprocessor macros) {
        ShaderProperties p = new ShaderProperties();
        // The conditional pass keeps properties lines as they are; '#' comments are not directives there.
        List<Boolean> taken = new ArrayList<>();
        List<Boolean> parent = new ArrayList<>();
        boolean active = true;
        String[] lines = text.replace("\r", "").split("\n");
        StringBuilder pending = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#")) {
                String d = line.substring(1).trim();
                String word = d.split("\\s+", 2)[0];
                String rest = d.length() > word.length() ? d.substring(word.length()).trim() : "";
                switch (word) {
                    case "if", "ifdef", "ifndef" -> {
                        boolean holds = active && switch (word) {
                            case "ifdef" -> macros.isDefined(rest);
                            case "ifndef" -> !macros.isDefined(rest);
                            default -> macros.evaluate(rest);
                        };
                        parent.add(active);
                        taken.add(holds);
                        active = holds;
                    }
                    case "elif" -> {
                        if (!parent.isEmpty()) {
                            boolean holds = parent.getLast() && !taken.getLast() && macros.evaluate(rest);
                            taken.set(taken.size() - 1, taken.getLast() || holds);
                            active = holds;
                        }
                    }
                    case "else" -> {
                        if (!parent.isEmpty()) {
                            active = parent.getLast() && !taken.getLast();
                            taken.set(taken.size() - 1, true);
                        }
                    }
                    case "endif" -> {
                        if (!parent.isEmpty()) {
                            active = parent.removeLast();
                            taken.removeLast();
                        }
                    }
                    case "define" -> {
                        if (active) {
                            String[] kv = rest.split("\\s+", 2);
                            macros.define(kv[0], kv.length > 1 ? kv[1] : "");
                        }
                    }
                    default -> {
                    }
                }
                continue;
            }
            if (!active) continue;
            if (line.endsWith("\\")) {
                pending.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            pending.append(line);
            String joined = pending.toString();
            pending.setLength(0);
            int eq = joined.indexOf('=');
            if (eq > 0) p.values.put(joined.substring(0, eq).trim(), joined.substring(eq + 1).trim());
        }
        return p;
    }

    String get(String key) {
        return values.get(key);
    }

    /** Keys under {@code prefix}, with the prefix removed, in file order. */
    Map<String, String> under(String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey().startsWith(prefix)) out.put(e.getKey().substring(prefix.length()), e.getValue());
        }
        return out;
    }
}
