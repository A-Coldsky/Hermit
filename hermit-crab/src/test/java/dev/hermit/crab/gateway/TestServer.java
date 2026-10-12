package dev.hermit.crab.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.testing.LegacyOaFixture;

final class TestServer implements AutoCloseable {
    final LegacyOaFixture fixture;
    final CrabPaths paths;
    final CrabRuntime runtime;
    final CrabHttpServer server;
    final ShellDoc shell;
    final String password;
    final CreatedApiKey apiKey;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    TestServer(
            LegacyOaFixture fixture,
            CrabPaths paths,
            CrabRuntime runtime,
            CrabHttpServer server,
            ShellDoc shell,
            String password,
            CreatedApiKey apiKey
    ) {
        this.fixture = fixture;
        this.paths = paths;
        this.runtime = runtime;
        this.server = server;
        this.shell = shell;
        this.password = password;
        this.apiKey = apiKey;
        this.base = "http://127.0.0.1:" + server.port();
    }

    static TestServer open(Path root, String password, CrabConfig config) throws IOException {
        LegacyOaFixture fixture = new LegacyOaFixture(password);
        CrabPaths paths = new CrabPaths(root);
        CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null);
        try {
            ShellDoc shell = ShellParser.parse(shellYaml());
            runtime.registry().put(shell);
            CreatedApiKey apiKey = runtime.admin().createApiKey(
                    "gateway", new ApiKeyScope(java.util.Set.of(), java.util.Set.of(), false), Actors.CLI);
            CrabHttpServer server = new CrabHttpServer(runtime);
            server.start();
            return new TestServer(fixture, paths, runtime, server, shell, password, apiKey);
        } catch (RuntimeException ex) {
            runtime.close();
            fixture.close();
            throw ex;
        }
    }

    String base() {
        return base;
    }

    void configureAccount(String name) {
        if (runtime.admin().listAccounts(shell.shell()).stream().noneMatch(account -> account.account().equals(name))) {
            runtime.admin().createAccount(shell.shell(), name, Actors.CLI);
        }
        runtime.admin().putConfig(shell, "base_url", fixture.baseUrl(), Actors.CLI);
        runtime.admin().writeCredentials(
                shell, name, Map.of("username", name, "password", password), Actors.CLI);
    }

    Reply call(String method, String path, String key, String account, String idempotency, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (key != null) {
            builder.header("Authorization", "Bearer " + key);
        }
        if (account != null) {
            builder.header("X-Hermit-Account", account);
        }
        if (idempotency != null) {
            builder.header("Idempotency-Key", idempotency);
        }
        if ("GET".equals(method) || "HEAD".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), response.body(), response.headers().map());
    }

    Reply console(String method, String path, String body, boolean header) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (header) {
            builder.header("X-Hermit-Console", "1");
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), response.body(), response.headers().map());
    }

    static String shellYaml() throws IOException {
        String sample;
        try (InputStream in = TestServer.class.getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            if (in == null) {
                throw new IllegalStateException("classpath is missing samples/legacy_oa.yaml");
            }
            sample = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String action = """
                save_note:
                  title: 保存备注
                  description: 保存一条备注，用于网关写操作测试。
                  method: POST
                  input:
                    text: { type: string, description: 备注正文 }
                  steps:
                    - request:
                        method: GET
                        path: /todo/list.aspx
                    - set:
                        saved: true
                  output:
                    saved: { type: boolean, description: 是否已保存 }
                  examples:
                    - input: { text: hello }
                      output: { saved: true }
                  notes: 测试写能力。
                  mcp: { read_only: false }
                """.stripIndent().indent(2);
        return sample.replace("health_check:", action + "health_check:");
    }

    @Override
    public void close() {
        server.close();
        runtime.close();
        fixture.close();
    }

    record Reply(int status, String body, Map<String, List<String>> headers) {
        JsonNode json() {
            return ApiJson.tree(body);
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }
}
