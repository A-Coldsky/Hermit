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
}
