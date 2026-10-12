package dev.hermit.common.template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TemplateEngine {

    private final FilterRegistry registry;

    public TemplateEngine() {
        this(FilterRegistry.defaults());
    }

    public TemplateEngine(FilterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Object render(Object tree, Map<String, Object> vars) {
        Objects.requireNonNull(vars, "vars");
        return renderValue(tree, vars);
    }

    public Set<String> referencedPaths(Object tree) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        collect(tree, paths);
        return Collections.unmodifiableSet(paths);
    }

    private Object renderValue(Object tree, Map<String, Object> vars) {
        if (tree instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), renderValue(entry.getValue(), vars));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (tree instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(renderValue(item, vars));
            }
            return Collections.unmodifiableList(copy);
        }
        if (tree instanceof String text) {
            return renderString(text, vars);
        }
        return tree;
    }

    private Object renderString(String text, Map<String, Object> vars) {
        Expressions.Parsed parsed = Expressions.parse(text);
        if (parsed.exact()) {
            return eval((Expressions.Expr) parsed.segments().get(0), vars);
        }
        StringBuilder rendered = new StringBuilder();
        for (Expressions.Segment segment : parsed.segments()) {
            if (segment instanceof Expressions.Text literal) {
                rendered.append(literal.text());
            } else if (segment instanceof Expressions.Expr expr) {
                Object value = eval(expr, vars);
                if (value != null) {
                    rendered.append(value);
                }
            }
        }
        return rendered.toString();
    }

    private Object eval(Expressions.Expr expr, Map<String, Object> vars) {
        boolean hasDefault = false;
        boolean hasDate = false;
        for (Expressions.FilterCall call : expr.filters()) {
            if ("default".equals(call.name())) {
                hasDefault = true;
            }
            if ("date".equals(call.name())) {
                hasDate = true;
            }
        }
        Resolved resolved = resolve(expr.path(), vars, hasDate);
        if (resolved.missing && !hasDefault) {
            throw new TemplateException(expr.path(), "路径不存在: " + expr.path());
        }
        Object value = resolved.missing ? null : resolved.value;
        for (Expressions.FilterCall call : expr.filters()) {
            Filter filter = registry.get(call.name());
            if (filter == null) {
                throw new TemplateException(expr.path(), "未知过滤器: " + call.name());
            }
            ArrayList<Object> args = new ArrayList<>(call.args().size());
            for (Expressions.Arg arg : call.args()) {
                args.add(evalArg(arg, vars));
            }
            try {
                value = filter.apply(value, args);
            } catch (TemplateException ex) {
                if (ex.path() == null || ex.path().isEmpty()) {
                    throw new TemplateException(expr.path(), ex.getMessage(), ex);
                }
                throw ex;
            }
        }
        return value;
    }

    private Object evalArg(Expressions.Arg arg, Map<String, Object> vars) {
        if (arg instanceof Expressions.Literal literal) {
            return literal.value();
        }
        Expressions.PathArg path = (Expressions.PathArg) arg;
        Resolved resolved = resolve(path.path(), vars, false);
        if (resolved.missing) {
            throw new TemplateException(path.path(), "路径不存在: " + path.path());
        }
        return resolved.value;
    }

    private void collect(Object node, Set<String> paths) {
        if (node instanceof String text) {
            Expressions.Parsed parsed = Expressions.parse(text);
            for (Expressions.Segment segment : parsed.segments()) {
                if (segment instanceof Expressions.Expr expr) {
                    paths.add(expr.path());
                    for (Expressions.FilterCall call : expr.filters()) {
                        for (Expressions.Arg arg : call.args()) {
                            if (arg instanceof Expressions.PathArg path) {
                                paths.add(path.path());
                            }
                        }
                    }
                }
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                collect(value, paths);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                collect(item, paths);
            }
        }
    }

    private static Resolved resolve(String path, Map<String, Object> vars, boolean dateFilter) {
        if ("now".equals(path) && dateFilter && !vars.containsKey("now")) {
            return new Resolved(false, "now");
        }
        Object current = vars;
        int index = 0;
        while (index < path.length()) {
            if (path.charAt(index) == '.') {
                index++;
            }
            if (index < path.length() && path.charAt(index) == '[') {
                int end = path.indexOf(']', index);
                int subscript = Integer.parseInt(path.substring(index + 1, end));
                if (!(current instanceof List<?> list) || subscript < 0 || subscript >= list.size()) {
                    return Resolved.MISSING;
                }
                current = list.get(subscript);
                index = end + 1;
                continue;
            }
            int start = index;
            while (index < path.length() && path.charAt(index) != '.' && path.charAt(index) != '[') {
                index++;
            }
            String field = path.substring(start, index);
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(field)) {
                return Resolved.MISSING;
            }
            current = map.get(field);
        }
        return new Resolved(false, current);
    }

    private record Resolved(boolean missing, Object value) {
        private static final Resolved MISSING = new Resolved(true, null);
    }
}
