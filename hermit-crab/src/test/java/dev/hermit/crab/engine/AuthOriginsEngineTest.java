package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

/**
 * auth.origins：登录步骤访问单独主机上的认证中心或门户。应用在 127.0.0.1，认证中心在 localhost，
 * 两个主机名不同，Cookie 互不可见；「门户」和应用同主机不同端口，用来模拟共用上级域的 Cookie。
 */
class AuthOriginsEngineTest {
    private HttpServer app;
    private HttpServer sso;
    private HttpServer portal;
    private HttpServer stranger;
    private ExecutorService pool;
    private String appUrl;
    private String ssoUrl;
    private String portalUrl;
    private String strangerUrl;

    private final AtomicInteger passwordLogins = new AtomicInteger();
    private final AtomicInteger tickets = new AtomicInteger();
    private final AtomicInteger strangerHits = new AtomicInteger();
    private final List<String> ssoSeen = new CopyOnWriteArrayList<>();
    private final List<String> appSeen = new CopyOnWriteArrayList<>();
    private volatile String appSession = "none";
    private volatile String ssoSession = "none";
    /** 认证中心登录成功后的回跳状态码。 */
    private volatile int ssoRedirect = 302;
    /** 应用首页把没登录的请求送去哪里。 */
    private volatile String loginHost;

    @BeforeEach
    void start() throws IOException {
        pool = Executors.newCachedThreadPool();
        app = server("127.0.0.1", this::serveApp);
        sso = server("localhost", this::serveSso);
        portal = server("127.0.0.1", this::servePortal);
        stranger = server("localhost", exchange -> {
            strangerHits.incrementAndGet();
            reply(exchange, 200, "text/plain", "stranger");
        });
        appUrl = "http://127.0.0.1:" + app.getAddress().getPort();
        ssoUrl = "http://localhost:" + sso.getAddress().getPort();
        portalUrl = "http://127.0.0.1:" + portal.getAddress().getPort();
        strangerUrl = "http://localhost:" + stranger.getAddress().getPort();
        loginHost = ssoUrl;
    }

