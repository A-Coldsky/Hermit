package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.crab.store.Origin;

/** if、repeat 和 request 的 retry 在引擎里的行为。上游是本机的 HttpServer。 */
class FlowStepsEngineTest {

    private HttpServer server;
    private String base;
    private final List<String> hits = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger flaky = new AtomicInteger();
    private volatile int failFirst;
    private volatile int pages = 3;
    private String trace;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        hits.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
        int status = 200;
        String body = "ok";
        if ("/order".equals(path)) {
            body = "请再次确认";
        } else if ("/plain".equals(path)) {
            body = "已提交";
        } else if ("/list".equals(path)) {
            int page = Integer.parseInt(query.substring("page=".length()));
            String next = page < pages ? ",\"next\":\"" + (page + 1) + "\"" : "";
            body = "{\"rows\":[{\"id\":\"" + page + "a\"},{\"id\":\"" + page + "b\"}]" + next + "}";
        } else if ("/flaky".equals(path)) {
            if (flaky.incrementAndGet() <= failFirst) {
                status = 503;
                body = "busy";
            } else {
                body = "report";
            }
        } else if ("/gone".equals(path)) {
            status = 404;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void ifRunsThenWhenTheConditionHoldsAndElseOtherwise() {
        String steps = """
                - request: {method: GET, path: "%s"}
                - if:
                    contains: "请再次确认"
                    then:
                      - request: {method: POST, path: /confirm}
                      - set: {confirmed: "yes"}
                    else:
                      - set: {confirmed: "no"}
                """;
        ExecutionResult yes = run(conch(steps.formatted("/order"), "confirmed: {type: string, description: 是否确认}"));
        assertThat(yes.success()).as(message(yes)).isTrue();
        assertThat(yes.data()).isEqualTo(Map.of("confirmed", "yes"));
        assertThat(hits).containsExactly("GET /order", "POST /confirm");
        assertThat(yes.stepLogs()).extracting(StepLog::type).containsExactly("request", "if", "request", "set");
        assertThat(yes.stepLogs()).extracting(StepLog::index).containsExactly(0, 1, 1, 1);
        assertThat(trace).contains("\"branch\":\"then\"").contains("\"result\":true");

        hits.clear();
        ExecutionResult no = run(conch(steps.formatted("/plain"), "confirmed: {type: string, description: 是否确认}"));
        assertThat(no.data()).isEqualTo(Map.of("confirmed", "no"));
        assertThat(hits).containsExactly("GET /plain");
        assertThat(trace).contains("\"branch\":\"else\"");
    }

    @Test
    void ifWithoutElseSkipsAndVariableKeysReadVariables() {
        ExecutionResult result = run(conch("""
                - set: {state: "done", empty: ""}
                - if:
                    equals: {state: "done"}
                    not_present: [empty, never_set]
                    present: state
                    then:
                      - set: {a: "1"}
                - if:
                    not_equals: {state: "done"}
                    then:
                      - set: {b: "1"}
                - if:
                    present: empty
                    then:
                      - set: {b: "2"}
                - set: {b: "{{b | default('skipped')}}"}
                """, """
                a: {type: string, description: a}
                      b: {type: string, description: b}"""));
        assertThat(result.success()).as(message(result)).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("a", "1", "b", "skipped"));
        assertThat(trace).contains("\"branch\":\"none\"");
    }

    @Test
    void aFailureInsideABranchIsReportedOnTheOuterStep() {
        ExecutionResult result = run(conch("""
                - request: {method: GET, path: /order}
                - if:
                    status: 200
                    then:
                      - request: {method: GET, path: /gone}
                """, "value: {type: string, description: v, value: ok}"));
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(result.error().failedStepIndex()).isEqualTo(1);
        assertThat(result.error().failedStepType()).isEqualTo("request");
    }

