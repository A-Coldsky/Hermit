package dev.hermit.crab.gateway;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.ConsoleSessions;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.ConsolePassword;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ConsolePasswordHttpTest {
    private static final String PASSWORD = "console-http-secret";
    private static final String NEXT = "console-http-secret-2";
    private static final String ACCOUNT_PASSWORD = "HermitConsoleSecret-a91c0e77b2";

    @TempDir
    Path temp;

    @Test
    void unsetPasswordLeavesTheConsoleAsItIsToday() throws Exception {
        try (TestServer server = TestServer.open(temp.resolve("open"), ACCOUNT_PASSWORD, listen())) {
            TestServer.Reply session = server.console("GET", "/console/api/session", null, true);
            assertThat(session.status()).isEqualTo(200);
            assertThat(session.json().path("data").path("required").asBoolean()).isFalse();
            assertThat(session.json().path("data").path("authenticated").asBoolean()).isTrue();
            assertThat(server.console("GET", "/console/api/debug", null, true).status()).isEqualTo(200);

            TestServer.Reply login = server.console(
                    "POST", "/console/api/session", "{\"password\":\"unused-pass\"}", true);
            assertThat(login.status()).isEqualTo(409);
            assertThat(login.json().path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(login.json().path("error").path("message").asText()).isEqualTo(ConsolePassword.UNSET_MESSAGE);

            TestServer.Reply page = server.console("GET", "/console/", null, false);
            assertThat(page.status()).isNotEqualTo(401);
            assertThat(page.body()).doesNotContain(ConsoleSessions.LOGIN_REQUIRED);
        }
    }

    @Test
    void passwordGatesConsoleApiAndLeavesMcpOnTheApiKey() throws Exception {
        try (TestServer server = TestServer.open(temp.resolve("gated"), ACCOUNT_PASSWORD, listen())) {
            server.runtime.consolePassword().set(PASSWORD);
            TestServer.Reply denied = server.console("GET", "/console/api/debug", null, true);
            assertThat(denied.status()).isEqualTo(401);
            assertThat(denied.json().path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
            assertThat(denied.json().path("error").path("message").asText()).isEqualTo(ConsoleSessions.LOGIN_REQUIRED);

            TestServer.Reply session = server.console("GET", "/console/api/session", null, true);
            assertThat(session.json().path("data").path("required").asBoolean()).isTrue();
            assertThat(session.json().path("data").path("authenticated").asBoolean()).isFalse();

            TestServer.Reply wrong = server.console(
                    "POST", "/console/api/session", "{\"password\":\"wrong-password\"}", true);
            assertThat(wrong.status()).isEqualTo(401);
            assertThat(wrong.json().path("error").path("message").asText()).isEqualTo(ConsolePassword.FAILURE_MESSAGE);
            assertThat(wrong.header("Set-Cookie")).isNull();
            SecretChecks.absent(wrong.body(), PASSWORD);

            TestServer.Reply login = server.console(
                    "POST", "/console/api/session", "{\"password\":\"" + PASSWORD + "\"}", true);
            assertThat(login.status()).isEqualTo(200);
            assertThat(login.json().path("data").path("ok").asBoolean()).isTrue();
            String setCookie = login.header("Set-Cookie");
            assertThat(setCookie).contains("hermit_console=", "HttpOnly", "SameSite=Strict", "Path=/console", "Max-Age=43200");
            assertThat(setCookie).doesNotContain("Secure");
            SecretChecks.absent(login.body(), PASSWORD);
            SecretChecks.absent(setCookie, PASSWORD);
            String token = setCookie.substring("hermit_console=".length(), setCookie.indexOf(';'));

            TestServer.Reply debug = console(server, "GET", "/console/api/debug", null, token);
            assertThat(debug.status()).isEqualTo(200);
            assertThat(debug.json().path("data").path("enabled").asBoolean()).isFalse();
            TestServer.Reply anonymous = server.console("GET", "/console/api/session", null, true);
            assertThat(anonymous.json().path("data").path("authenticated").asBoolean()).isFalse();
            TestServer.Reply authed = console(server, "GET", "/console/api/session", null, token);
            assertThat(authed.json().path("data").path("authenticated").asBoolean()).isTrue();

            TestServer.Reply page = server.console("GET", "/console/", null, false);
            assertThat(page.status()).isNotEqualTo(401);

            HttpResponse<String> mcp = server.http.send(HttpRequest.newBuilder(URI.create(server.base() + "/mcp"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Cookie", "hermit_console=" + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(mcp.statusCode()).isEqualTo(401);
            assertThat(mcp.body()).contains("AUTH_MISSING");
            assertThat(mcp.body()).doesNotContain(ConsolePassword.FAILURE_MESSAGE)
                    .doesNotContain(ConsoleSessions.LOGIN_REQUIRED);

            HttpResponse<String> api = server.http.send(HttpRequest.newBuilder(
                            URI.create(server.base() + "/api/legacy_oa/list_todo"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Cookie", "hermit_console=" + token)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(api.statusCode()).isEqualTo(401);
            assertThat(api.body()).contains("invalid api key");
            assertThat(api.body()).doesNotContain(ConsoleSessions.LOGIN_REQUIRED);

            TestServer.Reply keyed = server.call(
                    "GET", "/api/legacy_oa/list_todo", server.apiKey.plaintext(), null, null, null);
            assertThat(keyed.status()).isNotEqualTo(401);
            assertThat(keyed.body()).doesNotContain(ConsolePassword.FAILURE_MESSAGE);

            TestServer.Reply changed = console(server, "POST", "/console/api/session/password",
                    "{\"current\":\"" + PASSWORD + "\",\"next\":\"" + NEXT + "\"}", token);
            assertThat(changed.status()).isEqualTo(200);
            SecretChecks.absent(changed.body(), PASSWORD);
            SecretChecks.absent(changed.body(), NEXT);
            TestServer.Reply expired = console(server, "GET", "/console/api/debug", null, token);
            assertThat(expired.status()).isEqualTo(401);
            assertThat(expired.json().path("error").path("message").asText()).isEqualTo(ConsoleSessions.LOGIN_REQUIRED);

            TestServer.Reply again = server.console(
                    "POST", "/console/api/session", "{\"password\":\"" + NEXT + "\"}", true);
            assertThat(again.status()).isEqualTo(200);
            assertThat(server.console(
                    "POST", "/console/api/session", "{\"password\":\"" + PASSWORD + "\"}", true).status())
                    .isEqualTo(401);

            AuditLog.Page audits = server.runtime.configAudit(null, null, 1, 50);
            String auditJson = ApiJson.write(audits);
            SecretChecks.absent(auditJson, PASSWORD);
            SecretChecks.absent(auditJson, NEXT);
            assertThat(auditJson).doesNotContain("\"detail\"");
            assertThat(audits.entries().stream().filter(entry -> "CONSOLE_LOGIN".equals(entry.action())))
                    .isNotEmpty()
                    .allSatisfy(entry -> {
                        assertThat(entry.fields()).isEmpty();
                        assertThat(entry.conch()).isNull();
                        assertThat(entry.actor()).isEqualTo("console");
                    });

            byte[] database = Files.readAllBytes(Path.of(server.paths.databaseFile() + ".mv.db"));
            SecretChecks.absent(new String(database, StandardCharsets.ISO_8859_1), PASSWORD);
            SecretChecks.absent(new String(database, StandardCharsets.ISO_8859_1), NEXT);
        }
    }

    @Test
    void repeatedFailuresStayOnTheSameMessage() throws Exception {
        try (TestServer server = TestServer.open(temp.resolve("locked"), ACCOUNT_PASSWORD, listen())) {
            server.runtime.consolePassword().set(PASSWORD);
            for (int i = 0; i < ConsolePassword.MAX_FAILURES + 1; i++) {
                TestServer.Reply failed = server.console(
                        "POST", "/console/api/session", "{\"password\":\"wrong-password\"}", true);
                assertThat(failed.status()).isEqualTo(401);
                assertThat(failed.json().path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
                assertThat(failed.json().path("error").path("message").asText())
                        .isEqualTo(ConsolePassword.FAILURE_MESSAGE);
            }
            TestServer.Reply stillLocked = server.console(
                    "POST", "/console/api/session", "{\"password\":\"" + PASSWORD + "\"}", true);
            assertThat(stillLocked.status()).isEqualTo(401);
            assertThat(stillLocked.json().path("error").path("message").asText())
                    .isEqualTo(ConsolePassword.FAILURE_MESSAGE);
        }
    }

    private static TestServer.Reply console(TestServer server, String method, String path, String body, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.base() + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1")
                .header("Cookie", "hermit_console=" + token);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        HttpResponse<String> response = server.http.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new TestServer.Reply(response.statusCode(), response.body(), response.headers().map());
    }

    private static CrabConfig listen() {
        return CrabConfig.defaults().withListen("127.0.0.1", 0);
    }
}
