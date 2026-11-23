package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.Invoker;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.runtime.ApiJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 两个互不知道对方的规则包访问同一个网站。网站同一时间只认最后一次登录的会话，
 * 所以两个包轮流调用时会互相顶掉。Crab 只提示和告警，不改调用结果。
 */
class SameSiteHttpTest {
    private static final String PASSWORD = "S3cr3t-same-site";

    @TempDir
    Path temp;

    private final Ticker clock = new Ticker();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger logins = new AtomicInteger();
    private volatile String current = "";
    private HttpServer site;
    private CrabRuntime runtime;
    private CrabHttpServer server;
    private String siteUrl;
    private String crab;

    @BeforeEach
    void start() throws IOException {
        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/login", exchange -> {
            exchange.getRequestBody().readAllBytes();
            current = UUID.randomUUID().toString();
            logins.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "sid=" + current + "; Path=/");
            respond(exchange, 200, "{\"ok\":true}");
        });
        site.createContext("/data", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            boolean valid = cookie != null && !current.isEmpty() && cookie.contains("sid=" + current);
            respond(exchange, valid ? 200 : 401, valid ? "{\"ok\":true}" : "{\"ok\":false}");
        });
        site.start();
        siteUrl = "http://127.0.0.1:" + site.getAddress().getPort();
        runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("crab")),
                CrabConfig.defaults().withListen("127.0.0.1", 0),
                name -> null,
                clock);
        server = new CrabHttpServer(runtime);
        server.start();
        crab = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (runtime != null) {
            runtime.close();
        }
        if (site != null) {
            site.stop(0);
        }
    }

    @Test
    void consoleListsEnabledPackagesOnTheSameEffectiveSite() throws Exception {
        // 包里写的地址不同；pkg_b 在本机配置里改成了 pkg_a 的网站，大小写和路径不影响判断。
        install("pkg_a", siteUrl, false);
        install("pkg_b", "http://127.0.0.1:9", false);
        install("pkg_c", "http://127.0.0.1:10", false);
        assertThat(sameSite("pkg_a")).isEmpty();

        JsonNode saved = console("PUT", "/console/api/conches/pkg_b/config",
                "{\"base_url\":\"" + siteUrl.replace("http://", "HTTP://") + "/oa/\"}");
        assertThat(names(saved.path("sameSite"))).containsExactly("pkg_a");

        assertThat(sameSite("pkg_a")).containsExactly("pkg_b");
        assertThat(sameSite("pkg_b")).containsExactly("pkg_a");
        assertThat(sameSite("pkg_c")).isEmpty();
        assertThat(detail("pkg_b").path("site").asText()).isEqualTo(siteUrl);
        for (JsonNode row : console("GET", "/console/api/conches", null).path("conches")) {
            List<String> expected = switch (row.path("conch").asText()) {
                case "pkg_a" -> List.of("pkg_b");
                case "pkg_b" -> List.of("pkg_a");
                default -> List.of();
            };
            assertThat(names(row.path("sameSite"))).isEqualTo(expected);
        }

        // 停用的包不算；重新启用时当场告诉使用者。
        JsonNode off = console("POST", "/console/api/conches/pkg_b/disable", "{}");
        assertThat(names(off.path("sameSite"))).isEmpty();
        assertThat(sameSite("pkg_a")).isEmpty();
        JsonNode on = console("POST", "/console/api/conches/pkg_b/enable", "{}");
        assertThat(names(on.path("sameSite"))).containsExactly("pkg_a");
    }

    @Test
    void alternatingReloginsRaiseAnAlertWithoutChangingCalls() throws Exception {
        install("pkg_a", siteUrl, false);
        install("pkg_b", siteUrl, false);
        String key = key("all", new ApiKeyScope(Set.of(), Set.of(), false, true));

        // 各自首次登录不算。
        assertThat(call("GET", "/api/pkg_a/read", key).status).isEqualTo(200);
        assertThat(call("GET", "/api/pkg_b/read", key).status).isEqualTo(200);
        assertThat(logins.get()).isEqualTo(2);
        assertThat(contention()).isEmpty();

        // 两轮：每次调用都被对方顶掉，引擎重新登录后重跑，调用方看到的仍是成功。
        for (int round = 0; round < 2; round++) {
            assertThat(call("GET", "/api/pkg_a/read", key).status).isEqualTo(200);
            assertThat(call("GET", "/api/pkg_b/read", key).status).isEqualTo(200);
        }
        assertThat(logins.get()).isEqualTo(6);
        assertThat(contention()).isEmpty();
        assertThat(detail("pkg_a").path("contendingWith")).isEmpty();

        assertThat(call("GET", "/api/pkg_a/read", key).status).isEqualTo(200);
        assertThat(call("GET", "/api/pkg_b/read", key).status).isEqualTo(200);
        JsonNode pairs = contention();
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).path("site").asText()).isEqualTo(siteUrl);
        assertThat(names(pairs.get(0).path("conches"))).containsExactly("pkg_a", "pkg_b");
        assertThat(names(detail("pkg_a").path("contendingWith"))).containsExactly("pkg_b");
        assertThat(names(detail("pkg_b").path("contendingWith"))).containsExactly("pkg_a");

        // 停用其中一个之后不再是同一网站上的两个包，告警不再显示。
        console("POST", "/console/api/conches/pkg_b/disable", "{}");
        assertThat(contention()).isEmpty();
        assertThat(detail("pkg_a").path("contendingWith")).isEmpty();
    }

    @Test
    void expiredSessionNamesTheOtherPackageOnlyToKeysThatMayCallIt() throws Exception {
        install("pkg_a", siteUrl, false);
        install("pkg_b", siteUrl, false);
        String all = key("all", new ApiKeyScope(Set.of(), Set.of(), false, true));
        String narrow = key("narrow", new ApiKeyScope(Set.of("pkg_a"), Set.of(), false));

        // 没有告警时，会话失效的报错和以前一样。
        assertThat(call("GET", "/api/pkg_a/read", all).status).isEqualTo(200);
        assertThat(call("GET", "/api/pkg_b/read", all).status).isEqualTo(200);
        Reply plain = call("POST", "/api/pkg_a/write", all);
        assertThat(plain.json.path("error").path("code").asText()).isEqualTo("SESSION_EXPIRED");
        assertThat(plain.json.path("error").path("message").asText()).isEqualTo("会话已失效");

        for (int round = 0; round < 4; round++) {
            call("GET", "/api/pkg_a/read", all);
            call("GET", "/api/pkg_b/read", all);
        }
        assertThat(contention()).hasSize(1);

        Reply seen = call("POST", "/api/pkg_a/write", all);
        assertThat(seen.json.path("error").path("code").asText()).isEqualTo("SESSION_EXPIRED");
        assertThat(seen.json.path("error").path("message").asText())
                .startsWith("会话已失效。").contains("pkg_b").contains("互相顶掉登录");

        // pkg_a 重新登录，再被 pkg_b 顶掉一次。
        assertThat(call("GET", "/api/pkg_a/read", all).status).isEqualTo(200);
        assertThat(call("GET", "/api/pkg_b/read", all).status).isEqualTo(200);
        Reply hidden = call("POST", "/api/pkg_a/write", narrow);
        assertThat(hidden.json.path("error").path("code").asText()).isEqualTo("SESSION_EXPIRED");
        assertThat(hidden.json.path("error").path("message").asText())
                .isEqualTo("会话已失效。" + Invoker.RIVAL_HINT)
                .doesNotContain("pkg_b");
    }

    @Test
    void reloginAfterAnIdleProbeIsNotCounted() throws Exception {
        // 两个包都写了 alive_check，每次调用之间都超过空闲时限：
        // 被顶掉是在探活时发现的，属于正常的空闲后重新登录。
        install("pkg_a", siteUrl, true);
        install("pkg_b", siteUrl, true);
        String key = key("all", new ApiKeyScope(Set.of(), Set.of(), false, true));
        Duration idle = Duration.ofMinutes(CrabConfig.defaults().sessionIdleMinutes()).plusSeconds(1);
        for (int round = 0; round < 5; round++) {
            assertThat(call("GET", "/api/pkg_a/read", key).status).isEqualTo(200);
            clock.advance(idle);
            assertThat(call("GET", "/api/pkg_b/read", key).status).isEqualTo(200);
            clock.advance(idle);
        }
        assertThat(logins.get()).isEqualTo(10);
        assertThat(contention()).isEmpty();
    }

    private void install(String name, String baseUrl, boolean aliveCheck) {
        ConchDoc doc = ConchParser.parse(yaml(name, baseUrl, aliveCheck));
        runtime.registry().put(doc);
        runtime.admin().createAccount(name, "default", Actors.CLI);
        runtime.admin().writeCredentials(doc, "default", Map.of("password", PASSWORD), Actors.CLI);
    }

    private String key(String name, ApiKeyScope scope) {
        CreatedApiKey created = runtime.admin().createApiKey(name, scope, Actors.CLI);
        return created.plaintext();
    }

    private List<String> sameSite(String conch) throws Exception {
        return names(detail(conch).path("sameSite"));
    }

    private JsonNode detail(String conch) throws Exception {
        return console("GET", "/console/api/conches/" + conch, null);
    }

    private JsonNode contention() throws Exception {
        return console("GET", "/console/api/conches", null).path("contention");
    }

    private static List<String> names(JsonNode array) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private JsonNode console(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(crab + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1")
                .header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return ApiJson.tree(response.body()).path("data");
    }

    private Reply call(String method, String path, String key) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(crab + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json");
        builder.method(method, "GET".equals(method)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString("{}"));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), ApiJson.tree(response.body()));
    }

    private record Reply(int status, JsonNode json) {
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String yaml(String name, String baseUrl, boolean aliveCheck) {
        String alive = aliveCheck
                ? """
                  alive_check:
                    request: { method: GET, path: /data }
                """
                : "";
        return """
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: %s
                title: 同网站 %s
                description: 另一位作者写的同一网站规则包。
                credentials:
                  password: { type: secret, label: 密码, required: true }
                config:
                  base_url: { type: url, label: 地址, required: false }
                auth:
                  steps:
                    - request:
                        method: POST
                        path: /login
                        form: { password: "{{credential.password}}" }
                    - assert: { cookie_exists: sid }
                %sactions:
                  read:
                    title: 读
                    description: 读一条数据。
                    idempotent: true
                    mcp: { expose: true, read_only: true }
                    steps:
                      - request: { method: GET, path: /data }
                      - set: { message: seen }
                    output:
                      message: { type: string, description: 文本, value: seen }
                    examples:
                      - input: {}
                        output: { message: seen }
                    notes: 无
                    related: []
                  write:
                    title: 写
                    description: 写一条数据，不能自动重跑。
                    method: POST
                    mcp: { expose: true, read_only: false }
                    steps:
                      - request: { method: POST, path: /data }
                      - set: { message: saved }
                    output:
                      message: { type: string, description: 文本, value: saved }
                    examples:
                      - input: {}
                        output: { message: saved }
                    notes: 无
                    related: []
                """.formatted(name, baseUrl, name, alive);
    }

    private static final class Ticker extends Clock {
        private volatile Instant now = Instant.now();

        void advance(Duration by) {
            now = now.plus(by);
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
