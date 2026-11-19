package dev.hermit.common.template;

import java.util.ArrayList;
import java.util.List;

final class Expressions {

    private Expressions() {
    }

    record Parsed(List<Segment> segments, boolean exact) {
    }

    sealed interface Segment permits Text, Expr {
    }

    record Text(String text) implements Segment {
    }

    record Expr(String path, List<FilterCall> filters) implements Segment {
    }

    record FilterCall(String name, List<Arg> args) {
    }

    sealed interface Arg permits Literal, PathArg {
    }

    record Literal(Object value) implements Arg {
    }

    record PathArg(String path) implements Arg {
    }

    static Parsed parse(String original) {
        String trimmed = original.trim();
        List<Segment> trimmedSegments = scan(trimmed);
        boolean exact = trimmedSegments.size() == 1 && trimmedSegments.get(0) instanceof Expr;
        if (exact) {
            return new Parsed(trimmedSegments, true);
        }
        if (trimmed.equals(original)) {
            return new Parsed(trimmedSegments, false);
        }
        return new Parsed(scan(original), false);
    }

    private static List<Segment> scan(String source) {
        ArrayList<Segment> segments = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            int open = source.indexOf("{{", cursor);
            if (open < 0) {
                segments.add(new Text(source.substring(cursor)));
                break;
            }
            if (open > cursor) {
                segments.add(new Text(source.substring(cursor, open)));
            }
            int close = findClose(source, open + 2);
            if (close < 0) {
                throw new TemplateException(null, "模板缺少 '}}'");
            }
            segments.add(parseExpr(source.substring(open + 2, close)));
            cursor = close + 2;
        }
        return segments;
    }

    private static int findClose(String source, int from) {
        boolean single = false;
        boolean dbl = false;
        boolean escape = false;
        for (int i = from; i < source.length() - 1; i++) {
            char c = source.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (single) {
                if (c == '\'') {
                    single = false;
                }
                continue;
            }
            if (dbl) {
                if (c == '"') {
                    dbl = false;
                }
                continue;
            }
            if (c == '\'') {
                single = true;
                continue;
            }
            if (c == '"') {
                dbl = true;
                continue;
            }
            if (c == '}' && source.charAt(i + 1) == '}') {
                return i;
            }
        }
        return -1;
    }

    private static Expr parseExpr(String inner) {
        Cursor cursor = new Cursor(inner);
        cursor.skipWs();
        String path = cursor.parsePath(null);
        if (path.isEmpty()) {
            throw new TemplateException(null, "模板缺少路径");
        }
        ArrayList<FilterCall> filters = new ArrayList<>();
        while (true) {
            cursor.skipWs();
            if (cursor.done()) {
                break;
            }
            if (cursor.peek() != '|') {
                throw new TemplateException(path, "模板语法错误");
            }
            cursor.bump();
            cursor.skipWs();
            String name = cursor.identifier(path);
            cursor.skipWs();
            List<Arg> args = List.of();
            if (!cursor.done() && cursor.peek() == '(') {
                args = cursor.parseArgs(path);
            }
            filters.add(new FilterCall(name, args));
        }
        return new Expr(path, List.copyOf(filters));
    }

    private static final class Cursor {
        private final String text;
        private int index;

        private Cursor(String text) {
            this.text = text;
        }

        private boolean done() {
            return index >= text.length();
        }

        private char peek() {
            return text.charAt(index);
        }

        private char bump() {
            return text.charAt(index++);
        }

        private void skipWs() {
            while (!done() && Character.isWhitespace(peek())) {
                index++;
            }
        }

        private String parsePath(String context) {
            StringBuilder path = new StringBuilder();
            path.append(identifier(context));
            while (!done()) {
                if (peek() == '.') {
                    bump();
                    path.append('.');
                    path.append(identifier(path.toString()));
                } else if (peek() == '[') {
                    bump();
                    skipWs();
                    if (!done() && (peek() == '\'' || peek() == '"')) {
                        String key = parseString(path.toString());
                        skipWs();
                        if (done() || peek() != ']') {
                            throw new TemplateException(path.toString(), "模板下标无效");
                        }
                        bump();
                        path.append(encodeSubscript(key));
                    } else if (!done() && Character.isDigit(peek())) {
                        int start = index;
                        while (!done() && Character.isDigit(peek())) {
                            bump();
                        }
                        int subscript;
                        try {
                            subscript = Integer.parseInt(text.substring(start, index));
                        } catch (NumberFormatException ex) {
                            throw new TemplateException(path.toString(), "模板下标无效");
                        }
                        skipWs();
                        if (done() || peek() != ']') {
                            throw new TemplateException(path.toString(), "模板下标无效");
                        }
                        bump();
                        path.append('[').append(subscript).append(']');
                    } else {
                        throw new TemplateException(path.toString(), "模板下标无效");
                    }
                } else {
                    break;
                }
            }
            return path.toString();
        }

        private String identifier(String context) {
            if (done() || !isStart(peek())) {
                throw new TemplateException(context, "模板路径无效");
            }
            int start = index;
            bump();
            while (!done() && isPart(peek())) {
                bump();
            }
            return text.substring(start, index);
        }

        private List<Arg> parseArgs(String context) {
            bump();
            skipWs();
            if (!done() && peek() == ')') {
                bump();
                return List.of();
            }
            ArrayList<Arg> args = new ArrayList<>();
            while (true) {
                skipWs();
                args.add(parseArg(context));
                skipWs();
                if (!done() && peek() == ',') {
                    bump();
                    continue;
                }
                if (!done() && peek() == ')') {
                    bump();
                    break;
                }
                throw new TemplateException(context, "模板参数无效");
            }
            return List.copyOf(args);
        }

        private Arg parseArg(String context) {
            skipWs();
            if (done()) {
                throw new TemplateException(context, "模板参数无效");
            }
            char c = peek();
            if (c == '\'' || c == '"') {
                return new Literal(parseString(context));
            }
            if (c == '-' || Character.isDigit(c)) {
                return new Literal(parseNumber(context));
            }
            return new PathArg(parsePath(context));
        }

        private Object parseNumber(String context) {
            int start = index;
            if (peek() == '-') {
                bump();
            }
            if (done() || !Character.isDigit(peek())) {
                throw new TemplateException(context, "模板数字无效");
            }
            while (!done() && Character.isDigit(peek())) {
                bump();
            }
            boolean decimal = false;
            if (!done() && peek() == '.') {
                decimal = true;
                bump();
                if (done() || !Character.isDigit(peek())) {
                    throw new TemplateException(context, "模板数字无效");
                }
                while (!done() && Character.isDigit(peek())) {
                    bump();
                }
            }
            String literal = text.substring(start, index);
            if (decimal) {
                return Double.valueOf(literal);
            }
            long value = Long.parseLong(literal);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return (int) value;
            }
            return value;
        }

        private String parseString(String context) {
            char quote = bump();
            StringBuilder value = new StringBuilder();
            while (!done()) {
                char c = bump();
                if (c == '\\') {
                    if (done()) {
                        throw new TemplateException(context, "模板字符串未结束");
                    }
                    value.append(bump());
                    continue;
                }
                if (c == quote) {
                    return value.toString();
                }
                value.append(c);
            }
            throw new TemplateException(context, "模板字符串未结束");
        }

        private static boolean isStart(char c) {
            return c == '_' || Character.isLetter(c);
        }

        private static boolean isPart(char c) {
            return isStart(c) || Character.isDigit(c);
        }
    }

    /** Canonical string subscript. Single quotes, with {@code \} and {@code '} escaped. */
    static String encodeSubscript(String key) {
        StringBuilder encoded = new StringBuilder(key.length() + 4);
        encoded.append("['");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '\\' || c == '\'') {
                encoded.append('\\');
            }
            encoded.append(c);
        }
        encoded.append("']");
        return encoded.toString();
    }

    /**
     * Reads a canonical quoted subscript starting at the opening quote.
     * {@code null} when the quote is not closed.
     */
    static QuotedKey readQuoted(String path, int quoteAt) {
        StringBuilder value = new StringBuilder();
        int index = quoteAt + 1;
        while (index < path.length()) {
            char c = path.charAt(index);
            if (c == '\\') {
                if (index + 1 >= path.length()) {
                    return null;
                }
                value.append(path.charAt(index + 1));
                index += 2;
                continue;
            }
            if (c == '\'') {
                return new QuotedKey(value.toString(), index + 1);
            }
            value.append(c);
            index++;
        }
        return null;
    }

    record QuotedKey(String key, int next) {
    }
}
