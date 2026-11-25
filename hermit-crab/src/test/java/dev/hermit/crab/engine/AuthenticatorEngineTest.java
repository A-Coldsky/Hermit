package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import dev.hermit.crab.store.Origin;

/**
 * 授权器（driver: auth）和引用它的业务包（auth.use）。认证中心在 localhost，两个应用在 127.0.0.1 的两个端口。
 * oa 写了 auth.exchange（拿认证中心的 Cookie 换自己的会话），api 没写（直接带导出的令牌）。
 */
class AuthenticatorEngineTest {
    private static final SessionKey SSO = new SessionKey(Origin.LOCAL, "corp_sso", "zhang");
    private static final SessionKey OA = new SessionKey(Origin.LOCAL, "oa", "main");
    private static final SessionKey API = new SessionKey(Origin.LOCAL, "api", "main");
    private static final SessionKey API2 = new SessionKey(Origin.LOCAL, "api2", "main");
    private static final SessionKey COPY = new SessionKey(Origin.LOCAL, "api_copy", "main");
    private static final SessionKey SUB = new SessionKey(Origin.LOCAL, "oa_sub", "main");
    private static final SessionKey PEEK = new SessionKey(Origin.LOCAL, "oa_peek", "main");
    private static final SessionKey READS = new SessionKey(Origin.LOCAL, "oa_reads", "main");
    private static final String PASSWORD = EngineTestSupport.PASSWORD;

    private HttpServer sso;
    private HttpServer oa;
    private HttpServer api;
    private ExecutorService pool;
    private String ssoUrl;
    private String oaUrl;
    private String apiUrl;

    private final AtomicInteger passwordLogins = new AtomicInteger();
    private final AtomicInteger failedLogins = new AtomicInteger();
    private final AtomicInteger tickets = new AtomicInteger();
    private final AtomicInteger probes = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Set<String> ssoSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingTickets = ConcurrentHashMap.newKeySet();
    private final Set<String> oaSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> subSessions = ConcurrentHashMap.newKeySet();
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<String> peeks = new CopyOnWriteArrayList<>();
    /** api 的 /data 一律按会话失效回 401（规则把业务错误当成失效，或令牌永远不被接受）。 */
    private volatile boolean apiAlwaysRejects;
    /** oa 不认任何票据。 */
    private volatile boolean oaRejectsTickets;
    /** 登录要短信验证码。 */
    private volatile boolean wantsOtp;

    @BeforeEach
    void start() throws IOException {
        pool = Executors.newCachedThreadPool();
        sso = server("localhost", this::serveSso);
        oa = server("127.0.0.1", this::serveOa);
        api = server("127.0.0.1", this::serveApi);
        ssoUrl = "http://localhost:" + sso.getAddress().getPort();
        oaUrl = "http://127.0.0.1:" + oa.getAddress().getPort();
        apiUrl = "http://127.0.0.1:" + api.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        for (HttpServer server : List.of(sso, oa, api)) {
            server.stop(0);
        }
        pool.shutdownNow();
    }

