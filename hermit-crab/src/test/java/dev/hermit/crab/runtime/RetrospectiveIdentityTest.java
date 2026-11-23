package dev.hermit.crab.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.files.FileStage;
import dev.hermit.crab.files.StagedFile;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.store.Origin;

/**
 * 针对 CrabRuntime 试跑文件归属隔离、调用通道区分、mcp.expose 守门与本地扩边界待输入隔离的回归测试。
 */
class RetrospectiveIdentityTest {

    @TempDir
    Path temp;

    /** 控制台试跑下载的文件归属 CONSOLE_TRIAL_OWNER（不对 API Key 共享），且调用通道正确区分 CONSOLE 与 MCP。 */
    @Test
    void consoleTrialIsolatesDownloadedFilesAndDistinguishesMcpVsConsoleChannel() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/files/report.bin", exchange -> {
            byte[] payload = "console-secret-report".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        upstream.start();
        try (CrabRuntime runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("trial-files")), CrabConfig.defaults(), name -> null)) {
            runtime.debug().setEnabled(true, Actors.CONSOLE);
            runtime.localConches().importBytes(
                    downloadYaml(upstream.getAddress().getPort()).getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
            runtime.admin().createAccount(Origin.LOCAL, "dl_pkg", "main", Actors.CONSOLE);

            CreatedApiKey keyA = runtime.apiKeys().create(
                    "key-a", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CONSOLE);
            CreatedApiKey keyB = runtime.apiKeys().create(
                    "key-b", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CONSOLE);
            ApiKeyPrincipal principalA = runtime.apiKeys().authenticate(keyA.plaintext()).orElseThrow();

            // 1. 控制台试跑（apiKeyId == null）：下载文件用 CONSOLE_TRIAL_OWNER 隔离，不共享给任何 API Key。
            ExecutionResult consoleResult = runtime.trialLocal(
                    "dl_pkg", "fetch", Optional.of("main"), Map.of(), "trace-console-dl");
            assertThat(consoleResult.success()).isTrue();
            String consoleFileId = fileIdOf(consoleResult);
            assertThat(consoleFileId).isNotBlank();
            assertThat(runtime.fileStage().find(consoleFileId, keyA.id())).isEmpty();
            assertThat(runtime.fileStage().find(consoleFileId, keyB.id())).isEmpty();
            assertThat(runtime.fileStage().find(consoleFileId, FileStage.CONSOLE_TRIAL_OWNER)).isPresent();

            CallLogStore.CallDetail consoleLog = runtime.callLogs().findByTraceId("trace-console-dl").orElseThrow();
            assertThat(consoleLog.call().channel()).isEqualTo("CONSOLE");
            assertThat(consoleLog.call().apiKeyId()).isNull();

            // 控制台显式上传的文件（owner == null）仍可被控制台试跑和 API Key 共同读取。
            StagedFile consoleUpload = runtime.fileStage().store(
                    "uploaded-by-console".getBytes(StandardCharsets.UTF_8), "up.txt", "text/plain", null);
            assertThat(runtime.fileStage().find(consoleUpload.fileId(), FileStage.CONSOLE_TRIAL_OWNER)).isPresent();
            assertThat(runtime.fileStage().find(consoleUpload.fileId(), keyA.id())).isPresent();

            // 2. MCP 通道试跑（apiKeyId != null）：写入 MCP 通道，下载文件仅归属发起试跑的那把 Key。
            ConchDoc doc = runtime.registry().find("dl_pkg", Origin.LOCAL).orElseThrow();
            ExecutionResult mcpResult = runtime.trialLocal(
                    "dl_pkg", "fetch", Optional.of("main"), Map.of(), "trace-mcp-dl",
                    principalA.id(), doc, principalA, false);
            assertThat(mcpResult.success()).isTrue();
            String mcpFileId = fileIdOf(mcpResult);
            assertThat(mcpFileId).isNotBlank();
            assertThat(runtime.fileStage().find(mcpFileId, principalA.id())).isPresent();
            assertThat(runtime.fileStage().find(mcpFileId, keyB.id())).isEmpty();
            assertThat(runtime.fileStage().find(mcpFileId, FileStage.CONSOLE_TRIAL_OWNER)).isEmpty();

            CallLogStore.CallDetail mcpLog = runtime.callLogs().findByTraceId("trace-mcp-dl").orElseThrow();
            assertThat(mcpLog.call().channel()).isEqualTo("MCP");
            assertThat(mcpLog.call().apiKeyId()).isEqualTo(principalA.id());
        } finally {
            upstream.stop(0);
        }
    }

    /** MCP 通道试跑（principal != null）拒绝 mcp.expose: false 的能力；控制台试跑仍允许执行。 */
    @Test
    void mcpTrialRejectsUnexposedCapabilityWhileConsoleTrialAllowsIt() throws Exception {
        try (CrabRuntime runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("trial-expose")), CrabConfig.defaults(), name -> null)) {
            runtime.debug().setEnabled(true, Actors.CONSOLE);
            runtime.localConches().importBytes(exposeYaml().getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
            runtime.admin().createAccount(Origin.LOCAL, "hide_pkg", "main", Actors.CONSOLE);

            CreatedApiKey fullKey = runtime.apiKeys().create(
                    "full", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CONSOLE);
            ApiKeyPrincipal principal = runtime.apiKeys().authenticate(fullKey.plaintext()).orElseThrow();
            ConchDoc doc = runtime.registry().find("hide_pkg", Origin.LOCAL).orElseThrow();

            assertThatThrownBy(() -> runtime.trialLocal(
                    "hide_pkg", "hidden", Optional.of("main"), Map.of(), "trace-mcp-hidden",
                    principal.id(), doc, principal, false))
                    .isInstanceOf(TrialRejected.class)
                    .satisfies(ex -> {
                        TrialRejected rejected = (TrialRejected) ex;
                        assertThat(rejected.status()).isEqualTo(403);
                        assertThat(rejected.code()).isEqualTo(ErrorCode.FORBIDDEN);
                    });

            ExecutionResult mcpShown = runtime.trialLocal(
                    "hide_pkg", "shown", Optional.of("main"), Map.of(), "trace-mcp-shown",
                    principal.id(), doc, principal, false);
            assertThat(mcpShown.success()).isTrue();
            assertThat(runtime.callLogs().findByTraceId("trace-mcp-shown").orElseThrow().call().channel())
                    .isEqualTo("MCP");

            ExecutionResult consoleHidden = runtime.trialLocal(
                    "hide_pkg", "hidden", Optional.of("main"), Map.of(), "trace-console-hidden");
            assertThat(consoleHidden.success()).isTrue();
            assertThat(runtime.callLogs().findByTraceId("trace-console-hidden").orElseThrow().call().channel())
                    .isEqualTo("CONSOLE");
        }
    }

    /** 本地包扩边界覆盖清理待输入登录时，只丢弃 Origin.LOCAL 的待输入，不误删同名正式包的待输入。 */
    @Test
    void widenedLocalOverwriteOnlyDiscardsLocalPendingLoginNotOfficial() throws Exception {
        HttpServer oldHost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        oldHost.createContext("/captcha.png", exchange -> {
            byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1};
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(png);
            }
        });
        oldHost.start();
        HttpServer newHost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        newHost.start();
        int oldPort = oldHost.getAddress().getPort();
        int newPort = newHost.getAddress().getPort();
        try (CrabRuntime runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("widened-pending")), CrabConfig.defaults(), name -> null)) {
            String initialYaml = askYaml("dual_ask", "1.0.0", oldPort);
            ConchDoc officialDoc = ConchParser.parse(initialYaml);
            runtime.registry().put(officialDoc, Origin.OFFICIAL);
            runtime.localConches().importBytes(initialYaml.getBytes(StandardCharsets.UTF_8), Actors.CLI);
            ConchDoc localDoc = runtime.registry().find("dual_ask", Origin.LOCAL).orElseThrow();

            runtime.admin().createAccount(Origin.OFFICIAL, "dual_ask", "ada", Actors.CLI);
            runtime.admin().createAccount(Origin.LOCAL, "dual_ask", "ada", Actors.CLI);

            ExecutionResult officialLogin = runtime.admin().testLogin(
                    Origin.OFFICIAL, officialDoc, "ada", "trace-ask-official", Actors.CONSOLE);
            assertThat(officialLogin.success()).isFalse();
            assertThat(officialLogin.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(runtime.admin().loginInput(Origin.OFFICIAL, "dual_ask", "ada")).isNotNull();

            // 扩大本地包地址边界并覆盖：只清 Origin.LOCAL 的待输入登录，同名正式包的待输入必须保留。
            runtime.localConches().importBytes(
                    askYaml("dual_ask", "1.0.1", newPort).getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            assertThat(runtime.admin().loginInput(Origin.OFFICIAL, "dual_ask", "ada"))
                    .as("同名正式包的待输入登录不应被本地扩边界覆盖清掉").isNotNull();

            // 切换到 LOCAL 生效并建立本地包的待输入登录，再次扩边界覆盖时必须清掉 Origin.LOCAL 的待输入。
            runtime.registry().activate("dual_ask", Origin.LOCAL);
            ConchDoc localV101 = runtime.registry().find("dual_ask", Origin.LOCAL).orElseThrow();
            // 复用 oldPort 让本地包先触发验证码等待，再扩回 newPort。
            runtime.localConches().importBytes(
                    askYaml("dual_ask", "1.0.2", oldPort).getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            ConchDoc localV102 = runtime.registry().find("dual_ask", Origin.LOCAL).orElseThrow();
            ExecutionResult localLogin = runtime.admin().testLogin(
                    Origin.LOCAL, localV102, "ada", "trace-ask-local", Actors.CONSOLE);
            assertThat(localLogin.success()).isFalse();
            assertThat(localLogin.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            assertThat(runtime.admin().loginInput(Origin.LOCAL, "dual_ask", "ada")).isNotNull();

            runtime.localConches().importBytes(
                    askYaml("dual_ask", "1.0.3", newPort).getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            assertThat(runtime.admin().loginInput(Origin.LOCAL, "dual_ask", "ada")).isNull();
            assertThat(localV101.version().toString()).isEqualTo("1.0.1");
        } finally {
            oldHost.stop(0);
            newHost.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static String fileIdOf(ExecutionResult result) {
        Map<String, Object> data = (Map<String, Object>) result.data();
        Map<String, Object> file = (Map<String, Object>) data.get("file");
        return (String) file.get("fileId");
    }

    private static String downloadYaml(int port) {
        return """
                conch: dl_pkg
                version: 1.0.0
                driver: http
                min_crab_version: 1.8.0
                base_url: http://127.0.0.1:%d
                title: 下载试跑
                description: 校验试跑下载文件归属与调用通道。
                credentials: {}
                actions:
                  fetch:
                    title: 导出报表
                    description: 下载一个文件。
                    idempotent: true
                    mcp: { expose: true, read_only: true }
                    steps:
                      - request: { method: GET, path: /files/report.bin, download: true }
                      - set: { file: "{{ response.file }}" }
                    output:
                      file: { type: object, description: 报表文件 }
                    examples:
                      - input: {}
                        output: { file: { fileId: f_1 } }
                    notes: 无
                    related: []
                """.formatted(port);
    }

    private static String exposeYaml() {
        return """
                conch: hide_pkg
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9/
                title: 暴露控制
                description: 校验 MCP 试跑对 mcp.expose 的限制。
                credentials: {}
                actions:
                  shown:
                    title: 已暴露
                    description: 对 MCP 暴露的能力。
                    idempotent: true
                    mcp: { expose: true, read_only: true }
                    steps:
                      - set: { message: ok }
                    output:
                      message: { type: string, description: 文本 }
                    examples:
                      - input: {}
                        output: { message: ok }
                    notes: 无
                    related: []
                  hidden:
                    title: 未暴露
                    description: 不对 MCP 暴露的能力。
                    idempotent: true
                    mcp: { expose: false, read_only: true }
                    steps:
                      - set: { message: hidden }
                    output:
                      message: { type: string, description: 文本 }
                    examples:
                      - input: {}
                        output: { message: hidden }
                    notes: 无
                    related: []
                """;
    }

    private static String askYaml(String name, String version, int port) {
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.4.0
                base_url: http://127.0.0.1:%d
                title: 验证码
                description: 校验本地扩边界覆盖不影响同名正式包的待输入登录。
                credentials: {}
                auth:
                  steps:
                    - request: { method: GET, path: /captcha.png }
                    - ask:
                        into: captcha
                        kind: image
                        label: 验证码
                        hint: 看图
                        timeout_s: 300
                    - request:
                        method: POST
                        path: "/login/{{captcha}}"
                        form:
                          code: "{{captcha}}"
                    - extract:
                        token: { jsonpath: $.token }
                actions:
                  tick:
                    title: 打点
                    description: 用登录令牌打点。
                    method: POST
                    output:
                      seen: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { seen: ok }
                    notes: 测试。
                    related: []
                    steps:
                      - request: { method: GET, path: "/tick/{{login.token}}" }
                      - set: { seen: ok }
                """.formatted(name, version, port);
    }
}
