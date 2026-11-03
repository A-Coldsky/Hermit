package dev.hermit.crab.gateway;

import java.nio.file.Path;
import java.util.List;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.Origin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CallLogQueryTest {
    private static final String PASSWORD = "HermitCallQuerySecret-c91e";

    @TempDir
    Path temp;

    @Test
    void actionFilterAcceptsQualifiedNamesAndRejectsADifferentConch() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            append(server, "trace-todo", "legacy_oa", "list_todo");
            append(server, "trace-note", "legacy_oa", "save_note");
            append(server, "trace-other", "other_oa", "list_todo");

            TestServer.Reply qualified = server.console(
                    "GET", "/console/api/calls?action=legacy_oa.list_todo", null, true);
            assertThat(qualified.status()).isEqualTo(200);
            assertThat(qualified.body()).contains("trace-todo").doesNotContain("trace-note").doesNotContain("trace-other");

            TestServer.Reply same = server.console(
                    "GET", "/console/api/calls?conch=legacy_oa&action=legacy_oa.list_todo", null, true);
            assertThat(same.status()).isEqualTo(200);
            assertThat(same.body()).contains("trace-todo").doesNotContain("trace-other");

            TestServer.Reply bare = server.console(
                    "GET", "/console/api/calls?conch=legacy_oa&action=list_todo", null, true);
            assertThat(bare.status()).isEqualTo(200);
            assertThat(bare.body()).contains("trace-todo").doesNotContain("trace-note");

            String secret = "sid-secret-cookie-91";
            TestServer.Reply conflict = server.console(
                    "GET", "/console/api/calls?conch=legacy_oa&action=" + secret + ".list_todo", null, true);
            assertThat(conflict.status()).isEqualTo(400);
            assertThat(conflict.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(conflict.json().path("error").path("message").asText()).contains("action").contains("conch");
            assertThat(conflict.body()).doesNotContain(secret).doesNotContain("trace-todo");

            for (int i = 0; i < 110; i++) {
                append(server, "trace-page-" + i, "legacy_oa", "list_todo", "HTTP");
            }
            append(server, "trace-mcp", "legacy_oa", "save_note", "MCP");
            TestServer.Reply defaults = server.console("GET", "/console/api/calls?conch=legacy_oa", null, true);
            assertThat(defaults.status()).isEqualTo(200);
            assertThat(defaults.json().path("data").path("calls")).hasSize(CallLogStore.DEFAULT_LIST_LIMIT);
            assertThat(defaults.body()).contains("\"channel\":\"HTTP\"").contains("\"channel\":\"MCP\"");

            TestServer.Reply mcp = server.console(
                    "GET", "/console/api/calls?channel=MCP", null, true);
            assertThat(mcp.status()).isEqualTo(200);
            assertThat(mcp.json().path("data").path("calls")).hasSize(1);
            assertThat(mcp.json().path("data").path("calls").get(0).path("traceId").asText()).isEqualTo("trace-mcp");
            assertThat(mcp.json().path("data").path("calls").get(0).path("channel").asText()).isEqualTo("MCP");
            assertThat(mcp.json().path("data").path("calls").get(0).path("source").asText()).isEqualTo("REEF");

            TestServer.Reply detail = server.console("GET", "/console/api/calls/trace-mcp", null, true);
            assertThat(detail.status()).isEqualTo(200);
            assertThat(detail.json().path("data").path("channel").asText()).isEqualTo("MCP");

            String secretChannel = "channel-secret-value";
            TestServer.Reply badChannel = server.console(
                    "GET", "/console/api/calls?channel=" + secretChannel, null, true);
            assertThat(badChannel.status()).isEqualTo(400);
            assertThat(badChannel.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(badChannel.json().path("error").path("message").asText()).isEqualTo("channel is invalid");
            assertThat(badChannel.body()).doesNotContain(secretChannel);

            TestServer.Reply consoleChannel = server.console(
                    "GET", "/console/api/calls?channel=CONSOLE", null, true);
            assertThat(consoleChannel.status()).isEqualTo(200);
            assertThat(consoleChannel.json().path("data").path("calls")).isEmpty();

            TestServer.Reply tooMany = server.console("GET", "/console/api/calls?limit=501", null, true);
            assertThat(tooMany.status()).isEqualTo(400);
            assertThat(tooMany.json().path("error").path("message").asText()).isEqualTo("limit is out of range");
        }
    }

    private static void append(TestServer server, String traceId, String conch, String action) {
        append(server, traceId, conch, action, "HTTP");
    }

    private static void append(TestServer server, String traceId, String conch, String action, String channel) {
        server.runtime.callLogs().append(new CallLogStore.NewCall(
                traceId, Origin.OFFICIAL, conch, action, "alice", "1.0.0", true, null, null, 4, null,
                dev.hermit.crab.store.CallChannel.valueOf(channel)), List.of());
    }
}
