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
 * 身份的控制台接口和它对调用的效果。网站同一时间只认最后一次登录的会话：
 * 两个包各登录各的就互相顶掉，绑到同一个身份后共用那一次登录。
 */
class IdentityHttpTest {
    private static final String PASSWORD = "S3cr3t-identity";

    @TempDir
    Path temp;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger logins = new AtomicInteger();
    private volatile String current = "";
    private HttpServer site;
    private HttpServer elsewhere;
    private CrabRuntime runtime;
    private CrabHttpServer server;
    private String siteUrl;
    private String elsewhereUrl;
    private String crab;
    private String key;

    @BeforeEach
    void start() throws IOException {
        site = site();
        elsewhere = site();
        siteUrl = "http://127.0.0.1:" + site.getAddress().getPort();
        elsewhereUrl = "http://127.0.0.1:" + elsewhere.getAddress().getPort();
        open();
        key = runtime.admin().createApiKey("all", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CLI)
                .plaintext();
    }

    private HttpServer site() throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        created.createContext("/login", exchange -> {
            exchange.getRequestBody().readAllBytes();
            current = UUID.randomUUID().toString();
            logins.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", "sid=" + current + "; Path=/");
            respond(exchange, 200, "{\"ok\":true}");
        });
        created.createContext("/data", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            boolean valid = cookie != null && !current.isEmpty() && cookie.contains("sid=" + current);
            respond(exchange, valid ? 200 : 401, valid ? "{\"ok\":true}" : "{\"ok\":false}");
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
        site.stop(0);
        elsewhere.stop(0);
    }

    @Test
    void bindingTwoPackagesStopsThemFromReplacingEachOthersLogin() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        read("pkg_a");
        read("pkg_b");
        read("pkg_a");
        assertThat(logins.get()).as("没绑定：每次都被对方顶掉，重新登录").isEqualTo(3);

        String id = console("POST", "/console/api/identities", "{\"name\":\"OA · 张三\"}").path("id").asText();
        Reply unconfirmed = send("POST", "/console/api/identities/" + id + "/members",
                "{\"conch\":\"pkg_a\",\"account\":\"default\"}");
        assertThat(unconfirmed.status).as("绑定要明确确认").isEqualTo(400);
        bind(id, "pkg_a");

        JsonNode check = console("POST", "/console/api/identities/" + id + "/check",
                "{\"conch\":\"pkg_b\",\"account\":\"default\"}");
        assertThat(check.path("allowed").asBoolean()).isTrue();
        assertThat(check.path("peers").get(0).path("credentials").asText()).isEqualTo("SAME");
        assertThat(check.path("peers").get(0).path("addresses").get(0).asText()).isEqualTo(siteUrl);
        assertThat(check.toString()).doesNotContain("zhangsan").doesNotContain(PASSWORD);
        bind(id, "pkg_b");

        int before = logins.get();
        for (int round = 0; round < 3; round++) {
            read("pkg_a");
            read("pkg_b");
        }
        assertThat(logins.get() - before).as("绑定后共用一次登录，写了 alive_check 的包直接接着用").isLessThanOrEqualTo(1);

        JsonNode listed = console("GET", "/console/api/identities", null).path("identities").get(0);
        assertThat(listed.path("name").asText()).isEqualTo("OA · 张三");
        assertThat(listed.path("members")).hasSize(2);
        assertThat(listed.path("members").get(0).path("active").asBoolean()).isTrue();
        JsonNode account = console("GET", "/console/api/conches/pkg_b/accounts", null).path("accounts").get(0);
        assertThat(account.path("identity").path("id").asText()).isEqualTo(id);
        assertThat(account.path("identity").path("name").asText()).isEqualTo("OA · 张三");

