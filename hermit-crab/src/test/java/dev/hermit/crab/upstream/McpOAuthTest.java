package dev.hermit.crab.upstream;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.AccountContext;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.gateway.CrabHttpServer;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.ConsolePassword;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.SecretChecks;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class McpOAuthTest {
    private static final String CLIENT_SECRET = "client-secret-alice-91";
    private static final String PASSWORD = "console-oauth-secret";

    @TempDir
    Path temp;

    @Test
    void twoAccountsRefreshReplayMaskingAndDisconnect() throws Exception {
        try (Upstream upstream = new Upstream();
                Harness harness = Harness.open(temp.resolve("accounts"), upstream.port, null)) {
            byte[] original = harness.oauthYaml(true).getBytes(StandardCharsets.UTF_8);
            harness.importLocal(original);
            harness.account("alice");
            harness.account("bob");
            harness.write("alice", Map.of(McpOAuthToken.CLIENT_SECRET, CLIENT_SECRET));
            harness.apiKey("vendor_oauth");

            JsonNode before = harness.accountRow("vendor_oauth", "alice");
            assertThat(before.path("needsConfiguration").asBoolean()).isFalse();
            assertThat(before.path("missingFields")).isEmpty();
            assertThat(before.path("oauth").path("connected").asBoolean()).isFalse();
            assertThat(before.path("oauth").path("expiresAt").isNull()).isTrue();

            Reply reserved = harness.console(
                    "PUT",
                    "/console/api/conches/vendor_oauth/accounts/alice/credentials",
                    "{\"oauth_token\":\"hand-typed-token-aaaa\"}",
                    null);
            assertThat(reserved.status).isEqualTo(400);
            assertThat(reserved.json.path("error").path("message").asText()).isEqualTo(McpOAuth.RESERVED);

            Reply alicePage = harness.connect("alice", "code-alice");
            Reply bobPage = harness.connect("bob", "code-bob");
            assertThat(alicePage.status).isEqualTo(200);
            assertThat(alicePage.body).contains(McpOAuth.CALLBACK_OK);
            assertThat(alicePage.header("Cache-Control")).contains("no-store");
            assertThat(alicePage.header("Referrer-Policy")).isEqualTo("no-referrer");
            Reply replay = harness.callback(harness.lastState, "code-alice-again");
            assertThat(replay.status).isEqualTo(400);
            assertThat(replay.body).contains(McpOAuth.CALLBACK_EXPIRED);

            assertThat(upstream.issued).hasSize(2);
            Issued alice = upstream.issued.get(0);
            Issued bob = upstream.issued.get(1);
            assertThat(alice.grant).isEqualTo("authorization_code");
            assertThat(alice.secret).isEqualTo(CLIENT_SECRET);
            assertThat(bob.secret).isNull();
            assertThat(alice.access).isNotEqualTo(bob.access);
            assertThat(challenge(harness.lastChallenges.get(0))).isEqualTo(s256(alice.verifier));
            assertThat(query(harness.lastChallenges.get(0)).get("code_challenge_method")).isEqualTo("S256");
            assertThat(query(harness.lastChallenges.get(0)).get("resource")).isEqualTo(harness.upstreamUrl());
            assertThat(query(harness.lastChallenges.get(0)).get("redirect_uri"))
                    .isEqualTo("http://127.0.0.1:" + harness.port() + "/console/oauth/callback");
            absent(alicePage.body, alice, bob, "code-alice", harness.lastState);
            absent(bobPage.body, alice, bob, "code-bob", harness.lastState);

            JsonNode connected = harness.accountRow("vendor_oauth", "alice");
            assertThat(connected.path("oauth").path("connected").asBoolean()).isTrue();
            Instant expiresAt = Instant.parse(connected.path("oauth").path("expiresAt").asText());
            assertThat(expiresAt).isAfter(Instant.now());
            Reply names = harness.console(
                    "GET", "/console/api/conches/vendor_oauth/accounts/alice/credentials", null, null);
            assertThat(names.json.path("data").path("fields").toString()).doesNotContain(McpOAuthToken.FIELD);
            absent(names.body, alice, bob, CLIENT_SECRET);

            int toolsBefore = upstream.tools.get();
            Reply aliceCall = harness.call("alice", "ada");
            Reply bobCall = harness.call("bob", "bea");
            assertThat(aliceCall.status).isEqualTo(200);
            assertThat(bobCall.status).isEqualTo(200);
            assertThat(upstream.issued).hasSize(4);
            Issued aliceRefresh = upstream.issued.get(2);
            Issued bobRefresh = upstream.issued.get(3);
            assertThat(aliceRefresh.grant).isEqualTo("refresh_token");
            assertThat(aliceRefresh.refreshUsed).isEqualTo(alice.refresh);
            assertThat(aliceRefresh.secret).isEqualTo(CLIENT_SECRET);
            assertThat(bobRefresh.refreshUsed).isEqualTo(bob.refresh);
            assertThat(bobRefresh.secret).isNull();
            assertThat(upstream.toolAuthorizations())
                    .containsExactly("Bearer " + aliceRefresh.access, "Bearer " + bobRefresh.access);
            absent(aliceCall.body, alice, bob, aliceRefresh, bobRefresh, CLIENT_SECRET);
            absent(bobCall.body, alice, bob, aliceRefresh, bobRefresh, CLIENT_SECRET);
            assertThat(aliceCall.json.path("data").path("echo").asText()).isEqualTo("***");

            upstream.unauthorizedLeft.set(1);
            int issuedBeforeReplay = upstream.issued.size();
            Reply retried = harness.call("alice", "ada");
            assertThat(retried.status).isEqualTo(200);
            assertThat(upstream.issued).hasSize(issuedBeforeReplay + 1);
            Issued rotated = upstream.issued.get(upstream.issued.size() - 1);
            assertThat(rotated.refreshUsed).isEqualTo(aliceRefresh.refresh);
            assertThat(rotated.refreshUsed).isNotEqualTo(alice.refresh);
            List<String> authorizations = upstream.toolAuthorizations();
            assertThat(authorizations.get(authorizations.size() - 2)).isEqualTo("Bearer " + aliceRefresh.access);
            assertThat(authorizations.get(authorizations.size() - 1)).isEqualTo("Bearer " + rotated.access);
            absent(retried.body, alice, bob, aliceRefresh, bobRefresh, rotated, CLIENT_SECRET);

            Reply bobAgain = harness.call("bob", "bea");
            assertThat(bobAgain.status).isEqualTo(200);
            assertThat(upstream.toolAuthorizations().get(upstream.toolAuthorizations().size() - 1))
                    .isEqualTo("Bearer " + bobRefresh.access);

            int toolsAtDisconnect = upstream.tools.get();
            Reply disconnected = harness.console(
                    "DELETE", "/console/api/conches/vendor_oauth/accounts/alice/oauth", null, null);
            assertThat(disconnected.status).isEqualTo(200);
            assertThat(disconnected.json.path("data").path("oauth").path("connected").asBoolean()).isFalse();
            Reply blocked = harness.call("alice", "ada");
            assertThat(blocked.status).isEqualTo(409);
            assertThat(blocked.json.path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(blocked.json.path("error").path("message").asText()).isEqualTo(McpForwarder.OAUTH_NOT_CONNECTED);
            assertThat(upstream.tools.get()).isEqualTo(toolsAtDisconnect);
            assertThat(harness.call("bob", "bea").status).isEqualTo(200);
            assertThat(toolsBefore).isZero();

            byte[] exported = harness.runtime.localConches().export("vendor_oauth").bytes();
            assertThat(exported).isEqualTo(original);
            absent(new String(exported, StandardCharsets.UTF_8), alice, bob, aliceRefresh, bobRefresh, rotated, CLIENT_SECRET);
            Reply audit = harness.console("GET", "/console/api/audit?conch=vendor_oauth", null, null);
            assertThat(audit.body).contains("\"oauth_token\"");
            assertThat(audit.body).contains("\"console\"");
            absent(audit.body, alice, bob, aliceRefresh, bobRefresh, rotated, CLIENT_SECRET);

            byte[] headers = headersYaml(upstream.port).getBytes(StandardCharsets.UTF_8);
            harness.importLocal(headers);
            harness.account("vendor_headers", "gina");
            JsonNode gina = harness.accountRow("vendor_headers", "gina");
            assertThat(gina.path("oauth").isNull()).isTrue();
            Reply notOAuth = harness.console(
                    "DELETE", "/console/api/conches/vendor_headers/accounts/gina/oauth", null, null);
            assertThat(notOAuth.status).isEqualTo(409);
            assertThat(notOAuth.json.path("error").path("message").asText()).isEqualTo(McpOAuth.NOT_OAUTH);
        }
    }

    @Test
    void refreshFailureDoesNotCallTheTool() throws Exception {
        try (Upstream upstream = new Upstream();
                Harness harness = Harness.open(temp.resolve("refresh"), upstream.port, null)) {
            harness.importLocal(harness.oauthYaml(true).getBytes(StandardCharsets.UTF_8));
            harness.account("alice");
            harness.account("bob");
            harness.apiKey("vendor_oauth");
            harness.connect("alice", "code-alice");
            harness.connect("bob", "code-bob");

            upstream.rejectRefresh = true;
            int tokens = upstream.tokenPosts.get();
            Reply rejected = harness.call("alice", "ada");
            assertThat(rejected.status).isEqualTo(422);
            assertThat(rejected.json.path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
            assertThat(rejected.json.path("error").path("message").asText()).isEqualTo(McpOAuth.REJECTED);
            assertThat(upstream.tools.get()).isZero();
            assertThat(upstream.tokenPosts.get()).isEqualTo(tokens + 1);
            Reply again = harness.call("alice", "ada");
            assertThat(again.status).isEqualTo(409);
            assertThat(again.json.path("error").path("message").asText()).isEqualTo(McpForwarder.OAUTH_NOT_CONNECTED);
            assertThat(upstream.tokenPosts.get()).isEqualTo(tokens + 1);
            assertThat(upstream.tools.get()).isZero();

            upstream.rejectRefresh = false;
            upstream.redirectTo = upstream.sinkPort;
            int bobTokens = upstream.tokenPosts.get();
            Reply redirected = harness.call("bob", "bea");
            assertThat(redirected.status).isEqualTo(422);
            assertThat(redirected.json.path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
            assertThat(upstream.tools.get()).isZero();
            assertThat(upstream.sinkHits.get()).isZero();
            assertThat(upstream.tokenPosts.get()).isEqualTo(bobTokens + 1);
            assertThat(harness.call("bob", "bea").status).isEqualTo(409);
            assertThat(upstream.tokenPosts.get()).isEqualTo(bobTokens + 1);
        }
    }

    @Test
    void discoveryHonorsTheAllowlist() throws Exception {
        try (Upstream upstream = new Upstream();
                Harness harness = Harness.open(temp.resolve("discover"), upstream.port, null)) {
            harness.importLocal(harness.oauthYaml(false).getBytes(StandardCharsets.UTF_8));
            harness.account("alice");
            upstream.probe = true;
            upstream.metaBody = "{\"authorization_servers\":[\"http://localhost:" + upstream.port + "/as\"]}";
            Reply denied = harness.console(
                    "POST", "/console/api/conches/vendor_oauth/accounts/alice/oauth/start", "{}", null);
            assertThat(denied.status).isEqualTo(409);
            assertThat(denied.json.path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(denied.json.path("error").path("message").asText()).isEqualTo(McpForwarder.HOST_DENIED);
            assertThat(upstream.hosts()).noneMatch(host -> host.toLowerCase().startsWith("localhost"));
            assertThat(upstream.paths()).contains("/meta");
            assertThat(upstream.paths()).doesNotContain("/.well-known/oauth-protected-resource/mcp");

            upstream.probe = false;
            upstream.hits.clear();
            upstream.protectedBody = "{\"authorization_servers\":[\"http://127.0.0.1:" + upstream.port + "\"]}";
            upstream.serverBody = """
                    {"authorization_endpoint":"http://127.0.0.1:%d/authorize","token_endpoint":"http://127.0.0.1:%d/token"}
                    """.formatted(upstream.port, upstream.port);
            Reply started = harness.console(
                    "POST", "/console/api/conches/vendor_oauth/accounts/alice/oauth/start", "{}", null);
            assertThat(started.status).isEqualTo(200);
            String url = started.json.path("data").path("authorizationUrl").asText();
            assertThat(url).contains("/authorize");
            assertThat(query(url).get("code_challenge_method")).isEqualTo("S256");
            assertThat(query(url).get("resource")).isEqualTo(harness.upstreamUrl());
            assertThat(upstream.paths()).contains("/.well-known/oauth-protected-resource/mcp");
            assertThat(upstream.paths()).contains("/.well-known/oauth-authorization-server");
            assertThat(upstream.paths()).doesNotContain("/.well-known/oauth-protected-resource");
            assertThat(upstream.hosts()).noneMatch(host -> host.toLowerCase().startsWith("localhost"));
        }
    }

    @Test
    void callbackDoesNotNeedTheConsoleCookie() throws Exception {
        try (Upstream upstream = new Upstream();
                Harness harness = Harness.open(temp.resolve("cookie"), upstream.port, PASSWORD)) {
            harness.importLocal(harness.oauthYaml(true).getBytes(StandardCharsets.UTF_8));
            harness.account("alice");
            Reply locked = harness.console(
                    "POST", "/console/api/conches/vendor_oauth/accounts/alice/oauth/start", "{}", null);
            assertThat(locked.status).isEqualTo(401);
            assertThat(locked.json.path("error").path("message").asText()).isEqualTo("需要控制台登录。");

            Reply login = harness.console(
                    "POST", "/console/api/session", "{\"password\":\"" + PASSWORD + "\"}", null);
            assertThat(login.status).isEqualTo(200);
            String cookie = login.header("Set-Cookie");
            assertThat(cookie).contains("hermit_console=");
            harness.cookie = cookie.substring(0, cookie.indexOf(';'));

            Reply started = harness.console(
                    "POST", "/console/api/conches/vendor_oauth/accounts/alice/oauth/start", "{}", harness.cookie);
            assertThat(started.status).isEqualTo(200);
            String state = query(started.json.path("data").path("authorizationUrl").asText()).get("state");
            Reply denied = harness.callback(state, null, "access_denied");
            assertThat(denied.status).isEqualTo(400);
            assertThat(denied.body).contains(McpOAuth.CALLBACK_DENIED);
            assertThat(denied.body).doesNotContain("access_denied");
            assertThat(denied.body).doesNotContain(state);

            Reply startedAgain = harness.console(
                    "POST", "/console/api/conches/vendor_oauth/accounts/alice/oauth/start", "{}", harness.cookie);
            String next = query(startedAgain.json.path("data").path("authorizationUrl").asText()).get("state");
            Reply page = harness.callback(next, "code-cookie");
            assertThat(page.status).isEqualTo(200);
            assertThat(page.body).contains(McpOAuth.CALLBACK_OK);
            assertThat(page.body).doesNotContain("code-cookie");
            SecretChecks.absent(page.body, upstream.issued.get(0).access);
            Reply junk = harness.callback("evilstatevalue", "code-junk");
            assertThat(junk.status).isEqualTo(400);
            assertThat(junk.body).contains(McpOAuth.CALLBACK_EXPIRED);
            assertThat(junk.body).doesNotContain("evilstatevalue");
        }
    }

    @Test
    void tokenHostOutsideTheAllowlistNeverConnects() throws Exception {
        try (Upstream upstream = new Upstream()) {
            ConchDoc doc = ConchParser.parse("""
                    conch: vendor_oauth
                    version: 1.0.0
                    driver: mcp
                    min_crab_version: 1.1.0
                    title: 客户系统
                    description: 端点不在允许列表。
                    credentials: {}
                    upstream:
                      url: http://127.0.0.1:%d/mcp
                      transport: streamable_http
                      allowed_hosts: ["127.0.0.1"]
                      auth:
                        type: oauth
                        scopes: [mcp]
                        client_id: hermit-crab
                        authorization_endpoint: http://127.0.0.1:%d/authorize
                        token_endpoint: http://localhost:%d/token
                    actions:
                      search_customer:
                        tool: searchCustomer
                        title: 搜索客户
                        description: 按名称搜索。
                        mcp: {read_only: true}
                        input_schema:
                          type: object
                          properties:
                            q: {type: string}
                          required: [q]
                    """.formatted(upstream.port, upstream.port, upstream.port));
            String stored = """
                    {"access_token":"access-old-aaaa","refresh_token":"refresh-old-bbbb","expires_at":"2000-01-01T00:00:00Z","token_type":"Bearer"}
                    """;
            try (SessionManager accounts = new SessionManager();
                    McpForwarder forwarder = new McpForwarder(accounts, KnownSecrets.empty(), Duration.ofSeconds(2), 1)) {
                ExecutionResult result = forwarder.execute(new ExecutionRequest(
                        "trace-oauth-host",
                        Origin.OFFICIAL,
                        doc.conch(),
                        "search_customer",
                        Map.of("q", "ada"),
                        "alice",
                        new AccountContext() {
                            @Override
                            public Map<String, String> credentials() {
                                return Map.of(McpOAuthToken.FIELD, stored);
                            }

                            @Override
                            public Map<String, String> config() {
                                return Map.of();
                            }
                        }), doc);
                assertThat(result.success()).isFalse();
                assertThat(result.error().code().name()).isEqualTo("NOT_CONFIGURED");
                assertThat(result.error().message()).isEqualTo(McpForwarder.HOST_DENIED);
                SecretChecks.absent(result.error().message(), "access-old-aaaa");
                SecretChecks.absent(result.error().message(), "refresh-old-bbbb");
            }
            assertThat(upstream.hits).isEmpty();
        }
    }

    private static void absent(String text, Object... secrets) {
        for (Object secret : secrets) {
            if (secret instanceof Issued issued) {
                SecretChecks.absent(text, issued.access);
                SecretChecks.absent(text, issued.refresh);
            } else if (secret != null) {
                SecretChecks.absent(text, String.valueOf(secret));
            }
        }
    }

    private static String challenge(String url) {
        return query(url).get("code_challenge");
    }

    private static Map<String, String> query(String url) {
        String raw = URI.create(url).getRawQuery();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            values.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String s256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String headersYaml(int port) {
        return """
                conch: vendor_headers
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 请求头认证
                description: 不是 OAuth。
                credentials:
                  token: {type: secret, label: 令牌, required: true}
                upstream:
                  url: http://127.0.0.1:%d/mcp
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称搜索。
                    mcp: {read_only: true}
                    input_schema:
                      type: object
                      properties:
                        q: {type: string}
                      required: [q]
                """.formatted(port);
    }

    private static final class Harness implements AutoCloseable {
        final CrabRuntime runtime;
        final CrabHttpServer server;
        final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        final int upstreamPort;
        final List<String> lastChallenges = new ArrayList<>();
        CreatedApiKey key;
        String cookie;
        String lastState;

        Harness(CrabRuntime runtime, CrabHttpServer server, int upstreamPort) {
            this.runtime = runtime;
            this.server = server;
            this.upstreamPort = upstreamPort;
        }

        static Harness open(Path root, int upstreamPort, String password) throws Exception {
            CrabRuntime runtime = CrabRuntime.open(
                    new CrabPaths(root),
                    CrabConfig.defaults().withListen("127.0.0.1", 0).withLimits(5, 4),
                    name -> ConsolePassword.ENV.equals(name) ? password : null);
            CrabHttpServer server = new CrabHttpServer(runtime);
            server.start();
            return new Harness(runtime, server, upstreamPort);
        }

        int port() {
            return server.port();
        }

        String upstreamUrl() {
            return "http://127.0.0.1:" + upstreamPort + "/mcp";
        }

        String oauthYaml(boolean endpoints) {
            String endpointLines = endpoints
                    ? "    authorization_endpoint: http://127.0.0.1:" + upstreamPort + "/authorize\n"
                            + "    token_endpoint: http://127.0.0.1:" + upstreamPort + "/token\n"
                    : "";
            return """
                    conch: vendor_oauth
                    version: 1.0.0
                    driver: mcp
                    min_crab_version: 1.1.0
                    title: 客户系统
                    description: 上游 OAuth。
                    credentials:
                      oauth_token: {type: secret, label: 访问令牌, required: true}
                      client_secret: {type: secret, label: 客户端机密, required: false}
                    upstream:
                      url: http://127.0.0.1:%d/mcp
                      transport: streamable_http
                      auth:
                        type: oauth
                        scopes: [mcp]
                        client_id: hermit-crab
                    %sactions:
                      search_customer:
                        tool: searchCustomer
                        title: 搜索客户
                        description: 按名称搜索。
                        mcp: {read_only: true}
                        input_schema:
                          type: object
                          properties:
                            q: {type: string}
                          required: [q]
                    """.formatted(upstreamPort, endpointLines);
        }

        void importLocal(byte[] yaml) {
            runtime.localConches().importBytes(yaml, Actors.CONSOLE);
        }

        void account(String name) {
            account("vendor_oauth", name);
        }

        void account(String conch, String name) {
            runtime.admin().createAccount(conch, name, Actors.CONSOLE);
        }

        void write(String account, Map<String, String> fields) {
            ConchDoc doc = runtime.registry().find("vendor_oauth").orElseThrow();
            runtime.admin().writeCredentials(doc, account, fields, Actors.CONSOLE);
        }

        void apiKey(String conch) {
            key = runtime.admin().createApiKey(
                    "oauth", new ApiKeyScope(Set.of(conch), Set.of(), false), Actors.CONSOLE);
        }

        Reply connect(String account, String code) throws Exception {
            Reply started = console(
                    "POST",
                    "/console/api/conches/vendor_oauth/accounts/" + account + "/oauth/start",
                    "{}",
                    cookie);
            assertThat(started.status).isEqualTo(200);
            String url = started.json.path("data").path("authorizationUrl").asText();
            lastChallenges.add(url);
            lastState = query(url).get("state");
            return callback(lastState, code);
        }

        Reply callback(String state, String code) throws Exception {
            return callback(state, code, null);
        }

        Reply callback(String state, String code, String error) throws Exception {
            StringBuilder path = new StringBuilder("/console/oauth/callback?state=")
                    .append(URLEncoder.encode(state, StandardCharsets.UTF_8));
            if (code != null) {
                path.append("&code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
            }
            if (error != null) {
                path.append("&error=").append(URLEncoder.encode(error, StandardCharsets.UTF_8));
            }
            return send("GET", path.toString(), null, null, false);
        }

        Reply call(String account, String q) throws Exception {
            return send(
                    "POST",
                    "/api/vendor_oauth/search_customer",
                    "{\"q\":\"" + q + "\"}",
                    account,
                    true);
        }

        Reply console(String method, String path, String body, String cookieHeader) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("X-Hermit-Console", "1");
            if (cookieHeader != null) {
                builder.header("Cookie", cookieHeader);
            }
            if ("GET".equals(method)) {
                builder.GET();
            } else if ("DELETE".equals(method)) {
                builder.DELETE();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
                builder.header("Content-Type", "application/json");
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response);
        }

        JsonNode accountRow(String conch, String name) throws Exception {
            Reply reply = console("GET", "/console/api/conches/" + conch + "/accounts", null, cookie);
            assertThat(reply.status).isEqualTo(200);
            for (JsonNode row : reply.json.path("data").path("accounts")) {
                if (name.equals(row.path("name").asText())) {
                    return row;
                }
            }
            throw new AssertionError("missing account " + name);
        }

        private Reply send(String method, String path, String body, String account, boolean api) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(8));
            if (api) {
                builder.header("Authorization", "Bearer " + key.plaintext());
                builder.header("X-Hermit-Account", account);
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response);
        }

        private String base() {
            return "http://127.0.0.1:" + server.port();
        }

        @Override
        public void close() {
            server.close();
            runtime.close();
        }
    }

    private record Reply(int status, String body, JsonNode json, Map<String, List<String>> headers) {
        Reply(HttpResponse<String> response) {
            this(response.statusCode(), response.body(), tree(response.body()), response.headers().map());
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        private static JsonNode tree(String body) {
            if (body == null || body.isBlank() || body.charAt(0) != '{' && body.charAt(0) != '[') {
                return ApiJson.tree("{}");
            }
            return ApiJson.tree(body);
        }
    }

    private static final class Issued {
        final String access;
        final String refresh;
        final String grant;
        final String refreshUsed;
        final String verifier;
        final String secret;
        final String resource;

        Issued(String access, String refresh, Map<String, String> form) {
            this.access = access;
            this.refresh = refresh;
            this.grant = form.get("grant_type");
            this.refreshUsed = form.get("refresh_token");
            this.verifier = form.get("code_verifier");
            this.secret = form.get("client_secret");
            this.resource = form.get("resource");
        }
    }

    private static final class Upstream implements AutoCloseable {
        final Javalin app;
        final int port;
        final List<Hit> hits = new CopyOnWriteArrayList<>();
        final List<Issued> issued = new CopyOnWriteArrayList<>();
        final AtomicInteger sequence = new AtomicInteger();
        final AtomicInteger tools = new AtomicInteger();
        final AtomicInteger tokenPosts = new AtomicInteger();
        final AtomicInteger unauthorizedLeft = new AtomicInteger();
        final AtomicInteger sinkHits = new AtomicInteger();
        final int sinkPort;
        volatile boolean rejectRefresh;
        volatile boolean probe;
        volatile int redirectTo;
        volatile String metaBody = "{}";
        volatile String protectedBody = "{}";
        volatile String serverBody = "{}";
        private final String sessionId = "upstream-session-oauth";
        private final Javalin sink;

        Upstream() {
            app = Javalin.create(config -> config.showJavalinBanner = false);
            app.post("/token", this::token);
            app.post("/mcp", this::mcp);
            app.delete("/mcp", ctx -> {
                record(ctx, null);
                ctx.status(200);
            });
            app.get("/mcp", ctx -> {
                record(ctx, null);
                if (probe) {
                    ctx.status(401);
                    ctx.header("WWW-Authenticate", "Bearer resource_metadata=\"http://127.0.0.1:" + app.port() + "/meta\"");
                    return;
                }
                ctx.status(404);
            });
            app.get("/meta", ctx -> {
                record(ctx, null);
                ctx.contentType("application/json");
                ctx.result(metaBody);
            });
            app.get("/.well-known/oauth-protected-resource/mcp", ctx -> {
                record(ctx, null);
                ctx.contentType("application/json");
                ctx.result(protectedBody);
            });
            app.get("/.well-known/oauth-protected-resource", ctx -> {
                record(ctx, null);
                ctx.contentType("application/json");
                ctx.result(protectedBody);
            });
            app.get("/.well-known/oauth-authorization-server", ctx -> {
                record(ctx, null);
                ctx.contentType("application/json");
                ctx.result(serverBody);
            });
            app.start(0);
            port = app.port();
            sink = Javalin.create(config -> config.showJavalinBanner = false);
            sink.post("/*", ctx -> sinkHits.incrementAndGet());
            sink.get("/*", ctx -> sinkHits.incrementAndGet());
            sink.start(0);
            sinkPort = sink.port();
        }

        List<String> toolAuthorizations() {
            ArrayList<String> values = new ArrayList<>();
            for (Hit hit : hits) {
                if ("tools/call".equals(hit.kind) && hit.authorization != null) {
                    values.add(hit.authorization);
                }
            }
            return values;
        }

        List<String> hosts() {
            ArrayList<String> values = new ArrayList<>();
            for (Hit hit : hits) {
                if (hit.host != null) {
                    values.add(hit.host);
                }
            }
            return values;
        }

        List<String> paths() {
            ArrayList<String> values = new ArrayList<>();
            for (Hit hit : hits) {
                values.add(hit.path);
            }
            return values;
        }

        private void token(io.javalin.http.Context ctx) {
            tokenPosts.incrementAndGet();
            String body = ctx.body();
            record(ctx, body);
            Map<String, String> form = form(body);
            if ("refresh_token".equals(form.get("grant_type")) && rejectRefresh) {
                ctx.status(400).contentType("application/json").result("{\"error\":\"invalid_grant\"}");
                return;
            }
            if ("refresh_token".equals(form.get("grant_type")) && redirectTo > 0) {
                ctx.status(302);
                ctx.header("Location", "http://127.0.0.1:" + redirectTo + "/sink");
                return;
            }
            int n = sequence.incrementAndGet();
            String access = "access-" + n + "-aaaa";
            String refresh = "refresh-" + n + "-bbbb";
            issued.add(new Issued(access, refresh, form));
            int expires = "refresh_token".equals(form.get("grant_type")) ? 3600 : 30;
            ctx.contentType("application/json");
            ctx.result("""
                    {"access_token":"%s","refresh_token":"%s","token_type":"Bearer","expires_in":%d}
                    """.formatted(access, refresh, expires));
        }

        private void mcp(io.javalin.http.Context ctx) throws Exception {
            String body = ctx.body();
            JsonNode node = body == null || body.isBlank() ? ApiJson.tree("{}") : ApiJson.tree(body);
            String method = node.path("method").asText("");
            record(ctx, body, method);
            if ("notifications/initialized".equals(method)) {
                ctx.status(202);
                return;
            }
            if ("initialize".equals(method)) {
                ctx.status(200);
                ctx.header("mcp-session-id", sessionId);
                ctx.contentType("application/json");
                ctx.result("""
                        {"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"mock","version":"0"}},"jsonrpc":"2.0","id":%s}
                        """.formatted(node.path("id").asText("1")));
                return;
            }
            if (!"tools/call".equals(method)) {
                ctx.status(400);
                return;
            }
            tools.incrementAndGet();
            if (unauthorizedLeft.get() > 0) {
                unauthorizedLeft.decrementAndGet();
                ctx.status(401);
                return;
            }
            String bearer = ctx.header("Authorization");
            String token = bearer == null ? "" : bearer.replace("Bearer ", "");
            ctx.status(200);
            ctx.header("mcp-session-id", sessionId);
            ctx.contentType("application/json");
            ctx.result("""
                    {"result":{"content":[{"type":"text","text":"%s"}],"structuredContent":{"echo":"%s"}},"jsonrpc":"2.0","id":%s}
                    """.formatted(token, token, node.path("id").asText("1")));
        }

        private void record(io.javalin.http.Context ctx, String body) {
            record(ctx, body, ctx.path());
        }

        private void record(io.javalin.http.Context ctx, String body, String kind) {
            hits.add(new Hit(ctx.method().name(), ctx.path(), ctx.header("Host"), ctx.header("Authorization"), kind, body));
        }

        private static Map<String, String> form(String body) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            if (body == null || body.isBlank()) {
                return values;
            }
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                values.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
            return values;
        }

        @Override
        public void close() {
            app.stop();
            sink.stop();
        }
    }

    private record Hit(String method, String path, String host, String authorization, String kind, String body) {
    }
}
