package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.sync.DisabledConches;
import dev.hermit.crab.testing.SecretChecks;

/**
 * 登录停在 ask。图片和验证码都是测试夹具，不是真实凭证。
 */
class LoginInputEngineTest {

    private static final String CODE = "WXYZ9";
    private static final String SHORT = "abc";
    private static final String TOKEN = "session-token-value";
    private static final String ISSUED = "pre+asktoken";
    private static final String PAUSE_PASSWORD = "pause-secret-91ab";
    private static final String RESUME_PASSWORD = "resume-secret-92cd";
    private static final String PAUSE_SITE = "site-at-pause";
    private static final String RESUME_SITE = "site-at-resume";
    private static final String PAUSE_NOTE = "note-at-pause";
    private static final String RESUME_NOTE = "note-at-resume";

    private HttpServer server;
    private ExecutorService executor;
    private EngineTestSupport.MutableClock clock;
    private final CopyOnWriteArrayList<String> paths = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> posted = new CopyOnWriteArrayList<>();
    private volatile byte[] imageBody = png(1);

    @BeforeEach
    void start() throws Exception {
        clock = new EngineTestSupport.MutableClock();
        imageBody = png(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "login-input-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void pausesWithoutRerunningAndResumeRestoresTheSnapshot() {
        try (SessionManager sessions = sessions(EngineSettings.defaults())) {
            RuleEngine engine = engine(sessions, doc(successSteps(), actionSteps()));
            ExecutionResult paused = engine.execute(call("alice"));
            assertThat(paused.success()).isFalse();
            assertThat(paused.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(paused.error().message()).isEqualTo(LoginInputMessages.required("captcha_oa"));
            assertThat(paused.error().failedStepType()).isEqualTo("ask");
            assertThat(paths).containsExactly("/captcha.png");
            assertThat(sessions.pending().view(key("alice")).kind()).isEqualTo("image");
            assertThat(sessions.pending().image(key("alice")).bytes()).isEqualTo(png(1));
            assertThat(sessions.loginSnapshot(key("alice"))).doesNotContainKey("captcha");

            ExecutionResult again = engine.execute(call("alice"));
            assertThat(again.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(paths).containsExactly("/captcha.png");

            ExecutionResult resumed = engine.continueLogin(call("alice"), CODE);
            assertThat(resumed.success()).isTrue();
            assertThat(sessions.pending().current(key("alice"))).isNull();
            assertThat(sessions.loginSnapshot(key("alice"))).containsEntry("token", TOKEN).doesNotContainKey("captcha");
            SecretChecks.absent(EngineTestSupport.flatten(resumed), CODE);

            ExecutionResult used = engine.execute(call("alice"));
            assertThat(used.success()).isTrue();
            assertThat(paths).containsExactly("/captcha.png", "/login/" + CODE, "/tick/" + TOKEN);
            assertThat(engine.execute(call("bob")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(sessions.pending().current(key("alice"))).isNull();
            assertThat(sessions.pending().current(key("bob"))).isNotNull();
        }
    }

    @Test
    void resumeUsesFreshCredentialsAndLeavesThemOutOfThePendingLogin() {
        try (SessionManager sessions = sessions(EngineSettings.defaults())) {
            RuleEngine engine = engine(sessions, doc(freshCredentialSteps(), actionSteps()));
            ExecutionResult paused = engine.execute(call("alice", PAUSE_PASSWORD, PAUSE_SITE, PAUSE_NOTE));
            assertThat(paused.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            Map<String, Object> frozen = sessions.pending().current(key("alice")).vars();
            assertThat(frozen).doesNotContainKeys("input", "credential", "config", "now", "login");
            assertThat(frozen.get("response")).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) frozen.get("response")).get("body")).isEqualTo("");
            SecretChecks.absent(EngineTestSupport.flatten(frozen), PAUSE_PASSWORD);
            SecretChecks.absent(EngineTestSupport.flatten(frozen), PAUSE_SITE);
            SecretChecks.absent(EngineTestSupport.flatten(frozen), PAUSE_NOTE);

            ExecutionResult resumed = engine.continueLogin(
                    call("alice", RESUME_PASSWORD, RESUME_SITE, RESUME_NOTE), CODE);
            assertThat(resumed.success()).isTrue();
            assertThat(posted).anySatisfy(body -> {
                assertThat(body).contains("password=" + RESUME_PASSWORD);
                assertThat(body).contains("site=" + RESUME_SITE);
                assertThat(body).contains("note=" + RESUME_NOTE);
                assertThat(body).doesNotContain(PAUSE_PASSWORD);
                assertThat(body).doesNotContain(PAUSE_SITE);
                assertThat(body).doesNotContain(PAUSE_NOTE);
            });
        }
    }

    @Test
    void resumeMasksValuesCapturedBeforeAskWhenLaterStepsFail() {
        try (SessionManager sessions = sessions(EngineSettings.defaults())) {
            Hold traces = new Hold();
            RuleEngine engine = engine(sessions, doc(issuedTokenSteps(), """
                    - set: { seen: ok }
                    """), Origin.LOCAL);
            engine.bindDevTraceSink(traces);
            ExecutionRequest request = call("alice", Origin.LOCAL);
            assertThat(engine.execute(request).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);

            ExecutionResult failed = engine.continueLogin(request, CODE);
            String encoded = URLEncoder.encode(ISSUED, StandardCharsets.UTF_8);
            String flat = EngineTestSupport.flatten(failed);
            assertThat(failed.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(failed.error().message()).contains("提取 again 失败").contains("***");
            SecretChecks.absent(failed.error().message(), ISSUED);
            SecretChecks.absent(failed.error().message(), encoded);
            assertThat(flat).contains("/use/***");
            SecretChecks.absent(flat, ISSUED);
            SecretChecks.absent(flat, encoded);
            SecretChecks.absent(failed.stepLogs().toString(), ISSUED);
            SecretChecks.absent(failed.stepLogs().toString(), encoded);
            assertThat(traces.bodies).hasSize(2);
            SecretChecks.absent(traces.bodies.get(1), ISSUED);
            SecretChecks.absent(traces.bodies.get(1), encoded);
            assertThat(traces.bodies.get(1)).contains("/use/***").contains("***");
        }
    }

    @Test
    void updatedPackageRestartsLoginInsteadOfWaiting() {
        try (SessionManager sessions = sessions(EngineSettings.defaults())) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(doc(successSteps(), actionSteps()));
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults());
            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            String oldHash = sessions.pending().current(key("alice")).contentHash();
            assertThat(sessions.pending().current(key("alice")).version()).isEqualTo("1.0.0");

            registry.put(doc(successSteps("/captcha-v2.png"), actionSteps(), "1.0.1", "另一份说明"));
            ExecutionResult restarted = engine.execute(call("alice"));
            assertThat(restarted.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(paths).contains("/captcha-v2.png");
            PendingLogins.Pending next = sessions.pending().current(key("alice"));
            assertThat(next.version()).isEqualTo("1.0.1");
            assertThat(next.contentHash()).isNotEqualTo(oldHash)
                    .isEqualTo(ConchDigest.sha256(registry.find("captcha_oa").orElseThrow()));

            int size = paths.size();
            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(paths).hasSize(size);
        }
    }

    @Test
    void changedPackageAndExpiryDiscardThePendingLogin() {
        try (SessionManager sessions = sessions(EngineSettings.defaults())) {
            ConchRegistry registry = new ConchRegistry();
            ConchDoc first = doc(successSteps(), actionSteps());
            registry.put(first);
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults());
            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            registry.put(doc(successSteps(), actionSteps(), "1.0.1", "另一份说明"));
            ExecutionResult stale = engine.continueLogin(call("alice"), CODE);
            assertThat(stale.error().code()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(stale.error().message()).isEqualTo(LoginInputMessages.UPDATED);
            assertThat(sessions.pending().current(key("alice"))).isNull();
            assertThat(engine.resendLogin(call("alice")).error().message()).isEqualTo(LoginInputMessages.NONE);

            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            clock.advance(Duration.ofSeconds(60));
            assertThat(sessions.pending().current(key("alice"))).isNull();
            ExecutionResult afterExpiry = engine.execute(call("alice"));
            assertThat(afterExpiry.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(paths.stream().filter("/captcha.png"::equals).count()).isEqualTo(3);
        }
    }

    @Test
    void badImagesFailAuthAndDoNotWait() {
        try (SessionManager sessions = sessions(EngineSettings.defaults())) {
            RuleEngine engine = engine(sessions, doc(successSteps(), actionSteps()));
            imageBody = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
            ExecutionResult svg = engine.execute(call("alice"));
            assertThat(svg.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(svg.error().message()).isEqualTo(CaptchaImages.UNSUPPORTED);
            assertThat(sessions.pending().current(key("alice"))).isNull();

            imageBody = new byte[CaptchaImages.MAX_BYTES + 1];
            System.arraycopy(png(1), 0, imageBody, 0, 8);
            ExecutionResult huge = engine.execute(call("alice"));
            assertThat(huge.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(huge.error().message()).isEqualTo(CaptchaImages.TOO_LARGE);
            assertThat(sessions.pending().current(key("alice"))).isNull();
        }
    }

    @Test
    void masksTheSubmittedValueInLogsTraceAndExtractErrors() {
        try (SessionManager sessions = sessions(EngineSettings.defaults())) {
            Hold traces = new Hold();
            RuleEngine engine = engine(sessions, doc(redactionSteps(), actionSteps()), Origin.LOCAL);
            engine.bindDevTraceSink(traces);
            ExecutionRequest request = call("alice", Origin.LOCAL);
            assertThat(engine.execute(request).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(traces.bodies).singleElement().satisfies(body -> {
                assertThat(body).contains("\"type\":\"ask\"").contains("验证码");
                assertThat(body).doesNotContain("PNG");
                SecretChecks.absent(body, EngineTestSupport.PASSWORD);
            });

            ExecutionResult failed = engine.continueLogin(request, CODE);
            assertThat(failed.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(failed.error().message()).contains("提取 token 失败").contains("***").doesNotContain(CODE);
            assertThat(EngineTestSupport.flatten(failed)).doesNotContain("/login/" + CODE).contains("/login/***");
            SecretChecks.absent(EngineTestSupport.flatten(failed), CODE);
            SecretChecks.absent(EngineTestSupport.flatten(failed), EngineTestSupport.PASSWORD);
            assertThat(traces.bodies).hasSize(2);
            SecretChecks.absent(traces.bodies.get(1), CODE);
            SecretChecks.absent(traces.bodies.get(1), EngineTestSupport.PASSWORD);
            assertThat(traces.bodies.get(1)).contains("***");

            assertThat(engine.execute(call("bob", Origin.LOCAL)).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            ExecutionResult shortFail = engine.continueLogin(call("bob", Origin.LOCAL), SHORT);
            assertThat(shortFail.error().message()).contains(SHORT);
            assertThat(EngineTestSupport.flatten(shortFail)).contains("/login/" + SHORT);
        }
    }

    @Test
    void resumeFailureDoesNotPauseAutomaticLogin() {
        EngineSettings strict = new EngineSettings(Duration.ofSeconds(60), Duration.ofMinutes(10), 1);
        try (SessionManager sessions = sessions(strict)) {
            RuleEngine engine = engine(sessions, doc(cookieSteps(), """
                    - set: { seen: ok }
                    """));
            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            ExecutionResult resumed = engine.continueLogin(call("alice"), CODE);
            assertThat(resumed.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(resumed.error().message()).doesNotContain("暂停");
            ExecutionResult next = engine.execute(call("alice"));
            assertThat(next.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(next.error().message()).doesNotContain("暂停");
        }
    }

    @Test
    void resendReplacesTheImageAfterCooldownAndKeepsTheOldOneOnFailure() {
        try (SessionManager sessions = sessions(EngineSettings.defaults())) {
            RuleEngine engine = engine(sessions, doc(successSteps(), actionSteps()));
            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            ExecutionResult early = engine.resendLogin(call("alice"));
            assertThat(early.error().code()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(early.error().message()).isEqualTo(LoginInputMessages.TOO_FAST);
            assertThat(sessions.pending().view(key("alice")).canResend()).isTrue();
            assertThat(sessions.pending().view(key("alice")).resendAfterSeconds()).isEqualTo(2);

            clock.advance(Duration.ofSeconds(2));
            imageBody = png(2);
            assertThat(engine.resendLogin(call("alice")).success()).isTrue();
            assertThat(sessions.pending().image(key("alice")).bytes()).isEqualTo(png(2));
            assertThat(sessions.pending().image(key("alice")).mediaType()).isEqualTo("image/png");

            clock.advance(Duration.ofSeconds(2));
            imageBody = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
            ExecutionResult rejected = engine.resendLogin(call("alice"));
            assertThat(rejected.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(sessions.pending().image(key("alice")).bytes()).isEqualTo(png(2));
            assertThat(sessions.pending().current(key("alice")).resendCount()).isEqualTo(2);
        }
    }

    @Test
    void forgetInvalidateRemoveAndDisableDropThePendingLogin() {
        try (SessionManager sessions = sessions(EngineSettings.defaults())) {
            ConchRegistry registry = new ConchRegistry();
            registry.onRemove(name -> sessions.pending().discardConch(name));
            DisabledConches disabled = DisabledConches.none();
            disabled.onDisabled(name -> sessions.pending().discardConch(name));
            registry.put(doc(successSteps(), actionSteps()));
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults());
            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(engine.execute(call("bob")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            sessions.forget(key("alice"));
            assertThat(sessions.pending().current(key("alice"))).isNull();
            assertThat(sessions.pending().current(key("bob"))).isNotNull();
            sessions.invalidate(key("bob"));
            assertThat(sessions.pending().current(key("bob"))).isNull();

            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            registry.remove("captcha_oa");
            assertThat(sessions.pending().current(key("alice"))).isNull();

            registry.put(doc(successSteps(), actionSteps()));
            assertThat(engine.execute(call("alice")).error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            disabled.mark("captcha_oa", "1.0.0", clock.instant());
            assertThat(sessions.pending().current(key("alice"))).isNull();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        paths.add(path);
        if (!payload.isEmpty()) {
            posted.add(path + "?" + payload);
        }
        if ("/issue".equals(path)) {
            send(exchange, 200, "application/json",
                    ("{\"ticket\":\"" + ISSUED + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.startsWith("/use/")) {
            String encoded = URLEncoder.encode(ISSUED, StandardCharsets.UTF_8);
            String json = "{\"echo\":\"" + ISSUED + "\",\"encoded\":\"" + encoded + "\"}";
            send(exchange, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("/captcha.png".equals(path) || "/captcha-v2.png".equals(path)) {
            byte[] body = imageBody;
            String type = body.length >= 8 && body[1] == 'P' ? "image/png" : "image/svg+xml";
            send(exchange, 200, type, body);
            return;
        }
        if (path.startsWith("/login/")) {
            send(exchange, 200, "application/json", ("{\"token\":\"" + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.startsWith("/tick/")) {
            send(exchange, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(exchange, 404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private SessionManager sessions(EngineSettings settings) {
        return new SessionManager(new NopSessionPersistence(), settings, clock);
    }

    private RuleEngine engine(SessionManager sessions, ConchDoc doc) {
        return engine(sessions, doc, Origin.OFFICIAL);
    }

    private RuleEngine engine(SessionManager sessions, ConchDoc doc, Origin origin) {
        ConchRegistry registry = new ConchRegistry();
        registry.put(doc, origin);
        return new RuleEngine(registry, sessions, EngineSettings.defaults());
    }

    private ExecutionRequest call(String account) {
        return call(account, Origin.OFFICIAL);
    }

    private ExecutionRequest call(String account, Origin origin) {
        return EngineTestSupport.request(
                origin,
                "captcha_oa",
                "tick",
                account,
                Map.of(),
                EngineTestSupport.account(account, EngineTestSupport.PASSWORD, base()));
    }

    private ExecutionRequest call(String account, String password, String site, String note) {
        return EngineTestSupport.request(
                Origin.OFFICIAL,
                "captcha_oa",
                "tick",
                account,
                Map.of("note", note),
                new EngineTestSupport.MapAccount(
                        Map.of("username", account, "password", password),
                        Map.of("base_url", base(), "site", site)));
    }

    private SessionKey key(String account) {
        return new SessionKey(Origin.OFFICIAL, "captcha_oa", account);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ConchDoc doc(String authSteps, String actionSteps) {
        return doc(authSteps, actionSteps, "1.0.0", "说明");
    }

    private ConchDoc doc(String authSteps, String actionSteps, String version, String description) {
        String yaml = """
                conch: captcha_oa
                version: %s
                driver: http
                min_crab_version: 1.4.0
                base_url: %s
                title: 标题
                description: %s
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                config:
                  site: { type: text, required: false, description: 站点标记 }
                auth:
                  steps:
                %s
                actions:
                  tick:
                    title: 能力
                    description: 说明
                    method: POST
                    input:
                      note: { type: string, required: false, description: 备注 }
                    output:
                      seen: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { seen: ok }
                    notes: 注意
                    steps:
                %s
                """.formatted(
                version,
                base(),
                description,
                margin(authSteps).indent(4).stripTrailing(),
                margin(actionSteps).indent(6).stripTrailing());
        return ConchParser.parse(yaml);
    }

    private static String successSteps() {
        return successSteps("/captcha.png");
    }

    private static String successSteps(String captchaPath) {
        return """
                - request: { method: GET, path: %s }
                - ask:
                    into: captcha
                    kind: image
                    label: 验证码
                    hint: 看图
                    timeout_s: 60
                - request:
                    method: POST
                    path: "/login/{{captcha}}"
                    form:
                      username: "{{credential.username}}"
                      password: "{{credential.password}}"
                      code: "{{captcha}}"
                - extract:
                    token: { jsonpath: $.token }
                """.formatted(captchaPath);
    }

    private static String freshCredentialSteps() {
        return """
                - request: { method: GET, path: /captcha.png }
                - ask:
                    into: captcha
                    kind: image
                    label: 验证码
                    timeout_s: 60
                - request:
                    method: POST
                    path: "/login/{{captcha}}"
                    form:
                      username: "{{credential.username}}"
                      password: "{{credential.password}}"
                      site: "{{config.site}}"
                      note: "{{input.note}}"
                      code: "{{captcha}}"
                - extract:
                    token: { jsonpath: $.token }
                """;
    }

    private static String issuedTokenSteps() {
        return """
                - request: { method: GET, path: /issue }
                - extract:
                    ticket: { jsonpath: $.ticket }
                - ask:
                    into: captcha
                    kind: code
                    label: 验证码
                    timeout_s: 60
                - request:
                    method: GET
                    path: "/use/{{ticket}}"
                - extract:
                    again: { regex: "(nope-{{ticket}})" }
                """;
    }

    private static String redactionSteps() {
        return """
                - request: { method: GET, path: /captcha.png }
                - ask:
                    into: captcha
                    kind: image
                    label: 验证码
                    timeout_s: 60
                - request:
                    method: POST
                    path: "/login/{{captcha}}"
                    form:
                      password: "{{credential.password}}"
                      code: "{{captcha}}"
                - extract:
                    token: { regex: "{{captcha}}-nope" }
                """;
    }

    private static String cookieSteps() {
        return """
                - request: { method: GET, path: /captcha.png }
                - ask:
                    into: captcha
                    kind: image
                    label: 验证码
                    timeout_s: 60
                - request:
                    method: POST
                    path: "/login/{{captcha}}"
                - assert: { cookie_exists: sid }
                """;
    }

    private static String actionSteps() {
        return """
                - request: { method: GET, path: "/tick/{{login.token}}" }
                - set: { seen: ok }
                """;
    }

    private static String margin(String text) {
        return text.stripTrailing().stripIndent();
    }

    private static byte[] png(int mark) {
        byte[] body = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, (byte) mark
        };
        return body;
    }

    private static final class Hold implements DevTraceSink {
        private final List<String> bodies = new ArrayList<>();

        @Override
        public DevTraceDraft open(ExecutionRequest request) {
            return new DevTraceDraft(Instant.parse("2026-11-17T00:00:00Z"));
        }

        @Override
        public void save(ExecutionRequest request, Instant expiresAt, String body) {
            bodies.add(body);
        }
    }
}