        // 重启后绑定和身份的 Cookie 都在。
        int beforeRestart = logins.get();
        server.close();
        runtime.close();
        open();
        runtime.registry().put(doc("pkg_a", siteUrl));
        runtime.registry().put(doc("pkg_b", siteUrl));
        read("pkg_b");
        read("pkg_a");
        assertThat(logins.get()).isEqualTo(beforeRestart);
    }

    @Test
    void differentLoginNamesWarnAndUnrelatedSitesCannotBeBound() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "lisi");
        install("pkg_far", elsewhereUrl, "zhangsan");
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        bind(id, "pkg_a");

        JsonNode different = console("POST", "/console/api/identities/" + id + "/check",
                "{\"conch\":\"pkg_b\",\"account\":\"default\"}");
        assertThat(different.path("allowed").asBoolean()).isTrue();
        assertThat(different.path("peers").get(0).path("credentials").asText()).isEqualTo("DIFFERENT");
        assertThat(different.path("warnings").toString()).contains("登录名不一样");
        assertThat(different.toString()).doesNotContain("zhangsan").doesNotContain("lisi");

        JsonNode unrelated = console("POST", "/console/api/identities/" + id + "/check",
                "{\"conch\":\"pkg_far\",\"account\":\"default\"}");
        assertThat(unrelated.path("allowed").asBoolean()).isFalse();
        assertThat(send("POST", "/console/api/identities/" + id + "/members",
                "{\"conch\":\"pkg_far\",\"account\":\"default\",\"confirm\":true}").status).isEqualTo(400);

        // 一个身份下一个包只能有一个账号；一个账号只能在一个身份里。
        runtime.admin().createAccount("pkg_a", "second", Actors.CLI);
        assertThat(send("POST", "/console/api/identities/" + id + "/members",
                "{\"conch\":\"pkg_a\",\"account\":\"second\",\"confirm\":true}").status).isEqualTo(400);
        String other = console("POST", "/console/api/identities", "{\"name\":\"李四\"}").path("id").asText();
        assertThat(send("POST", "/console/api/identities/" + other + "/members",
                "{\"conch\":\"pkg_a\",\"account\":\"default\",\"confirm\":true}").status).isEqualTo(400);
        assertThat(send("POST", "/console/api/identities", "{\"name\":\"李四\"}").status).as("重名").isEqualTo(400);
    }

    @Test
    void credentialChangeClearsTheIdentityAndLeavingReturnsToASeparateSession() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        bind(id, "pkg_a");
        bind(id, "pkg_b");
        read("pkg_a");
        read("pkg_b");
        assertThat(logins.get()).isEqualTo(1);

        // 改一个成员的凭证：整个身份重新登录。
        runtime.admin().writeCredentials(doc("pkg_b", siteUrl), "default",
                Map.of("username", "zhangsan", "password", PASSWORD), Actors.CLI);
        read("pkg_a");
        assertThat(logins.get()).isEqualTo(2);

        // 删掉账号：它离开身份，另一个成员的会话不受影响。
        console("DELETE", "/console/api/conches/pkg_b/accounts/default", null);
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_b", "default"))).isNull();
        read("pkg_a");
        assertThat(logins.get()).isEqualTo(2);

        // 解除绑定：回到自己的空白会话。
        console("DELETE", "/console/api/identities/" + id + "/members/OFFICIAL/pkg_a/default", null);
        read("pkg_a");
        assertThat(logins.get()).as("独立会话里没有身份的 Cookie").isEqualTo(3);
        assertThat(console("GET", "/console/api/identities", null).path("identities").get(0).path("members"))
                .isEmpty();
        console("DELETE", "/console/api/identities/" + id, null);
        assertThat(console("GET", "/console/api/identities", null).path("identities")).isEmpty();
    }

    @Test
    void aMemberWhoseAddressNoLongerMatchesLeavesTheIdentity() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        bind(id, "pkg_a");
        bind(id, "pkg_b");
        read("pkg_a");

        console("PUT", "/console/api/conches/pkg_b/config", "{\"base_url\":\"" + elsewhereUrl + "\"}");
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_b", "default")))
                .as("改了地址，和身份里别的成员不再是同一个网站").isNull();
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default"))).isEqualTo(id);
        JsonNode audit = console("GET", "/console/api/audit?limit=50", null);
        assertThat(audit.toString()).contains("IDENTITY_UNBOUND").contains("address_changed");
    }

    @Test
    void aNewVersionThatMovesToAnotherSiteLeavesWhileTheUnchangedMemberStays() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        bind(id, "pkg_a");
        bind(id, "pkg_b");

        // 同步装上 pkg_a 的新版本，它换了网站。两边各剩一个成员：走的是变了的那个，不是排在后面的那个。
        runtime.registry().put(doc("pkg_a", elsewhereUrl));
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default"))).isNull();
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_b", "default"))).isEqualTo(id);
    }

    @Test
    void theMemberWhoseAddressMovedLeavesWhicheverNotificationArrivesFirst() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        bind(id, "pkg_a");
        bind(id, "pkg_b");

        // pkg_b 的新版本换了网站，通知还没发出；这时另一个线程上 pkg_a 原样重新载入，它的通知先到。
        try (dev.hermit.crab.engine.ConchRegistry.Hold ignored = runtime.registry().hold()) {
            runtime.registry().put(doc("pkg_b", elsewhereUrl));
            Thread other = new Thread(() -> runtime.registry().put(doc("pkg_a", siteUrl)));
            other.start();
            other.join();
        }
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default")))
                .as("地址没变的成员留下").isEqualTo(id);
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_b", "default"))).isNull();
    }

    @Test
    void aLoneMemberThatMovedEarlierIsNotBlamedWhenALaterMemberMoves() throws Exception {
        install("pkg_a", elsewhereUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        bind(id, "pkg_b");
        // 身份里只有 pkg_b 时它换了网站，之后 pkg_a 才加入。
        runtime.registry().put(doc("pkg_b", elsewhereUrl));
        bind(id, "pkg_a");

        // 这回换网站的是 pkg_a：pkg_b 上一次换地址是加入之前的事，不该算它也动过。
        runtime.registry().put(doc("pkg_a", siteUrl));
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_b", "default")))
                .as("这次没动的成员留下").isEqualTo(id);
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default"))).isNull();
    }

    @Test
    void whenTheLocalCopyOfASameNamePackageMovesTheOfficialCopyStays() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        ConchDoc local = doc("pkg_a", siteUrl);
        runtime.registry().put(local, Origin.LOCAL);
        runtime.admin().createAccount(Origin.LOCAL, "pkg_a", "default", Actors.CLI);
        runtime.admin().writeCredentials(
                Origin.LOCAL, local, "default", Map.of("username", "zhangsan", "password", PASSWORD), Actors.CLI);
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        // 本地那份先绑：两边一样大时排在前面的不该因此占便宜。
        runtime.identities().bind(id, Origin.LOCAL, "pkg_a", "default", Actors.CLI);
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_a", "default", Actors.CLI);

        runtime.registry().put(doc("pkg_a", elsewhereUrl), Origin.LOCAL);
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default")))
                .as("正式包那份的地址没变").isEqualTo(id);
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.LOCAL, "pkg_a", "default"))).isNull();
    }

    @Test
    void aVersionThatIsPutBackBeforeItIsCommittedDoesNotUnbindAnyone() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        bind(id, "pkg_a");
        bind(id, "pkg_b");

        // 换版本时先放进注册表再写指针；指针写失败会把旧版本放回去。成败定下来之前不重判绑定。
        try (dev.hermit.crab.engine.ConchRegistry.Hold ignored = runtime.registry().hold()) {
            runtime.registry().put(doc("pkg_a", elsewhereUrl));
            runtime.registry().put(doc("pkg_a", siteUrl));
        }
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default"))).isEqualTo(id);
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_b", "default"))).isEqualTo(id);
    }

    @Test
    void packagesWithoutAliveCheckLogInOnceEachAndThenStopReplacingEachOther() throws Exception {
        install("pkg_a", siteUrl, "zhangsan", false);
        install("pkg_b", siteUrl, "zhangsan", false);
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        bind(id, "pkg_a");
        JsonNode check = console("POST", "/console/api/identities/" + id + "/check",
                "{\"conch\":\"pkg_b\",\"account\":\"default\"}");
        assertThat(check.path("warnings").toString()).contains("alive_check");
        bind(id, "pkg_b");
        for (int round = 0; round < 3; round++) {
            read("pkg_a");
            read("pkg_b");
        }
        // 没有 alive_check 的包不知道别人登录出来的 Cookie 能不能用，各登录一次；
        // 之后网站换掉的会话 Cookie 在共用的罐里，另一个包接着用新的，不再被顶掉。
        assertThat(logins.get()).isEqualTo(2);
    }

    @Test
    void removedPackagesLeaveWhileDisabledOrMissingOnesStayBound() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        String id = console("POST", "/console/api/identities", "{\"name\":\"张三\"}").path("id").asText();
        bind(id, "pkg_a");
        bind(id, "pkg_b");
        read("pkg_a");
        read("pkg_b");
        SessionKey memberA = new SessionKey(Origin.OFFICIAL, "pkg_a", "default");
        SessionKey memberB = new SessionKey(Origin.OFFICIAL, "pkg_b", "default");

        // 停用：绑定留着，另一个成员照常用，不重新登录。
        runtime.setConchDisabled("pkg_b", true, Actors.CLI);
        assertThat(runtime.identities().identityOf(memberB)).isEqualTo(id);
        read("pkg_a");
        assertThat(logins.get()).isEqualTo(1);
        runtime.setConchDisabled("pkg_b", false, Actors.CLI);

        // 包暂时不在本机（比如文件读不出来）：不是使用者删的，绑定留着；别的成员改配置也不会因此被判成不相干。
        runtime.registry().remove("pkg_b");
        runtime.identities().revalidate(Actors.SYNC);
        assertThat(runtime.identities().identityOf(memberB)).isEqualTo(id);
        assertThat(runtime.identities().identityOf(memberA)).isEqualTo(id);
        runtime.registry().put(doc("pkg_b", siteUrl));
        runtime.identities().packageRemoved(Origin.OFFICIAL, "pkg_b", Actors.CLI);
        assertThat(runtime.identities().identityOf(memberB)).as("包还在，不算删除").isEqualTo(id);

        // 使用者退订或删掉了包：它的账号离开身份，另一个成员的会话还在。
        runtime.registry().remove("pkg_b");
        runtime.identities().packageRemoved(Origin.OFFICIAL, "pkg_b", Actors.CLI);
        assertThat(runtime.identities().identityOf(memberB)).isNull();
        assertThat(runtime.identities().identityOf(memberA)).isEqualTo(id);
        read("pkg_a");
        assertThat(logins.get()).isEqualTo(1);
        assertThat(console("GET", "/console/api/audit?limit=50", null).toString()).contains("package_removed");
    }

    private void bind(String id, String conch) throws Exception {
        console("POST", "/console/api/identities/" + id + "/members",
                "{\"conch\":\"" + conch + "\",\"account\":\"default\",\"confirm\":true}");
    }

    private void read(String conch) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(crab + "/api/" + conch + "/read"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + key)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private void install(String name, String baseUrl, String username) {
        install(name, baseUrl, username, true);
    }

    private void install(String name, String baseUrl, String username, boolean aliveCheck) {
        ConchDoc doc = doc(name, baseUrl, aliveCheck);
        runtime.registry().put(doc);
        runtime.admin().createAccount(name, "default", Actors.CLI);
        runtime.admin().writeCredentials(doc, "default", Map.of("username", username, "password", PASSWORD), Actors.CLI);
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static ConchDoc doc(String name, String baseUrl) {
        return doc(name, baseUrl, true);
    }

    private static ConchDoc doc(String name, String baseUrl, boolean aliveCheck) {
        String alive = aliveCheck ? "  alive_check:\n    request: { method: GET, path: /data }\n" : "";
        return ConchParser.parse("""
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: %s
                title: 同网站 %s
                description: 另一位作者写的同一网站规则包。
                credentials:
                  username: { type: text, label: 登录名, required: true }
                  password: { type: secret, label: 密码, required: true }
                config:
                  base_url: { type: url, label: 地址, required: false }
                auth:
                  steps:
                    - request:
                        method: POST
                        path: /login
                        form: { username: "{{credential.username}}", password: "{{credential.password}}" }
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
                """.formatted(name, baseUrl, name, alive));
    }
}
