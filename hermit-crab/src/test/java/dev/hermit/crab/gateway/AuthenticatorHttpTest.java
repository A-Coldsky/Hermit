package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.Origin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 授权器在整台 Crab 上：控制台绑定、调用、普通入口看不到授权器、绑定作废的几种情况。
 * 认证中心在 localhost，两个应用在 127.0.0.1。
 */
class AuthenticatorHttpTest {
    private static final String PASSWORD = "S3cr3t-authenticator";
    private static final String TOKEN = "token-export-9f3a7c";

    @TempDir
    Path temp;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger logins = new AtomicInteger();
    private HttpServer sso;
    private HttpServer app;
    private HttpServer other;
    private CrabRuntime runtime;
    private CrabHttpServer server;
    private String ssoUrl;
    private String appUrl;
    private String otherUrl;
    private String crab;
    private String key;

    @BeforeEach
    void start() throws IOException {
        sso = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        sso.createContext("/login", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.contains("S3cr3t-authenticator")) {
                respond(exchange, 401, "{}");
                return;
            }
            logins.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "TGC=tgc-1; Path=/");
            respond(exchange, 200, "{\"token\":\"" + TOKEN + "\"}");
        });
        sso.createContext("/me", exchange -> respond(exchange,
                String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")).contains("TGC=tgc-1") ? 200 : 401, "{}"));
        sso.start();
        app = app();
        other = app();
        ssoUrl = "http://localhost:" + sso.getAddress().getPort();
        appUrl = "http://127.0.0.1:" + app.getAddress().getPort();
        otherUrl = "http://127.0.0.1:" + other.getAddress().getPort();
        open();
        key = runtime.admin().createApiKey("all", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CLI)
                .plaintext();
    }

    private static HttpServer app() throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        created.createContext("/data", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean valid = ("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, valid ? 200 : 401, "{\"echo\":\"" + (valid ? TOKEN : "") + "\"}");
        });
        created.start();
        return created;
    }

    private void open() {
        runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("crab")),
                CrabConfig.defaults().withListen("127.0.0.1", 0),
                name -> null,
                Clock.systemUTC());
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
        sso.stop(0);
        app.stop(0);
        other.stop(0);
    }

    @Test
    void bindingIsConfirmedInTheConsoleAndPackagesShareOneLogin() throws Exception {
        installAll();
        Reply unbound = call("pkg_a");
        assertThat(unbound.status).isNotEqualTo(200);
        assertThat(unbound.json.toString()).contains("NOT_CONFIGURED").doesNotContain("corp_sso").doesNotContain("zhang");
        assertThat(logins.get()).isZero();

        String path = "/console/api/conches/pkg_a/accounts/default/authenticator";
        JsonNode check = console("POST", path + "/check", "{\"authAccount\":\"zhang\"}");
        assertThat(check.path("allowed").asBoolean()).isTrue();
        assertThat(check.path("baseUrl").asText()).isEqualTo(appUrl);
        assertThat(send("PUT", path, "{\"authAccount\":\"zhang\",\"baseUrl\":\"" + appUrl + "\"}").status)
                .as("绑定要明确确认").isEqualTo(400);
        assertThat(send("PUT", path, "{\"authAccount\":\"zhang\",\"baseUrl\":\"" + otherUrl + "\",\"confirm\":true}").status)
                .as("确认的地址要和现在的一致").isEqualTo(400);
        assertThat(console("POST", path + "/check", "{\"authAccount\":\"nobody\"}").path("allowed").asBoolean()).isFalse();
        bind("pkg_a");
        bind("pkg_b");

        Reply first = call("pkg_a");
        assertThat(first.status).as(String.valueOf(first.json)).isEqualTo(200);
        assertThat(first.json.toString()).as("导出的值是秘密，回显也要打码").doesNotContain(TOKEN);
        assertThat(call("pkg_b").status).isEqualTo(200);
        assertThat(logins.get()).as("两个包共用授权器账号的一次登录").isEqualTo(1);

        JsonNode account = console("GET", "/console/api/conches/pkg_a/accounts", null).path("accounts").get(0);
        assertThat(account.path("authenticator").path("conch").asText()).isEqualTo("corp_sso");
        assertThat(account.path("authenticator").path("account").asText()).isEqualTo("zhang");
        JsonNode listed = console("GET", "/console/api/authenticators", null).path("authenticators");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("accounts").get(0).path("consumers")).hasSize(2);
        assertThat(console("GET", "/console/api/audit", null).toString()).doesNotContain(PASSWORD).doesNotContain(TOKEN);

        // 重启后绑定还在。
        server.close();
        runtime.close();
        open();
        registerAll();
        assertThat(call("pkg_a").status).isEqualTo(200);

        assertThat(console("DELETE", path, null).path("unbound").asBoolean()).isTrue();
        assertThat(call("pkg_a").json.toString()).contains("NOT_CONFIGURED");
        assertThat(call("pkg_b").status).as("别的包的绑定不受影响").isEqualTo(200);
    }

    @Test
    void authenticatorsAreNotCallableAndNotListedAnywhereOutsideTheConsoleView() throws Exception {
        installAll();
        bind("pkg_a");
        assertThat(console("GET", "/console/api/conches", null).toString()).doesNotContain("corp_sso");
        assertThat(get("/openapi.json")).doesNotContain("corp_sso");
        HttpResponse<String> direct = http.send(HttpRequest.newBuilder(URI.create(crab + "/api/corp_sso/read"))
                .header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(direct.statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> runtime.admin().createApiKey(
                "sso", new ApiKeyScope(Set.of("corp_sso"), Set.of(), false, false), Actors.CLI))
                .isInstanceOf(IllegalArgumentException.class);
        // 控制台仍能看授权器的详情和账号。
        assertThat(console("GET", "/console/api/conches/corp_sso", null).path("authenticator").asBoolean()).isTrue();
        assertThat(console("GET", "/console/api/conches/corp_sso/accounts", null).path("accounts").get(0)
                .path("consumers")).hasSize(1);
        assertThat(console("GET", "/console/api/conches/pkg_a", null).path("authUse").path("conch").asText())
                .isEqualTo("corp_sso");
    }

    @Test
    void identitiesAndAuthenticatorBindingsExcludeEachOther() throws Exception {
        installAll();
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        for (String conch : new String[] {"pkg_a", "corp_sso"}) {
            JsonNode check = console("POST", "/console/api/identities/" + id + "/check",
                    "{\"conch\":\"" + conch + "\",\"account\":\"" + (conch.equals("pkg_a") ? "default" : "zhang") + "\"}");
            assertThat(check.path("allowed").asBoolean()).as(conch).isFalse();
        }
        assertThat(send("POST", "/console/api/identities/" + id + "/members",
                "{\"conch\":\"pkg_a\",\"account\":\"default\",\"confirm\":true}").status).isEqualTo(400);
    }

    @Test
    void aChangedAddressOrARemovedAuthenticatorAccountVoidsTheBinding() throws Exception {
        installAll();
        bind("pkg_a");
        bind("pkg_b");
        assertThat(call("pkg_a").status).isEqualTo(200);

        runtime.admin().putConfig(consumer("pkg_a", appUrl), "base_url", otherUrl, Actors.CLI);
        assertThat(runtime.authenticators().find(new SessionKey(Origin.OFFICIAL, "pkg_a", "default")))
                .as("地址变了，要重新确认").isNull();
        assertThat(call("pkg_a").json.toString()).contains("NOT_CONFIGURED");
        assertThat(call("pkg_b").status).isEqualTo(200);
        assertThat(logins.get()).as("pkg_a 的地址变化不清授权器的会话").isEqualTo(1);

        runtime.admin().deleteAccount("corp_sso", "zhang", Actors.CLI);
        assertThat(runtime.authenticators().list()).isEmpty();
        assertThat(call("pkg_b").json.toString()).contains("NOT_CONFIGURED");
    }

    @Test
    void anAuthenticatorCanBeSwitchedOffAndItsAddressCanBeProbed() throws Exception {
        installAll();
        bind("pkg_a");
        assertThat(call("pkg_a").status).isEqualTo(200);

        console("POST", "/console/api/conches/corp_sso/disable", null);
        assertThat(runtime.conchSwitchedOff("corp_sso")).isTrue();
        assertThat(call("pkg_a").json.toString()).as("授权器停用后按没有绑定处理").contains("NOT_CONFIGURED");
        console("POST", "/console/api/conches/corp_sso/enable", null);
        assertThat(call("pkg_a").status).isEqualTo(200);

        // 认证地址只写在授权器里：证书检测要认它（这里是明文端口，握手失败，但不是「不允许检测」）。
        Reply probe = send("POST", "/console/api/tls/probe",
                "{\"host\":\"localhost\",\"port\":" + sso.getAddress().getPort() + "}");
        assertThat(probe.status).as(String.valueOf(probe.json)).isEqualTo(200);
    }

    @Test
    void aBindingOnlyCoversTheAddressTheCallActuallyUses() throws Exception {
        installAll();
        bind("pkg_a");
        SessionKey account = new SessionKey(Origin.OFFICIAL, "pkg_a", "default");
        ConchDoc doc = consumer("pkg_a", appUrl);
        assertThat(runtime.authenticators().resolve(account, doc, appUrl)).isNotNull();
        // 排队期间地址被改掉又重新绑定：还在用旧地址的那次调用拿不到授权器的秘密。
        assertThat(runtime.authenticators().resolve(account, doc, otherUrl)).isNull();
    }

    @Test
    void aBindingSurvivesStartupWhileItsAuthenticatorIsNotLoadedYet() throws Exception {
        installAll();
        bind("pkg_a");
        server.close();
        runtime.close();
        open();
        // 启动时别的来源先载入：同名的普通包先到，绑定的那个授权器后到。
        runtime.registry().put(consumer("pkg_a", appUrl));
        runtime.registry().scoped(Origin.LOCAL).put(plain("corp_sso", appUrl));
        assertThat(runtime.authenticators().list()).as("授权器还没载入，不能据此作废绑定").hasSize(1);
    }

    private void installAll() {
        registerAll();
        ConchDoc authenticator = authenticator();
        runtime.admin().createAccount("corp_sso", "zhang", Actors.CLI);
        runtime.admin().writeCredentials(
                authenticator, "zhang", Map.of("username", "zhang", "password", PASSWORD), Actors.CLI);
        runtime.admin().createAccount("pkg_a", "default", Actors.CLI);
        runtime.admin().createAccount("pkg_b", "default", Actors.CLI);
    }

    private void registerAll() {
        runtime.registry().put(authenticator());
        runtime.registry().put(consumer("pkg_a", appUrl));
        runtime.registry().put(consumer("pkg_b", otherUrl));
    }

    private void bind(String conch) throws Exception {
        String base = conch.equals("pkg_a") ? appUrl : otherUrl;
        console("PUT", "/console/api/conches/" + conch + "/accounts/default/authenticator",
                "{\"authAccount\":\"zhang\",\"baseUrl\":\"" + base + "\",\"confirm\":true}");
    }

    private Reply call(String conch) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(crab + "/api/" + conch + "/read"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + key)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), ApiJson.tree(response.body()));
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(crab + path))
                .header("Authorization", "Bearer " + key).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    private JsonNode console(String method, String path, String body) throws Exception {
        Reply reply = send(method, path, body);
        assertThat(reply.status).as(String.valueOf(reply.json)).isEqualTo(200);
        return reply.json.path("data");
    }

    private Reply send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(crab + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1")
                .header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), ApiJson.tree(response.body()));
    }

    private record Reply(int status, JsonNode json) {
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private ConchDoc authenticator() {
        return ConchParser.parse("""
                conch: corp_sso
                version: 1.2.0
                driver: auth
                min_crab_version: 1.13.0
                title: 公司统一登录
                description: 公司 SSO 的登录
                credentials:
                  username: { type: text, label: 用户名, required: true }
                  password: { type: secret, label: 密码, required: true }
                auth:
                  origins: ["SSO"]
                  steps:
                    - request:
                        method: POST
                        url: SSO/login
                        form: { username: "{{credential.username}}", password: "{{credential.password}}" }
                    - assert: { status: 200 }
                    - extract: { access_token: { jsonpath: "$.token" } }
                  alive_check:
                    request: { method: GET, url: SSO/me }
                  exports:
                    access_token: { description: 访问令牌 }
                """.replace("SSO", ssoUrl));
    }

    /** 不用授权器的普通包。 */
    private static ConchDoc plain(String name, String baseUrl) {
        return ConchParser.parse(consumerYaml(name, baseUrl)
                .replace("auth:\n  use: corp_sso\n  min_version: 1.2.0\n",
                        "credentials:\n  token: { type: secret, label: 令牌, required: true }\n")
                .replace("          headers: { Authorization: \"Bearer {{auth.access_token}}\" }\n", ""));
    }

    private static ConchDoc consumer(String name, String baseUrl) {
        return ConchParser.parse(consumerYaml(name, baseUrl));
    }

    private static String consumerYaml(String name, String baseUrl) {
        return """
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.13.0
                base_url: %s
                title: 应用 %s
                description: 用授权器登录的包。
                config:
                  base_url: { type: url, label: 地址, required: false }
                auth:
                  use: corp_sso
                  min_version: 1.2.0
                actions:
                  read:
                    title: 读
                    description: 读一条数据。
                    idempotent: true
                    steps:
                      - request:
                          method: GET
                          path: /data
                          headers: { Authorization: "Bearer {{auth.access_token}}" }
                      - extract: { echo: { jsonpath: "$.echo" } }
                    output:
                      echo: { type: string, description: 回显 }
                    examples:
                      - input: {}
                        output: { echo: x }
                    notes: 无
                    related: []
                """.formatted(name, baseUrl, name);
    }
}
