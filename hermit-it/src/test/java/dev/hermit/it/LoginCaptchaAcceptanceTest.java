package dev.hermit.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.crab.engine.LoginInputMessages;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 图片验证码和短信验证码。两个模拟站点都在本机，只用临时目录。
 * 开发追踪只记本地规则包，所以走本地导入。验证码和登录令牌不得出现在调用步骤、错误、追踪和进程日志里。
 */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class LoginCaptchaAcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALICE = "alice";
    private static final String BOB = "bobby";
    private static final String ALICE_PASSWORD = "p+ass/img";
    private static final String BOB_PASSWORD = "p+ass/sms";
    private static final String WRONG = "NOPE+9";
    private static final String ALICE_SUBJECT = "morning-note";
    private static final String BOB_SUBJECT = "evening-note";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path temp;

    @Test
    void imageCaptchaThenConsoleSubmit() throws Exception {
        run(MockCaptchaSite.Kind.IMAGE);
    }

    @Test
    void smsCodeThenConsoleSubmit() throws Exception {
        run(MockCaptchaSite.Kind.SMS);
    }

    private void run(MockCaptchaSite.Kind kind) throws Exception {
        boolean image = kind == MockCaptchaSite.Kind.IMAGE;
        String conch = image ? "image_gate" : "sms_gate";
        String alicePassword = image ? ALICE_PASSWORD : BOB_PASSWORD;
        String bobPassword = image ? BOB_PASSWORD : ALICE_PASSWORD;
        try (MockCaptchaSite site = image ? MockCaptchaSite.image() : MockCaptchaSite.sms()) {
            site.allow(ALICE, alicePassword, ALICE_SUBJECT);
            site.allow(BOB, bobPassword, BOB_SUBJECT);
            Path home = temp.resolve(conch);
            Files.createDirectories(home);
            Files.writeString(home.resolve("config.yml"), """
                    server:
                      host: 127.0.0.1
                      port: 0
                    report:
                      enabled: false
                    """);
            Function<String, String> env = name -> "HERMIT_HOME".equals(name) ? home.toString() : null;
            try (CrabServer crab = CrabServer.start(env); LogCapture logs = new LogCapture()) {
                String base = "http://127.0.0.1:" + crab.port();
                List<String> watched = new ArrayList<>();
                importPackage(base, packageYaml(conch, site.baseUrl(), "1.0.0", "第一版测试夹具。", image), false);
                console(base, "POST", "/console/api/conches/" + conch + "/accounts", "{\"name\":\"" + ALICE + "\"}");
                console(base, "POST", "/console/api/conches/" + conch + "/accounts", "{\"name\":\"" + BOB + "\"}");
                console(base, "POST", "/console/api/conches/" + conch + "/accounts/" + ALICE + "/default", "{}");
                putCredentials(base, conch, ALICE, alicePassword);
                putCredentials(base, conch, BOB, bobPassword);
                console(base, "POST", "/console/api/debug", "{\"enabled\":true}");
                String apiKey = console(base, "POST", "/console/api/apikeys", "{\"name\":\"caller\",\"all\":true}")
                        .path("data").path("plaintext").asText();
                assertThat(apiKey).startsWith("hk_");

                HttpResponse<String> paused = call(base, conch, apiKey, ALICE, "hold-alice", "{}");
                long pausedAt = System.nanoTime();
                assertThat(paused.statusCode()).isEqualTo(409);
                JsonNode pausedBody = json(paused);
                assertThat(pausedBody.path("error").path("code").asText()).isEqualTo("LOGIN_INPUT_REQUIRED");
                assertThat(pausedBody.path("error").path("message").asText())
                        .isEqualTo(LoginInputMessages.required(conch));
                assertThat(paused.headers().firstValue("Idempotent-Replayed")).isEmpty();
                assertThat(site.challenges()).hasSize(1);
                MockCaptchaSite.Challenge first = site.challenges().get(0);
                watched.add(paused.body());

                HttpResponse<String> early = consoleRaw(
                        base, "POST", "/console/api/conches/" + conch + "/accounts/" + ALICE + "/login-input/resend", "{}");
                assertThat(early.statusCode()).isEqualTo(409);
                assertThat(json(early).path("error").path("code").asText()).isEqualTo("CONFLICT");
                assertThat(json(early).path("error").path("message").asText()).isEqualTo(LoginInputMessages.TOO_FAST);
                assertThat(site.challenges()).hasSize(1);
                watched.add(early.body());

                JsonNode view = console(
                        base, "GET", "/console/api/conches/" + conch + "/accounts/" + ALICE + "/login-input", null)
                        .path("data");
                assertThat(view.path("kind").asText()).isEqualTo(image ? "image" : "code");
                assertThat(view.path("label").asText()).isEqualTo(image ? "图片验证码" : "短信验证码");
                assertThat(view.path("hint").asText()).isEqualTo(image ? "输入图中字符" : "输入短信里的验证码");
                assertThat(view.path("hasImage").asBoolean()).isEqualTo(image);
                assertThat(view.path("canResend").asBoolean()).isTrue();
                assertThat(view.path("expiresAt").asText()).contains("T");
                if (image) {
                    assertThat(view.path("resendAfter").asInt()).isBetween(1, 2);
                } else {
                    assertThat(view.path("resendAfter").asInt()).isBetween(50, 60);
                    SecretChecks.absent(site.lastSendBody(), first.answer());
                }
                JsonNode aliceRow = account(base, conch, ALICE);
                assertThat(aliceRow.path("loginInputRequired").asBoolean()).isTrue();
                assertThat(aliceRow.path("loginInputExpiresAt").asText()).isEqualTo(view.path("expiresAt").asText());
                assertThat(aliceRow.path("loginPaused").asBoolean()).isFalse();

                HttpResponse<String> imageMiss = consoleRaw(
                        base, "GET", "/console/api/conches/" + conch + "/accounts/" + ALICE + "/login-input/image", null);
                if (image) {
                    assertImage(base, conch, ALICE, first);
                } else {
                    assertThat(imageMiss.statusCode()).isEqualTo(404);
                    assertThat(json(imageMiss).path("error").path("message").asText())
                            .isEqualTo(LoginInputMessages.NO_IMAGE);
                    watched.add(imageMiss.body());
                }

                HttpResponse<String> again = call(base, conch, apiKey, ALICE, "hold-alice-again", "{}");
                assertThat(again.statusCode()).isEqualTo(409);
                assertThat(json(again).path("error").path("code").asText()).isEqualTo("LOGIN_INPUT_REQUIRED");
                assertThat(site.challenges()).hasSize(1);
                watched.add(again.body());

                sleepUntil(pausedAt, image ? 2_100 : 61_000);
                HttpResponse<String> resent = consoleRaw(
                        base, "POST", "/console/api/conches/" + conch + "/accounts/" + ALICE + "/login-input/resend", "{}");
                assertThat(resent.statusCode()).as(safe(resent.body(), List.of(apiKey))).isEqualTo(200);
                assertThat(json(resent).path("data").path("canResend").asBoolean()).isTrue();
                assertThat(site.challenges()).hasSize(2);
                MockCaptchaSite.Challenge refreshed = site.challenges().get(1);
                assertThat(refreshed.cookie()).isNotEqualTo(first.cookie());
                different(refreshed.answer(), first.answer(), "resend reused the previous answer");
                if (image) {
                    assertThat(refreshed.image()).isNotEqualTo(first.image());
                    assertImage(base, conch, ALICE, refreshed);
                } else {
                    SecretChecks.absent(site.lastSendBody(), refreshed.answer());
                }
                watched.add(resent.body());
                watchTrace(crab, resent, watched);

                HttpResponse<String> wrong = consoleRaw(
                        base,
                        "POST",
                        "/console/api/conches/" + conch + "/accounts/" + ALICE + "/login-input",
                        "{\"value\":\"" + WRONG + "\"}");
                assertThat(wrong.statusCode()).isEqualTo(422);
                JsonNode wrongBody = json(wrong);
                assertThat(wrongBody.path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
                assertThat(wrongBody.path("error").path("message").asText()).contains("期望 200").contains("实际 401");
                assertThat(wrongBody.path("requestNote").asText()).isEqualTo("请求编号，不可查追踪");
                assertThat(site.logins()).hasSize(1);
                MockCaptchaSite.LoginHit rejected = site.logins().get(0);
                assertThat(rejected.accepted()).isFalse();
                assertThat(rejected.token()).isNull();
                same(rejected.cookie(), refreshed.cookie(), "wrong login did not send the session cookie");
                same(rejected.pathCode(), WRONG, "wrong login did not submit the typed value");
                same(rejected.formCode(), WRONG, "wrong login form did not carry the typed value");
                HttpResponse<String> cleared = consoleRaw(
                        base, "GET", "/console/api/conches/" + conch + "/accounts/" + ALICE + "/login-input", null);
                assertThat(cleared.statusCode()).isEqualTo(404);
                assertThat(json(cleared).path("error").path("message").asText()).isEqualTo(LoginInputMessages.NONE);
                assertThat(account(base, conch, ALICE).path("loginInputRequired").asBoolean()).isFalse();
                assertThat(account(base, conch, ALICE).path("loginPaused").asBoolean()).isFalse();
                watched.add(wrong.body());
                watched.add(cleared.body());
                watchTrace(crab, wrong, watched);

                HttpResponse<String> restarted = call(base, conch, apiKey, ALICE, "hold-alice-fresh", "{}");
                assertThat(restarted.statusCode()).isEqualTo(409);
                assertThat(json(restarted).path("error").path("code").asText()).isEqualTo("LOGIN_INPUT_REQUIRED");
                assertThat(json(restarted).path("error").path("message").asText())
                        .doesNotContain("暂停");
                assertThat(site.challenges()).hasSize(3);
                MockCaptchaSite.Challenge current = site.challenges().get(2);
                different(current.answer(), refreshed.answer(), "a fresh login reused the discarded answer");
                watched.add(restarted.body());
                if (image) {
                    assertImage(base, conch, ALICE, current);
                }

                HttpResponse<String> submitted = consoleRaw(
                        base,
                        "POST",
                        "/console/api/conches/" + conch + "/accounts/" + ALICE + "/login-input",
                        "{\"value\":\"" + current.answer() + "\"}");
                assertThat(submitted.statusCode()).as(safe(submitted.body(), List.of(apiKey))).isEqualTo(200);
                assertThat(json(submitted).path("data").path("ok").asBoolean()).isTrue();
                assertThat(site.logins()).hasSize(2);
                MockCaptchaSite.LoginHit accepted = site.logins().get(1);
                assertThat(accepted.accepted()).isTrue();
                assertThat(accepted.username()).isEqualTo(ALICE);
                same(accepted.cookie(), current.cookie(), "login did not send the captcha session cookie");
                same(accepted.pathCode(), current.answer(), "login path did not carry the issued answer");
                same(accepted.formCode(), current.answer(), "login form did not carry the issued answer");
                assertThat(accepted.token()).isNotBlank();
                assertThat(account(base, conch, ALICE).path("loginInputRequired").asBoolean()).isFalse();
                watched.add(submitted.body());
                watchTrace(crab, submitted, watched);

                HttpResponse<String> done = call(base, conch, apiKey, ALICE, "hold-alice", "{}");
                assertThat(done.statusCode()).as(safe(done.body(), List.of(apiKey))).isEqualTo(200);
                assertThat(done.headers().firstValue("Idempotent-Replayed")).isEmpty();
                assertThat(json(done).path("data").path("subject").asText()).isEqualTo(ALICE_SUBJECT);
                assertThat(site.challenges()).hasSize(3);
                assertThat(site.inboxes()).hasSize(1);
                MockCaptchaSite.InboxHit inbox = site.inboxes().get(0);
                assertThat(inbox.status()).isEqualTo(200);
                assertThat(inbox.subject()).isEqualTo(ALICE_SUBJECT);
                same(inbox.cookieToken(), accepted.token(), "inbox cookie was not the login token");
                same(inbox.pathToken(), accepted.token(), "inbox path was not the login token");
                watched.add(done.body());
                String doneTrace = json(done).path("traceId").asText();
                HttpResponse<String> detail = consoleRaw(base, "GET", "/console/api/calls/" + doneTrace, null);
                assertThat(detail.statusCode()).isEqualTo(200);
                String steps = json(detail).path("data").path("steps").toString();
                assertThat(steps).contains("***");
                watched.add(detail.body());

                HttpResponse<String> bobPaused = call(base, conch, apiKey, BOB, "hold-bob", "{}");
                assertThat(bobPaused.statusCode()).isEqualTo(409);
                assertThat(site.challenges()).hasSize(4);
                String staleAnswer = site.issuedAnswer();
                int loginsBeforeUpdate = site.logins().size();
                int challengesBeforeUpdate = site.challenges().size();
                watched.add(bobPaused.body());

                importPackage(base, packageYaml(conch, site.baseUrl(), "1.0.1", "第二版测试夹具。", image), true);
                HttpResponse<String> stale = consoleRaw(
                        base,
                        "POST",
                        "/console/api/conches/" + conch + "/accounts/" + BOB + "/login-input",
                        "{\"value\":\"" + staleAnswer + "\"}");
                assertThat(stale.statusCode()).isEqualTo(409);
                assertThat(json(stale).path("error").path("code").asText()).isEqualTo("CONFLICT");
                assertThat(json(stale).path("error").path("message").asText()).isEqualTo(LoginInputMessages.UPDATED);
                assertThat(site.logins()).hasSize(loginsBeforeUpdate);
                assertThat(site.challenges()).hasSize(challengesBeforeUpdate);
                HttpResponse<String> dropped = consoleRaw(
                        base, "GET", "/console/api/conches/" + conch + "/accounts/" + BOB + "/login-input", null);
                assertThat(dropped.statusCode()).isEqualTo(404);
                assertThat(json(dropped).path("error").path("message").asText()).isEqualTo(LoginInputMessages.NONE);
                watched.add(stale.body());
                watched.add(dropped.body());
                watchTrace(crab, stale, watched);

                HttpResponse<String> aliceStill = call(base, conch, apiKey, ALICE, "hold-alice-after", "{}");
                assertThat(aliceStill.statusCode()).as(safe(aliceStill.body(), List.of(apiKey))).isEqualTo(200);
                assertThat(json(aliceStill).path("data").path("subject").asText()).isEqualTo(ALICE_SUBJECT);
                assertThat(site.challenges()).hasSize(challengesBeforeUpdate);
                String aliceTrace = json(aliceStill).path("traceId").asText();
                HttpResponse<String> aliceDetail = consoleRaw(base, "GET", "/console/api/calls/" + aliceTrace, null);
                assertThat(json(aliceDetail).path("data").path("conchVersion").asText()).isEqualTo("1.0.1");
                watched.add(aliceStill.body());
                watched.add(aliceDetail.body());

                HttpResponse<String> bobAgain = call(base, conch, apiKey, BOB, "hold-bob-next", "{}");
                assertThat(bobAgain.statusCode()).isEqualTo(409);
                assertThat(json(bobAgain).path("error").path("code").asText()).isEqualTo("LOGIN_INPUT_REQUIRED");
                assertThat(site.challenges()).hasSize(challengesBeforeUpdate + 1);
                different(site.issuedAnswer(), staleAnswer, "the updated package reused the discarded answer");
                watched.add(bobAgain.body());

                for (HttpResponse<String> response : List.of(paused, again, restarted, done, bobPaused, aliceStill, bobAgain)) {
                    watchCall(base, response, watched);
                }
                List<String> secrets = secrets(site, apiKey, alicePassword, bobPassword);
                String combined = String.join("\n", watched) + "\n" + logs.text();
                assertMasked(combined, secrets);
            }
        }
    }

    private void assertImage(String base, String conch, String account, MockCaptchaSite.Challenge challenge)
            throws Exception {
        HttpResponse<byte[]> first = image(base, conch, account, "1");
        HttpResponse<byte[]> second = image(base, conch, account, "2");
        assertThat(first.headers().firstValue("Content-Type").orElse("")).startsWith("image/png");
        assertThat(first.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
        assertThat(first.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(first.body()).startsWith(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        assertThat(first.body()).isEqualTo(challenge.image());
        if (!new String(first.body(), StandardCharsets.ISO_8859_1).contains(challenge.answer())) {
            throw new AssertionError("png did not embed the issued answer");
        }
    }

    private HttpResponse<byte[]> image(String base, String conch, String account, String cacheBust) throws Exception {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(base
                        + "/console/api/conches/" + conch + "/accounts/" + account
                        + "/login-input/image?t=" + cacheBust))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Hermit-Console", "1")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private void watchCall(String base, HttpResponse<String> response, List<String> watched) throws Exception {
        watched.add(response.body());
        String traceId = json(response).path("traceId").asText();
        if (traceId.isBlank()) {
            return;
        }
        HttpResponse<String> detail = consoleRaw(base, "GET", "/console/api/calls/" + traceId, null);
        if (detail.statusCode() == 200) {
            watched.add(detail.body());
        }
    }

    private static void watchTrace(CrabServer crab, HttpResponse<String> response, List<String> watched) throws Exception {
        JsonNode body = json(response);
        String id = body.path("traceId").asText();
        if (id.isBlank()) {
            id = body.path("requestId").asText();
        }
        if (id.isBlank()) {
            return;
        }
        String trace = crab.runtime().devTraces().find(id).orElseThrow().body();
        watched.add(trace);
    }

    private JsonNode account(String base, String conch, String name) throws Exception {
        JsonNode listed = console(base, "GET", "/console/api/conches/" + conch + "/accounts", null);
        for (JsonNode row : listed.path("data").path("accounts")) {
            if (name.equals(row.path("name").asText())) {
                return row;
            }
        }
        throw new AssertionError("account was not listed");
    }

    private void importPackage(String base, String yaml, boolean replace) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        base + "/console/api/local-conches" + (replace ? "?confirm=true" : "")))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1")
                .header("Content-Type", "text/yaml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(yaml))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        if (replace) {
            assertThat(json(response).path("data").path("replaced").asBoolean()).isTrue();
            assertThat(json(response).path("data").path("version").asText()).isEqualTo("1.0.1");
        }
    }

    private void putCredentials(String base, String conch, String account, String password) throws Exception {
        console(base, "PUT", "/console/api/conches/" + conch + "/accounts/" + account + "/credentials",
                "{\"username\":\"" + account + "\",\"password\":\"" + password + "\"}");
    }

    private HttpResponse<String> call(
            String base,
            String conch,
            String apiKey,
            String account,
            String idempotencyKey,
            String body
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/" + conch + "/inbox"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("X-Hermit-Account", account)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode console(String base, String method, String path, String body) throws Exception {
        HttpResponse<String> response = consoleRaw(base, method, path, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private HttpResponse<String> consoleRaw(String base, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void sleepUntil(long started, long delayMillis) throws InterruptedException {
        long remain = TimeUnit.NANOSECONDS.toMillis(started + TimeUnit.MILLISECONDS.toNanos(delayMillis) - System.nanoTime());
        if (remain > 0) {
            Thread.sleep(remain);
        }
    }

    private static List<String> secrets(MockCaptchaSite site, String apiKey, String alicePassword, String bobPassword) {
        ArrayList<String> secrets = new ArrayList<>();
        secrets.add(apiKey);
        secrets.add(alicePassword);
        secrets.add(bobPassword);
        secrets.add(WRONG);
        for (MockCaptchaSite.Challenge challenge : site.challenges()) {
            secrets.add(challenge.answer());
            secrets.add(challenge.cookie());
        }
        for (MockCaptchaSite.LoginHit hit : site.logins()) {
            secrets.add(hit.token());
            secrets.add(hit.pathCode());
            secrets.add(hit.formCode());
        }
        for (MockCaptchaSite.InboxHit hit : site.inboxes()) {
            secrets.add(hit.cookieToken());
            secrets.add(hit.pathToken());
        }
        return secrets;
    }

    private static void assertMasked(String text, List<String> secrets) {
        assertThat(text).contains("***");
        for (String secret : secrets) {
            if (secret == null || secret.length() < 4) {
                continue;
            }
            SecretChecks.absent(text, secret);
            SecretChecks.absent(text, java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8));
        }
    }

    private static void same(String actual, String expected, String message) {
        if (expected == null || !expected.equals(actual)) {
            throw new AssertionError(message);
        }
    }

    private static void different(String actual, String expected, String message) {
        if (expected != null && expected.equals(actual)) {
            throw new AssertionError(message);
        }
    }

    private static String safe(String body, List<String> secrets) {
        String text = body == null ? "" : body;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                text = text.replace(secret, "[redacted]");
            }
        }
        return text;
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static String packageYaml(
            String conch,
            String baseUrl,
            String version,
            String description,
            boolean image
    ) {
        String ask = image
                ? """
                    - request: { method: GET, path: /captcha.png }
                    - ask:
                        into: captcha
                        kind: image
                        label: 图片验证码
                        hint: 输入图中字符
                        timeout_s: 300
                    - request:
                        method: POST
                        path: "/login/{{captcha}}"
                        form:
                          username: "{{credential.username}}"
                          password: "{{credential.password}}"
                          code: "{{captcha}}"
                    - assert: { status: 200 }
                    - extract:
                        token: { jsonpath: $.token }
                """
                : """
                    - request:
                        method: POST
                        path: /sms/send
                        form:
                          phone: "{{credential.username}}"
                    - ask:
                        into: captcha
                        kind: code
                        label: 短信验证码
                        hint: 输入短信里的验证码
                        timeout_s: 300
                    - request:
                        method: POST
                        path: "/login/{{captcha}}"
                        form:
                          username: "{{credential.username}}"
                          password: "{{credential.password}}"
                          code: "{{captcha}}"
                    - assert: { status: 200 }
                    - extract:
                        token: { jsonpath: $.token }
                """;
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.4.0
                base_url: %s
                title: 验证码验收
                description: %s
                credentials:
                  username: { type: text, required: true, label: 用户名 }
                  password: { type: secret, required: true, label: 密码 }
                auth:
                  steps:
                %s
                actions:
                  inbox:
                    title: 收件箱
                    description: 登录后读取主题。
                    method: POST
                    idempotent: true
                    output:
                      subject: { type: string, description: 主题 }
                    examples:
                      - input: {}
                        output: { subject: 示例 }
                    notes: 测试夹具。
                    related: []
                    steps:
                      - request: { method: GET, path: "/inbox/{{login.token}}" }
                      - extract:
                          subject: { jsonpath: $.subject }
                """.formatted(conch, version, baseUrl, description, ask.stripIndent().indent(4).stripTrailing());
    }

    private static final class LogCapture extends Handler implements AutoCloseable {
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final Logger logger = Logger.getLogger("dev.hermit");
        private final Level previous;

        private LogCapture() {
            previous = logger.getLevel();
            setLevel(Level.ALL);
            logger.setLevel(Level.ALL);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || record.getMessage() == null) {
                return;
            }
            String text = record.getMessage();
            Object[] parameters = record.getParameters();
            if (parameters != null && parameters.length > 0) {
                try {
                    text = MessageFormat.format(text, parameters);
                } catch (RuntimeException ignored) {
                    // 保留原来的模板。
                }
            }
            if (record.getThrown() != null) {
                text = text + " " + record.getThrown();
            }
            lines.add(text);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(previous);
        }

        private String text() {
            return String.join("\n", lines);
        }
    }
}
