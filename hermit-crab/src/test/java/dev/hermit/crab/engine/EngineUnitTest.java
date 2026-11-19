package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Action;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.SecretChecks;

class EngineUnitTest {

    private final FilterRegistry filters = FilterRegistry.defaults();
    private final TemplateEngine templates = new TemplateEngine(filters);

    @Test
    void preparesInputBeforeValidation() {
        Action action = ConchParser.parse("""
                conch: sample_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  list_todo:
                    title: 能力
                    description: 说明
                    input:
                      page: { type: integer, default: 1, description: 页码 }
                      flag: { type: boolean, description: 开关 }
                      amount: { type: number, description: 数量 }
                      name: { type: string, required: true, description: 名称 }
                    output:
                      value: { type: string, description: 结果 }
                    examples:
                      - input: { name: a }
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - set: { marker: ok }
                """).actions().get("list_todo");
        InputPreparer preparer = new InputPreparer();
        Map<String, Object> prepared = preparer.prepare(action, Map.of(
                "page", "2",
                "flag", "TRUE",
                "amount", "1.5",
                "name", "alice"));
        assertThat(prepared).containsEntry("page", 2).containsEntry("flag", true).containsEntry("amount", 1.5);

        Map<String, Object> defaults = preparer.prepare(action, Map.of("name", "alice"));
        assertThat(defaults).containsEntry("page", 1).doesNotContainKey("flag");

        InvalidInput invalid = assertInvalid(preparer, action, Map.of("page", "no", "name", "alice"));
        assertThat(invalid.getMessage()).contains("page").doesNotContain("no");

        InvalidInput leaked = assertInvalid(preparer, action, Map.of("page", EngineTestSupport.PASSWORD, "name", "alice"));
        SecretChecks.absent(leaked.getMessage(), EngineTestSupport.PASSWORD);

        InvalidInput missing = assertInvalid(preparer, action, Map.of());
        assertThat(missing.getMessage()).contains("name");

        InvalidInput extra = assertInvalid(preparer, action, Map.of("name", "alice", "nope", "leak-me"));
        assertThat(extra.getMessage()).contains("nope").doesNotContain("leak-me");
    }

    @Test
    void rejectsOutOfRangeInputInsteadOfClamping() {
        Action action = ConchParser.parse("""
                conch: bounds_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 分页
                description: 说明
                credentials: {}
                actions:
                  page_items:
                    title: 分页
                    description: 说明
                    input:
                      page: { type: integer, minimum: 1, maximum: 100, required: true, description: 页码 }
                      pageSize: { type: integer, minimum: 1, maximum: 50, description: 每页条数 }
                      label: { type: string, required: true, description: 标签 }
                    output:
                      seen: { type: boolean, description: 是否执行 }
                    examples:
                      - input: { page: 1, label: a }
                        output: { seen: true }
                    notes: 注意
                    steps:
                      - set: { seen: true }
                """).actions().get("page_items");
        InputPreparer preparer = new InputPreparer();
        assertThat(assertInvalid(preparer, action, Map.of("page", -1, "pageSize", 20, "label", "a")).getMessage())
                .contains("page");
        assertThat(assertInvalid(preparer, action, Map.of("page", "-1", "pageSize", "20", "label", "a")).getMessage())
                .contains("page").doesNotContain("-1");
        assertThat(assertInvalid(preparer, action, Map.of("page", 1, "pageSize", 1000, "label", "a")).getMessage())
                .contains("pageSize");
        assertThat(assertInvalid(preparer, action, Map.of("page", 1, "pageSize", 20)).getMessage())
                .contains("label");
        assertThat(assertInvalid(preparer, action, Map.of("page", "no", "pageSize", 20, "label", "a")).getMessage())
                .contains("page").doesNotContain("no");
        assertThat(preparer.prepare(action, Map.of("page", 1, "pageSize", 50, "label", "a")))
                .containsEntry("page", 1)
                .containsEntry("pageSize", 50)
                .containsEntry("label", "a");
    }

