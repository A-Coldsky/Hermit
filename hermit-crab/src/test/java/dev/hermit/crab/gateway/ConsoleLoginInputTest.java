package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.LoginInputMessages;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.testing.SecretChecks;

class ConsoleLoginInputTest {
    private static final String PASSWORD = "HermitCaptcha-91ab";
    private static final String CODE = "WXYZ9";

    @TempDir
    Path temp;

    private HttpServer upstream;
    private ExecutorService executor;
    private final AtomicInteger captchaHits = new AtomicInteger();
    private final AtomicInteger tickHits = new AtomicInteger();

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void consoleCompletesAPausedLoginAndDoesNotCacheTheConflict() throws Exception {
        startUpstream();
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            String yaml = yaml(baseUrl());
            server.runtime.localConches().importBytes(yaml.getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
            ConchDoc doc = server.runtime.registry().find("captcha_oa").orElseThrow();
            server.runtime.admin().createAccount("captcha_oa", "alice", Actors.CONSOLE);
            server.runtime.admin().createAccount("captcha_oa", "bob", Actors.CONSOLE);
            server.runtime.admin().writeCredentials(
                    doc, "alice", Map.of("username", "alice", "password", PASSWORD), Actors.CONSOLE);
            server.runtime.admin().writeCredentials(
                    doc, "bob", Map.of("username", "bob", "password", PASSWORD), Actors.CONSOLE);

            TestServer.Reply paused = server.call(
                    "POST", "/api/captcha_oa/tick", server.apiKey.plaintext(), "alice", "cap-alice", "{}");
            assertThat(paused.status()).isEqualTo(409);
            assertThat(paused.json().path("error").path("code").asText()).isEqualTo("LOGIN_INPUT_REQUIRED");
            assertThat(paused.json().path("error").path("message").asText())
                    .isEqualTo(LoginInputMessages.required("captcha_oa"));
            assertThat(captchaHits).hasValue(1);

            TestServer.Reply bob = server.call(
                    "POST", "/api/captcha_oa/tick", server.apiKey.plaintext(), "bob", "cap-bob", "{}");
            assertThat(bob.status()).isEqualTo(409);
            assertThat(captchaHits).hasValue(2);

            TestServer.Reply early = server.console(
                    "POST", "/console/api/conches/captcha_oa/accounts/alice/login-input/resend", "{}", true);
            assertThat(early.status()).isEqualTo(409);
            assertThat(early.json().path("error").path("code").asText()).isEqualTo("CONFLICT");
            assertThat(early.json().path("error").path("message").asText()).isEqualTo(LoginInputMessages.TOO_FAST);

            TestServer.Reply waiting = server.console(
                    "GET", "/console/api/conches/captcha_oa/accounts/alice/login-input", null, true);
            assertThat(waiting.status()).isEqualTo(200);
            JsonNode view = waiting.json().path("data");
            assertThat(view.path("kind").asText()).isEqualTo("image");
            assertThat(view.path("label").asText()).isEqualTo("验证码");
            assertThat(view.path("hint").asText()).isEqualTo("看图");
            assertThat(view.path("hasImage").asBoolean()).isTrue();
            assertThat(view.path("canResend").asBoolean()).isTrue();
            assertThat(view.path("resendAfter").asInt()).isPositive();
            assertThat(view.path("expiresAt").asText()).contains("T");

            JsonNode alice = account(server, "alice");
            JsonNode bobRow = account(server, "bob");
            assertThat(alice.path("loginInputRequired").asBoolean()).isTrue();
            assertThat(alice.path("loginInputExpiresAt").asText()).isEqualTo(view.path("expiresAt").asText());
            assertThat(bobRow.path("loginInputRequired").asBoolean()).isTrue();
            assertThat(account(server, "alice").path("loginInputExpiresAt").isNull()).isFalse();

            byte[] first = image(server, "alice", "1");
            byte[] again = image(server, "alice", "2");
            assertThat(again).isEqualTo(first);
            assertThat(first).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);

            Thread.sleep(2100);
            TestServer.Reply resent = server.console(
                    "POST", "/console/api/conches/captcha_oa/accounts/alice/login-input/resend", "{}", true);
            assertThat(resent.status()).isEqualTo(200);
            assertThat(captchaHits).hasValue(3);
            assertThat(image(server, "alice", "3")).isNotEqualTo(first);

            TestServer.Reply replay = server.call(
                    "POST", "/api/captcha_oa/tick", server.apiKey.plaintext(), "alice", "cap-alice", "{}");
            assertThat(replay.status()).isEqualTo(409);
            assertThat(captchaHits).hasValue(3);

            TestServer.Reply dropped = server.console(
                    "DELETE", "/console/api/conches/captcha_oa/accounts/bob/login-input", null, true);
            assertThat(dropped.status()).isEqualTo(200);
            TestServer.Reply gone = server.console(
                    "GET", "/console/api/conches/captcha_oa/accounts/bob/login-input", null, true);
            assertThat(gone.status()).isEqualTo(404);
            TestServer.Reply bobAgain = server.call(
                    "POST", "/api/captcha_oa/tick", server.apiKey.plaintext(), "bob", "cap-bob", "{}");
            assertThat(bobAgain.status()).isEqualTo(409);
            assertThat(captchaHits).hasValue(4);

            TestServer.Reply submitted = server.console(
                    "POST",
                    "/console/api/conches/captcha_oa/accounts/alice/login-input",
                    "{\"value\":\"" + CODE + "\"}",
                    true);
            assertThat(submitted.status()).isEqualTo(200);
            assertThat(submitted.json().path("data").path("ok").asBoolean()).isTrue();
            SecretChecks.absent(submitted.body(), CODE);
            SecretChecks.absent(submitted.body(), PASSWORD);
            assertThat(account(server, "alice").path("loginInputRequired").asBoolean()).isFalse();
            assertThat(account(server, "alice").path("loginInputExpiresAt").isNull()).isTrue();

            TestServer.Reply done = server.call(
                    "POST", "/api/captcha_oa/tick", server.apiKey.plaintext(), "alice", "cap-alice", "{}");
            assertThat(done.status()).isEqualTo(200);
            assertThat(tickHits).hasValue(1);
            assertThat(captchaHits).hasValue(4);
            SecretChecks.absent(done.body(), CODE);
        }
    }

    @Test
    void codeLoginHasNoImageAndDoesNotClaimTheWaitIsGone() throws Exception {
        startUpstream();
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("code"), PASSWORD, config)) {
            server.runtime.localConches().importBytes(codeYaml(baseUrl()).getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
            ConchDoc doc = server.runtime.registry().find("captcha_oa").orElseThrow();
            server.runtime.admin().createAccount("captcha_oa", "alice", Actors.CONSOLE);
            server.runtime.admin().writeCredentials(
                    doc, "alice", Map.of("username", "alice", "password", PASSWORD), Actors.CONSOLE);
            TestServer.Reply paused = server.call(
                    "POST", "/api/captcha_oa/tick", server.apiKey.plaintext(), "alice", null, "{}");
            assertThat(paused.status()).isEqualTo(409);

            TestServer.Reply waiting = server.console(
                    "GET", "/console/api/conches/captcha_oa/accounts/alice/login-input", null, true);
            assertThat(waiting.status()).isEqualTo(200);
            assertThat(waiting.json().path("data").path("kind").asText()).isEqualTo("code");
            assertThat(waiting.json().path("data").path("hasImage").asBoolean()).isFalse();

            TestServer.Reply image = server.console(
                    "GET", "/console/api/conches/captcha_oa/accounts/alice/login-input/image", null, true);
            assertThat(image.status()).isEqualTo(404);
            assertThat(image.json().path("error").path("message").asText()).isEqualTo(LoginInputMessages.NO_IMAGE);
            SecretChecks.absent(image.body(), CODE);

            TestServer.Reply stillWaiting = server.console(
                    "GET", "/console/api/conches/captcha_oa/accounts/alice/login-input", null, true);
            assertThat(stillWaiting.status()).isEqualTo(200);
        }
    }

    @Test
    void credentialChangeAndReefDisableDropThePendingLogin() throws Exception {
        startUpstream();
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("other"), PASSWORD, config)) {
            server.runtime.localConches().importBytes(yaml(baseUrl()).getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
            ConchDoc doc = server.runtime.registry().find("captcha_oa").orElseThrow();
            server.runtime.admin().createAccount("captcha_oa", "alice", Actors.CONSOLE);
            server.runtime.admin().writeCredentials(
                    doc, "alice", Map.of("username", "alice", "password", PASSWORD), Actors.CONSOLE);
            TestServer.Reply paused = server.call(
                    "POST", "/api/captcha_oa/tick", server.apiKey.plaintext(), "alice", null, "{}");
            assertThat(paused.status()).isEqualTo(409);

            server.runtime.admin().writeCredentials(
                    doc, "alice", Map.of("username", "alice", "password", PASSWORD), Actors.CONSOLE);
            TestServer.Reply cleared = server.console(
                    "GET", "/console/api/conches/captcha_oa/accounts/alice/login-input", null, true);
            assertThat(cleared.status()).isEqualTo(404);
            assertThat(cleared.json().path("error").path("message").asText()).isEqualTo(LoginInputMessages.NONE);

            assertThat(server.call(
                    "POST", "/api/captcha_oa/tick", server.apiKey.plaintext(), "alice", null, "{}").status())
                    .isEqualTo(409);
            server.runtime.disabledConches().mark("captcha_oa", "1.0.0", Instant.parse("2026-11-17T00:00:00Z"));
            TestServer.Reply disabled = server.console(
                    "GET", "/console/api/conches/captcha_oa/accounts/alice/login-input", null, true);
            assertThat(disabled.status()).isEqualTo(404);
        }
    }

    private void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "console-captcha-http");
            thread.setDaemon(true);
            return thread;
        });
        upstream.setExecutor(executor);
        upstream.createContext("/", this::handle);
        upstream.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        if ("/captcha.png".equals(path)) {
            byte[] body = png(captchaHits.incrementAndGet());
            send(exchange, 200, "image/png", body);
            return;
        }
        if ("/sms".equals(path)) {
            send(exchange, 200, "application/json", "{\"sent\":true}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.startsWith("/login/")) {
            send(exchange, 200, "application/json", "{\"token\":\"session-token-value\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.startsWith("/tick/")) {
            tickHits.incrementAndGet();
            send(exchange, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(exchange, 404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8));
    }

    private byte[] image(TestServer server, String account, String cacheBust) throws Exception {
        HttpResponse<byte[]> response = server.http.send(
                HttpRequest.newBuilder(URI.create(server.base()
                        + "/console/api/conches/captcha_oa/accounts/" + account
                        + "/login-input/image?t=" + cacheBust))
                        .header("X-Hermit-Console", "1")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("image/png");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(response.body()).hasSizeLessThanOrEqualTo(512 * 1024);
        return response.body();
    }

    private static JsonNode account(TestServer server, String name) throws Exception {
        TestServer.Reply listed = server.console("GET", "/console/api/conches/captcha_oa/accounts", null, true);
        assertThat(listed.status()).isEqualTo(200);
        for (JsonNode row : listed.json().path("data").path("accounts")) {
            if (name.equals(row.path("name").asText())) {
                return row;
            }
        }
        throw new AssertionError("account was not listed");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    private static String codeYaml(String baseUrl) {
        return """
                conch: captcha_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.4.0
                base_url: %s
                title: 短信验证码
                description: 测试夹具。
                credentials:
                  username: { type: text, required: true, label: 用户名 }
                  password: { type: secret, required: true, label: 密码 }
                auth:
                  steps:
                    - request: { method: POST, path: /sms }
                    - ask:
                        into: captcha
                        kind: code
                        label: 短信验证码
                        hint: 看短信
                        timeout_s: 300
                    - request:
                        method: POST
                        path: "/login/{{captcha}}"
                        form:
                          code: "{{captcha}}"
                    - extract:
                        token: { jsonpath: $.token }
                actions:
                  tick:
                    title: 打点
                    description: 用登录令牌打点。
                    method: POST
                    output:
                      seen: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { seen: ok }
                    notes: 测试。
                    related: []
                    steps:
                      - request: { method: GET, path: "/tick/{{login.token}}" }
                      - set: { seen: ok }
                """.formatted(baseUrl);
    }

    private static String yaml(String baseUrl) {
        return """
                conch: captcha_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.4.0
                base_url: %s
                title: 验证码
                description: 测试夹具。
                credentials:
                  username: { type: text, required: true, label: 用户名 }
                  password: { type: secret, required: true, label: 密码 }
                auth:
                  steps:
                    - request: { method: GET, path: /captcha.png }
                    - ask:
                        into: captcha
                        kind: image
                        label: 验证码
                        hint: 看图
                        timeout_s: 300
                    - request:
                        method: POST
                        path: "/login/{{captcha}}"
                        form:
                          username: "{{credential.username}}"
                          password: "{{credential.password}}"
                          code: "{{captcha}}"
                    - extract:
                        token: { jsonpath: $.token }
                actions:
                  tick:
                    title: 打点
                    description: 用登录令牌打点。
                    method: POST
                    output:
                      seen: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { seen: ok }
                    notes: 测试。
                    related: []
                    steps:
                      - request: { method: GET, path: "/tick/{{login.token}}" }
                      - set: { seen: ok }
                """.formatted(baseUrl);
    }

    private static byte[] png(int mark) {
        return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, (byte) mark};
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
