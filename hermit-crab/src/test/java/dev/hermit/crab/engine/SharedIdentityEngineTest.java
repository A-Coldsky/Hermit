package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.crab.store.Origin;

/**
 * 身份：两个互不知道对方的规则包，账号绑到同一个身份后共用 Cookie 和账号锁。
 * 认证中心在 localhost，两个应用在 127.0.0.1 的两个端口（Cookie 不分端口，所以两个应用的会话 Cookie 起不同的名字）。
 * 认证中心按用户名分会话，用来看两个身份之间串不串。
 */
class SharedIdentityEngineTest {
    private static final SessionKey OA_ALICE = new SessionKey(Origin.LOCAL, "oa", "alice");
    private static final SessionKey ERP_ALICE = new SessionKey(Origin.LOCAL, "erp", "main");
    private static final SessionKey OA_BOB = new SessionKey(Origin.LOCAL, "oa", "bob");
    private static final SessionKey ERP_BOB = new SessionKey(Origin.LOCAL, "erp", "second");
    private static final SessionKey OA_READS = new SessionKey(Origin.LOCAL, "oa_reads_only", "alice");
    private static final SessionKey OA_ASK = new SessionKey(Origin.LOCAL, "oa_ask", "alice");
    private static final SessionKey OA_VARS = new SessionKey(Origin.LOCAL, "oa_vars", "alice");
    private static final SessionKey ERP_VARS = new SessionKey(Origin.LOCAL, "erp_vars", "alice");

    private HttpServer sso;
    private HttpServer oa;
    private HttpServer erp;
    private ExecutorService pool;
    private String ssoUrl;
    private String oaUrl;
    private String erpUrl;

    private final AtomicInteger passwordLogins = new AtomicInteger();
    private final AtomicInteger tickets = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    /** 有效的认证中心会话：TGC 的值 → 用户名。 */
    private final Map<String, String> ssoSessions = new ConcurrentHashMap<>();
    /** 发出去还没用的票据 → 用户名。 */
    private final Map<String, String> pendingTickets = new ConcurrentHashMap<>();
    /** 有效的应用会话：Cookie 值 → 用户名。 */
    private final Map<String, String> appSessions = new ConcurrentHashMap<>();
    private final List<String> seen = new CopyOnWriteArrayList<>();
    /** 应用收到的 stamp 查询参数：「应用名 值」。 */
    private final List<String> stamps = new CopyOnWriteArrayList<>();
    /** 认证中心收到认不出的 TGC 时报错，而不是给登录页。 */
    private volatile boolean staleTgcIsAnError;
    /** 认证中心临时返回 503（验证 P1-S2 不应清空全身份 Cookie）。 */
    private volatile boolean sso503;
    /** 应用会话失效时 /data 回 200 加业务码，不跳认证中心。 */
    private volatile boolean softExpiry;
    /** 已登录时 /data 也回一个业务错误码（不是会话失效）。 */
    private volatile String bizCode;
    private final List<String> ssoGetHeaders = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch slowEntered = new CountDownLatch(1);
    private volatile CountDownLatch slowRelease = new CountDownLatch(0);

    @BeforeEach
    void start() throws IOException {
        pool = Executors.newCachedThreadPool();
        sso = server("localhost", this::serveSso);
        oa = server("127.0.0.1", exchange -> serveApp(exchange, "oa", "sidA"));
        erp = server("127.0.0.1", exchange -> serveApp(exchange, "erp", "sidB"));
        ssoUrl = "http://localhost:" + sso.getAddress().getPort();
        oaUrl = "http://127.0.0.1:" + oa.getAddress().getPort();
        erpUrl = "http://127.0.0.1:" + erp.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        slowRelease.countDown();
        for (HttpServer server : List.of(sso, oa, erp)) {
            server.stop(0);
        }
        pool.shutdownNow();
    }

