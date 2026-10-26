package dev.hermit.crab.engine;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    private Extractors() {
    }

    static int apply(
            Map<String, Object> spec,
            Map<String, Object> vars,
            FilterRegistry filters,
            TemplateEngine templates
    ) {
        int count = 0;
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            if (ConchRegistry.RESERVED.contains(entry.getKey())) {
                throw problem("不能写入保留变量");
            }
            Map<String, Object> child = Values.map(entry.getValue());
            if (child == null) {
                throw problem("提取规格必须是映射");
            }
            Object value = extractSpec(child, responseSource(vars, child.get("from") == null), vars, filters, templates);
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
            TemplateEngine templates
    ) {
        Source source = spec.get("from") == null ? inherited : sourceFrom(Values.text(spec.get("from")), vars);
        String kind = selectorKind(spec);
        List<Source> matches = select(kind, Values.text(spec.get(kind)), spec, source);
        if (spec.get("each") != null) {
            Integer min = Values.integerOrNull(spec.get("min"));
            if (min != null && matches.size() < min) {
                throw problem("选择器匹配 " + matches.size() + " 条，少于 " + min + " 条");
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
                    row.put(entry.getKey(), extractSpec(child, match, vars, filters, templates));
                }
                rows.add(Collections.unmodifiableMap(row));
            }
            return List.copyOf(rows);
        }
        if (matches.isEmpty()) {
            if (Values.bool(spec.get("optional"), false)) {
                return applyFilter(spec, null, vars, filters, templates);
            }
            throw problem("选择器匹配 0 条");
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
            throw ex;
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

    private static List<Source> select(String kind, String selector, Map<String, Object> spec, Source source) {
        return switch (kind) {
            case "css" -> css(selector, source);
            case "xpath" -> xpath(selector, source);
            case "jsonpath" -> jsonpath(selector, source);
            case "regex" -> regex(selector, spec, source);
            default -> throw problem("提取规格必须且只能有一种选择器");
        };
    }

    private static List<Source> css(String selector, Source source) {
        try {
            List<Element> elements = source.element != null
                    ? source.element.select(selector)
                    : Jsoup.parse(source.text()).select(selector);
            ArrayList<Source> matches = new ArrayList<>();
            for (Element element : elements) {
                matches.add(Source.element(element));
            }
            return matches;
        } catch (IllegalArgumentException ex) {
            throw problem("选择器无效");
        }
    }

    private static List<Source> xpath(String selector, Source source) {
        try {
            org.jsoup.nodes.Document document = source.element != null
                    ? Jsoup.parse(source.element.outerHtml())
                    : Jsoup.parse(source.text());
            org.w3c.dom.Document dom = new W3CDom().namespaceAware(false).fromJsoup(document);
            XPath xpath = XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) xpath.evaluate(selector, dom, XPathConstants.NODESET);
            ArrayList<Source> matches = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                matches.add(Source.node(nodes.item(i)));
            }
            return matches;
        } catch (XPathExpressionException ex) {
            throw problem("XPath 无效");
        }
    }

    private static List<Source> jsonpath(String selector, Source source) {
        Object read;
        try {
            if (source.json != null) {
                read = JsonPath.read(source.json, selector);
            } else {
                try {
                    JSON.readTree(source.text());
                } catch (JsonProcessingException ex) {
                    throw problem("响应不是 JSON");
                }
                read = JsonPath.read(source.text(), selector);
            }
        } catch (PathNotFoundException ex) {
            return List.of();
        } catch (InvalidPathException ex) {
            throw problem("JsonPath 无效");
        } catch (RuntimeException ex) {
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

    private static List<Source> regex(String selector, Map<String, Object> spec, Source source) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(selector);
        } catch (PatternSyntaxException ex) {
            throw problem("正则无效");
        }
        int group = spec.get("group") == null ? 1 : Values.integer(spec.get("group"), 1);
        Matcher matcher = pattern.matcher(source.text());
        ArrayList<Source> matches = new ArrayList<>();
        while (matcher.find()) {
            if (group < 0 || group > matcher.groupCount()) {
                throw problem("正则没有该分组");
            }
            String text = matcher.group(group);
            matches.add(Source.text(text == null ? "" : text));
        }
        return matches;
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

    private static Source responseSource(Map<String, Object> vars, boolean required) {
        if (!required) {
            return Source.text("");
        }
        Object response = vars.get("response");
        if (!(response instanceof Map<?, ?> map) || !map.containsKey("body")) {
            throw problem("没有响应");
        }
        Object body = map.get("body");
        return Source.text(body == null ? "" : String.valueOf(body));
    }

    private static Source sourceFrom(String name, Map<String, Object> vars) {
        if (!vars.containsKey(name)) {
            throw problem("来源变量不存在");
        }
        Object value = vars.get(name);
        if (value == null) {
            return Source.text("");
        }
        if (value instanceof String text) {
            return Source.text(text);
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
            javax.xml.transform.Transformer transformer = javax.xml.transform.TransformerFactory.newInstance()
                    .newTransformer();
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

    private static RuleProblem problem(String summary) {
        return new RuleProblem(ErrorCode.RULE_MISMATCH, summary, null, null, null);
    }

    private static final class Source {
        private final String text;
        private final Element element;
        private final Node node;
        private final Object json;

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
