package dev.hermit.crab.recorder;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.gateway.CrabHttpServer;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;

import static org.assertj.core.api.Assertions.assertThat;

class RecorderConsoleAndMcpTest {
    @TempDir
    Path temp;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void importHarCreateSnapshotDownloadAndReadViaMcpWithStrictGates() throws Exception {
        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("har-flow")), CrabConfig.defaults());
             CrabHttpServer server = new CrabHttpServer(runtime)) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();

            CreatedApiKey fullKey = runtime.apiKeys().create(
                    "full", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CONSOLE);
            CreatedApiKey narrowKey = runtime.apiKeys().create(
                    "narrow", new ApiKeyScope(Set.of("only_one"), Set.of(), false, false), Actors.CONSOLE);

            // Developer mode OFF: status reports devMode=false; mutating endpoints & MCP tools blocked.
            HttpResponse<String> offStatus = consoleGet(base + "/console/api/recorder/status");
            assertThat(offStatus.statusCode()).isEqualTo(200);
            assertThat(ApiJson.tree(offStatus.body()).path("data").path("devMode").asBoolean()).isFalse();

            HttpResponse<String> offImport = consolePostRaw(base + "/console/api/recorder/import-har?name=test.har", "{}");
            assertThat(offImport.statusCode()).isEqualTo(409);

            String offSession = mcpInit(base, fullKey.plaintext());
            JsonNode offTools = mcpCall(base, fullKey.plaintext(), offSession, "tools/list", null);
            assertThat(toolNames(offTools)).doesNotContain("conch_capture_list", "conch_capture_get");
            JsonNode offListCall = mcpCall(base, fullKey.plaintext(), offSession, "tools/call",
                    Map.of("name", "conch_capture_list", "arguments", Map.of()));
            assertThat(offListCall.path("result").path("isError").asBoolean()).isTrue();
            assertThat(toolErrorCode(offListCall)).isEqualTo("NOT_CONFIGURED");

            // Enable developer mode.
            runtime.debug().setEnabled(true, Actors.CONSOLE);

            // Import a raw HAR containing static noise + login redirect + secret cookie + custom secret field.
            String sampleHar = """
                    {
                      "log": {
                        "version": "1.2",
                        "entries": [
                          {
                            "startedDateTime": "2026-11-24T12:00:00Z",
                            "time": 5,
                            "_resourceType": "Image",
                            "request": { "method": "GET", "url": "https://oa.example.com/static/logo.png", "headers": [] },
                            "response": { "status": 200, "statusText": "OK", "headers": [], "content": { "size": 100, "mimeType": "image/png" } }
                          },
                          {
                            "startedDateTime": "2026-11-24T12:00:01Z",
                            "time": 25,
                            "_resourceType": "Document",
                            "request": {
                              "method": "POST",
                              "url": "https://sso.example.com/do-login",
                              "headers": [
                                {"name": "Content-Type", "value": "application/x-www-form-urlencoded"}
                              ],
                              "postData": {
                                "mimeType": "application/x-www-form-urlencoded",
                                "text": "uid=alice&mm=CustomOaPass88!"
                              }
                            },
                            "response": {
                              "status": 302,
                              "statusText": "Found",
                              "headers": [
                                {"name": "Set-Cookie", "value": "OA_SESSION=SECRET_SESS_777; Path=/; HttpOnly"},
                                {"name": "Location", "value": "https://oa.example.com/api/todos?ticket=ST-SECRET-1"}
                              ],
                              "content": { "size": 0, "mimeType": "text/html" }
                            }
                          },
                          {
                            "startedDateTime": "2026-11-24T12:00:02Z",
                            "time": 12,
                            "_resourceType": "XHR",
                            "request": {
                              "method": "GET",
                              "url": "https://oa.example.com/api/todos",
                              "headers": [
                                {"name": "Cookie", "value": "OA_SESSION=SECRET_SESS_777"},
                                {"name": "Authorization", "value": "Bearer LIVE_JWT_TOKEN_999"}
                              ]
                            },
                            "response": {
                              "status": 200,
                              "statusText": "OK",
                              "headers": [
                                {"name": "Content-Type", "value": "application/json"}
                              ],
                              "content": {
                                "size": 58,
                                "mimeType": "application/json",
                                "text": "{\\"items\\":[{\\"id\\":101,\\"title\\":\\"请假审批\\"}],\\"token\\":\\"LIVE_BODY_TOK\\"}"
                              }
                            }
                          }
                        ]
                      }
                    }
                    """;
            HttpResponse<String> imported = consolePostRaw(base + "/console/api/recorder/import-har?name=oa.har", sampleHar);
            assertThat(imported.statusCode()).isEqualTo(200);
            JsonNode importData = ApiJson.tree(imported.body()).path("data");
            assertThat(importData.path("state").asText()).isEqualTo("STOPPED");
            assertThat(importData.path("totalEntries").asInt()).isEqualTo(3);
            assertThat(importData.path("businessEntries").asInt()).isEqualTo(2);

            // Business-only list excludes /static/logo.png.
            HttpResponse<String> entriesResp = consoleGet(base + "/console/api/recorder/entries");
            assertThat(entriesResp.statusCode()).isEqualTo(200);
            JsonNode entries = ApiJson.tree(entriesResp.body()).path("data").path("entries");
            assertThat(entries).hasSize(2);
            List<String> ids = new ArrayList<>();
            entries.forEach(e -> ids.add(e.path("id").asText()));

            // Preview detail already masks wire Cookie & Authorization.
            HttpResponse<String> previewResp = consoleGet(base + "/console/api/recorder/entries/" + ids.get(1));
            assertThat(previewResp.statusCode()).isEqualTo(200);
            assertThat(previewResp.body())
                    .doesNotContain("SECRET_SESS_777")
                    .doesNotContain("LIVE_JWT_TOKEN_999")
                    .contains("OA_SESSION=***")
                    .contains("Bearer ***");

            // Confirm snapshot with extraSecret for custom field 'mm'.
            HttpResponse<String> snapResp = consolePostJson(base + "/console/api/recorder/snapshots", Map.of(
                    "title", "OA 审批流",
                    "entryIds", ids,
                    "extraSecrets", List.of("CustomOaPass88!")));
            assertThat(snapResp.statusCode()).isEqualTo(200);
            JsonNode snapData = ApiJson.tree(snapResp.body()).path("data");
            String captureId = snapData.path("id").asText();
            assertThat(captureId).startsWith("cap_");
            assertThat(snapData.path("entryCount").asInt()).isEqualTo(2);
            assertThat(snapData.path("hasRedirects").asBoolean()).isTrue();
            assertThat(textList(snapData.path("origins"))).containsExactly("https://sso.example.com", "https://oa.example.com");
            assertThat(textList(snapData.path("cookieNames"))).containsExactly("OA_SESSION");

            // Download redacted HAR 1.2 and verify zero leaked secrets.
            HttpResponse<String> harResp = consoleGet(base + "/console/api/recorder/snapshots/" + captureId + "/har");
            assertThat(harResp.statusCode()).isEqualTo(200);
            assertThat(harResp.body())
                    .doesNotContain("SECRET_SESS_777")
                    .doesNotContain("LIVE_JWT_TOKEN_999")
                    .doesNotContain("CustomOaPass88!")
                    .doesNotContain("ST-SECRET-1")
                    .doesNotContain("LIVE_BODY_TOK")
                    .contains("OA_SESSION=***")
                    .contains("请假审批");

            // Full-permission MCP key sees and reads the snapshot cleanly with UNTRUSTED marker.
            String fullSession = mcpInit(base, fullKey.plaintext());
            JsonNode fullTools = mcpCall(base, fullKey.plaintext(), fullSession, "tools/list", null);
            assertThat(toolNames(fullTools)).contains("conch_capture_list", "conch_capture_get");

            JsonNode listResult = mcpCall(base, fullKey.plaintext(), fullSession, "tools/call",
                    Map.of("name", "conch_capture_list", "arguments", Map.of()));
            JsonNode listStruct = listResult.path("result").path("structuredContent");
            assertThat(listStruct.path("total").asInt()).isEqualTo(1);
            assertThat(listStruct.path("untrusted").asBoolean()).isTrue();
            assertThat(listStruct.path("next").asText()).contains("conch_capture_get").contains("conch_guide");
            assertThat(listStruct.path("snapshots").get(0).path("captureId").asText()).isEqualTo(captureId);
            assertThat(textList(listStruct.path("snapshots").get(0).path("cookieNames"))).containsExactly("OA_SESSION");

            JsonNode getResult = mcpCall(base, fullKey.plaintext(), fullSession, "tools/call",
                    Map.of("name", "conch_capture_get", "arguments", Map.of(
                            "captureId", captureId,
                            "indices", List.of(1, 2))));
            String getJson = ApiJson.write(getResult);
            assertThat(getJson)
                    .doesNotContain("SECRET_SESS_777")
                    .doesNotContain("LIVE_JWT_TOKEN_999")
                    .doesNotContain("CustomOaPass88!")
                    .doesNotContain("ST-SECRET-1")
                    .doesNotContain("LIVE_BODY_TOK")
                    .contains("OA_SESSION=***")
                    .contains("请假审批");
            JsonNode getStruct = getResult.path("result").path("structuredContent");
            assertThat(getStruct.path("untrusted").asBoolean()).isTrue();
            assertThat(getStruct.path("next").asText()).contains("conch_guide").contains("conch_validate");

            // Narrow-scoped API key must NOT see or read capture snapshots.
            String narrowSession = mcpInit(base, narrowKey.plaintext());
            JsonNode narrowTools = mcpCall(base, narrowKey.plaintext(), narrowSession, "tools/list", null);
            assertThat(toolNames(narrowTools)).doesNotContain("conch_capture_list", "conch_capture_get");
            JsonNode narrowCall = mcpCall(base, narrowKey.plaintext(), narrowSession, "tools/call",
                    Map.of("name", "conch_capture_list", "arguments", Map.of()));
            assertThat(narrowCall.path("result").path("isError").asBoolean()).isTrue();
            assertThat(toolErrorCode(narrowCall)).isEqualTo("FORBIDDEN");

            // Console recorder routes must reject API Key headers even with X-Hermit-Console: 1.
            HttpResponse<String> bearerBlocked = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/console/api/recorder/status"))
                            .header("X-Hermit-Console", "1")
                            .header("Authorization", "Bearer " + fullKey.plaintext())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(bearerBlocked.statusCode()).isEqualTo(403);
            HttpResponse<String> keyHeaderBlocked = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/console/api/recorder/start"))
                            .header("X-Hermit-Console", "1")
                            .header("X-Hermit-Key", fullKey.plaintext())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"launch\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(keyHeaderBlocked.statusCode()).isEqualTo(403);

            // Turning developer mode OFF immediately wipes all snapshots and session buffer.
            runtime.debug().setEnabled(false, Actors.CONSOLE);
            assertThat(runtime.recorder().listSnapshots()).isEmpty();
            runtime.debug().setEnabled(true, Actors.CONSOLE);
            assertThat(runtime.recorder().listSnapshots()).isEmpty();
        }
    }

    @Test
    void capturesRealHeadlessChromeCdpTrafficEndToEnd() throws Exception {
        List<BrowserDetector.DetectedBrowser> browsers = BrowserDetector.detect();
        Assumptions.assumeFalse(browsers.isEmpty(), "No local Chrome/Chromium installed; skipping live CDP test");

        // Local mock OA: /login loads HTML + static logo + triggers form POST -> 302 Set-Cookie -> /api/me JSON.
        HttpServer mockOa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockOa.createContext("/logo.png", ex -> {
            byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            ex.getResponseHeaders().add("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
            ex.close();
        });
        mockOa.createContext("/login", ex -> {
            String html = """
                    <!doctype html><html><body>
                    <img src="/logo.png">
                    <form id="f" method="POST" action="/do-login">
                      <input type="hidden" name="csrf_token" value="LIVE_CSRF_TOKEN_9876">
                      <input type="text" name="user" value="alice">
                      <input type="password" name="mm" value="LiveChromeSecret42!">
                    </form>
                    <script>setTimeout(() => document.getElementById('f').submit(), 120);</script>
                    </body></html>
                    """;
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        mockOa.createContext("/do-login", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Set-Cookie", "OA_LIVE_SID=CHROME_COOKIE_SECRET_555; Path=/; HttpOnly");
            ex.getResponseHeaders().add("Location", "/api/me");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        mockOa.createContext("/api/me", ex -> {
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            String body = "{\"user\":\"alice\",\"cookieSeen\":" + (cookie != null && cookie.contains("OA_LIVE_SID")) + ",\"access_token\":\"LIVE_API_TOK_333\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        mockOa.start();

        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("chrome-cdp")), CrabConfig.defaults());
             CrabHttpServer server = new CrabHttpServer(runtime)) {
            server.start();
            runtime.debug().setEnabled(true, Actors.CONSOLE);
            String base = "http://127.0.0.1:" + server.port();
            String oaBase = "http://127.0.0.1:" + mockOa.getAddress().getPort();
            CreatedApiKey fullKey = runtime.apiKeys().create(
                    "cdp", new ApiKeyScope(Set.of(), Set.of(), false, true), Actors.CONSOLE);

            HttpResponse<String> started = consolePostJson(base + "/console/api/recorder/start", Map.of(
                    "mode", "launch",
                    "startUrl", oaBase + "/login",
                    "headless", true));
            assertThat(started.statusCode()).isEqualTo(200);
            assertThat(ApiJson.tree(started.body()).path("data").path("state").asText()).isEqualTo("RECORDING");

            // Wait until real Chrome completes /login -> /do-login (302) -> /api/me (200).
            JsonNode entries = null;
            long deadline = System.currentTimeMillis() + 12_000L;
            while (System.currentTimeMillis() < deadline) {
                HttpResponse<String> resp = consoleGet(base + "/console/api/recorder/entries");
                entries = ApiJson.tree(resp.body()).path("data").path("entries");
                boolean sawMe = false;
                for (JsonNode e : entries) {
                    if ("/api/me".equals(e.path("path").asText()) && e.path("status").asInt() == 200) {
                        sawMe = true;
                        break;
                    }
                }
                if (sawMe) {
                    break;
                }
                Thread.sleep(150);
            }
            assertThat(entries).isNotNull();

            HttpResponse<String> stopped = consolePostJson(base + "/console/api/recorder/stop", Map.of());
            assertThat(stopped.statusCode()).isEqualTo(200);
            assertThat(ApiJson.tree(stopped.body()).path("data").path("state").asText()).isEqualTo("STOPPED");

            HttpResponse<String> businessResp = consoleGet(base + "/console/api/recorder/entries");
            JsonNode businessEntries = ApiJson.tree(businessResp.body()).path("data").path("entries");
            List<String> paths = new ArrayList<>();
            List<String> entryIds = new ArrayList<>();
            for (JsonNode e : businessEntries) {
                paths.add(e.path("path").asText());
                entryIds.add(e.path("id").asText());
            }
            assertThat(paths).containsExactly("/login", "/do-login", "/api/me");

            HttpResponse<String> snapResp = consolePostJson(base + "/console/api/recorder/snapshots", Map.of(
                    "title", "Chrome 实录 OA",
                    "entryIds", entryIds,
                    "extraSecrets", List.of("LiveChromeSecret42!")));
            assertThat(snapResp.statusCode()).isEqualTo(200);
            JsonNode snap = ApiJson.tree(snapResp.body()).path("data");
            String captureId = snap.path("id").asText();
            assertThat(textList(snap.path("cookieNames"))).contains("OA_LIVE_SID");
            assertThat(snap.path("hasRedirects").asBoolean()).isTrue();

            String session = mcpInit(base, fullKey.plaintext());
            JsonNode detail = mcpCall(base, fullKey.plaintext(), session, "tools/call",
                    Map.of("name", "conch_capture_get", "arguments", Map.of("captureId", captureId)));
            JsonNode detailEntries = detail.path("result").path("structuredContent").path("entries");
            assertThat(detailEntries).hasSize(3);
            // Hop 2 (/do-login 302) owns Set-Cookie, hop 3 (/api/me 200) sends Cookie and gets boolean cookieSeen=true.
            JsonNode meEntry = detailEntries.get(2);
            assertThat(meEntry.path("path").asText()).isEqualTo("/api/me");
            assertThat(meEntry.path("redirectTo").isNull()).isTrue();
            JsonNode meBody = ApiJson.tree(meEntry.path("responseBody").asText());
            assertThat(meBody.path("user").asText()).isEqualTo("alice");
            assertThat(meBody.path("cookieSeen").asBoolean()).isTrue();
            assertThat(meBody.path("access_token").asText()).isEqualTo("***");

            String serialized = ApiJson.write(detail);
            assertThat(serialized)
                    .contains("OA_LIVE_SID=***")
                    .doesNotContain("CHROME_COOKIE_SECRET_555")
                    .doesNotContain("LiveChromeSecret42!")
                    .doesNotContain("LIVE_CSRF_TOKEN_9876")
                    .doesNotContain("LIVE_API_TOK_333");
        } finally {
            mockOa.stop(0);
        }
    }

    @Test
    void attachesToExistingLocalDebuggingChromeWithoutKillingIt() throws Exception {
        List<BrowserDetector.DetectedBrowser> browsers = BrowserDetector.detect();
        Assumptions.assumeFalse(browsers.isEmpty(), "No local Chrome/Chromium installed; skipping live attach CDP test");

        HttpServer mockSite = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockSite.createContext("/ping", ex -> {
            byte[] bytes = "{\"ok\":true,\"service\":\"attach-demo\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        mockSite.start();

        Path externalProfile = temp.resolve("external-chrome-profile");
        try (BrowserLauncher.LaunchedBrowser externalChrome = BrowserLauncher.launch(
                Path.of(browsers.get(0).path()), externalProfile, "about:blank", true);
             CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("chrome-attach")), CrabConfig.defaults());
             CrabHttpServer server = new CrabHttpServer(runtime)) {
            server.start();
            runtime.debug().setEnabled(true, Actors.CONSOLE);
            String base = "http://127.0.0.1:" + server.port();
            String debugHttpEndpoint = "http://127.0.0.1:" + externalChrome.wsUri().getPort();
            String pingUrl = "http://127.0.0.1:" + mockSite.getAddress().getPort() + "/ping";

            HttpResponse<String> started = consolePostJson(base + "/console/api/recorder/start", Map.of(
                    "mode", "attach",
                    "debugEndpoint", debugHttpEndpoint,
                    "startUrl", pingUrl));
            assertThat(started.statusCode()).isEqualTo(200);
            assertThat(ApiJson.tree(started.body()).path("data").path("source").asText()).isEqualTo("chrome_attach");

            long deadline = System.currentTimeMillis() + 10_000L;
            boolean sawPing = false;
            while (System.currentTimeMillis() < deadline) {
                HttpResponse<String> resp = consoleGet(base + "/console/api/recorder/entries");
                for (JsonNode e : ApiJson.tree(resp.body()).path("data").path("entries")) {
                    if ("/ping".equals(e.path("path").asText()) && e.path("status").asInt() == 200) {
                        sawPing = true;
                        break;
                    }
                }
                if (sawPing) {
                    break;
                }
                Thread.sleep(150);
            }
            assertThat(sawPing).isTrue();

            HttpResponse<String> stopped = consolePostJson(base + "/console/api/recorder/stop", Map.of());
            assertThat(stopped.statusCode()).isEqualTo(200);
            // Stopping attach mode must NOT terminate the user's external Chrome process.
            assertThat(externalChrome.isAlive()).isTrue();
        } finally {
            mockSite.stop(0);
        }
    }

    private HttpResponse<String> consoleGet(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                .header("X-Hermit-Console", "1")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> consolePostJson(String url, Map<String, Object> body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                .header("X-Hermit-Console", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ApiJson.write(body), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> consolePostRaw(String url, String raw) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                .header("X-Hermit-Console", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(raw, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String mcpInit(String base, String key) throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                """;
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).isEqualTo(200);
        return resp.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    private JsonNode mcpCall(String base, String key, String session, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> payload = params == null
                ? Map.of("jsonrpc", "2.0", "id", 2, "method", method)
                : Map.of("jsonrpc", "2.0", "id", 2, "method", method, "params", params);
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .header("Authorization", "Bearer " + key)
                .header("Mcp-Session-Id", session)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(ApiJson.write(payload), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).isEqualTo(200);
        return ApiJson.tree(resp.body());
    }

    private static String toolErrorCode(JsonNode call) {
        JsonNode structured = call.path("result").path("structuredContent");
        if (structured.has("error")) {
            return structured.path("error").path("code").asText("");
        }
        String text = call.path("result").path("content").path(0).path("text").asText("");
        if (!text.isBlank()) {
            try {
                return ApiJson.tree(text).path("error").path("code").asText("");
            } catch (RuntimeException ignored) {
                return "";
            }
        }
        return "";
    }

    private static List<String> toolNames(JsonNode listResp) {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : listResp.path("result").path("tools")) {
            names.add(tool.path("name").asText());
        }
        return names;
    }

    private static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                out.add(item.asText());
            }
        }
        return out;
    }
}
