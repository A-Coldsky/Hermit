package dev.hermit.common.template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class TemplateEngine {

    private static final ObjectMapper JSON = new ObjectMapper();

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
                    rendered.append(stringify(value, expr.path()));
                }
            }
        }
        return rendered.toString();
    }

    private static String stringify(Object value, String path) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException ex) {
                throw new TemplateException(path, "值无法序列化为 JSON");
            }
        }
        return String.valueOf(value);
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

    /**
     * Paths referenced by a template. A {@code default} filter marks that path handled.
     * Arguments of any filter stay unhandled, so {@code {{ok | default(token)}}} still sees {@code token}.
     */
    public List<TemplateRef> templateRefs(String template) {
        if (template == null) {
            return List.of();
        }
        ArrayList<TemplateRef> refs = new ArrayList<>();
        Expressions.Parsed parsed = Expressions.parse(template);
        for (Expressions.Segment segment : parsed.segments()) {
            if (!(segment instanceof Expressions.Expr expr)) {
                continue;
            }
            boolean handled = false;
            for (Expressions.FilterCall call : expr.filters()) {
                if ("default".equals(call.name())) {
                    handled = true;
                }
                for (Expressions.Arg arg : call.args()) {
                    if (arg instanceof Expressions.PathArg path) {
                        refs.add(new TemplateRef(path.path(), false));
                    }
                }
            }
            refs.add(new TemplateRef(expr.path(), handled));
        }
        return List.copyOf(refs);
    }

    /** True when any template under {@code node} applies a filter with this name. Invalid templates are false. */
    public boolean usesFilter(Object node, String filter) {
        if (filter == null || filter.isEmpty()) {
            return false;
        }
        try {
            return containsFilter(node, filter);
        } catch (TemplateException ex) {
            return false;
        }
    }

    /** True when any template under {@code node} reads {@code credential} or {@code credential.*}. */
    public boolean referencesCredential(Object node) {
        try {
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            collect(node, paths);
            for (String path : paths) {
                if ("credential".equals(path) || path.startsWith("credential.") || path.startsWith("credential[")) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean containsFilter(Object node, String filter) {
        if (node instanceof String text) {
            Expressions.Parsed parsed = Expressions.parse(text);
            for (Expressions.Segment segment : parsed.segments()) {
                if (segment instanceof Expressions.Expr expr) {
                    for (Expressions.FilterCall call : expr.filters()) {
                        if (filter.equals(call.name())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (containsFilter(value, filter)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                if (containsFilter(item, filter)) {
                    return true;
                }
            }
        }
        return false;
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
        boolean atRoot = true;
        boolean inResponse = false;
        boolean headerNames = false;
        int index = 0;
        while (index < path.length()) {
            if (path.charAt(index) == '.') {
                index++;
            }
            if (index >= path.length()) {
                return Resolved.MISSING;
            }
            Step step = readStep(path, index);
            if (step == null) {
                return Resolved.MISSING;
            }
            index = step.next;
            if (step.list) {
                if (!(current instanceof List<?> list) || step.index < 0 || step.index >= list.size()) {
                    return Resolved.MISSING;
                }
                current = list.get(step.index);
                headerNames = false;
                inResponse = false;
                atRoot = false;
                continue;
            }
            Object next = takeField(current, step.key, headerNames);
            if (next == ABSENT) {
                return Resolved.MISSING;
            }
            // 只有 response.headers 忽略大小写。头值本身不再按头名查找。
            boolean enteredHeaders = inResponse && "headers".equals(step.key) && next instanceof Map<?, ?>;
            boolean enteredResponse = atRoot && "response".equals(step.key) && next instanceof Map<?, ?>;
            current = next;
            headerNames = enteredHeaders;
            inResponse = enteredResponse;
            atRoot = false;
        }
        return new Resolved(false, current);
    }

    private static Step readStep(String path, int index) {
        if (path.charAt(index) == '[') {
            int cursor = index + 1;
            if (cursor < path.length() && path.charAt(cursor) == '\'') {
                Expressions.QuotedKey quoted = Expressions.readQuoted(path, cursor);
                if (quoted == null || quoted.next() >= path.length() || path.charAt(quoted.next()) != ']') {
                    return null;
                }
                return new Step(false, 0, quoted.key(), quoted.next() + 1);
            }
            int end = path.indexOf(']', cursor);
            if (end < 0) {
                return null;
            }
            try {
                int subscript = Integer.parseInt(path.substring(cursor, end));
                return new Step(true, subscript, null, end + 1);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        int start = index;
        while (index < path.length() && path.charAt(index) != '.' && path.charAt(index) != '[') {
            index++;
        }
        return new Step(false, 0, path.substring(start, index), index);
    }

    private static Object takeField(Object current, String field, boolean headerNames) {
        if (!(current instanceof Map<?, ?> map)) {
            return ABSENT;
        }
        if (headerNames) {
            return lookupHeader(map, field);
        }
        if (!map.containsKey(field)) {
            return ABSENT;
        }
        return map.get(field);
    }

    /**
     * Exact key wins. Otherwise the key equal to the lowercased name, then a single
     * case-insensitive match. Two different casings and no lowercase key are missing,
     * so a header value is not chosen arbitrarily.
     */
    private static Object lookupHeader(Map<?, ?> map, String field) {
        if (map.containsKey(field)) {
            return map.get(field);
        }
        String folded = field.toLowerCase(Locale.ROOT);
        Object single = ABSENT;
        int matches = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            if (key.equals(folded)) {
                return entry.getValue();
            }
            if (folded.equals(key.toLowerCase(Locale.ROOT))) {
                matches++;
                single = entry.getValue();
            }
        }
        return matches == 1 ? single : ABSENT;
    }

    private static final Object ABSENT = new Object();

    private record Step(boolean list, int index, String key, int next) {
    }

    private record Resolved(boolean missing, Object value) {
        private static final Resolved MISSING = new Resolved(true, null);
    }
}
