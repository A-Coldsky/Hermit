package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.testing.FakeReef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrabWiringTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    java.nio.file.Path temp;

    @Test
    void missingPublicKeyFailsBeforeSync() {
        CrabConfig config = config("http://127.0.0.1:9/", null, true);
        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("missing")), config, name -> null)) {
            assertThatThrownBy(runtime::loadInstalledShells)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("reef.url is set but reef.public_key is missing");
        }
    }

    @Test
    void syncAuditsFieldNamesAndCallsAreReported() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), true);
            CrabPaths paths = new CrabPaths(temp.resolve("wired"));
            try (CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null)) {
                runtime.loadInstalledShells();
                reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
                SyncReport first = runtime.syncOnce(null).orElseThrow();
                assertThat(first.shells()).anyMatch(outcome -> outcome.kind() == OutcomeKind.INSTALLED);

                var doc = runtime.registry().find("legacy_oa").orElseThrow();
                runtime.admin().createAccount("legacy_oa", "alice", Actors.CLI);
                runtime.admin().writeCredentials(doc, "alice", Map.of("username", "alice", "password", "pw"), Actors.CLI);
                CreatedApiKey key = runtime.admin().createApiKey(
                        "caller", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
                var outcome = runtime.invoker().invoke(
                        runtime.apiKeys().authenticate(key.plaintext()).orElseThrow(),
                        "legacy_oa",
                        "list_todo",
                        Map.of(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        "trace-wire");
                assertThat(outcome.status()).as(new String(outcome.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);
                assertThat(runtime.reportSender().preview().batch().counts()).isNotEmpty();

                reef.publish(ShellTexts.withoutPassword("legacy_oa", "1.1.0"));
                runtime.syncOnce(Actors.CONSOLE);
                String detail = auditDetail(runtimeDataSource(runtime));
                assertThat(detail).contains("otp").contains("password");
                assertThat(detail).doesNotContain("pw");
            }
        }
    }

    @Test
    void consoleSyncAndDisabledReports() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            CrabConfig config = config(reef.base().toString(), Ed25519Keys.writePublicPem(reef.keys().getPublic()), false)
                    .withListen("127.0.0.1", 0);
            CrabPaths paths = new CrabPaths(temp.resolve("console"));
            CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null);
            reef.publish(ShellTexts.standard("legacy_oa", "1.0.0"));
            try (CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpResponse<String> preview = send(http, server.port(), "GET", "/console/api/reports/preview", null);
                assertThat(preview.statusCode()).isEqualTo(409);
                assertThat(JSON.readTree(preview.body()).path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");

                HttpResponse<String> sync = send(http, server.port(), "POST", "/console/api/sync", "{}");
                assertThat(sync.statusCode()).as(sync.body()).isEqualTo(200);
                JsonNode data = JSON.readTree(sync.body()).path("data");
                assertThat(data.path("notModified").isBoolean()).isTrue();
                assertThat(data.path("latestCrab").path("latestVersion").asText()).isEqualTo(reef.latestVersion());
                HttpResponse<String> shells = send(http, server.port(), "GET", "/console/api/shells", null);
                assertThat(shells.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(shells.body()).path("data").path("shells").toString()).contains("legacy_oa");

                HttpResponse<String> status = send(http, server.port(), "GET", "/console/api/sync", null);
                assertThat(status.statusCode()).isEqualTo(200);
                JsonNode last = JSON.readTree(status.body()).path("data");
                assertThat(last.path("report").path("notModified").asBoolean()).isEqualTo(data.path("notModified").asBoolean());
                assertThat(last.path("latestCrab").path("latestVersion").asText()).isEqualTo(reef.latestVersion());
            }
        }
    }

    private static HttpResponse<String> send(HttpClient http, int port, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static CrabConfig config(String url, String pem, boolean reports) {
        return new CrabConfig(url, null, pem, "127.0.0.1", 0, 30, reports, 60, List.of(), Map.of(), 60, 10, 3);
    }

    private static DataSource runtimeDataSource(CrabRuntime runtime) {
        return runtime.dataSource();
    }

    private static String auditDetail(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT detail FROM config_audit WHERE kind = 'CREDENTIAL_DECLARATION'")) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                String detail = rows.getString(1);
                assertThat(ApiJson.tree(detail).path("addedRequired").toString()).contains("otp");
                return detail;
            }
        }
    }
}
