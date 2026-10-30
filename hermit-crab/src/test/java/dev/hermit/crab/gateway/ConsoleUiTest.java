package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.hermit.crab.config.CrabConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsoleUiTest {
    private static final String PASSWORD = "ConsoleUiSecret-a91c0e77b2";
    private static final Pattern ASSET = Pattern.compile("/console/assets/([A-Za-z0-9._-]+)");
    private static final String[] PAGES = {
            "/console/",
            "/console/conches",
            "/console/conches/legacy_oa",
            "/console/apikeys",
            "/console/calls",
            "/console/reports"
    };

    @TempDir
    java.nio.file.Path temp;

    @AfterEach
    void clearRoot() {
        System.clearProperty(ConsoleUi.ROOT_PROPERTY);
    }

    @Test
    void loopbackPagesDoNotNeedTheConsoleHeader() throws Exception {
        System.setProperty(ConsoleUi.ROOT_PROPERTY, "crab-ui-fixture");
        try (TestServer server = TestServer.open(temp.resolve("pages"), PASSWORD, listen())) {
            for (String path : PAGES) {
                TestServer.Reply page = server.console("GET", path, null, false);
                assertThat(page.status()).isEqualTo(200);
                assertThat(page.header("Content-Type")).isEqualTo("text/html;charset=UTF-8");
                assertThat(page.header("Cache-Control")).isEqualTo("no-cache");
                assertThat(page.body()).contains("<div id=\"app\">");
            }
            TestServer.Reply asset = server.console("GET", "/console/assets/app.js", null, false);
            assertThat(asset.status()).isEqualTo(200);
            assertThat(asset.header("Content-Type")).isEqualTo("text/javascript");
            assertThat(asset.header("Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
            assertThat(asset.body()).contains("crab-ui-fixture");

            TestServer.Reply missing = server.console("GET", "/console/assets/missing.js", null, false);
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
            TestServer.Reply dotted = server.console("GET", "/console/assets/foo..bar.js", null, false);
            assertThat(dotted.status()).isEqualTo(404);
            assertThat(dotted.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");

            TestServer.Reply api = server.console("GET", "/console/api/conches", null, false);
            assertThat(api.status()).isEqualTo(403);
            assertThat(api.json().path("error").path("code").asText()).isEqualTo("FORBIDDEN");

            TestServer.Reply redirect = server.console("GET", "/console", null, false);
            assertThat(redirect.status()).isEqualTo(302);
            assertThat(redirect.header("Location")).isEqualTo("/console/");

            TestServer.Reply root = server.call("GET", "/", null, null, null, null);
            assertThat(root.status()).isEqualTo(404);
            assertThat(root.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void assetParentSegmentIsNotFound() throws Exception {
        try (TestServer server = TestServer.open(temp.resolve("traversal"), PASSWORD, listen())) {
            try (Socket socket = new Socket("127.0.0.1", server.server.port())) {
                socket.setSoTimeout(5000);
                String request = "GET /console/assets/../x HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(raw).contains("404");
                assertThat(raw).contains("NOT_FOUND");
            }
        }
    }

    @Test
    void missingIndexIsJsonNotFound() throws Exception {
        System.setProperty(ConsoleUi.ROOT_PROPERTY, "no-such-crab-ui/");
        try (TestServer server = TestServer.open(temp.resolve("missing"), PASSWORD, listen())) {
            TestServer.Reply page = server.console("GET", "/console/", null, false);
            assertThat(page.status()).isEqualTo(404);
            assertThat(page.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
            TestServer.Reply deep = server.console("GET", "/console/audit", null, false);
            assertThat(deep.status()).isEqualTo(404);
            assertThat(deep.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
            TestServer.Reply asset = server.console("GET", "/console/assets/app.js", null, false);
            assertThat(asset.status()).isEqualTo(404);
            assertThat(asset.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
            HttpResponse<String> shells = rawGet(server, "/console/shells/legacy_oa");
            assertThat(shells.statusCode()).isEqualTo(302);
            assertThat(shells.headers().firstValue("Location")).hasValue("/console/conches");
        }
    }

    @Test
    void deepLinksAreTheSpaAndLegacyShellsRedirect() throws Exception {
        System.setProperty(ConsoleUi.ROOT_PROPERTY, "crab-ui-fixture");
        try (TestServer server = TestServer.open(temp.resolve("fallback"), PASSWORD, listen())) {
            for (String path : new String[] {
                    "/console/audit",
                    "/console/nope",
                    "/console/nope/nested",
                    "/console/shellsfake",
                    "/console/Shells",
                    "/console/audit?from=shells"
            }) {
                HttpResponse<String> page = rawGet(server, path);
                assertThat(page.statusCode()).as(path).isEqualTo(200);
                assertThat(page.headers().firstValue("Content-Type")).as(path).hasValue("text/html;charset=UTF-8");
                assertThat(page.headers().firstValue("Cache-Control")).as(path).hasValue("no-cache");
                assertThat(page.body()).as(path).contains("<div id=\"app\">");
                assertThat(page.body()).as(path).doesNotContain(PASSWORD);
            }
            for (String path : new String[] {
                    "/console/shells",
                    "/console/shells/",
                    "/console/shells/legacy_oa",
                    "/console/shells/a/b",
                    "/console/shells?next=https://evil.example/phish"
            }) {
                HttpResponse<String> redirect = rawGet(server, path);
                assertThat(redirect.statusCode()).as(path).isEqualTo(302);
                assertThat(redirect.headers().firstValue("Location")).as(path).hasValue("/console/conches");
                assertThat(redirect.body()).as(path).doesNotContain("<div id=\"app\">").doesNotContain("evil.example");
                assertThat(redirect.body()).as(path).doesNotContain(PASSWORD);
            }

            TestServer.Reply posted = server.console("POST", "/console/nope", "{}", false);
            assertThat(posted.status()).isEqualTo(404);
            assertThat(posted.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
            TestServer.Reply postShells = server.console("POST", "/console/shells", "{}", false);
            assertThat(postShells.status()).isEqualTo(404);
            assertThat(postShells.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");

            TestServer.Reply api = server.console("GET", "/console/api/does-not-exist", null, true);
            assertThat(api.status()).isEqualTo(404);
            assertThat(api.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(api.body()).doesNotContain("<div id=\"app\">");
            TestServer.Reply apiDenied = server.console("GET", "/console/api/conches", null, false);
            assertThat(apiDenied.status()).isEqualTo(403);
            assertThat(apiDenied.json().path("error").path("code").asText()).isEqualTo("FORBIDDEN");

            TestServer.Reply nestedAsset = server.console("GET", "/console/assets/nested/app.js", null, false);
            assertThat(nestedAsset.status()).isEqualTo(404);
            assertThat(nestedAsset.json().path("error").path("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(nestedAsset.body()).doesNotContain("<div id=\"app\">");
        }
    }

    @Test
    void packagedConsoleContainsTheAppDiv() throws Exception {
        assumeTrue(ConsoleUi.class.getClassLoader().getResource("hermit-web/crab-console/index.html") != null,
                "frontend resources are not in this build");
        try (TestServer server = TestServer.open(temp.resolve("packaged"), PASSWORD, listen())) {
            TestServer.Reply page = server.console("GET", "/console/", null, false);
            assertThat(page.status()).isEqualTo(200);
            assertThat(page.body()).contains("<div id=\"app\">");
            Matcher matcher = ASSET.matcher(page.body());
            assumeTrue(matcher.find(), "built index has no hashed asset");
            TestServer.Reply asset = server.console("GET", "/console/assets/" + matcher.group(1), null, false);
            assertThat(asset.status()).isEqualTo(200);
            assertThat(asset.header("Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
        }
    }

    private static CrabConfig listen() {
        return CrabConfig.defaults().withListen("127.0.0.1", 0);
    }

    private static HttpResponse<String> rawGet(TestServer server, String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return client.send(
                HttpRequest.newBuilder(URI.create(server.base() + path)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
