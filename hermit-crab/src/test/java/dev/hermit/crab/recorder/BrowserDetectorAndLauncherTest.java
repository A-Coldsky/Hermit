package dev.hermit.crab.recorder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserDetectorAndLauncherTest {
    @TempDir
    Path temp;

    @Test
    void detectsConfiguredEnvOverrideAndLinuxPathBrowser() throws Exception {
        Path binDir = Files.createDirectories(temp.resolve("bin"));
        Path fakeChrome = binDir.resolve("google-chrome");
        Files.writeString(fakeChrome, "#!/bin/sh\n", StandardCharsets.UTF_8);
        fakeChrome.toFile().setExecutable(true);

        Path customBrowser = temp.resolve("my-chromium");
        Files.writeString(customBrowser, "#!/bin/sh\n", StandardCharsets.UTF_8);
        customBrowser.toFile().setExecutable(true);

        Map<String, String> env = Map.of(
                BrowserDetector.ENV_CHROME_PATH, customBrowser.toString(),
                "PATH", binDir.toString());

        List<BrowserDetector.DetectedBrowser> detected = BrowserDetector.detect("Linux", env::get, temp.toString());
        assertThat(detected).isNotEmpty();
        assertThat(detected.get(0).path()).isEqualTo(customBrowser.toAbsolutePath().normalize().toString());
        assertThat(detected.stream().map(BrowserDetector.DetectedBrowser::path))
                .contains(fakeChrome.toAbsolutePath().normalize().toString());
    }

    @Test
    void rejectsNonLoopbackAttachEndpointsAndUnsafeStartUrls() {
        assertThatThrownBy(() -> BrowserLauncher.resolveAttachUri("http://10.0.0.8:9222"))
                .isInstanceOf(RecorderException.class)
                .hasMessageContaining("仅允许本机回环");
        assertThatThrownBy(() -> BrowserLauncher.resolveAttachUri("ws://example.com:9222/devtools/browser/1"))
                .isInstanceOf(RecorderException.class)
                .hasMessageContaining("仅允许本机回环");
        assertThatThrownBy(() -> BrowserLauncher.normalizeStartUrl("javascript:alert(1)"))
                .isInstanceOf(RecorderException.class);
        assertThatThrownBy(() -> BrowserLauncher.normalizeStartUrl("file:///etc/passwd"))
                .isInstanceOf(RecorderException.class);
        assertThat(BrowserLauncher.normalizeStartUrl("https://oa.corp.local/login"))
                .isEqualTo("https://oa.corp.local/login");
        assertThat(BrowserLauncher.normalizeStartUrl("   ")).isEqualTo("about:blank");

        URI loopbackWs = BrowserLauncher.resolveAttachUri("ws://127.0.0.1:9222/devtools/browser/abc");
        assertThat(loopbackWs.toString()).isEqualTo("ws://127.0.0.1:9222/devtools/browser/abc");
    }

    @Test
    void parsesDevToolsActivePortFile() throws Exception {
        Path portFile = temp.resolve("DevToolsActivePort");
        Files.writeString(portFile, "43210\n/devtools/browser/uuid-123\n", StandardCharsets.UTF_8);
        Optional<URI> parsed = BrowserLauncher.readDevToolsActivePort(portFile);
        assertThat(parsed).contains(URI.create("ws://127.0.0.1:43210/devtools/browser/uuid-123"));

        Path invalid = temp.resolve("BadPort");
        Files.writeString(invalid, "not-a-port\nfoo\n", StandardCharsets.UTF_8);
        assertThat(BrowserLauncher.readDevToolsActivePort(invalid)).isEmpty();
    }

    @Test
    void validatesExecutablePath() {
        assertThatThrownBy(() -> BrowserDetector.validateExecutable(temp.resolve("missing-chrome").toString()))
                .isInstanceOf(RecorderException.class)
                .hasMessageContaining("找不到");
        assertThatThrownBy(() -> BrowserDetector.validateExecutable(temp.toString()))
                .isInstanceOf(RecorderException.class)
                .hasMessageContaining("找不到");
    }

    @Test
    void worksCleanlyWithoutAnyLocalBrowserInstalled() {
        // Simulates a machine with zero Chrome/Edge/Chromium installed.
        List<BrowserDetector.DetectedBrowser> none = BrowserDetector.detect("Mac OS X", k -> null, temp.toString());
        assertThat(none).isEmpty();
        assertThat(BrowserDetector.findDefaultDevToolsActivePort("Mac OS X", k -> null, temp.toString())).isEmpty();

        try (RecorderManager manager = new RecorderManager(
                temp.resolve("profiles"),
                java.time.Clock.systemUTC(),
                k -> null,
                () -> true)) {
            // HAR import & snapshot creation work completely independently of browser availability.
            String har = """
                    {"log":{"version":"1.2","entries":[{
                      "startedDateTime":"2026-11-24T12:00:00Z","time":10,
                      "request":{"method":"GET","url":"https://oa.example.com/api/list","headers":[]},
                      "response":{"status":200,"statusText":"OK","headers":[],"content":{"size":9,"mimeType":"application/json","text":"{\\"ok\\":true}"}}
                    }]}}""";
            RecorderManager.RecorderStatus imported = manager.importHar(har.getBytes(StandardCharsets.UTF_8), "manual.har");
            assertThat(imported.businessEntries()).isEqualTo(1);
            List<RecordedEntry> entries = manager.listSessionEntries(false);
            CaptureSnapshot snap = manager.createSnapshot("无浏览器手工 HAR", List.of(entries.get(0).id()), List.of());
            assertThat(snap.entries()).hasSize(1);
            assertThat(manager.exportHar(snap.id())).isNotEmpty();
        }
    }

    @Test
    void detectsWindowsBrowsersWithQuotesSpacesChinesePathsAndQuotedPathVar() throws Exception {
        Path programFiles = Files.createDirectories(temp.resolve("Program Files"));
        Path chrome = Files.createDirectories(programFiles.resolve("Google/Chrome/Application")).resolve("chrome.exe");
        Files.writeString(chrome, "stub", StandardCharsets.UTF_8);
        chrome.toFile().setExecutable(true);

        Path chineseDir = Files.createDirectories(temp.resolve("用户张三 浏览器"));
        Path edge = chineseDir.resolve("msedge.exe");
        Files.writeString(edge, "stub", StandardCharsets.UTF_8);
        edge.toFile().setExecutable(true);

        Map<String, String> env = Map.of(
                "ProgramFiles", programFiles.toString(),
                "Path", "\"" + chineseDir + "\";C:\\nonexistent");

        List<BrowserDetector.DetectedBrowser> detected = BrowserDetector.detect("Windows 10", env::get, temp.toString());
        assertThat(detected).extracting(BrowserDetector.DetectedBrowser::id)
                .contains("chrome", "edge");
        assertThat(detected).extracting(BrowserDetector.DetectedBrowser::path)
                .contains(chrome.toAbsolutePath().normalize().toString(),
                        edge.toAbsolutePath().normalize().toString());

        // Explorer "Copy as path" adds surrounding double quotes; both validateExecutable and env override must accept them.
        assertThat(BrowserDetector.validateExecutable("\"" + chrome + "\""))
                .isEqualTo(chrome.toAbsolutePath().normalize());
        Map<String, String> quotedEnv = Map.of(BrowserDetector.ENV_CHROME_PATH, "\"" + edge + "\"");
        assertThat(BrowserDetector.configuredOverride(quotedEnv::get))
                .contains(edge.toAbsolutePath().normalize());

        // Malformed env override must not crash detect().
        Map<String, String> badEnv = Map.of(BrowserDetector.ENV_CHROME_PATH, "bad\0path");
        assertThat(BrowserDetector.configuredOverride(badEnv::get)).isEmpty();
    }

    @Test
    void parsesWindowsRegistrySzAndExpandSzOutputs() {
        String regSz = """
                HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe
                    (默认)    REG_SZ    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
                """;
        assertThat(BrowserDetector.parseWindowsRegQueryOutput(regSz, k -> null))
                .contains("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");

        String regExpand = """
                HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\msedge.exe
                    (Default)    REG_EXPAND_SZ    "%LOCALAPPDATA%\\Microsoft\\Edge\\Application\\msedge.exe"
                """;
        Map<String, String> env = Map.of("LOCALAPPDATA", "C:\\Users\\张三\\AppData\\Local");
        assertThat(BrowserDetector.parseWindowsRegQueryOutput(regExpand, env::get))
                .contains("C:\\Users\\张三\\AppData\\Local\\Microsoft\\Edge\\Application\\msedge.exe");
    }

    @Test
    void handlesWindowsDevToolsActivePortBomCrlfAndStaleFallback() throws Exception {
        Path localAppData = Files.createDirectories(temp.resolve("AppDataLocal"));
        Path chromePort = Files.createDirectories(localAppData.resolve("Google/Chrome/User Data"))
                .resolve("DevToolsActivePort");
        Path edgePort = Files.createDirectories(localAppData.resolve("Microsoft/Edge/User Data"))
                .resolve("DevToolsActivePort");

        // Chrome file has UTF-8 BOM + CRLF and an older timestamp (stale port 41111).
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] chromeBody = "41111\r\n/devtools/browser/chrome-stale\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] chromeWithBom = new byte[bom.length + chromeBody.length];
        System.arraycopy(bom, 0, chromeWithBom, 0, bom.length);
        System.arraycopy(chromeBody, 0, chromeWithBom, bom.length, chromeBody.length);
        Files.write(chromePort, chromeWithBom);
        Files.setLastModifiedTime(chromePort, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));

        // Edge file is newer (live port 42222).
        Files.writeString(edgePort, "42222\r\n/devtools/browser/edge-live\r\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(edgePort, java.nio.file.attribute.FileTime.fromMillis(1_700_000_060_000L));

        assertThat(BrowserLauncher.readDevToolsActivePort(chromePort))
                .contains(URI.create("ws://127.0.0.1:41111/devtools/browser/chrome-stale"));

        Map<String, String> winEnv = Map.of("LOCALAPPDATA", localAppData.toString());
        List<Path> discovered = BrowserDetector.findDefaultDevToolsActivePorts("Windows 10", winEnv::get, temp.toString());
        assertThat(discovered).containsExactly(edgePort, chromePort);

        // Even if the first candidate is stale, resolveDefaultAttachUri skips to the reachable one.
        URI chosen = BrowserLauncher.resolveDefaultAttachUri(
                List.of(chromePort, edgePort),
                uri -> uri.getPort() == 42222);
        assertThat(chosen).isEqualTo(URI.create("ws://127.0.0.1:42222/devtools/browser/edge-live"));

        // All stale candidates fail with an actionable Chinese message.
        assertThatThrownBy(() -> BrowserLauncher.resolveDefaultAttachUri(List.of(chromePort), uri -> false))
                .isInstanceOf(RecorderException.class)
                .hasMessageContaining("未在监听");
    }

    @Test
    void resolvesLocalhostAttachEndpointViaIpv4AndNormalizesWsHost() throws Exception {
        assertThat(BrowserLauncher.loopbackHostsToTry("localhost")).startsWith("127.0.0.1");

        com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        int port = stub.getAddress().getPort();
        stub.createContext("/json/version", exchange -> {
            byte[] body = ("{\"webSocketDebuggerUrl\":\"ws://localhost:" + port + "/devtools/browser/win10\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        try {
            URI resolved = BrowserLauncher.resolveAttachUri("http://localhost:" + port);
            assertThat(resolved).isEqualTo(URI.create("ws://127.0.0.1:" + port + "/devtools/browser/win10"));
        } finally {
            stub.stop(0);
        }
    }

    @Test
    void importsWindowsBomUtf8AndUtf16HarFiles() {
        String harJson = """
                {\r
                  "log": {\r
                    "version": "1.2",\r
                    "entries": [{\r
                      "startedDateTime": "2026-11-24T12:00:00Z",\r
                      "time": 12,\r
                      "request": {"method": "GET", "url": "https://oa.corp.local/api/todo", "headers": []},\r
                      "response": {"status": 200, "statusText": "OK", "headers": [], "content": {"size": 18, "mimeType": "application/json", "text": "{\\"title\\":\\"审批单\\"}"}}\r
                    }]\r
                  }\r
                }\r
                """;
        // UTF-8 with BOM (Windows PowerShell 5.1 Set-Content -Encoding UTF8 / Notepad / Fiddler)
        byte[] utf8 = harJson.getBytes(StandardCharsets.UTF_8);
        byte[] utf8Bom = new byte[3 + utf8.length];
        utf8Bom[0] = (byte) 0xEF;
        utf8Bom[1] = (byte) 0xBB;
        utf8Bom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, utf8Bom, 3, utf8.length);
        List<RecordedEntry> fromUtf8Bom = HarImporter.parse(utf8Bom, 50);
        assertThat(fromUtf8Bom).hasSize(1);
        assertThat(fromUtf8Bom.get(0).responseBody()).contains("审批单");

        // UTF-16 LE with BOM (Windows PowerShell 5.1 default Out-File)
        byte[] utf16 = harJson.getBytes(StandardCharsets.UTF_16LE);
        byte[] utf16Bom = new byte[2 + utf16.length];
        utf16Bom[0] = (byte) 0xFF;
        utf16Bom[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, utf16Bom, 2, utf16.length);
        List<RecordedEntry> fromUtf16Bom = HarImporter.parse(utf16Bom, 50);
        assertThat(fromUtf16Bom).hasSize(1);
        assertThat(fromUtf16Bom.get(0).responseBody()).contains("审批单");
    }

    @Test
    void rejectsWindowsShortcutLnkPathsWithActionableMessage() {
        assertThatThrownBy(() -> BrowserDetector.validateExecutable("\"C:\\Users\\Alice\\Desktop\\Google Chrome.lnk\""))
                .isInstanceOf(RecorderException.class)
                .hasMessageContaining("快捷方式")
                .hasMessageContaining("属性 → 目标");
        assertThatThrownBy(() -> BrowserDetector.validateExecutable("msedge.LNK"))
                .isInstanceOf(RecorderException.class)
                .hasMessageContaining(".lnk");
    }

    @Test
    void normalizesDirectWsLocalhostAndProbesLiveDefaultDevToolsPorts() throws Exception {
        URI wsLocalhost = URI.create("ws://localhost:9222/devtools/browser/abc?v=1");
        assertThat(BrowserLauncher.preferReachableLoopbackWs(wsLocalhost, uri -> "127.0.0.1".equals(uri.getHost())))
                .isEqualTo(URI.create("ws://127.0.0.1:9222/devtools/browser/abc?v=1"));
        assertThat(BrowserLauncher.preferReachableLoopbackWs(wsLocalhost, uri -> false))
                .isEqualTo(wsLocalhost);
        URI wsIp = URI.create("ws://127.0.0.1:9222/devtools/browser/abc");
        assertThat(BrowserLauncher.preferReachableLoopbackWs(wsIp, uri -> true))
                .isEqualTo(wsIp);

        Path stale = temp.resolve("DevToolsActivePort-stale");
        Path live = temp.resolve("DevToolsActivePort-live");
        Files.writeString(stale, "45111\r\n/devtools/browser/stale\r\n", StandardCharsets.UTF_8);
        Files.writeString(live, "45222\r\n/devtools/browser/live\r\n", StandardCharsets.UTF_8);

        assertThat(BrowserLauncher.hasLiveDefaultDevToolsPort(List.of(), uri -> true)).isFalse();
        assertThat(BrowserLauncher.hasLiveDefaultDevToolsPort(List.of(stale), uri -> false)).isFalse();
        assertThat(BrowserLauncher.hasLiveDefaultDevToolsPort(List.of(stale, live), uri -> uri.getPort() == 45222))
                .isTrue();
    }
}
