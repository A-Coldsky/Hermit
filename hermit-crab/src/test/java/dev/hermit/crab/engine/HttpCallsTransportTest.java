package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.testing.LocalHttps;
import dev.hermit.crab.testing.SecretChecks;
import dev.hermit.crab.tls.OutboundTls;

class HttpCallsTransportTest {

    @Test
    void dnsRefusedAndTimeoutKeepTheirMessages() throws Exception {
        try (SessionManager sessions = new SessionManager()) {
            SessionKey key = new SessionKey(Origin.LOCAL, "lab", "a");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                CloseableHttpClient client = sessions.client(key);
                RuleProblem dns = failure(client, Map.of("url", "http://no-such-host.invalid/"));
                assertThat(dns.summary).startsWith("无法解析上游地址");
                assertThat(dns.transport).containsEntry("kind", "DNS");

                int port;
                try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                    port = closed.getLocalPort();
                }
                RuleProblem refused = failure(client, Map.of("url", "http://127.0.0.1:" + port + "/"));
                assertThat(refused.summary).contains("上游拒绝连接（127.0.0.1:" + port + "）");
                assertThat(refused.transport).containsEntry("kind", "REFUSED");

                try (ServerSocket hanging = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                    Thread hold = new Thread(() -> {
                        try {
                            hanging.accept();
                            Thread.sleep(5_000);
                        } catch (Exception ignored) {
                            // 测试结束。
                        }
                    });
                    hold.setDaemon(true);
                    hold.start();
                    RuleProblem timeout = failure(client, Map.of(
                            "url", "http://127.0.0.1:" + hanging.getLocalPort() + "/",
                            "timeout_ms", 300));
                    assertThat(timeout.summary).isEqualTo(HttpCalls.STEP_TIMEOUT);
                    assertThat(timeout.transport).isNull();
                }
            } finally {
                sessions.unlock(key);
            }
        }
    }

    @Test
    void untrustedHttpsIsSpecificAndReloadReusesTheAccountClient(@TempDir Path dir) throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(dir.resolve("lab"));
        Path caDir = dir.resolve("ca");
        Files.createDirectories(caDir);
        try (LocalHttps https = LocalHttps.start(new X509Certificate[] {certs.leaf()}, certs.leafKey())) {
            OutboundTls tls = OutboundTls.open(caDir, true);
            OutboundTls previous = OutboundTls.install(tls);
            try (SessionManager sessions = new SessionManager()) {
                SessionKey key = new SessionKey(Origin.LOCAL, "lab", "a");
                assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
                try {
                    CloseableHttpClient client = sessions.client(key);
                    RuleProblem untrusted = failure(client, Map.of("url", https.url()));
                    assertThat(untrusted.summary).contains("不受信任").contains("Hermit Lab Root");
                    assertThat(untrusted.summary).doesNotContain("上游连接失败");
                    assertThat(untrusted.transport.get("kind")).isEqualTo("TLS_UNTRUSTED");
                    assertThat(untrusted.transport.get("chain")).asList().isNotEmpty();

                    Files.copy(certs.rootPem(), caDir.resolve("root.pem"));
                    tls.reload(caDir, true);
                    assertThat(sessions.client(key)).isSameAs(client);
                    HttpResult result = HttpCalls.execute(
                            Map.of("url", https.url()), client, () -> { }, URI.create(https.url()), null, deadline());
                    assertThat(result.status()).isEqualTo(200);
                } finally {
                    sessions.unlock(key);
                }
            } finally {
                OutboundTls.restore(previous);
            }
        }
    }

    @Test
    void unparsedLocationWarnsAndGetDoesNotBlameTheStatusCode() throws Exception {
        String token = "loc-token-9f3a";
        String location = "http://[" + token;
        try (ScriptedLoopback loop = ScriptedLoopback.start();
                CloseableHttpClient client = plainClient();
                SessionManager sessions = new SessionManager()) {
            loop.reply(redirect(location));
            HttpResult stopped = call(client, Map.of(
                    "method", "GET",
                    "path", "/start",
                    "follow_redirects", true), loop.base());
            assertThat(stopped.status()).isEqualTo(302);
            assertThat(stopped.redirect()).isEqualTo(location);
            assertThat(stopped.warnings()).extracting(TraceWarning::code)
                    .containsExactly(TraceWarning.LOCATION_UNRESOLVED);
            assertThat(stopped.warnings().get(0).message()).isEqualTo(HttpCalls.LOCATION_WARNING);
            assertThat(stopped.warnings().get(0).message()).doesNotContain(token).doesNotContain("http://[");
            assertThat(loop.hits()).hasSize(1);

            loop.reply(redirect(loop.base() + "/land"));
            loop.reply(ScriptedLoopback.response(200, "text/plain", "landed".getBytes(StandardCharsets.UTF_8)));
            HttpResult followed = call(client, Map.of(
                    "method", "GET",
                    "path", "/start",
                    "follow_redirects", true), loop.base());
            assertThat(followed.status()).isEqualTo(200);
            assertThat(followed.body()).isEqualTo("landed");
            assertThat(followed.warnings()).isEmpty();

            loop.reply(redirect("http://127.0.0.1:1/path?token=" + token));
            HttpResult cross = call(client, Map.of(
                    "method", "GET",
                    "path", "/away",
                    "follow_redirects", true), loop.base());
            assertThat(cross.status()).isEqualTo(302);
            assertThat(cross.warnings()).extracting(TraceWarning::code).containsExactly(TraceWarning.CROSS_ORIGIN);
            assertThat(cross.warnings().get(0).message()).isEqualTo("跳转到不同源 http://127.0.0.1:1，未跟随");
            assertThat(cross.warnings().get(0).message()).doesNotContain(token).doesNotContain("/path");

            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(EngineTestSupport.actionConch("bad_loc", "read", """
                    - request: { method: GET, path: /start, follow_redirects: true }
                    """, true)));
            registry.put(ConchParser.parse(EngineTestSupport.actionConch("kept_loc", "read", """
                    - request: { method: GET, path: /start, follow_redirects: true }
                    - assert: { status: 302 }
                    """, true)));
            registry.put(ConchParser.parse(EngineTestSupport.actionConch("wrong_loc", "read", """
                    - request: { method: GET, path: /start, follow_redirects: true }
                    - assert: { status: 200 }
                    """, true)));
            registry.put(ConchParser.parse(EngineTestSupport.actionConch("still_loc", "read", """
                    - request: { method: GET, path: /start, follow_redirects: false }
                    """, true)));
            registry.put(ConchParser.parse(EngineTestSupport.actionConch("post_loc", "read", """
                    - request: { method: POST, path: /start, follow_redirects: true }
                    """, true)));
            registry.put(ConchParser.parse(EngineTestSupport.actionConch("away_loc", "read", """
                    - request: { method: GET, path: /away, follow_redirects: true }
                    """, true)));
            CapturingSink sink = new CapturingSink();
            RuleEngine engine = new RuleEngine(registry, sessions);
            engine.bindDevTraceSink(sink);

            loop.reply(redirect(location));
            ExecutionResult missed = engine.execute(invoke(loop.base(), "bad_loc", "read"));
            assertThat(missed.success()).isFalse();
            assertThat(missed.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(missed.error().message()).isEqualTo(HttpCalls.UNRESOLVED_LOCATION);
            assertThat(missed.error().message()).doesNotContain("上游状态码").doesNotContain(token).doesNotContain("http://[");
            SecretChecks.absent(missed.error().message(), EngineTestSupport.PASSWORD);
            JsonNode warning = ApiJson.tree(sink.body).path("steps").get(0).path("warnings").get(0);
            assertThat(warning.path("code").asText()).isEqualTo(TraceWarning.LOCATION_UNRESOLVED);
            assertThat(warning.path("message").asText()).isEqualTo(HttpCalls.LOCATION_WARNING);
            assertThat(warning.path("message").asText()).doesNotContain(token);

            loop.reply(redirect(location));
            ExecutionResult kept = engine.execute(invoke(loop.base(), "kept_loc", "read"));
            assertThat(kept.success()).as(kept.error() == null ? "" : kept.error().message()).isTrue();
            assertThat(ApiJson.tree(sink.body).path("steps").get(0).path("warnings").get(0).path("message").asText())
                    .isEqualTo(HttpCalls.LOCATION_WARNING);

            loop.reply(redirect(location));
            ExecutionResult wrong = engine.execute(invoke(loop.base(), "wrong_loc", "read"));
            assertThat(wrong.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(wrong.error().message()).isEqualTo(HttpCalls.UNRESOLVED_LOCATION);
            assertThat(wrong.error().message()).doesNotContain("请核对上游");

            loop.reply(redirect(location));
            ExecutionResult ignored = engine.execute(invoke(loop.base(), "still_loc", "read"));
            assertThat(ignored.error().message()).isEqualTo(HttpCalls.upstreamStatus(302));
            assertThat(sink.body).doesNotContain(TraceWarning.LOCATION_UNRESOLVED);

            loop.reply(redirect(location));
            ExecutionResult posted = engine.execute(invoke(loop.base(), "post_loc", "read"));
            assertThat(posted.success()).as(posted.error() == null ? "" : posted.error().message()).isTrue();
            assertThat(ApiJson.tree(sink.body).path("steps").get(0).path("warnings").get(0).path("code").asText())
                    .isEqualTo(TraceWarning.LOCATION_UNRESOLVED);

            loop.reply(redirect("http://127.0.0.1:1/path?token=" + token));
            ExecutionResult away = engine.execute(invoke(loop.base(), "away_loc", "read"));
            assertThat(away.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(away.error().message()).isEqualTo(HttpCalls.upstreamStatus(302));
            assertThat(away.error().message()).doesNotContain(HttpCalls.UNRESOLVED_LOCATION).doesNotContain(token);
            JsonNode crossWarning = ApiJson.tree(sink.body).path("steps").get(0).path("warnings").get(0);
            assertThat(crossWarning.path("code").asText()).isEqualTo(TraceWarning.CROSS_ORIGIN);
            assertThat(crossWarning.path("message").asText()).doesNotContain(token).doesNotContain("/path");
        }
    }

    @Test
    void crossOriginAndHostMismatchAreRuleMismatchesBeforeConnect() throws Exception {
        try (ScriptedLoopback loop = ScriptedLoopback.start(); CloseableHttpClient client = plainClient()) {
            RuleProblem cross = failure(client, Map.of("url", "http://other.example/a"), loop.base());
            assertThat(cross.code).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(cross.summary).isEqualTo(HttpCalls.CROSS_ORIGIN_TARGET);
            assertThat(cross.summary).doesNotContain("other.example").doesNotContain("/a");
            assertThat(loop.hits()).isEmpty();

            RuleProblem host = failure(client, Map.of(
                    "url", loop.base() + "/a",
                    "headers", Map.of("Host", "token-host-9f3a.example")), loop.base());
            assertThat(host.code).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(host.summary).isEqualTo(HttpCalls.HOST_MISMATCH);
            assertThat(host.summary).doesNotContain("token-host-9f3a").doesNotContain("127.0.0.1");
            assertThat(loop.hits()).isEmpty();

            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            HttpResult matched = call(client, Map.of(
                    "path", "/a",
                    "headers", Map.of("Host", "127.0.0.1:" + loop.port())), loop.base());
            assertThat(matched.status()).isEqualTo(200);
            assertThat(loop.hits()).hasSize(1);
        }
    }

    @Test
    void objectQueryStaysJsonEncodedAndWarnsWithTheFieldName() throws Exception {
        String nested = "nested-secret-value";
        try (ScriptedLoopback loop = ScriptedLoopback.start(); CloseableHttpClient client = plainClient()) {
            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            LinkedHashMap<String, Object> object = new LinkedHashMap<>();
            object.put("a", 1);
            object.put("secret", nested);
            LinkedHashMap<String, Object> query = new LinkedHashMap<>();
            query.put("q", object);
            query.put("ids", List.of(1));
            query.put("name", "a b");
            query.put("n", 2);
            query.put("flag", false);
            HttpResult result = call(client, Map.of("path", "/q", "query", query), loop.base());
            assertThat(result.status()).isEqualTo(200);
            assertThat(loop.hits()).hasSize(1);
            assertThat(loop.hits().get(0).target()).isEqualTo(
                    "/q?q=%7B%22a%22:1,%22secret%22:%22" + nested + "%22%7D&ids=%5B1%5D&name=a%20b&n=2&flag=false");
            assertThat(result.warnings()).extracting(TraceWarning::message).containsExactly(
                    HttpCalls.notTextWarning("query", "q"),
                    HttpCalls.notTextWarning("query", "ids"));
            assertThat(result.warnings()).extracting(TraceWarning::code)
                    .containsOnly(TraceWarning.NOT_TEXT);
            for (TraceWarning warning : result.warnings()) {
                assertThat(warning.message()).doesNotContain(nested).doesNotContain("\"a\"");
            }

            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            LinkedHashMap<String, Object> form = new LinkedHashMap<>();
            form.put("q", Map.of("a", 1));
            HttpResult posted = call(client, Map.of(
                    "method", "POST",
                    "path", "/form",
                    "form", form), loop.base());
            assertThat(posted.status()).isEqualTo(200);
            assertThat(new String(loop.hits().get(1).body(), StandardCharsets.UTF_8))
                    .isEqualTo("q=%7B%22a%22%3A1%7D");
            assertThat(posted.warnings()).extracting(TraceWarning::message)
                    .containsExactly(HttpCalls.notTextWarning("form", "q"));
            assertThat(posted.warnings().get(0).message()).doesNotContain("\"a\"");

            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            HttpResult styled = call(client, Map.of(
                    "path", "/q",
                    "query_style", "form",
                    "query", Map.of("q", Map.of("a", 1))), loop.base());
            assertThat(styled.status()).isEqualTo(200);
            assertThat(loop.hits().get(2).target()).isEqualTo("/q?q=%7B%22a%22%3A1%7D");
            assertThat(styled.warnings()).extracting(TraceWarning::message)
                    .containsExactly(HttpCalls.notTextWarning("query", "q"));
        }
    }

    @Test
    void loopbackResponseHeadersRenderByLowerNameWithoutLeakingValues() throws Exception {
        String body = "{\"id\":\"BODY-NOT-IN-ERRORS\"}";
        try (ScriptedLoopback loop = ScriptedLoopback.start();
                CloseableHttpClient client = HttpClients.custom()
                        .disableRedirectHandling()
                        .disableAutomaticRetries()
                        .build()) {
            loop.reply(ScriptedLoopback.response(
                    200,
                    "application/json; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8),
                    "ETag: W/1",
                    "X-Token: s3cret value"));
            HttpResult result = HttpCalls.execute(
                    Map.of("method", "GET", "path", "/item"),
                    client,
                    () -> { },
                    URI.create(loop.base() + "/"),
                    null,
                    deadline());
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.body()).isEqualTo(body);
            assertThat(result.headers()).containsEntry("content-type", "application/json; charset=utf-8");
            assertThat(result.headers()).containsEntry("etag", "W/1");
            assertThat(result.headers()).containsEntry("x-token", "s3cret value");
            assertThat(result.headers()).doesNotContainKey("Content-Type");
            assertThat(result.headers()).doesNotContainKey("ETag");
            assertThat(result.headers()).doesNotContainKey("X-Token");

            TemplateEngine engine = new TemplateEngine();
            Map<String, Object> vars = Map.of("response", result.view());
            assertThat(engine.render("{{response.headers['content-type']}}", vars))
                    .isEqualTo("application/json; charset=utf-8");
            assertThat(engine.render("{{response.headers['Content-Type']}}", vars))
                    .isEqualTo("application/json; charset=utf-8");
            assertThat(engine.render("{{response.headers.ETag}}", vars)).isEqualTo("W/1");
            assertThat(engine.render("{{response.headers['x-token']}}", vars)).isEqualTo("s3cret value");
            assertThatThrownBy(() -> engine.render("{{response.headers['x-missing']}}", vars))
                    .isInstanceOf(TemplateException.class)
                    .hasMessage("response.headers['x-missing']: 路径不存在: response.headers['x-missing']")
                    .satisfies(error -> assertThat(error.getMessage())
                            .doesNotContain("application/json")
                            .doesNotContain("W/1")
                            .doesNotContain("s3cret")
                            .doesNotContain("s3cret%20value")
                            .doesNotContain("s3cret+value")
                            .doesNotContain("BODY-NOT-IN-ERRORS")
                            .doesNotContain("%20"));
            assertThatThrownBy(() -> engine.render("{{response.headers.content-type}}", vars))
                    .isInstanceOf(TemplateException.class)
                    .hasMessage("response.headers.content: 模板语法错误")
                    .satisfies(error -> assertThat(error.getMessage())
                            .doesNotContain("application/json")
                            .doesNotContain("s3cret")
                            .doesNotContain("BODY-NOT-IN-ERRORS"));
            assertThatThrownBy(() -> engine.render("{{response.headers.ContentType}}", vars))
                    .isInstanceOf(TemplateException.class)
                    .hasMessage("response.headers.ContentType: 路径不存在: response.headers.ContentType")
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain("application/json"));
        }
    }

    private static RuleProblem failure(CloseableHttpClient client, Map<String, Object> spec) {
        URI base = URI.create(String.valueOf(spec.get("url")));
        try {
            HttpCalls.execute(spec, client, () -> { }, base, null, deadline());
        } catch (RuleProblem problem) {
            return problem;
        }
        throw new AssertionError("expected a RuleProblem");
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    }

    private static CloseableHttpClient plainClient() {
        return HttpClients.custom().disableRedirectHandling().disableAutomaticRetries().build();
    }

    private static HttpResult call(CloseableHttpClient client, Map<String, Object> spec, String base) {
        return HttpCalls.execute(spec, client, () -> { }, URI.create(base + "/"), null, deadline());
    }

    private static RuleProblem failure(CloseableHttpClient client, Map<String, Object> spec, String base) {
        try {
            call(client, spec, base);
        } catch (RuleProblem problem) {
            return problem;
        }
        throw new AssertionError("expected a RuleProblem");
    }

    private static String redirect(String location) {
        return "HTTP/1.1 302 Found\r\nLocation: " + location
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    }

    private static ExecutionRequest invoke(String base, String conch, String action) {
        return EngineTestSupport.request(
                Origin.LOCAL,
                conch,
                action,
                "alice",
                Map.of(),
                EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, base));
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
