package net.vulkanmod.pack.expr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The expressions of {@code uniform.*} and {@code variable.*} (shaders.properties). An expression
 * compiles once into a tree and is evaluated once per frame. Values are vectors of doubles;
 * scalars broadcast, as in GLSL, and booleans are 0 and 1.
 */
public final class Expression {

    /** Resolves a name to a live value: the caller writes into the returned array every frame. */
    public interface Variables {
        double[] bind(String name);
    }

    private interface Node {
        double[] eval();
    }

    private final Node root;

    private Expression(Node root) {
        this.root = root;
    }

    public static Expression parse(String text, Variables variables, double[] frameTime) {
        return new Expression(new Parser(text, variables, frameTime).parse());
    }

    public double[] eval() {
        return root.eval();
    }

    private static double[] scalar(double v) {
        return new double[]{v};
    }

    private static double at(double[] v, int i) {
        return v[v.length == 1 ? 0 : i];
    }

    private interface Op2 {
        double apply(double a, double b);
    }

    private static Node binary(Node a, Node b, Op2 op) {
        return () -> {
            double[] x = a.eval(), y = b.eval();
            double[] r = new double[Math.max(x.length, y.length)];
            for (int i = 0; i < r.length; i++) r[i] = op.apply(at(x, i), at(y, i));
            return r;
        };
    }

    private interface Op1 {
        double apply(double a);
    }

    private static Node unary(Node a, Op1 op) {
        return () -> {
            double[] x = a.eval();
            double[] r = new double[x.length];
            for (int i = 0; i < r.length; i++) r[i] = op.apply(x[i]);
            return r;
        };
    }

    private static final class Parser {
        private final String s;
        private final Variables variables;
        private final double[] frameTime;
        private int p;

        Parser(String s, Variables variables, double[] frameTime) {
            this.s = s;
            this.variables = variables;
            this.frameTime = frameTime;
        }

        Node parse() {
            Node n = or();
            skip();
            if (p < s.length()) throw new IllegalArgumentException("unexpected text in expression: " + s.substring(p));
            return n;
        }

        private void skip() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        private boolean eat(String op) {
            skip();
            if (!s.startsWith(op, p)) return false;
            p += op.length();
            return true;
        }

        private Node or() {
            Node n = and();
            while (eat("||")) n = binary(n, and(), (a, b) -> a != 0 || b != 0 ? 1 : 0);
            return n;
        }

        private Node and() {
            Node n = equality();
            while (eat("&&")) n = binary(n, equality(), (a, b) -> a != 0 && b != 0 ? 1 : 0);
            return n;
        }

        private Node equality() {
            Node n = relational();
            while (true) {
                if (eat("==")) n = binary(n, relational(), (a, b) -> a == b ? 1 : 0);
                else if (eat("!=")) n = binary(n, relational(), (a, b) -> a != b ? 1 : 0);
                else return n;
            }
        }

        private Node relational() {
            Node n = additive();
            while (true) {
                if (eat("<=")) n = binary(n, additive(), (a, b) -> a <= b ? 1 : 0);
                else if (eat(">=")) n = binary(n, additive(), (a, b) -> a >= b ? 1 : 0);
                else if (eat("<")) n = binary(n, additive(), (a, b) -> a < b ? 1 : 0);
                else if (eat(">")) n = binary(n, additive(), (a, b) -> a > b ? 1 : 0);
                else return n;
            }
        }

        private Node additive() {
            Node n = multiplicative();
            while (true) {
                if (eat("+")) n = binary(n, multiplicative(), Double::sum);
                else if (eat("-")) n = binary(n, multiplicative(), (a, b) -> a - b);
                else return n;
            }
        }

        private Node multiplicative() {
            Node n = unaryExpression();
            while (true) {
                if (eat("*")) n = binary(n, unaryExpression(), (a, b) -> a * b);
                else if (eat("/")) n = binary(n, unaryExpression(), (a, b) -> b == 0 ? 0 : a / b);
                else if (eat("%")) n = binary(n, unaryExpression(), (a, b) -> b == 0 ? 0 : a - b * Math.floor(a / b));
                else return n;
            }
        }

        private Node unaryExpression() {
            skip();
            if (s.startsWith("!=", p)) throw new IllegalArgumentException("unexpected != in expression: " + s);
            if (eat("!")) return unary(unaryExpression(), a -> a == 0 ? 1 : 0);
            if (eat("-")) return unary(unaryExpression(), a -> -a);
            if (eat("+")) return unaryExpression();
            return primary();
        }

