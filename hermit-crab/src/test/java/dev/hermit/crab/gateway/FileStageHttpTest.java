package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.Actors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 文件暂存区：下载得到 fileId 和链接，上传用 fileId，文件只属于创建它的 Key。 */
class FileStageHttpTest {
    private static final String PASSWORD = "S3cr3t-files";
    private static final String ACTIONS = """
              export_report:
                title: 导出报表
                description: 导出报表文件。
                steps:
                  - request: { method: GET, path: /files/report.bin, download: true }
                  - set: { file: "{{ response.file }}" }
                output:
                  file: { type: object, description: 报表文件 }
                mcp: { read_only: true }
              upload_attachment:
                title: 上传附件
                description: 上传一个附件。
                method: POST
                input:
                  doc: { type: file, required: true, description: 附件 }
                  note: { type: string, default: none, description: 备注 }
                steps:
                  - request:
                      method: POST
                      path: /files/upload
                      multipart:
                        note: "{{ input.note }}"
                        attachment: { file: "{{ input.doc }}" }
                  - set: { ok: "{{ response.body }}" }
                output:
                  ok: { type: string, description: 结果 }
            """;

    @TempDir
    Path temp;

    @Test
    void downloadGivesAFileIdAndLinkAndUploadSendsMultipart() throws Exception {
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (TestServer server = TestServer.open(temp.resolve("files"), PASSWORD, config)) {
            String key = server.apiKey.plaintext();
            String yaml = TestServer.conchYaml()
                    .replaceFirst("(?m)^conch:.*$", "conch: file_oa")
                    .replaceFirst("(?m)^min_crab_version:.*$", "min_crab_version: 1.8.0")
                    .replaceFirst("(?m)^actions:\\n", "actions:\n" + ACTIONS);
            server.runtime.localConches().importBytes(yaml.getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
            server.runtime.admin().createAccount("file_oa", "alice", Actors.CONSOLE);
            server.runtime.admin().putCredentials(
                    server.runtime.registry().find("file_oa").orElseThrow(),
                    "alice",
                    java.util.Map.of("username", "alice", "password", PASSWORD),
                    Actors.CONSOLE);
            server.runtime.admin().putConfig(
                    server.runtime.registry().find("file_oa").orElseThrow(),
                    "base_url", server.fixture.baseUrl(), Actors.CONSOLE);

            // 下载：返回 fileId 和带随机令牌的链接，链接不带 Key 也能取，可以取两次。
            TestServer.Reply exported = server.call("GET", "/api/file_oa/export_report", key, "alice", null, null);
            assertThat(exported.status()).as(exported.body()).isEqualTo(200);
            JsonNode file = exported.json().path("data").path("file");
            assertThat(file.path("name").asText()).isEqualTo("报表.pdf");
            assertThat(file.path("mime").asText()).isEqualTo("application/pdf");
            assertThat(file.path("size").asLong()).isEqualTo(4);
            String fileId = file.path("fileId").asText();
            String url = file.path("url").asText();
            assertThat(url).startsWith(server.base() + "/api/-/files/").doesNotContain(fileId);
            for (int i = 0; i < 2; i++) {
                HttpResponse<byte[]> got = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(got.statusCode()).isEqualTo(200);
                assertThat(got.body()).containsExactly(server.fixture.fileBytes);
                assertThat(got.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
            }
            // fileId 不是下载凭据。
            assertThat(HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(server.base() + "/api/-/files/" + fileId)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);

            // 转存：下载得到的 fileId 直接作上传入参，上游收到同样的字节。
            TestServer.Reply uploaded = server.call(
                    "POST", "/api/file_oa/upload_attachment", key, "alice", null,
                    "{\"doc\":\"" + fileId + "\",\"note\":\"hello\"}");
            assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(200);
            assertThat(server.fixture.uploadType).startsWith("multipart/form-data; boundary=");
            String sent = new String(server.fixture.uploadBody, StandardCharsets.ISO_8859_1);
            assertThat(sent).contains("name=\"note\"\r\n\r\nhello");
            assertThat(sent).contains("name=\"attachment\"; filename=\"");
            assertThat(sent).contains("Content-Type: application/pdf\r\n\r\n\u0000\u0001\u0002\u00ff\r\n");

            // 先用 HTTP 上传再调用。
            HttpResponse<String> staged = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(server.base() + "/api/-/files?name=a.txt"))
                            .header("Authorization", "Bearer " + key)
                            .header("Content-Type", "text/plain")
                            .POST(HttpRequest.BodyPublishers.ofString("from-curl"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(staged.statusCode()).as(staged.body()).isEqualTo(200);
            String mine = ApiJsonReader.data(staged.body()).path("fileId").asText();
            assertThat(server.call("POST", "/api/file_oa/upload_attachment", key, "alice", null,
                    "{\"doc\":\"" + mine + "\"}").status()).isEqualTo(200);
            assertThat(new String(server.fixture.uploadBody, StandardCharsets.UTF_8))
                    .contains("filename=\"a.txt\"").contains("from-curl");
            // 不带 Key 不能上传。
            assertThat(HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(server.base() + "/api/-/files?name=a.txt"))
                            .POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);

            // 小文件可以直接用 data URI。
            String inline = "data:text/plain;name=b.txt;base64,"
                    + Base64.getEncoder().encodeToString("inline-bytes".getBytes(StandardCharsets.UTF_8));
            assertThat(server.call("POST", "/api/file_oa/upload_attachment", key, "alice", null,
                    "{\"doc\":\"" + inline + "\"}").status()).isEqualTo(200);
            assertThat(new String(server.fixture.uploadBody, StandardCharsets.UTF_8))
                    .contains("filename=\"b.txt\"").contains("inline-bytes");

            // 别的 Key 用不了这个 fileId；控制台上传的文件所有 Key 都能用。
            String other = server.runtime.apiKeys().create("other", Actors.CONSOLE).plaintext();
            TestServer.Reply denied = server.call("POST", "/api/file_oa/upload_attachment", other, "alice", null,
                    "{\"doc\":\"" + mine + "\"}");
            assertThat(denied.status()).isEqualTo(400);
            assertThat(denied.json().path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            String shared = server.runtime.fileStage()
                    .store("shared".getBytes(StandardCharsets.UTF_8), "c.txt", "text/plain", null).fileId();
            assertThat(server.call("POST", "/api/file_oa/upload_attachment", other, "alice", null,
                    "{\"doc\":\"" + shared + "\"}").status()).isEqualTo(200);

            // 控制台：清单不带下载令牌，可以改设置；超过单文件上限的下载被拒。
            JsonNode listed = server.console("GET", "/console/api/files", null, true).json().path("data");
            assertThat(listed.path("settings").path("ttlSeconds").asInt()).isEqualTo(3600);
            assertThat(listed.path("files").size()).isGreaterThanOrEqualTo(4);
            assertThat(listed.toString()).doesNotContain(url.substring(url.lastIndexOf('/') + 1));
            TestServer.Reply changed = server.console("PUT", "/console/api/files/settings",
                    "{\"ttlSeconds\":600,\"maxFileBytes\":1024,\"maxTotalBytes\":1048576}", true);
            assertThat(changed.status()).as(changed.body()).isEqualTo(200);
            server.fixture.fileBytes = new byte[2048];
            TestServer.Reply tooLarge = server.call("GET", "/api/file_oa/export_report", key, "alice", null, null);
            assertThat(tooLarge.status()).isNotEqualTo(200);
            assertThat(tooLarge.body()).contains("单文件上限");
        }
    }

    /** 读 {@code data}，不依赖生产代码的包内工具。 */
    private static final class ApiJsonReader {
        static JsonNode data(String body) throws Exception {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data");
        }
    }
}
