package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.testing.LocalHttps;
import dev.hermit.crab.tls.OutboundTls;

class LegacyHttpCompatTest {

    @Test
    void sameOriginRedirectKeepsHeadersAndDropsBodyOnGet() throws Exception {
        try (ScriptedLoopback loop = ScriptedLoopback.start(); CloseableHttpClient client = plainClient()) {
            loop.reply(redirect(302, loop.base() + "/land"));
            loop.reply(ScriptedLoopback.response(200, "text/plain", "landed".getBytes(StandardCharsets.UTF_8)));
            HttpResult followed = call(client, post("/start"), loop.base());
            assertThat(followed.status()).isEqualTo(200);
            assertThat(followed.body()).isEqualTo("landed");
            assertThat(loop.hits()).hasSize(2);
            ScriptedLoopback.Hit first = loop.hits().get(0);
            ScriptedLoopback.Hit second = loop.hits().get(1);
            assertThat(first.method()).isEqualTo("POST");
            assertThat(first.header("authorization")).isEqualTo("Bearer keep-me");
            assertThat(first.header("x-trace")).isEqualTo("trace-value");
            assertThat(first.header("content-type")).isEqualTo("application/custom");
            assertThat(new String(first.body(), StandardCharsets.UTF_8)).isEqualTo("a=b");
            assertThat(second.method()).isEqualTo("GET");
            assertThat(second.target()).isEqualTo("/land");
            assertThat(second.header("authorization")).isEqualTo("Bearer keep-me");
            assertThat(second.header("x-trace")).isEqualTo("trace-value");
            assertThat(second.header("content-type")).isNull();
            assertThat(second.body()).isEmpty();
            assertThat(second.header("cookie")).isNull();

            loop.reply(redirect(307, "/keep"));
            loop.reply(ScriptedLoopback.response(200, "text/plain", "kept".getBytes(StandardCharsets.UTF_8)));
            HttpResult kept = call(client, post("/start"), loop.base());
            assertThat(kept.status()).isEqualTo(200);
            ScriptedLoopback.Hit posted = loop.hits().get(3);
            assertThat(posted.method()).isEqualTo("POST");
            assertThat(posted.target()).isEqualTo("/keep");
            assertThat(posted.header("authorization")).isEqualTo("Bearer keep-me");
            assertThat(posted.header("content-type")).isEqualTo("application/custom");
            assertThat(new String(posted.body(), StandardCharsets.UTF_8)).isEqualTo("a=b");

            loop.reply(redirect(308, "/still"));
            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            call(client, post("/start"), loop.base());
            ScriptedLoopback.Hit still = loop.hits().get(5);
            assertThat(still.method()).isEqualTo("POST");
            assertThat(new String(still.body(), StandardCharsets.UTF_8)).isEqualTo("a=b");
        }
    }

    @Test
    void stopsAtCrossOriginAndAfterFiveRedirects() throws Exception {
        try (ScriptedLoopback loop = ScriptedLoopback.start(); CloseableHttpClient client = plainClient()) {
            loop.reply(redirect(302, "https://127.0.0.1:8443/secret-path?token=S3cret!pwd"));
            HttpResult cross = call(client, Map.of(
                    "method", "GET",
                    "path", "/away",
                    "follow_redirects", true), loop.base());
            assertThat(cross.status()).isEqualTo(302);
            assertThat(loop.hits()).hasSize(1);
            assertThat(cross.warnings()).extracting(TraceWarning::code).containsExactly(TraceWarning.CROSS_ORIGIN);
            assertThat(cross.warnings().get(0).message()).isEqualTo("跳转到不同源 https://127.0.0.1:8443，未跟随");
            assertThat(cross.warnings().get(0).message()).doesNotContain("secret-path").doesNotContain("S3cret");

            loop.reply(redirect(302, "http://127.0.0.1:1/nope"));
            HttpResult ignored = call(client, Map.of("method", "GET", "path", "/away"), loop.base());
            assertThat(ignored.status()).isEqualTo(302);
            assertThat(ignored.warnings()).isEmpty();

            for (int i = 0; i < 6; i++) {
                loop.reply(redirect(302, "/loop"));
            }
            RuleProblem tooMany = failure(client, Map.of(
                    "method", "GET",
                    "path", "/loop",
                    "follow_redirects", true), loop.base());
            assertThat(tooMany.code).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(tooMany.summary).isEqualTo(HttpCalls.TOO_MANY_REDIRECTS);
            assertThat(tooMany.summary).doesNotContain("上游状态码");
            assertThat(loop.hits()).hasSize(8);

            for (int i = 0; i < 5; i++) {
                loop.reply(redirect(302, "/step"));
            }
            loop.reply(ScriptedLoopback.response(200, "text/plain", "done".getBytes(StandardCharsets.UTF_8)));
            HttpResult done = call(client, Map.of(
                    "method", "GET",
                    "path", "/step",
                    "follow_redirects", true), loop.base());
            assertThat(done.status()).isEqualTo(200);
            assertThat(done.body()).isEqualTo("done");
            assertThat(loop.hits()).hasSize(14);
        }
    }

