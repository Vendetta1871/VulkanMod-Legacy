package net.vesu.pack.optifine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the conditional directives of an OptiFine program, so that the directives in its
 * comments and its declarations are read for the options in effect. {@code #define} lines
 * stay in the output; glslang expands them. Inactive lines become empty lines and comments
 * are removed, which keeps the line numbers of compile errors.
 */
final class GlslPreprocessor {

    /** A macro; {@code params} is null for an object-like one. */
    private record Macro(List<String> params, String body) {
    }

    /** Marks a comment line that may hold a directive, for the conditional pass. */
    private static final char MARK = '\u0001';
    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(\\w+)\\s*(.*)$");
    private static final Pattern DEFINE = Pattern.compile("^(\\w+)(\\(([^)]*)\\))?\\s*(.*)$");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

    private final Map<String, Macro> macros = new HashMap<>();
    /** Lines of active comments and of active code that may be directives, in source order. */
    final List<String> directiveLines = new ArrayList<>();
    int version = 120;

    void define(String name, String value) {
        macros.put(name, new Macro(null, value));
    }

    boolean isDefined(String name) {
        return macros.containsKey(name);
    }

    /** The value of an object-like macro, expanded, or null. */
    String value(String name) {
        Macro m = macros.get(name);
        return m == null || m.params != null ? null : expand(m.body, 0).trim();
    }

    /** True when the expression of an {@code #if} holds, for {@code shaders.properties} too. */
    boolean evaluate(String expression) {
        return new Condition(expand(replaceDefined(expression), 0)).parse() != 0.0;
    }

    /** A constant integer expression with the macros applied. */
    double evaluateNumber(String expression) {
        return new Condition(expand(replaceDefined(expression), 0)).parse();
    }

    String process(String source) {
        String[] lines = stripComments(source.replace("\r", "")).split("\n", -1);
        StringBuilder out = new StringBuilder(source.length());
        // One entry per open #if: this branch runs, some branch ran, the enclosing one runs.
        List<boolean[]> stack = new ArrayList<>();
        boolean active = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int joined = 0;
            while (line.endsWith("\\") && i + 1 + joined < lines.length) {
                line = line.substring(0, line.length() - 1) + " " + lines[i + 1 + joined];
                joined++;
            }
            StringBuilder code = new StringBuilder();
            for (int mark = 0; mark >= 0; ) {
                int start = line.indexOf(MARK, mark);
                if (start < 0) {
                    code.append(line, mark, line.length());
                    break;
                }
                code.append(line, mark, start);
                int end = line.indexOf(MARK, start + 1);
                if (active) directiveLines.add(line.substring(start + 1, end));
                mark = end + 1;
            }
            line = code.toString();
            Matcher d = DIRECTIVE.matcher(line);
            if (d.matches()) {
                String name = d.group(1), rest = d.group(2).trim();
                switch (name) {
                    case "if", "ifdef", "ifndef" -> {
                        boolean holds = active && switch (name) {
                            case "ifdef" -> macros.containsKey(firstWord(rest));
                            case "ifndef" -> !macros.containsKey(firstWord(rest));
                            default -> evaluate(rest);
                        };
                        stack.add(new boolean[]{holds, holds, active});
                        active = holds;
                    }
                    case "elif" -> {
                        if (!stack.isEmpty()) {
                            boolean[] top = stack.getLast();
                            top[0] = top[2] && !top[1] && evaluate(rest);
                            top[1] |= top[0];
                            active = top[0];
                        }
                    }
                    case "else" -> {
                        if (!stack.isEmpty()) {
                            boolean[] top = stack.getLast();
                            top[0] = top[2] && !top[1];
                            top[1] = true;
                            active = top[0];
                        }
                    }
                    case "endif" -> {
                        if (!stack.isEmpty()) active = stack.removeLast()[2];
                    }
                    case "define" -> {
                        if (active) {
                            define(rest);
                            out.append(line);
                        }
                    }
                    case "undef" -> {
                        if (active) {
                            macros.remove(firstWord(rest));
                            out.append(line);
                        }
                    }
                    case "version" -> {
                        if (active) {
                            Matcher v = Pattern.compile("\\d+").matcher(rest);
                            if (v.find()) version = Integer.parseInt(v.group());
                        }
                    }
                    case "line" -> {
                        if (active) out.append(line);
                    }
                    default -> { // #extension, #pragma: every feature is core in 450.
                    }
                }
            } else if (active) {
                out.append(line);
                if (line.contains("const ")) directiveLines.add(line);
            }
            for (int n = 0; n <= joined; n++) out.append('\n');
            i += joined;
        }
        return out.toString();
    }

    private void define(String rest) {
        Matcher m = DEFINE.matcher(rest);
        if (!m.matches()) return;
        List<String> params = null;
        if (m.group(2) != null) {
            params = new ArrayList<>();
            for (String p : m.group(3).split(",")) {
                if (!p.isBlank()) params.add(p.trim());
            }
        }
        macros.put(m.group(1), new Macro(params, m.group(4).trim()));
    }

    private static String firstWord(String s) {
        Matcher m = IDENT.matcher(s);
        return m.find() ? m.group() : "";
    }

    /** Removes comments, keeping newlines. Lines of block comments that look like directives survive between marks. */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) end = n;
                String comment = s.substring(i + 2, end);
                String[] lines = comment.split("\n", -1);
                for (int l = 0; l < lines.length; l++) {
                    String t = lines[l].trim();
                    if (t.startsWith("DRAWBUFFERS") || t.startsWith("RENDERTARGETS") || t.startsWith("const ")) {
                        out.append(MARK).append(t).append(MARK);
                    }
                    if (l + 1 < lines.length) out.append('\n');
                }
                i = Math.min(n, end + 2);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private String replaceDefined(String expr) {
        Matcher m = Pattern.compile("\\bdefined\\s*\\(\\s*(\\w+)\\s*\\)|\\bdefined\\s+(\\w+)").matcher(expr);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            m.appendReplacement(out, macros.containsKey(name) ? "1" : "0");
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Expands macros in an expression of a directive. */
    private String expand(String text, int depth) {
        if (depth > 32) return text;
        StringBuilder out = new StringBuilder();
        Matcher m = IDENT.matcher(text);
        int last = 0;
        while (m.find(last)) {
            int start = m.start();
            if (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) {
                // Inside a number such as 1.0e5.
                out.append(text, last, m.end());
                last = m.end();
                continue;
            }
            out.append(text, last, start);
            Macro macro = macros.get(m.group());
            last = m.end();
            if (macro == null) {
                out.append(m.group());
            } else if (macro.params == null) {
                out.append(' ').append(expand(macro.body, depth + 1)).append(' ');
            } else {
                int open = last;
                while (open < text.length() && Character.isWhitespace(text.charAt(open))) open++;
                if (open == text.length() || text.charAt(open) != '(') {
                    out.append(m.group());
                    continue;
                }
                List<String> args = new ArrayList<>();
                int level = 0, argStart = open + 1, i = open;
                for (; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '(') level++;
                    else if (c == ')' && --level == 0) break;
                    else if (c == ',' && level == 1) {
                        args.add(text.substring(argStart, i));
                        argStart = i + 1;
                    }
                }
                args.add(text.substring(argStart, Math.min(i, text.length())));
                String body = macro.body;
                for (int p = 0; p < macro.params.size() && p < args.size(); p++) {
                    body = body.replaceAll("\\b" + macro.params.get(p) + "\\b", Matcher.quoteReplacement(args.get(p)));
                }
                out.append(' ').append(expand(body, depth + 1)).append(' ');
                last = Math.min(i + 1, text.length());
            }
        }
        out.append(text.substring(Math.min(last, text.length())));
        return out.toString();
    }

    /** The integer expressions of {@code #if}; numbers are doubles, which also serves {@code shaders.properties}. */
    private static final class Condition {
        private final String s;
        private int p;

        Condition(String s) {
            this.s = s;
        }

        double parse() {
            double v = ternary();
            return v;
        }

        private double ternary() {
            double c = or();
            if (eat("?")) {
                double a = ternary();
                eat(":");
                double b = ternary();
                return c != 0 ? a : b;
            }
            return c;
        }

        private double or() {
            double v = and();
            while (eat("||")) {
                double r = and();
                v = v != 0 || r != 0 ? 1 : 0;
            }
            return v;
        }

        private double and() {
            double v = equality();
            while (eat("&&")) {
                double r = equality();
                v = v != 0 && r != 0 ? 1 : 0;
            }
            return v;
        }

        private double equality() {
            double v = relational();
            while (true) {
                if (eat("==")) v = v == relational() ? 1 : 0;
                else if (eat("!=")) v = v != relational() ? 1 : 0;
                else return v;
            }
        }

        private double relational() {
            double v = additive();
            while (true) {
                if (eat("<=")) v = v <= additive() ? 1 : 0;
                else if (eat(">=")) v = v >= additive() ? 1 : 0;
                else if (eat("<")) v = v < additive() ? 1 : 0;
                else if (eat(">")) v = v > additive() ? 1 : 0;
                else return v;
            }
        }

        private double additive() {
            double v = multiplicative();
            while (true) {
                if (eat("+")) v += multiplicative();
                else if (eat("-")) v -= multiplicative();
                else return v;
            }
        }

        private double multiplicative() {
            double v = unary();
            while (true) {
                if (eat("*")) v *= unary();
                else if (eat("/")) {
                    double r = unary();
                    v = r == 0 ? 0 : v / r;
                } else if (eat("%")) {
                    double r = unary();
                    v = r == 0 ? 0 : v % r;
                } else return v;
            }
        }

        private double unary() {
            if (eat("!")) return unary() == 0 ? 1 : 0;
            if (eat("-")) return -unary();
            if (eat("+")) return unary();
            if (eat("(")) {
                double v = ternary();
                eat(")");
                return v;
            }
            skip();
            int start = p;
            while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '.' || s.charAt(p) == '_')) p++;
            if (start == p) {
                p = s.length();
                return 0;
            }
            String word = s.substring(start, p);
            if (word.equals("true")) return 1;
            if (word.equals("false")) return 0;
            try {
                if (word.startsWith("0x") || word.startsWith("0X")) return Long.parseLong(word.substring(2), 16);
                return Double.parseDouble(word.replaceAll("[uUfFlL]+$", ""));
            } catch (NumberFormatException e) {
                return 0; // An undefined identifier counts as 0, as in C.
            }
        }

        private void skip() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        private boolean eat(String op) {
            skip();
            if (!s.startsWith(op, p)) return false;
            // "<" must not take the start of "<=", "!" of "!=": those are tried first by the callers,
            // but "!" is reached from unary after an operand was complete only on malformed input.
            p += op.length();
            return true;
        }
    }
}
