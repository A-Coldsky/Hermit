package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.crab.files.FileScope;
import dev.hermit.crab.files.FileStage;
import dev.hermit.crab.files.FileStageSettings;
import dev.hermit.crab.store.Origin;

/** 1.12.0 非 401/403 会话失效判定（auth.expired_when、alive_check.expect、assert.on_fail: session_expired）引擎测试。 */
class SessionExpiryEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private ExecutorService serverPool;
    private String base;
    private final AtomicInteger loginCount = new AtomicInteger();
    private final AtomicInteger aliveCount = new AtomicInteger();
    private final AtomicInteger actionHits = new AtomicInteger();
    private final AtomicInteger loginPageHits = new AtomicInteger();
    private final AtomicReference<String> validSession = new AtomicReference<>("s1");
    private volatile String expiryMode = "json_200";
    private volatile String aliveMode = "json_ok";
    private volatile boolean expireOnce = true;
    private volatile CountDownLatch concurrentBarrier;
    private String lastTrace;

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
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        boolean authed = cookie != null && cookie.contains("SID=" + validSession.get());
        if ("/login".equals(path)) {
            int n = loginCount.incrementAndGet();
            String next = "s" + n;
            validSession.set(next);
            exchange.getResponseHeaders().add("Set-Cookie", "SID=" + next + "; Path=/");
            // 故意让登录响应体带有 "请重新登录" 关键字，验证 auth.expired_when 不会误伤登录步骤。
            write(exchange, 200, "application/json", "{\"code\":0,\"hint\":\"请重新登录\"}");
            return;
        }
        if ("/login_token".equals(path)) {
            int n = loginCount.incrementAndGet();
            String next = "s" + n;
            validSession.set(next);
            exchange.getResponseHeaders().add("Set-Cookie", "SID=" + next + "; Path=/");
            write(exchange, 200, "application/json", "{\"code\":0,\"token\":\"secret-login-tok-" + n + "\"}");
            return;
        }
        if ("/api/me".equals(path)) {
            aliveCount.incrementAndGet();
            if ("slow_regex_body".equals(aliveMode)) {
                write(exchange, 200, "text/plain; charset=utf-8", "a".repeat(26) + "!");
                return;
            }
            if (!authed) {
                if ("html_200".equals(aliveMode)) {
                    write(exchange, 200, "text/html; charset=utf-8", "<script>top.location.href='/login'</script>");
                } else {
                    write(exchange, 200, "application/json", "{\"code\":401,\"msg\":\"未登录\"}");
                }
                return;
            }
            write(exchange, 200, "application/json", "{\"code\":0,\"data\":{\"userId\":\"u1\"}}");
            return;
        }
        if ("/login-page".equals(path)) {
            loginPageHits.incrementAndGet();
            write(exchange, 500, "text/html; charset=utf-8", "<html>login page crashed</html>");
            return;
        }
        if ("/api/redir-login".equals(path)) {
            int hit = actionHits.incrementAndGet();
            if (!authed || (expireOnce && hit == 1)) {
                if (expireOnce && hit == 1) {
                    validSession.set("invalidated");
                }
                exchange.getResponseHeaders().add("Location", "/login-page");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"redir.pdf\"");
            write(exchange, 200, "application/pdf", "%PDF-1.4 ok");
            return;
        }
        if ("/api/download".equals(path)) {
            int hit = actionHits.incrementAndGet();
            if (!authed) {
                write(exchange, 200, "text/html; charset=utf-8", "<html><body>请重新登录</body></html>");
                return;
            }
            if (expireOnce && hit == 1) {
                validSession.set("invalidated");
                write(exchange, 200, "text/html; charset=utf-8", "<html><body>请重新登录</body></html>");
                return;
            }
            exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"report.pdf\"");
            write(exchange, 200, "application/pdf", "%PDF-1.4 binary-content-without-marker");
            return;
        }
        if ("/api/bad_param".equals(path)) {
            write(exchange, 400, "application/json", "{\"code\":\"BAD_PARAM\",\"msg\":\"页码错误\"}");
            return;
        }
        if ("/api/html_ok".equals(path)) {
            actionHits.incrementAndGet();
            write(exchange, 200, "text/html; charset=utf-8", "<html><body><h1>工作台</h1></body></html>");
            return;
        }
        if ("/api/concurrent".equals(path)) {
            int hit = actionHits.incrementAndGet();
            CountDownLatch barrier = concurrentBarrier;
            if (barrier != null && hit <= 2) {
                barrier.countDown();
                try {
                    barrier.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                write(exchange, 200, "application/json", "{\"code\":401,\"msg\":\"会话过期\"}");
                return;
            }
            write(exchange, 200, "application/json", "{\"code\":0,\"items\":[\"ok\"]}");
            return;
        }
        // 默认能力接口 /api/items
        int hit = actionHits.incrementAndGet();
        boolean expired = !authed || (expireOnce && hit == 1);
        if (expired) {
            if (expireOnce && hit == 1) {
                validSession.set("invalidated");
            }
            switch (expiryMode) {
                case "json_200" -> write(exchange, 200, "application/json", "{\"code\":10001,\"msg\":\"登录超时\"}");
                case "json_list_expired" -> write(exchange, 200, "application/json",
                        "{\"errors\":[{\"code\":401},{\"code\":99}]}");
                case "js_redirect_200" -> write(exchange, 200, "text/html; charset=utf-8",
                        "<html><script>top.location.href='/login?next=/items'</script></html>");
                case "header_200" -> {
                    exchange.getResponseHeaders().add("X-Session-Expired", "True");
                    write(exchange, 200, "application/json", "{\"data\":[]}");
                }
                case "status_400_expired" -> write(exchange, 400, "application/json",
                        "{\"code\":\"TOKEN_EXPIRED\",\"msg\":\"会话已过期\"}");
                case "status_419" -> write(exchange, 419, "application/json", "{\"message\":\"CSRF token mismatch\"}");
                case "status_502_body" -> write(exchange, 502, "text/html; charset=utf-8",
                        "<html>502 Bad Gateway 请重新登录</html>");
                case "xml_custom" -> write(exchange, 200, "application/xml", "<resp><err>9001</err></resp>");
                default -> write(exchange, 200, "application/json", "{\"code\":401}");
            }
            return;
        }
        write(exchange, 200, "application/json", "{\"code\":0,\"items\":[\"t1\",\"t2\"]}");
    }

    private static void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void json200ExpiredWhenRelogsAndReplaysIdempotentAndFailsNonIdempotent() throws Exception {
        String yaml = baseYaml("""
                  expired_when:
                    - json_equals: {"$.code": [401, 10001]}
                    - contains: "请重新登录"
                """, true);
        ConchDoc doc = parseAndValidate(yaml);
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, doc);
            expiryMode = "json_200";
            expireOnce = true;

            ExecutionResult ok = execute(engine, doc, "list");
            assertThat(ok.success()).isTrue();
            assertThat(asMap(ok)).containsEntry("items", List.of("t1", "t2"));
            assertThat(loginCount.get()).isEqualTo(2);
            assertThat(actionHits.get()).isEqualTo(2);
            JsonNode trace = JSON.readTree(lastTrace);
            assertThat(trace.toString()).contains("\"code\":\"session_expired\"");
            assertThat(trace.toString()).contains("auth.expired_when 第 1 条");
        }

        // 非幂等能力：触发会话失效后清会话、不重放，直接返回 SESSION_EXPIRED
        loginCount.set(0);
        actionHits.set(0);
        ConchDoc nonIdem = parseAndValidate(baseYaml("""
                  expired_when:
                    - json_equals: {"$.code": [401, 10001]}
                """, false));
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, nonIdem);
            expiryMode = "json_200";
            expireOnce = true;

            ExecutionResult failed = execute(engine, nonIdem, "list");
            assertThat(failed.success()).isFalse();
            assertThat(failed.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(failed.error().failedStepType()).isEqualTo("request");
            assertThat(loginCount.get()).isEqualTo(1);
            assertThat(actionHits.get()).isEqualTo(1);
            // 已经标记登出，下一次调用会重新登录并成功
            expireOnce = false;
            ExecutionResult recovered = execute(engine, nonIdem, "list");
            assertThat(recovered.success()).isTrue();
            assertThat(loginCount.get()).isEqualTo(2);
        }

        // 5xx 始终按 UPSTREAM_ERROR 处理，即使 expired_when 未写 status 且正文命中关键词也不会误判会话失效
        loginCount.set(0);
        actionHits.set(0);
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, doc);
            expiryMode = "status_502_body";
            expireOnce = true;

            ExecutionResult err502 = execute(engine, doc, "list");
            assertThat(err502.success()).isFalse();
            assertThat(err502.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(loginCount.get()).isEqualTo(1);
            assertThat(actionHits.get()).isEqualTo(1);
        }
    }

    @Test
    void jsRedirectHeader400And419RulesWorkWithoutBreakingNormal400OrHtml() {
        String yaml = """
                conch: demo_expiry
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 示例
                description: 示例说明
                credentials: {}
                auth:
                  expired_when:
                    - regex: "top\\\\.location\\\\.href\\\\s*=\\\\s*'/login"
                    - header_contains: {X-Session-Expired: "true"}
                    - status: 400
                      json_equals: {"$.code": "TOKEN_EXPIRED"}
                    - status: [419, 440]
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                actions:
                  list:
                    title: 列表
                    description: 查询
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/items}
                      - extract: {items: {jsonpath: "$.items"}}
                    output: {items: {type: array, description: 列表}}
                    examples: [{input: {}, output: {items: []}}]
                    notes: 测试
                    related: []
                  bad_param:
                    title: 错参
                    description: 普通400
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/bad_param}
                    output: {ok: {type: string, description: ok, value: ok}}
                    examples: [{input: {}, output: {ok: ok}}]
                    notes: 测试
                    related: []
                  html_ok:
                    title: 网页
                    description: 非JSON正常响应
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/html_ok}
                      - extract: {title: {css: "h1"}}
                    output: {title: {type: string, description: 标题}}
                    examples: [{input: {}, output: {title: 工作台}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);

        for (String mode : List.of("js_redirect_200", "header_200", "status_400_expired", "status_419")) {
            loginCount.set(0);
            actionHits.set(0);
            expiryMode = mode;
            expireOnce = true;
            try (SessionManager sessions = new SessionManager()) {
                ExecutionResult res = execute(newEngine(sessions, doc), doc, "list");
                assertThat(res.success()).as("mode=" + mode).isTrue();
                assertThat(loginCount.get()).as("mode=" + mode).isEqualTo(2);
            }
        }

        // 普通 400 不匹配 expired_when，仍按 INVALID_INPUT 返回
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, doc);
            ExecutionResult bad = execute(engine, doc, "bad_param");
            assertThat(bad.success()).isFalse();
            assertThat(bad.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);

            // 非 JSON HTML 页面在存在 json_equals 规则时不会报错或误判失效
            ExecutionResult html = execute(engine, doc, "html_ok");
            assertThat(html.success()).isTrue();
            assertThat(asMap(html)).containsEntry("title", "工作台");
        }
    }

    @Test
    void aliveCheckExpectCatches200ExpiryOnIdleAndSharedIdentityAdopt() throws Exception {
        String pkgA = """
                conch: pkg_a
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 包A
                description: 包A
                credentials: {}
                auth:
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                  alive_check:
                    request: {method: GET, path: /api/me}
                    expect:
                      status: 200
                      json_equals: {"$.code": 0}
                      json_exists: "$.data.userId"
                      cookie_exists: SID
                actions:
                  list:
                    title: 列表
                    description: 查询
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/items}
                      - extract: {items: {jsonpath: "$.items"}}
                    output: {items: {type: array, description: 列表}}
                    examples: [{input: {}, output: {items: []}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        String pkgB = pkgA.replace("conch: pkg_a", "conch: pkg_b");
        ConchDoc docA = parseAndValidate(pkgA);
        ConchDoc docB = parseAndValidate(pkgB);

        // 1. 空闲超时探活：服务端把会话作废后返回 200 {"code": 401} -> expect 拦截并重新登录
        MutableClock clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(10), Duration.ofSeconds(5), 3);
        expireOnce = false;
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), SharedSessions.none(), settings, clock)) {
            RuleEngine engine = newEngine(sessions, settings, docA);
            assertThat(execute(engine, docA, "list").success()).isTrue();
            assertThat(loginCount.get()).isEqualTo(1);

            validSession.set("expired_on_server");
            clock.advance(Duration.ofSeconds(30));

            ExecutionResult afterIdle = execute(engine, docA, "list");
            assertThat(afterIdle.success()).isTrue();
            assertThat(aliveCount.get()).isEqualTo(1);
            assertThat(loginCount.get()).isEqualTo(2);
            JsonNode trace = JSON.readTree(lastTrace);
            assertThat(trace.at("/session/aliveCheckRan").asBoolean()).isTrue();
            assertThat(trace.at("/session/aliveCheckOk").asBoolean()).isFalse();
            assertThat(trace.at("/steps/0/warnings/0/code").asText()).isEqualTo("session_expired");
        }

        // 2. 共享身份 adopting：pkg_a 登录后会话仍有效 -> pkg_b 通过 alive_check.expect 直接接手，不重登
        loginCount.set(0);
        aliveCount.set(0);
        MemoryShared shared = new MemoryShared();
        SessionKey keyA = new SessionKey(Origin.LOCAL, "pkg_a", "alice");
        SessionKey keyB = new SessionKey(Origin.LOCAL, "pkg_b", "alice");
        shared.bind("id1", keyA);
        shared.bind("id1", keyB);
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), shared, EngineSettings.defaults(), clock)) {
            RuleEngine engine = newEngine(sessions, docA, docB);
            assertThat(execute(engine, docA, "list", "alice").success()).isTrue();
            assertThat(loginCount.get()).isEqualTo(1);

            assertThat(execute(engine, docB, "list", "alice").success()).isTrue();
            assertThat(aliveCount.get()).isEqualTo(1);
            assertThat(loginCount.get()).isEqualTo(1);
        }

        // 3. 共享身份 adopting 遇 200 业务码失效 -> pkg_b 探活失败后重登
        loginCount.set(0);
        aliveCount.set(0);
        MemoryShared shared2 = new MemoryShared();
        shared2.bind("id2", keyA);
        shared2.bind("id2", keyB);
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), shared2, EngineSettings.defaults(), clock)) {
            RuleEngine engine = newEngine(sessions, docA, docB);
            assertThat(execute(engine, docA, "list", "alice").success()).isTrue();
            assertThat(loginCount.get()).isEqualTo(1);

            validSession.set("kicked");
            assertThat(execute(engine, docB, "list", "alice").success()).isTrue();
            assertThat(aliveCount.get()).isEqualTo(1);
            assertThat(loginCount.get()).isEqualTo(2);
        }
    }

    @Test
    void assertOnFailSessionExpiredWorksWithDirectScriptAndNonIdempotent() throws Exception {
        String yaml = """
                conch: demo_assert_expiry
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 断言失效
                description: 断言触发会话失效
                credentials: {}
                auth:
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                actions:
                  xml_idem:
                    title: XML幂等
                    description: 脚本判断XML失效
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/items}
                      - script:
                          into: expired
                          code: "return vars.response.body.indexOf('<err>9001</err>') >= 0;"
                      - assert:
                          equals: {expired: false}
                          on_fail: session_expired
                      - extract: {items: {jsonpath: "$.items"}}
                    output: {items: {type: array, description: 列表}}
                    examples: [{input: {}, output: {items: []}}]
                    notes: 测试
                    related: []
                  xml_write:
                    title: XML写操作
                    description: 非幂等断言失效
                    idempotent: false
                    mcp: {expose: true, read_only: false}
                    steps:
                      - request: {method: GET, path: /api/items}
                      - assert:
                          not_contains: "<err>9001</err>"
                          on_fail: session_expired
                    output: {ok: {type: string, description: ok, value: ok}}
                    examples: [{input: {}, output: {ok: ok}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);
        expiryMode = "xml_custom";
        expireOnce = true;

        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, doc);
            ExecutionResult ok = execute(engine, doc, "xml_idem");
            assertThat(ok.success()).isTrue();
            assertThat(asMap(ok)).containsEntry("items", List.of("t1", "t2"));
            assertThat(loginCount.get()).isEqualTo(2);
            JsonNode trace = JSON.readTree(lastTrace);
            assertThat(trace.toString()).contains("\"code\":\"session_expired\"");
            assertThat(trace.toString()).contains("\"onFail\":\"session_expired\"");
        }

        // 非幂等能力：不重放，返回 SESSION_EXPIRED，且 failedStepType 为 assert
        loginCount.set(0);
        actionHits.set(0);
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, doc);
            ExecutionResult failed = execute(engine, doc, "xml_write");
            assertThat(failed.success()).isFalse();
            assertThat(failed.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(failed.error().failedStepType()).isEqualTo("assert");
            assertThat(failed.error().failedStepIndex()).isEqualTo(1);
            assertThat(loginCount.get()).isEqualTo(1);
        }
    }

    @Test
    void concurrentReadsHandoffWorksWithExpiredWhen() throws Exception {
        String yaml = """
                conch: demo_concurrent
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 并发读
                description: 并发读200失效
                credentials: {}
                auth:
                  concurrent_reads: true
                  expired_when:
                    - json_equals: {"$.code": 401}
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                actions:
                  list:
                    title: 列表
                    description: 查询
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/concurrent}
                      - extract: {items: {jsonpath: "$.items"}}
                    output: {items: {type: array, description: 列表}}
                    examples: [{input: {}, output: {items: []}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, doc);
            // 先建立会话
            concurrentBarrier = null;
            actionHits.set(10);
            assertThat(execute(engine, doc, "list").success()).isTrue();
            assertThat(loginCount.get()).isEqualTo(1);

            // 两个并发只读调用同时在共享锁下收到 200 {"code": 401}，升级独占锁后只重新登录一次，全部成功。
            actionHits.set(0);
            concurrentBarrier = new CountDownLatch(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ExecutionResult> f1 = pool.submit(() -> execute(engine, doc, "list"));
                Future<ExecutionResult> f2 = pool.submit(() -> execute(engine, doc, "list"));
                ExecutionResult r1 = f1.get(10, TimeUnit.SECONDS);
                ExecutionResult r2 = f2.get(10, TimeUnit.SECONDS);
                assertThat(r1.success()).isTrue();
                assertThat(r2.success()).isTrue();
                assertThat(loginCount.get()).isEqualTo(2);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void downloadExpiredByHeaderDiscardsSpoolAndNotContainsDoesNotFalseFireOnPdf(@TempDir Path temp) {
        String yaml = """
                conch: demo_download
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 下载
                description: 下载失效重登
                credentials: {}
                auth:
                  expired_when:
                    - header_contains: {content-type: "text/html"}
                    - status: 200
                      not_contains: "MUST_EXIST_MARKER"
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                actions:
                  export_pdf:
                    title: 导出
                    description: 导出PDF
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/download, download: true}
                    output: {file: {type: file, description: 文件, value: "{{response.file}}"}}
                    examples: [{input: {}, output: {file: {fileId: "f1", name: "a.pdf", size: 1, mime: "application/pdf", sha256: "0", downloadUrl: "/api/-/files/x"}}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        expireOnce = true;
        FileScope previous = FileScope.begin(stage, "k1", "http://127.0.0.1:8080");
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, doc);
            ExecutionResult res = execute(engine, doc, "export_pdf");
            assertThat(res.success()).isTrue();
            assertThat(loginCount.get()).isEqualTo(2);
            // 只存了重登后真正的 PDF（第 1 次返回的 HTML 登录页已丢弃，没有进入暂存区）
            assertThat(stage.list()).hasSize(1);
            assertThat(stage.list().get(0).name()).isEqualTo("report.pdf");
            assertThat(stage.list().get(0).mime()).isEqualTo("application/pdf");
        } finally {
            FileScope.restore(previous);
        }
    }

    @Test
    void sessionContentionDetectsAlternating200ExpiredWhenKicks() {
        ConchDoc docA = parseAndValidate(baseYaml("""
                  expired_when:
                    - json_equals: {"$.code": [401, 10001]}
                """, true).replace("conch: demo_expiry", "conch: pkg_a"));
        ConchDoc docB = parseAndValidate(baseYaml("""
                  expired_when:
                    - json_equals: {"$.code": [401, 10001]}
                """, true).replace("conch: demo_expiry", "conch: pkg_b"));
        SessionContention contention = new SessionContention(Clock.systemUTC());
        expiryMode = "json_200";
        expireOnce = false;
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, docA, docB);
            engine.bindContention(contention);

            // 每次另一个包登录，都会把 validSession 换新，导致前一个包返回 200 {"code": 10001} 并重登。
            for (int round = 0; round < SessionContention.ROUNDS + 1; round++) {
                assertThat(execute(engine, docA, "list").success()).isTrue();
                assertThat(execute(engine, docB, "list").success()).isTrue();
            }
            List<SessionContention.Pair> active = contention.active();
            assertThat(active).hasSize(1);
            assertThat(active.get(0).first()).isEqualTo("pkg_a");
            assertThat(active.get(0).second()).isEqualTo("pkg_b");
        }
    }

    @Test
    void edgeCasesRepeatAssertOnFailPureVariableAssertStructuralErrorAndAliveViaExpiredWhen() {
        // 1. repeat 里的 assert on_fail: session_expired 触发重登重放；纯变量 assert 不需要前置 request；
        //    assert 写错（模板渲染出非数字 status）不触发重登，仍报 RULE_MISMATCH。
        String yaml = """
                conch: demo_edges
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 边界
                description: 边界测试
                credentials: {}
                auth:
                  expired_when:
                    - json_equals: {"$.code": [401.0, 10001]}
                    - json_not_equals: {"$.valid": true}
                    - json_exists: "$.forceLogout"
                    - header_exists: X-Session-Expired
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                  alive_check:
                    request: {method: GET, path: /api/me}
                actions:
                  in_repeat:
                    title: 循环中断言失效
                    description: repeat里assert触发会话失效
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - repeat:
                          max: 2
                          steps:
                            - request: {method: GET, path: /api/items}
                            - assert: {not_contains: "<err>9001</err>", on_fail: session_expired}
                          until: {status: 200}
                      - extract: {items: {jsonpath: "$.items"}}
                    output: {items: {type: array, description: 列表}}
                    examples: [{input: {}, output: {items: []}}]
                    notes: 测试
                    related: []
                  pure_var:
                    title: 纯变量断言
                    description: 不发请求直接断言变量
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {state: "ready", empty_val: ""}
                      - assert:
                          equals: {state: "ready"}
                          not_equals: {state: "failed"}
                          present: state
                          not_present: [missing_var, empty_val]
                    output: {state: {type: string, description: 状态}}
                    examples: [{input: {}, output: {state: ready}}]
                    notes: 测试
                    related: []
                  bad_status_assert:
                    title: 错误断言不重登
                    description: 非数字status报错不重登
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/html_ok}
                      - set: {bad: "oops"}
                      - assert: {status: "{{bad}}", on_fail: session_expired}
                    output: {ok: {type: string, description: ok, value: ok}}
                    examples: [{input: {}, output: {ok: ok}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);
        MutableClock clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(10), Duration.ofSeconds(5), 3);

        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), SharedSessions.none(), settings, clock)) {
            RuleEngine engine = newEngine(sessions, settings, doc);

            // repeat 内的 assert on_fail: session_expired 触发重登并成功
            expiryMode = "xml_custom";
            expireOnce = true;
            ExecutionResult rep = execute(engine, doc, "in_repeat");
            assertThat(rep.success()).isTrue();
            assertThat(asMap(rep)).containsEntry("items", List.of("t1", "t2"));
            assertThat(loginCount.get()).isEqualTo(2);

            // 纯变量 assert 不需要前置 request 就能通过
            ExecutionResult pv = execute(engine, doc, "pure_var");
            assertThat(pv.success()).isTrue();
            assertThat(asMap(pv)).containsEntry("state", "ready");

            // 结构错误（模板渲染出非数字 status）即使写了 on_fail: session_expired 也不应触发重登
            int loginsBefore = loginCount.get();
            ExecutionResult bad = execute(engine, doc, "bad_status_assert");
            assertThat(bad.success()).isFalse();
            assertThat(bad.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(loginCount.get()).isEqualTo(loginsBefore);

            // 没写 alive_check.expect 时，空闲探活仍会自动套用 auth.expired_when（401.0 == 401）
            validSession.set("expired_on_server");
            expireOnce = false;
            clock.advance(Duration.ofSeconds(30));
            ExecutionResult afterIdle = execute(engine, doc, "pure_var");
            assertThat(afterIdle.success()).isTrue();
            assertThat(aliveCount.get()).isEqualTo(1);
            assertThat(loginCount.get()).isEqualTo(loginsBefore + 1);
        }
    }

    @Test
    void non2xxAnd400ReachAssertOnFailAndIfHandlersAndJsonListExpiredWhen() {
        String yaml = """
                conch: demo_p02
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 非2xx与400判定
                description: 验证400与非2xx可进入assert(on_fail)和if
                credentials: {}
                auth:
                  expired_when:
                    - json_equals: {"$.errors[*].code": 401}
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                actions:
                  assert_status_on_fail:
                    title: 断言状态失效
                    description: assert(on_fail)接住400与419
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/items}
                      - assert: {status: 200, on_fail: session_expired}
                      - extract: {items: {jsonpath: "$.items"}}
                    output: {items: {type: array, description: 列表}}
                    examples: [{input: {}, output: {items: []}}]
                    notes: 测试
                    related: []
                  if_handles_400:
                    title: 分支接住400
                    description: if status 400不被提前拦截
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/bad_param}
                      - set: {handled: "no"}
                      - if:
                          status: 400
                          then:
                            - set: {handled: "yes"}
                    output: {handled: {type: string, description: 处理结果}}
                    examples: [{input: {}, output: {handled: yes}}]
                    notes: 测试
                    related: []
                  if_contains_assert_on_fail:
                    title: 无请求if内含失效断言
                    description: 无request的if内部assert(on_fail)接住400
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/items}
                      - if:
                          contains: "TOKEN_EXPIRED"
                          then:
                            - assert: {not_contains: "TOKEN_EXPIRED", on_fail: session_expired}
                      - extract: {items: {jsonpath: "$.items"}}
                    output: {items: {type: array, description: 列表}}
                    examples: [{input: {}, output: {items: []}}]
                    notes: 测试
                    related: []
                  if_no_request_then_status_assert:
                    title: 无请求if之后的状态断言
                    description: 跨过无request的if扫描后续assert.status
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/bad_param}
                      - if:
                          contains: "NEVER_MATCH"
                          then:
                            - set: {flag: "1"}
                      - assert: {status: [200, 400]}
                    output: {ok: {type: string, description: ok, value: ok}}
                    examples: [{input: {}, output: {ok: ok}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);

        for (String mode : List.of("status_400_expired", "status_419", "json_list_expired")) {
            loginCount.set(0);
            actionHits.set(0);
            expiryMode = mode;
            expireOnce = true;
            try (SessionManager sessions = new SessionManager()) {
                ExecutionResult res = execute(newEngine(sessions, doc), doc, "assert_status_on_fail");
                assertThat(res.success()).as("mode=" + mode + " err=" + res.error()).isTrue();
                assertThat(asMap(res)).containsEntry("items", List.of("t1", "t2"));
                assertThat(loginCount.get()).as("mode=" + mode).isEqualTo(2);
            }
        }

        // 无 request 的 if 内含 assert(on_fail: session_expired)：400 放行并触发重登重放
        loginCount.set(0);
        actionHits.set(0);
        expiryMode = "status_400_expired";
        expireOnce = true;
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, doc);
            ExecutionResult viaIf = execute(engine, doc, "if_contains_assert_on_fail");
            assertThat(viaIf.success()).as(String.valueOf(viaIf.error())).isTrue();
            assertThat(asMap(viaIf)).containsEntry("items", List.of("t1", "t2"));
            assertThat(loginCount.get()).isEqualTo(2);

            // if: {status: 400} 接住 400
            ExecutionResult handled400 = execute(engine, doc, "if_handles_400");
            assertThat(handled400.success()).as(String.valueOf(handled400.error())).isTrue();
            assertThat(asMap(handled400)).containsEntry("handled", "yes");

            // 跨过不含 request 的 if，由后续 assert: {status: [200, 400]} 接住 400
            ExecutionResult afterIf = execute(engine, doc, "if_no_request_then_status_assert");
            assertThat(afterIf.success()).as(String.valueOf(afterIf.error())).isTrue();
        }
    }

    @Test
    void loginTaintedSetAndExtractCannotFlowIntoOrdinaryScriptOrLeakUnmasked() {
        String yaml = """
                conch: demo_p01
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 登录值防绕过
                description: set和extract派生的login值不得流入普通script或绕过脱敏
                credentials: {}
                auth:
                  steps:
                    - request: {method: POST, path: /login_token}
                    - assert: {status: 200, cookie_exists: SID}
                    - extract: {tok: {jsonpath: "$.token"}}
                actions:
                  probe:
                    title: 探测
                    description: 测试login变量污染传播
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set:
                          alias: "{{login.tok}}"
                          encoded_val: "{{login.tok | base64}}"
                      - set:
                          alias2: "p-{{alias}}"
                      - request: {method: GET, path: /api/html_ok}
                      - extract:
                          from_tainted: {from: alias, regex: "(.*)"}
                          from_filter: {regex: "(NEVER_MATCH)", optional: true, filter: "default(login.tok)"}
                      - script:
                          into: seen
                          code: "return [typeof vars.login, typeof vars.alias, typeof vars.alias2, typeof vars.from_tainted, typeof vars.from_filter].join('|');"
                    output:
                      seen: {type: string, description: 脚本可见性}
                      leaked: {type: string, description: 脱敏测试, value: "{{encoded_val}}"}
                    examples: [{input: {}, output: {seen: "undefined|undefined|undefined|undefined|undefined", leaked: "***"}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = newEngine(sessions, doc);
            ExecutionResult res = execute(engine, doc, "probe");
            assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
            assertThat(asMap(res)).containsEntry("seen", "undefined|undefined|undefined|undefined|undefined");
            String b64 = java.util.Base64.getEncoder()
                    .encodeToString("secret-login-tok-1".getBytes(StandardCharsets.UTF_8));
            assertThat(String.valueOf(asMap(res).get("leaked"))).doesNotContain(b64).isEqualTo("***");
        }
    }

    @Test
    void expectNotRedirectStopsImmediatelyWithoutFollowingToLoginPage(@TempDir Path temp) {
        String yaml = """
                conch: demo_redir_stop
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 跳转即停
                description: 命中expect_not_redirect时不继续跟随到登录页
                credentials: {}
                auth:
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                  alive_check:
                    request: {method: GET, path: /api/me}
                    expect_not_redirect: /login-page
                actions:
                  export_redir:
                    title: 导出
                    description: 带follow_redirects的下载遇登录跳转立即重登
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/redir-login, follow_redirects: true, download: true}
                    output: {file: {type: file, description: 文件, value: "{{response.file}}"}}
                    examples: [{input: {}, output: {file: {fileId: "f1", name: "a.pdf", size: 1, mime: "application/pdf", sha256: "0", url: "/api/-/files/x"}}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        expireOnce = true;
        FileScope prev = FileScope.begin(stage, "k1", "");
        try (SessionManager sessions = new SessionManager()) {
            ExecutionResult res = execute(newEngine(sessions, doc), doc, "export_redir");
            assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
            assertThat(loginCount.get()).isEqualTo(2);
            assertThat(loginPageHits.get()).as("命中 expect_not_redirect 后不能继续请求 /login-page").isZero();
            assertThat(stage.list()).hasSize(1);
            assertThat(stage.list().get(0).name()).isEqualTo("redir.pdf");
        } finally {
            FileScope.restore(prev);
        }
    }

    @Test
    void aliveCheckExpectTimeoutStillRecordsFailedAliveCheckTrace() throws Exception {
        String yaml = """
                conch: demo_alive_timeout
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 探活超时记账
                description: alive_check.expect正则超时仍写入失败探活追踪
                credentials: {}
                auth:
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                  alive_check:
                    request: {method: GET, path: /api/me}
                    expect:
                      status: 200
                      regex: "((a+)+)+$"
                actions:
                  ping:
                    title: 查询
                    description: 普通请求
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/html_ok}
                    output: {ok: {type: string, description: ok, value: ok}}
                    examples: [{input: {}, output: {ok: ok}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);
        MutableClock clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        EngineSettings settings = new EngineSettings(Duration.ofMillis(250), Duration.ofSeconds(5), 3);
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), SharedSessions.none(), settings, clock)) {
            RuleEngine engine = newEngine(sessions, settings, doc);
            assertThat(execute(engine, doc, "ping").success()).isTrue();

            aliveMode = "slow_regex_body";
            clock.advance(Duration.ofSeconds(30));
            ExecutionResult timedOut = execute(engine, doc, "ping");
            assertThat(timedOut.success()).isFalse();
            assertThat(timedOut.error().code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(timedOut.error().failedStepType()).isEqualTo("request");
            JsonNode trace = JSON.readTree(lastTrace);
            assertThat(trace.at("/session/aliveCheckRan").asBoolean()).isTrue();
            assertThat(trace.at("/session/aliveCheckOk").asBoolean()).isFalse();
        }
    }

    @Test
    void emptyAuthStepsFailsWithSessionExpiredWhenAliveCheckFails() {
        String yaml = """
                conch: demo_empty_auth
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 无登录步骤探活失败
                description: auth.steps为空且alive_check失败时直接返回SESSION_EXPIRED
                credentials: {}
                auth:
                  alive_check:
                    request: {method: GET, path: /api/me}
                    expect:
                      status: 200
                      json_equals: {"$.code": 0}
                actions:
                  ping:
                    title: 查询
                    description: 普通请求
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/html_ok}
                    output: {ok: {type: string, description: ok, value: ok}}
                    examples: [{input: {}, output: {ok: ok}}]
                    notes: 测试
                    related: []
                """.formatted(base);
        ConchDoc doc = parseAndValidate(yaml);
        MutableClock clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(10), Duration.ofSeconds(5), 3);
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), SharedSessions.none(), settings, clock)) {
            RuleEngine engine = newEngine(sessions, settings, doc);
            assertThat(execute(engine, doc, "ping").success()).isTrue();
            int hitsBefore = actionHits.get();

            clock.advance(Duration.ofSeconds(30));
            ExecutionResult expired = execute(engine, doc, "ping");
            assertThat(expired.success()).isFalse();
            assertThat(expired.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(actionHits.get()).as("探活失败且无登录步骤时不应继续执行能力步骤").isEqualTo(hitsBefore);
            assertThat(sessions.loggedIn(new SessionKey(Origin.LOCAL, "demo_empty_auth", "default"))).isFalse();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(ExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    private RuleEngine newEngine(SessionManager sessions, ConchDoc... docs) {
        return newEngine(sessions, EngineSettings.defaults(), docs);
    }

    private RuleEngine newEngine(SessionManager sessions, EngineSettings settings, ConchDoc... docs) {
        ConchRegistry registry = new ConchRegistry();
        for (ConchDoc doc : docs) {
            registry.put(doc, Origin.LOCAL);
        }
        RuleEngine engine = new RuleEngine(registry, sessions, settings);
        engine.bindDevTraceSink(new DevTraceSink() {
            @Override
            public DevTraceDraft open(ExecutionRequest request) {
                return new DevTraceDraft(Instant.parse("2026-11-23T01:00:00Z"));
            }

            @Override
            public void save(ExecutionRequest request, Instant expiresAt, String body) {
                lastTrace = body;
            }
        });
        return engine;
    }

    private ExecutionResult execute(RuleEngine engine, ConchDoc doc, String action) {
        return execute(engine, doc, action, "default");
    }

    private ExecutionResult execute(RuleEngine engine, ConchDoc doc, String action, String account) {
        return engine.execute(EngineTestSupport.request(
                Origin.LOCAL,
                doc.conch(),
                action,
                account,
                Map.of(),
                new AccountContext() {
                    @Override
                    public Map<String, String> credentials() {
                        return Map.of();
                    }

                    @Override
                    public Map<String, String> config() {
                        return Map.of();
                    }
                }));
    }

    private static ConchDoc parseAndValidate(String yaml) {
        ConchDoc doc = ConchParser.parse(yaml);
        assertThat(ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT)).errors()).isEmpty();
        return doc;
    }

    private String baseYaml(String authExtra, boolean idempotent) {
        return """
                conch: demo_expiry
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: %s
                title: 示例
                description: 示例说明
                credentials: {}
                auth:
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                %sactions:
                  list:
                    title: 列表
                    description: 查询
                    idempotent: %s
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/items}
                      - extract: {items: {jsonpath: "$.items"}}
                    output: {items: {type: array, description: 列表}}
                    examples: [{input: {}, output: {items: []}}]
                    notes: 测试
                    related: []
                """.formatted(base, authExtra, idempotent);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration delta) {
            current = current.plus(delta);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class MemoryShared implements SharedSessions {
        private final Map<SessionKey, String> map = new ConcurrentHashMap<>();
        private final Map<String, List<StoredCookie>> saved = new ConcurrentHashMap<>();

        void bind(String id, SessionKey key) {
            map.put(key, id);
        }

        @Override
        public String identityOf(SessionKey key) {
            return map.get(key);
        }

        @Override
        public List<SessionKey> members(String identity) {
            return map.entrySet().stream()
                    .filter(e -> identity.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        @Override
        public List<StoredCookie> load(String identity) {
            return saved.getOrDefault(identity, List.of());
        }

        @Override
        public void save(String identity, List<StoredCookie> cookies) {
            saved.put(identity, List.copyOf(cookies));
        }

        @Override
        public void delete(String identity) {
            saved.remove(identity);
        }
    }
}