    @Test
    void recoversBareUtf8LocationAndEncodesSpaces() throws Exception {
        try (ScriptedLoopback loop = ScriptedLoopback.start(); CloseableHttpClient client = plainClient()) {
            byte[] location = "/文件 名".getBytes(StandardCharsets.UTF_8);
            byte[] raw = join(
                    "HTTP/1.1 302 Found\r\nLocation: ".getBytes(StandardCharsets.US_ASCII),
                    location,
                    "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            loop.reply(raw);
            String encoded = "/" + URLEncoder.encode("文件", StandardCharsets.UTF_8) + "%20"
                    + URLEncoder.encode("名", StandardCharsets.UTF_8);
            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            HttpResult result = call(client, Map.of(
                    "method", "GET",
                    "path", "/start",
                    "follow_redirects", true), loop.base());
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.redirect()).isNull();
            assertThat(loop.hits().get(1).target()).isEqualTo(encoded);
            assertThat(loop.hits().get(1).target()).doesNotContain(" ");

            loop.reply(redirect(302, "/a%2Fb c"));
            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            call(client, Map.of("method", "GET", "path", "/start", "follow_redirects", true), loop.base());
            assertThat(loop.hits().get(3).target()).isEqualTo("/a%2Fb%20c");
        }
    }

    @Test
    void defaultPortHttpToHttpsDropsCredentials(@TempDir Path dir) throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(dir.resolve("lab"));
        Path caDir = dir.resolve("ca");
        Files.createDirectories(caDir);
        Files.copy(certs.rootPem(), caDir.resolve("root.pem"));
        try (ScriptedLoopback http = ScriptedLoopback.start();
             LocalHttps https = LocalHttps.start(new X509Certificate[] {certs.leaf()}, certs.leafKey())) {
            http.reply("HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1/land\r\n"
                    + "Set-Cookie: SID=cookie-secret-value; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            OutboundTls tls = OutboundTls.open(caDir, false);
            BasicCookieStore cookies = new BasicCookieStore();
            try (CloseableHttpClient client = HttpClients.custom()
                    .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                            .setTlsSocketStrategy(tls.tlsStrategy())
                            .setSchemePortResolver(host -> "https".equalsIgnoreCase(host.getSchemeName())
                                    ? https.port()
                                    : http.port())
                            .setDefaultConnectionConfig(ConnectionConfig.custom()
                                    .setConnectTimeout(Timeout.ofSeconds(3))
                                    .setSocketTimeout(Timeout.ofSeconds(3))
                                    .build())
                            .build())
                    .setDefaultCookieStore(cookies)
                    .setDefaultCookieSpecRegistry(LegacyCookies.registry())
                    .addResponseInterceptorLast(LegacyCookies.notices())
                    .addRequestInterceptorLast(HttpCalls.crossOriginStripper())
                    .disableRedirectHandling()
                    .disableAutomaticRetries()
                    .build()) {
                LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Bearer secret-token");
                headers.put("X-Trace", "keep-trace");
                headers.put("X-Token", "S3cret!pwd");
                headers.put("X-Enc", URLEncoder.encode("S3cret!pwd", StandardCharsets.UTF_8));
                LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
                spec.put("method", "POST");
                spec.put("url", "http://127.0.0.1/from");
                spec.put("follow_redirects", true);
                spec.put("form", Map.of("a", "b"));
                spec.put("headers", headers);
                Set<String> sensitive = HttpCalls.sensitiveValues(
                        Map.of("credential", Map.of("password", "S3cret!pwd")), null);
                HttpResult result = HttpCalls.execute(
                        spec,
                        client,
                        () -> { },
                        URI.create("http://127.0.0.1/"),
                        null,
                        deadline(),
                        sensitive);
                assertThat(result.status()).isEqualTo(200);
                assertThat(result.body()).isEqualTo("ok");
                assertThat(http.hits()).hasSize(1);
                assertThat(http.hits().get(0).header("authorization")).isEqualTo("Bearer secret-token");
                assertThat(http.hits().get(0).header("x-token")).contains("S3cret!pwd");
                assertThat(https.requests()).hasSize(1);
                String landed = https.requests().get(0);
                assertThat(landed).contains("GET /land").contains("X-Trace: keep-trace");
                assertThat(landed).doesNotContain("Authorization")
                        .doesNotContain("S3cret")
                        .doesNotContain("cookie-secret-value")
                        .doesNotContain("SID=");
            }
        }
    }

    @Test
    void stepEncodingOverridesContentTypeAndOmittedDoesNot() throws Exception {
        Charset gbk = Charset.forName("GBK");
        byte[] zhang = "张三".getBytes(gbk);
        try (ScriptedLoopback loop = ScriptedLoopback.start(); CloseableHttpClient client = plainClient()) {
            loop.reply(ScriptedLoopback.response(200, "text/plain; charset=iso-8859-1", zhang));
            HttpResult forced = call(client, Map.of(
                    "method", "GET", "path", "/name", "encoding", "gbk"), loop.base());
            assertThat(forced.body()).isEqualTo("张三");

            loop.reply(ScriptedLoopback.response(200, "text/plain; charset=gbk", zhang));
            HttpResult typed = call(client, Map.of("method", "GET", "path", "/name"), loop.base());
            assertThat(typed.body()).isEqualTo("张三");

            loop.reply(ScriptedLoopback.response(200, "text/plain; charset=iso-8859-1", zhang));
            HttpResult omitted = call(client, Map.of("method", "GET", "path", "/name", "encoding", " "), loop.base());
            assertThat(omitted.body()).isNotEqualTo("张三");

            byte[] page = "<html><head><meta charset=\"gbk\"></head><body>张三</body></html>".getBytes(gbk);
            loop.reply(ScriptedLoopback.response(200, "text/html", page));
            HttpResult meta = call(client, Map.of("method", "GET", "path", "/name"), loop.base());
            assertThat(meta.body()).contains("张三");
        }
    }

    @Test
    void legacyCookiesAreStoredPerAccountAndWarnedWithoutValues() throws Exception {
        LocalDate future = LocalDate.of(2030, 11, 9);
        String weekday = future.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String expires = weekday + ", 09-Nov-30 23:12:40 GMT";
        String set = String.join("\r\n",
                "HTTP/1.1 200 OK",
                "Set-Cookie: NS99=legacy-year-value; Expires=Wednesday, 09-Nov-99 23:12:40 GMT; Path=/",
                "Set-Cookie: NS30=future-cookie-value; Expires=" + expires + "; Path=/",
                "Set-Cookie: SEC=secure-cookie-secret; Secure; Path=/",
                "Set-Cookie: BAD=rejected-secret-value; Expires=not-a-date",
                "Set-Cookie: =empty-name-secret",
                "Set-Cookie: P=path-cookie-value; Path=app",
                "Set-Cookie: SLASH=slash-cookie-value; Path=/app",
                "Content-Length: 2",
                "Connection: close",
                "",
                "ok");
        try (ScriptedLoopback loop = ScriptedLoopback.start(); Accounts accounts = Accounts.open()) {
            CloseableHttpClient alice = accounts.client(Origin.LOCAL, "legacy", "alice");
            CloseableHttpClient bob = accounts.client(Origin.LOCAL, "legacy", "bob");
            CloseableHttpClient other = accounts.client(Origin.OFFICIAL, "legacy", "alice");
            loop.reply(set);
            HttpResult first = call(alice, Map.of(
                    "method", "GET", "path", "/app/page", "headers", Map.of("Cookie", "HACK=hacked-cookie-value")),
                    loop.base());
            assertThat(first.status()).isEqualTo(200);
            assertThat(loop.hits().get(0).header("cookie")).isNull();
            assertThat(messages(first)).contains(
                    "收到 Secure cookie SEC，明文连接不会发送",
                    "Cookie BAD 无法解析，已忽略",
                    "有一条 Set-Cookie 无法解析，已忽略");
            assertThat(String.join("\n", messages(first)))
                    .doesNotContain("secure-cookie-secret")
                    .doesNotContain("rejected-secret-value")
                    .doesNotContain("empty-name-secret")
                    .doesNotContain("legacy-year-value");
            assertThat(first.warnings()).noneMatch(warning ->
                    TraceWarning.COOKIE_REJECTED.equals(warning.code()) && warning.message().contains("NS99"));
            assertThat(first.warnings()).noneMatch(warning ->
                    TraceWarning.COOKIE_REJECTED.equals(warning.code()) && warning.message().contains("NS30"));

            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            call(alice, Map.of("method", "GET", "path", "/app/other"), loop.base());
            String underApp = loop.hits().get(1).header("cookie");
            assertThat(underApp).doesNotContain("NS99=").contains("NS30=future-cookie-value");
            assertThat(underApp).contains("P=path-cookie-value").contains("SLASH=slash-cookie-value");
            assertThat(underApp).doesNotContain("SEC=").doesNotContain("BAD=").doesNotContain("HACK=");

            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            call(alice, Map.of("method", "GET", "path", "/nope"), loop.base());
            String elsewhere = loop.hits().get(2).header("cookie");
            assertThat(elsewhere).doesNotContain("NS99=").contains("NS30=");
            assertThat(elsewhere).doesNotContain("P=").doesNotContain("SLASH=");

            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            call(bob, Map.of("method", "GET", "path", "/app/other"), loop.base());
            assertThat(loop.hits().get(3).header("cookie")).isNull();

            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));
            call(other, Map.of("method", "GET", "path", "/app/other"), loop.base());
            assertThat(loop.hits().get(4).header("cookie")).isNull();
        }
    }

    @Test
    void twoDigitYear1999RemovesAnExistingCookie() throws Exception {
        byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
        try (ScriptedLoopback loop = ScriptedLoopback.start(); Accounts accounts = Accounts.open()) {
            CloseableHttpClient client = accounts.client(Origin.LOCAL, "legacy", "alice");
            loop.reply(ScriptedLoopback.response(200, "text/plain", ok, "Set-Cookie: SID=keep-session; Path=/"));
            call(client, Map.of("method", "GET", "path", "/session"), loop.base());

            loop.reply(ScriptedLoopback.response(200, "text/plain", ok,
                    "Set-Cookie: SID=keep-session; Expires=Wednesday, 09-Nov-99 23:12:40 GMT; Path=/"));
            HttpResult logout = call(client, Map.of("method", "GET", "path", "/session"), loop.base());
            assertThat(loop.hits().get(1).header("cookie")).contains("SID=keep-session");
            assertThat(logout.warnings()).noneMatch(warning -> TraceWarning.COOKIE_REJECTED.equals(warning.code()));

            loop.reply(ScriptedLoopback.response(200, "text/plain", ok));
            call(client, Map.of("method", "GET", "path", "/session"), loop.base());
            String after = loop.hits().get(2).header("cookie");
            if (after != null) {
                assertThat(after).doesNotContain("SID=");
            }
        }
    }

    @Test
    void twoDigitYearTwentySixParsesAs2026() {
        assertThat(LegacyCookies.parseExpiry("Mon, 28-Dec-26 23:12:40 GMT"))
                .isEqualTo(Instant.parse("2026-12-28T23:12:40Z"));
        assertThat(LegacyCookies.parseExpiry("Mon, 09-Nov-99 23:12:40 GMT"))
                .isEqualTo(Instant.parse("1999-11-09T23:12:40Z"));
        assertThat(LegacyCookies.parseExpiry("Wednesday, 09-Nov-99 23:12:40 GMT"))
                .isEqualTo(Instant.parse("1999-11-09T23:12:40Z"));
        assertThat(LegacyCookies.parseExpiry("Mon, 09 Nov 2099 23:12:40 GMT"))
                .isEqualTo(Instant.parse("2099-11-09T23:12:40Z"));
    }

    private static List<String> messages(HttpResult result) {
        ArrayList<String> messages = new ArrayList<>();
        for (TraceWarning warning : result.warnings()) {
            messages.add(warning.message());
        }
        return messages;
    }

    private static Map<String, Object> post(String path) {
        LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer keep-me");
        headers.put("X-Trace", "trace-value");
        headers.put("Content-Type", "application/custom");
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("method", "POST");
        spec.put("path", path);
        spec.put("follow_redirects", true);
        spec.put("form", Map.of("a", "b"));
        spec.put("headers", headers);
        return spec;
    }

    private static String redirect(int status, String location) {
        return "HTTP/1.1 " + status + " X\r\nLocation: " + location
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    }

    private static CloseableHttpClient plainClient() {
        return HttpClients.custom().disableRedirectHandling().disableAutomaticRetries().build();
    }

    private static HttpResult call(CloseableHttpClient client, Map<String, Object> spec, String base) {
        return HttpCalls.execute(spec, client, () -> { }, URI.create(base + "/"), null, deadline());
    }

    private static RuleProblem failure(CloseableHttpClient client, Map<String, Object> spec, String base) {
        try {
            call(client, spec, base);
        } catch (RuleProblem problem) {
            return problem;
        }
        throw new AssertionError("expected a RuleProblem");
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    }

    private static byte[] join(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] raw = new byte[size];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, raw, at, part.length);
            at += part.length;
        }
        return raw;
    }

    private static final class Accounts implements AutoCloseable {
        private final SessionManager sessions = new SessionManager();
        private final List<SessionKey> keys = new ArrayList<>();

        static Accounts open() {
            return new Accounts();
        }

        CloseableHttpClient client(Origin origin, String conch, String account) {
            SessionKey key = new SessionKey(origin, conch, account);
            try {
                assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
            keys.add(key);
            return sessions.client(key);
        }

        @Override
        public void close() {
            for (SessionKey key : keys) {
                sessions.unlock(key);
            }
            sessions.close();
        }
    }
}
