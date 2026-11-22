package dev.hermit.crab.engine;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;

final class Extractors {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SELECTORS = List.of("css", "xpath", "jsonpath", "regex");
    /** {@code response} 信封上的字段。路径第一段是这些名字时，不提示作者去读 body。 */
    private static final Set<String> RESPONSE_FIELDS = Set.of("status", "body", "headers", "url", "redirect", "file");
    /**
     * 一次 {@code Matcher.find} 不看能力 deadline，也不响应中断。可能回溯很久的匹配放到这组守护线程上，
     * 调用方只等到 deadline：到点就按超时返回，账号锁由 {@code RuleEngine} 的 finally 放开。
     * 卡住的那次 find 继续占着一个池槽，直到自己返回。池没有等待队列，满了立刻按超时失败，
     * 不为每次提取新建线程。没有量词或只有一个量词的正则仍在调用线程上匹配。
     */
    private static final int MATCH_THREADS = 32;
    private static final ThreadPoolExecutor MATCHES = newMatchPool();
    /** 工厂的查找比求值贵得多，而且不保证线程安全，所以每个线程留一个。 */
    private static final ThreadLocal<XPathFactory> XPATH_FACTORY = ThreadLocal.withInitial(XPathFactory::newInstance);
    private static final ThreadLocal<javax.xml.transform.TransformerFactory> TRANSFORMER_FACTORY =
            ThreadLocal.withInitial(javax.xml.transform.TransformerFactory::newInstance);

    private Extractors() {
    }

