package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.store.Origin;

/** if、repeat 和 request 的 retry 在引擎里的行为。上游是本机的 HttpServer。 */
class FlowStepsEngineTest {

    private HttpServer server;
    private ExecutorService serverPool;
    private String base;
    private final List<String> hits = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger flaky = new AtomicInteger();
    private final AtomicInteger slowOnce = new AtomicInteger();
    private volatile int failFirst;
    private volatile int pages = 3;
    private String trace;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newCachedThreadPool();
        server.setExecutor(serverPool);
        server.createContext("/", this::serve);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        serverPool.shutdownNow();
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
        } else if ("/unauth".equals(path)) {
            status = 401;
            body = "{}";
        } else if ("/slow-once".equals(path)) {
            if (slowOnce.incrementAndGet() == 1) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            body = "recovered";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // 调用方按步骤超时断开连接。
        } finally {
            exchange.close();
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
        assertThat(yes.stepLogs()).extracting(StepLog::type).containsExactly("request", "if");
        assertThat(yes.stepLogs()).extracting(StepLog::index).containsExactly(0, 1);
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
    void callLogKeepsOneEntryPerOuterStep() {
        ExecutionResult loop = run(conch(LIST.formatted(20), LIST_OUTPUT));
        assertThat(loop.stepLogs()).extracting(StepLog::index).containsExactly(0, 1);
        assertThat(loop.stepLogs()).extracting(StepLog::type).containsExactly("repeat", "set");

        ExecutionResult failed = run(conch("""
                - request: {method: GET, path: /plain}
                - if:
                    contains: "已提交"
                    then:
                      - set: {seen: "yes"}
                      - request: {method: GET, path: /gone}
                """, "seen: {type: string, description: s}"));
        assertThat(failed.success()).isFalse();
        assertThat(failed.stepLogs()).extracting(StepLog::index).containsExactly(0, 1);
        assertThat(failed.stepLogs().get(1).type()).isEqualTo("request");
        assertThat(failed.stepLogs().get(1).status()).isEqualTo(404);
    }

    @Test
    void aRoundThatSkipsTheListDoesNotCollectThePreviousRoundAgain() {
        ExecutionResult result = run(conch("""
                - repeat:
                    max: 5
                    steps:
                      - if:
                          equals: {loop.index: "2"}
                          present: rows
                          then:
                            - set: {kept: "yes"}
                      - request: {method: GET, path: /list, query: {page: "{{loop.index}}"}}
                      - extract:
                          next: {jsonpath: "$.next", optional: true}
                      - if:
                          not_equals: {loop.index: "2"}
                          then:
                            - extract:
                                rows: {jsonpath: "$.rows[*]", each: {id: {jsonpath: "$.id"}}}
                    collect: {all: rows}
                    until: {not_present: next}
                - set: {rounds: "{{loop.rounds}}", truncated: "{{loop.truncated}}"}
                """, LIST_OUTPUT.replaceAll(
                        "(?m)^(\\s*)rounds: (.*)$", "$1rounds: $2\n$1kept: {type: string, description: k}")));
        assertThat(result.success()).as(message(result)).isTrue();
        assertThat(((Map<?, ?>) result.data()).get("kept")).as("下一轮读得到上一轮的列表").isEqualTo("yes");
        assertThat(((Map<?, ?>) result.data()).get("all")).isEqualTo(List.of(
                Map.of("id", "1a"), Map.of("id", "1b"), Map.of("id", "3a"), Map.of("id", "3b")));
    }

    @Test
    void loginVariablesOnlyOneBranchNeedsDoNotFailTheCall() {
        String yaml = conch("""
                - if:
                    present: login.vip
                    then:
                      - set: {level: "{{login.vip}}", kind: "is_vip"}
                    else:
                      - set: {kind: "not_vip"}
                """, "kind: {type: string, description: k}").replace("actions:", """
                auth:
                  steps:
                    - request: {method: GET, path: /plain}
                    - if:
                        contains: "请再次确认"
                        then:
                          - set: {vip: "gold"}
                        else:
                          - set: {plain: "normal"}
                actions:""");
        ExecutionResult result = run(yaml);
        assertThat(result.success()).as(message(result)).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("kind", "not_vip"));
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

    @Test
    void repeatUntilStatusAndOuterExpectationsCoverInnerRequests() {
        ExecutionResult untilMatch = run(conch("""
                - repeat:
                    max: 3
                    steps:
                      - request: {method: GET, path: /gone}
                    until: {status: 404}
                - set: {rounds: "{{loop.rounds}}", truncated: "{{loop.truncated}}"}
                """, """
                rounds: {type: string, description: r}
                      truncated: {type: string, description: t}"""));
        assertThat(untilMatch.success()).as(message(untilMatch)).isTrue();
        assertThat(untilMatch.data()).isEqualTo(Map.of("rounds", 1, "truncated", false));
        assertThat(hits).containsExactly("GET /gone");

        hits.clear();
        ExecutionResult untilMiss = run(conch("""
                - repeat:
                    max: 3
                    steps:
                      - request: {method: GET, path: /gone}
                    until: {status: 200}
                """, "value: {type: string, description: v, value: ok}"));
        assertThat(untilMiss.success()).isFalse();
        assertThat(untilMiss.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(hits).containsExactly("GET /gone");

        hits.clear();
        ExecutionResult outerAssert = run(conch("""
                - set: {flag: "yes"}
                - if:
                    present: flag
                    then:
                      - request: {method: GET, path: /gone}
                - assert: {status: 404}
                """, "flag: {type: string, description: f}"));
        assertThat(outerAssert.success()).as(message(outerAssert)).isTrue();
        assertThat(hits).containsExactly("GET /gone");

        hits.clear();
        ExecutionResult outerIf = run(conch("""
                - set: {flag: "yes"}
                - if:
                    present: flag
                    then:
                      - request: {method: GET, path: /gone}
                - if:
                    status: 404
                    then:
                      - set: {found: "no"}
                    else:
                      - set: {found: "yes"}
                """, "found: {type: string, description: f}"));
        assertThat(outerIf.success()).as(message(outerIf)).isTrue();
        assertThat(outerIf.data()).isEqualTo(Map.of("found", "no"));
        assertThat(hits).containsExactly("GET /gone");

        // 分支里紧跟着另一次 request 时，外层 assert 只能覆盖最后那一次，不能覆盖前一次。
        hits.clear();
        ExecutionResult shadowed = run(conch("""
                - set: {flag: "yes"}
                - if:
                    present: flag
                    then:
                      - request: {method: GET, path: /gone}
                      - request: {method: GET, path: /plain}
                - assert: {status: 404}
                """, "flag: {type: string, description: f}"));
        assertThat(shadowed.success()).isFalse();
        assertThat(shadowed.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(hits).containsExactly("GET /gone");
    }

    @Test
    void postRepeatStatusAssertDoesNotMaskNonFinalRepeatRoundFailure() {
        // 非最后一轮、until 没写 status 时，循环外的 assert: {status: 404} 不得吞掉本轮 404。
        ExecutionResult nonFinal = run(conch("""
                - set: {done: "no"}
                - repeat:
                    max: 3
                    steps:
                      - request: {method: GET, path: /gone}
                    until: {equals: {done: "yes"}}
                - assert: {status: 404}
                """, "done: {type: string, description: d}"));
        assertThat(nonFinal.success()).isFalse();
        assertThat(nonFinal.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(hits).containsExactly("GET /gone");

        // 最后一轮结束后必定进入循环外步骤，循环外的 assert: {status: 404} 仍然生效。
        hits.clear();
        ExecutionResult finalRound = run(conch("""
                - set: {done: "no"}
                - repeat:
                    max: 1
                    steps:
                      - request: {method: GET, path: /gone}
                    until: {equals: {done: "yes"}}
                - assert: {status: 404}
                """, "done: {type: string, description: d}"));
        assertThat(finalRound.success()).as(message(finalRound)).isTrue();
        assertThat(hits).containsExactly("GET /gone");
    }

    @Test
    void sessionLostInsideIfOrRepeatLogsTheFailingInnerRequest() {
        ExecutionResult inIf = run(conch("""
                - set: {flag: "yes"}
                - if:
                    present: flag
                    then:
                      - request: {method: GET, path: /unauth}
                """, "flag: {type: string, description: f}"));
        assertThat(inIf.success()).isFalse();
        assertThat(inIf.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
        assertThat(inIf.error().failedStepIndex()).isEqualTo(1);
        assertThat(inIf.error().failedStepType()).isEqualTo("request");
        assertThat(inIf.stepLogs()).hasSize(2);
        assertThat(inIf.stepLogs().get(1).index()).isEqualTo(1);
        assertThat(inIf.stepLogs().get(1).type()).isEqualTo("request");
        assertThat(inIf.stepLogs().get(1).status()).isEqualTo(401);
        assertThat(inIf.stepLogs().get(1).path()).isEqualTo("/unauth");

        ExecutionResult inRepeat = run(conch("""
                - set: {flag: "yes"}
                - repeat:
                    max: 2
                    steps:
                      - request: {method: GET, path: /unauth}
                """, "flag: {type: string, description: f}"));
        assertThat(inRepeat.success()).isFalse();
        assertThat(inRepeat.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
        assertThat(inRepeat.error().failedStepIndex()).isEqualTo(1);
        assertThat(inRepeat.error().failedStepType()).isEqualTo("request");
        assertThat(inRepeat.stepLogs()).hasSize(2);
        assertThat(inRepeat.stepLogs().get(1).index()).isEqualTo(1);
        assertThat(inRepeat.stepLogs().get(1).type()).isEqualTo("request");
        assertThat(inRepeat.stepLogs().get(1).status()).isEqualTo(401);
        assertThat(inRepeat.stepLogs().get(1).path()).isEqualTo("/unauth");
    }

    @Test
    void failedIfAndRepeatStampPassedFalseAndErrorInDevTrace() {
        ExecutionResult badRepeat = run(conch("""
                - repeat:
                    max: 2
                    steps:
                      - set: {one: "x"}
                    collect: {all: one}
                """, "value: {type: string, description: v, value: ok}"));
        assertThat(badRepeat.success()).isFalse();
        JsonNode repeatStep = findTraceStep(trace, "repeat");
        assertThat(repeatStep).isNotNull();
        assertThat(repeatStep.path("passed").asBoolean()).isFalse();
        assertThat(repeatStep.path("error").asText()).contains("不是列表");

        ExecutionResult badIf = run(conch("""
                - if:
                    status: 200
                    then:
                      - set: {seen: "yes"}
                """, "seen: {type: string, description: s}"));
        assertThat(badIf.success()).isFalse();
        assertThat(badIf.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        JsonNode ifStep = findTraceStep(trace, "if");
        assertThat(ifStep).isNotNull();
        assertThat(ifStep.path("passed").asBoolean()).isFalse();
        assertThat(ifStep.path("error").asText()).contains("还没有 request");
    }

    @Test
    void retryOnStepTimeoutTriesAgainAgainstSlowUpstream() {
        ExecutionResult result = run(conch("""
                - request:
                    method: GET
                    path: /slow-once
                    timeout_ms: 150
                    retry: {times: 1, delay_ms: 10, on: [timeout]}
                - extract: {body: {regex: "(.+)"}}
                """, "body: {type: string, description: b}"));
        assertThat(result.success()).as(message(result)).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("body", "recovered"));
        assertThat(hits).containsExactly("GET /slow-once", "GET /slow-once");
        assertThat(trace).contains("\"reason\":\"timeout\"");
    }

    @Test
    void retryDistinguishesConnectTimeoutFromReadTimeoutAndCapabilityDeadline() {
        FilterRegistry filters = FilterRegistry.defaults();
        StepRunner runner = new StepRunner(new TemplateEngine(filters), filters);
        URI target = URI.create("http://127.0.0.1:8080");

        // 1) 未写 timeout_ms 时，ConnectTimeoutException 按 connect 重试，不按 timeout 重试。
        AtomicInteger connectCalls = new AtomicInteger();
        CloseableHttpClient connectOnce = stubClient(() -> {
            if (connectCalls.incrementAndGet() == 1) {
                throw new ConnectTimeoutException("connect timed out");
            }
            return okResponse("ok");
        });
        Outcome retriedConnect = runner.run(
                requestWithRetry(1, List.of("connect")),
                new LinkedHashMap<>(), null, connectOnce, () -> { }, new BasicCookieStore(),
                target, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), true, Map.of());
        assertThat(retriedConnect.status()).isEqualTo(200);
        assertThat(connectCalls.get()).isEqualTo(2);
        assertThat(retriedConnect.capture().get("retries")).isEqualTo(List.of(Map.of("attempt", 1, "reason", "connect")));

        AtomicInteger connectIgnoredCalls = new AtomicInteger();
        CloseableHttpClient connectAlways = stubClient(() -> {
            connectIgnoredCalls.incrementAndGet();
            throw new ConnectTimeoutException("connect timed out");
        });
        assertThatThrownBy(() -> runner.run(
                requestWithRetry(1, List.of("timeout")),
                new LinkedHashMap<>(), null, connectAlways, () -> { }, new BasicCookieStore(),
                target, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), true, Map.of()))
                .isInstanceOf(DeadlineExceeded.class);
        assertThat(connectIgnoredCalls.get()).isEqualTo(1);

        // 2) 未写 timeout_ms 时，SocketTimeoutException（读超时）按 timeout 重试，不按 connect 重试。
        AtomicInteger readCalls = new AtomicInteger();
        CloseableHttpClient readOnce = stubClient(() -> {
            if (readCalls.incrementAndGet() == 1) {
                throw new SocketTimeoutException("Read timed out");
            }
            return okResponse("ok");
        });
        Outcome retriedRead = runner.run(
                requestWithRetry(1, List.of("timeout")),
                new LinkedHashMap<>(), null, readOnce, () -> { }, new BasicCookieStore(),
                target, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), true, Map.of());
        assertThat(retriedRead.status()).isEqualTo(200);
        assertThat(readCalls.get()).isEqualTo(2);
        assertThat(retriedRead.capture().get("retries")).isEqualTo(List.of(Map.of("attempt", 1, "reason", "timeout")));

        AtomicInteger readIgnoredCalls = new AtomicInteger();
        CloseableHttpClient readAlways = stubClient(() -> {
            readIgnoredCalls.incrementAndGet();
            throw new SocketTimeoutException("Read timed out");
        });
        assertThatThrownBy(() -> runner.run(
                requestWithRetry(1, List.of("connect")),
                new LinkedHashMap<>(), null, readAlways, () -> { }, new BasicCookieStore(),
                target, System.nanoTime() + TimeUnit.SECONDS.toNanos(10), true, Map.of()))
                .isInstanceOf(DeadlineExceeded.class);
        assertThat(readIgnoredCalls.get()).isEqualTo(1);

        // 3) 能力总 deadline 已到时，即使 on 包含 connect 和 timeout 也不得再重试。
        AtomicInteger expiredCalls = new AtomicInteger();
        CloseableHttpClient pastDeadline = stubClient(() -> {
            expiredCalls.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            throw new SocketTimeoutException("Read timed out");
        });
        assertThatThrownBy(() -> runner.run(
                requestWithRetry(2, List.of("connect", "timeout")),
                new LinkedHashMap<>(), null, pastDeadline, () -> { }, new BasicCookieStore(),
                target, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40), true, Map.of()))
                .isInstanceOf(DeadlineExceeded.class);
        assertThat(expiredCalls.get()).isEqualTo(1);
    }

    private static Step requestWithRetry(int times, List<Object> on) {
        return new Step("request", Map.of(
                "method", "GET",
                "path", "/probe",
                "retry", Map.of("times", times, "delay_ms", 0, "on", on)));
    }

    private static JsonNode findTraceStep(String json, String type) {
        JsonNode steps = ApiJson.tree(json).path("steps");
        for (int i = steps.size() - 1; i >= 0; i--) {
            JsonNode item = steps.get(i);
            if (type.equals(item.path("type").asText())) {
                return item;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface StubExchange {
        CloseableHttpResponse execute() throws IOException;
    }

    private static CloseableHttpClient stubClient(StubExchange exchange) {
        return new CloseableHttpClient() {
            @Override
            protected CloseableHttpResponse doExecute(
                    HttpHost target, ClassicHttpRequest request, HttpContext context) throws IOException {
                return exchange.execute();
            }

            @Override
            public void close() {
            }

            @Override
            public void close(CloseMode closeMode) {
            }
        };
    }

    private static CloseableHttpResponse okResponse(String body) {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        response.setEntity(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), ContentType.TEXT_PLAIN));
        return CloseableHttpResponse.adapt(response);
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
