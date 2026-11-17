package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.EncryptedLoginSnapshotStore;
import dev.hermit.crab.store.EncryptedSessionPersistence;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.SecretChecks;

/**
 * 登录快照按账号整表替换。令牌是测试里造的，不是真实凭证。
 */
class LoginSnapshotEngineTest {
    private static final String SHORT = "ab";

    @TempDir
    java.nio.file.Path temp;

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger failUses = new AtomicInteger();
    private final CopyOnWriteArrayList<String> paths = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> issued = new CopyOnWriteArrayList<>();
    private CrabDatabase database;
    private EncryptedSessionPersistence cookies;
    private EncryptedLoginSnapshotStore snapshots;
    private KnownSecrets secrets;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "login-snapshot-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        database = new CrabDatabase(new CrabPaths(temp.resolve("crab")));
        CredentialCipher cipher = new CredentialCipher(new byte[32]);
        cookies = new EncryptedSessionPersistence(database.dataSource(), cipher);
        snapshots = new EncryptedLoginSnapshotStore(database.dataSource(), cipher);
        secrets = KnownSecrets.empty();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void bodyTokenSurvivesReloadAndStaysInsideOneAccount() throws Exception {
        Set<String> before = Set.copyOf(secrets.copy());
        ConchDoc doc = doc(auth(loginSteps("/login", true)), useSteps(), false);
        try (SessionManager sessions = open(EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = registry(doc);
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults(), secrets);
            ExecutionResult first = engine.execute(call("alice", Origin.OFFICIAL));
            assertThat(first.success()).isTrue();
            assertThat(logins).hasValue(1);
            assertThat(count("SELECT COUNT(*) FROM session_login")).isEqualTo(1);
            String token = issued.get(0);
            assertThat(paths).containsExactly("/use/" + token + "/sid-cookie-value");

            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            assertThat(logins).hasValue(1);

            assertThat(engine.execute(call("bob", Origin.OFFICIAL)).success()).isTrue();
            assertThat(engine.execute(call("alice", Origin.LOCAL)).success()).isTrue();
            assertThat(logins).hasValue(3);
            assertThat(issued).doesNotHaveDuplicates();
            assertThat(paths).containsExactly(
                    "/use/" + issued.get(0) + "/sid-cookie-value",
                    "/use/" + issued.get(0) + "/sid-cookie-value",
                    "/use/" + issued.get(1) + "/sid-cookie-value",
                    "/use/" + issued.get(2) + "/sid-cookie-value");
        }

        paths.clear();
        try (SessionManager reloaded = open(EngineSettings.defaults(), Clock.systemUTC())) {
            RuleEngine engine = new RuleEngine(registry(doc), reloaded, EngineSettings.defaults(), secrets);
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            assertThat(logins).hasValue(3);
            assertThat(paths).containsExactly("/use/" + issued.get(0) + "/sid-cookie-value");
        }
        assertThat(secrets.copy()).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM config_audit")).isZero();
    }

    @Test
    void cookieNameReadsTheJarWithoutAnotherLogin() throws Exception {
        try (SessionManager sessions = open(EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = registry(doc(auth("""
                    - request: { method: POST, path: /login-cookie, form: { username: "{{credential.username}}" } }
                    - assert: { cookie_exists: sid }
                    """), """
                    - request: { method: GET, path: "/jar/{{login.sid}}" }
                    """, false));
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults(), secrets);
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            assertThat(logins).hasValue(1);
            assertThat(count("SELECT COUNT(*) FROM session_login")).isZero();
            assertThat(paths).containsExactly("/jar/sid-cookie-value", "/jar/sid-cookie-value");
        }
    }