    static int apply(
            Map<String, Object> spec,
            Map<String, Object> vars,
            FilterRegistry filters,
            TemplateEngine templates,
            long deadlineNanos
    ) {
        int count = 0;
        // 同一步骤里多个字段读同一份正文时共用一个来源，HTML、JSON 只解析一次。
        Sources sources = new Sources();
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            if (ConchRegistry.RESERVED.contains(entry.getKey())) {
                throw problem("不能写入保留变量");
            }
            Map<String, Object> child = Values.map(entry.getValue());
            if (child == null) {
                throw problem("提取规格必须是映射");
            }
            Object value;
            try {
                value = extractSpec(
                        child,
                        responseSource(vars, child.get("from") == null, sources),
                        vars,
                        filters,
                        templates,
                        deadlineNanos,
                        sources);
            } catch (RuleProblem ex) {
                // 试跑失败时要能看出是哪个变量、哪条表达式没取到。
                throw problem("提取 " + entry.getKey() + " 失败：" + ex.summary);
            }
            vars.put(entry.getKey(), value);
            count += countOf(value);
        }
        return count;
    }

    private static Object extractSpec(
            Map<String, Object> spec,
            Source inherited,
            Map<String, Object> vars,
            FilterRegistry filters,
            TemplateEngine templates,
            long deadlineNanos,
            Sources sources
    ) {
        Source source = spec.get("from") == null
                ? inherited
                : sourceFrom(Values.text(spec.get("from")), vars, sources);
        String kind = selectorKind(spec);
        String selector = Values.text(spec.get(kind));
        String where = label(kind) + " " + clip(selector);
        List<Source> matches;
        boolean pathMissing = false;
        try {
            matches = select(kind, selector, spec, source, deadlineNanos);
        } catch (PathMissing ignored) {
            matches = List.of();
            pathMissing = true;
        } catch (RuleProblem ex) {
            throw problem(ex.summary + "（" + where + "）");
        }
        if (spec.get("each") != null) {
            Integer min = Values.integerOrNull(spec.get("min"));
            if (min != null && matches.size() < min) {
                throw problem(withHint(
                        where + " 匹配 " + matches.size() + " 条，少于 " + min + " 条", selector, source));
            }
            Map<String, Object> children = Values.map(spec.get("each"));
            if (children == null) {
                throw problem("each 必须是映射");
            }
            ArrayList<Object> rows = new ArrayList<>();
            for (Source match : matches) {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : children.entrySet()) {
                    Map<String, Object> child = Values.map(entry.getValue());
                    if (child == null) {
                        throw problem("子规格必须是映射");
                    }
                    row.put(entry.getKey(), extractSpec(child, match, vars, filters, templates, deadlineNanos, sources));
                }
                rows.add(Collections.unmodifiableMap(row));
            }
            return List.copyOf(rows);
        }
        if (matches.isEmpty()) {
            if (optionalFlag(spec.get("optional"))) {
                return applyFilter(spec, null, vars, filters, templates);
            }
            throw problem(missSummary(where, selector, source, pathMissing));
        }
        Object value = scalar(matches.get(0), spec);
        return applyFilter(spec, value, vars, filters, templates);
    }

    private static Object applyFilter(
            Map<String, Object> spec,
            Object value,
            Map<String, Object> vars,
            FilterRegistry filters,
            TemplateEngine templates
    ) {
        if (spec.get("filter") == null) {
            return value;
        }
        try {
            return FilterChain.apply(filters, templates, Values.text(spec.get("filter")), value, vars);
        } catch (TemplateException ex) {
            throw problem(filterFailure(ex));
        }
    }

    private static String selectorKind(Map<String, Object> spec) {
        String kind = null;
        for (String candidate : SELECTORS) {
            if (spec.get(candidate) != null) {
                if (kind != null) {
                    throw problem("提取规格必须且只能有一种选择器");
                }
                kind = candidate;
            }
        }
        if (kind == null) {
            throw problem("提取规格必须且只能有一种选择器");
        }
        return kind;
    }

    private static List<Source> select(
            String kind,
            String selector,
            Map<String, Object> spec,
            Source source,
            long deadlineNanos
    ) {
        return switch (kind) {
            case "css" -> css(selector, source);
            case "xpath" -> xpath(selector, source);
            case "jsonpath" -> jsonpath(selector, source);
            case "regex" -> regex(selector, spec, source, deadlineNanos);
            default -> throw problem("提取规格必须且只能有一种选择器");
        };
    }

    private static List<Source> css(String selector, Source source) {
        try {
            List<Element> elements;
            if (source.element != null) {
                // :matchText 会把文本节点替换成合成元素，克隆后再查，不动父文档和同行的其它字段。
                Element target = rewritesDocument(selector) ? source.element.clone() : source.element;
                elements = target.select(selector);
            } else if (rewritesDocument(selector)) {
                // :matchText 会往文档里插入合成元素，不能落在各字段共用的那一份上。
                elements = Jsoup.parse(source.text()).select(selector);
            } else {
                elements = source.document().select(selector);
            }
            ArrayList<Source> matches = new ArrayList<>();
            for (Element element : elements) {
                matches.add(Source.element(element));
            }
            return matches;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // jsoup 1.23 的 SelectorParseException 继承 IllegalStateException。句子只用短原因，
            // 不附解析器原文：原文会再写一遍选择器，极端情况下也可能带上片段。
            throw problem("选择器无法解析");
        }
    }

    private static boolean rewritesDocument(String selector) {
        return selector.toLowerCase(Locale.ROOT).contains(":matchtext");
    }

    private static List<Source> xpath(String selector, Source source) {
        try {
            XPath xpath = XPATH_FACTORY.get().newXPath();
            NodeList nodes = (NodeList) xpath.evaluate(selector, source.dom(), XPathConstants.NODESET);
            ArrayList<Source> matches = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                matches.add(Source.node(nodes.item(i)));
            }
            return matches;
        } catch (XPathExpressionException ex) {
            throw problem("XPath 无法解析");
        }
    }

    private static List<Source> jsonpath(String selector, Source source) {
        Object read;
        try {
            if (source.json != null) {
                // 函数（append 等）会就地改对象或数组；带括号的表达式先序列化成独立副本，不动共享 JSON 和 vars。
                if (selector.indexOf('(') >= 0 && (source.json instanceof Map<?, ?> || source.json instanceof List<?>)) {
                    read = JsonPath.read(JSON.writeValueAsString(source.json), selector);
                } else {
                    read = JsonPath.read(source.json, selector);
                }
            } else {
                DocumentContext shared = source.jsonContext();
                // 函数（append 等）会就地改数组，带括号的表达式各读各的一份。
                read = selector.indexOf('(') < 0 ? shared.read(selector) : JsonPath.read(source.text(), selector);
            }
        } catch (PathNotFoundException ex) {
            throw new PathMissing();
        } catch (InvalidPathException ex) {
            throw problem("JSON 路径无法解析");
        } catch (JsonProcessingException | RuntimeException ex) {
            throw problem("响应不是 JSON");
        }
        boolean indefinite = selector.contains("*")
                || selector.contains("..")
                || selector.contains("[?")
                || selector.contains("[,");
        if (indefinite && read instanceof List<?> list) {
            ArrayList<Source> matches = new ArrayList<>();
            for (Object item : list) {
                matches.add(Source.json(item));
            }
            return matches;
        }
        return List.of(Source.json(read));
    }

    private static List<Source> regex(
            String selector,
            Map<String, Object> spec,
            Source source,
            long deadlineNanos
    ) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(selector);
        } catch (PatternSyntaxException ex) {
            throw problem("正则无法解析");
        }
        int group = spec.get("group") == null ? 1 : Values.integer(spec.get("group"), 1);
        String text = source.text();
        if (RegexShape.callerSafe(selector)) {
            return scan(pattern, group, text, deadlineNanos);
        }
        return scanBounded(pattern, group, text, deadlineNanos);
    }

    private static List<Source> scan(Pattern pattern, int group, String text, long deadlineNanos) {
        ensureDeadline(deadlineNanos);
        Matcher matcher = pattern.matcher(text);
        ArrayList<Source> matches = new ArrayList<>();
        while (matcher.find()) {
            // 一次 find 返回之后再看时间。卡住的那一次 find 靠外层的等待上限结束。
            ensureDeadline(deadlineNanos);
            if (group < 0 || group > matcher.groupCount()) {
                throw problem("这个正则没有第 " + group + " 组");
            }
            String captured = matcher.group(group);
            matches.add(Source.text(captured == null ? "" : captured));
        }
        ensureDeadline(deadlineNanos);
        return matches;
    }

    private static List<Source> scanBounded(Pattern pattern, int group, String text, long deadlineNanos) {
        ensureDeadline(deadlineNanos);
        Future<List<Source>> pending;
        try {
            pending = MATCHES.submit(() -> scan(pattern, group, text, deadlineNanos));
        } catch (RejectedExecutionException ex) {
            throw new DeadlineExceeded(-1, "extract", List.of());
        }
        long left = deadlineNanos - System.nanoTime();
        if (left < 0) {
            left = 0;
        }
        try {
            return pending.get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            // find 不会因为中断停下来，也不使用 Thread.stop。取消只是不再等。
            // 这次匹配继续占着一个池槽，直到 find 自己返回。
            pending.cancel(false);
            throw new DeadlineExceeded(-1, "extract", List.of());
        } catch (InterruptedException ex) {
            pending.cancel(false);
            Thread.currentThread().interrupt();
            throw new DeadlineExceeded(-1, "extract", List.of());
        } catch (ExecutionException ex) {
            throw unwrap(ex);
        }
    }

    private static void ensureDeadline(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new DeadlineExceeded(-1, "extract", List.of());
        }
    }

    private static RuntimeException unwrap(ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof DeadlineExceeded exceeded) {
            return exceeded;
        }
        if (cause instanceof RuleProblem problem) {
            return problem;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(cause);
    }

    private static ThreadPoolExecutor newMatchPool() {
        return new ThreadPoolExecutor(
                MATCH_THREADS,
                MATCH_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "hermit-regex-match");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static Object scalar(Source match, Map<String, Object> spec) {
        String attr = spec.get("attr") == null ? null : Values.text(spec.get("attr"));
        boolean html = Values.bool(spec.get("html"), false);
        if (attr != null && html) {
            throw problem("attr 与 html 不能同时使用");
        }
        if (match.element != null) {
            if (html) {
                return match.element.html();
            }
            if (attr != null) {
                return match.element.attr(attr);
            }
            return match.element.text();
        }
        if (match.node != null) {
            if (html) {
                return innerXml(match.node);
            }
            if (attr != null && match.node instanceof org.w3c.dom.Element element) {
                return element.getAttribute(attr);
            }
            return normalize(match.node.getTextContent());
        }
        if (match.json != null && attr == null && !html) {
            return match.json;
        }
        return match.text == null ? "" : match.text;
    }

    private static Source responseSource(Map<String, Object> vars, boolean required, Sources sources) {
        if (!required) {
            return Source.text("");
        }
        Object response = vars.get("response");
        if (!(response instanceof Map<?, ?> map) || !map.containsKey("body")) {
            throw problem("没有响应");
        }
        Object body = map.get("body");
        return sources.text(body == null ? "" : String.valueOf(body));
    }

    private static Source sourceFrom(String name, Map<String, Object> vars, Sources sources) {
        if (!vars.containsKey(name)) {
            throw problem(missingSource(name));
        }
        Object value = vars.get(name);
        if (value == null) {
            return Source.text("");
        }
        if (value instanceof String text) {
            return sources.text(text);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return Source.json(value);
        }
        return Source.text(String.valueOf(value));
    }

    private static int countOf(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        return value == null ? 0 : 1;
    }

    private static String innerXml(Node node) {
        StringBuilder builder = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            builder.append(outerXml(children.item(i)));
        }
        return builder.toString();
    }

    private static String outerXml(Node node) {
        try {
            javax.xml.transform.Transformer transformer = TRANSFORMER_FACTORY.get().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(
                    new javax.xml.transform.dom.DOMSource(node),
                    new javax.xml.transform.stream.StreamResult(writer));
            return writer.toString();
        } catch (javax.xml.transform.TransformerException ex) {
            return node.getTextContent() == null ? "" : node.getTextContent();
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String label(String kind) {
        return switch (kind) {
            case "css" -> "CSS 选择器";
            case "xpath" -> "XPath";
            case "jsonpath" -> "JSON 路径";
            case "regex" -> "正则";
            default -> kind;
        };
    }

    /**
     * 确定路径没有取到，和不定路径一条都没匹配到，对外不是同一句。
     * {@code optional} 仍把这两种都当成 0 条。
     */
    private static String missSummary(String where, String selector, Source source, boolean pathMissing) {
        String summary = pathMissing ? "JSON 路径没有取到（" + where + "）" : where + " 匹配 0 条";
        return withHint(summary, selector, source);
    }

    /**
     * 来源是带 {@code body} 的对象，路径第一段又不是信封字段时，作者多半把信封当成了正文。
     * 只追加固定短句，不打印对象。
     */
    private static String withHint(String summary, String selector, Source source) {
        if (!bodySourceHint(selector, source)) {
            return summary;
        }
        return summary + "，来源是对象，正文在 body";
    }

    private static boolean bodySourceHint(String selector, Source source) {
        if (!(source.json instanceof Map<?, ?> map) || !map.containsKey("body")) {
            return false;
        }
        String key = rootProperty(selector);
        return key != null && !RESPONSE_FIELDS.contains(key);
    }

    /** {@code $.items}、{@code $['items']}、{@code $..items} 的第一段。认不出来就返回 null。 */
    private static String rootProperty(String selector) {
        if (selector == null) {
            return null;
        }
        int index = 0;
        int length = selector.length();
        while (index < length && selector.charAt(index) <= ' ') {
            index++;
        }
        if (index >= length || selector.charAt(index) != '$') {
            return null;
        }
        index++;
        if (index < length && selector.charAt(index) == '.') {
            index++;
            if (index < length && selector.charAt(index) == '.') {
                index++;
            }
        }
        if (index >= length) {
            return null;
        }
        char current = selector.charAt(index);
        if (current == '[') {
            index++;
            while (index < length && selector.charAt(index) <= ' ') {
                index++;
            }
            if (index >= length) {
                return null;
            }
            char quote = selector.charAt(index);
            if (quote != '\'' && quote != '"') {
                return null;
            }
            int end = selector.indexOf(quote, index + 1);
            if (end <= index + 1) {
                return null;
            }
            return selector.substring(index + 1, end);
        }
        if (!Character.isLetter(current) && current != '_') {
            return null;
        }
        int start = index;
        index++;
        while (index < length) {
            char part = selector.charAt(index);
            if (!Character.isLetterOrDigit(part) && part != '_') {
                break;
            }
            index++;
        }
        return selector.substring(start, index);
    }

    private static boolean optionalFlag(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw problem("optional 必须是 true 或 false");
    }

    private static String filterFailure(TemplateException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "过滤器失败";
        }
        return message;
    }

    /**
     * 只回显像变量名的短名字。{@code from} 会先被模板渲染，渲染结果若是正文就不能写进错误。
     */
    private static String missingSource(String name) {
        if (name != null && name.length() <= 80 && identifierLike(name)) {
            return "来源变量 " + name + " 不存在";
        }
        return "来源变量不存在";
    }

    private static boolean identifierLike(String name) {
        if (name.isEmpty()) {
            return false;
        }
        int first = name.codePointAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int index = Character.charCount(first); index < name.length(); ) {
            int point = name.codePointAt(index);
            if (!(Character.isLetterOrDigit(point) || point == '_' || point == '.')) {
                return false;
            }
            index += Character.charCount(point);
        }
        return true;
    }

    /** 规则里写的表达式，太长时截断，免得一条报错占满屏。 */
    private static String clip(String selector) {
        if (selector == null) {
            return "";
        }
        return selector.length() <= 80 ? selector : selector.substring(0, 80) + "…";
    }

    private static RuleProblem problem(String summary) {
        return new RuleProblem(ErrorCode.RULE_MISMATCH, summary, null, null, null);
    }

    /**
     * 分不清会不会在一次 find 里耗过 deadline 时，交给带超时的匹配。
     * 嵌套量词、量词作用在含 {@code |} 的组上、以及两个以上会回溯的量词，都走线程池。
     */
    private static final class RegexShape {

        private final String pattern;
        private int index;

        private RegexShape(String pattern) {
            this.pattern = pattern;
        }

        private static boolean callerSafe(String pattern) {
            try {
                RegexShape shape = new RegexShape(pattern);
                Shape node = shape.parseAlt();
                if (shape.index != pattern.length()) {
                    return false;
                }
                if (node.risky || node.quantifiers > 1) {
                    return false;
                }
                return true;
            } catch (RuntimeException ex) {
                return false;
            }
        }

        private Shape parseAlt() {
            Shape node = parseSeq();
            if (index < pattern.length() && pattern.charAt(index) == '|') {
                index++;
                return Shape.alt(node, parseAlt());
            }
            return node;
        }

        private Shape parseSeq() {
            Shape node = Shape.LEAF;
            while (index < pattern.length()) {
                char current = pattern.charAt(index);
                if (current == '|' || current == ')') {
                    break;
                }
                node = Shape.seq(node, maybeQuantify(parseAtom()));
            }
            return node;
        }

        private Shape parseAtom() {
            if (index >= pattern.length()) {
                throw fail();
            }
            char current = pattern.charAt(index);
            if (current == '\\') {
                return escape();
            }
            if (current == '[') {
                skipClass();
                return Shape.LEAF;
            }
            if (current == '(') {
                return group();
            }
            if (current == '.' || current == '^' || current == '$') {
                index++;
                return Shape.LEAF;
            }
            if (current == '*' || current == '+' || current == '?' || current == '{'
                    || current == ')' || current == '|') {
                throw fail();
            }
            index++;
            return Shape.LEAF;
        }

        private Shape group() {
            index++;
            if (index < pattern.length() && pattern.charAt(index) == '?') {
                index++;
                if (index >= pattern.length()) {
                    throw fail();
                }
                char kind = pattern.charAt(index);
                if (kind == '#') {
                    index++;
                    int end = pattern.indexOf(')', index);
                    if (end < 0) {
                        throw fail();
                    }
                    index = end + 1;
                    return Shape.LEAF;
                }
                if (kind == '<' || kind == '\'') {
                    return namedOrLookbehind();
                }
                if (kind == ':' || kind == '=' || kind == '!' || kind == '>') {
                    index++;
                    return close(parseAlt());
                }
                boolean extended = false;
                while (index < pattern.length()) {
                    char flag = pattern.charAt(index);
                    if ("imsdUux-".indexOf(flag) < 0) {
                        break;
                    }
                    if (flag == 'x') {
                        extended = true;
                    }
                    index++;
                }
                if (extended) {
                    throw fail();
                }
                if (index < pattern.length() && pattern.charAt(index) == ':') {
                    index++;
                    return close(parseAlt());
                }
                if (index < pattern.length() && pattern.charAt(index) == ')') {
                    index++;
                    return Shape.LEAF;
                }
                throw fail();
            }
            return close(parseAlt());
        }

        private Shape namedOrLookbehind() {
            char quote = pattern.charAt(index);
            index++;
            if (quote == '<' && index < pattern.length()
                    && (pattern.charAt(index) == '=' || pattern.charAt(index) == '!')) {
                index++;
                return close(parseAlt());
            }
            char end = quote == '<' ? '>' : '\'';
            int closeAt = pattern.indexOf(end, index);
            if (closeAt < 0) {
                throw fail();
            }
            index = closeAt + 1;
            return close(parseAlt());
        }

        private Shape close(Shape inner) {
            if (index >= pattern.length() || pattern.charAt(index) != ')') {
                throw fail();
            }
            index++;
            return inner;
        }

        private Shape maybeQuantify(Shape atom) {
            if (index >= pattern.length()) {
                return atom;
            }
            char current = pattern.charAt(index);
            if (current != '*' && current != '+' && current != '?' && current != '{') {
                return atom;
            }
            if (current == '{') {
                if (!consumeBrace()) {
                    throw fail();
                }
            } else {
                index++;
            }
            boolean possessive = false;
            if (index < pattern.length() && pattern.charAt(index) == '+') {
                possessive = true;
                index++;
            } else if (index < pattern.length() && pattern.charAt(index) == '?') {
                index++;
            }
            if (index < pattern.length() && (pattern.charAt(index) == '+' || pattern.charAt(index) == '?')) {
                throw fail();
            }
            if (possessive) {
                return atom;
            }
            boolean nested = atom.quantifiers > 0 || atom.alternation || atom.risky;
            return new Shape(atom.quantifiers + 1, atom.alternation, nested);
        }

        private boolean consumeBrace() {
            int start = index;
            index++;
            if (index >= pattern.length() || !Character.isDigit(pattern.charAt(index))) {
                index = start;
                return false;
            }
            while (index < pattern.length() && Character.isDigit(pattern.charAt(index))) {
                index++;
            }
            if (index < pattern.length() && pattern.charAt(index) == ',') {
                index++;
                while (index < pattern.length() && Character.isDigit(pattern.charAt(index))) {
                    index++;
                }
            }
            if (index < pattern.length() && pattern.charAt(index) == '}') {
                index++;
                return true;
            }
            index = start;
            return false;
        }

        private Shape escape() {
            index++;
            if (index >= pattern.length()) {
                throw fail();
            }
            char current = pattern.charAt(index++);
            if (current == 'Q') {
                int end = pattern.indexOf("\\E", index);
                if (end < 0) {
                    index = pattern.length();
                } else {
                    index = end + 2;
                }
                return Shape.LEAF;
            }
            if ((current == 'p' || current == 'P' || current == 'x' || current == 'u')
                    && index < pattern.length() && pattern.charAt(index) == '{') {
                int end = pattern.indexOf('}', index);
                if (end < 0) {
                    throw fail();
                }
                index = end + 1;
                return Shape.LEAF;
            }
            if (current == 'k' && index < pattern.length()) {
                char quote = pattern.charAt(index);
                if (quote == '<' || quote == '\'') {
                    char end = quote == '<' ? '>' : '\'';
                    int closeAt = pattern.indexOf(end, index + 1);
                    if (closeAt < 0) {
                        throw fail();
                    }
                    index = closeAt + 1;
                }
            }
            return Shape.LEAF;
        }

        private void skipClass() {
            index++;
            if (index < pattern.length() && pattern.charAt(index) == '^') {
                index++;
            }
            if (index < pattern.length() && pattern.charAt(index) == ']') {
                index++;
            }
            while (index < pattern.length()) {
                char current = pattern.charAt(index);
                if (current == '\\') {
                    index += 2;
                    continue;
                }
                index++;
                if (current == ']') {
                    return;
                }
                if (current == '[') {
                    index--;
                    skipClass();
                }
            }
            throw fail();
        }

        private static IllegalArgumentException fail() {
            return new IllegalArgumentException("regex shape");
        }
    }

    private record Shape(int quantifiers, boolean alternation, boolean risky) {
        private static final Shape LEAF = new Shape(0, false, false);

        private static Shape seq(Shape left, Shape right) {
            return new Shape(
                    left.quantifiers + right.quantifiers,
                    left.alternation || right.alternation,
                    left.risky || right.risky);
        }

        private static Shape alt(Shape left, Shape right) {
            return new Shape(
                    Math.max(left.quantifiers, right.quantifiers),
                    true,
                    left.risky || right.risky);
        }
    }

    /** 确定的 JSON 路径没有取到。不是 {@link RuleProblem}，这样 {@code optional} 仍能把它当成 0 条。 */
    private static final class PathMissing extends RuntimeException {
        private PathMissing() {
        }
    }

    /** 一次 extract 步骤里按文本内容复用来源。变量中途被改写时文本不同，拿到的是另一个来源。 */
    private static final class Sources {
        private final Map<String, Source> byText = new java.util.HashMap<>();

        private Source text(String text) {
            return byText.computeIfAbsent(text, Source::text);
        }
    }

    private static final class Source {
        private final String text;
        private final Element element;
        private final Node node;
        private final Object json;
        /** 下面三项是解析结果的缓存，只读不改；解析失败不缓存，下次照样报同一个错。 */
        private org.jsoup.nodes.Document document;
        private org.w3c.dom.Document dom;
        private DocumentContext jsonContext;

        private org.jsoup.nodes.Document document() {
            if (document == null) {
                document = Jsoup.parse(element != null ? element.outerHtml() : text());
            }
            return document;
        }

        private org.w3c.dom.Document dom() {
            if (dom == null) {
                dom = new W3CDom().namespaceAware(false).fromJsoup(document());
            }
            return dom;
        }

        /** 先用 Jackson 判定是不是 JSON，再交给 JsonPath 自己解析，和逐字段解析时的结果一致。 */
        private DocumentContext jsonContext() {
            if (jsonContext == null) {
                String body = text();
                try {
                    JSON.readTree(body);
                } catch (JsonProcessingException ex) {
                    throw problem("响应不是 JSON");
                }
                jsonContext = JsonPath.parse(body);
            }
            return jsonContext;
        }

        private Source(String text, Element element, Node node, Object json) {
            this.text = text;
            this.element = element;
            this.node = node;
            this.json = json;
        }

        private static Source text(String text) {
            return new Source(text, null, null, null);
        }

        private static Source element(Element element) {
            return new Source(null, element, null, null);
        }

        private static Source node(Node node) {
            return new Source(null, null, node, null);
        }

        private static Source json(Object json) {
            return new Source(null, null, null, json);
        }

        private String text() {
            if (text != null) {
                return text;
            }
            if (element != null) {
                return element.outerHtml();
            }
            if (node != null) {
                return outerXml(node);
            }
            if (json instanceof String string) {
                return string;
            }
            if (json != null) {
                try {
                    return JSON.writeValueAsString(json);
                } catch (JsonProcessingException ex) {
                    return String.valueOf(json);
                }
            }
            return "";
        }
    }
}
