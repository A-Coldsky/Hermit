package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
        if (user == null) {
            redirect(exchange, ssoUrl + "/login?service=" + name);
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
            EngineSettings settings = new EngineSettings(Duration.ofSeconds(20), Duration.ofMinutes(10), 3);
            sessions = new SessionManager(
                    new NopSessionPersistence(), new NopLoginSnapshotStore(), shared, settings, Clock.systemUTC());
            ConchRegistry registry = new ConchRegistry();
            for (String yaml : List.of(
                    conch("oa", oaUrl, ""),
                    conch("erp", erpUrl, ""),
                    conch("oa_reports", oaUrl, "  alive_check:\n    request: {method: GET, path: /ping}\n"),
                    readOnly(conch("oa_reads_only", oaUrl, "  concurrent_reads: true\n")),
                    asksForCode(conch("oa_ask", oaUrl, "")),
                    extractsStamp(conch("oa_vars", oaUrl, "")),
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