    private HttpServer server(String host, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, 0), 0);
        server.setExecutor(pool);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String cookie(HttpExchange exchange, String name) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.strip().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private void serveSso(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String tgc = cookie(exchange, "TGC");
        seen.add("sso " + exchange.getRequestMethod() + " " + path + " sid=" + cookie(exchange, "sidA"));
        boolean live = tgc != null && ssoSessions.contains(tgc);
        switch (path) {
            case "/login" -> {
                if (!body.contains("password=S3cret%21pwd")) {
                    failedLogins.incrementAndGet();
                    reply(exchange, 401, "{\"error\":\"bad password\"}");
                    return;
                }
                if (wantsOtp && !body.contains("otp=246810")) {
                    reply(exchange, 200, "{\"token\":\"\",\"otp\":\"required\"}");
                    return;
                }
                passwordLogins.incrementAndGet();
                String value = "tgc-" + sequence.incrementAndGet();
                String token = "token-" + sequence.incrementAndGet() + "-secret";
                ssoSessions.add(value);
                tokens.add(token);
                exchange.getResponseHeaders().add("Set-Cookie", "TGC=" + value + "; Path=/");
                reply(exchange, 200, "{\"token\":\"" + token + "\"}");
            }
            case "/me" -> {
                probes.incrementAndGet();
                reply(exchange, live ? 200 : 401, "{\"ok\":" + live + "}");
            }
            case "/ticket" -> {
                if (!live) {
                    reply(exchange, 401, "{\"error\":\"login\"}");
                    return;
                }
                String ticket = "ST-" + tickets.incrementAndGet() + "-ticket";
                pendingTickets.add(ticket);
                reply(exchange, 200, "{\"ticket\":\"" + ticket + "\"}");
            }
            default -> reply(exchange, 404, "{}");
        }
    }

    private void serveOa(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = String.valueOf(exchange.getRequestURI().getQuery());
        String sid = cookie(exchange, "sidA");
        seen.add("oa " + exchange.getRequestMethod() + " " + path + " tgc=" + cookie(exchange, "TGC"));
        String peek = exchange.getRequestHeaders().getFirst("X-Peek");
        if (peek != null) {
            peeks.add(peek);
        }
        if (path.startsWith("/sub/")) {
            // 同一台主机上、另一个路径下的第二个应用，会话 Cookie 只发到 /sub。
            if (path.equals("/sub/cas")) {
                if (!pendingTickets.remove(query.replace("ticket=", ""))) {
                    reply(exchange, 403, "{\"error\":\"bad ticket\"}");
                    return;
                }
                String value = "sub-" + sequence.incrementAndGet() + "-session";
                subSessions.add(value);
                exchange.getResponseHeaders().add("Set-Cookie", "sidB=" + value + "; Path=/sub");
                reply(exchange, 200, "{\"ok\":true}");
                return;
            }
            String sub = cookie(exchange, "sidB");
            if (sub == null || !subSessions.contains(sub)) {
                reply(exchange, 401, "{\"error\":\"login\"}");
                return;
            }
            reply(exchange, 200, "{\"value\":\"sub-data\"}");
            return;
        }
        if (path.equals("/cas")) {
            if (oaRejectsTickets || !pendingTickets.remove(query.replace("ticket=", ""))) {
                reply(exchange, 403, "{\"error\":\"bad ticket\"}");
                return;
            }
            String value = "oa-" + sequence.incrementAndGet() + "-session";
            oaSessions.add(value);
            exchange.getResponseHeaders().add("Set-Cookie", "sidA=" + value + "; Path=/");
            reply(exchange, 200, "{\"ok\":true}");
            return;
        }
        if (sid == null || !oaSessions.contains(sid)) {
            reply(exchange, 401, "{\"error\":\"login\"}");
            return;
        }
        reply(exchange, 200, "{\"value\":\"oa-data\"}");
    }

    private void serveApi(HttpExchange exchange) throws IOException {
        String header = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
        seen.add("api " + exchange.getRequestURI().getPath() + " tgc=" + cookie(exchange, "TGC"));
        if (apiAlwaysRejects || !header.startsWith("Bearer ") || !tokens.contains(header.substring(7))) {
            reply(exchange, 401, "{\"error\":\"token\"}");
            return;
        }
        reply(exchange, 200, "{\"value\":\"api-data\",\"echo\":\"" + header.substring(7) + "\"}");
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String authenticator() {
        return """
                conch: corp_sso
                version: 1.2.0
                driver: auth
                min_crab_version: 1.13.0
                title: 公司统一登录
                description: 公司 SSO 的登录
                credentials:
                  username: { type: text, label: 用户名 }
                  password: { type: secret, label: 密码 }
                auth:
                  origins: ["SSO"]
                  steps:
                    - request:
                        method: POST
                        url: SSO/login
                        form: { username: "{{credential.username}}", password: "{{credential.password}}" }
                    - assert: { status: 200 }
                    - extract: { access_token: { jsonpath: "$.token" } }
                  alive_check:
                    request:
                      method: GET
                      url: SSO/me
                      headers: { Authorization: "Bearer {{login.access_token}}" }
                  exports:
                    access_token: { description: 访问令牌 }
                """.replace("SSO", ssoUrl);
    }

    private String withOtp(String yaml) {
        return yaml.replace("    - assert: { status: 200 }\n    - extract: { access_token: { jsonpath: \"$.token\" } }", """
                    - assert: { status: 200 }
                    - extract: { need: { jsonpath: "$.otp", optional: true } }
                    - ask: { into: otp, kind: code, label: 短信验证码, timeout_s: 60 }
                    - request:
                        method: POST
                        url: SSO/login
                        form: { username: "{{credential.username}}", password: "{{credential.password}}", otp: "{{otp}}" }
                    - extract: { access_token: { jsonpath: "$.token" } }
                """.replace("SSO", ssoUrl).stripTrailing());
    }

    private String consumer(String name, String baseUrl, String auth, String request) {
        return """
                conch: NAME
                version: 1.0.0
                driver: http
                min_crab_version: 1.13.0
                base_url: BASE
                title: NAME
                description: 业务包
                auth:
                  use: corp_sso
                  min_version: 1.2.0
                AUTH
                actions:
                  run:
                    title: 取数据
                    description: 取数据
                    method: GET
                    idempotent: true
                    output:
                      value: { type: string, description: 值 }
                    examples:
                      - input: {}
                        output: { value: x }
                    steps:
                      - request: REQUEST
                      - extract: { value: { jsonpath: "$.value" } }
                """.replace("NAME", name).replace("BASE", baseUrl).replace("AUTH\n", auth).replace("REQUEST", request);
    }

    private String oaYaml(String name, String extra) {
        return consumer(name, oaUrl, extra + """
                  exchange:
                    - request: { method: GET, url: SSO/ticket }
                    - extract: { ticket: { jsonpath: "$.ticket" } }
                    - request: { method: GET, path: /cas, query: { ticket: "{{ticket}}" } }
                """.replace("SSO", ssoUrl), "{ method: GET, path: /data }");
    }

    private String subYaml() {
        return consumer("oa_sub", oaUrl + "/sub", """
                  exchange:
                    - request: { method: GET, url: SSO/ticket }
                    - extract: { ticket: { jsonpath: "$.ticket" } }
                    - request: { method: GET, path: /sub/cas, query: { ticket: "{{ticket}}" } }
                """.replace("SSO", ssoUrl), "{ method: GET, path: /sub/data }");
    }

    /** 换票步骤里把导出的令牌 set 进普通变量，再交给普通脚本，脚本看到什么就发给应用。 */
    private String peekYaml() {
        return consumer("oa_peek", oaUrl, """
                  exchange:
                    - request: { method: GET, url: SSO/ticket }
                    - extract: { ticket: { jsonpath: "$.ticket" } }
                    - set: { copied: "{{auth.access_token}}" }
                    - script:
                        into: peek
                        code: |
                          var copy = typeof vars.copied === 'undefined' ? 'hidden' : String(vars.copied);
                          var direct = (vars.auth && vars.auth.access_token) ? String(vars.auth.access_token) : 'hidden';
                          return copy + '/' + direct;
                    - request:
                        method: GET
                        path: /cas
                        query: { ticket: "{{ticket}}" }
                        headers: { X-Peek: "{{peek}}" }
                """.replace("SSO", ssoUrl), "{ method: GET, path: /data }");
    }

    /** 把导出的令牌 set 进普通变量，再交给普通脚本，并把它们都放进输出。 */
    private String copyYaml() {
        return apiYaml("api_copy")
                .replace("      value: { type: string, description: 值 }\n",
                        "      value: { type: string, description: 值 }\n"
                                + "      copied: { type: string, description: 复制 }\n"
                                + "      leaked: { type: string, description: 脚本看到的 }\n"
                                + "      direct: { type: string, description: 脚本直接读的 }\n")
                + "      - set: { copied: \"{{auth.access_token}}\" }\n"
                + "      - script:\n"
                + "          code: |\n"
                + "            var seen = typeof vars.copied === 'undefined' ? 'hidden' : String(vars.copied);\n"
                + "            var direct = (vars.auth && vars.auth.access_token) ? String(vars.auth.access_token) : 'hidden';\n"
                + "            return { leaked: seen, direct: direct };\n";
    }

    private String apiYaml(String name) {
        return consumer(name, apiUrl, "",
                "{ method: GET, path: /data, headers: { Authorization: \"Bearer {{auth.access_token}}\" } }");
    }

    @Test
    void twoPackagesShareOneAuthenticatorLoginAndNeitherHoldsThePassword() {
        try (Fixture fixture = new Fixture()) {
            ExecutionResult first = fixture.call(OA);
            assertThat(first.success()).as(String.valueOf(first.error())).isTrue();
            assertThat(fixture.value(API)).isEqualTo("api-data");
            assertThat(passwordLogins.get()).as("两个包共用授权器的一次登录").isEqualTo(1);
            assertThat(tickets.get()).isEqualTo(1);
            // 认证中心的 Cookie 只发给认证中心；oa 的会话 Cookie 不发给认证中心。
            assertThat(seen).filteredOn(line -> !line.startsWith("sso")).allMatch(line -> line.endsWith("tgc=null"));
            assertThat(seen).filteredOn(line -> line.startsWith("sso")).allMatch(line -> line.endsWith("sid=null"));
            // 导出的令牌是秘密：api 把它原样回显，输出里要被打码。
            ExecutionResult echoed = fixture.call(API);
            String flat = EngineTestSupport.flatten(echoed);
            for (String token : tokens) {
                assertThat(flat).doesNotContain(token);
            }
            assertThat(flat).doesNotContain(PASSWORD);
        }
    }

    @Test
    void anExportCopiedWithSetStaysSecretAndNeverReachesAPlainScript() {
        try (Fixture fixture = new Fixture()) {
            ExecutionResult result = fixture.call(COPY);
            assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
            Map<?, ?> data = (Map<?, ?>) result.data();
            assertThat(data.get("leaked")).as("set 出来的副本不进普通脚本").isEqualTo("hidden");
            assertThat(data.get("direct")).as("普通脚本读不到 auth").isEqualTo("hidden");
            assertThat(tokens).isNotEmpty();
            String flat = EngineTestSupport.flatten(result);
            for (String token : tokens) {
                assertThat(flat).as("输出里的副本要打码").doesNotContain(token);
            }
        }
    }

    @Test
    void anExportCopiedInsideExchangeNeverReachesAPlainScript() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(PEEK)).isEqualTo("oa-data");
            assertThat(tokens).isNotEmpty();
            // 换票步骤按登录步骤跑，但 auth.* 是别的包的秘密：副本和 auth 本身都不进普通脚本。
            assertThat(peeks).containsExactly("hidden/hidden");
        }
    }

    @Test
    void reExchangingOnePackageKeepsTheCookiesOfAnotherPackageOnTheSameHost() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(fixture.value(SUB)).isEqualTo("sub-data");
            assertThat(tickets.get()).isEqualTo(2);
            // /sub 下的应用会话失效：重新换票时只删 /sub 下的 Cookie，同主机根路径那个包的会话留着。
            subSessions.clear();
            assertThat(fixture.value(SUB)).isEqualTo("sub-data");
            assertThat(tickets.get()).isEqualTo(3);
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(tickets.get()).as("根路径的包不用重新换票").isEqualTo(3);
        }
    }

    @Test
    void invalidatingAConsumerAccountKeepsTheAuthenticatorSessionAndTheOtherPackages() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(fixture.value(API)).isEqualTo("api-data");
            // 控制台对业务包账号测试登录、删账号前都会先作废它：罐是授权器账号的，不能跟着清空。
            fixture.sessions.invalidate(OA);
            assertThat(fixture.sessions.loggedIn(SSO)).isTrue();
            assertThat(fixture.sessions.loggedIn(OA)).isFalse();
            assertThat(fixture.value(API)).isEqualTo("api-data");
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(passwordLogins.get()).as("授权器不用重新交密码").isEqualTo(1);
            assertThat(tickets.get()).as("只有被作废的包重新换票").isEqualTo(2);
        }
    }

    @Test
    void aParallelReadThatLosesBothSessionsLogsTheAuthenticatorInAgain() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(READS)).isEqualTo("oa-data");
            // 应用和认证中心的会话一起没了。只读能力先在共享锁下发现失效，再到独占锁下恢复：
            // 要先探授权器、重新登录它，而不是带着失效的授权器会话去换票。
            oaSessions.clear();
            ssoSessions.clear();
            assertThat(fixture.value(READS)).isEqualTo("oa-data");
            assertThat(passwordLogins.get()).isEqualTo(2);
            assertThat(fixture.sessions.authFailures(READS)).as("没有白白失败的换票").isZero();
        }
    }

    @Test
    void anExpiredExchangedSessionOnlyFetchesANewTicket() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(fixture.value(API)).isEqualTo("api-data");
            oaSessions.clear();
            int before = probes.get();
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(probes.get()).as("先探授权器").isEqualTo(before + 1);
            assertThat(passwordLogins.get()).as("授权器的会话还在，不重新交密码").isEqualTo(1);
            assertThat(tickets.get()).isEqualTo(2);
            assertThat(fixture.sessions.loggedIn(SSO)).isTrue();
            assertThat(fixture.value(API)).isEqualTo("api-data");
            assertThat(passwordLogins.get()).isEqualTo(1);
        }
    }

    @Test
    void aFailingExchangeNeverTouchesTheAuthenticatorOrItsFailureCount() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(API)).isEqualTo("api-data");
            oaRejectsTickets = true;
            for (int i = 0; i < 4; i++) {
                ExecutionResult failed = fixture.call(OA);
                assertThat(failed.success()).isFalse();
                assertThat(EngineTestSupport.flatten(failed)).doesNotContain("corp_sso").doesNotContain("zhang");
            }
            assertThat(fixture.sessions.authFailures(SSO)).as("换票失败不记到授权器账号上").isZero();
            assertThat(fixture.sessions.loginPaused(SSO)).isFalse();
            assertThat(fixture.sessions.loggedIn(SSO)).isTrue();
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(fixture.value(API)).as("别的包不受影响").isEqualTo("api-data");
        }
    }

    @Test
    void aDeadAuthenticatorSessionIsLoggedInAgainOnceForEveryone() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(fixture.value(API)).isEqualTo("api-data");
            ssoSessions.clear();
            tokens.clear();
            oaSessions.clear();
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(passwordLogins.get()).isEqualTo(2);
            assertThat(fixture.value(API)).as("api 用上新一代的令牌，不再登录").isEqualTo("api-data");
            assertThat(passwordLogins.get()).isEqualTo(2);
        }
    }

    @Test
    void aShortLivedExportIsRecoveredByOneReloginThenRateLimited() {
        EngineTestSupport.MutableClock clock = new EngineTestSupport.MutableClock();
        try (Fixture fixture = new Fixture(clock)) {
            assertThat(fixture.value(API)).isEqualTo("api-data");
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            // 令牌比认证中心的会话短命：探活通过，令牌却不被接受。
            tokens.clear();
            assertThat(fixture.value(API)).as("授权器重新登录一次，拿到新令牌").isEqualTo("api-data");
            assertThat(passwordLogins.get()).isEqualTo(2);

            // 规则把业务错误当成失效：窗口内不再让授权器重新登录，别的包也不被登出。
            apiAlwaysRejects = true;
            for (int i = 0; i < 3; i++) {
                ExecutionResult failed = fixture.call(API);
                assertThat(failed.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            }
            assertThat(passwordLogins.get()).as("同一个包的账号 10 分钟内只让授权器重新登录一次").isEqualTo(2);
            int ticketsBefore = tickets.get();
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(tickets.get()).as("oa 最多因为授权器换代重新换一次票").isLessThanOrEqualTo(ticketsBefore + 1);

            // 另一个同样写宽了的包：每个授权器账号在窗口内的总次数也有上限。
            for (int i = 0; i < 3; i++) {
                assertThat(fixture.call(API2).error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            }
            assertThat(passwordLogins.get()).isEqualTo(3);
            clock.advance(Duration.ofMinutes(11));
            assertThat(fixture.call(API).error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(passwordLogins.get()).as("窗口过去后可以再试一次").isEqualTo(4);
            assertThat(fixture.sessions.authFailures(SSO)).isZero();
        }
    }

    @Test
    void anExportAddedByAnAuthenticatorUpdateIsFilledByOneRelogin() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(passwordLogins.get()).isEqualTo(1);

            // 授权器更新：多导出一个变量。已有的登录是旧版本做的，里面没有它。
            fixture.install(authenticator()
                    .replace("version: 1.2.0", "version: 1.3.0")
                    .replace("- extract: { access_token: { jsonpath: \"$.token\" } }",
                            "- extract: { access_token: { jsonpath: \"$.token\" }, api_token: { jsonpath: \"$.token\" } }")
                    .replace("    access_token: { description: 访问令牌 }",
                            "    access_token: { description: 访问令牌 }\n    api_token: { description: 接口令牌 }"));
            fixture.install(consumer("api_new", apiUrl, "",
                    "{ method: GET, path: /data, headers: { Authorization: \"Bearer {{auth.api_token}}\" } }"));
            SessionKey fresh = new SessionKey(Origin.LOCAL, "api_new", "main");

            assertThat(fixture.value(fresh)).as("授权器重新登录一次，补上新变量").isEqualTo("api-data");
            assertThat(passwordLogins.get()).isEqualTo(2);
            assertThat(fixture.value(fresh)).isEqualTo("api-data");
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(passwordLogins.get()).as("补上以后不再重新登录").isEqualTo(2);
        }
    }

    @Test
    void aReadOnlyCallAlsoGetsTheExportAddedByAnUpdate() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            fixture.install(authenticator()
                    .replace("version: 1.2.0", "version: 1.3.0")
                    .replace("- extract: { access_token: { jsonpath: \"$.token\" } }",
                            "- extract: { access_token: { jsonpath: \"$.token\" }, api_token: { jsonpath: \"$.token\" } }")
                    .replace("    access_token: { description: 访问令牌 }",
                            "    access_token: { description: 访问令牌 }\n    api_token: { description: 接口令牌 }"));
            // 先用不涉及新变量的包把这个账号的会话热起来，只读调用才会走共享锁。
            String reads = consumer("api_reads", apiUrl, "  concurrent_reads: true\n",
                    "{ method: GET, path: /data, headers: { Authorization: \"Bearer {{auth.access_token}}\" } }")
                    .replace("idempotent: true", "idempotent: true\n    mcp: {expose: true, read_only: true}");
            fixture.install(reads);
            SessionKey key = new SessionKey(Origin.LOCAL, "api_reads", "main");
            assertThat(fixture.value(key)).isEqualTo("api-data");
            assertThat(passwordLogins.get()).isEqualTo(1);

            fixture.install(reads.replace("version: 1.0.0", "version: 1.0.1").replace("auth.access_token", "auth.api_token"));
            assertThat(fixture.value(key)).as("只读能力也要补上新变量").isEqualTo("api-data");
            assertThat(passwordLogins.get()).isEqualTo(2);
        }
    }

    @Test
    void anExportOnlyAnotherActionUsesDoesNotTriggerALogin() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            // 新版本多导出 api_token；已有的登录里没有它。
            fixture.install(authenticator()
                    .replace("version: 1.2.0", "version: 1.3.0")
                    .replace("- extract: { access_token: { jsonpath: \"$.token\" } }",
                            "- extract: { access_token: { jsonpath: \"$.token\" }, api_token: { jsonpath: \"$.token\" } }")
                    .replace("    access_token: { description: 访问令牌 }",
                            "    access_token: { description: 访问令牌 }\n    api_token: { description: 接口令牌 }"));
            String run = "{ method: GET, path: /data, headers: { Authorization: \"Bearer {{auth.access_token}}\" } }";
            String yaml = consumer("api_two", apiUrl, "", run);
            String action = yaml.substring(yaml.indexOf("  run:"));
            fixture.install(yaml + action.replace("  run:", "  other:").replace("auth.access_token", "auth.api_token"));
            SessionKey key = new SessionKey(Origin.LOCAL, "api_two", "main");

            assertThat(fixture.value(key)).isEqualTo("api-data");
            assertThat(passwordLogins.get()).as("这个能力没用到缺的变量，不重新登录").isEqualTo(1);
        }
    }

    @Test
    void anUnboundAccountIsRefusedWithoutNamingTheAuthenticator() {
        try (Fixture fixture = new Fixture()) {
            fixture.bindings.remove(OA);
            ExecutionResult refused = fixture.call(OA);
            assertThat(refused.error().code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
            assertThat(EngineTestSupport.flatten(refused)).doesNotContain("corp_sso");
            assertThat(seen).isEmpty();
        }
    }

    @Test
    void wrongPasswordCountsOnTheAuthenticatorAndPausesEveryPackage() {
        try (Fixture fixture = new Fixture()) {
            fixture.password = EngineTestSupport.WRONG_PASSWORD;
            for (int i = 0; i < 3; i++) {
                assertThat(fixture.call(i % 2 == 0 ? OA : API).error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            }
            assertThat(fixture.sessions.loginPaused(SSO)).isTrue();
            assertThat(fixture.sessions.loginPaused(OA)).isFalse();
            int before = failedLogins.get();
            ExecutionResult paused = fixture.call(API);
            assertThat(paused.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(paused.error().message()).isEqualTo(RuleEngine.LINK_PAUSED);
            assertThat(failedLogins.get()).as("暂停后不再向认证中心交密码").isEqualTo(before);
        }
    }

    @Test
    void oneCaptchaWaitServesEveryPackageAndIsAnsweredOnTheAuthenticatorAccount() {
        wantsOtp = true;
        try (Fixture fixture = new Fixture(true)) {
            ExecutionResult first = fixture.call(OA);
            assertThat(first.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(EngineTestSupport.flatten(first)).doesNotContain("corp_sso");
            int logins = (int) seen.stream().filter(line -> line.startsWith("sso POST /login")).count();
            ExecutionResult second = fixture.call(API);
            assertThat(second.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(seen.stream().filter(line -> line.startsWith("sso POST /login")).count())
                    .as("第二个包不再触发一次登录").isEqualTo(logins);
            assertThat(fixture.sessions.pending().current(SSO)).isNotNull();
            assertThat(fixture.sessions.pending().current(OA)).isNull();
            assertThat(fixture.sessions.pending().current(API)).isNull();

            ExecutionResult answered = fixture.engine.continueLogin(fixture.request(SSO, "run"), "246810");
            assertThat(answered.success()).as(String.valueOf(answered.error())).isTrue();
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(fixture.value(API)).isEqualTo("api-data");
            assertThat(passwordLogins.get()).isEqualTo(1);
        }
    }

    @Test
    void concurrentReadsExchangeOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(pool.submit(() -> fixture.value(READS)));
            }
            for (Future<String> call : calls) {
                assertThat(call.get(20, TimeUnit.SECONDS)).isEqualTo("oa-data");
            }
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(tickets.get()).isEqualTo(1);

            oaSessions.clear();
            calls.clear();
            for (int i = 0; i < 6; i++) {
                calls.add(pool.submit(() -> fixture.value(READS)));
            }
            for (Future<String> call : calls) {
                assertThat(call.get(20, TimeUnit.SECONDS)).isEqualTo("oa-data");
            }
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(tickets.get()).as("并行的只读调用发现失效，只重新换一次票").isEqualTo(2);
        }
    }

    @Test
    void testingTheAuthenticatorLoginUsesItsOwnPasswordAndPackagesFollow() {
        try (Fixture fixture = new Fixture()) {
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            ExecutionResult tested = fixture.engine.testLogin(fixture.request(SSO, "run"));
            assertThat(tested.success()).as(String.valueOf(tested.error())).isTrue();
            assertThat(passwordLogins.get()).isEqualTo(2);
            assertThat(fixture.value(OA)).isEqualTo("oa-data");
            assertThat(tickets.get()).isEqualTo(2);
            // 业务包自己测试登录：只重新换票，不清授权器的会话。
            ExecutionResult own = fixture.engine.testLogin(fixture.request(OA, "run"));
            assertThat(own.success()).as(String.valueOf(own.error())).isTrue();
            assertThat(passwordLogins.get()).isEqualTo(2);
            assertThat(tickets.get()).isEqualTo(3);
        }
    }

    private static final class AuthShared implements SharedSessions {
        private final Map<SessionKey, String> bound = new ConcurrentHashMap<>();
        private final Map<String, List<StoredCookie>> saved = new ConcurrentHashMap<>();

        @Override
        public String identityOf(SessionKey key) {
            return bound.get(key);
        }

        @Override
        public boolean borrows(SessionKey key) {
            return !key.equals(SSO) && bound.containsKey(key);
        }

        @Override
        public List<SessionKey> members(String identity) {
            ArrayList<SessionKey> members = new ArrayList<>();
            bound.forEach((key, id) -> {
                if (id.equals(identity)) {
                    members.add(key);
                }
            });
            return members;
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

    private final class Fixture implements AutoCloseable {
        private final SessionManager sessions;
        private final RuleEngine engine;
        private final Map<SessionKey, SessionKey> bindings = new ConcurrentHashMap<>();
        private final AuthShared shared = new AuthShared();
        private final ConchRegistry registry;
        private volatile ConchDoc authenticator;
        private volatile String password = PASSWORD;

        /** 授权器更新到新版本，或者装上一个新的业务包。 */
        private void install(String yaml) {
            ConchDoc doc = ConchParser.parse(yaml);
            assertThat(ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT)).errors())
                    .isEmpty();
            registry.put(doc, Origin.LOCAL);
            if (doc.conch().equals(SSO.conch())) {
                authenticator = doc;
            } else {
                SessionKey key = new SessionKey(Origin.LOCAL, doc.conch(), "main");
                bindings.put(key, SSO);
                shared.bound.put(key, "auth:sso");
            }
        }

        private Fixture() {
            this(java.time.Clock.systemUTC(), false);
        }

        private Fixture(boolean otp) {
            this(java.time.Clock.systemUTC(), otp);
        }

        private Fixture(java.time.Clock clock) {
            this(clock, false);
        }

        private Fixture(java.time.Clock clock, boolean otp) {
            EngineSettings settings = new EngineSettings(Duration.ofSeconds(20), Duration.ofMinutes(10), 3);
            sessions = new SessionManager(
                    new NopSessionPersistence(), new NopLoginSnapshotStore(), shared, settings, clock);
            registry = new ConchRegistry();
            ConchDoc auth = null;
            for (String yaml : List.of(
                    otp ? withOtp(authenticator()) : authenticator(),
                    oaYaml("oa", ""),
                    apiYaml("api"),
                    apiYaml("api2"),
                    copyYaml(),
                    peekYaml(),
                    subYaml(),
                    oaYaml("oa_reads", "  concurrent_reads: true\n")
                            .replace("idempotent: true", "idempotent: true\n    mcp: {expose: true, read_only: true}"))) {
                ConchDoc doc = ConchParser.parse(yaml);
                assertThat(ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT)).errors())
                        .isEmpty();
                registry.put(doc, Origin.LOCAL);
                if (auth == null) {
                    auth = doc;
                }
            }
            authenticator = auth;
            shared.bound.put(SSO, "auth:sso");
            for (SessionKey key : List.of(OA, API, API2, COPY, PEEK, SUB, READS)) {
                bindings.put(key, SSO);
                shared.bound.put(key, "auth:sso");
            }
            engine = new RuleEngine(registry, sessions, settings);
            engine.useAuthBindings((key, conch, site) -> bindings.containsKey(key)
                    ? new AuthBindings.BoundAuth(bindings.get(key), authenticator,
                            Map.of("username", "zhang", "password", password), Map.of(),
                            key.equals(SUB) ? Set.of(oaUrl) : key.equals(OA) ? Set.of(oaUrl + "/sub") : Set.of())
                    : null);
        }

        private ExecutionRequest request(SessionKey key, String action) {
            Map<String, String> credentials = key.equals(SSO)
                    ? Map.of("username", "zhang", "password", password) : Map.of();
            return EngineTestSupport.request(key.origin(), key.conch(), action, key.account(), Map.of(),
                    new EngineTestSupport.MapAccount(credentials, Map.of()));
        }

        private ExecutionResult call(SessionKey key) {
            return engine.execute(request(key, "run"));
        }

        private String value(SessionKey key) {
            ExecutionResult result = call(key);
            assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
            return String.valueOf(((Map<?, ?>) result.data()).get("value"));
        }

        @Override
        public void close() {
            sessions.close();
        }
    }
}
