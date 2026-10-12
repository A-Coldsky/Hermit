package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.LegacyOaFixture;
import dev.hermit.crab.testing.SecretChecks;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LegacyOaEngineTest {

    @Test
    void listsTodosAfterLoginAndKeepsSecretsOutOfTheResult() throws Exception {
        try (Running running = Running.start();
                EngineTestSupport.LogCapture logs = new EngineTestSupport.LogCapture();
                EngineTestSupport.StreamCapture streams = new EngineTestSupport.StreamCapture()) {
            ExecutionResult result = running.list("alice", EngineTestSupport.PASSWORD, null);
            absent(result, logs, streams, EngineTestSupport.PASSWORD);
            assertThat(result.success()).isTrue();
            assertThat(result.shellVersion()).isEqualTo("1.0.0");
            assertThat(result.traceId()).isEqualTo("t-1");
            assertThat(result.error()).isNull();
            Map<String, Object> item = item(result);
            assertThat(item).containsEntry("id", "A1024")
                    .containsEntry("title", "张三的请假申请")
                    .containsEntry("applicant", "张三")
                    .containsEntry("date", "2026-10-12");
            assertThat(result.stepLogs()).hasSize(2);
            StepLog request = result.stepLogs().get(0);
            assertThat(request.index()).isZero();
            assertThat(request.type()).isEqualTo("request");
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/todo/list.aspx");
            assertThat(request.path()).doesNotContain("?");
            assertThat(request.status()).isEqualTo(200);
            assertThat(result.stepLogs().get(1).type()).isEqualTo("extract");
            assertThat(result.stepLogs().get(1).extractCount()).isEqualTo(1);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/todo/list.aspx")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/home.aspx")).isZero();
            assertThat(query(running, "/todo/list.aspx")).isEqualTo("p=1");
            absent(result, logs, streams, sessionId(running, "/todo/list.aspx"));
        }
    }

    @Test
    void coercesPageFromTheQueryString() throws Exception {
        try (Running running = Running.start()) {
            ExecutionResult result = running.list("alice", EngineTestSupport.PASSWORD, Map.of("page", "2"));
            assertThat(result.success()).isTrue();
            assertThat(query(running, "/todo/list.aspx")).isEqualTo("p=2");
        }
    }

    @Test
    void rejectsUncoercibleInputBeforeCallingUpstream() throws Exception {
        try (Running running = Running.start()) {
            ExecutionResult result = running.list("alice", EngineTestSupport.PASSWORD, Map.of("page", "no"));
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(result.error().message()).contains("page");
            SecretChecks.absent(result.error().message(), "no");
            assertThat(running.fixture.hits()).isEmpty();
        }
    }

    @Test
    void wrongPasswordFailsAuthWithoutPausing() throws Exception {
        try (Running running = Running.start();
                EngineTestSupport.LogCapture logs = new EngineTestSupport.LogCapture();
                EngineTestSupport.StreamCapture streams = new EngineTestSupport.StreamCapture()) {
            ExecutionResult result = running.list("alice", EngineTestSupport.WRONG_PASSWORD, Map.of());
            absent(result, logs, streams, EngineTestSupport.WRONG_PASSWORD);
            absent(result, logs, streams, EngineTestSupport.PASSWORD);
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(result.error().failedStepType()).isEqualTo("assert");
            assertThat(result.error().failedStepIndex()).isEqualTo(3);
            assertThat(result.error().message()).doesNotContain("暂停");
            assertThat(result.stepLogs()).hasSize(4);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
        }
    }

    @Test
    void pausesAfterThreeLoginFailuresUntilReset() throws Exception {
        try (Running running = Running.start()) {
            for (int i = 0; i < 3; i++) {
                ExecutionResult failed = running.list("alice", EngineTestSupport.WRONG_PASSWORD, Map.of());
                assertThat(failed.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
                assertThat(failed.error().message()).doesNotContain("暂停");
            }
            ExecutionResult paused = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertThat(paused.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(paused.error().message()).contains("暂停");
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(3);
            SecretChecks.absent(EngineTestSupport.flatten(paused), EngineTestSupport.PASSWORD);

            running.sessions.resetLoginPause(new SessionKey(Origin.OFFICIAL, "legacy_oa", "alice"));
            ExecutionResult again = running.list("alice", EngineTestSupport.WRONG_PASSWORD, Map.of());
            assertThat(again.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(again.error().message()).doesNotContain("暂停");
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(4);
        }
    }

    @Test
    void retriesIdempotentActionOnceAfterSessionLoss() throws Exception {
        try (Running running = Running.start()) {
            running.fixture.expireNextAuthenticated();
            ExecutionResult result = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertThat(result.success()).isTrue();
            assertThat(item(result)).containsEntry("id", "A1024");
            assertThat(result.stepLogs()).hasSize(2);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(2);
            assertThat(running.fixture.count("GET", "/todo/list.aspx")).isEqualTo(2);
        }
    }

    @Test
    void nonIdempotentSessionLossDoesNotRetryUntilTheNextCall() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ShellParser.parse(
                    EngineTestSupport.legacyYaml().replace("idempotent: true", "idempotent: false")));
            running.fixture.expireNextAuthenticated();
            ExecutionResult expired = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertThat(expired.success()).isFalse();
            assertThat(expired.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(expired.error().failedStepType()).isEqualTo("request");
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/todo/list.aspx")).isEqualTo(1);

            ExecutionResult again = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertThat(again.success()).isTrue();
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(2);
        }
    }

    @Test
    void pageChangeIsRuleMismatch() throws Exception {
        try (Running running = Running.start()) {
            running.fixture.breakTable(true);
            ExecutionResult result = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().summary()).contains("选择器匹配 0 条");
            assertThat(result.error().failedStepType()).isEqualTo("extract");
            SecretChecks.absent(result.error().summary(), EngineTestSupport.PASSWORD);
        }
    }

    @Test
    void upstream500IsNotARuleMismatch() throws Exception {
        try (Running running = Running.start()) {
            running.fixture.todoStatus(500);
            ExecutionResult result = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(result.error().summary()).contains("500");
            assertThat(result.error().failedStepType()).isEqualTo("request");
        }
    }

    @Test
    void slowUpstreamCountsAsCapabilityTimeout() throws Exception {
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(2), Duration.ofMinutes(10), 3);
        try (Running running = Running.start(settings)) {
            running.fixture.todoDelay(Duration.ofSeconds(8));
            ExecutionResult result = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertThat(result.error().code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(result.durationMillis()).isLessThan(5_000);
        }
    }

    @Test
    void lockWaitCountsTowardTheCapabilityTimeout() throws Exception {
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(1), Duration.ofMinutes(10), 3);
        try (Running running = Running.start(settings)) {
            SessionKey key = new SessionKey(Origin.OFFICIAL, "legacy_oa", "alice");
            assertThat(running.sessions.tryLock(key, System.nanoTime() + Duration.ofMinutes(1).toNanos())).isTrue();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<ExecutionResult> future = pool.submit(() ->
                        running.list("alice", EngineTestSupport.PASSWORD, Map.of()));
                ExecutionResult result = future.get(10, TimeUnit.SECONDS);
                assertThat(result.error().code()).isEqualTo(ErrorCode.TIMEOUT);
                assertThat(result.durationMillis()).isLessThan(4_000);
                assertThat(running.fixture.hits()).isEmpty();
            } finally {
                running.sessions.unlock(key);
                pool.shutdownNow();
            }
        }
    }

    @Test
    void accountsRunInParallelAndCannotSeeEachOthersCookies() throws Exception {
        try (Running running = Running.start()) {
            running.fixture.armRendezvous();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ExecutionResult> alice = pool.submit(() ->
                        running.list("alice", EngineTestSupport.PASSWORD, Map.of()));
                Future<ExecutionResult> bob = pool.submit(() ->
                        running.list("bob", EngineTestSupport.PASSWORD, Map.of()));
                ExecutionResult aliceResult = alice.get(20, TimeUnit.SECONDS);
                ExecutionResult bobResult = bob.get(20, TimeUnit.SECONDS);
                assertThat(aliceResult.success()).isTrue();
                assertThat(bobResult.success()).isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(running.fixture.maxInFlight()).isGreaterThanOrEqualTo(2);
            assertCookieOwner(running, "alice", "bob");
            assertCookieOwner(running, "bob", "alice");
        }
    }

    @Test
    void sameAccountIsSerial() throws Exception {
        try (Running running = Running.start()) {
            running.fixture.todoDelay(Duration.ofMillis(300));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ExecutionResult> first = pool.submit(() ->
                        running.list("alice", EngineTestSupport.PASSWORD, Map.of()));
                Future<ExecutionResult> second = pool.submit(() ->
                        running.list("alice", EngineTestSupport.PASSWORD, Map.of()));
                assertThat(first.get(20, TimeUnit.SECONDS).success()).isTrue();
                assertThat(second.get(20, TimeUnit.SECONDS).success()).isTrue();
            } finally {
                pool.shutdownNow();
            }
            assertThat(running.fixture.maxInFlight()).isEqualTo(1);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/todo/list.aspx")).isEqualTo(2);
        }
    }

    @Test
    void originsDoNotShareASession() throws Exception {
        try (Running running = Running.start()) {
            assertThat(running.list("alice", EngineTestSupport.PASSWORD, Map.of()).success()).isTrue();
            ExecutionResult local = running.engine.execute(EngineTestSupport.request(
                    Origin.LOCAL,
                    "legacy_oa",
                    "list_todo",
                    "alice",
                    Map.of(),
                    EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, running.fixture.baseUrl())));
            assertThat(local.success()).isTrue();
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(2);
        }
    }

    @Test
    void replacingTheShellKeepsTheSession() throws Exception {
        try (Running running = Running.start()) {
            assertThat(running.list("alice", EngineTestSupport.PASSWORD, Map.of()).success()).isTrue();
            running.registry.put(ShellParser.parse(
                    EngineTestSupport.legacyYaml().replaceFirst("version: 1\\.0\\.0", "version: 1.1.0")));
            ExecutionResult again = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertThat(again.success()).isTrue();
            assertThat(again.shellVersion()).isEqualTo("1.1.0");
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
        }
    }

    @Test
    void idleAliveCheckRelogsInWhenTheSessionWasInvalidated() throws Exception {
        try (Running running = Running.start()) {
            assertThat(running.list("alice", EngineTestSupport.PASSWORD, Map.of()).success()).isTrue();
            running.clock.advance(Duration.ofMinutes(11));
            assertThat(running.list("alice", EngineTestSupport.PASSWORD, Map.of()).success()).isTrue();
            assertThat(running.fixture.count("GET", "/home.aspx")).isEqualTo(1);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);

            running.fixture.invalidateSessions();
            running.clock.advance(Duration.ofMinutes(11));
            assertThat(running.list("alice", EngineTestSupport.PASSWORD, Map.of()).success()).isTrue();
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(2);
            assertThat(running.fixture.count("GET", "/home.aspx")).isEqualTo(2);
        }
    }

    @Test
    void aliveCheck401LogsInAgain() throws Exception {
        try (Running running = Running.start()) {
            assertThat(running.list("alice", EngineTestSupport.PASSWORD, Map.of()).success()).isTrue();
            running.clock.advance(Duration.ofMinutes(11));
            running.fixture.homeStatus(401);
            assertThat(running.list("alice", EngineTestSupport.PASSWORD, Map.of()).success()).isTrue();
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(2);
            assertThat(running.fixture.count("GET", "/home.aspx")).isEqualTo(1);
        }
    }

    @Test
    void rejectsCrossOriginTargetsBeforeConnecting() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ShellParser.parse(EngineTestSupport.actionShell("cross_shell", "steal", """
                    - request: { method: GET, url: "http://127.0.0.1:1/steal" }
                    """, true)));
            ExecutionResult result = running.execute("cross_shell", "steal", "alice");
            assertThat(result.error().code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(result.error().summary()).contains("不同源");
            assertThat(running.fixture.hits()).isEmpty();
        }
    }

    @Test
    void doesNotFollowCrossOriginRedirects() throws Exception {
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(2), Duration.ofMinutes(10), 3);
        try (Running running = Running.start(settings)) {
            running.registry.put(ShellParser.parse(EngineTestSupport.actionShell("away_shell", "away", """
                    - request: { method: GET, path: /away, follow_redirects: true }
                    - assert: { status: 302 }
                    """, true)));
            ExecutionResult result = running.execute("away_shell", "away", "alice");
            assertThat(result.success()).isTrue();
            assertThat(running.fixture.count("GET", "/away")).isEqualTo(1);
            assertThat(result.durationMillis()).isLessThan(1_500);
        }
    }

    @Test
    void followsAtMostFiveSameOriginRedirects() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ShellParser.parse(EngineTestSupport.actionShell("loop_shell", "loop", """
                    - request: { method: GET, path: /loop, follow_redirects: true }
                    """, true)));
            ExecutionResult result = running.execute("loop_shell", "loop", "alice");
            assertThat(result.success()).isTrue();
            assertThat(running.fixture.count("GET", "/loop")).isEqualTo(6);

            running.registry.put(ShellParser.parse(EngineTestSupport.actionShell("once_shell", "once", """
                    - request: { method: GET, path: /once, follow_redirects: true }
                    - extract:
                        value: { css: "#m" }
                    """, false)));
            ExecutionResult once = running.execute("once_shell", "once", "alice");
            assertThat(once.success()).isTrue();
            assertThat(data(once)).containsEntry("value", "ok");
        }
    }

    @Test
    void rejectsBodiesOverFiveMegabytes() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ShellParser.parse(EngineTestSupport.actionShell("huge_shell", "huge", """
                    - request: { method: GET, path: /huge }
                    """, true)));
            ExecutionResult result = running.execute("huge_shell", "huge", "alice");
            assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(result.error().summary()).contains("5MB");
        }
    }

    @Test
    void decodesGbkFromMetaCharset() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ShellParser.parse(EngineTestSupport.actionShell("gbk_shell", "name", """
                    - request: { method: GET, path: /gbk/name.aspx }
                    - extract:
                        value: { css: "#name" }
                    """, false)));
            ExecutionResult result = running.execute("gbk_shell", "name", "alice");
            assertThat(result.success()).isTrue();
            assertThat(data(result)).containsEntry("value", "张三");
        }
    }

    @Test
    void doesNotRenderExtractedTextAsATemplate() throws Exception {
        try (Running running = Running.start();
                EngineTestSupport.LogCapture logs = new EngineTestSupport.LogCapture()) {
            running.registry.put(ShellParser.parse(EngineTestSupport.actionShell("echo_shell", "echo", """
                    - request: { method: GET, path: /echo.aspx }
                    - extract:
                        value: { css: "#v" }
                    """, false)));
            ExecutionResult result = running.execute("echo_shell", "echo", "alice");
            assertThat(result.success()).isTrue();
            assertThat(data(result)).containsEntry("value", "{{credential.password}}");
            absent(result, logs, null, EngineTestSupport.PASSWORD);
        }
    }

    @Test
    void masksCredentialValuesCopiedIntoOutput() throws Exception {
        try (Running running = Running.start();
                EngineTestSupport.LogCapture logs = new EngineTestSupport.LogCapture();
                EngineTestSupport.StreamCapture streams = new EngineTestSupport.StreamCapture()) {
            running.fixture.todoTitle(EngineTestSupport.PASSWORD);
            ExecutionResult result = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            absent(result, logs, streams, EngineTestSupport.PASSWORD);
            absent(result, logs, streams, sessionId(running, "/todo/list.aspx"));
            Object title = item(result).get("title");
            if (!"***".equals(title)) {
                throw new AssertionError("title was not masked; length "
                        + (title == null ? -1 : String.valueOf(title).length()));
            }
            assertThat(item(result)).containsEntry("id", "A1024").containsEntry("date", "2026-10-12");
        }
    }

    @Test
    void masksSecretsEmbeddedInOutput() throws Exception {
        String action = """
                show_secret:
                  title: 查看回显
                  description: 查看页面回显。
                  output:
                    greet: { type: string, description: 问候 }
                    sid: { type: string, description: 会话 }
                    note: { type: string, description: 备注 }
                  examples:
                    - input: {}
                      output: { greet: hi, sid: x, note: ok }
                  notes: 测试回显。
                  steps:
                    - request: { method: GET, path: /profile.aspx }
                    - extract:
                        greet: { css: "#greet" }
                        sid: { css: "#sid" }
                        note: { css: "#note" }
                """.stripIndent().indent(2);
        try (Running running = Running.start();
                EngineTestSupport.LogCapture logs = new EngineTestSupport.LogCapture();
                EngineTestSupport.StreamCapture streams = new EngineTestSupport.StreamCapture()) {
            running.registry.put(ShellParser.parse(
                    EngineTestSupport.legacyYaml().replace("health_check:", action + "health_check:")));
            running.fixture.welcome("欢迎 alice，你的密码是 " + EngineTestSupport.PASSWORD);
            ExecutionResult result = running.engine.execute(EngineTestSupport.request(
                    Origin.OFFICIAL,
                    "legacy_oa",
                    "show_secret",
                    "alice",
                    Map.of(),
                    EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, running.fixture.baseUrl())));
            String sid = sessionId(running, "/profile.aspx");
            absent(result, logs, streams, EngineTestSupport.PASSWORD);
            absent(result, logs, streams, sid);
            if (!result.success()) {
                throw new AssertionError("profile request failed: " + result.error().code());
            }
            Map<String, Object> data = data(result);
            if (!"欢迎 ***，你的密码是 ***".equals(data.get("greet"))) {
                throw new AssertionError("greeting was not masked");
            }
            if (!"***".equals(data.get("sid"))) {
                throw new AssertionError("session value was not masked");
            }
            assertThat(data.get("note")).isEqualTo("待办");
        }
    }

    @Test
    void testLoginRunsAuthStepsOnly() throws Exception {
        try (Running running = Running.start()) {
            ExecutionResult result = running.engine.testLogin(EngineTestSupport.request(
                    Origin.OFFICIAL,
                    "legacy_oa",
                    "test_login",
                    "alice",
                    Map.of(),
                    EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, running.fixture.baseUrl())));
            assertThat(result.success()).isTrue();
            assertThat(data(result)).containsEntry("ok", true);
            assertThat(result.stepLogs()).isEmpty();
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/todo/list.aspx")).isZero();
        }
    }

    @Test
    void summarizesTodosWithScript() throws Exception {
        String action = """
                summarize_todo:
                  title: 汇总待办
                  description: 用脚本汇总当前页待办条数和第一条标题。
                  input:
                    page: { type: integer, default: 1, description: 页码 }
                  steps:
                    - request:
                        method: GET
                        path: /todo/list.aspx
                        query: { p: "{{input.page}}" }
                    - extract:
                        items:
                          css: "table#todo tr:not(:first-child)"
                          each:
                            id: { css: "td:nth-child(1)" }
                            title: { css: "td:nth-child(2) a" }
                    - script:
                        code: |
                          var rows = vars.items;
                          var first = rows.length === 0 ? null : rows[0].title;
                          return { total: rows.length, first: first };
                  output:
                    total: { type: integer, description: 条数 }
                    first: { type: string, description: 第一条标题 }
                  examples:
                    - input: { page: 1 }
                      output: { total: 1, first: 张三的请假申请 }
                  notes: 只汇总本页。
                  related: [list_todo]
                  idempotent: true
                """.stripIndent().indent(2);
        try (Running running = Running.start();
                EngineTestSupport.LogCapture logs = new EngineTestSupport.LogCapture();
                EngineTestSupport.StreamCapture streams = new EngineTestSupport.StreamCapture()) {
            running.registry.put(ShellParser.parse(
                    EngineTestSupport.legacyYaml().replace("health_check:", action + "health_check:")));
            ExecutionResult result = running.engine.execute(EngineTestSupport.request(
                    Origin.OFFICIAL,
                    "legacy_oa",
                    "summarize_todo",
                    "alice",
                    Map.of(),
                    EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, running.fixture.baseUrl())));
            absent(result, logs, streams, EngineTestSupport.PASSWORD);
            absent(result, logs, streams, sessionId(running, "/todo/list.aspx"));
            assertThat(result.success()).isTrue();
            assertThat(data(result)).containsEntry("total", 1L).containsEntry("first", "张三的请假申请");
            assertThat(result.stepLogs()).extracting(StepLog::type).containsExactly("request", "extract", "script");
            assertThat(result.stepLogs().get(0).path()).isEqualTo("/todo/list.aspx");
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/todo/list.aspx")).isEqualTo(1);
        }
    }

    private static void absent(
            ExecutionResult result,
            EngineTestSupport.LogCapture logs,
            EngineTestSupport.StreamCapture streams,
            String secret
    ) {
        EngineTestSupport.secretsAbsent(EngineTestSupport.flatten(result), secret);
        if (logs != null) {
            EngineTestSupport.secretsAbsent(logs.text(), secret);
        }
        if (streams != null) {
            EngineTestSupport.secretsAbsent(streams.text(), secret);
        }
    }

    private static void assertCookieOwner(Running running, String owner, String other) {
        boolean seen = false;
        for (LegacyOaFixture.Hit hit : running.fixture.hits()) {
            if (!"/todo/list.aspx".equals(hit.path()) || hit.cookie() == null) {
                continue;
            }
            if (!hit.cookie().contains("sid-" + owner + "-")) {
                continue;
            }
            seen = true;
            if (hit.cookie().contains("sid-" + other + "-")) {
                throw new AssertionError("todo cookie mixed two accounts");
            }
        }
        if (!seen) {
            throw new AssertionError("missing todo cookie for " + owner);
        }
    }

    private static String query(Running running, String path) {
        for (LegacyOaFixture.Hit hit : running.fixture.hits()) {
            if (path.equals(hit.path()) && hit.query() != null) {
                return hit.query();
            }
        }
        throw new AssertionError("missing query for " + path);
    }

    private static String sessionId(Running running, String path) {
        for (LegacyOaFixture.Hit hit : running.fixture.hits()) {
            if (!path.equals(hit.path()) || hit.cookie() == null) {
                continue;
            }
            for (String part : hit.cookie().split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && "ASP.NET_SessionId".equals(pair[0])) {
                    return pair[1];
                }
            }
        }
        throw new AssertionError("missing session cookie");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ExecutionResult result) {
        if (!(result.data() instanceof Map<?, ?> map)) {
            throw new AssertionError("data was not a map");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(ExecutionResult result) {
        Object items = data(result).get("items");
        if (!(items instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> row)) {
            throw new AssertionError("items did not contain a row");
        }
        return (Map<String, Object>) row;
    }

    private static final class Running implements AutoCloseable {
        final LegacyOaFixture fixture;
        final ShellRegistry registry;
        final SessionManager sessions;
        final RuleEngine engine;
        final EngineTestSupport.MutableClock clock;

        private Running(
                LegacyOaFixture fixture,
                ShellRegistry registry,
                SessionManager sessions,
                RuleEngine engine,
                EngineTestSupport.MutableClock clock
        ) {
            this.fixture = fixture;
            this.registry = registry;
            this.sessions = sessions;
            this.engine = engine;
            this.clock = clock;
        }

        static Running start() throws Exception {
            return start(EngineSettings.defaults());
        }

        static Running start(EngineSettings settings) throws Exception {
            LegacyOaFixture fixture = new LegacyOaFixture(EngineTestSupport.PASSWORD);
            try {
                ShellRegistry registry = new ShellRegistry();
                registry.put(EngineTestSupport.legacy());
                EngineTestSupport.MutableClock clock = new EngineTestSupport.MutableClock();
                SessionManager sessions = new SessionManager(new NopSessionPersistence(), settings, clock);
                RuleEngine engine = new RuleEngine(registry, sessions, settings);
                return new Running(fixture, registry, sessions, engine, clock);
            } catch (RuntimeException ex) {
                fixture.close();
                throw ex;
            }
        }

        ExecutionResult list(String user, String password, Map<String, Object> input) {
            return engine.execute(EngineTestSupport.request(
                    Origin.OFFICIAL,
                    "legacy_oa",
                    "list_todo",
                    user,
                    input,
                    EngineTestSupport.account(user, password, fixture.baseUrl())));
        }

        ExecutionResult execute(String shell, String action, String user) {
            return engine.execute(EngineTestSupport.request(
                    Origin.OFFICIAL,
                    shell,
                    action,
                    user,
                    Map.of(),
                    EngineTestSupport.account(user, EngineTestSupport.PASSWORD, fixture.baseUrl())));
        }

        @Override
        public void close() {
            sessions.close();
            fixture.close();
        }
    }
}
