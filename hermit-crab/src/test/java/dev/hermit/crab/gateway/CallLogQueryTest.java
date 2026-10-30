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
        }
    }

    private static void append(TestServer server, String traceId, String conch, String action) {
        server.runtime.callLogs().append(new CallLogStore.NewCall(
                traceId, Origin.OFFICIAL, conch, action, "alice", "1.0.0", true, null, null, 4, null), List.of());
    }
}