        private Node primary() {
            skip();
            if (eat("(")) {
                Node n = or();
                if (!eat(")")) throw new IllegalArgumentException("missing ) in expression: " + s);
                return swizzle(n);
            }
            int start = p;
            char c = p < s.length() ? s.charAt(p) : 0;
            if (Character.isDigit(c) || c == '.') {
                while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.'
                        || s.charAt(p) == 'e' || s.charAt(p) == 'E'
                        || ((s.charAt(p) == '-' || s.charAt(p) == '+') && (s.charAt(p - 1) == 'e' || s.charAt(p - 1) == 'E')))) p++;
                double[] v = scalar(Double.parseDouble(s.substring(start, p)));
                while (p < s.length() && "fFdD".indexOf(s.charAt(p)) >= 0) p++;
                return () -> v;
            }
            while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_')) p++;
            if (start == p) throw new IllegalArgumentException("bad expression at " + s.substring(start) + ": " + s);
            String name = s.substring(start, p);
            if (eat("(")) {
                List<Node> args = new ArrayList<>();
                if (!eat(")")) {
                    do {
                        args.add(or());
                    } while (eat(","));
                    if (!eat(")")) throw new IllegalArgumentException("missing ) in expression: " + s);
                }
                return swizzle(function(name, args));
            }
            if (name.equals("true")) return () -> scalar(1);
            if (name.equals("false")) return () -> scalar(0);
            if (name.equals("pi")) return () -> scalar(Math.PI);
            double[] value = variables.bind(name);
            return swizzle(() -> value);
        }

        /** {@code v.x}, {@code v.xyz}, {@code v.0}, and matrices as {@code m.0.1} (column, row). */
        private Node swizzle(Node n) {
            while (p < s.length() && s.charAt(p) == '.') {
                int start = ++p;
                while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)))) p++;
                String field = s.substring(start, p);
                if (field.length() == 1 && Character.isDigit(field.charAt(0))) {
                    int k = field.charAt(0) - '0';
                    Node base = n;
                    n = () -> {
                        double[] v = base.eval();
                        // A column of a matrix, or one element.
                        if (v.length == 16) return new double[]{v[k * 4], v[k * 4 + 1], v[k * 4 + 2], v[k * 4 + 3]};
                        return new double[]{k < v.length ? v[k] : 0};
                    };
                    continue;
                }
                int[] index = new int[field.length()];
                for (int i = 0; i < index.length; i++) {
                    int k = "xyzw".indexOf(field.charAt(i));
                    if (k < 0) k = "rgba".indexOf(field.charAt(i));
                    if (k < 0) k = "stpq".indexOf(field.charAt(i));
                    if (k < 0) k = field.charAt(i) - '0';
                    index[i] = k;
                }
                Node base = n;
                n = () -> {
                    double[] v = base.eval();
                    double[] r = new double[index.length];
                    for (int i = 0; i < r.length; i++) r[i] = index[i] < v.length ? v[index[i]] : 0;
                    return r;
                };
            }
            return n;
        }

        private Node function(String name, List<Node> a) {
            Map<String, Op1> ones = UNARY;
            if (ones.containsKey(name) && a.size() == 1) return unary(a.get(0), ones.get(name));
            switch (name) {
                case "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4" -> {
                    return () -> {
                        double[] r = new double[name.charAt(name.length() - 1) - '0'];
                        int k = 0;
                        for (Node n : a) for (double d : n.eval()) if (k < r.length) r[k++] = d;
                        double last = k > 0 ? r[k - 1] : 0;
                        while (k < r.length) r[k++] = last;
                        return r;
                    };
                }
                case "min", "max", "pow", "fmod", "mod", "atan2", "atan", "step" -> {
                    Op2 op = switch (name) {
                        case "min" -> Math::min;
                        case "max" -> Math::max;
                        case "pow" -> Math::pow;
                        case "step" -> (e, x) -> x < e ? 0 : 1;
                        case "atan", "atan2" -> Math::atan2;
                        default -> (x, y) -> y == 0 ? 0 : x - y * Math.floor(x / y);
                    };
                    Node n = a.get(0);
                    for (int i = 1; i < a.size(); i++) n = binary(n, a.get(i), op);
                    return n;
                }
                case "clamp" -> {
                    return binary(binary(a.get(0), a.get(1), Math::max), a.get(2), Math::min);
                }
                case "mix" -> {
                    return () -> {
                        double[] x = a.get(0).eval(), y = a.get(1).eval(), t = a.get(2).eval();
                        double[] r = new double[Math.max(x.length, y.length)];
                        for (int i = 0; i < r.length; i++) r[i] = at(x, i) * (1 - at(t, i)) + at(y, i) * at(t, i);
                        return r;
                    };
                }
                case "smoothstep" -> {
                    return () -> {
                        double e0 = a.get(0).eval()[0], e1 = a.get(1).eval()[0], x = a.get(2).eval()[0];
                        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
                        return scalar(t * t * (3 - 2 * t));
                    };
                }
                case "length" -> {
                    return () -> {
                        double sum = 0;
                        for (double d : a.get(0).eval()) sum += d * d;
                        return scalar(Math.sqrt(sum));
                    };
                }
                case "dot" -> {
                    return () -> {
                        double[] x = a.get(0).eval(), y = a.get(1).eval();
                        double sum = 0;
                        for (int i = 0; i < Math.min(x.length, y.length); i++) sum += x[i] * y[i];
                        return scalar(sum);
                    };
                }
                case "normalize" -> {
                    return () -> {
                        double[] x = a.get(0).eval().clone();
                        double sum = 0;
                        for (double d : x) sum += d * d;
                        double l = Math.sqrt(sum);
                        for (int i = 0; i < x.length && l > 0; i++) x[i] /= l;
                        return x;
                    };
                }
                case "if" -> {
                    return () -> {
                        for (int i = 0; i + 1 < a.size(); i += 2) {
                            if (a.get(i).eval()[0] != 0) return a.get(i + 1).eval();
                        }
                        return a.size() % 2 == 1 ? a.get(a.size() - 1).eval() : scalar(0);
                    };
                }
                case "and", "or" -> {
                    Node n = a.get(0);
                    for (int i = 1; i < a.size(); i++) {
                        n = binary(n, a.get(i), name.equals("and") ? (x, y) -> x != 0 && y != 0 ? 1 : 0 : (x, y) -> x != 0 || y != 0 ? 1 : 0);
                    }
                    return n;
                }
                case "between" -> {
                    return () -> {
                        double v = a.get(0).eval()[0];
                        return scalar(v >= a.get(1).eval()[0] && v <= a.get(2).eval()[0] ? 1 : 0);
                    };
                }
                case "equals" -> {
                    return () -> {
                        double d = a.size() > 2 ? a.get(2).eval()[0] : 0;
                        return scalar(Math.abs(a.get(0).eval()[0] - a.get(1).eval()[0]) <= d ? 1 : 0);
                    };
                }
                case "in" -> {
                    return () -> {
                        double v = a.get(0).eval()[0];
                        for (int i = 1; i < a.size(); i++) if (a.get(i).eval()[0] == v) return scalar(1);
                        return scalar(0);
                    };
                }
                case "random" -> {
                    Random random = new Random();
                    return () -> scalar(random.nextDouble());
                }
                case "smooth" -> {
                    // smooth([id,] value, up [, down]): an exponential filter with state of its own.
                    int v = a.size() >= 4 ? 1 : 0;
                    Node value = a.get(v), up = a.size() > v + 1 ? a.get(v + 1) : () -> scalar(1),
                            down = a.size() > v + 2 ? a.get(v + 2) : up;
                    double[] state = {Double.NaN};
                    return () -> {
                        double target = value.eval()[0];
                        if (Double.isNaN(state[0])) state[0] = target;
                        double t = target > state[0] ? up.eval()[0] : down.eval()[0];
                        double dt = frameTime[0];
                        // Half-life t: OptiFine's fade coefficient.
                        double k = t <= 0 ? 1 : 1 - Math.pow(0.5, dt / t);
                        state[0] += (target - state[0]) * k;
                        return new double[]{state[0]};
                    };
                }
                default -> throw new IllegalArgumentException("unknown function " + name + " in expression: " + s);
            }
        }
    }

    private static final Map<String, Op1> UNARY = new HashMap<>();

    static {
        UNARY.put("sin", Math::sin);
        UNARY.put("cos", Math::cos);
        UNARY.put("tan", Math::tan);
        UNARY.put("asin", Math::asin);
        UNARY.put("acos", Math::acos);
        UNARY.put("atan", Math::atan);
        UNARY.put("torad", Math::toRadians);
        UNARY.put("radians", Math::toRadians);
        UNARY.put("todeg", Math::toDegrees);
        UNARY.put("degrees", Math::toDegrees);
        UNARY.put("abs", Math::abs);
        UNARY.put("floor", Math::floor);
        UNARY.put("ceil", Math::ceil);
        UNARY.put("exp", Math::exp);
        UNARY.put("frac", a -> a - Math.floor(a));
        UNARY.put("fract", a -> a - Math.floor(a));
        UNARY.put("log", Math::log);
        UNARY.put("log2", a -> Math.log(a) / Math.log(2));
        UNARY.put("log10", Math::log10);
        UNARY.put("exp2", a -> Math.pow(2, a));
        UNARY.put("exp10", a -> Math.pow(10, a));
        UNARY.put("sqrt", Math::sqrt);
        UNARY.put("round", a -> (double) Math.round(a));
        UNARY.put("signum", Math::signum);
        UNARY.put("sign", Math::signum);
        UNARY.put("not", a -> a == 0 ? 1 : 0);
    }
}
