package dev.hermit.crab.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.hermit.common.template.Filter;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;

/**
 * 过滤器链只作用于已经取出的值，不把该值再拼进模板。
 */
final class FilterChain {

    private FilterChain() {
    }

    static Object apply(
            FilterRegistry registry,
            TemplateEngine templates,
            String chain,
            Object value,
            Map<String, Object> vars
    ) {
        if (chain == null || chain.isBlank()) {
            return value;
        }
        for (Call call : parse(chain)) {
            Filter filter = registry.get(call.name);
            if (filter == null) {
                throw new TemplateException(null, "未知过滤器: " + call.name);
            }
            ArrayList<Object> args = new ArrayList<>(call.args.size());
            for (Arg arg : call.args) {
                args.add(arg.resolve(templates, vars));
            }
            value = filter.apply(value, List.copyOf(args));
        }
        return value;
    }

    private static List<Call> parse(String chain) {
        Cursor cursor = new Cursor(chain);
        ArrayList<Call> calls = new ArrayList<>();
        while (true) {
            cursor.skipWs();
            if (cursor.done()) {
                break;
            }
            String name = cursor.identifier();
            cursor.skipWs();
            List<Arg> args = List.of();
            if (!cursor.done() && cursor.peek() == '(') {
                args = cursor.args();
            }
            calls.add(new Call(name, args));
            cursor.skipWs();
            if (cursor.done()) {
                break;
            }
            if (cursor.peek() != '|') {
                throw new TemplateException(null, "过滤器链语法错误");
            }
            cursor.bump();
        }
        return calls;
    }

    private record Call(String name, List<Arg> args) {
    }

    private interface Arg {
        Object resolve(TemplateEngine templates, Map<String, Object> vars);
    }

    private record Literal(Object value) implements Arg {
        @Override
        public Object resolve(TemplateEngine templates, Map<String, Object> vars) {
            return value;
        }
    }

    private record PathRef(String path) implements Arg {
        @Override
        public Object resolve(TemplateEngine templates, Map<String, Object> vars) {
            return templates.render("{{" + path + "}}", vars);
        }
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

        private String identifier() {
            skipWs();
            if (done() || !isStart(peek())) {
                throw new TemplateException(null, "过滤器链语法错误");
            }
            int start = index;
            bump();
            while (!done() && isPart(peek())) {
                bump();
            }
            return text.substring(start, index);
        }

        private List<Arg> args() {
            bump();
            skipWs();
            if (!done() && peek() == ')') {
                bump();
                return List.of();
            }
            ArrayList<Arg> args = new ArrayList<>();
            while (true) {
                skipWs();
                args.add(arg());
                skipWs();
                if (!done() && peek() == ',') {
                    bump();
                    continue;
                }
                if (!done() && peek() == ')') {
                    bump();
                    break;
                }
                throw new TemplateException(null, "过滤器参数无效");
            }
            return List.copyOf(args);
        }

        private Arg arg() {
            skipWs();
            if (done()) {
                throw new TemplateException(null, "过滤器参数无效");
            }
            char c = peek();
            if (c == '\'' || c == '"') {
                return new Literal(string());
            }
            if (c == '-' || Character.isDigit(c)) {
                return new Literal(number());
            }
            return new PathRef(path());
        }

        private String path() {
            StringBuilder path = new StringBuilder(identifier());
            while (!done()) {
                if (peek() == '.') {
                    bump();
                    path.append('.').append(identifier());
                } else if (peek() == '[') {
                    bump();
                    skipWs();
                    int start = index;
                    while (!done() && Character.isDigit(peek())) {
                        bump();
                    }
                    if (start == index) {
                        throw new TemplateException(null, "过滤器参数无效");
                    }
                    int end = index;
                    skipWs();
                    if (done() || peek() != ']') {
                        throw new TemplateException(null, "过滤器参数无效");
                    }
                    bump();
                    path.append('[').append(text, start, end).append(']');
                } else {
                    break;
                }
            }
            return path.toString();
        }

        private String string() {
            char quote = bump();
            StringBuilder value = new StringBuilder();
            while (!done()) {
                char c = bump();
                if (c == '\\') {
                    if (done()) {
                        throw new TemplateException(null, "过滤器字符串未结束");
                    }
                    value.append(bump());
                    continue;
                }
                if (c == quote) {
                    return value.toString();
                }
                value.append(c);
            }
            throw new TemplateException(null, "过滤器字符串未结束");
        }

        private Object number() {
            int start = index;
            if (peek() == '-') {
                bump();
            }
            if (done() || !Character.isDigit(peek())) {
                throw new TemplateException(null, "过滤器数字无效");
            }
            while (!done() && Character.isDigit(peek())) {
                bump();
            }
            boolean decimal = false;
            if (!done() && peek() == '.') {
                decimal = true;
                bump();
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

        private static boolean isStart(char c) {
            return c == '_' || Character.isLetter(c);
        }

        private static boolean isPart(char c) {
            return isStart(c) || Character.isDigit(c);
        }
    }
}
