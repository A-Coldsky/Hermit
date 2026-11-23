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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import dev.hermit.crab.runtime.Identities;
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

    /** H2：并发 delete 与 bind 不能留下悬空成员或不一致的内存绑定状态。 */
    @Test
    void concurrentIdentityDeleteAndBindLeavesCleanState() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        SessionKey memberA = new SessionKey(Origin.OFFICIAL, "pkg_a", "default");
        SessionKey memberB = new SessionKey(Origin.OFFICIAL, "pkg_b", "default");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                String id = runtime.identities().create("del-" + round, Actors.CLI).id();
                runtime.identities().bind(id, Origin.OFFICIAL, "pkg_a", "default", Actors.CLI);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> deleter = pool.submit(() -> {
                    await(barrier);
                    runtime.identities().delete(id, Actors.CLI);
                });
                Future<?> binder = pool.submit(() -> {
                    await(barrier);
                    try {
                        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_b", "default", Actors.CLI);
                    } catch (IllegalArgumentException ignored) {
                        // 删除先完成时身份已不存在。
                    }
                });
                deleter.get(10, TimeUnit.SECONDS);
                binder.get(10, TimeUnit.SECONDS);
                assertThat(runtime.identities().identityOf(memberA)).isNull();
                assertThat(runtime.identities().identityOf(memberB)).isNull();
                assertThat(runtime.identities().list()).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** H3：并发向空身份绑定两个地址毫无交集的包时，最多只能成功一个；重复绑定同一身份不清空现有会话。 */
    @Test
    void concurrentDisjointBindsNeverCoexistAndIdempotentRebindKeepsSession() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_far", elsewhereUrl, "zhangsan");
        SessionKey memberA = new SessionKey(Origin.OFFICIAL, "pkg_a", "default");
        SessionKey memberFar = new SessionKey(Origin.OFFICIAL, "pkg_far", "default");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                String id = runtime.identities().create("race-" + round, Actors.CLI).id();
                CyclicBarrier barrier = new CyclicBarrier(2);
                AtomicInteger ok = new AtomicInteger();
                AtomicInteger rejected = new AtomicInteger();
                Future<?> first = pool.submit(() -> {
                    await(barrier);
                    try {
                        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_a", "default", Actors.CLI);
                        ok.incrementAndGet();
                    } catch (IllegalArgumentException ex) {
                        rejected.incrementAndGet();
                    }
                });
                Future<?> second = pool.submit(() -> {
                    await(barrier);
                    try {
                        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_far", "default", Actors.CLI);
                        ok.incrementAndGet();
                    } catch (IllegalArgumentException ex) {
                        rejected.incrementAndGet();
                    }
                });
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
                assertThat(ok.get()).isEqualTo(1);
                assertThat(rejected.get()).isEqualTo(1);
                assertThat(runtime.identities().identityOf(memberA) != null
                        && runtime.identities().identityOf(memberFar) != null).isFalse();
                runtime.identities().delete(id, Actors.CLI);
            }
        } finally {
            pool.shutdownNow();
        }

        // 幂等重复绑定同一身份：不触发 rebound，已有登录会话不被清掉。
        String id = console("POST", "/console/api/identities", "{\"name\":\"幂等\"}").path("id").asText();
        bind(id, "pkg_a");
        read("pkg_a");
        assertThat(logins.get()).isEqualTo(1);
        bind(id, "pkg_a");
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_a", "default", Actors.CLI);
        read("pkg_a");
        assertThat(logins.get()).as("重复绑定同一身份不应重登").isEqualTo(1);
    }

    /** H9：本地规则包与正式规则包混绑时，无论哪一方先绑，check 都给出未签名提示；同来源不提示。 */
    @Test
    void mixingLocalAndOfficialPackagesEmitsWarningInBothDirections() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        ConchDoc localDoc = doc("pkg_local", siteUrl);
        runtime.registry().put(localDoc, Origin.LOCAL);
        runtime.admin().createAccount(Origin.LOCAL, "pkg_local", "default", Actors.CLI);
        runtime.admin().writeCredentials(
                Origin.LOCAL, localDoc, "default", Map.of("username", "zhangsan", "password", PASSWORD), Actors.CLI);

        String expectedWarning = "其中包含未经过 Reef 签名的本地规则包，绑定后会与正式规则包共用这份登录 Cookie，请确认本地规则可信。";

        String idOfficial = runtime.identities().create("正式起头", Actors.CLI).id();
        runtime.identities().bind(idOfficial, Origin.OFFICIAL, "pkg_a", "default", Actors.CLI);
        Identities.Check sameOrigin = runtime.identities().check(idOfficial, Origin.OFFICIAL, "pkg_b", "default");
        assertThat(sameOrigin.allowed()).isTrue();
        assertThat(sameOrigin.warnings()).doesNotContain(expectedWarning);

        Identities.Check localIntoOfficial = runtime.identities().check(idOfficial, Origin.LOCAL, "pkg_local", "default");
        assertThat(localIntoOfficial.allowed()).isTrue();
        assertThat(localIntoOfficial.warnings()).contains(expectedWarning);

        String idLocal = runtime.identities().create("本地起头", Actors.CLI).id();
        runtime.identities().bind(idLocal, Origin.LOCAL, "pkg_local", "default", Actors.CLI);
        Identities.Check officialIntoLocal = runtime.identities().check(idLocal, Origin.OFFICIAL, "pkg_b", "default");
        assertThat(officialIntoLocal.allowed()).isTrue();
        assertThat(officialIntoLocal.warnings()).contains(expectedWarning);
    }

    /** H5：已载入的成员变成 driver: mcp 或清空 base_url 时，直接解绑该成员，且不毒化整个身份的重判。 */
    @Test
    void loadedMcpReplacementOrClearedBaseUrlDetachesMemberAndDoesNotPoisonIdentity() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        String id = runtime.identities().create("张三", Actors.CLI).id();
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_a", "default", Actors.CLI);
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_b", "default", Actors.CLI);

        // pkg_b 被替换成同名的 driver: mcp 包：虽然仍已载入，但地址为空，应立即解绑而非跳过整个身份。
        runtime.registry().put(mcpDoc("pkg_b"));
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_b", "default"))).isNull();
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default"))).isEqualTo(id);

        // 只靠 config.base_url 确定地址的包：清空配置后地址为空，同样应被解绑。
        ConchDoc cfgDoc = docWithoutBaseUrl("pkg_cfg");
        runtime.registry().put(cfgDoc);
        runtime.admin().createAccount(Origin.OFFICIAL, "pkg_cfg", "default", Actors.CLI);
        runtime.admin().writeCredentials(
                Origin.OFFICIAL, cfgDoc, "default", Map.of("username", "zhangsan", "password", PASSWORD), Actors.CLI);
        runtime.admin().putConfig(Origin.OFFICIAL, cfgDoc, "base_url", siteUrl, Actors.CLI);
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_cfg", "default", Actors.CLI);
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_cfg", "default"))).isEqualTo(id);

        runtime.admin().deleteConfig(Origin.OFFICIAL, cfgDoc, "base_url", Actors.CLI);
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_cfg", "default"))).isNull();
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default"))).isEqualTo(id);

        // 桥接成员变成 mcp 后：不仅桥接成员自己离开，原本靠它连在一起的两个不相干包也不能继续留在同一身份。
        install("pkg_far", elsewhereUrl, "zhangsan");
        ConchDoc bridge = bridgeDoc("pkg_bridge", siteUrl, elsewhereUrl);
        runtime.registry().put(bridge);
        runtime.admin().createAccount(Origin.OFFICIAL, "pkg_bridge", "default", Actors.CLI);
        runtime.admin().writeCredentials(
                Origin.OFFICIAL, bridge, "default", Map.of("username", "zhangsan", "password", PASSWORD), Actors.CLI);
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_bridge", "default", Actors.CLI);
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_far", "default", Actors.CLI);

        runtime.registry().put(mcpDoc("pkg_bridge"));
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_bridge", "default"))).isNull();
        boolean aStays = id.equals(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default")));
        boolean farStays = id.equals(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_far", "default")));
        assertThat(aStays ^ farStays).as("桥接失效后两个不相干站点不能继续留在同一个身份").isTrue();
    }

    /** M4：正式包本就生效时带 copy 切换来源并复制了 base_url 配置，也必须重判身份绑定。 */
    @Test
    void switchSourceWithCopyRevalidatesIdentitiesWhenCopiedConfigMovesAddress() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        install("pkg_b", siteUrl, "zhangsan");
        String id = runtime.identities().create("张三", Actors.CLI).id();
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_a", "default", Actors.CLI);
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_b", "default", Actors.CLI);

        // 导入同名本地包并在本地配置里把 base_url 改到另一个站点；正式包仍处于生效状态。
        runtime.localConches().importBytes(yaml("pkg_b", siteUrl, true).getBytes(StandardCharsets.UTF_8), Actors.CLI);
        ConchDoc localB = runtime.registry().find("pkg_b", Origin.LOCAL).orElseThrow();
        runtime.admin().putConfig(Origin.LOCAL, localB, "base_url", elsewhereUrl, Actors.CLI);
        assertThat(runtime.registry().origin("pkg_b")).isEqualTo(Origin.OFFICIAL);

        CrabRuntime.SourceSwitch switched = runtime.switchSource("pkg_b", Origin.OFFICIAL, true, Actors.CONSOLE);
        assertThat(switched.copied()).isTrue();
        assertThat(switched.configKeys()).isEqualTo(1);
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_b", "default")))
                .as("复制过来的 base_url 已改变地址，正式包账号应离开身份").isNull();
        assertThat(runtime.identities().identityOf(new SessionKey(Origin.OFFICIAL, "pkg_a", "default"))).isEqualTo(id);
    }

    /** M5：reload 检测到本地文件被删除或扩大地址边界时，触发解绑与清会话；语法损坏的文件保持绑定。 */
    @Test
    void reloadLocalConchesUnbindsDeletedOrWidenedFilesWhileBrokenFileKeepsBinding() throws Exception {
        install("pkg_a", siteUrl, "zhangsan");
        runtime.localConches().importBytes(yaml("pkg_local", siteUrl, true).getBytes(StandardCharsets.UTF_8), Actors.CLI);
        ConchDoc localDoc = runtime.registry().find("pkg_local", Origin.LOCAL).orElseThrow();
        runtime.admin().createAccount(Origin.LOCAL, "pkg_local", "default", Actors.CLI);
        runtime.admin().writeCredentials(
                Origin.LOCAL, localDoc, "default", Map.of("username", "zhangsan", "password", PASSWORD), Actors.CLI);

        String id = runtime.identities().create("混绑", Actors.CLI).id();
        runtime.identities().bind(id, Origin.OFFICIAL, "pkg_a", "default", Actors.CLI);
        runtime.identities().bind(id, Origin.LOCAL, "pkg_local", "default", Actors.CLI);
        SessionKey localKey = new SessionKey(Origin.LOCAL, "pkg_local", "default");
        SessionKey officialKey = new SessionKey(Origin.OFFICIAL, "pkg_a", "default");
        Path file = runtime.paths().localConches().resolve("pkg_local.yaml");

        // 1. 文件临时写坏：reload 标记为 broken，但不是删除，绑定保留。
        Files.writeString(file, ":\n");
        runtime.localConches().reload(Actors.CLI);
        assertThat(runtime.identities().identityOf(localKey)).isEqualTo(id);
        assertThat(runtime.identities().identityOf(officialKey)).isEqualTo(id);

        // 2. 从磁盘删掉文件再 reload：触发 onDeleted -> 解除绑定，另一个成员不受影响。
        Files.delete(file);
        runtime.localConches().reload(Actors.CLI);
        assertThat(runtime.identities().identityOf(localKey)).isNull();
        assertThat(runtime.identities().identityOf(officialKey)).isEqualTo(id);
        assertThat(console("GET", "/console/api/audit?limit=50", null).toString()).contains("package_removed");

        // 3. 重新导入并绑定后，直接在磁盘改文件扩大地址再 reload：触发 onWidened -> 解绑（address_widened）。
        runtime.localConches().importBytes(yaml("pkg_local", siteUrl, true).getBytes(StandardCharsets.UTF_8), Actors.CLI);
        runtime.identities().bind(id, Origin.LOCAL, "pkg_local", "default", Actors.CLI);
        assertThat(runtime.identities().identityOf(localKey)).isEqualTo(id);

        Files.writeString(file, yaml("pkg_local", elsewhereUrl, true));
        runtime.localConches().reload(Actors.CLI);
        assertThat(runtime.identities().identityOf(localKey)).isNull();
        assertThat(runtime.identities().identityOf(officialKey)).isEqualTo(id);
        assertThat(console("GET", "/console/api/audit?limit=50", null).toString()).contains("address_widened");
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
        return ConchParser.parse(yaml(name, baseUrl, aliveCheck));
    }

    private static String yaml(String name, String baseUrl, boolean aliveCheck) {
        String alive = aliveCheck ? "  alive_check:\n    request: { method: GET, path: /data }\n" : "";
        return """
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
                """.formatted(name, baseUrl, name, alive);
    }

    private static ConchDoc docWithoutBaseUrl(String name) {
        return ConchParser.parse("""
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 可配站点 %s
                description: 站点地址完全由本机配置提供。
                credentials:
                  username: { type: text, label: 登录名, required: true }
                  password: { type: secret, label: 密码, required: true }
                config:
                  base_url: { type: url, label: 地址, required: true }
                auth:
                  steps:
                    - request:
                        method: POST
                        path: /login
                        form: { username: "{{credential.username}}", password: "{{credential.password}}" }
                    - assert: { cookie_exists: sid }
                actions:
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
                """.formatted(name, name));
    }

    private static ConchDoc bridgeDoc(String name, String baseUrl, String authOrigin) {
        return ConchParser.parse("""
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.11.0
                base_url: %s
                title: 桥接 %s
                description: 经认证地址登录的规则包。
                credentials:
                  username: { type: text, label: 登录名, required: true }
                  password: { type: secret, label: 密码, required: true }
                auth:
                  origins: ["%s"]
                  steps:
                    - request:
                        method: POST
                        url: %s/login
                        form: { username: "{{credential.username}}", password: "{{credential.password}}" }
                    - assert: { cookie_exists: sid }
                actions:
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
                """.formatted(name, baseUrl, name, authOrigin, authOrigin));
    }

    private static ConchDoc mcpDoc(String name) {
        return ConchParser.parse("""
                conch: %s
                version: 2.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 上游 %s
                description: MCP 规则包。
                credentials: {}
                upstream:
                  url: https://mcp.example.com/mcp
                  transport: streamable_http
                  auth: { type: headers }
                actions:
                  search:
                    tool: search
                    title: 搜索
                    description: 查询。
                    mcp: { read_only: true, destructive: false }
                    confirm: false
                    input_schema: { type: object }
                """.formatted(name, name));
    }
}
