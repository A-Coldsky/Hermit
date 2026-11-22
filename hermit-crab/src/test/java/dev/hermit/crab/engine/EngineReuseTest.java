package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;

/** 解析结果和编译结果复用之后，提取和脚本的结果与逐次重新解析时一致。 */
class EngineReuseTest {

    private static final String HTML = """
            <html><body>
            <h1 id="title">订单</h1>
            <div class="row"><span class="no">A-1</span><span class="amt"><b>10</b></span></div>
            <div class="row"><span class="no">A-2</span><span class="amt"><b>20</b></span></div>
            </body></html>
            """;

    private final FilterRegistry filters = FilterRegistry.defaults();
    private final TemplateEngine templates = new TemplateEngine(filters);

    @Test
    void severalFieldsReadOneHtmlBody() {
        Map<String, Object> vars = response(HTML);
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("title", Map.of("css", "#title"));
        spec.put("first", Map.of("xpath", "//span[@class='no']"));
        spec.put("rows", Map.of("css", "div.row", "each", ordered(
                "no", Map.of("css", ".no"),
                "amount", Map.of("xpath", "//span[@class='amt']/b"),
                "cell", Map.of("xpath", "//span[@class='amt']", "html", true))));
        spec.put("viaXpath", Map.of("xpath", "//div[@class='row']", "each", ordered(
                "no", Map.of("xpath", "//span[@class='no']"),
                "amount", Map.of("css", ".amt"))));
        spec.put("again", Map.of("css", "#title"));

        int count = extract(spec, vars);

        assertThat(vars.get("title")).isEqualTo("订单");
        assertThat(vars.get("again")).isEqualTo("订单");
        assertThat(vars.get("first")).isEqualTo("A-1");
        assertThat(vars.get("rows")).isEqualTo(List.of(
                Map.of("no", "A-1", "amount", "10", "cell", "<b>10</b>"),
                Map.of("no", "A-2", "amount", "20", "cell", "<b>20</b>")));
        assertThat(vars.get("viaXpath")).isEqualTo(List.of(
                Map.of("no", "A-1", "amount", "10"),
                Map.of("no", "A-2", "amount", "20")));
        assertThat(count).isEqualTo(7);
    }

    @Test
    void severalFieldsReadOneJsonBody() {
        Map<String, Object> vars = response("""
                {"total": 2, "items": [{"id": 1, "tags": ["a"]}, {"id": 2, "tags": ["b", "c"]}]}
                """);
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("total", Map.of("jsonpath", "$.total"));
        spec.put("ids", Map.of("jsonpath", "$.items[*]", "each", Map.of("id", Map.of("jsonpath", "$.id"))));
        spec.put("first", Map.of("jsonpath", "$.items[0]"));
        spec.put("missing", Map.of("jsonpath", "$.nope", "optional", true));

        extract(spec, vars);

        assertThat(vars.get("total")).isEqualTo(2);
        assertThat(vars.get("ids")).isEqualTo(List.of(Map.of("id", 1), Map.of("id", 2)));
        assertThat(vars.get("first")).isEqualTo(Map.of("id", 1, "tags", List.of("a")));
        assertThat(vars).containsEntry("missing", null);
    }

    /** 同一步骤里前一个字段改写了变量，后一个字段读到的是新值，不是缓存里的旧解析结果。 */
    @Test
    void laterFieldSeesAVariableRewrittenEarlierInTheSameStep() {
        Map<String, Object> vars = response("{\"inner\": \"{\\\"v\\\": 2}\"}");
        vars.put("doc", "{\"v\": 1}");
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("before", Map.of("from", "doc", "jsonpath", "$.v"));
        spec.put("doc", Map.of("jsonpath", "$.inner"));
        spec.put("after", Map.of("from", "doc", "jsonpath", "$.v"));

        extract(spec, vars);

        assertThat(vars.get("before")).isEqualTo(1);
        assertThat(vars.get("after")).isEqualTo(2);
    }

    @Test
    void everyFieldReportsABodyThatIsNotJson() {
        for (String field : List.of("a", "b")) {
            Map<String, Object> vars = response("<html>not json</html>");
            LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
            spec.put("title", Map.of("css", "html"));
            spec.put(field, Map.of("jsonpath", "$.a"));
            assertThatThrownBy(() -> extract(spec, vars))
                    .isInstanceOf(RuleProblem.class)
                    .satisfies(ex -> assertThat(((RuleProblem) ex).summary)
                            .startsWith("提取 " + field + " 失败：响应不是 JSON"));
        }
    }

