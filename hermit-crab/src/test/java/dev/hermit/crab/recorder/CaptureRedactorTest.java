package dev.hermit.crab.recorder;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import dev.hermit.crab.runtime.ApiJson;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureRedactorTest {

    @Test
    void redactsHeadersCookiesUrlFormJsonMultipartHtmlAndExtraSecrets() {
        String customPass = "MyOaPass#88";
        String gbkEncoded = URLEncoder.encode(customPass, Charset.forName("GBK"));
        RecordedEntry raw = new RecordedEntry(
                "e1",
                1,
                Instant.parse("2026-11-24T12:00:00Z"),
                42L,
                "POST",
                "https://sso.corp.local/auth;jsessionid=RAW_SID_123?ticket=ST-98765&user=alice&custom=" + gbkEncoded,
                "https://sso.corp.local",
                "/auth;jsessionid=RAW_SID_123",
                "XHR",
                false,
                302,
                "Found",
                "application/json; charset=utf-8",
                "https://oa.corp.local/cb?code=OAUTH_CODE_555&state=ok",
                false,
                null,
                128L,
                null,
                List.of(
                        new RecordedEntry.HeaderPair("Cookie", "JSESSIONID=LIVE_SESS_999; CASTGC=TGT_SECRET_111"),
                        new RecordedEntry.HeaderPair("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.secret.sig"),
                        new RecordedEntry.HeaderPair("X-CSRF-Token", "csrf_live_value"),
                        new RecordedEntry.HeaderPair("Content-Type", "application/x-www-form-urlencoded"),
                        new RecordedEntry.HeaderPair("User-Agent", "Chrome")),
                "username=alice&password=PlainPassword123&mm=" + gbkEncoded + "&keep=hello",
                false,
                List.of(
                        new RecordedEntry.HeaderPair("Set-Cookie", "JSESSIONID=NEW_COOKIE_VAL; Path=/; HttpOnly; Secure"),
                        new RecordedEntry.HeaderPair("Location", "https://oa.corp.local/cb?code=OAUTH_CODE_555&state=ok"),
                        new RecordedEntry.HeaderPair("Content-Type", "application/json")),
                "{\"user\":\"alice\",\"access_token\":\"LIVE_ACCESS_777\",\"note\":\"echo " + customPass + "\"}",
                false);

        RecordedEntry redacted = CaptureRedactor.redactEntry(raw, 1, Set.of(customPass), 4096);

        // URL path session param, sensitive query param, and extraSecret GBK encoding all masked.
        assertThat(redacted.url())
                .contains(";jsessionid=***")
                .contains("ticket=***")
                .contains("user=alice")
                .doesNotContain("RAW_SID_123")
                .doesNotContain("ST-98765")
                .doesNotContain(gbkEncoded)
                .doesNotContain(customPass);
        assertThat(redacted.redirectTo())
                .contains("code=***")
                .contains("state=ok")
                .doesNotContain("OAUTH_CODE_555");

        // Cookie / Set-Cookie keep names and attributes while masking live values.
        assertThat(header(redacted.requestHeaders(), "Cookie"))
                .isEqualTo("JSESSIONID=***; CASTGC=***");
        assertThat(header(redacted.requestHeaders(), "Authorization"))
                .isEqualTo("Bearer ***");
        assertThat(header(redacted.requestHeaders(), "X-CSRF-Token"))
                .isEqualTo("***");
        assertThat(header(redacted.responseHeaders(), "Set-Cookie"))
                .isEqualTo("JSESSIONID=***; Path=/; HttpOnly; Secure");

        // Cookie name extraction works on redacted entries without ever leaking values.
        assertThat(CaptureRedactor.extractCookieNames(List.of(redacted)))
                .containsExactly("JSESSIONID", "CASTGC");
        assertThat(CaptureRedactor.extractOrigins(List.of(redacted)))
                .containsExactly("https://sso.corp.local", "https://oa.corp.local");

        // Form request body redacts standard password key AND custom field via extraSecrets.
        assertThat(redacted.requestBody())
                .contains("username=alice")
                .contains("password=***")
                .contains("keep=hello")
                .doesNotContain("PlainPassword123")
                .doesNotContain(gbkEncoded)
                .doesNotContain(customPass);

        // JSON response body keeps keys and non-sensitive fields while masking token + extraSecret.
        JsonNode respJson = ApiJson.tree(redacted.responseBody());
        assertThat(respJson.path("user").asText()).isEqualTo("alice");
        assertThat(respJson.path("access_token").asText()).isEqualTo("***");
        assertThat(respJson.path("note").asText()).doesNotContain(customPass).contains("***");
    }

    @Test
    void redactsHtmlHiddenSensitiveInputsAndMultipartUploads() {
        RecordedEntry htmlEntry = new RecordedEntry(
                "e2", 1, Instant.EPOCH, 10L, "GET", "https://oa.corp.local/login",
                "https://oa.corp.local", "/login", "Document", false, 200, "OK", "text/html",
                null, false, null, 200L, null, List.of(), null, false, List.of(),
                "<form><input type=\"hidden\" name=\"csrf_token\" value=\"CSRF-SECRET-888\">"
                        + "<input type=\"text\" name=\"dept\" value=\"finance\"></form>",
                false);
        RecordedEntry cleanHtml = CaptureRedactor.redactEntry(htmlEntry, 1, Set.of(), 4096);
        assertThat(cleanHtml.responseBody())
                .contains("name=\"csrf_token\" value=\"***\"")
                .contains("name=\"dept\" value=\"finance\"")
                .doesNotContain("CSRF-SECRET-888");

        String multipart = "--BOUND\r\n"
                + "Content-Disposition: form-data; name=\"password\"\r\n\r\n"
                + "TopSecretPwd\r\n"
                + "--BOUND\r\n"
                + "Content-Disposition: form-data; name=\"attachment\"; filename=\"report.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n"
                + "%PDF-1.7-RAW-BYTES\r\n"
                + "--BOUND--\r\n";
        RecordedEntry uploadEntry = new RecordedEntry(
                "e3", 1, Instant.EPOCH, 10L, "POST", "https://oa.corp.local/upload",
                "https://oa.corp.local", "/upload", "Fetch", false, 200, "OK", "application/json",
                null, false, null, 20L, null,
                List.of(new RecordedEntry.HeaderPair("Content-Type", "multipart/form-data; boundary=BOUND")),
                multipart, false, List.of(), "{\"ok\":true}", false);
        RecordedEntry cleanUpload = CaptureRedactor.redactEntry(uploadEntry, 1, Set.of(), 4096);
        assertThat(cleanUpload.requestBody())
                .contains("name=\"password\"")
                .contains("filename=\"report.pdf\"")
                .contains("[BINARY FILE CONTENT OMITTED")
                .doesNotContain("TopSecretPwd")
                .doesNotContain("%PDF-1.7-RAW-BYTES");
    }

    @Test
    void classifiesStaticAssetsAndRoundTripsHarSafely() {
        assertThat(CaptureRedactor.isStaticAsset("GET", 200, "Image", "https://oa.local/logo.png", "image/png")).isTrue();
        assertThat(CaptureRedactor.isStaticAsset("GET", 200, "Image", "https://oa.local/sso/captcha.jpg?t=1", "image/jpeg")).isFalse();
        assertThat(CaptureRedactor.isStaticAsset("GET", 200, "Image", "https://oa.local/kaptcha", "image/png")).isFalse();
        assertThat(CaptureRedactor.isStaticAsset("GET", 200, "Image", "https://oa.local/verifyCode?r=9", "image/gif")).isFalse();
        assertThat(CaptureRedactor.isStaticAsset("GET", 200, "Script", "https://oa.local/captcha-sdk.js", "application/javascript")).isTrue();
        assertThat(CaptureRedactor.isStaticAsset("GET", 200, "Stylesheet", "https://oa.local/captcha.css", "text/css")).isTrue();
        assertThat(CaptureRedactor.isStaticAsset("GET", 200, "Stylesheet", "https://oa.local/app.css", "text/css")).isTrue();
        assertThat(CaptureRedactor.isStaticAsset("GET", 200, "Script", "https://oa.local/app.js", "application/javascript")).isTrue();
        assertThat(CaptureRedactor.isStaticAsset("POST", 200, "XHR", "https://oa.local/api/login", "application/json")).isFalse();
        assertThat(CaptureRedactor.isStaticAsset("GET", 302, "Document", "https://oa.local/sso", "")).isFalse();
        assertThat(CaptureRedactor.isBinaryContent("application/pdf", "attachment; filename=\"a.pdf\"")).isTrue();
        assertThat(CaptureRedactor.isBinaryContent("application/json; charset=utf-8", null)).isFalse();

        String rawHar = """
                {
                  "log": {
                    "version": "1.2",
                    "entries": [
                      {
                        "startedDateTime": "2026-11-24T12:00:00Z",
                        "time": 18,
                        "_resourceType": "XHR",
                        "request": {
                          "method": "POST",
                          "url": "https://oa.corp.local/api/login",
                          "headers": [
                            {"name": "Cookie", "value": "SID=RAW_HAR_COOKIE"},
                            {"name": "Authorization", "value": "Bearer RAW_HAR_BEARER"},
                            {"name": "Content-Type", "value": "application/json"}
                          ],
                          "postData": {
                            "mimeType": "application/json",
                            "text": "{\\"user\\":\\"bob\\",\\"password\\":\\"HarSecret99\\",\\"code\\":\\"654321\\"}"
                          }
                        },
                        "response": {
                          "status": 200,
                          "statusText": "OK",
                          "headers": [
                            {"name": "Set-Cookie", "value": "OA_TOKEN=LIVE_OA_TOKEN; Path=/; HttpOnly"}
                          ],
                          "content": {
                            "size": 44,
                            "mimeType": "application/json",
                            "text": "{\\"code\\":0,\\"ok\\":true,\\"token\\":\\"LIVE_RESP_TOK\\"}"
                          }
                        }
                      }
                    ]
                  }
                }
                """;
        List<RecordedEntry> parsed = HarImporter.parse(rawHar.getBytes(StandardCharsets.UTF_8), 100);
        assertThat(parsed).hasSize(1);
        // Wire headers scrubbed immediately during HAR import!
        assertThat(header(parsed.get(0).requestHeaders(), "Cookie")).isEqualTo("SID=***");
        assertThat(header(parsed.get(0).requestHeaders(), "Authorization")).isEqualTo("Bearer ***");
        assertThat(header(parsed.get(0).responseHeaders(), "Set-Cookie")).isEqualTo("OA_TOKEN=***; Path=/; HttpOnly");

        RecordedEntry redacted = CaptureRedactor.redactEntry(parsed.get(0), 1, Set.of(), 4096);
        assertThat(redacted.requestBody()).contains("\"code\":\"***\"").doesNotContain("654321");
        assertThat(redacted.responseBody()).contains("\"code\":0");
        CaptureSnapshot snap = new CaptureSnapshot(
                "cap_12345678", "Test", Instant.parse("2026-11-24T12:00:00Z"), "har_import",
                CaptureRedactor.extractOrigins(List.of(redacted)),
                CaptureRedactor.extractCookieNames(List.of(redacted)),
                false, false, List.of(redacted));
        String exportedHar = new String(HarWriter.write(snap), StandardCharsets.UTF_8);
        assertThat(exportedHar)
                .doesNotContain("RAW_HAR_COOKIE")
                .doesNotContain("RAW_HAR_BEARER")
                .doesNotContain("HarSecret99")
                .doesNotContain("654321")
                .doesNotContain("LIVE_OA_TOKEN")
                .doesNotContain("LIVE_RESP_TOK")
                .contains("OA_TOKEN=***")
                .contains("\"version\":\"1.2\"");
    }

    private static String header(List<RecordedEntry.HeaderPair> headers, String name) {
        return headers.stream()
                .filter(h -> h.name().equalsIgnoreCase(name))
                .map(RecordedEntry.HeaderPair::value)
                .findFirst()
                .orElse("");
    }
}
