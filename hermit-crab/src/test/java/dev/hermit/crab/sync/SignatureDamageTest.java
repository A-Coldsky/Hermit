package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.common.pkg.PackageReason;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.testing.FakeReef;

/**
 * A subscribed package whose signature no longer verifies is not an installed catalog row,
 * and its detail is readable so the console can ask for a manual repair. Sync still does not
 * download over it.
 */
class SignatureDamageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void damagedSignatureIsNotInstalledUntilManualRepair() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            reef.publish(ConchTexts.standard("other_sys", "1.0.0"));
            reef.publish(ConchTexts.standard("not_yet", "1.0.0"));
            CrabConfig config = config(reef).withListen("127.0.0.1", 0);
            try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("sig")), config, name -> null);
                 CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newHttpClient();
                assertThat(runtime.subscribe("legacy_oa", Actors.CONSOLE).kind()).isEqualTo(OutcomeKind.INSTALLED);
                assertThat(runtime.subscribe("other_sys", Actors.CONSOLE).kind()).isEqualTo(OutcomeKind.INSTALLED);
                CreatedApiKey key = runtime.admin().createApiKey(
                        "agent", ApiKeyScope.explicit(true, Set.of(), Set.of(), false), Actors.CLI);

                JsonNode healthy = catalogEntry(http, server.port(), "legacy_oa");
                assertThat(healthy.path("installed").asBoolean()).isTrue();
                assertThat(healthy.path("repairRequired").asBoolean()).isFalse();
                assertThat(healthy.path("repairReason").isNull()).isTrue();
                assertThat(healthy.path("message").isNull()).isTrue();
                String session = mcpSession(http, server.port(), key.plaintext());
                assertThat(toolNames(http, server.port(), key.plaintext(), session)).contains("legacy_oa__list_todo");

                corruptSignature(runtime.paths().conches().resolve("legacy_oa/1.0.0/package.zip"));
                runtime.registry().remove("legacy_oa");
                runtime.loadInstalledConches();
                assertThat(runtime.registry().find("legacy_oa")).isEmpty();
                assertThat(runtime.registry().find("other_sys")).isPresent();

                ConchCatalog.Entry beforeSync = entry(runtime.catalog(), "legacy_oa");
                assertThat(beforeSync.installed()).isFalse();
                assertThat(beforeSync.repairRequired()).isTrue();
                assertThat(beforeSync.repairReason()).isEqualTo(PackageReason.BAD_SIGNATURE.name());
                assertThat(beforeSync.message()).isEqualTo(SignatureDamage.MESSAGE);
                assertThat(beforeSync.updateAvailable()).isFalse();
                assertThat(beforeSync.subscribed()).isTrue();

                reef.publish(ConchTexts.standard("legacy_oa", "1.0.1"));
                int downloads = reef.downloads();
                runtime.conchSync().runScheduled();
                SyncReport scheduled = runtime.lastSync().orElseThrow();
                assertThat(outcome(scheduled, "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
                assertThat(outcome(scheduled, "legacy_oa").reason()).isEqualTo(PackageReason.BAD_SIGNATURE.name());
                assertThat(reef.downloads()).isEqualTo(downloads);

                SyncReport manual = runtime.syncOnce(Actors.CONSOLE).orElseThrow();
                assertThat(outcome(manual, "legacy_oa").kind()).isEqualTo(OutcomeKind.REPAIR_REQUIRED);
                assertThat(outcome(manual, "legacy_oa").kind()).isNotEqualTo(OutcomeKind.INSTALLED);
                assertThat(reef.downloads()).isEqualTo(downloads);
                assertThat(runtime.registry().find("legacy_oa")).isEmpty();

                JsonNode damaged = catalogEntry(http, server.port(), "legacy_oa");
                assertThat(damaged.path("installed").asBoolean()).isFalse();
                assertThat(damaged.path("repairRequired").asBoolean()).isTrue();
                assertThat(damaged.path("repairReason").asText()).isEqualTo("BAD_SIGNATURE");
                assertThat(damaged.path("message").asText()).isEqualTo(SignatureDamage.MESSAGE);
                assertThat(damaged.path("version").asText()).isEqualTo("1.0.1");
                assertThat(damaged.path("subscribed").asBoolean()).isTrue();
                JsonNode other = catalogEntry(http, server.port(), "other_sys");
                assertThat(other.path("installed").asBoolean()).isTrue();
                assertThat(other.path("repairRequired").asBoolean()).isFalse();
                assertThat(other.path("repairReason").isNull()).isTrue();
                assertThat(other.path("message").isNull()).isTrue();
                JsonNode waiting = catalogEntry(http, server.port(), "not_yet");
                assertThat(waiting.path("installed").asBoolean()).isFalse();
                assertThat(waiting.path("repairRequired").asBoolean()).isFalse();
                assertThat(waiting.path("repairReason").isNull()).isTrue();
                assertThat(waiting.path("message").isNull()).isTrue();

                HttpResponse<String> detail = send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null);
                assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
                JsonNode shown = JSON.readTree(detail.body()).path("data");
                assertThat(shown.path("installed").asBoolean()).isFalse();
                assertThat(shown.path("repairRequired").asBoolean()).isTrue();
                assertThat(shown.path("repairReason").asText()).isEqualTo("BAD_SIGNATURE");
                assertThat(shown.path("message").asText()).isEqualTo(SignatureDamage.DETAIL_MESSAGE);
                assertThat(shown.path("message").asText()).startsWith(SignatureDamage.MESSAGE);
                assertThat(shown.path("message").asText()).contains("可以手动修复");
                assertThat(shown.path("version").isNull()).isTrue();
                assertThat(shown.path("subscribed").asBoolean()).isTrue();
                assertThat(shown.path("capabilities")).isEmpty();
                assertThat(shown.path("title").asText()).isEqualTo("标题");
                assertThat(detail.body()).doesNotContain("list_todo");

                HttpResponse<String> listed = send(http, server.port(), "GET", "/console/api/conches", null);
                assertThat(listed.statusCode()).isEqualTo(200);
                assertThat(listed.body()).doesNotContain("legacy_oa").contains("other_sys");
                assertThat(toolNames(http, server.port(), key.plaintext(), session))
                        .doesNotContain("legacy_oa__list_todo")
                        .contains("other_sys__list_todo");

                HttpResponse<String> accounts = send(
                        http, server.port(), "GET", "/console/api/conches/legacy_oa/accounts", null);
                assertThat(accounts.statusCode()).isEqualTo(404);
                assertThat(JSON.readTree(accounts.body()).path("error").path("message").asText()).isEqualTo("规则包不存在");
                HttpResponse<String> missing = send(http, server.port(), "GET", "/console/api/conches/missing_conch", null);
                assertThat(missing.statusCode()).isEqualTo(404);
                assertThat(JSON.readTree(missing.body()).path("error").path("message").asText()).isEqualTo("规则包不存在");

                HttpResponse<String> repaired = send(http, server.port(), "POST", "/console/api/conches/legacy_oa/update", "{}");
                assertThat(repaired.statusCode()).as(repaired.body()).isEqualTo(200);
                assertThat(JSON.readTree(repaired.body()).path("data").path("outcome").path("kind").asText()).isEqualTo("INSTALLED");
                assertThat(JSON.readTree(repaired.body()).path("data").path("outcome").path("version").asText()).isEqualTo("1.0.1");
                assertThat(reef.downloads()).isGreaterThan(downloads);
                assertThat(runtime.registry().find("legacy_oa")).isPresent();

                JsonNode restored = catalogEntry(http, server.port(), "legacy_oa");
                assertThat(restored.path("installed").asBoolean()).isTrue();
                assertThat(restored.path("repairRequired").asBoolean()).isFalse();
                assertThat(restored.path("repairReason").isNull()).isTrue();
                assertThat(restored.path("message").isNull()).isTrue();
                HttpResponse<String> after = send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null);
                JsonNode loaded = JSON.readTree(after.body()).path("data");
                assertThat(loaded.path("version").asText()).isEqualTo("1.0.1");
                assertThat(loaded.path("repairRequired").isMissingNode() || !loaded.path("repairRequired").asBoolean()).isTrue();
                assertThat(loaded.path("capabilities").toString()).contains("list_todo");
                assertThat(toolNames(http, server.port(), key.plaintext(), session)).contains("legacy_oa__list_todo");
            }
        }
    }

    @Test
    void pointerMissingStaysRepairRequiredWithoutASignatureDetail() throws Exception {
        try (FakeReef reef = new FakeReef()) {
            reef.publish(ConchTexts.standard("legacy_oa", "1.0.0"));
            CrabConfig config = config(reef).withListen("127.0.0.1", 0);
            try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("pointer")), config, name -> null);
                 CrabServer server = CrabServer.start(runtime)) {
                HttpClient http = HttpClient.newHttpClient();
                runtime.subscribe("legacy_oa", Actors.CONSOLE);
                runtime.registry().remove("legacy_oa");
                Files.delete(runtime.paths().conches().resolve("legacy_oa/current"));
                int downloads = reef.downloads();

                runtime.conchSync().runScheduled();
                runtime.syncOnce(Actors.CONSOLE);
                assertThat(reef.downloads()).isEqualTo(downloads);
                assertThat(runtime.registry().find("legacy_oa")).isEmpty();

                JsonNode row = catalogEntry(http, server.port(), "legacy_oa");
                assertThat(row.path("installed").asBoolean()).isFalse();
                assertThat(row.path("repairRequired").asBoolean()).isFalse();
                assertThat(row.path("repairReason").isNull()).isTrue();
                assertThat(row.path("message").isNull()).isTrue();
                HttpResponse<String> detail = send(http, server.port(), "GET", "/console/api/conches/legacy_oa", null);
                assertThat(detail.statusCode()).isEqualTo(404);
                assertThat(JSON.readTree(detail.body()).path("error").path("message").asText()).isEqualTo("规则包不存在");
            }
        }
    }

    private static JsonNode catalogEntry(HttpClient http, int port, String conch) throws Exception {
        HttpResponse<String> catalog = send(http, port, "GET", "/console/api/catalog", null);
        assertThat(catalog.statusCode()).as(catalog.body()).isEqualTo(200);
        for (JsonNode row : JSON.readTree(catalog.body()).path("data").path("conches")) {
            if (conch.equals(row.path("conch").asText())) {
                return row;
            }
        }
        throw new AssertionError("missing catalog row " + conch);
    }

    private static ConchCatalog.Entry entry(ConchCatalog catalog, String conch) {
        return catalog.conches().stream().filter(row -> conch.equals(row.conch())).findFirst().orElseThrow();
    }

    private static ConchOutcome outcome(SyncReport report, String conch) {
        return report.conches().stream().filter(row -> conch.equals(row.conch())).findFirst().orElseThrow();
    }

    private static String mcpSession(HttpClient http, int port, String key) throws Exception {
        HttpResponse<String> initialized = mcp(http, port, key, null, initialize());
        assertThat(initialized.statusCode()).as(initialized.body()).isEqualTo(200);
        return initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    private static List<String> toolNames(HttpClient http, int port, String key, String session) throws Exception {
        HttpResponse<String> listed = mcp(http, port, key, session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        JsonNode tools = JSON.readTree(listed.body()).path("result").path("tools");
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    private static String initialize() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                """.trim();
    }

    private static HttpResponse<String> mcp(HttpClient http, int port, String key, String session, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + key);
        if (session != null) {
            builder.header("Mcp-Session-Id", session);
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> send(HttpClient http, int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void corruptSignature(Path zip) throws IOException {
        Map<String, byte[]> files = unzip(Files.readAllBytes(zip));
        byte[] signature = files.get("signature.sig");
        signature[0] ^= 0x01;
        Files.write(zip, rezip(files));
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    private static byte[] rezip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String name : List.of("conch.yaml", "manifest.json", "signature.sig")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(files.get(name));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static CrabConfig config(FakeReef reef) {
        return new CrabConfig(
                reef.base().toString(),
                null,
                Ed25519Keys.writePublicPem(reef.keys().getPublic()),
                "127.0.0.1",
                0,
                3600,
                false,
                3600,
                List.of(),
                Map.of(),
                60,
                10,
                3,
                List.of(),
                4);
    }
}