    @Test
    void filterChainDoesNotReparseTheValue() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("credential", Map.of("password", EngineTestSupport.PASSWORD));
        Object literal = FilterChain.apply(
                filters, templates, "trim", "{{credential.password}}", vars);
        assertThat(literal).isEqualTo("{{credential.password}}");
        Object date = FilterChain.apply(
                filters, templates, "trim | date('yyyy-MM-dd')", "  2026/10/12 ", vars);
        assertThat(date).isEqualTo("2026-10-12");
    }

    @Test
    void masksSecretSubstringsAndLeavesMapKeys() {
        Redactor redactor = Redactor.of(Set.of(EngineTestSupport.PASSWORD, "ab", "sid-alice-1"));
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put(EngineTestSupport.PASSWORD, "待办");
        ArrayList<Object> values = new ArrayList<>();
        values.add(EngineTestSupport.PASSWORD);
        values.add("欢迎 alice，你的密码是 " + EngineTestSupport.PASSWORD);
        values.add("cookie=sid-alice-1");
        values.add("ab");
        values.add("ok");
        values.add(null);
        values.add(row);
        Object masked = OutputMask.apply(values, redactor);
        if (!(masked instanceof List<?> list) || list.size() != 7 || !(list.get(6) instanceof Map<?, ?> map)) {
            throw new AssertionError("masked shape changed");
        }
        assertThat(list.get(0)).isEqualTo("***");
        assertThat(list.get(1)).isEqualTo("欢迎 alice，你的密码是 ***");
        assertThat(list.get(2)).isEqualTo("cookie=***");
        assertThat(list.get(3)).isEqualTo("ab");
        assertThat(list.get(4)).isEqualTo("ok");
        assertThat(list.get(5)).isNull();
        if (!map.containsKey(EngineTestSupport.PASSWORD)) {
            throw new AssertionError("map key was rewritten");
        }
        assertThat(map.get(EngineTestSupport.PASSWORD)).isEqualTo("待办");
    }

    @Test
    void decodesStepEncodingThenContentTypeThenMeta() {
        Charset gbk = Charset.forName("GBK");
        byte[] meta = "<html><head><meta charset=\"gbk\"></head><body>张三</body></html>".getBytes(gbk);
        assertThat(HttpCalls.decode(meta, "text/html", null)).contains("张三");
        assertThat(HttpCalls.decode(meta, "text/html", StandardCharsets.UTF_8)).doesNotContain("张三");
        assertThat(HttpCalls.decode("张三".getBytes(gbk), "text/html; charset=utf-8", gbk)).isEqualTo("张三");
        assertThat(HttpCalls.decode("张三".getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", null))
                .isEqualTo("张三");
        assertThat(HttpCalls.decode("张三".getBytes(gbk), "text/html", null)).isNotEqualTo("张三");
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] json = "{\"name\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + json.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(json, 0, withBom, bom.length, json.length);
        assertThat(HttpCalls.decode(withBom, "application/json", null)).isEqualTo("{\"name\":\"ok\"}");
        String wide = "上游状态码 500\n" + "a".repeat(4095) + "张";
        String shown = HttpCalls.presentUpstream(wide);
        String rest = shown.substring(shown.indexOf('\n') + 1);
        assertThat(rest.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(HttpCalls.UPSTREAM_BODY_LIMIT);
        assertThat(rest).doesNotContain("张");
        assertThat(HttpCalls.originText(URI.create("http://[::1]:8080/secret"))).isEqualTo("http://[::1]:8080");
        assertThat(HttpCalls.originText(URI.create("https://[::1]/secret"))).isEqualTo("https://[::1]");
        assertThat(HttpCalls.normalizeLocation("/a%2Fb c")).isEqualTo("/a%2Fb%20c");
    }

    @Test
    void sameOriginComparesSchemeHostAndPort() {
        URI base = URI.create("http://127.0.0.1:9/app");
        assertThat(HttpCalls.sameOrigin(base, URI.create("http://127.0.0.1:9/todo"))).isTrue();
        assertThat(HttpCalls.sameOrigin(base, URI.create("http://127.0.0.1:1/steal"))).isFalse();
        assertThat(HttpCalls.sameOrigin(URI.create("https://oa.example/a"), URI.create("https://oa.example:443/b")))
                .isTrue();
        assertThat(HttpCalls.sameOrigin(URI.create("http://oa.example/a"), URI.create("http://other.example/a")))
                .isFalse();
    }

    @Test
    void extractsWithoutParsingTheValueAgain() {
        String html = """
                <html><body>
                <input id="__VIEWSTATE" value="vs-fixture">
                <span id="name">张三</span>
                <span id="v">{{credential.password}}</span>
                <table id="todo">
                <tr><th>单号</th></tr>
                <tr><td>A1024</td><td><a href="/todo/A1024">张三的请假申请</a></td><td>张三</td><td>2026/10/12</td></tr>
                </table>
                </body></html>
                """;
        Map<String, Object> vars = vars(html);
        vars.put("credential", Map.of("password", EngineTestSupport.PASSWORD));
        apply(Map.of("viewstate", Map.of("css", "#__VIEWSTATE", "attr", "value")), vars, filters, templates);
        assertThat(vars.get("viewstate")).isEqualTo("vs-fixture");

        apply(Map.of("name", Map.of("xpath", "//span[@id='name']")), vars, filters, templates);
        assertThat(vars.get("name")).isEqualTo("张三");

        apply(Map.of("code", Map.of("regex", ">(A\\d+)<")), vars, filters, templates);
        assertThat(vars.get("code")).isEqualTo("A1024");

        apply(Map.of(
                "items", Map.of(
                        "css", "table#todo tr:not(:first-child)",
                        "each", Map.of(
                                "id", Map.of("css", "td:nth-child(1)"),
                                "title", Map.of("css", "td:nth-child(2) a"),
                                "date", Map.of("css", "td:nth-child(4)", "filter", "date('yyyy-MM-dd')")))),
                vars, filters, templates);
        assertThat(vars.get("items")).isEqualTo(List.of(Map.of(
                "id", "A1024",
                "title", "张三的请假申请",
                "date", "2026-10-12")));

        apply(Map.of("literal", Map.of("css", "#v")), vars, filters, templates);
        assertThat(vars.get("literal")).isEqualTo("{{credential.password}}");
        SecretChecks.absent(String.valueOf(vars.get("literal")), EngineTestSupport.PASSWORD);

        Map<String, Object> json = vars("{\"name\":\"张三\"}");
        apply(Map.of("name", Map.of("jsonpath", "$.name")), json, filters, templates);
        assertThat(json.get("name")).isEqualTo("张三");
    }

    @Test
    void extractMissesAndNonJsonAreRuleMismatches() {
        RuleProblem none = catchProblem(() -> apply(
                Map.of("missing", Map.of("css", "#missing")), vars("<html></html>"), filters, templates));
        assertThat(none.code).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(none.summary).isEqualTo("提取 missing 失败：CSS 选择器 #missing 匹配 0 条");

        Map<String, Object> optional = vars("<html></html>");
        int count = apply(
                Map.of("missing", Map.of("css", "#missing", "optional", true)), optional, filters, templates);
        assertThat(optional.get("missing")).isNull();
        assertThat(count).isZero();

        Map<String, Object> noRows = vars("<table id=\"todo\"><tr><th>单号</th></tr></table>");
        apply(Map.of(
                "items", Map.of(
                        "css", "table#todo tr:not(:first-child)",
                        "each", Map.of("id", Map.of("css", "td")))),
                noRows, filters, templates);
        assertThat(noRows.get("items")).isEqualTo(List.of());

        RuleProblem notJson = catchProblem(() -> apply(
                Map.of("name", Map.of("jsonpath", "$.name")), vars("<html></html>"), filters, templates));
        assertThat(notJson.summary).isEqualTo("提取 name 失败：响应不是 JSON（JSON 路径 $.name）");

        Redactor json = Redactor.of(Set.of("json"));
        assertThat(json.redact(notJson.summary)).isEqualTo(notJson.summary);
        assertThat(json.redact("xxjsonyy")).isEqualTo("xx***yy");
        RuleProblem fuzzy = catchProblem(() -> apply(
                Map.of("name", Map.of("jsonpath", "$.json.name")), vars("<html></html>"), filters, templates));
        assertThat(json.redact(fuzzy.summary))
                .isEqualTo("提取 name 失败：响应不是 JSON（JSON 路径 $.***.name）");
    }

    @Test
    void illegalSelectorsNameTheVariableAndStayRuleMismatches() {
        String marker = "BODY-MARKER-991";
        RuleProblem css = catchProblem(() -> apply(
                Map.of("name", Map.of("css", "???")),
                vars("<html><body>" + marker + "</body></html>"),
                filters,
                templates));
        assertThat(css.code).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(css.summary).isEqualTo("提取 name 失败：选择器无法解析（CSS 选择器 ???）");
        assertThat(css.summary).doesNotContain(marker)
                .doesNotContain("Could not parse")
                .doesNotContain("SelectorParseException")
                .doesNotContain("内部错误");

        RuleProblem xpath = catchProblem(() -> apply(
                Map.of("name", Map.of("xpath", "(((")),
                vars("<html><body>" + marker + "</body></html>"),
                filters,
                templates));
        assertThat(xpath.summary).isEqualTo("提取 name 失败：XPath 无法解析（XPath (((）");
        assertThat(xpath.summary).doesNotContain(marker).doesNotContain("XPathExpression");

        RuleProblem regex = catchProblem(() -> apply(
                Map.of("name", Map.of("regex", "(")),
                vars("hello " + marker),
                filters,
                templates));
        assertThat(regex.summary).isEqualTo("提取 name 失败：正则无法解析（正则 (）");
        assertThat(regex.summary).doesNotContain(marker).doesNotContain("Unclosed");

        String selector = "a".repeat(90);
        RuleProblem clipped = catchProblem(() -> apply(
                Map.of("name", Map.of("css", selector)),
                vars("<html>" + marker + "</html>"),
                filters,
                templates));
        assertThat(clipped.summary).isEqualTo(
                "提取 name 失败：CSS 选择器 " + "a".repeat(80) + "… 匹配 0 条");
        assertThat(clipped.summary).doesNotContain("a".repeat(81)).doesNotContain(marker);
    }

    @Test
    void jsonPathMissesDistinguishMissingPathsFromEmptyLists() {
        String marker = "MARKER-771";
        RuleProblem missing = catchProblem(() -> apply(
                Map.of("name", Map.of("jsonpath", "$.name")),
                vars("{\"items\":[\"" + marker + "\"]}"),
                filters,
                templates));
        assertThat(missing.summary).isEqualTo("提取 name 失败：JSON 路径没有取到（JSON 路径 $.name）");
        assertThat(missing.summary).doesNotContain(marker).doesNotContain("匹配 0 条");

        RuleProblem empty = catchProblem(() -> apply(
                Map.of("items", Map.of("jsonpath", "$.items[*]")),
                vars("{\"items\":[]}"),
                filters,
                templates));
        assertThat(empty.summary).isEqualTo("提取 items 失败：JSON 路径 $.items[*] 匹配 0 条");
        assertThat(empty.summary).doesNotContain("没有取到");

        RuleProblem typed = catchProblem(() -> apply(
                Map.of("name", Map.of("jsonpath", "$.items[0].name")),
                vars("{\"items\":1,\"note\":\"" + marker + "\"}"),
                filters,
                templates));
        assertThat(typed.summary).isEqualTo("提取 name 失败：JSON 路径没有取到（JSON 路径 $.items[0].name）");
        assertThat(typed.summary).doesNotContain(marker);

        Map<String, Object> nulled = vars("{\"name\":null}");
        int count = apply(Map.of("name", Map.of("jsonpath", "$.name")), nulled, filters, templates);
        assertThat(nulled.get("name")).isEqualTo("");
        assertThat(count).isEqualTo(1);

        Map<String, Object> summed = vars("{\"items\":[1]}");
        apply(Map.of("total", Map.of("jsonpath", "$.items.sum()")), summed, filters, templates);
        assertThat(summed.get("total")).isEqualTo(1.0d);

        Map<String, Object> rows = vars("{\"other\":1}");
        apply(Map.of(
                "rows", Map.of(
                        "jsonpath", "$.items",
                        "each", Map.of("name", Map.of("jsonpath", "$.name")))),
                rows, filters, templates);
        assertThat(rows.get("rows")).isEqualTo(List.of());

        Map<String, Object> optional = vars("{\"items\":[1]}");
        int skipped = apply(
                Map.of("name", Map.of("jsonpath", "$.name", "optional", true)), optional, filters, templates);
        assertThat(optional.get("name")).isNull();
        assertThat(skipped).isZero();
    }

    @Test
    void extractFiltersOptionalAndGroupsNameTheVariable() {
        String marker = "BODY-MARKER-991";
        RuleProblem unknown = catchProblem(() -> apply(
                Map.of("name", Map.of("css", "p", "filter", "nope")),
                vars("<p>" + marker + "</p>"),
                filters,
                templates));
        assertThat(unknown.code).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(unknown.summary).isEqualTo("提取 name 失败：未知过滤器: nope");
        assertThat(unknown.summary).doesNotContain(marker);

        RuleProblem dated = catchProblem(() -> apply(
                Map.of("n", Map.of("jsonpath", "$.n", "filter", "date('yyyy-MM-dd')")),
                vars("{\"n\":20261012}"),
                filters,
                templates));
        assertThat(dated.summary).isEqualTo("提取 n 失败：日期需要文本");
        assertThat(dated.summary).doesNotContain("20261012");

        RuleProblem yes = catchProblem(() -> apply(
                Map.of("name", Map.of("css", "#missing", "optional", "yes")),
                vars("<html></html>"),
                filters,
                templates));
        assertThat(yes.summary).isEqualTo("提取 name 失败：optional 必须是 true 或 false");
        assertThat(yes.summary).doesNotContain("yes");

        RuleProblem number = catchProblem(() -> apply(
                Map.of("name", Map.of("css", "#missing", "optional", 1)),
                vars("<html></html>"),
                filters,
                templates));
        assertThat(number.summary).isEqualTo("提取 name 失败：optional 必须是 true 或 false");

        Map<String, Object> hit = vars("<span id=\"name\">张三</span>");
        apply(Map.of("name", Map.of("css", "#name", "optional", "yes")), hit, filters, templates);
        assertThat(hit.get("name")).isEqualTo("张三");

        RuleProblem group = catchProblem(() -> apply(
                Map.of("name", Map.of("regex", "hello")),
                vars("hello " + marker),
                filters,
                templates));
        assertThat(group.summary).isEqualTo("提取 name 失败：这个正则没有第 1 组（正则 hello）");
        assertThat(group.summary).doesNotContain(marker);

        RuleProblem none = catchProblem(() -> apply(
                Map.of("name", Map.of("regex", "(hello)")),
                vars("zzz"),
                filters,
                templates));
        assertThat(none.summary).isEqualTo("提取 name 失败：正则 (hello) 匹配 0 条");

        RuleProblem second = catchProblem(() -> apply(
                Map.of("name", Map.of("regex", "(hello)", "group", 2)),
                vars("hello " + marker),
                filters,
                templates));
        assertThat(second.summary).isEqualTo("提取 name 失败：这个正则没有第 2 组（正则 (hello)）");
        assertThat(second.summary).doesNotContain(marker);
    }

    @Test
    void responseObjectHintNamesTheSourceWithoutDumpingIt() {
        String marker = "INBOX-991";
        String body = "{\"items\":[{\"name\":\"" + marker + "\"}]}";
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", 200);
        envelope.put("url", "http://127.0.0.1/items");
        envelope.put("headers", Map.of("content-type", "application/json"));
        envelope.put("body", body);
        envelope.put("redirect", null);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("response", envelope);

        RuleProblem items = catchProblem(() -> apply(
                Map.of("items", Map.of("jsonpath", "$.items", "from", "response")),
                vars, filters, templates));
        assertThat(items.summary).isEqualTo(
                "提取 items 失败：JSON 路径没有取到（JSON 路径 $.items），来源是对象，正文在 body");
        assertThat(items.summary).doesNotContain(marker).doesNotContain(body);

        RuleProblem quoted = catchProblem(() -> apply(
                Map.of("items", Map.of("jsonpath", "$['items']", "from", "response")),
                vars, filters, templates));
        assertThat(quoted.summary).contains("来源是对象，正文在 body").contains("$['items']");
        assertThat(quoted.summary).doesNotContain(marker);

        RuleProblem header = catchProblem(() -> apply(
                Map.of("name", Map.of("jsonpath", "$.headers.nope", "from", "response")),
                vars, filters, templates));
        assertThat(header.summary).isEqualTo("提取 name 失败：JSON 路径没有取到（JSON 路径 $.headers.nope）");
        assertThat(header.summary).doesNotContain("正文在 body").doesNotContain(marker);

        apply(Map.of("text", Map.of("jsonpath", "$.body", "from", "response")), vars, filters, templates);
        assertThat(vars.get("text")).isEqualTo(body);
        apply(Map.of("status", Map.of("jsonpath", "$.status", "from", "response")), vars, filters, templates);
        assertThat(vars.get("status")).isEqualTo(200);

        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("row", Map.of("id", 1));
        RuleProblem row = catchProblem(() -> apply(
                Map.of("name", Map.of("jsonpath", "$.name", "from", "row")),
                plain, filters, templates));
        assertThat(row.summary).isEqualTo("提取 name 失败：JSON 路径没有取到（JSON 路径 $.name）");
        assertThat(row.summary).doesNotContain("正文在 body");

        RuleProblem named = catchProblem(() -> apply(
                Map.of("items", Map.of("jsonpath", "$.items", "from", "nope")),
                vars, filters, templates));
        assertThat(named.summary).isEqualTo("提取 items 失败：来源变量 nope 不存在");

        RuleProblem dumped = catchProblem(() -> apply(
                Map.of("items", Map.of("jsonpath", "$.items", "from", body)),
                vars, filters, templates));
        assertThat(dumped.summary).isEqualTo("提取 items 失败：来源变量不存在");
        assertThat(dumped.summary).doesNotContain(marker);

        Map<String, Object> direct = vars(body);
        apply(Map.of("items", Map.of("jsonpath", "$.items")), direct, filters, templates);
        assertThat(direct.get("items")).isEqualTo(List.of(Map.of("name", marker)));

        RuleProblem few = catchProblem(() -> apply(
                Map.of("items", Map.of(
                        "jsonpath", "$.items",
                        "from", "response",
                        "min", 1,
                        "each", Map.of("name", Map.of("jsonpath", "$.name")))),
                vars, filters, templates));
        assertThat(few.summary).isEqualTo(
                "提取 items 失败：JSON 路径 $.items 匹配 0 条，少于 1 条，来源是对象，正文在 body");
        assertThat(few.summary).doesNotContain(marker);

        RuleProblem shortfall = catchProblem(() -> apply(
                Map.of("rows", Map.of(
                        "css", "#missing",
                        "min", 1,
                        "each", Map.of("id", Map.of("css", "td")))),
                vars("<html></html>"), filters, templates));
        assertThat(shortfall.summary).isEqualTo("提取 rows 失败：CSS 选择器 #missing 匹配 0 条，少于 1 条");
        assertThat(shortfall.summary).doesNotContain("正文在 body");
    }

    @Test
    void illegalCssSelectorStaysOnTheExtractStep() throws Exception {
        String marker = "BODY-MARKER-991";
        String body = "<html><body><p>" + marker + "</p></body></html>";
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply(ScriptedLoopback.response(200, "text/html", body.getBytes(StandardCharsets.UTF_8)));
            ExecutionResult result = execute(sessions, EngineTestSupport.actionConch("css_conch", "read", """
                    - request: { method: GET, path: /page }
                    - extract:
                        name: { css: "???{{credential.password}}" }
                    """, true), loop.base(), "css_conch", "read");
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().failedStepIndex()).isEqualTo(1);
            assertThat(result.error().failedStepType()).isEqualTo("extract");
            assertThat(result.error().message())
                    .isEqualTo("提取 name 失败：选择器无法解析（CSS 选择器 ???***）");
            assertThat(result.error().summary()).isEqualTo(result.error().message());
            assertThat(result.error().message()).doesNotContain(marker)
                    .doesNotContain("内部错误")
                    .doesNotContain("SelectorParseException")
                    .doesNotContain("Could not parse");
            SecretChecks.absent(result.error().message(), EngineTestSupport.PASSWORD);
            SecretChecks.absent(EngineTestSupport.flatten(result), EngineTestSupport.PASSWORD);
            assertThat(EngineTestSupport.flatten(result)).doesNotContain(marker);
            assertThat(result.stepLogs()).anySatisfy(log -> {
                assertThat(log.index()).isEqualTo(1);
                assertThat(log.type()).isEqualTo("extract");
            });
        }
    }

    @Test
    void responseFromReadsTheEnvelopeNotTheBody() throws Exception {
        String marker = "INBOX-991";
        byte[] json = ("{\"items\":[{\"name\":\"" + marker + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply(ScriptedLoopback.response(200, "application/json", json));
            ExecutionResult missed = execute(sessions, EngineTestSupport.actionConch("env_conch", "read", """
                    - request: { method: GET, path: /items }
                    - extract:
                        items: { jsonpath: "$.items", from: response }
                    """, true), loop.base(), "env_conch", "read");
            assertThat(missed.success()).isFalse();
            assertThat(missed.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(missed.error().failedStepIndex()).isEqualTo(1);
            assertThat(missed.error().failedStepType()).isEqualTo("extract");
            assertThat(missed.error().message()).isEqualTo(
                    "提取 items 失败：JSON 路径没有取到（JSON 路径 $.items），来源是对象，正文在 body");
            assertThat(missed.error().message()).doesNotContain(marker).doesNotContain("内部错误");
            assertThat(EngineTestSupport.flatten(missed)).doesNotContain(marker);

            loop.reply(ScriptedLoopback.response(200, "application/json", json));
            ExecutionResult found = execute(sessions, EngineTestSupport.actionConch("body_conch", "read", """
                    - request: { method: GET, path: /items }
                    - extract:
                        value: { jsonpath: "$.items[0].name" }
                    """, false), loop.base(), "body_conch", "read");
            assertThat(found.success()).as(found.error() == null ? "" : found.error().message()).isTrue();
            assertThat(found.data()).isEqualTo(Map.of("value", marker));
        }
    }

    @Test
    void loginExtractFilterIsARuleMismatch() throws Exception {
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply(ScriptedLoopback.response(
                    200, "text/html", "<p>ok</p>".getBytes(StandardCharsets.UTF_8)));
            ExecutionResult result = execute(sessions, """
                    conch: login_filter
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    title: 标题
                    description: 说明
                    credentials:
                      username: { type: text, required: true }
                      password: { type: secret, required: true }
                    auth:
                      steps:
                        - request: { method: GET, path: /login }
                        - extract:
                            name: { css: p, filter: nope }
                    actions:
                      read:
                        title: 读取
                        description: 说明
                        output:
                          value: { type: string, description: 结果, value: ok }
                        examples:
                          - input: {}
                            output: { value: ok }
                        notes: 注意
                        steps:
                          - request: { method: GET, path: /never }
                    """.stripIndent(), loop.base(), "login_filter", "read");
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().message()).isEqualTo("提取 name 失败：未知过滤器: nope");
            assertThat(result.error().failedStepIndex()).isEqualTo(1);
            assertThat(result.error().failedStepType()).isEqualTo("extract");
            assertThat(result.error().message()).doesNotContain("ok</p>").doesNotContain("内部错误");
        }
    }

    @Test
    void unknownConchOrActionIsNotFound() {
        ConchRegistry registry = new ConchRegistry();
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = new RuleEngine(registry, sessions);
            AccountContext context = EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, "http://127.0.0.1");
            ExecutionResult missingConch = engine.execute(EngineTestSupport.request(
                    Origin.LOCAL, "missing", "list_todo", "alice", Map.of(), context));
            assertThat(missingConch.success()).isFalse();
            assertThat(missingConch.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(missingConch.error().message()).isEqualTo("规则包不存在");

            registry.put(EngineTestSupport.legacy());
            ExecutionResult missingAction = engine.execute(EngineTestSupport.request(
                    Origin.OFFICIAL, "legacy_oa", "missing", "alice", Map.of(), context));
            assertThat(missingAction.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(missingAction.error().message()).contains("能力");
            SecretChecks.absent(EngineTestSupport.flatten(missingAction), EngineTestSupport.PASSWORD);
        }
    }

    @Test
    void requestRejectsNullAccount() {
        AccountContext context = EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, "http://127.0.0.1");
        assertThatThrownBy(() -> new ExecutionRequest(
                "t", Origin.OFFICIAL, "legacy_oa", "list_todo", Map.of(), null, context))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void quotedStatusAndCapabilityCookieDoNotBlameTheUpstream() throws Exception {
        String body = "body-secret-value";
        byte[] ok = ScriptedLoopback.response(200, "text/plain", body.getBytes(StandardCharsets.UTF_8));
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply(ok);
            ExecutionResult quoted = execute(sessions, EngineTestSupport.actionConch("quote_conch", "ping", """
                    - request: { method: GET, path: /ping }
                    - assert: { status: "200" }
                    """, true), loop.base(), "quote_conch", "ping");
            assertThat(quoted.success()).isFalse();
            assertThat(quoted.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(quoted.error().message()).isEqualTo(StepRunner.STATUS_NOT_NUMERIC);
            assertThat(quoted.error().message())
                    .doesNotContain("改版")
                    .doesNotContain("200")
                    .doesNotContain(body)
                    .doesNotContain(EngineTestSupport.PASSWORD);

            loop.reply(ok);
            ExecutionResult numeric = execute(sessions, EngineTestSupport.actionConch("num_conch", "ping", """
                    - request: { method: GET, path: /ping }
                    - assert: { status: 200 }
                    """, true), loop.base(), "num_conch", "ping");
            assertThat(numeric.success()).as(numeric.error() == null ? "" : numeric.error().message()).isTrue();

            loop.reply(ScriptedLoopback.response(200, "text/html", "ok".getBytes(StandardCharsets.UTF_8)));
            ExecutionResult header = execute(sessions, EngineTestSupport.actionConch("head_conch", "ping", """
                    - request: { method: GET, path: /ping }
                    - assert: { header_exists: Content-Type }
                    """, true), loop.base(), "head_conch", "ping");
            assertThat(header.success()).as(header.error() == null ? "" : header.error().message()).isTrue();

            loop.reply(ok);
            ExecutionResult cookie = execute(sessions, EngineTestSupport.actionConch("cookie_conch", "ping", """
                    - request: { method: GET, path: /ping }
                    - assert: { cookie_exists: session-cookie-name }
                    """, true), loop.base(), "cookie_conch", "ping");
            assertThat(cookie.success()).isFalse();
            assertThat(cookie.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(cookie.error().message()).isEqualTo(StepRunner.COOKIE_MISSING_SUMMARY);
            assertThat(cookie.error().message())
                    .doesNotContain("密码")
                    .doesNotContain("session-cookie-name")
                    .doesNotContain(body);

            loop.reply(ok);
            ExecutionResult login = execute(sessions, loginCookieConch(), loop.base(), "login_cookie", "ping");
            assertThat(login.success()).isFalse();
            assertThat(login.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(login.error().message()).contains("登录未建立会话").contains("用户名和密码");
            assertThat(login.error().message())
                    .doesNotContain("没有找到规则要求的 Cookie")
                    .doesNotContain("session-cookie-name")
                    .doesNotContain(EngineTestSupport.PASSWORD);

            loop.reply(ok);
            ExecutionResult unknown = execute(sessions, EngineTestSupport.actionConch("statu_conch", "ping", """
                    - request: { method: GET, path: /ping }
                    - assert: { statu: "s3cret-value" }
                    """, true), loop.base(), "statu_conch", "ping");
            assertThat(unknown.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(unknown.error().message()).isEqualTo("未知的 assert 键 statu");
            assertThat(unknown.error().message()).doesNotContain("s3cret-value").doesNotContain(body);
        }
    }

    private static ExecutionResult execute(
            SessionManager sessions,
            String yaml,
            String base,
            String conch,
            String action
    ) {
        ConchRegistry registry = new ConchRegistry();
        registry.put(ConchParser.parse(yaml));
        return new RuleEngine(registry, sessions).execute(EngineTestSupport.request(
                Origin.OFFICIAL,
                conch,
                action,
                "alice",
                Map.of(),
                EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, base)));
    }

    private static String loginCookieConch() {
        return """
                conch: login_cookie
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                auth:
                  steps:
                    - request: { method: GET, path: /login }
                    - assert: { cookie_exists: session-cookie-name }
                actions:
                  ping:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - set: { seen: true }
                """;
    }

    @Test
    void failureSummaryMatchesTheCallErrorAfterMaskingAndClipping() {
        String secret = EngineTestSupport.PASSWORD;
        String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        Redactor redactor = Redactor.of(Set.of(secret));
        DevTraceDraft draft = new DevTraceDraft(Instant.parse("2026-11-19T00:00:00Z"));
        draft.add("action", 1, 1, failure("前缀" + secret + "中" + encoded + "后缀"));
        String raw = "提取 name 失败：" + secret + "x".repeat(9000);
        draft.add("action", 2, 1, failure(raw));
        JsonNode steps = ApiJson.tree(draft.render(redactor, List.of(), false)).path("steps");
        assertThat(steps.get(0).path("passed").asBoolean()).isFalse();
        assertThat(steps.get(0).path("error").asText()).isEqualTo("前缀***中***后缀");
        String clipped = steps.get(1).path("error").asText();
        String call = HttpCalls.clipPublic(HttpCalls.presentUpstream(redactor.redact(raw)));
        assertThat(clipped).isEqualTo(call);
        assertThat(clipped.getBytes(StandardCharsets.UTF_8)).hasSize(4096);
        assertThat(clipped).startsWith("提取 name 失败：***");
        SecretChecks.absent(steps.toString(), secret);
        SecretChecks.absent(steps.toString(), encoded);
    }

    private static Map<String, Object> failure(String summary) {
        LinkedHashMap<String, Object> piece = new LinkedHashMap<>();
        piece.put("type", "extract");
        piece.put("passed", false);
        piece.put("error", summary);
        return piece;
    }


    private int apply(
            Map<String, Object> spec,
            Map<String, Object> vars,
            FilterRegistry filters,
            TemplateEngine templates
    ) {
        return Extractors.apply(
                spec, vars, filters, templates, System.nanoTime() + TimeUnit.MINUTES.toNanos(1));
    }

    private static InvalidInput assertInvalid(InputPreparer preparer, Action action, Map<String, Object> raw) {
        try {
            preparer.prepare(action, raw);
        } catch (InvalidInput ex) {
            return ex;
        }
        throw new AssertionError("expected invalid input");
    }

    private static Map<String, Object> vars(String body) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("response", Map.of("body", body, "status", 200));
        return vars;
    }

    private static RuleProblem catchProblem(Runnable action) {
        try {
            action.run();
        } catch (RuleProblem ex) {
            return ex;
        }
        throw new AssertionError("expected rule problem");
    }
}