    /** 编译结果跨调用复用，全局对象不复用：上一次留下的全局变量和改过的内置原型下一次看不到。 */
    @Test
    void compiledScriptRunsOnFreshGlobalsEveryTime() {
        ScriptStep step = new ScriptStep(Clock.systemUTC());
        Map<String, Object> spec = Map.of("code", """
                var dirty = typeof leaked !== 'undefined' || typeof Array.prototype.leaked !== 'undefined';
                leaked = true;
                Array.prototype.leaked = true;
                return { dirty: dirty, doubled: vars.n * 2 };
                """);
        for (int n = 1; n <= 3; n++) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("n", n);
            step.run(spec, vars, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), Map.of());
            assertThat(vars.get("dirty")).isEqualTo(false);
            assertThat(((Number) vars.get("doubled")).intValue()).isEqualTo(n * 2);
        }
    }

    /** jsonpath 的函数（append 等）会就地改解析出来的数组，这类表达式不能读共用的那一份。 */
    @Test
    void jsonpathFunctionDoesNotChangeWhatLaterFieldsRead() {
        Map<String, Object> vars = response("{\"nums\": [1, 2]}");
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("before", Map.of("jsonpath", "$.nums.length()"));
        spec.put("sum", Map.of("jsonpath", "$.nums.append(10).sum()"));
        spec.put("after", Map.of("jsonpath", "$.nums.length()"));
        spec.put("nums", Map.of("jsonpath", "$.nums"));

        extract(spec, vars);

        assertThat(((Number) vars.get("sum")).intValue()).isEqualTo(13);
        assertThat(vars.get("before")).isEqualTo(2);
        assertThat(vars.get("after")).isEqualTo(2);
        assertThat(vars.get("nums")).isEqualTo(List.of(1, 2));
    }

    /** each 和 from 变量里调用会改写数组的 jsonpath 函数时，不能污染共享 JSON 和 vars。 */
    @Test
    void jsonpathFunctionInsideEachAndFromVarDoesNotMutateSharedData() {
        Map<String, Object> vars = response("{\"rows\": [{\"nums\": [1, 2]}, {\"nums\": [3, 4]}]}");
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("rows", Map.of(
                "jsonpath", "$.rows[*]",
                "each", ordered(
                        "sum", Map.of("jsonpath", "$.nums.append(10).sum()"),
                        "count", Map.of("jsonpath", "$.nums.length()"),
                        "nums", Map.of("jsonpath", "$.nums"))));
        spec.put("after", Map.of("jsonpath", "$.rows[0].nums"));

        extract(spec, vars);

        List<?> rows = (List<?>) vars.get("rows");
        assertThat(rows).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) rows.get(0);
        Map<?, ?> second = (Map<?, ?>) rows.get(1);
        assertThat(((Number) first.get("sum")).intValue()).isEqualTo(13);
        assertThat(first.get("count")).isEqualTo(2);
        assertThat(first.get("nums")).isEqualTo(List.of(1, 2));
        assertThat(((Number) second.get("sum")).intValue()).isEqualTo(17);
        assertThat(second.get("count")).isEqualTo(2);
        assertThat(second.get("nums")).isEqualTo(List.of(3, 4));
        assertThat(vars.get("after")).isEqualTo(List.of(1, 2));

        ArrayList<Object> liveList = new ArrayList<>(List.of(1, 2));
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("nums", liveList);
        Map<String, Object> fromVars = response("{}");
        fromVars.put("payload", payload);
        LinkedHashMap<String, Object> fromSpec = new LinkedHashMap<>();
        fromSpec.put("sum", Map.of("from", "payload", "jsonpath", "$.nums.append(10).sum()"));
        fromSpec.put("after", Map.of("from", "payload", "jsonpath", "$.nums"));

        extract(fromSpec, fromVars);

        assertThat(((Number) fromVars.get("sum")).intValue()).isEqualTo(13);
        assertThat(fromVars.get("after")).isEqualTo(List.of(1, 2));
        assertThat(liveList).containsExactly(1, 2);
    }

    /** :matchText 会往文档里插入合成元素，后面的字段不能看到它们。 */
    @Test
    void matchTextDoesNotLeaveElementsForLaterFields() {
        Map<String, Object> vars = response("<html><body><p>one<br>two</p></body></html>");
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("lines", Map.of("css", "p:matchText"));
        spec.put("html", Map.of("css", "p", "html", true));
        spec.put("viaXpath", Map.of("xpath", "//p", "html", true));

        extract(spec, vars);

        assertThat(vars.get("lines")).isEqualTo("one");
        assertThat(String.valueOf(vars.get("html"))).doesNotContain("jsoup").doesNotContain("pseudo");
        assertThat(String.valueOf(vars.get("viaXpath"))).doesNotContain("jsoup").doesNotContain("pseudo");
    }

    /** each 里使用 :matchText 不能改写行元素或共享文档（含表格行 tr/td）。 */
    @Test
    void matchTextInsideEachDoesNotMutateRowElementOrSharedDocument() {
        Map<String, Object> vars = response(
                "<html><body><table><tr><td>alpha<b>beta</b></td></tr></table></body></html>");
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("rows", Map.of(
                "css", "table tr",
                "each", ordered(
                        "text", Map.of("css", "td:matchText"),
                        "firstChild", Map.of("css", "td > :first-child"),
                        "html", Map.of("css", "td", "html", true))));
        spec.put("outerFirstChild", Map.of("css", "table tr td > :first-child"));
        spec.put("outerHtml", Map.of("css", "table tr td", "html", true));

        extract(spec, vars);

        assertThat(vars.get("rows")).isEqualTo(List.of(Map.of(
                "text", "alpha",
                "firstChild", "beta",
                "html", "alpha<b>beta</b>")));
        assertThat(vars.get("outerFirstChild")).isEqualTo("beta");
        assertThat(vars.get("outerHtml")).isEqualTo("alpha<b>beta</b>");
    }

    /** 带标签的模板字面量：每次调用拿到的 strings 都是这次全局对象下的数组。 */
    @Test
    void taggedTemplateSeesThisCallsGlobals() {
        ScriptStep step = new ScriptStep(Clock.systemUTC());
        Map<String, Object> spec = Map.of("code", """
                function tag(strings) { return (strings instanceof Array) + ':' + strings[0]; }
                return { tagged: tag`x${vars.n}y` };
                """);
        for (int n = 1; n <= 3; n++) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("n", n);
            step.run(spec, vars, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), Map.of());
            assertThat(vars.get("tagged")).isEqualTo("true:x");
        }
    }

    @Test
    void syntaxErrorIsReportedTheSameWayEveryTime() {
        ScriptStep step = new ScriptStep(Clock.systemUTC());
        Map<String, Object> spec = Map.of("code", "return (;", "into", "x");
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> step.run(
                    spec, new LinkedHashMap<>(), System.nanoTime() + TimeUnit.SECONDS.toNanos(10), Map.of()))
                    .isInstanceOf(RuleProblem.class)
                    .satisfies(ex -> summaries.add(((RuleProblem) ex).summary));
        }
        assertThat(summaries.get(0)).isEqualTo(summaries.get(1));
    }

    @Test
    void sameScriptRunsConcurrentlyWithItsOwnVariables() throws Exception {
        ScriptStep step = new ScriptStep(Clock.systemUTC());
        Map<String, Object> spec = Map.of("use", List.of("lib"), "into", "out", "code", """
                var total = 0;
                for (var i = 0; i < 2000; i++) { total = add(total, vars.n); }
                return total;
                """);
        Map<String, String> libraries = Map.of("lib", "function add(a, b) { return a + b; }\n");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int n = 1; n <= 32; n++) {
                int value = n;
                Callable<Integer> call = () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("n", value);
                    step.run(spec, vars, System.nanoTime() + TimeUnit.SECONDS.toNanos(30), libraries);
                    return ((Number) vars.get("out")).intValue();
                };
                results.add(pool.submit(call));
            }
            for (int n = 1; n <= 32; n++) {
                assertThat(results.get(n - 1).get(60, TimeUnit.SECONDS)).isEqualTo(n * 2000);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void compiledScriptCacheStaysBounded() {
        ScriptStep step = new ScriptStep(Clock.systemUTC());
        for (int i = 0; i < CompiledScripts.MAX_ENTRIES + 20; i++) {
            Map<String, Object> vars = new LinkedHashMap<>();
            step.run(Map.of("code", "return " + i + ";", "into", "x"), vars,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(10), Map.of());
            assertThat(((Number) vars.get("x")).intValue()).isEqualTo(i);
        }
        assertThat(CompiledScripts.size()).isLessThanOrEqualTo(CompiledScripts.MAX_ENTRIES);
    }

    private int extract(Map<String, Object> spec, Map<String, Object> vars) {
        return Extractors.apply(spec, vars, filters, templates, System.nanoTime() + TimeUnit.MINUTES.toNanos(1));
    }

    private static Map<String, Object> response(String body) {
        Map<String, Object> vars = new LinkedHashMap<>();
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("body", body);
        vars.put("response", response);
        return vars;
    }

    private static Map<String, Object> ordered(Object... pairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
