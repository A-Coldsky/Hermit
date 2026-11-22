package dev.hermit.it.quiz;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.crab.engine.AccountContext;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.store.Origin;

/**
 * 写规则测验：每道题是一段给模型的题面（{@code quiz/<题号>.md}）和若干组试跑。
 * 判分只看两件事：规则能不能通过校验；在模拟站点上试跑，输出和发出的请求对不对。
 */
final class RuleQuiz {

    /** 题面里写给模型的站点地址。判分时换成模拟站点的实际地址。 */
    static final String BASE = "http://127.0.0.1:18080";
    static final String CONCH = "quiz";
    static final String ACTION = "run";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 一组试跑。{@code hits} 不为 null 时，发出的请求必须正好是这些（按顺序）；
     * {@code maxHits} 大于 0 时请求数不能超过它。
     */
    record Case(String input, String expected, List<String> hits, int maxHits) {
        static Case of(String input, String expected) {
            return new Case(input, expected, null, 0);
        }

        static Case of(String input, String expected, List<String> hits) {
            return new Case(input, expected, hits, 0);
        }
    }

    record Task(String id, String covers, List<Case> cases) {
        String prompt() {
            return resource("quiz/" + id + ".md");
        }

        String reference() {
            return resource("quiz/answers/" + id + ".yaml");
        }
    }

    /** 判分结果。{@code feedback} 是可以原样交还给模型的说明，通过时为空。 */
    record Grade(boolean valid, boolean correct, List<String> feedback) {
    }

    static final List<Task> TASKS = List.of(
            new Task("q1_json", "request、extract（jsonpath）", List.of(
                    Case.of("{}", "{\"state\":\"ok\"}", List.of("GET /api/ping")))),
            new Task("q2_table", "extract（css、each）", List.of(
                    Case.of("{}", "{\"items\":[{\"id\":\"N1\",\"title\":\"放假通知\"},"
                            + "{\"id\":\"N2\",\"title\":\"系统升级\"}]}", List.of("GET /notices")))),
            new Task("q3_confirm", "if（contains、没有 else）", List.of(
                    Case.of("{\"id\":\"A1\"}", "{\"order_no\":\"SO-A1-C\"}",
                            List.of("POST /order/submit", "POST /order/confirm")),
                    Case.of("{\"id\":\"B2\"}", "{\"order_no\":\"SO-B2\"}", List.of("POST /order/submit")))),
            new Task("q4_branch", "if（equals、else）", List.of(
                    Case.of("{\"user\":\"ann\"}", "{\"quota\":500}", List.of("GET /profile", "GET /vip/quota")),
                    Case.of("{\"user\":\"bob\"}", "{\"quota\":100}", List.of("GET /profile", "GET /quota")))),
            new Task("q5_pages", "repeat（loop.index、collect、until）", List.of(
                    Case.of("{}", "{\"items\":[{\"id\":\"L11\"},{\"id\":\"L12\"},{\"id\":\"L21\"},{\"id\":\"L22\"},"
                            + "{\"id\":\"L31\"},{\"id\":\"L32\"}]}", List.of("GET /list", "GET /list", "GET /list")))),
            new Task("q6_cursor", "repeat（每一轮用上一轮取到的变量）", List.of(
                    Case.of("{}", "{\"ids\":[{\"id\":\"F-start-1\"},{\"id\":\"F-start-2\"},{\"id\":\"F-c2-1\"},"
                            + "{\"id\":\"F-c2-2\"},{\"id\":\"F-c3-1\"},{\"id\":\"F-c3-2\"}]}",
                            List.of("GET /feed", "GET /feed", "GET /feed")))),
            new Task("q7_truncated", "repeat（max、loop.truncated）", List.of(
                    new Case("{}", "{\"count\":10,\"truncated\":true}", null, 5))),
            new Task("q8_retry", "request 的 retry", List.of(
                    Case.of("{}", "{\"total\":42}", List.of("GET /report", "GET /report", "GET /report")))),
            new Task("q9_login", "登录步骤里的 if", List.of(
                    Case.of("{}", "{\"name\":\"张三\"}", List.of("POST /login", "POST /agree", "GET /me")))));