    @Test
    void ifOnStatusRightAfterARequestSeesANonSuccessStatus() {
        ExecutionResult result = run(conch("""
                - request: {method: GET, path: /gone}
                - if:
                    status: 404
                    then:
                      - set: {found: "no"}
                    else:
                      - set: {found: "yes"}
                """, "found: {type: string, description: f}"));
        assertThat(result.success()).as(message(result)).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("found", "no"));
    }

    @Test
    void repeatCollectsEveryRoundAndStopsOnUntil() {
        ExecutionResult result = run(conch(LIST.formatted(20), LIST_OUTPUT));
        assertThat(result.success()).as(message(result)).isTrue();
        assertThat(hits).containsExactly("GET /list?page=1", "GET /list?page=2", "GET /list?page=3");
        assertThat(result.data()).isEqualTo(Map.of(
                "all", List.of(
                        Map.of("id", "1a"), Map.of("id", "1b"), Map.of("id", "2a"),
                        Map.of("id", "2b"), Map.of("id", "3a"), Map.of("id", "3b")),
                "rounds", 3,
                "truncated", false));
        assertThat(trace).contains("\"stopped\":\"until\"").contains("\"rounds\":3").contains("\"round\":2");
    }

    @Test
    void repeatThatHitsMaxReturnsWhatItHasAndSaysTruncated() {
        pages = 50;
        ExecutionResult result = run(conch(LIST.formatted(2), LIST_OUTPUT));
        assertThat(result.success()).as(message(result)).isTrue();
        assertThat(hits).hasSize(2);
        assertThat(((Map<?, ?>) result.data()).get("truncated")).isEqualTo(true);
        assertThat((List<?>) ((Map<?, ?>) result.data()).get("all")).hasSize(4);
        assertThat(trace).contains("\"stopped\":\"max\"").contains("\"incomplete\":true");
    }

    @Test
    void repeatWithoutUntilRunsMaxRoundsAndIsNotTruncated() {
        ExecutionResult result = run(conch("""
                - repeat:
                    max: 3
                    steps:
                      - request: {method: GET, path: /list, query: {page: "{{loop.index}}"}}
                - set: {truncated: "{{loop.truncated}}"}
                """, "truncated: {type: string, description: t}"));
        assertThat(result.success()).as(message(result)).isTrue();
        assertThat(hits).hasSize(3);
        assertThat(result.data()).isEqualTo(Map.of("truncated", false));
    }

    @Test
    void collectingSomethingThatIsNotAListFails() {
        ExecutionResult result = run(conch("""
                - repeat:
                    max: 2
                    steps:
                      - set: {one: "x"}
                    collect: {all: one}
                """, "value: {type: string, description: v, value: ok}"));
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(result.error().message()).contains("不是列表");
        assertThat(result.error().failedStepType()).isEqualTo("repeat");
    }

    @Test
    void retryTriesAgainOnAListedStatusAndRecordsEachAttempt() {
        failFirst = 2;
        ExecutionResult result = run(conch("""
                - request:
                    method: GET
                    path: /flaky
                    retry: {times: 2, delay_ms: 10, on: [503]}
                - extract: {body: {regex: "(.+)"}}
                """, "body: {type: string, description: b}"));
        assertThat(result.success()).as(message(result)).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("body", "report"));
        assertThat(hits).hasSize(3);
        assertThat(trace).contains("\"retries\":[{").contains("\"reason\":\"503\"");
    }

    @Test
    void retryGivesUpAfterTimesAndReportsTheLastFailure() {
        failFirst = 10;
        ExecutionResult result = run(conch("""
                - request: {method: GET, path: /flaky, retry: {times: 1}}
                """, "value: {type: string, description: v, value: ok}"));
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(hits).hasSize(2);
    }

    @Test
    void retryLeavesOtherStatusesAlone() {
        ExecutionResult result = run(conch("""
                - request: {method: GET, path: /gone, retry: {times: 3}}
                """, "value: {type: string, description: v, value: ok}"));
        assertThat(result.success()).isFalse();
        assertThat(hits).hasSize(1);
    }

    @Test
    void retryOnAConnectionFailureTriesAgain() {
        server.stop(0);
        long started = System.nanoTime();
        ExecutionResult result = run(conch("""
                - request: {method: GET, path: /flaky, retry: {times: 2, delay_ms: 150, on: [connect]}}
                """, "value: {type: string, description: v, value: ok}"));
        assertThat(result.success()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(trace).contains("\"reason\":\"connect\"");
    }

    private static final String LIST = """
            - repeat:
                max: %d
                steps:
                  - request: {method: GET, path: /list, query: {page: "{{loop.index}}"}}
                  - extract:
                      rows: {jsonpath: "$.rows[*]", each: {id: {jsonpath: "$.id"}}}
                      next: {jsonpath: "$.next", optional: true}
                collect: {all: rows}
                until: {not_present: next}
            - set: {rounds: "{{loop.rounds}}", truncated: "{{loop.truncated}}"}
            """;

    private static final String LIST_OUTPUT = """
            all:
                    type: array
                    description: 全部
                    items:
                      id: {type: string, description: id}
                  rounds: {type: string, description: r}
                  truncated: {type: string, description: t}""";

    private static String conch(String steps, String output) {
        return """
                conch: flow_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.9.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                actions:
                  run:
                    title: 能力
                    description: 说明
                    output:
                      %s
                    examples:
                      - input: {}
                        output: {}
                    notes: 注意
                    steps:
                %s
                """.formatted(output.strip(), steps.stripIndent().indent(6).stripTrailing());
    }

    private ExecutionResult run(String yaml) {
        ConchDoc doc = ConchParser.parse(yaml);
        ValidationResult checked = ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT));
        assertThat(checked.errors()).isEmpty();
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(20), Duration.ofMinutes(10), 3);
        try (SessionManager sessions = new SessionManager(new NopSessionPersistence(), settings, Clock.systemUTC())) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(doc, Origin.LOCAL);
            RuleEngine engine = new RuleEngine(registry, sessions, settings);
            engine.bindDevTraceSink(new DevTraceSink() {
                @Override
                public DevTraceDraft open(ExecutionRequest request) {
                    return new DevTraceDraft(Instant.parse("2026-11-18T00:00:00Z"));
                }

                @Override
                public void save(ExecutionRequest request, Instant expiresAt, String body) {
                    trace = body;
                }
            });
            return engine.execute(EngineTestSupport.request(
                    Origin.LOCAL, "flow_conch", "run", "alice", Map.of(),
                    EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, base)));
        }
    }

    private static String message(ExecutionResult result) {
        return result.error() == null ? "" : result.error().message() + " " + result.error().summary();
    }
}
