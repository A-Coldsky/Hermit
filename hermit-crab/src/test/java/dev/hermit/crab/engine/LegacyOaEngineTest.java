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
import dev.hermit.common.parse.ConchParser;
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
            assertThat(result.conchVersion()).isEqualTo("1.0.0");
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
            assertThat(result.error().message())
                    .contains("登录未建立会话")
                    .contains("核对用户名和密码")
                    .contains("重新测试登录")
                    .doesNotContain("cookie_exists")
                    .doesNotContain("assert 未通过");
            ExecutionResult login = running.engine.testLogin(EngineTestSupport.request(
                    Origin.OFFICIAL,
                    "legacy_oa",
                    "test_login",
                    "alice",
                    Map.of(),
                    EngineTestSupport.account("alice", EngineTestSupport.WRONG_PASSWORD, running.fixture.baseUrl())));
            assertThat(login.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(login.error().message()).isEqualTo(result.error().message());
            absent(login, logs, streams, EngineTestSupport.WRONG_PASSWORD);
            assertThat(result.stepLogs()).hasSize(4);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(2);
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
            running.registry.put(ConchParser.parse(
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
            assertThat(result.error().summary()).contains("匹配 0 条").contains("提取 ");
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
    void upstreamHttp400IsInvalidInputAndDoesNotLookLikeSuccess() throws Exception {
        try (Running running = Running.start()) {
            installTaskConch(running);
            ExecutionResult rejected = task(running, "create_task", Map.of("title", " "));
            assertThat(rejected.success()).isFalse();
            assertThat(rejected.data()).isNull();
            assertThat(rejected.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(rejected.error().message()).isEqualTo(RuleEngine.UPSTREAM_INPUT_REJECTED);
            assertThat(rejected.error().message()).doesNotContain("reject-token-9").doesNotContain("RULE_MISMATCH");
            assertThat(rejected.error().failedStepType()).isEqualTo("request");
            assertThat(rejected.stepLogs()).hasSize(1);
            assertThat(rejected.stepLogs().get(0).status()).isEqualTo(400);
            assertThat(running.fixture.count("POST", "/api/tasks")).isEqualTo(1);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
            absent(rejected, null, null, EngineTestSupport.PASSWORD);

            running.fixture.apiStatus(400);
            ExecutionResult listed = task(running, "list_tasks", Map.of());
            assertThat(listed.success()).isFalse();
            assertThat(listed.data()).isNull();
            assertThat(listed.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(listed.error().message()).doesNotContain("reject-token-9");
            assertThat(running.fixture.count("GET", "/api/tasks")).isEqualTo(1);
        }
    }

    @Test
    void acceptedUpstreamTaskStillReturnsTheId() throws Exception {
        try (Running running = Running.start()) {
            installTaskConch(running);
            ExecutionResult created = task(running, "create_task", Map.of("title", "周报"));
            assertThat(created.success()).isTrue();
            assertThat(data(created)).containsEntry("id", "T1");
            assertThat(created.error()).isNull();
        }
    }

    @Test
    void sessionHttp401And403OnWriteExpireWithoutRetry() throws Exception {
        try (Running running = Running.start()) {
            installTaskConch(running);
            running.fixture.denyApi(401);
            ExecutionResult expired = task(running, "create_task", Map.of("title", "周报"));
            assertThat(expired.success()).isFalse();
            assertThat(expired.data()).isNull();
            assertThat(expired.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(expired.error().failedStepType()).isEqualTo("request");
            assertThat(running.fixture.count("POST", "/api/tasks")).isEqualTo(1);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
            absent(expired, null, null, EngineTestSupport.PASSWORD);
        }
        try (Running running = Running.start()) {
            installTaskConch(running);
            running.fixture.denyApi(403);
            ExecutionResult expired = task(running, "create_task", Map.of("title", "周报"));
            assertThat(expired.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(running.fixture.count("POST", "/api/tasks")).isEqualTo(1);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
        }
    }

    @Test
    void sessionHttp401OnReadRetriesOnceAndDoesNotReturnAnEmptyList() throws Exception {
        try (Running running = Running.start()) {
            installTaskConch(running);
            running.fixture.denyNextApi(401);
            ExecutionResult recovered = task(running, "list_tasks", Map.of());
            assertThat(recovered.success()).isTrue();
            assertThat(items(recovered)).isNotEmpty();
            assertThat(running.fixture.count("GET", "/api/tasks")).isEqualTo(2);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(2);
        }
        try (Running running = Running.start()) {
            installTaskConch(running);
            running.fixture.denyApi(401);
            ExecutionResult expired = task(running, "list_tasks", Map.of());
            assertThat(expired.success()).isFalse();
            assertThat(expired.data()).isNull();
            assertThat(expired.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(expired.stepLogs()).extracting(StepLog::type).containsExactly("request");
            assertThat(running.fixture.count("GET", "/api/tasks")).isEqualTo(2);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(2);
            absent(expired, null, null, EngineTestSupport.PASSWORD);
        }
    }

    @Test
    void listTodo404And409AreUpstreamErrorsNotEmptyLists() throws Exception {
        try (Running running = Running.start()) {
            running.fixture.todoStatus(404);
            ExecutionResult missing = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertUpstreamStatus(missing, 404);
            assertThat(running.fixture.count("GET", "/todo/list.aspx")).isEqualTo(1);
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
        }
        try (Running running = Running.start()) {
            running.fixture.todoStatus(409);
            ExecutionResult conflict = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertUpstreamStatus(conflict, 409);
            assertThat(running.fixture.count("GET", "/todo/list.aspx")).isEqualTo(1);
        }
    }

    @Test
    void explicitStatusAssertKeeps302And304() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("see_redirect", "hop", """
                    - request: { method: GET, path: /loop, follow_redirects: false }
                    - set: { note: kept }
                    - assert: { status: 302 }
                    """, true)));
            ExecutionResult redirected = running.execute("see_redirect", "hop", "alice");
            assertThat(redirected.success()).as(redirected.error() == null ? "" : redirected.error().message()).isTrue();
            assertThat(redirected.stepLogs()).extracting(StepLog::type).containsExactly("request", "set", "assert");
            assertThat(redirected.stepLogs().get(0).status()).isEqualTo(302);
            assertThat(running.fixture.count("GET", "/loop")).isEqualTo(1);

            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("see_cached", "cached", """
                    - request: { method: GET, path: /cached }
                    - assert: { status: 304 }
                    """, true)));
            ExecutionResult cached = running.execute("see_cached", "cached", "alice");
            assertThat(cached.success()).as(cached.error() == null ? "" : cached.error().message()).isTrue();
            assertThat(cached.stepLogs().get(0).status()).isEqualTo(304);

            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("stale_cached", "stale", """
                    - request: { method: GET, path: /cached }
                    """, true)));
            ExecutionResult bare = running.execute("stale_cached", "stale", "alice");
            assertUpstreamStatus(bare, 304);
        }
    }

    @Test
    void post303ToNonLoginPageSucceedsWithoutFollowing() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("form_conch", "submit", """
                    - request: { method: POST, path: /form-post, follow_redirects: false }
                    """, true)));
            ExecutionResult result = running.execute("form_conch", "submit", "alice");
            assertThat(result.success()).as(result.error() == null ? "" : result.error().message()).isTrue();
            assertThat(result.stepLogs()).extracting(StepLog::status).containsExactly(303);
            assertThat(running.fixture.count("POST", "/form-post")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/not-login")).isZero();
        }
    }

    @Test
    void post303ToLoginPageIsStillSessionExpired() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ConchParser.parse("""
                    conch: form_login
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.0.0
                    title: 标题
                    description: 说明
                    credentials:
                      username: { type: text, required: true }
                      password: { type: secret, required: true }
                    auth:
                      alive_check:
                        request: { method: GET, path: /home.aspx }
                        expect_not_redirect: /login.aspx
                    actions:
                      submit:
                        title: 能力
                        description: 说明
                        idempotent: false
                        output:
                          value: { type: string, description: 结果, value: ok }
                        examples:
                          - input: {}
                            output: { value: ok }
                        notes: 注意
                        steps:
                          - request: { method: POST, path: /form-login, follow_redirects: false }
                    """));
            ExecutionResult result = running.execute("form_login", "submit", "alice");
            assertThat(result.success()).isFalse();
            assertThat(result.data()).isNull();
            assertThat(result.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(running.fixture.count("POST", "/form-login")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/login.aspx")).isZero();
        }
    }

    @Test
    void statusAssertThatDoesNotCoverTheCodeStillRejects() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("miss_conch", "missing", """
                    - request: { method: GET, path: /missing-page }
                    - assert: { status: 200 }
                    """, true)));
            ExecutionResult result = running.execute("miss_conch", "missing", "alice");
            assertUpstreamStatus(result, 404);
            assertThat(running.fixture.count("GET", "/missing-page")).isEqualTo(1);
        }
    }

    @Test
    void nonLoginRedirectOnListTodoIsUpstreamError() throws Exception {
        try (Running running = Running.start()) {
            running.fixture.todoRedirect("/not-login");
            ExecutionResult result = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertUpstreamStatus(result, 302);
            assertThat(result.error().code()).isNotEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(running.fixture.count("GET", "/todo/list.aspx")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/not-login")).isZero();
            assertThat(running.fixture.count("POST", "/login.aspx")).isEqualTo(1);
        }
    }

    @Test
    void statusAssertOn2xxRemainsRuleMismatch() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("status_conch", "created", """
                    - request: { method: GET, path: /echo.aspx }
                    - assert: { status: 201 }
                    """, true)));
            ExecutionResult result = running.execute("status_conch", "created", "alice");
            assertThat(result.success()).isFalse();
            assertThat(result.data()).isNull();
            assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().summary())
                    .contains("201")
                    .contains("200")
                    .contains("请核对")
                    .doesNotStartWith("assert 未通过")
                    .doesNotContain("assert 未通过")
                    .doesNotContain(EngineTestSupport.PASSWORD);
            assertThat(result.error().failedStepType()).isEqualTo("assert");
        }
    }

    @Test
    void login404StillReachesTheAssert() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ConchParser.parse("""
                    conch: login_status
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
                        - request: { method: GET, path: /missing-page }
                        - assert: { status: 200 }
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
                          - request: { method: GET, path: /echo.aspx }
                    """));
            ExecutionResult result = running.execute("login_status", "ping", "alice");
            assertThat(result.success()).isFalse();
            assertThat(result.data()).isNull();
            assertThat(result.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(result.error().summary()).contains("404").contains("200");
            assertThat(result.error().failedStepType()).isEqualTo("assert");
            assertThat(running.fixture.count("GET", "/missing-page")).isEqualTo(1);
            assertThat(running.fixture.count("GET", "/echo.aspx")).isZero();
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
    void replacingTheConchKeepsTheSession() throws Exception {
        try (Running running = Running.start()) {
            assertThat(running.list("alice", EngineTestSupport.PASSWORD, Map.of()).success()).isTrue();
            running.registry.put(ConchParser.parse(
                    EngineTestSupport.legacyYaml().replaceFirst("version: 1\\.0\\.0", "version: 1.1.0")));
            ExecutionResult again = running.list("alice", EngineTestSupport.PASSWORD, Map.of());
            assertThat(again.success()).isTrue();
            assertThat(again.conchVersion()).isEqualTo("1.1.0");
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
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("cross_conch", "steal", """
                    - request: { method: GET, url: "http://127.0.0.1:1/steal" }
                    """, true)));
            ExecutionResult result = running.execute("cross_conch", "steal", "alice");
            assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().summary()).isEqualTo(HttpCalls.CROSS_ORIGIN_TARGET);
            assertThat(result.error().summary()).doesNotContain("127.0.0.1").doesNotContain("/steal");
            assertThat(running.fixture.hits()).isEmpty();
        }
    }

    @Test
    void doesNotFollowCrossOriginRedirects() throws Exception {
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(2), Duration.ofMinutes(10), 3);
        try (Running running = Running.start(settings)) {
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("away_conch", "away", """
                    - request: { method: GET, path: /away, follow_redirects: true }
                    - assert: { status: 302 }
                    """, true)));
            ExecutionResult result = running.execute("away_conch", "away", "alice");
            assertThat(result.success()).as(result.error() == null ? "" : result.error().message()).isTrue();
            assertThat(result.stepLogs()).extracting(StepLog::type).containsExactly("request", "assert");
            assertThat(result.stepLogs().get(0).status()).isEqualTo(302);
            assertThat(running.fixture.count("GET", "/away")).isEqualTo(1);
            assertThat(result.durationMillis()).isLessThan(1_500);
        }
    }

    @Test
    void followsAtMostFiveSameOriginRedirects() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("loop_conch", "loop", """
                    - request: { method: GET, path: /loop, follow_redirects: true }
                    """, true)));
            ExecutionResult result = running.execute("loop_conch", "loop", "alice");
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(result.error().message()).isEqualTo(HttpCalls.TOO_MANY_REDIRECTS);
            assertThat(result.error().failedStepType()).isEqualTo("request");
            assertThat(result.stepLogs().get(0).status()).isEqualTo(302);
            assertThat(running.fixture.count("GET", "/loop")).isEqualTo(6);

            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("once_conch", "once", """
                    - request: { method: GET, path: /once, follow_redirects: true }
                    - extract:
                        value: { css: "#m" }
                    """, false)));
            ExecutionResult once = running.execute("once_conch", "once", "alice");
            assertThat(once.success()).isTrue();
            assertThat(data(once)).containsEntry("value", "ok");
        }
    }

    @Test
    void rejectsBodiesOverFiveMegabytes() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("huge_conch", "huge", """
                    - request: { method: GET, path: /huge }
                    """, true)));
            ExecutionResult result = running.execute("huge_conch", "huge", "alice");
            assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(result.error().summary()).contains("5MB");
        }
    }

    @Test
    void decodesGbkFromMetaCharset() throws Exception {
        try (Running running = Running.start()) {
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("gbk_conch", "name", """
                    - request: { method: GET, path: /gbk/name.aspx }
                    - extract:
                        value: { css: "#name" }
                    """, false)));
            ExecutionResult result = running.execute("gbk_conch", "name", "alice");
            assertThat(result.success()).isTrue();
            assertThat(data(result)).containsEntry("value", "张三");
        }
    }

    @Test
    void doesNotRenderExtractedTextAsATemplate() throws Exception {
        try (Running running = Running.start();
                EngineTestSupport.LogCapture logs = new EngineTestSupport.LogCapture()) {
            running.registry.put(ConchParser.parse(EngineTestSupport.actionConch("echo_conch", "echo", """
                    - request: { method: GET, path: /echo.aspx }
                    - extract:
                        value: { css: "#v" }
                    """, false)));
            ExecutionResult result = running.execute("echo_conch", "echo", "alice");
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
            running.registry.put(ConchParser.parse(
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
            running.registry.put(ConchParser.parse(
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

    private static void assertUpstreamStatus(ExecutionResult result, int status) {
        assertThat(result.success()).isFalse();
        assertThat(result.data()).isNull();
        assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(result.error().message()).isEqualTo(HttpCalls.upstreamStatus(status));
        assertThat(result.error().message()).doesNotContain("empty-list-marker");
        assertThat(result.error().failedStepType()).isEqualTo("request");
        assertThat(result.stepLogs()).extracting(StepLog::type).containsExactly("request");
    }

    private static void installTaskConch(Running running) {
        String actions = """
                create_task:
                  title: 创建任务
                  description: 在上游创建一个任务。
                  input:
                    title: { type: string, required: true, description: 标题 }
                  steps:
                    - request:
                        method: POST
                        path: /api/tasks
                        json: { title: "{{input.title}}" }
                    - assert: { status: 201 }
                    - extract:
                        id: { jsonpath: "$.id" }
                  output:
                    id: { type: string, description: 任务编号 }
                  examples:
                    - input: { title: 周报 }
                      output: { id: T1 }
                  notes: 标题不能为空白。
                  idempotent: false
                list_tasks:
                  title: 查询任务
                  description: 查询当前账号的任务。
                  steps:
                    - request: { method: GET, path: /api/tasks }
                    - extract:
                        items: { jsonpath: "$.items" }
                  output:
                    items:
                      type: array
                      description: 任务列表
                  examples:
                    - input: {}
                      output:
                        items: []
                  notes: 会话失效时不能把空列表当成成功。
                  idempotent: true
                  mcp: { read_only: true }
                """.stripIndent().indent(2);
        running.registry.put(ConchParser.parse(
                EngineTestSupport.legacyYaml().replace("health_check:", actions + "health_check:")));
    }

    private static ExecutionResult task(Running running, String action, Map<String, Object> input) {
        return running.engine.execute(EngineTestSupport.request(
                Origin.OFFICIAL,
                "legacy_oa",
                action,
                "alice",
                input,
                EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, running.fixture.baseUrl())));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> items(ExecutionResult result) {
        Object items = data(result).get("items");
        if (!(items instanceof List<?> list)) {
            throw new AssertionError("items was not a list");
        }
        return (List<Object>) list;
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
        final ConchRegistry registry;
        final SessionManager sessions;
        final RuleEngine engine;
        final EngineTestSupport.MutableClock clock;

        private Running(
                LegacyOaFixture fixture,
                ConchRegistry registry,
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
                ConchRegistry registry = new ConchRegistry();
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

        ExecutionResult execute(String conch, String action, String user) {
            return engine.execute(EngineTestSupport.request(
                    Origin.OFFICIAL,
                    conch,
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
