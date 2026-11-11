package dev.hermit.crab.gateway;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.CrabPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DerivedSecretTraceTest {
    private static final String USERNAME = "alice-user-91";
    private static final String PASSWORD = "p@ss-word-91";
    private static final String COOKIE = "cookie-secret-91";
    private static final String TOKEN = "session-token-91-alpha";

    @TempDir
    Path temp;

    @Test
    void loginExtractsAreMaskedAndTheMaskCannotLogIn() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/login", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean accepted = request.contains(USERNAME) && request.contains(PASSWORD);
            int status = accepted ? 200 : 401;
            String body = accepted
                    ? "{\"session\":\"" + TOKEN + "\",\"who\":\"" + USERNAME + "\",\"echo\":\"" + PASSWORD
                            + "\",\"crumb\":\"" + COOKIE + "\"}"
                    : "{\"ok\":false}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (accepted) {
                exchange.getResponseHeaders().add("Set-Cookie", "SID=" + COOKIE + "; Path=/");
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        upstream.createContext("/session", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getRequestBody().readAllBytes();
            int status = ("Bearer " + TOKEN).equals(authorization) ? 200 : 401;
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        upstream.createContext("/ping", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        upstream.start();
        CrabRuntime runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("crab")),
                CrabConfig.defaults().withListen("127.0.0.1", 0));
        CrabHttpServer server = new CrabHttpServer(runtime);
        try {
            server.start();
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            runtime.localConches().importBytes(yaml(base).getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
            runtime.admin().createAccount("secret_echo", "ada", Actors.CONSOLE);
            ConchDoc local = runtime.registry().find("secret_echo").orElseThrow();
            runtime.admin().writeCredentials(
                    local, "ada", java.util.Map.of("username", USERNAME, "password", PASSWORD), Actors.CONSOLE);
            runtime.debug().setEnabled(true, Actors.CONSOLE);

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest trial = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + server.port() + "/console/api/local-conches/secret_echo/actions/ping/run"))
                    .header("X-Hermit-Console", "1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"account\":\"ada\",\"input\":{}}"))
                    .build();
            HttpResponse<String> ran = http.send(trial, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(ran.statusCode()).as(ran.body()).isEqualTo(200);
            String traceId = ApiJson.tree(ran.body()).path("traceId").asText();
            String stored = runtime.devTraces().find(traceId).orElseThrow().body();
            assertThat(stored).doesNotContain(TOKEN).doesNotContain(PASSWORD).doesNotContain(USERNAME).doesNotContain(COOKIE);
            assertThat(stored).contains("***");

            HttpResponse<String> masked = http.send(HttpRequest.newBuilder(URI.create(base + "/session"))
                    .header("Authorization", "Bearer ***")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(masked.statusCode()).isEqualTo(401);
            HttpResponse<String> real = http.send(HttpRequest.newBuilder(URI.create(base + "/session"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(real.statusCode()).isEqualTo(200);
            HttpResponse<String> badLogin = http.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + USERNAME + "\",\"password\":\"***\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(badLogin.statusCode()).isEqualTo(401);
        } finally {
            server.close();
            runtime.close();
            upstream.stop(0);
        }
    }

    @Test
    void actionStepExtractsOfCredentialRequestsAreMasked() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/login", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean accepted = request.contains(USERNAME) && request.contains(PASSWORD);
            int status = accepted ? 200 : 401;
            String body = accepted ? "{\"session\":\"" + TOKEN + "\"}" : "{\"ok\":false}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        upstream.createContext("/session", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getRequestBody().readAllBytes();
            int status = ("Bearer " + TOKEN).equals(authorization) ? 200 : 401;
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        upstream.start();
        CrabRuntime runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("action-crab")),
                CrabConfig.defaults().withListen("127.0.0.1", 0));
        CrabHttpServer server = new CrabHttpServer(runtime);
        try {
            server.start();
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            runtime.localConches().importBytes(actionYaml(base).getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
            runtime.admin().createAccount("secret_echo", "ada", Actors.CONSOLE);
            ConchDoc local = runtime.registry().find("secret_echo").orElseThrow();
            runtime.admin().writeCredentials(
                    local, "ada", java.util.Map.of("username", USERNAME, "password", PASSWORD), Actors.CONSOLE);
            runtime.debug().setEnabled(true, Actors.CONSOLE);
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> ran = http.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + server.port() + "/console/api/local-conches/secret_echo/actions/ping/run"))
                    .header("X-Hermit-Console", "1")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"account\":\"ada\",\"input\":{}}"))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(ran.statusCode()).as(ran.body()).isEqualTo(200);
            String stored = runtime.devTraces().find(ApiJson.tree(ran.body()).path("traceId").asText()).orElseThrow().body();
            assertThat(stored).doesNotContain(TOKEN).doesNotContain(PASSWORD).doesNotContain(USERNAME);
            assertThat(stored).contains("***").contains("ready");
            HttpResponse<String> masked = http.send(HttpRequest.newBuilder(URI.create(base + "/session"))
                    .header("Authorization", "Bearer ***")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(masked.statusCode()).isEqualTo(401);
        } finally {
            server.close();
            runtime.close();
            upstream.stop(0);
        }
    }

    private static String yaml(String base) {
        return """
                conch: secret_echo
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: %s
                title: 登录追踪
                description: 登录响应里的派生值要打码。
                credentials:
                  username: { type: text, label: 用户名, required: true }
                  password: { type: secret, label: 密码, required: true }
                auth:
                  steps:
                    - request:
                        method: POST
                        path: /login
                        json:
                          username: "{{credential.username}}"
                          password: "{{credential.password}}"
                    - extract:
                        session: { jsonpath: $.session }
                    - assert:
                        status: 200
                        cookie_exists: SID
                actions:
                  ping:
                    title: 探测
                    description: 登录后再读一页。
                    idempotent: true
                    mcp: { expose: true, read_only: true }
                    steps:
                      - request: { method: GET, path: /ping }
                      - assert: { status: 200 }
                    output:
                      ok: { type: boolean, description: 是否成功, value: true }
                    examples:
                      - input: {}
                        output: { ok: true }
                    notes: 本地假上游。
                    related: []
                """.formatted(base);
    }

    private static String actionYaml(String base) {
        return """
                conch: secret_echo
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: %s
                title: 能力里的令牌
                description: 能力步骤里从凭证换来的值也要打码。
                credentials:
                  username: { type: text, label: 用户名, required: true }
                  password: { type: secret, label: 密码, required: true }
                actions:
                  ping:
                    title: 探测
                    description: 在能力里登录再带令牌。
                    idempotent: true
                    mcp: { expose: true, read_only: true }
                    steps:
                      - set: { label: ready }
                      - request:
                          method: POST
                          path: /login
                          json:
                            username: "{{credential.username}}"
                            password: "{{credential.password}}"
                      - extract:
                          session: { jsonpath: $.session }
                      - set: { note: ready }
                      - request:
                          method: GET
                          path: /session
                          headers:
                            Authorization: "Bearer {{session}}"
                      - assert: { status: 200 }
                    output:
                      ok: { type: boolean, description: 是否成功, value: true }
                    examples:
                      - input: {}
                        output: { ok: true }
                    notes: 本地假上游。
                    related: []
                """.formatted(base);
    }
}