    private RuleQuiz() {
    }

    static Task task(String id) {
        return TASKS.stream().filter(task -> task.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有这道题：" + id));
    }

    /** 校验一份答案，再把每组试跑在新的模拟站点上跑一遍。 */
    static Grade grade(Task task, String yaml) {
        List<String> feedback = new ArrayList<>();
        ConchDoc doc;
        try {
            doc = ConchParser.parse(yaml.replace(BASE, "http://127.0.0.1:1"));
        } catch (RuntimeException ex) {
            return new Grade(false, false, List.of("解析失败：" + ex.getMessage()));
        }
        ValidationResult checked = ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT));
        for (ValidationIssue issue : checked.errors()) {
            feedback.add("校验错误 " + issue.code() + " " + issue.path() + "：" + issue.message());
        }
        if (!feedback.isEmpty()) {
            return new Grade(false, false, feedback);
        }
        if (!CONCH.equals(doc.conch()) || !doc.actions().containsKey(ACTION)) {
            return new Grade(true, false, List.of("包名必须是 " + CONCH + "，能力名必须是 " + ACTION));
        }
        if (!yaml.contains(BASE)) {
            return new Grade(true, false, List.of("base_url 必须是 " + BASE));
        }
        for (int i = 0; i < task.cases().size(); i++) {
            trial(task.cases().get(i), i + 1, yaml, feedback);
        }
        return new Grade(true, feedback.isEmpty(), feedback);
    }

    private static void trial(Case one, int number, String yaml, List<String> feedback) {
        String label = "试跑 " + number + "（入参 " + one.input() + "）";
        try (QuizSite site = new QuizSite(); SessionManager sessions = new SessionManager()) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(yaml.replace(BASE, site.base())), Origin.LOCAL);
            RuleEngine engine = new RuleEngine(registry, sessions);
            Map<String, Object> input = JSON.convertValue(JSON.readTree(one.input()), JSON.getTypeFactory()
                    .constructMapType(java.util.LinkedHashMap.class, String.class, Object.class));
            ExecutionResult result = engine.execute(new ExecutionRequest(
                    "quiz", Origin.LOCAL, CONCH, ACTION, input, "quiz", new Account()));
            if (!result.success()) {
                feedback.add(label + "失败：" + result.error().code() + " " + result.error().message()
                        + "（第 " + result.error().failedStepIndex() + " 步 " + result.error().failedStepType() + "）");
                return;
            }
            JsonNode actual = JSON.valueToTree(result.data());
            JsonNode expected = JSON.readTree(one.expected());
            if (!same(expected, actual)) {
                feedback.add(label + "输出不对。期望 " + expected + "，实际 " + actual);
            }
            List<String> hits = site.hits();
            if (one.hits() != null && !one.hits().equals(hits)) {
                feedback.add(label + "发出的请求不对。期望 " + one.hits() + "，实际 " + hits);
            }
            if (one.maxHits() > 0 && hits.size() > one.maxHits()) {
                feedback.add(label + "请求太多：最多 " + one.maxHits() + " 次，实际 " + hits.size() + " 次");
            }
        } catch (IOException | RuntimeException ex) {
            feedback.add(label + "没能运行：" + ex);
        }
    }

    /** 输出里的数字、布尔和它们的文本写法算相同；多出来的字段不算错。 */
    private static boolean same(JsonNode expected, JsonNode actual) {
        if (actual == null) {
            return false;
        }
        if (expected.isObject()) {
            if (!actual.isObject()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> field : expected.properties()) {
                if (!same(field.getValue(), actual.get(field.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray()) {
            if (!actual.isArray() || actual.size() != expected.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!same(expected.get(i), actual.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return expected.asText().equals(actual.asText());
    }

    private static String resource(String name) {
        try (InputStream in = RuleQuiz.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Account() implements AccountContext {
        @Override
        public Map<String, String> credentials() {
            return Map.of("username", "zhangsan", "password", "S3cret!pwd");
        }

        @Override
        public Map<String, String> config() {
            return Map.of();
        }
    }
}
