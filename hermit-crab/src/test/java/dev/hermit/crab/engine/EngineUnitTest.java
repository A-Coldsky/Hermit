package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Action;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.SecretChecks;

class EngineUnitTest {

    private final FilterRegistry filters = FilterRegistry.defaults();
    private final TemplateEngine templates = new TemplateEngine(filters);

    @Test
    void preparesInputBeforeValidation() {
        Action action = ShellParser.parse("""
                shell: sample_shell
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
    void decodesByContentTypeThenMetaThenStepEncoding() {
        Charset gbk = Charset.forName("GBK");
        byte[] meta = "<html><head><meta charset=\"gbk\"></head><body>张三</body></html>".getBytes(gbk);
        assertThat(HttpCalls.decode(meta, "text/html", StandardCharsets.UTF_8)).contains("张三");
        assertThat(HttpCalls.decode("张三".getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", gbk))
                .isEqualTo("张三");
        assertThat(HttpCalls.decode("张三".getBytes(gbk), "text/html", gbk)).isEqualTo("张三");
        assertThat(HttpCalls.decode("张三".getBytes(StandardCharsets.UTF_8), null, null)).isEqualTo("张三");
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
        Extractors.apply(Map.of("viewstate", Map.of("css", "#__VIEWSTATE", "attr", "value")), vars, filters, templates);
        assertThat(vars.get("viewstate")).isEqualTo("vs-fixture");

        Extractors.apply(Map.of("name", Map.of("xpath", "//span[@id='name']")), vars, filters, templates);
        assertThat(vars.get("name")).isEqualTo("张三");

        Extractors.apply(Map.of("code", Map.of("regex", ">(A\\d+)<")), vars, filters, templates);
        assertThat(vars.get("code")).isEqualTo("A1024");

        Extractors.apply(Map.of(
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

        Extractors.apply(Map.of("literal", Map.of("css", "#v")), vars, filters, templates);
        assertThat(vars.get("literal")).isEqualTo("{{credential.password}}");
        SecretChecks.absent(String.valueOf(vars.get("literal")), EngineTestSupport.PASSWORD);

        Map<String, Object> json = vars("{\"name\":\"张三\"}");
        Extractors.apply(Map.of("name", Map.of("jsonpath", "$.name")), json, filters, templates);
        assertThat(json.get("name")).isEqualTo("张三");
    }

    @Test
    void extractMissesAndNonJsonAreRuleMismatches() {
        RuleProblem none = catchProblem(() -> Extractors.apply(
                Map.of("missing", Map.of("css", "#missing")), vars("<html></html>"), filters, templates));
        assertThat(none.summary).isEqualTo("选择器匹配 0 条");

        Map<String, Object> optional = vars("<html></html>");
        int count = Extractors.apply(
                Map.of("missing", Map.of("css", "#missing", "optional", true)), optional, filters, templates);
        assertThat(optional.get("missing")).isNull();
        assertThat(count).isZero();

        Map<String, Object> noRows = vars("<table id=\"todo\"><tr><th>单号</th></tr></table>");
        Extractors.apply(Map.of(
                "items", Map.of(
                        "css", "table#todo tr:not(:first-child)",
                        "each", Map.of("id", Map.of("css", "td")))),
                noRows, filters, templates);
        assertThat(noRows.get("items")).isEqualTo(List.of());

        RuleProblem notJson = catchProblem(() -> Extractors.apply(
                Map.of("name", Map.of("jsonpath", "$.name")), vars("<html></html>"), filters, templates));
        assertThat(notJson.summary).isEqualTo("响应不是 JSON");
    }

    @Test
    void unknownShellOrActionIsNotFound() {
        ShellRegistry registry = new ShellRegistry();
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = new RuleEngine(registry, sessions);
            AccountContext context = EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, "http://127.0.0.1");
            ExecutionResult missingShell = engine.execute(EngineTestSupport.request(
                    Origin.LOCAL, "missing", "list_todo", "alice", Map.of(), context));
            assertThat(missingShell.success()).isFalse();
            assertThat(missingShell.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(missingShell.error().message()).contains("shell");

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