    private HttpServer server(String host, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, 0), 0);
        server.setExecutor(pool);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String cookie(HttpExchange exchange, String name) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.strip().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private void serveSso(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String query = String.valueOf(exchange.getRequestURI().getQuery());
        String back = query.contains("service=erp") || body.contains("service=erp") ? erpUrl : oaUrl;
        String tgc = cookie(exchange, "TGC");
        seen.add("sso " + exchange.getRequestMethod() + " tgc=" + tgc
                + " sid=" + cookie(exchange, "sidA") + cookie(exchange, "sidB"));
        if ("GET".equals(exchange.getRequestMethod())) {
            ssoGetHeaders.add("frozen=" + exchange.getRequestHeaders().getFirst("X-Frozen")
                    + " otp=" + exchange.getRequestHeaders().getFirst("X-Otp")
                    + " plain=" + exchange.getRequestHeaders().getFirst("X-Plain"));
        }
        if (sso503) {
            reply(exchange, 503, "service unavailable");
            return;
        }
        if (exchange.getRequestMethod().equals("POST")) {
            String user = body.contains("username=bob") ? "bob" : "alice";
            if (!body.contains("password=S3cret%21pwd")) {
                reply(exchange, 401, "<input name=\"password\"> wrong");
                return;
            }
            passwordLogins.incrementAndGet();
            String value = user + "-tgc" + sequence.incrementAndGet();
            ssoSessions.put(value, user);
            exchange.getResponseHeaders().add("Set-Cookie", "TGC=" + value + "; Path=/");
            redirect(exchange, back + "/cas?ticket=" + ticket(user));
            return;
        }
        String user = tgc == null ? null : ssoSessions.get(tgc);
        if (user != null) {
            redirect(exchange, back + "/cas?ticket=" + ticket(user));
            return;
        }
        if (tgc != null && staleTgcIsAnError) {
            reply(exchange, 200, "会话已失效，请关闭浏览器后重试");
            return;
        }
        reply(exchange, 200, "<form><input name=\"password\"><input name=service value=\""
                + (back.equals(erpUrl) ? "erp" : "oa") + "\"></form>");
    }

    private String ticket(String user) {
        String ticket = "ST-" + tickets.incrementAndGet();
        pendingTickets.put(ticket, user);
        return ticket;
    }

    private void serveApp(HttpExchange exchange, String name, String cookieName) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String sid = cookie(exchange, cookieName);
        String user = sid == null ? null : appSessions.get(sid);
        seen.add(name + " " + exchange.getRequestMethod() + " " + path + " tgc=" + cookie(exchange, "TGC"));
        if (path.equals("/cas") && query != null && query.startsWith("ticket=")) {
            String owner = pendingTickets.remove(query.substring("ticket=".length()));
            if (owner == null) {
                reply(exchange, 403, "bad ticket");
                return;
            }
            String value = name + "-" + owner + "-" + sequence.incrementAndGet();
            appSessions.put(value, owner);
            exchange.getResponseHeaders().add("Set-Cookie", cookieName + "=" + value + "; Path=/");
            redirect(exchange, "/home");
            return;
        }
        if (path.equals("/loose-ping")) {
            reply(exchange, 200, "{\"ok\":true}");
            return;
        }
        if (user == null && sid != null && path.equals("/weak-home")) {
            // 带着失效的会话 Cookie 来：首页照样回 200，不给新会话。
            reply(exchange, 200, "{\"value\":\"stale\"}");
            return;
        }
        if (user == null && softExpiry && path.equals("/data")) {
            reply(exchange, 200, "{\"code\":\"NOT_LOGIN\"}");
            return;
        }
        if (user == null) {
            redirect(exchange, ssoUrl + "/login?service=" + name);
            return;
        }
        if (bizCode != null && name.equals("oa") && path.equals("/data")) {
            reply(exchange, 200, "{\"code\":\"" + bizCode + "\"}");
            return;
        }
        if (path.equals("/stamp")) {
            reply(exchange, 200, "{\"value\":\"" + name + "-stamp-" + sequence.incrementAndGet() + "\"}");
            return;
        }
        if (query != null && query.startsWith("stamp=")) {
            stamps.add(name + " " + query.substring("stamp=".length()));
        }
        if (path.equals("/slow")) {
            slowEntered.countDown();
            try {
                slowRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        // 用户名是凭证，输出里会被打码；这里回大写的，方便断言是谁。
        reply(exchange, 200, "{\"value\":\"" + name + ":" + user.toUpperCase(java.util.Locale.ROOT) + "\"}");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", body.startsWith("{") ? "application/json" : "text/html");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void unboundAccountsEachLogInAtTheAuthCentre() {
        try (Fixture fixture = new Fixture(new MemoryShared())) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).as("没绑定：各有各的 Cookie，认证中心上登录两次").isEqualTo(2);
        }
    }

    @Test
    void boundAccountsLogInOnceAndAnExpiredAppSessionOnlyFetchesANewTicket() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).as("第二个包带着认证中心的 Cookie 换票据，不再交密码").isEqualTo(1);
            assertThat(tickets.get()).isEqualTo(2);
            // 认证中心的 Cookie 只发给认证中心，应用的会话 Cookie 不发给认证中心。
            assertThat(seen).filteredOn(line -> !line.startsWith("sso")).allMatch(line -> line.endsWith("tgc=null"));
            assertThat(seen).filteredOn(line -> line.startsWith("sso")).allMatch(line -> line.endsWith("sid=nullnull"));

            // OA 的会话过期：只有 OA 重新换一次票据，认证中心和 ERP 的登录都留着。
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(tickets.get()).isEqualTo(3);
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(tickets.get()).as("ERP 的会话没被清掉").isEqualTo(3);
            assertThat(fixture.sessions.lastCleared("zhang")).isNull();
            // 身份的 Cookie 存在身份名下，不在哪个账号名下。
            assertThat(shared.saved.get("zhang")).extracting(StoredCookie::name)
                    .contains("TGC", "sidA", "sidB");
        }
    }

    @Test
    void loginThatFailsWithTheSharedCookiesClearsThemAndStartsOver() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");

            // 认证中心和 OA 的会话都没了，认证中心对旧 Cookie 只给报错页：带着旧 Cookie 登录不成。
            ssoSessions.clear();
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            staleTgcIsAnError = true;
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).as("清空后从头登录，重新交了一次密码").isEqualTo(2);
            assertThat(fixture.sessions.lastCleared("zhang").by()).isEqualTo(OA_ALICE);
            assertThat(fixture.sessions.authFailures(OA_ALICE)).as("带旧 Cookie 的那次失败不算登录失败").isZero();

            // 清空时 ERP 的会话 Cookie 也没了：它下一次带着新的认证中心 Cookie 换票据，不交密码。
            assertThat(shared.saved.get("zhang")).extracting(StoredCookie::name).doesNotContain("sidB");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(2);
        }
    }

    @Test
    void twoIdentitiesNeverSeeEachOthersCookies() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        shared.bind("li", OA_BOB, ERP_BOB);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(OA_BOB, "run")).isEqualTo("oa:BOB");
            // 各自的第二个包接着用各自身份的认证中心登录，拿到的是各自的人。
            assertThat(fixture.value(ERP_BOB, "run")).isEqualTo("erp:BOB");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(2);
            assertThat(shared.saved.get("zhang")).allMatch(cookie -> !cookie.value().contains("bob"));
            assertThat(shared.saved.get("li")).allMatch(cookie -> !cookie.value().contains("alice"));
        }
    }

    @Test
    void aliveCheckLetsAMemberUseTheSessionAnotherMemberLoggedIn() {
        MemoryShared shared = new MemoryShared();
        SessionKey other = new SessionKey(Origin.LOCAL, "oa_reports", "default");
        shared.bind("zhang", OA_ALICE, other);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            int before = tickets.get();
            // 同一个网站的另一个包，写了 alive_check：探活通过就直接用，不跑登录步骤。
            assertThat(fixture.value(other, "run")).isEqualTo("oa:ALICE");
            assertThat(tickets.get()).isEqualTo(before);
            assertThat(passwordLogins.get()).isEqualTo(1);

            // 接手的会话其实不能用（这里把它弄过期）：这次调用重新登录后重试。
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            assertThat(fixture.value(other, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).as("认证中心的登录还在，只换票据").isEqualTo(1);
        }
    }

    @Test
    void membersQueueOnOneLockWhileUnboundAccountsDoNot() throws Exception {
        for (boolean bound : List.of(true, false)) {
            MemoryShared shared = new MemoryShared();
            if (bound) {
                shared.bind("zhang", OA_ALICE, ERP_ALICE);
            }
            slowEntered = new CountDownLatch(1);
            slowRelease = new CountDownLatch(1);
            try (Fixture fixture = new Fixture(shared)) {
                assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
                assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
                Future<String> slow = pool.submit(() -> fixture.value(OA_ALICE, "slow"));
                assertThat(slowEntered.await(10, TimeUnit.SECONDS)).isTrue();
                int erpBefore = (int) seen.stream().filter(line -> line.startsWith("erp GET /data")).count();
                Future<String> second = pool.submit(() -> fixture.value(ERP_ALICE, "run"));
                if (bound) {
                    Thread.sleep(400);
                    assertThat(seen.stream().filter(line -> line.startsWith("erp GET /data")).count())
                            .as("同一个身份的调用排队，OA 的调用没结束前 ERP 的请求不发").isEqualTo(erpBefore);
                } else {
                    assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("erp:ALICE");
                }
                slowRelease.countDown();
                assertThat(slow.get(10, TimeUnit.SECONDS)).isEqualTo("oa:ALICE");
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("erp:ALICE");
            }
        }
    }

    @Test
    void anAuthCentreThatCannotBeReachedDoesNotCostTheOtherMembersTheirSessions() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");

            // ERP 的会话过期，它去认证中心换票据时认证中心连不上：没有哪个响应说留着的 Cookie 没用。
            appSessions.keySet().removeIf(value -> value.startsWith("erp-"));
            sso.stop(0);
            ExecutionResult failed = fixture.call(ERP_ALICE, "run");
            assertThat(failed.success()).isFalse();
            assertThat(failed.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(fixture.sessions.lastCleared("zhang")).as("连不上不清空身份的 Cookie").isNull();
            assertThat(shared.saved.get("zhang")).extracting(StoredCookie::name).contains("TGC", "sidA");
            int before = tickets.get();
            assertThat(fixture.value(OA_ALICE, "run")).as("OA 的会话还在，不用找认证中心").isEqualTo("oa:ALICE");
            assertThat(tickets.get()).isEqualTo(before);
        }
    }

    @Test
    void testingALoginChecksThatAccountsOwnPasswordEvenWhenAPeerIsLoggedIn() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            // ERP 已经登录，认证中心的 Cookie 在共用的罐里：带着它 OA 不交密码也能换到票据。
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            fixture.password = "Wrong-pass-9";
            ExecutionResult tested = fixture.engine.testLogin(fixture.request(OA_ALICE, "run"));
            assertThat(tested.success()).as("测试登录验证的是这个账号填的密码").isFalse();
            assertThat(tested.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);

            fixture.password = EngineTestSupport.PASSWORD;
            int before = passwordLogins.get();
            ExecutionResult again = fixture.engine.testLogin(fixture.request(OA_ALICE, "run"));
            assertThat(again.success()).as(String.valueOf(again.error())).isTrue();
            assertThat(passwordLogins.get()).as("从空白登录，交了一次密码").isEqualTo(before + 1);
        }
    }

    @Test
    void clearingTheIdentityDropsACodeAnotherMemberWasWaitingFor() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ASK, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.call(OA_ASK, "run").error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(fixture.sessions.pending().current(OA_ASK)).isNotNull();

            // ERP 改了凭证：整个身份的 Cookie 清空，OA 那次走到一半的登录带的 Cookie 也没了。
            fixture.sessions.invalidate(ERP_ALICE);
            assertThat(fixture.sessions.pending().current(OA_ASK)).as("旧的验证码接不下去，不再等").isNull();
            assertThat(fixture.call(OA_ASK, "run").error().code()).as("下一次调用从头登录，重新要验证码")
                    .isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
        }
    }

    @Test
    void aPausedAccountStaysPausedWhenItLeavesOrJoinsAnIdentity() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            fixture.password = "Wrong-pass-9";
            for (int i = 0; i < 3; i++) {
                assertThat(fixture.call(OA_ALICE, "run").error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            }
            assertThat(fixture.sessions.loginPaused(OA_ALICE)).isTrue();
            int before = passwordLogins.get();

            // 解除绑定：凭证没改，暂停还在，不会再拿同一个错密码去试。
            shared.unbind(OA_ALICE);
            fixture.sessions.rebound(OA_ALICE);
            assertThat(fixture.sessions.loginPaused(OA_ALICE)).isTrue();
            assertThat(fixture.sessions.authFailures(OA_ALICE)).isEqualTo(3);
            assertThat(fixture.call(OA_ALICE, "run").success()).isFalse();

            // 绑定变了但还没人通知会话管理器：下一次调用自己发现，暂停同样带过去。
            shared.bind("li", OA_ALICE);
            assertThat(fixture.call(OA_ALICE, "run").success()).isFalse();
            assertThat(fixture.sessions.loginPaused(OA_ALICE)).isTrue();
            assertThat(passwordLogins.get()).as("暂停期间没有再去登录").isEqualTo(before);
        }
    }

    @Test
    void aMemberOnPlainHttpCannotReadASecureCookieAnotherMemberBroughtIn() throws Exception {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        // 同一个主机上 https 的成员留下的 Secure Cookie，和一个普通 Cookie。
        shared.save("zhang", List.of(
                new StoredCookie("secureOnly", "s3cure-value", "127.0.0.1", "/", null, true, false, true),
                new StoredCookie("plain", "plain-value", "127.0.0.1", "/", null, false, false, true)));
        try (Fixture fixture = new Fixture(shared)) {
            java.net.URI http = java.net.URI.create(oaUrl);
            assertThat(fixture.sessions.tryLock(OA_ALICE, System.nanoTime() + Duration.ofSeconds(5).toNanos())).isTrue();
            try {
                assertThat(fixture.sessions.cookieValue(OA_ALICE, "plain", http)).isEqualTo("plain-value");
                assertThat(fixture.sessions.cookieValue(OA_ALICE, "secureOnly", http))
                        .as("http 的请求不会带 Secure Cookie，共用的罐里也不让按名字读").isNull();
                assertThat(fixture.sessions.cookieValue(
                        OA_ALICE, "secureOnly", java.net.URI.create("https://127.0.0.1"))).isEqualTo("s3cure-value");
                // cookie_exists 和按名字取值看到的一样：不会走进「有这个 Cookie」的分支又取不到值。
                FilterRegistry filters = FilterRegistry.defaults();
                StepRunner runner = new StepRunner(new TemplateEngine(filters), filters);
                var jar = fixture.sessions.cookies(OA_ALICE);
                Map<String, Object> vars = Map.of("response", Map.of("status", 200));
                assertThat(runner.test(Map.of("cookie_exists", "plain"), vars, jar, http)).isTrue();
                assertThat(runner.test(Map.of("cookie_exists", "secureOnly"), vars, jar, http)).isFalse();
                assertThat(runner.test(Map.of("cookie_exists", "secureOnly"), vars,
                        jar, java.net.URI.create("https://127.0.0.1"))).isTrue();
            } finally {
                fixture.sessions.unlock(OA_ALICE);
            }
        }
    }

    @Test
    void loginFailuresPauseOnlyTheMemberThatFailed() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            // 认证中心的登录没了，OA 这个包填的密码又是错的。
            ssoSessions.clear();
            appSessions.clear();
            fixture.password = "Wrong-pass-9";
            for (int i = 0; i < 3; i++) {
                ExecutionResult failed = fixture.call(OA_ALICE, "run");
                assertThat(failed.success()).isFalse();
                assertThat(failed.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            }
            assertThat(fixture.sessions.loginPaused(OA_ALICE)).isTrue();
            assertThat(fixture.sessions.loginPaused(ERP_ALICE)).as("暂停按账号，不按身份").isFalse();
            fixture.password = EngineTestSupport.PASSWORD;
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
        }
    }

    @Test
    void aMemberCannotReadCookiesThatAreNotSentToItsOwnSite() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            // 罐里有认证中心的 TGC，但它不会发给 ERP 的站点，ERP 的规则也就读不到。
            ExecutionResult peek = fixture.call(ERP_ALICE, "peek");
            assertThat(peek.success()).isFalse();
            assertThat(peek.toString()).doesNotContain("alice-tgc");
        }
    }

    @Test
    void sharedCookiesSurviveARestartAndAreNotStoredUnderAnAccount() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
        }
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).as("重启后带着存下来的认证中心 Cookie 换票据").isEqualTo(1);
        }
    }

    @Test
    void bindingMovesAnAccountIntoTheIdentityAndUnbindingGivesItAFreshSession() {
        MemoryShared shared = new MemoryShared();
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            // 绑定：已登录的账号把自己的 Cookie 带进身份，后来的成员接着用。
            shared.bind("zhang", OA_ALICE, ERP_ALICE);
            fixture.sessions.rebound(OA_ALICE);
            fixture.sessions.rebound(ERP_ALICE);
            assertThat(shared.saved.get("zhang")).extracting(StoredCookie::name).contains("TGC", "sidA");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);

            // 改了一个成员的凭证：整个身份的 Cookie 都清掉。
            fixture.sessions.invalidate(ERP_ALICE);
            assertThat(shared.saved.get("zhang")).isNull();
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(2);

            // 解除：这个账号回到自己的空白会话，身份的 Cookie 留给其它成员。
            shared.unbind(ERP_ALICE);
            fixture.sessions.rebound(ERP_ALICE);
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).as("独立的会话里没有认证中心的 Cookie").isEqualTo(3);
            assertThat(shared.saved.get("zhang")).extracting(StoredCookie::name).contains("TGC", "sidA")
                    .doesNotContain("sidB");
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(3);
        }
    }

    @Test
    void readOnlyCallsOfOneMemberOverlapWhileAnotherMembersCallWaits() throws Exception {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_READS, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_READS, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            slowEntered = new CountDownLatch(2);
            slowRelease = new CountDownLatch(1);
            Future<String> first = pool.submit(() -> fixture.value(OA_READS, "slow"));
            Future<String> second = pool.submit(() -> fixture.value(OA_READS, "slow"));
            assertThat(slowEntered.await(10, TimeUnit.SECONDS))
                    .as("绑定身份后，concurrent_reads 的只读调用仍然并行").isTrue();
            long erpBefore = seen.stream().filter(line -> line.startsWith("erp GET /data")).count();
            Future<String> writer = pool.submit(() -> fixture.value(ERP_ALICE, "run"));
            Thread.sleep(400);
            assertThat(seen.stream().filter(line -> line.startsWith("erp GET /data")).count())
                    .as("另一个成员没开 concurrent_reads，要等只读调用结束").isEqualTo(erpBefore);
            slowRelease.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("oa:ALICE");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("oa:ALICE");
            assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);
        }
    }

    @Test
    void aMemberWaitingForACodeDoesNotHoldUpOrSpeakForTheOthers() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ASK, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            ExecutionResult paused = fixture.call(OA_ASK, "run");
            assertThat(paused.success()).isFalse();
            assertThat(paused.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(fixture.sessions.pending().current(OA_ASK)).isNotNull();
            assertThat(fixture.sessions.pending().current(ERP_ALICE)).as("等待验证码按账号，不按身份").isNull();

            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(fixture.sessions.pending().current(OA_ASK)).as("别的成员登录不会冲掉它的等待").isNotNull();
            assertThat(fixture.call(OA_ASK, "run").error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);

            ExecutionResult resumed = fixture.engine.continueLogin(fixture.request(OA_ASK, "run"), "246813");
            assertThat(resumed.success()).as(String.valueOf(resumed.error())).isTrue();
            assertThat(fixture.sessions.pending().current(OA_ASK)).isNull();
            assertThat(passwordLogins.get()).as("继续登录时用的是身份里已有的认证中心 Cookie").isEqualTo(1);
            assertThat(fixture.value(OA_ASK, "run")).isEqualTo("oa:ALICE");
        }
    }

    @Test
    void loginVariablesStayWithTheMemberThatExtractedThem() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_VARS, ERP_VARS);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_VARS, "stamp")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_VARS, "stamp")).isEqualTo("erp:ALICE");
            assertThat(fixture.value(OA_VARS, "stamp")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);

            assertThat(stamps).hasSize(3);
            String oa = stamps.get(0);
            String erp = stamps.get(1);
            assertThat(oa).startsWith("oa oa-stamp-");
            assertThat(erp).startsWith("erp erp-stamp-");
            assertThat(stamps.get(2)).as("另一个成员登录后，它的 login.stamp 没被换掉，也没重新登录").isEqualTo(oa);
            assertThat(fixture.sessions.loginSnapshot(OA_VARS).get("stamp")).isEqualTo(oa.substring("oa ".length()));
            assertThat(fixture.sessions.loginSnapshot(ERP_VARS).get("stamp")).isEqualTo(erp.substring("erp ".length()));
        }
    }

    /** H6：能力请求报会话失效后的重跑（含 concurrent_reads 交接）不能再走 alive_check 接手，必须直接重新登录。 */
    @Test
    void retryAfterSessionLossDoesNotAdoptBrokenSessionViaAliveCheck() {
        SessionKey loose = new SessionKey(Origin.LOCAL, "oa_loose_ping", "alice");
        SessionKey readsLoose = new SessionKey(Origin.LOCAL, "oa_reads_loose_ping", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, loose);
        shared.bind("li", OA_BOB, readsLoose);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(loose, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);

            // 应用会话过期，但认证中心 TGC 仍在共享罐里；/loose-ping 仍返回 200。
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            long pingsBefore = seen.stream().filter(line -> line.contains(" /loose-ping ")).count();
            int ticketsBefore = tickets.get();
            assertThat(fixture.value(loose, "run")).as("重试必须跳过探活接手、直接重新换票").isEqualTo("oa:ALICE");
            assertThat(seen.stream().filter(line -> line.contains(" /loose-ping ")).count()).isEqualTo(pingsBefore);
            assertThat(tickets.get()).isEqualTo(ticketsBefore + 1);
            assertThat(passwordLogins.get()).isEqualTo(1);

            // concurrent_reads 路径：共享锁下发现失效并交接给独占锁，也不能走 alive_check 接手。
            assertThat(fixture.value(readsLoose, "run")).isEqualTo("oa:ALICE");
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            long readsPingsBefore = seen.stream().filter(line -> line.contains(" /loose-ping ")).count();
            int readsTicketsBefore = tickets.get();
            assertThat(fixture.value(readsLoose, "run")).as("只读交接重跑必须直接重新换票").isEqualTo("oa:ALICE");
            assertThat(seen.stream().filter(line -> line.contains(" /loose-ping ")).count()).isEqualTo(readsPingsBefore);
            assertThat(tickets.get()).isEqualTo(readsTicketsBefore + 1);
        }
    }

    /** M3：探活接手阶段 requireLoginRefs 已经触发完整登录时，不能再补跑一次 alive_check，也不能误标 reusedCookies。 */
    @Test
    void aliveCheckReferencingLoginVarDoesNotRunRedundantProbeAfterLogin() {
        SessionKey aliveVars = new SessionKey(Origin.LOCAL, "oa_alive_vars", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, aliveVars);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);

            // oa_alive_vars 还没登录、共享罐有 Cookie，原本要走探活接手；但 alive_check 引用了 login.stamp，
            // requireLoginRefs 会直接跑完整登录，之后不应再请求 /ping-stamp。
            assertThat(fixture.value(aliveVars, "stamp")).isEqualTo("oa:ALICE");
            assertThat(seen).noneMatch(line -> line.contains(" /ping-stamp "));
            assertThat(passwordLogins.get()).isEqualTo(1);
        }
    }

    /** P1-S3：探活接手（adopting）不应标 reusedCookies，首轮动作失效时先留着 TGC 换票，不清空全身份 Cookie。 */
    @Test
    void adoptingThroughLooseAliveCheckDoesNotWipeSharedIdentityOnFirstActionExpiry() {
        SessionKey loose = new SessionKey(Origin.LOCAL, "oa_loose_ping", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE, loose);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);

            // 仅让 OA 应用会话失效，认证中心 TGC 与 ERP 会话仍有效；loose 首次调用靠 /loose-ping 探活接手。
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            assertThat(fixture.value(loose, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).as("应用会话过期只换票据，不应清空全身份重交密码").isEqualTo(1);
            assertThat(fixture.sessions.lastCleared("zhang")).isNull();
            assertThat(shared.saved.get("zhang")).extracting(StoredCookie::name).contains("TGC", "sidA", "sidB");

            int ticketsBefore = tickets.get();
            assertThat(fixture.value(ERP_ALICE, "run")).as("ERP 会话仍有效").isEqualTo("erp:ALICE");
            assertThat(tickets.get()).isEqualTo(ticketsBefore);
        }
    }

    /** P1-S2：共享 Cookie 登录遇认证中心 5xx 时，不再清空整个身份的 Cookie。 */
    @Test
    void upstream5xxDuringSharedCookieLoginDoesNotClearIdentity() {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");

            appSessions.keySet().removeIf(value -> value.startsWith("erp-"));
            sso503 = true;
            ExecutionResult failed = fixture.call(ERP_ALICE, "run");
            assertThat(failed.success()).isFalse();
            assertThat(failed.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(fixture.sessions.lastCleared("zhang")).as("5xx 是上游故障，不能清空全身份 Cookie").isNull();
            assertThat(shared.saved.get("zhang")).extracting(StoredCookie::name).contains("TGC", "sidA");

            int ticketsBefore = tickets.get();
            assertThat(fixture.value(OA_ALICE, "run")).as("OA 会话未受影响").isEqualTo("oa:ALICE");
            assertThat(tickets.get()).isEqualTo(ticketsBefore);
        }
    }

    /** P1-S1：跨源 authHop 跳转必须剥离含 login 已知值（seenLogin/snapshot）与历史 Cookie 的自定义头。 */
    @Test
    void crossOriginAuthHopStripsHeadersContainingKnownLoginAndSeenCookieSecrets() {
        Set<String> gathered = HttpCalls.sensitiveValues(
                Map.of("login", (HttpCalls.SecretSource) () -> List.of("snap-secret-1")),
                new BasicCookieStore(),
                List.of("old-cookie-val-2"));
        assertThat(gathered).contains("snap-secret-1", "old-cookie-val-2");

        SessionKey askHop = new SessionKey(Origin.LOCAL, "oa_ask_hop", "alice");
        MemoryShared shared = new MemoryShared();
        try (Fixture fixture = new Fixture(shared)) {
            ExecutionResult paused = fixture.call(askHop, "run");
            assertThat(paused.success()).isFalse();
            assertThat(paused.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);

            ssoGetHeaders.clear();
            ExecutionResult resumed = fixture.engine.continueLogin(
                    fixture.request(askHop, "run"), "otp-secret-8899");
            assertThat(resumed.success()).as(String.valueOf(resumed.error())).isTrue();
            assertThat(ssoGetHeaders)
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(line).isEqualTo("frozen=null otp=null plain=keep-me"));
        }
    }

    /** P3-1：开发追踪的 session.cookieNames 按当前规则包 base_url 过滤，不混入同身份其它主机的 Cookie。 */
    @Test
    void devTraceCookieNamesOnlyIncludesCookiesForThisConchBaseHost() throws Exception {
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, ERP_ALICE);
        AtomicReference<String> traceBody = new AtomicReference<>();
        try (Fixture fixture = new Fixture(shared)) {
            fixture.engine.bindDevTraceSink(new DevTraceSink() {
                @Override
                public DevTraceDraft open(ExecutionRequest request) {
                    return new DevTraceDraft(Instant.parse("2026-11-23T01:00:00Z"));
                }

                @Override
                public void save(ExecutionRequest request, Instant expiresAt, String body) {
                    traceBody.set(body);
                }
            });
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            JsonNode cookieNames = new ObjectMapper().readTree(traceBody.get()).at("/session/cookieNames");
            List<String> names = new ArrayList<>();
            cookieNames.forEach(node -> names.add(node.asText()));
            assertThat(names).contains("sidA").doesNotContain("TGC");
        }
    }

    // 1.12 的失效判定（expired_when、alive_check.expect、assert 的 on_fail）和身份共用叠加。

    @Test
    void expiredWhenOnABoundMemberOnlyFetchesANewTicket() {
        SessionKey soft = new SessionKey(Origin.LOCAL, "oa_soft", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", soft, ERP_ALICE);
        softExpiry = true;
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(soft, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);
            int before = tickets.get();
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            assertThat(fixture.value(soft, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(tickets.get()).isEqualTo(before + 1);
            assertThat(fixture.sessions.lastCleared("zhang")).isNull();
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(tickets.get()).isEqualTo(before + 1);
        }
    }

    @Test
    void expiredWhenOnANonIdempotentCapabilityReportsExpiredAndTheNextCallRecovers() {
        SessionKey soft = new SessionKey(Origin.LOCAL, "oa_soft_once", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", soft, ERP_ALICE);
        softExpiry = true;
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(soft, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            long dataBefore = seen.stream().filter(line -> line.startsWith("oa GET /data")).count();
            ExecutionResult lost = fixture.call(soft, "run");
            assertThat(lost.success()).isFalse();
            assertThat(lost.error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(seen.stream().filter(line -> line.startsWith("oa GET /data")).count())
                    .as("非幂等不重放").isEqualTo(dataBefore + 1);
            assertThat(fixture.sessions.lastCleared("zhang")).isNull();
            assertThat(fixture.value(soft, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);
        }
    }

    @Test
    void concurrentReadsThatHitExpiredWhenLogInOnceAndKeepThePeerLoggedIn() throws Exception {
        SessionKey reads = new SessionKey(Origin.LOCAL, "oa_soft_reads", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", reads, ERP_ALICE);
        softExpiry = true;
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(reads, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            int before = tickets.get();
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            List<Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(pool.submit(() -> fixture.value(reads, "run")));
            }
            for (Future<String> call : calls) {
                assertThat(call.get(15, TimeUnit.SECONDS)).isEqualTo("oa:ALICE");
            }
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(tickets.get()).as("6 个并行调用只重新换一次票据").isEqualTo(before + 1);
            assertThat(fixture.sessions.lastCleared("zhang")).isNull();
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(tickets.get()).isEqualTo(before + 1);
        }
    }

    @Test
    void assertOnFailInsideRepeatAndIfOnlyFetchesANewTicket() {
        SessionKey asserts = new SessionKey(Origin.LOCAL, "oa_assert", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", asserts, ERP_ALICE);
        softExpiry = true;
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(asserts, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            int before = tickets.get();
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            assertThat(fixture.value(asserts, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);
            assertThat(tickets.get()).isEqualTo(before + 1);
            assertThat(fixture.sessions.lastCleared("zhang")).isNull();
        }
    }

    @Test
    void aliveCheckExpectAdoptsThePeersSessionAndFallsBackToATicket() {
        SessionKey expect = new SessionKey(Origin.LOCAL, "oa_expect", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", OA_ALICE, expect, ERP_ALICE);
        softExpiry = true;
        try (Fixture fixture = new Fixture(shared)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            int before = tickets.get();
            assertThat(fixture.value(expect, "run")).isEqualTo("oa:ALICE");
            assertThat(tickets.get()).as("探活的 expect 成立：直接用同伴登录出来的会话").isEqualTo(before);
        }
        // 另一种情况：同伴的 OA 会话已经失效，探活回 200 但 expect 不成立，应当只换票据。
        MemoryShared again = new MemoryShared();
        again.bind("wang", OA_ALICE, expect, ERP_ALICE);
        try (Fixture fixture = new Fixture(again)) {
            assertThat(fixture.value(OA_ALICE, "run")).isEqualTo("oa:ALICE");
            int logins = passwordLogins.get();
            int before = tickets.get();
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            assertThat(fixture.value(expect, "run")).isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(logins);
            assertThat(tickets.get()).isEqualTo(before + 1);
            assertThat(fixture.sessions.lastCleared("wang")).isNull();
        }
    }

    /** 规则把业务错误码也写进 expired_when：清空没有用，10 分钟内只连累同身份的成员一次。 */
    @Test
    void aRuleThatTakesABusinessErrorForExpiryLogsItsPeersOutOnlyOncePerWindow() {
        SessionKey soft = new SessionKey(Origin.LOCAL, "oa_soft", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", soft, ERP_ALICE);
        EngineTestSupport.MutableClock clock = new EngineTestSupport.MutableClock();
        try (Fixture fixture = new Fixture(shared, clock)) {
            assertThat(fixture.value(soft, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(1);

            bizCode = "FORBIDDEN";
            assertThat(fixture.call(soft, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            SessionManager.Cleared first = fixture.sessions.lastCleared("zhang");
            assertThat(first.by()).as("第一次：带着旧 Cookie 登录成功后又失效，清空一次").isEqualTo(soft);
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).as("同伴被登出，重新交了一次密码").isEqualTo(2);

            for (int i = 0; i < 3; i++) {
                clock.advance(Duration.ofMinutes(1));
                assertThat(fixture.call(soft, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
                int tickets = this.tickets.get();
                assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
                assertThat(this.tickets.get()).as("窗口内不再清空：同伴的会话留着").isEqualTo(tickets);
            }
            assertThat(passwordLogins.get()).isEqualTo(2);
            assertThat(fixture.sessions.lastCleared("zhang")).isEqualTo(first);

            clock.advance(SessionManager.RELAPSE_CLEAR_WINDOW);
            assertThat(fixture.call(soft, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(fixture.sessions.lastCleared("zhang").at()).as("窗口过后可以再清空一次").isAfter(first.at());
        }
    }

    /** 非幂等能力同理：第二次调用清空一次，之后窗口内不再把密码登录转给同伴。 */
    @Test
    void aNonIdempotentCapabilityThatKeepsLosingItsSessionClearsTheIdentityOnlyOncePerWindow() {
        SessionKey once = new SessionKey(Origin.LOCAL, "oa_soft_once", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", once, ERP_ALICE);
        try (Fixture fixture = new Fixture(shared, new EngineTestSupport.MutableClock())) {
            assertThat(fixture.value(once, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            bizCode = "FORBIDDEN";
            assertThat(fixture.call(once, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(fixture.sessions.lastCleared("zhang")).as("第一次只让自己登出").isNull();
            assertThat(fixture.call(once, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(fixture.sessions.lastCleared("zhang").by()).isEqualTo(once);
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(2);
            for (int i = 0; i < 3; i++) {
                assertThat(fixture.call(once, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
                assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");
            }
            assertThat(passwordLogins.get()).as("窗口内同伴不再被登出").isEqualTo(2);
        }
    }

    /** 失效的 Cookie 让登录步骤看似成功、没换到新会话：清空一次，下一次调用从空白登录恢复。 */
    @Test
    void aStaleCookieTheLoginStepsDoNotReplaceIsClearedOnceAndTheNextCallRecovers() {
        SessionKey weak = new SessionKey(Origin.LOCAL, "oa_weak", "alice");
        MemoryShared shared = new MemoryShared();
        shared.bind("zhang", weak, ERP_ALICE);
        EngineTestSupport.MutableClock clock = new EngineTestSupport.MutableClock();
        try (Fixture fixture = new Fixture(shared, clock)) {
            assertThat(fixture.value(weak, "run")).isEqualTo("oa:ALICE");
            assertThat(fixture.value(ERP_ALICE, "run")).isEqualTo("erp:ALICE");

            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            assertThat(fixture.call(weak, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(fixture.sessions.lastCleared("zhang").by()).isEqualTo(weak);
            assertThat(fixture.value(weak, "run")).as("清空后从空白登录").isEqualTo("oa:ALICE");
            assertThat(passwordLogins.get()).isEqualTo(2);

            // 窗口内又遇到一次：不再清空，只有这个成员登出；测试登录从空白开始，能手动恢复。
            clock.advance(Duration.ofMinutes(1));
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            assertThat(fixture.call(weak, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(fixture.call(weak, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(fixture.engine.testLogin(fixture.request(weak, "run")).success()).isTrue();
            assertThat(fixture.value(weak, "run")).isEqualTo("oa:ALICE");

            // 测试登录之后窗口重新算：再遇到时又能清空一次，这一次调用自己重登后成功。
            appSessions.keySet().removeIf(value -> value.startsWith("oa-"));
            assertThat(fixture.call(weak, "run").error().code()).isEqualTo(ErrorCode.SESSION_EXPIRED);
            assertThat(fixture.value(weak, "run")).isEqualTo("oa:ALICE");
        }
    }

    private static final String EXPIRED_WHEN =
            "  expired_when:\n    - json_equals: {\"$.code\": [NOT_LOGIN, FORBIDDEN]}\n";

    private static String v112(String yaml) {
        return yaml.replace("min_crab_version: 1.11.0", "min_crab_version: 1.12.0");
    }

    private static String once(String yaml) {
        return yaml.replace("    idempotent: true\n", "    idempotent: false\n");
    }

    private static String assertsInRepeat(String yaml) {
        return yaml.replace(
                "      - request: {method: GET, path: /data}\n      - extract:\n",
                "      - repeat:\n          max: 2\n          steps:\n"
                        + "            - request: {method: GET, path: /data}\n"
                        + "            - if:\n                contains: '\"code\"'\n                then:\n"
                        + "                  - assert: {not_contains: NOT_LOGIN, on_fail: session_expired}\n"
                        + "      - extract:\n");
    }

    /** 给 conch() 的规则加上：只读能力、登录时等验证码、登录时取一个变量并在能力里用。 */
    private static String readOnly(String yaml) {
        return yaml.replace("    idempotent: true\n", "    idempotent: true\n    mcp: {expose: true, read_only: true}\n");
    }

    private static String asksForCode(String yaml) {
        return yaml.replace(
                "    - request: {method: GET, path: /home, follow_redirects: true}\n",
                "    - ask: {into: code, kind: code, label: 验证码, timeout_s: 60}\n"
                        + "    - request: {method: GET, path: /home, follow_redirects: true}\n");
    }

    private static String asksForCodeWithHeaders(String yaml) {
        return yaml.replace(
                "    - request: {method: GET, path: /home, follow_redirects: true}\n",
                "    - set: {carry_val: \"frozen-secret-val-9\"}\n"
                        + "    - ask: {into: code, kind: code, label: 验证码, timeout_s: 60}\n"
                        + "    - request:\n"
                        + "        method: GET\n"
                        + "        path: /home\n"
                        + "        follow_redirects: true\n"
                        + "        headers:\n"
                        + "          X-Frozen: \"{{carry_val}}\"\n"
                        + "          X-Otp: \"{{code}}\"\n"
                        + "          X-Plain: \"keep-me\"\n");
    }

    private static String extractsStamp(String yaml) {
        String withLogin = yaml.replace(
                "    - assert: {status: 200, contains: '\"value\"'}\n",
                "    - assert: {status: 200, contains: '\"value\"'}\n"
                        + "    - request: {method: GET, path: /stamp}\n"
                        + "    - extract:\n"
                        + "        stamp: {jsonpath: \"$.value\"}\n");
        return withLogin + """
                  stamp:
                    title: 登录变量
                    description: 说明
                    idempotent: true
                    output:
                      value: {type: string, description: v}
                    examples:
                      - input: {}
                        output: {}
                    notes: 注意
                    steps:
                      - request: {method: GET, path: /data, query: {stamp: "{{login.stamp}}"}}
                      - extract:
                          value: {jsonpath: "$.value"}
                """;
    }

    private String conch(String name, String base, String extra) {
        return """
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.11.0
                base_url: %s
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                auth:
                  origins:
                    - %s
                %s
                  steps:
                    - request: {method: GET, path: /home, follow_redirects: true}
                    - if:
                        contains: 'name="password"'
                        then:
                          - request:
                              method: POST
                              url: %s/login
                              form:
                                username: "{{credential.username}}"
                                password: "{{credential.password}}"
                                service: %s
                              follow_redirects: true
                    - assert: {status: 200, contains: '"value"'}
                actions:
                  run:
                    title: 能力
                    description: 说明
                    idempotent: true
                    output:
                      value: {type: string, description: v}
                    examples:
                      - input: {}
                        output: {}
                    notes: 注意
                    steps:
                      - request: {method: GET, path: /data}
                      - extract:
                          value: {jsonpath: "$.value"}
                  slow:
                    title: 慢
                    description: 说明
                    idempotent: true
                    output:
                      value: {type: string, description: v}
                    examples:
                      - input: {}
                        output: {}
                    notes: 注意
                    steps:
                      - request: {method: GET, path: /slow}
                      - extract:
                          value: {jsonpath: "$.value"}
                  peek:
                    title: 读 Cookie
                    description: 说明
                    idempotent: true
                    output:
                      value: {type: string, description: v}
                    examples:
                      - input: {}
                        output: {}
                    notes: 注意
                    steps:
                      - request: {method: GET, path: /data}
                      - assert: {cookie_exists: TGC}
                      - extract:
                          value: {jsonpath: "$.value"}
                """.formatted(name, base, ssoUrl, extra, ssoUrl, base.equals(erpUrl) ? "erp" : "oa");
    }

    /** 内存里的身份绑定和身份 Cookie。 */
    private static final class MemoryShared implements SharedSessions {
        private final Map<SessionKey, String> bound = new ConcurrentHashMap<>();
        private final Map<String, List<StoredCookie>> saved = new ConcurrentHashMap<>();

        void bind(String identity, SessionKey... keys) {
            for (SessionKey key : keys) {
                bound.put(key, identity);
            }
        }

        void unbind(SessionKey key) {
            bound.remove(key);
        }

        @Override
        public String identityOf(SessionKey key) {
            return bound.get(key);
        }

        @Override
        public List<SessionKey> members(String identity) {
            ArrayList<SessionKey> members = new ArrayList<>();
            bound.forEach((key, id) -> {
                if (id.equals(identity)) {
                    members.add(key);
                }
            });
            return members;
        }

        @Override
        public List<StoredCookie> load(String identity) {
            return saved.getOrDefault(identity, List.of());
        }

        @Override
        public void save(String identity, List<StoredCookie> cookies) {
            saved.put(identity, List.copyOf(cookies));
        }

        @Override
        public void delete(String identity) {
            saved.remove(identity);
        }
    }

    private final class Fixture implements AutoCloseable {
        private final SessionManager sessions;
        private final RuleEngine engine;
        private volatile String password = EngineTestSupport.PASSWORD;

        private Fixture(SharedSessions shared) {
            this(shared, Clock.systemUTC());
        }

        private Fixture(SharedSessions shared, Clock clock) {
            EngineSettings settings = new EngineSettings(Duration.ofSeconds(20), Duration.ofMinutes(10), 3);
            sessions = new SessionManager(
                    new NopSessionPersistence(), new NopLoginSnapshotStore(), shared, settings, clock);
            ConchRegistry registry = new ConchRegistry();
            for (String yaml : List.of(
                    conch("oa", oaUrl, ""),
                    conch("erp", erpUrl, ""),
                    conch("oa_reports", oaUrl, "  alive_check:\n    request: {method: GET, path: /ping}\n"),
                    conch("oa_loose_ping", oaUrl, "  alive_check:\n    request: {method: GET, path: /loose-ping}\n"),
                    readOnly(conch("oa_reads_only", oaUrl, "  concurrent_reads: true\n")),
                    readOnly(conch("oa_reads_loose_ping", oaUrl,
                            "  concurrent_reads: true\n  alive_check:\n    request: {method: GET, path: /loose-ping}\n")),
                    asksForCode(conch("oa_ask", oaUrl, "")),
                    v112(conch("oa_soft", oaUrl, EXPIRED_WHEN)),
                    conch("oa_weak", oaUrl, "").replace("path: /home, follow_redirects", "path: /weak-home, follow_redirects"),
                    v112(once(conch("oa_soft_once", oaUrl, EXPIRED_WHEN))),
                    v112(readOnly(conch("oa_soft_reads", oaUrl, "  concurrent_reads: true\n" + EXPIRED_WHEN))),
                    v112(assertsInRepeat(conch("oa_assert", oaUrl, ""))),
                    v112(conch("oa_expect", oaUrl,
                            "  alive_check:\n    request: {method: GET, path: /data}\n"
                                    + "    expect:\n      json_exists: \"$.value\"\n      cookie_exists: sidA\n")),
                    asksForCodeWithHeaders(conch("oa_ask_hop", oaUrl, "")),
                    extractsStamp(conch("oa_vars", oaUrl, "")),
                    extractsStamp(conch("oa_alive_vars", oaUrl,
                            "  alive_check:\n    request: {method: GET, path: /ping-stamp, query: {stamp: \"{{login.stamp}}\"}}\n")),
                    extractsStamp(conch("erp_vars", erpUrl, "")))) {
                ConchDoc doc = ConchParser.parse(yaml);
                assertThat(ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT)).errors())
                        .isEmpty();
                registry.put(doc, Origin.LOCAL);
            }
            engine = new RuleEngine(registry, sessions, settings);
        }

        private ExecutionRequest request(SessionKey key, String action) {
            String user = key.account().equals("bob") || key.account().equals("second") ? "bob" : "alice";
            return EngineTestSupport.request(
                    key.origin(), key.conch(), action, key.account(), Map.of(),
                    new EngineTestSupport.MapAccount(Map.of("username", user, "password", password), Map.of()));
        }

        private ExecutionResult call(SessionKey key, String action) {
            return engine.execute(request(key, action));
        }

        private String value(SessionKey key, String action) {
            ExecutionResult result = call(key, action);
            assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
            return String.valueOf(((Map<?, ?>) result.data()).get("value"));
        }

        @Override
        public void close() {
            sessions.close();
        }
    }
}