    private HttpServer server(String host, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, 0), 0);
        server.setExecutor(pool);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    @AfterEach
    void stop() {
        for (HttpServer server : List.of(app, sso, portal, stranger)) {
            server.stop(0);
        }
        pool.shutdownNow();
    }

    private void serveApp(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String cookie = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        appSeen.add(exchange.getRequestMethod() + " " + path + " cookie=" + cookie
                + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                + " probe=" + exchange.getRequestHeaders().getFirst("X-Probe") + " body=" + body);
        boolean loggedIn = cookie.contains("sid=" + appSession) || cookie.contains("portal=" + ssoSession);
        if (path.equals("/cas") && query != null && query.equals("ticket=ST-" + tickets.get())) {
            appSession = "app" + tickets.get();
            exchange.getResponseHeaders().add("Set-Cookie", "sid=" + appSession + "; Path=/");
            redirect(exchange, 302, "/home");
            return;
        }
        if (path.equals("/acs")) {
            if (body.contains("SAMLResponse=assert-" + ssoSession)) {
                appSession = "saml";
                exchange.getResponseHeaders().add("Set-Cookie", "sid=saml; Path=/");
                reply(exchange, 200, "text/html", "welcome");
            } else {
                reply(exchange, 403, "text/plain", "bad assertion");
            }
            return;
        }
        if (path.equals("/api")) {
            boolean bearer = ("Bearer tok-" + ssoSession).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, bearer ? 200 : 401, "application/json", bearer ? "{\"value\":\"ok\"}" : "{}");
            return;
        }
        if (path.equals("/mail")) {
            redirect(exchange, 302, "mailto:help@example.com");
            return;
        }
        if (path.equals("/leave")) {
            redirect(exchange, 302, strangerUrl + "/elsewhere");
            return;
        }
        if (!loggedIn) {
            redirect(exchange, 302, loginHost + "/login?service=app");
            return;
        }
        reply(exchange, 200, "application/json", "{\"value\":\"ok\"}");
    }

    private void serveSso(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String cookie = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        ssoSeen.add(exchange.getRequestMethod() + " " + path + " cookie=" + cookie
                + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                + " probe=" + exchange.getRequestHeaders().getFirst("X-Probe") + " body=" + body);
        boolean post = exchange.getRequestMethod().equals("POST");
        boolean rightPassword = body.contains("password=S3cret%21pwd") || body.contains("\"S3cret!pwd\"");
        if (path.equals("/token") && post) {
            if (rightPassword) {
                ssoSession = "t" + passwordLogins.incrementAndGet();
            }
            reply(exchange, rightPassword ? 200 : 401, "application/json",
                    rightPassword ? "{\"token\":\"tok-" + ssoSession + "\"}" : "{}");
            return;
        }
        if (path.equals("/idp") && post) {
            if (!rightPassword) {
                reply(exchange, 401, "text/html", "denied");
                return;
            }
            ssoSession = "i" + passwordLogins.incrementAndGet();
            reply(exchange, 200, "text/html", "<form method=post action=\"" + appUrl + "/acs\">"
                    + "<input type=hidden name=SAMLResponse value=\"assert-" + ssoSession + "\"></form>");
            return;
        }
        if (!path.equals("/login")) {
            reply(exchange, 404, "text/plain", "");
            return;
        }
        if (post) {
            if (!rightPassword || !body.contains("execution=e1")) {
                reply(exchange, 401, "text/html", "<input name=\"password\"> wrong");
                return;
            }
            ssoSession = "tgc" + passwordLogins.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "TGC=" + ssoSession + "; Path=/");
            redirect(exchange, ssoRedirect, appUrl + "/cas?ticket=ST-" + tickets.incrementAndGet());
            return;
        }
        if (cookie.contains("TGC=" + ssoSession)) {
            redirect(exchange, 302, appUrl + "/cas?ticket=ST-" + tickets.incrementAndGet());
            return;
        }
        reply(exchange, 200, "text/html",
                "<form><input name=\"password\"><input name=execution value=\"e1\"></form>");
    }

    private void servePortal(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (exchange.getRequestURI().getPath().equals("/portal/login") && body.contains("password=S3cret%21pwd")) {
            ssoSession = "p" + passwordLogins.incrementAndGet();
            // 没写 Domain：按主机发。应用和门户同主机不同端口，相当于两个子域共用上级域的 Cookie。
            exchange.getResponseHeaders().add("Set-Cookie", "portal=" + ssoSession + "; Path=/");
            redirect(exchange, 302, appUrl + "/home");
            return;
        }
        reply(exchange, 401, "text/plain", "denied");
    }

    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void ticketInTheRedirectLogsInOnceAndSurvivesAnExpiredAppSession() {
        try (Fixture fixture = new Fixture(cas("- " + ssoUrl))) {
            ExecutionResult first = fixture.call("run");
            assertThat(first.success()).as(String.valueOf(first.error())).isTrue();
            assertThat(passwordLogins.get()).isEqualTo(1);
            // 应用的 Cookie 不发给认证中心，认证中心的 Cookie 不发给应用；带凭证的规则请求头不过源。
            assertThat(ssoSeen).noneMatch(line -> line.contains("sid=")).noneMatch(line -> line.contains("probe=S3cret"));
            assertThat(appSeen).noneMatch(line -> line.contains("TGC="));
            assertThat(appSeen.get(0)).contains("probe=S3cret!pwd");
            assertThat(appSeen).filteredOn(line -> line.startsWith("GET /cas")).allMatch(line -> line.contains("probe=null"));

            // 应用会话过期：能力请求被送去认证中心，算会话失效，重新登录后重试成功。
            // 没绑定身份的账号重新登录前清空 Cookie 罐，所以认证中心上也重新交一次密码。
            appSession = "expired";
            int data = (int) appSeen.stream().filter(line -> line.startsWith("GET /data")).count();
            ExecutionResult second = fixture.call("run");
            assertThat(second.success()).as(String.valueOf(second.error())).isTrue();
            assertThat(passwordLogins.get()).isEqualTo(2);
            assertThat(tickets.get()).isEqualTo(2);
            assertThat(appSeen.stream().filter(line -> line.startsWith("GET /data")).count())
                    .as("失效的那次加重试的那次").isEqualTo(data + 2);
        }
    }

    @Test
    void configuredOriginWorksAndAnUnsetOneIsRefused() {
        String yaml = cas("- \"{{config.sso_url}}\"")
                .replace("url: " + ssoUrl + "/login", "url: \"{{config.sso_url}}/login\"");
        try (Fixture fixture = new Fixture(yaml)) {
            fixture.config = Map.of("base_url", appUrl, "sso_url", ssoUrl);
            ExecutionResult ok = fixture.call("run");
            assertThat(ok.success()).as(String.valueOf(ok.error())).isTrue();
        }
        ssoSeen.clear();
        appSession = "expired";
        try (Fixture fixture = new Fixture(yaml)) {
            fixture.config = Map.of("base_url", appUrl, "sso_url", "");
            ExecutionResult refused = fixture.call("run");
            assertThat(refused.success()).isFalse();
            assertThat(ssoSeen).as("没填认证地址时不跟随过去，也不提交凭证").isEmpty();
        }
    }

    @Test
    void autoSubmittedFormCarriesTheAssertionBack() {
        String steps = """
                - request:
                    method: POST
                    url: %s/idp
                    form: {username: "{{credential.username}}", password: "{{credential.password}}"}
                - extract:
                    assertion: {css: "input[name=SAMLResponse]", attr: value}
                - request: {method: POST, path: /acs, form: {SAMLResponse: "{{assertion}}"}}
                - assert: {cookie_exists: sid}
                """.formatted(ssoUrl);
        try (Fixture fixture = new Fixture(conch("- " + ssoUrl, steps, ""))) {
            ExecutionResult result = fixture.call("run");
            assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
            assertThat(appSeen).anyMatch(line -> line.startsWith("POST /acs") && line.contains("assert-i1"));
            assertThat(result.toString()).doesNotContain("assert-i1");
        }
    }

    @Test
    void cookieSharedWithThePortalHostReachesTheApp() {
        String steps = """
                - request:
                    method: POST
                    url: %s/portal/login
                    form: {username: "{{credential.username}}", password: "{{credential.password}}"}
                    follow_redirects: true
                - assert: {status: 200, cookie_exists: portal}
                """.formatted(portalUrl);
        try (Fixture fixture = new Fixture(conch("- " + portalUrl, steps, ""))) {
            ExecutionResult result = fixture.call("run");
            assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
            assertThat(appSeen).anyMatch(line -> line.startsWith("GET /home") && line.contains("portal=p1"));
        }
    }

    @Test
    void tokenFromTheAuthOriginIsUsedAsARequestHeader() {
        String steps = """
                - request:
                    method: POST
                    url: %s/token
                    json: {username: "{{credential.username}}", password: "{{credential.password}}"}
                - extract:
                    token: {jsonpath: "$.token"}
                """.formatted(ssoUrl);
        String yaml = conch("- " + ssoUrl, steps, "")
                .replace("path: /data}", "path: /api, headers: {Authorization: \"Bearer {{login.token}}\"}}");
        try (Fixture fixture = new Fixture(yaml)) {
            ExecutionResult result = fixture.call("run");
            assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
            assertThat(result.toString()).doesNotContain("tok-t1");
        }
    }

    @Test
    void capabilityStepsCannotReachTheAuthOrigin() {
        try (Fixture fixture = new Fixture(cas("- " + ssoUrl))) {
            assertThat(fixture.call("run").success()).isTrue();
            int before = ssoSeen.size();
            ExecutionResult result = fixture.call("escape", Map.of("target", ssoUrl + "/login"));
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().message()).contains("认证地址只能在登录步骤里访问");
            assertThat(ssoSeen).hasSize(before);
        }
    }

    @Test
    void redirectWithoutAHostIsAnOrdinaryResponse() {
        // Location 是 mailto: 这种没有主机的地址：不是认证地址，也不该变成内部错误。
        try (Fixture fixture = new Fixture(cas("- " + ssoUrl))) {
            ExecutionResult result = fixture.call("escape", Map.of("target", appUrl + "/mail"));
            // 和没写 auth.origins 的包一样：没跟随的 302 按上游状态码报错。
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(result.error().message()).contains("302");
            assertThat(tickets.get()).as("没有被当成会话失效").isEqualTo(1);
        }
    }

    @Test
    void capabilityThatFollowsRedirectsStillStopsAtTheAuthOrigin() {
        String yaml = cas("- " + ssoUrl)
                .replace("- request: {method: GET, path: /data}",
                        "- request: {method: GET, path: /data, follow_redirects: true}");
        try (Fixture fixture = new Fixture(yaml)) {
            assertThat(fixture.call("run").success()).isTrue();
            appSession = "expired";
            int before = ssoSeen.size();
            ExecutionResult again = fixture.call("run");
            assertThat(again.success()).as(String.valueOf(again.error())).isTrue();
            assertThat(tickets.get()).isEqualTo(2);
            // 认证中心只收到重新登录时的那一次登录页请求，能力请求没有跟过去。
            assertThat(ssoSeen.subList(before, ssoSeen.size()))
                    .filteredOn(line -> line.startsWith("GET /login")).hasSize(1);
        }
    }

    @Test
    void undeclaredOriginIsNeitherRequestedNorFollowed() {
        // 没声明认证地址：应用把登录送去认证中心，登录步骤停在 302。
        try (Fixture fixture = new Fixture(cas("[]").replace("min_crab_version: 1.11.0", "min_crab_version: 1.9.0"))) {
            ExecutionResult result = fixture.call("run");
            assertThat(result.success()).isFalse();
            assertThat(ssoSeen).isEmpty();
        }
        // 声明了认证中心，登录中途被送去别的主机：不跟随。
        String steps = """
                - request: {method: GET, path: /leave, follow_redirects: true}
                - assert: {status: 302}
                """;
        try (Fixture fixture = new Fixture(conch("- " + ssoUrl, steps, ""))) {
            fixture.call("run");
            assertThat(strangerHits.get()).isZero();
        }
    }

    @Test
    void crossOriginRedirectThatWouldResendTheBodyIsNotFollowed() {
        ssoRedirect = 307;
        try (Fixture fixture = new Fixture(cas("- " + ssoUrl))) {
            ExecutionResult result = fixture.call("run");
            assertThat(result.success()).isFalse();
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(appSeen).as("含密码的表单没有重发给应用")
                    .noneMatch(line -> line.contains("S3cret")  && line.contains("body=username"))
                    .noneMatch(line -> line.startsWith("POST"));
        }
    }

    @Test
    void cookiesOfTheAuthOriginCannotBeReadByName() {
        String yaml = cas("- " + ssoUrl).replace("cookie_exists: sid", "cookie_exists: TGC");
        try (Fixture fixture = new Fixture(yaml)) {
            ExecutionResult result = fixture.call("run");
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
        }
        String named = cas("- " + ssoUrl)
                .replace("auth:\n", "auth:\n  cookies: {tgc: TGC}\n")
                .replace("path: /data}", "path: /data, query: {t: \"{{login.tgc}}\"}}");
        try (Fixture fixture = new Fixture(named)) {
            ExecutionResult result = fixture.call("run");
            assertThat(result.success()).isFalse();
            assertThat(appSeen).noneMatch(line -> line.contains("tgc1"));
        }
    }

    @Test
    void aliveCheckSentToTheAuthOriginMeansTheSessionIsGone() throws Exception {
        String yaml = cas("- " + ssoUrl)
                .replace("auth:\n", "auth:\n  alive_check:\n    request: {method: GET, path: /ping}\n");
        try (Fixture fixture = new Fixture(yaml, Duration.ofMillis(1))) {
            assertThat(fixture.call("run").success()).isTrue();
            appSession = "expired";
            Thread.sleep(20);
            int before = (int) appSeen.stream().filter(line -> line.startsWith("GET /data")).count();
            ExecutionResult again = fixture.call("run");
            assertThat(again.success()).as(String.valueOf(again.error())).isTrue();
            assertThat(tickets.get()).isEqualTo(2);
            assertThat(appSeen.stream().filter(line -> line.startsWith("GET /data")).count())
                    .as("探活发现失效后先登录，能力请求只发一次").isEqualTo(before + 1);
        }
    }

    /** CAS 式登录：先访问应用，被送到认证中心；出现密码框才提交，认证中心带票据跳回应用。 */
    private String cas(String origins) {
        String steps = """
                - request: {method: GET, path: /home, follow_redirects: true, headers: {X-Probe: "{{credential.password}}"}}
                - if:
                    contains: 'name="password"'
                    then:
                      - extract:
                          execution: {css: "input[name=execution]", attr: value}
                      - request:
                          method: POST
                          url: %s/login
                          form:
                            username: "{{credential.username}}"
                            password: "{{credential.password}}"
                            execution: "{{execution}}"
                          follow_redirects: true
                - assert: {status: 200, cookie_exists: sid}
                """.formatted(ssoUrl);
        return conch(origins, steps, """
                  sso_url: {type: url, label: 认证中心}
                """);
    }

    private static String conch(String origins, String steps, String config) {
        String originBlock = origins.startsWith("[") ? "origins: " + origins : "origins:\n    " + origins;
        return """
                conch: sso_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.11.0
                base_url: http://127.0.0.1:9
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                config:
                  base_url: {type: url, label: 应用地址}
                %s
                auth:
                  %s
                  steps:
                %s
                actions:
                  run:
                    title: 能力
                    description: 说明
                    idempotent: true
                    output:
                      value: {type: string, description: v}
                    examples:
                      - input: {}
                        output: {}
                    notes: 注意
                    steps:
                      - request: {method: GET, path: /data}
                      - extract:
                          value: {jsonpath: "$.value"}
                  escape:
                    title: 越界
                    description: 说明
                    input:
                      target: {type: string, description: 地址}
                    output:
                      value: {type: string, description: v}
                    examples:
                      - input: {target: x}
                        output: {}
                    notes: 注意
                    steps:
                      - request: {method: GET, url: "{{input.target}}"}
                      - set: {value: done}
                """.formatted(config.stripTrailing(), originBlock, steps.stripIndent().indent(4).stripTrailing());
    }

    private final class Fixture implements AutoCloseable {
        private final SessionManager sessions;
        private final RuleEngine engine;
        private Map<String, String> config = Map.of("base_url", appUrl);

        private Fixture(String yaml) {
            this(yaml, Duration.ofMinutes(10));
        }

        private Fixture(String yaml, Duration idle) {
            ConchDoc doc = ConchParser.parse(yaml);
            ValidationResult checked = ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT));
            assertThat(checked.errors()).isEmpty();
            EngineSettings settings = new EngineSettings(Duration.ofSeconds(20), idle, 3);
            sessions = new SessionManager(new NopSessionPersistence(), settings, Clock.systemUTC());
            ConchRegistry registry = new ConchRegistry();
            registry.put(doc, Origin.LOCAL);
            engine = new RuleEngine(registry, sessions, settings);
        }

        private ExecutionResult call(String action) {
            return call(action, Map.of());
        }

        private ExecutionResult call(String action, Map<String, Object> input) {
            return engine.execute(EngineTestSupport.request(
                    Origin.LOCAL, "sso_conch", action, "alice", input,
                    new EngineTestSupport.MapAccount(
                            Map.of("username", "alice", "password", EngineTestSupport.PASSWORD), config)));
        }

        @Override
        public void close() {
            sessions.close();
        }
    }
}
