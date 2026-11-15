package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.crab.config.CrabConfig;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnknownRouteTest {
    private static final String PASSWORD = "UnknownRouteSecret-c41e8b";

    @TempDir
    java.nio.file.Path temp;

    @AfterEach
    void clearRoot() {
        System.clearProperty(ConsoleUi.ROOT_PROPERTY);
    }

    @Test
    void unknownApiRoutesUseTheNotFoundEnvelopeAndSpaPagesStayHtml() throws Exception {
        System.setProperty(ConsoleUi.ROOT_PROPERTY, "crab-ui-fixture");
        try (TestServer server = TestServer.open(temp, PASSWORD, CrabConfig.defaults().withListen("127.0.0.1", 0))) {
            assertNotFound(server.console("GET", "/console/api/does-not-exist", null, true));
            assertNotFound(server.console("POST", "/console/api/conches/legacy_oa/missing", "{}", true));
            assertNotFound(server.call("GET", "/api/not-a-route", null, null, null, null));
            assertNotFound(server.call("GET", "/api/a/b/c", null, null, null, null));
            assertNotFound(server.call("POST", "/no-such-gateway", null, null, null, "{\"secret\":\"not-logged\"}"));
            HttpResponse<String> jsonAccept = server.http.send(
                    HttpRequest.newBuilder(URI.create(server.base() + "/api/not-a-route"))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertNotFound(new TestServer.Reply(jsonAccept.statusCode(), jsonAccept.body(), jsonAccept.headers().map()));

            TestServer.Reply page = server.console("GET", "/console/conches", null, false);
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.header("Content-Type")).isEqualTo("text/html;charset=UTF-8");
            assertThat(page.body()).contains("<div id=\"app\">");
            TestServer.Reply deep = server.console("GET", "/console/nope", null, false);
            assertThat(deep.status()).isEqualTo(200);
            assertThat(deep.header("Content-Type")).isEqualTo("text/html;charset=UTF-8");
            assertThat(deep.body()).contains("<div id=\"app\">");
            assertNotFound(server.console("POST", "/console/nope", "{}", false));
        }
    }

    private static void assertNotFound(TestServer.Reply reply) {
        assertThat(reply.status()).isEqualTo(404);
        assertThat(reply.json().path("success").asBoolean()).isFalse();
        assertThat(reply.json().path("data").isNull()).isTrue();
        assertThat(reply.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(reply.json().path("error").path("message").asText()).isEqualTo("不存在");
        // 没有执行，没有记录可查：只给请求编号。
        assertThat(reply.json().has("traceId")).isFalse();
        assertThat(reply.json().path("requestId").asText()).isNotBlank();
        assertThat(reply.json().path("requestNote").asText()).isEqualTo("请求编号，不可查追踪");
        assertThat(reply.header("X-Hermit-Trace")).isNull();
        assertThat(reply.body()).doesNotContain("\"title\"");
        assertThat(reply.body()).doesNotContain("javalin");
        assertThat(reply.body()).doesNotContain("timestamp");
    }
}
