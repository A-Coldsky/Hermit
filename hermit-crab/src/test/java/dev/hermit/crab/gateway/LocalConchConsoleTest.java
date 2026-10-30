package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.sync.LocalConchBehaviorTest;

class LocalConchConsoleTest {
    private static final String PASSWORD = "HermitLocalConsole-91ab";

    @TempDir
    Path temp;

    @Test
    void consoleImportExportAndExplicitKeyCall() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp, PASSWORD, config)) {
            byte[] yaml = LocalConchBehaviorTest.ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8);
            HttpResponse<String> imported = post(
                    server, "/console/api/local-conches", "text/yaml; charset=utf-8", yaml);
            assertThat(imported.statusCode()).isEqualTo(200);
            assertThat(imported.body()).contains("\"conch\":\"local_ping\"").contains("\"replaced\":false");

            HttpResponse<String> clash = post(
                    server,
                    "/console/api/local-conches",
                    "application/yaml",
                    LocalConchBehaviorTest.ping("legacy_oa", "1.0.0").getBytes(StandardCharsets.UTF_8));
            assertThat(clash.statusCode()).isEqualTo(409);
            assertThat(clash.body()).contains("INVALID_INPUT").contains("与已安装的正式规则包重名");

            HttpResponse<String> invalid = post(
                    server, "/console/api/local-conches", "text/yaml", "conch: [\n".getBytes(StandardCharsets.UTF_8));
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).contains("\"errors\"");

            String boundary = "----hermitlocal";
            byte[] other = LocalConchBehaviorTest.ping("local_two", "1.0.0").getBytes(StandardCharsets.UTF_8);
            byte[] multipart = multipart(boundary, "local_two.yaml", other);
            HttpResponse<String> uploaded = post(
                    server, "/console/api/local-conches", "multipart/form-data; boundary=" + boundary, multipart);
            assertThat(uploaded.statusCode()).isEqualTo(200);
            assertThat(uploaded.body()).contains("local_two");

            TestServer.Reply listed = server.console("GET", "/console/api/conches", null, true);
            assertThat(listed.status()).isEqualTo(200);
            assertThat(listed.body()).contains("\"conch\":\"legacy_oa\"").contains("\"source\":\"REEF\"");
            assertThat(listed.body()).contains("\"conch\":\"local_ping\"").contains("\"source\":\"LOCAL\"");

            HttpResponse<byte[]> exported = server.http.send(
                    HttpRequest.newBuilder(URI.create(server.base() + "/console/api/local-conches/local_ping/export"))
                            .header("X-Hermit-Console", "1")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(exported.statusCode()).isEqualTo(200);
            assertThat(exported.headers().firstValue("Content-Type").orElse("")).startsWith("application/yaml");
            assertThat(exported.headers().firstValue("Content-Disposition").orElse(""))
                    .isEqualTo("attachment; filename=\"local_ping-1.0.0.yaml\"");
            assertThat(exported.body()).isEqualTo(yaml);
            assertThat(exported.body()).isEqualTo(Files.readAllBytes(
                    server.paths.localConches().resolve("local_ping.yaml")));

            TestServer.Reply missing = server.console("DELETE", "/console/api/local-conches/legacy_oa", null, true);
            assertThat(missing.status()).isEqualTo(404);

            server.runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            CreatedApiKey named = server.runtime.admin().createApiKey(
                    "named", new ApiKeyScope(Set.of("local_ping"), Set.of(), false), Actors.CLI);
            TestServer.Reply denied = server.call("GET", "/api/local_ping/ping", server.apiKey.plaintext(), null, null, null);
            TestServer.Reply allowed = server.call("GET", "/api/local_ping/ping", named.plaintext(), null, null, null);
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.json().path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(allowed.status()).isEqualTo(200);

            TestServer.Reply calls = server.console("GET", "/console/api/calls?conch=local_ping", null, true);
            assertThat(calls.body()).contains("\"source\":\"LOCAL\"");

            TestServer.Reply removed = server.console("DELETE", "/console/api/local-conches/local_two", null, true);
            assertThat(removed.status()).isEqualTo(200);
            assertThat(removed.body()).contains("\"deleted\":true");
            assertThat(server.runtime.registry().find("local_two")).isEmpty();
        }
    }

    private static HttpResponse<String> post(TestServer server, String path, String type, byte[] body) throws Exception {
        return server.http.send(
                HttpRequest.newBuilder(URI.create(server.base() + path))
                        .header("X-Hermit-Console", "1")
                        .header("Content-Type", type)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] multipart(String boundary, String filename, byte[] yaml) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] prefix = head.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = tail.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + yaml.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(yaml, 0, body, prefix.length, yaml.length);
        System.arraycopy(suffix, 0, body, prefix.length + yaml.length, suffix.length);
        return body;
    }
}
