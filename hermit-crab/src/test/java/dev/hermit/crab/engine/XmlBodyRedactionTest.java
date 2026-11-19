package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.template.XmlEscape;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.SecretChecks;

/** 凭证插进 XML 正文后，上游回显和开发追踪都按 form/json 同一套名单打码。 */
class XmlBodyRedactionTest {

    private static final String PASSWORD = "p&w<x>y\"z'qq";

    @Test
    void jsonEchoOfTheXmlBodyIsMaskedInTheResultAndTheTrace() throws Exception {
        Hit hit = run("/list", true);
        assertThat(hit.result.success()).as(error(hit.result)).isTrue();
        String echo = String.valueOf(map(hit.result.data()).get("echo"));
        assertMasked(echo);
        assertThat(echo).contains("***").contains("<object").contains("\"echo\"");
        assertMasked(hit.trace);
        JsonNode steps = ApiJson.tree(hit.trace).path("steps");
        assertThat(requestBodies(steps)).contains("***").contains("<object");
        JsonNode action = requestStep(steps, "action");
        assertThat(action.path("responseBody").asText()).contains("***").contains("echo");
        assertThat(action.path("wire").path("body").asText()).contains("***").contains("<object");
        assertThat(action.path("wire").path("contentType").asText()).isEqualTo("application/xml");
        assertThat(hit.login.contains(XmlEscape.escape(PASSWORD))).isTrue();
        assertThat(hit.action.contains(XmlEscape.escape(PASSWORD))).isTrue();
    }

    @Test
    void plainEchoIsHiddenInTheTraceWhileTheRequestBodyStaysMasked() throws Exception {
        Hit hit = run("/raw", false);
        assertThat(hit.result.success()).as(error(hit.result)).isTrue();
        String echo = String.valueOf(map(hit.result.data()).get("echo"));
        assertMasked(echo);
        assertThat(echo).contains("***").contains("<object").doesNotContain("{");
        assertMasked(hit.trace);
        JsonNode action = requestStep(ApiJson.tree(hit.trace).path("steps"), "action");
        assertThat(action.path("responseBody").asText()).isEqualTo(DevTraceDraft.HIDDEN_CREDENTIAL_BODY);
        assertThat(action.path("requestBody").asText()).contains("***").contains("<object");
        assertThat(action.path("wire").path("body").asText()).contains("***");
        assertMasked(action.path("requestBody").asText());
        assertMasked(action.path("wire").path("body").asText());
    }

    private static Hit run(String path, boolean json) throws IOException {
        AtomicReference<String> login = new AtomicReference<>();
        AtomicReference<String> action = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            login.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Set-Cookie", "sid=session-cookie-name; Path=/");
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(ok);
            }
        });
        server.createContext(path, exchange -> {
            String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            action.set(text);
            byte[] body = json ? jsonEcho(text) : text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", json ? "application/json" : "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        MemoryTrace trace = new MemoryTrace();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ExecutionResult result = execute(conch(path), base, trace);
            return new Hit(result, trace.body, login.get(), action.get());
        } finally {
            server.stop(0);
        }
    }

    private static byte[] jsonEcho(String text) {
        LinkedHashMap<String, String> payload = new LinkedHashMap<>();
        payload.put("echo", text);
        payload.put("encoded", URLEncoder.encode(text, StandardCharsets.UTF_8));
        return ApiJson.write(payload).getBytes(StandardCharsets.UTF_8);
    }

    private static void assertMasked(String text) {
        SecretChecks.absent(text, PASSWORD);
        SecretChecks.absent(text, XmlEscape.escape(PASSWORD));
        SecretChecks.absent(text, XmlEscape.escape(XmlEscape.escape(PASSWORD)));
        SecretChecks.absent(text, URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8));
        SecretChecks.absent(text, URLEncoder.encode(XmlEscape.escape(PASSWORD), StandardCharsets.UTF_8));
        EngineTestSupport.secretsAbsent(text, PASSWORD);
    }

    private static String requestBodies(JsonNode steps) {
        StringBuilder text = new StringBuilder();
        for (JsonNode step : steps) {
            if (step.has("requestBody")) {
                text.append(step.path("requestBody").asText()).append('\n');
            }
            if (step.has("wire")) {
                text.append(step.path("wire").path("body").asText()).append('\n');
            }
        }
        return text.toString();
    }

    private static JsonNode requestStep(JsonNode steps, String phase) {
        for (JsonNode step : steps) {
            if (phase.equals(step.path("phase").asText()) && "request".equals(step.path("type").asText())) {
                return step;
            }
        }
        throw new AssertionError("没有请求步骤 " + phase);
    }

    private static ExecutionResult execute(String yaml, String baseUrl, MemoryTrace trace) {
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(yaml));
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults(), KnownSecrets.empty());
            engine.bindDevTraceSink(trace);
            return engine.execute(EngineTestSupport.request(
                    Origin.LOCAL,
                    "mail_box",
                    "list",
                    "alice",
                    Map.of("fid", 1, "order", "a<b"),
                    EngineTestSupport.account("alice", PASSWORD, baseUrl)));
        }
    }

    private static String conch(String path) {
        return """
                conch: mail_box
                version: 1.0.0
                driver: http
                min_crab_version: 1.7.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                auth:
                  steps:
                    - request:
                        method: POST
                        path: /login
                        headers:
                          Content-Type: application/xml
                        body: |
                          <object><string name="password">{{credential.password | xml_escape}}</string></object>
                    - assert: { cookie_exists: sid }
                actions:
                  list:
                    title: 查询
                    description: 说明
                    input:
                      fid: { type: integer, description: 文件夹 }
                      order: { type: string, description: 排序 }
                    output:
                      echo: { type: string, description: 回显, value: "{{response.body}}" }
                    examples:
                      - input: { fid: 1, order: receiveDate }
                        output: { echo: ok }
                    notes: 注意
                    related: []
                    steps:
                      - request:
                          method: POST
                          path: %s
                          headers:
                            Content-Type: application/xml
                          body: |
                            <object><int name="fid">{{input.fid}}</int><string name="order">{{input.order | xml_escape}}</string><string name="password">{{credential.password | xml_escape}}</string></object>
                """.formatted(path);
    }

    private static String error(ExecutionResult result) {
        return result.error() == null ? "" : result.error().message();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private record Hit(ExecutionResult result, String trace, String login, String action) {
    }

    private static final class MemoryTrace implements DevTraceSink {
        private String body;

        @Override
        public DevTraceDraft open(ExecutionRequest request) {
            return new DevTraceDraft(Instant.parse("2026-11-19T00:00:00Z"));
        }

        @Override
        public void save(ExecutionRequest request, Instant expiresAt, String body) {
            this.body = body;
        }
    }
}
