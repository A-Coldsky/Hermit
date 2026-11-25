package dev.hermit.crab.recorder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.runtime.ApiJson;

/**
 * Parses standard HAR 1.2 JSON files into {@link RecordedEntry} items so users can also run
 * externally captured HARs through the same noise filter, redactor, and MCP snapshot workflow.
 * Sensitive wire headers are scrubbed immediately during parsing.
 */
public final class HarImporter {
    public static final int MAX_HAR_BYTES = 20 * 1024 * 1024;
    public static final int MAX_BODY_CHARS = 512 * 1024;

    private HarImporter() {
    }

    public static List<RecordedEntry> parse(byte[] bytes, int maxEntries) {
        if (bytes == null || bytes.length == 0) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "HAR 文件为空。");
        }
        if (bytes.length > MAX_HAR_BYTES) {
            throw new RecorderException(413, ErrorCode.INVALID_INPUT, "HAR 文件超过 20 MiB 上限。");
        }
        JsonNode root;
        try {
            root = ApiJson.tree(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "HAR JSON 格式无效。");
        }
        JsonNode entriesNode = root.path("log").path("entries");
        if (!entriesNode.isArray()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "HAR 文件缺少 log.entries 数组。");
        }
        int limit = Math.max(1, maxEntries);
        ArrayList<RecordedEntry> out = new ArrayList<>();
        int seq = 0;
        for (JsonNode item : entriesNode) {
            if (out.size() >= limit) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            JsonNode req = item.path("request");
            String url = req.path("url").asText("");
            if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                continue;
            }
            seq++;
            String method = req.path("method").asText("GET").toUpperCase(Locale.ROOT);
            Instant startedAt = parseInstant(item.path("startedDateTime").asText(""));
            long durationMs = Math.max(0L, Math.round(item.path("time").asDouble(0.0)));
            String resourceType = item.path("_resourceType").asText("");

            List<RecordedEntry.HeaderPair> reqHeaders = parseHeaders(req.path("headers"));
            JsonNode postData = req.path("postData");
            String reqBody = null;
            boolean reqTruncated = false;
            if (postData.isObject() && postData.has("text") && postData.get("text").isTextual()) {
                String rawText = postData.get("text").asText();
                if (rawText.length() > MAX_BODY_CHARS) {
                    reqBody = rawText.substring(0, MAX_BODY_CHARS);
                    reqTruncated = true;
                } else {
                    reqBody = rawText;
                }
            } else if (postData.isObject() && postData.path("params").isArray() && !postData.path("params").isEmpty()) {
                StringBuilder form = new StringBuilder();
                for (JsonNode param : postData.path("params")) {
                    if (form.length() > 0) {
                        form.append('&');
                    }
                    form.append(param.path("name").asText("")).append('=').append(param.path("value").asText(""));
                }
                reqBody = form.toString();
            }

            JsonNode resp = item.path("response");
            int status = resp.path("status").asInt(0);
            String statusText = resp.path("statusText").asText("");
            List<RecordedEntry.HeaderPair> respHeaders = parseHeaders(resp.path("headers"));
            String contentDisposition = firstHeader(respHeaders, "content-disposition");
            JsonNode content = resp.path("content");
            String mimeType = content.path("mimeType").asText("");
            if (mimeType.isBlank()) {
                mimeType = firstHeader(respHeaders, "content-type");
            }
            String redirectTo = resp.path("redirectURL").asText("");
            if (redirectTo.isBlank()) {
                redirectTo = firstHeader(respHeaders, "location");
            }
            if (redirectTo != null && redirectTo.isBlank()) {
                redirectTo = null;
            }
            if (resourceType.isBlank()) {
                resourceType = guessResourceType(method, mimeType, url);
            }
            boolean binary = CaptureRedactor.isBinaryContent(mimeType, contentDisposition);
            long responseBytes = Math.max(0L, content.path("size").asLong(0L));
            String respBody = null;
            boolean respTruncated = false;
            if (!binary && content.has("text") && content.get("text").isTextual()) {
                String text = content.get("text").asText();
                String encoding = content.path("encoding").asText("");
                if ("base64".equalsIgnoreCase(encoding)) {
                    try {
                        text = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException ex) {
                        binary = true;
                        text = null;
                    }
                }
                if (text != null) {
                    if (responseBytes == 0L) {
                        responseBytes = text.getBytes(StandardCharsets.UTF_8).length;
                    }
                    if (text.length() > MAX_BODY_CHARS) {
                        respBody = text.substring(0, MAX_BODY_CHARS);
                        respTruncated = true;
                    } else {
                        respBody = text;
                    }
                }
            }
            String errorText = resp.has("_error") && resp.get("_error").isTextual()
                    ? resp.get("_error").asText()
                    : null;

            boolean staticAsset = CaptureRedactor.isStaticAsset(method, status, resourceType, url, mimeType);
            out.add(new RecordedEntry(
                    "e" + seq,
                    seq,
                    startedAt,
                    durationMs,
                    method,
                    url,
                    CaptureRedactor.extractOrigin(url),
                    CaptureRedactor.extractPath(url),
                    resourceType,
                    staticAsset,
                    status,
                    statusText,
                    mimeType,
                    redirectTo,
                    binary,
                    contentDisposition,
                    responseBytes,
                    errorText,
                    CaptureRedactor.scrubWireHeaders(reqHeaders),
                    reqBody,
                    reqTruncated,
                    CaptureRedactor.scrubWireHeaders(respHeaders),
                    respBody,
                    respTruncated));
        }
        return List.copyOf(out);
    }

    private static List<RecordedEntry.HeaderPair> parseHeaders(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        ArrayList<RecordedEntry.HeaderPair> headers = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            String name = item.path("name").asText("");
            if (name.isEmpty() || name.startsWith(":")) {
                continue;
            }
            headers.add(new RecordedEntry.HeaderPair(name, item.path("value").asText("")));
        }
        return headers;
    }

    private static String firstHeader(List<RecordedEntry.HeaderPair> headers, String target) {
        for (RecordedEntry.HeaderPair pair : headers) {
            if (target.equalsIgnoreCase(pair.name())) {
                return pair.value();
            }
        }
        return null;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ex) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                return Instant.EPOCH;
            }
        }
    }

    private static String guessResourceType(String method, String mimeType, String url) {
        String lower = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (lower.contains("json") || lower.contains("xml")) {
            return "XHR";
        }
        if (lower.contains("html")) {
            return "Document";
        }
        if (lower.contains("css")) {
            return "Stylesheet";
        }
        if (lower.contains("javascript")) {
            return "Script";
        }
        if (lower.startsWith("image/")) {
            return "Image";
        }
        if (lower.startsWith("font/")) {
            return "Font";
        }
        if (!"GET".equalsIgnoreCase(method)) {
            return "XHR";
        }
        return CaptureRedactor.isStaticAsset(method, 200, "", url, mimeType) ? "Other" : "Document";
    }
}
