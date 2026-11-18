package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.SecretChecks;

class LegacyCompatEngineTest {

    @Test
    void stripsBomBeforeJsonExtract() throws Exception {
        byte[] json = "{\"name\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[json.length + 3];
        body[0] = (byte) 0xEF;
        body[1] = (byte) 0xBB;
        body[2] = (byte) 0xBF;
        System.arraycopy(json, 0, body, 3, json.length);
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply(ScriptedLoopback.response(200, "application/json", body));
            ExecutionResult result = engine(sessions, bomConch()).execute(call(loop.base(), "bom_conch", "read"));
            assertThat(result.success()).as(result.error() == null ? "" : result.error().message()).isTrue();
            assertThat(result.data()).isEqualTo(Map.of("name", "ok"));
        }
    }

    @Test
    void upstreamErrorKeepsRedactedBodyAndLoginStaysUpstream() throws Exception {
        String secret = EngineTestSupport.PASSWORD;
        String payload = "a".repeat(4092) + secret + "tail-of-body";
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply(ScriptedLoopback.response(500, "text/plain", payload.getBytes(StandardCharsets.UTF_8)));
            CapturingSink sink = new CapturingSink();
            RuleEngine engine = engine(sessions, EngineTestSupport.actionConch("down_conch", "down", """
                    - request: { method: GET, path: /down }
                    """, true));
            engine.bindDevTraceSink(sink);
            ExecutionResult result = engine.execute(call(loop.base(), "down_conch", "down", Origin.LOCAL));
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            String message = result.error().message();
            assertThat(message).startsWith("上游状态码 500\n");
            assertThat(message).contains("***").doesNotContain(secret).doesNotContain("S3cr").doesNotContain("et!pwd");
            String body = message.substring(message.indexOf('\n') + 1);
            assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(HttpCalls.UPSTREAM_BODY_LIMIT);
            assertThat(sink.body).contains("***").doesNotContain(secret).doesNotContain("S3cr");

            loop.reply(ScriptedLoopback.response(500, "text/plain", new byte[0]));
            ExecutionResult empty = engine(sessions, EngineTestSupport.actionConch("empty_conch", "down", """
                    - request: { method: GET, path: /empty }
                    """, true)).execute(call(loop.base(), "empty_conch", "down"));
            assertThat(empty.error().message()).isEqualTo("上游状态码 500");

            loop.reply(ScriptedLoopback.response(
                    500, "text/plain", ("login " + secret).getBytes(StandardCharsets.UTF_8)));
            ExecutionResult login = engine(sessions, loginConch()).execute(call(loop.base(), "login_down", "read"));
            assertThat(login.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(login.error().message()).startsWith("上游状态码 500\n");
            assertThat(login.error().message()).contains("***").doesNotContain(secret);
        }
    }

    @Test
    void traceRedactsWarningText() throws Exception {
        String secret = EngineTestSupport.PASSWORD;
        String cross = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/path?token=" + secret
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        String secure = "HTTP/1.1 200 OK\r\nSet-Cookie: " + secret
                + "=other-cookie-value; Secure; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok";
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply(cross);
            CapturingSink sink = new CapturingSink();
            RuleEngine engine = engine(sessions, EngineTestSupport.actionConch("away_conch", "away", """
                    - request: { method: GET, path: /away, follow_redirects: true }
                    - assert: { status: 302 }
                    """, true));
            engine.bindDevTraceSink(sink);
            ExecutionResult result = engine.execute(call(loop.base(), "away_conch", "away", Origin.LOCAL));
            assertThat(result.success()).as(result.error() == null ? "" : result.error().message()).isTrue();
            assertThat(sink.body).contains("跳转到不同源 http://127.0.0.1:1，未跟随");
            assertThat(sink.body).contains("cross_origin");
            SecretChecks.absent(sink.body, secret);

            loop.reply(secure);
            CapturingSink secureSink = new CapturingSink();
            RuleEngine secureEngine = engine(sessions, EngineTestSupport.actionConch("sec_conch", "sec", """
                    - request: { method: GET, path: /sec }
                    """, true));
            secureEngine.bindDevTraceSink(secureSink);
            ExecutionResult secured = secureEngine.execute(call(loop.base(), "sec_conch", "sec", Origin.LOCAL));
            assertThat(secured.success()).isTrue();
            assertThat(secureSink.body).contains("收到 Secure cookie ***，明文连接不会发送");
            assertThat(secureSink.body).contains("secure_cookie");
            SecretChecks.absent(secureSink.body, secret);
            assertThat(secureSink.body).doesNotContain("other-cookie-value");
        }
    }

    private static RuleEngine engine(SessionManager sessions, String yaml) {
        ConchRegistry registry = new ConchRegistry();
        registry.put(ConchParser.parse(yaml));
        return new RuleEngine(registry, sessions);
    }

    private static ExecutionRequest call(String base, String conch, String action) {
        return call(base, conch, action, Origin.OFFICIAL);
    }

    private static ExecutionRequest call(String base, String conch, String action, Origin origin) {
        return EngineTestSupport.request(
                origin,
                conch,
                action,
                "alice",
                Map.of(),
                EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, base));
    }

    private static String bomConch() {
        return """
                conch: bom_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                actions:
                  read:
                    title: 能力
                    description: 说明
                    output:
                      name: { type: string, description: 名字 }
                    examples:
                      - input: {}
                        output: { name: ok }
                    notes: 注意
                    steps:
                      - request: { method: GET, path: /bom }
                      - extract:
                          name: { jsonpath: $.name }
                """;
    }

    private static String loginConch() {
        return """
                conch: login_down
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
                    - request: { method: GET, path: /down }
                actions:
                  read:
                    title: 读取
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - request: { method: GET, path: /never }
                """;
    }

    private static final class CapturingSink implements DevTraceSink {
        private String body = "";

        @Override
        public DevTraceDraft open(ExecutionRequest request) {
            return new DevTraceDraft(Instant.now().plus(Duration.ofHours(1)));
        }

        @Override
        public void save(ExecutionRequest request, Instant expiresAt, String body) {
            this.body = body;
        }
    }
}