    @Test
    void cookieAliasReadsTheHyphenatedName() throws Exception {
        try (SessionManager sessions = open(EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = registry(doc("""
                    steps:
                      - request: { method: POST, path: /login-alias, form: { username: "{{credential.username}}" } }
                      - assert: { cookie_exists: XSRF-TOKEN }
                    cookies:
                      xsrf: XSRF-TOKEN
                    """, """
                    - request: { method: GET, path: "/alias/{{login.xsrf}}" }
                    """, false));
            RuleEngine engine = new RuleEngine(registry, sessions);
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            assertThat(logins).hasValue(1);
            assertThat(paths).containsExactly("/alias/csrf-header-value", "/alias/csrf-header-value");
        }
    }

    @Test
    void emptySnapshotLogsInOnceWhenTheBodyTokenIsMissing() throws Exception {
        try (SessionManager sessions = open(EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(doc(auth("""
                    - request: { method: POST, path: /login-cookie, form: { username: "{{credential.username}}" } }
                    - assert: { cookie_exists: sid }
                    """), """
                    - request: { method: GET, path: "/jar/{{login.sid}}" }
                    """, false));
            RuleEngine engine = new RuleEngine(registry, sessions);
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            assertThat(count("SELECT COUNT(*) FROM session_login")).isZero();

            registry.put(doc(auth(loginSteps("/login", true)), useSteps(), false));
            ExecutionResult filled = engine.execute(call("alice", Origin.OFFICIAL));
            assertThat(filled.success()).isTrue();
            assertThat(logins).hasValue(2);
            assertThat(paths.get(paths.size() - 1))
                    .isEqualTo("/use/" + issued.get(issued.size() - 1) + "/sid-cookie-value");
        }
    }

    @Test
    void idempotentReplaySendsTheNewTokenAndStopsAfterASecondLoss() throws Exception {
        try (SessionManager sessions = open(EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = registry(doc(auth(loginSteps("/login", true)), useSteps(), true));
            RuleEngine engine = new RuleEngine(registry, sessions);
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            failUses.set(1);
            ExecutionResult replayed = engine.execute(call("alice", Origin.OFFICIAL));
            assertThat(replayed.success()).isTrue();
            assertThat(logins).hasValue(2);
            assertThat(paths).containsExactly(
                    "/use/" + issued.get(0) + "/sid-cookie-value",
                    "/use/" + issued.get(0) + "/sid-cookie-value",
                    "/use/" + issued.get(1) + "/sid-cookie-value");

            failUses.set(2);
            ExecutionResult expired = engine.execute(call("alice", Origin.OFFICIAL));
            assertThat(expired.success()).isFalse();
            assertThat(expired.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(count("SELECT COUNT(*) FROM session_login")).isZero();
        }
    }

    @Test
    void nonIdempotentLossDeletesTheSnapshotWithoutReplay() throws Exception {
        try (SessionManager sessions = open(EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = registry(doc(auth(loginSteps("/login", true)), useSteps(), false));
            RuleEngine engine = new RuleEngine(registry, sessions);
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            failUses.set(1);
            ExecutionResult expired = engine.execute(call("alice", Origin.OFFICIAL));
            assertThat(expired.success()).isFalse();
            assertThat(expired.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(logins).hasValue(1);
            assertThat(paths).containsExactly(
                    "/use/" + issued.get(0) + "/sid-cookie-value",
                    "/use/" + issued.get(0) + "/sid-cookie-value");
            assertThat(count("SELECT COUNT(*) FROM session_login")).isZero();
        }
    }

    @Test
    void aliveCheckReadsThePreviousSnapshot() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(30), Duration.ofMinutes(1), 3);
        try (SessionManager sessions = open(settings, clock)) {
            String auth = auth(loginSteps("/login", true)) + "\n" + """
                    alive_check:
                      request: { method: GET, path: "/alive/{{login.token}}" }
                      expect_not_redirect: /login
                    """.stripIndent().stripTrailing();
            ConchRegistry registry = registry(doc(auth, useSteps(), false));
            RuleEngine engine = new RuleEngine(registry, sessions, settings, secrets);
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            clock.advance(Duration.ofMinutes(2));
            assertThat(engine.execute(call("alice", Origin.OFFICIAL)).success()).isTrue();
            assertThat(logins).hasValue(1);
            assertThat(paths).contains("/alive/" + issued.get(0), "/use/" + issued.get(0) + "/sid-cookie-value");
        }
    }

    @Test
    void masksSnapshotValuesOnOfficialCallsAndInDebugTraces() throws Exception {
        try (SessionManager sessions = open(EngineSettings.defaults(), Clock.systemUTC())) {
            ConchDoc shown = doc(auth(loginSteps("/login", true)), """
                    - request: { method: GET, path: "/echo/{{login.token}}" }
                    - extract:
                        echo: { jsonpath: $.echo }
                        encoded: { jsonpath: $.encoded }
                        brief: { jsonpath: $.brief }
                    - script:
                        into: marker
                        code: "return vars.login === undefined ? 'absent' : 'present';"
                    """, false, """
                      echo: { type: string, description: 回显 }
                      encoded: { type: string, description: 编码 }
                      brief: { type: string, description: 短值 }
                      marker: { type: string, description: 脚本 }
                    """, "{ echo: ok, encoded: ok, brief: ab, marker: absent }");
            ConchRegistry registry = registry(shown);
            ExecutionResult result = new RuleEngine(registry, sessions, EngineSettings.defaults(), secrets)
                    .execute(call("alice", Origin.OFFICIAL));
            assertThat(result.success()).isTrue();
            String token = issued.get(0);
            String flat = EngineTestSupport.flatten(result.data());
            SecretChecks.absent(flat, token);
            SecretChecks.absent(flat, URLEncoder.encode(token, StandardCharsets.UTF_8));
            assertThat(flat).contains(SHORT).contains("absent");
            SecretChecks.absent(result.stepLogs().toString(), token);
            SecretChecks.absent(result.stepLogs().toString(), URLEncoder.encode(token, StandardCharsets.UTF_8));

            CapturingSink sink = new CapturingSink();
            RuleEngine local = new RuleEngine(registry, sessions, EngineSettings.defaults(), secrets);
            local.bindDevTraceSink(sink);
            ExecutionResult traced = local.execute(call("alice", Origin.LOCAL));
            assertThat(traced.success()).isTrue();
            assertThat(sink.opens).isEqualTo(1);
            SecretChecks.absent(sink.body, issued.get(1));
            SecretChecks.absent(sink.body, URLEncoder.encode(issued.get(1), StandardCharsets.UTF_8));

            ConchRegistry boom = registry(doc(auth(loginSteps("/login", true)), """
                    - request: { method: GET, path: "/boom/{{login.token}}" }
                    """, false));
            ExecutionResult failed = new RuleEngine(boom, sessions)
                    .execute(call("carol", Origin.OFFICIAL));
            assertThat(failed.success()).isFalse();
            String boomToken = issued.get(issued.size() - 1);
            SecretChecks.absent(failed.error().message(), boomToken);
            SecretChecks.absent(failed.stepLogs().toString(), boomToken);
            SecretChecks.absent(failed.stepLogs().toString(), URLEncoder.encode(boomToken, StandardCharsets.UTF_8));
        }
        assertThat(secrets.copy()).isEmpty();
    }

    @Test
    void oversizedSnapshotIsNotStored() throws Exception {
        try (SessionManager sessions = open(EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = registry(doc(auth("""
                    - request: { method: POST, path: /huge, form: { username: "{{credential.username}}" } }
                    - extract:
                        token: { jsonpath: $.token }
                    """), """
                    - request: { method: GET, path: /after }
                    """, false));
            ExecutionResult result = new RuleEngine(registry, sessions).execute(call("alice", Origin.OFFICIAL));
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(result.error().message()).isEqualTo("登录变量超过 32KB");
            assertThat(result.error().message()).doesNotContain("aaaaa");
            assertThat(count("SELECT COUNT(*) FROM session_login")).isZero();
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void missingLoginVariableDoesNotRecurseAndPauseStillApplies() throws Exception {
        EngineSettings settings = new EngineSettings(Duration.ofSeconds(30), Duration.ofMinutes(10), 1);
        try (SessionManager sessions = open(settings, Clock.systemUTC())) {
            ConchRegistry registry = registry(doc(auth("""
                    - request:
                        method: POST
                        path: "/login/{{login.token}}"
                        form: { username: "{{credential.username}}" }
                    - extract:
                        token: { jsonpath: $.token }
                    """), """
                    - request: { method: GET, path: /after }
                    """, false));
            RuleEngine engine = new RuleEngine(registry, sessions, settings, secrets);
            ExecutionResult missing = engine.execute(call("alice", Origin.OFFICIAL));
            assertThat(missing.success()).isFalse();
            assertThat(missing.error().message()).isEqualTo("登录没有建立变量 token");
            assertThat(logins).hasValue(0);

            registry.put(doc(auth("""
                    - request: { method: POST, path: /deny, form: { username: "{{credential.username}}" } }
                    - assert: { cookie_exists: sid }
                    """), """
                    - request: { method: GET, path: "/jar/{{login.sid}}" }
                    """, false));
            ExecutionResult failed = engine.execute(call("bob", Origin.OFFICIAL));
            assertThat(failed.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(logins).hasValue(1);
            ExecutionResult paused = engine.execute(call("bob", Origin.OFFICIAL));
            assertThat(paused.error().message()).isEqualTo("该账号已暂停自动登录");
            assertThat(logins).hasValue(1);
        }
    }

    @Test
    void callKeepsTheAuthorizedLoginTemplate() throws Exception {
        try (SessionManager sessions = open(EngineSettings.defaults(), Clock.systemUTC())) {
            ConchDoc authorized = doc(auth("""
                    - request: { method: POST, path: /login, form: { username: "{{credential.username}}" } }
                    - extract:
                        kept: { jsonpath: $.token }
                    """), """
                    - request: { method: GET, path: "/kept/{{login.kept}}" }
                    """, false);
            ConchDoc registered = doc(auth("""
                    - request: { method: POST, path: /login, form: { username: "{{credential.username}}" } }
                    - extract:
                        other: { jsonpath: $.token }
                    """), """
                    - request: { method: GET, path: "/other/{{login.other}}" }
                    """, false);
            ExecutionResult result = new RuleEngine(registry(registered), sessions)
                    .execute(call("alice", Origin.OFFICIAL), authorized);
            assertThat(result.success()).isTrue();
            assertThat(paths).containsExactly("/kept/" + issued.get(0));
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        if (path.equals("/huge")) {
            logins.incrementAndGet();
            byte[] body = ("{\"token\":\"" + "a".repeat(40_000) + "\",\"brief\":\"ab\"}")
                    .getBytes(StandardCharsets.UTF_8);
            respond(exchange, 200, body, "application/json");
            return;
        }
        if (path.equals("/deny")) {
            logins.incrementAndGet();
            respond(exchange, 401, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
            return;
        }
        if (path.equals("/login-alias")) {
            logins.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=csrf-header-value; Path=/");
            respond(exchange, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), "application/json");
            return;
        }
        if (path.startsWith("/login")) {
            int n = logins.incrementAndGet();
            String token = "Tok+" + n + "Value";
            issued.add(token);
            exchange.getResponseHeaders().add("Set-Cookie", "sid=sid-cookie-value; Path=/");
            byte[] body = ("{\"token\":\"" + token + "\",\"brief\":\"ab\"}").getBytes(StandardCharsets.UTF_8);
            respond(exchange, 200, body, "application/json");
            return;
        }
        paths.add(path);
        if (path.startsWith("/boom")) {
            respond(exchange, 500, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
            return;
        }
        if (path.startsWith("/use") && failUses.get() > 0) {
            failUses.decrementAndGet();
            respond(exchange, 401, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
            return;
        }
        String token = issued.isEmpty() ? "" : issued.get(issued.size() - 1);
        if (path.startsWith("/use/") || path.startsWith("/echo/") || path.startsWith("/kept/")) {
            int slash = path.indexOf('/', 1);
            int next = path.indexOf('/', slash + 1);
            token = next < 0 ? path.substring(slash + 1) : path.substring(slash + 1, next);
        }
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        byte[] body = ("{\"echo\":\"" + jsonEscape(token) + "\",\"encoded\":\"" + encoded + "\",\"brief\":\"ab\"}")
                .getBytes(StandardCharsets.UTF_8);
        respond(exchange, 200, body, "application/json");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, String type) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private SessionManager open(EngineSettings settings, Clock clock) {
        return new SessionManager(cookies, snapshots, settings, clock);
    }

    private ConchRegistry registry(ConchDoc doc) {
        ConchRegistry registry = new ConchRegistry();
        registry.put(doc);
        return registry;
    }

    private ExecutionRequest call(String account, Origin origin) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return EngineTestSupport.request(
                origin,
                "sample_login",
                "read",
                account,
                Map.of(),
                EngineTestSupport.account(account, "pw-sample-1", base));
    }

    private ConchDoc doc(String authBody, String actionSteps, boolean idempotent) {
        return doc(authBody, actionSteps, idempotent,
                "value: { type: string, description: 结果, value: ok }",
                "{ value: ok }");
    }

    private ConchDoc doc(
            String authBody,
            String actionSteps,
            boolean idempotent,
            String output,
            String example
    ) {
        return ConchParser.parse(conch(authBody, actionSteps, idempotent, output, example));
    }

    private static String auth(String steps) {
        return "steps:\n" + margin(steps).indent(2).stripTrailing();
    }

    private static String margin(String text) {
        return text.stripTrailing().stripIndent();
    }

    private static String loginSteps(String path, boolean extractToken) {
        String extract = extractToken ? """
                - extract:
                    token: { jsonpath: $.token }
                    brief: { jsonpath: $.brief }
                """ : "";
        return """
                - request:
                    method: POST
                    path: %s
                    form: { username: "{{credential.username}}", password: "{{credential.password}}" }
                %s
                - assert: { cookie_exists: sid }
                """.formatted(path, margin(extract));
    }

    private static String useSteps() {
        return """
                - request: { method: GET, path: "/use/{{login.token}}/{{login.sid}}" }
                """;
    }

    private static String conch(
            String authBody,
            String actionSteps,
            boolean idempotent,
            String output,
            String example
    ) {
        return """
                conch: sample_login
                version: 1.0.0
                driver: http
                min_crab_version: 1.3.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                auth:
                %s
                actions:
                  read:
                    title: 读取
                    description: 说明
                    idempotent: %s
                    output:
                %s
                    examples:
                      - input: {}
                        output: %s
                    notes: 注意
                    steps:
                %s
                """.formatted(
                margin(authBody).indent(2).stripTrailing(),
                idempotent,
                margin(output).indent(6).stripTrailing(),
                example,
                margin(actionSteps).indent(6).stripTrailing());
    }

    private int count(String sql) throws Exception {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static final class CapturingSink implements DevTraceSink {
        private int opens;
        private String body = "";

        @Override
        public DevTraceDraft open(ExecutionRequest request) {
            opens++;
            return new DevTraceDraft(Instant.now().plus(Duration.ofHours(1)));
        }

        @Override
        public void save(ExecutionRequest request, Instant expiresAt, String body) {
            this.body = body;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
