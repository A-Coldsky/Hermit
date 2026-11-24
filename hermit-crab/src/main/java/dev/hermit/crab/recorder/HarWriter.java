package dev.hermit.crab.recorder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.runtime.ApiJson;

/**
 * Exports a confirmed, already-redacted {@link CaptureSnapshot} to standard HAR 1.2 JSON.
 */
public final class HarWriter {
    private HarWriter() {
    }

    public static byte[] write(CaptureSnapshot snapshot) {
        LinkedHashMap<String, Object> creator = new LinkedHashMap<>();
        creator.put("name", "Hermit Crab");
        creator.put("version", CrabVersion.CURRENT.toString());

        ArrayList<Map<String, Object>> entries = new ArrayList<>(snapshot.entries().size());
        for (RecordedEntry entry : snapshot.entries()) {
            entries.add(toHarEntry(entry));
        }

        LinkedHashMap<String, Object> log = new LinkedHashMap<>();
        log.put("version", "1.2");
        log.put("creator", creator);
        log.put("comment", "Redacted by Hermit Crab (" + snapshot.id() + ")");
        log.put("entries", entries);

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("log", log);
        return ApiJson.write(root).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> toHarEntry(RecordedEntry entry) {
        LinkedHashMap<String, Object> harEntry = new LinkedHashMap<>();
        harEntry.put("startedDateTime", DateTimeFormatter.ISO_INSTANT.format(entry.startedAt()));
        harEntry.put("time", Math.max(0L, entry.durationMs()));
        harEntry.put("_resourceType", entry.resourceType());

        LinkedHashMap<String, Object> request = new LinkedHashMap<>();
        request.put("method", entry.method());
        request.put("url", entry.url());
        request.put("httpVersion", "HTTP/1.1");
        request.put("cookies", requestCookies(entry.requestHeaders()));
        request.put("headers", headerMaps(entry.requestHeaders()));
        request.put("queryString", queryParams(entry.url()));
        request.put("headersSize", -1);
        request.put("bodySize", entry.requestBody() == null ? 0 : entry.requestBody().getBytes(StandardCharsets.UTF_8).length);
        if (entry.requestBody() != null) {
            LinkedHashMap<String, Object> postData = new LinkedHashMap<>();
            postData.put("mimeType", headerValue(entry.requestHeaders(), "content-type"));
            postData.put("text", entry.requestBody());
            request.put("postData", postData);
        }
        harEntry.put("request", request);

        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("status", entry.status());
        response.put("statusText", entry.statusText());
        response.put("httpVersion", "HTTP/1.1");
        response.put("cookies", responseCookies(entry.responseHeaders()));
        response.put("headers", headerMaps(entry.responseHeaders()));
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("size", entry.responseBytes());
        content.put("mimeType", entry.mimeType());
        if (entry.binary()) {
            content.put("comment", "binary content omitted");
        } else if (entry.responseBody() != null) {
            content.put("text", entry.responseBody());
        }
        response.put("content", content);
        response.put("redirectURL", entry.redirectTo() == null ? "" : entry.redirectTo());
        response.put("headersSize", -1);
        response.put("bodySize", entry.responseBytes());
        if (entry.errorText() != null && !entry.errorText().isBlank()) {
            response.put("_error", entry.errorText());
        }
        harEntry.put("response", response);

        harEntry.put("cache", Map.of());
        LinkedHashMap<String, Object> timings = new LinkedHashMap<>();
        timings.put("send", 0);
        timings.put("wait", Math.max(0L, entry.durationMs()));
        timings.put("receive", 0);
        harEntry.put("timings", timings);
        return harEntry;
    }

    private static List<Map<String, Object>> headerMaps(List<RecordedEntry.HeaderPair> headers) {
        ArrayList<Map<String, Object>> list = new ArrayList<>(headers.size());
        for (RecordedEntry.HeaderPair pair : headers) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("name", pair.name());
            map.put("value", pair.value());
            list.add(map);
        }
        return list;
    }

    private static List<Map<String, Object>> requestCookies(List<RecordedEntry.HeaderPair> headers) {
        ArrayList<Map<String, Object>> cookies = new ArrayList<>();
        for (RecordedEntry.HeaderPair header : headers) {
            if (!"cookie".equalsIgnoreCase(header.name())) {
                continue;
            }
            for (String part : header.value().split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    LinkedHashMap<String, Object> c = new LinkedHashMap<>();
                    c.put("name", trimmed.substring(0, eq).trim());
                    c.put("value", CaptureRedactor.MASK);
                    cookies.add(c);
                }
            }
        }
        return cookies;
    }

    private static List<Map<String, Object>> responseCookies(List<RecordedEntry.HeaderPair> headers) {
        ArrayList<Map<String, Object>> cookies = new ArrayList<>();
        for (RecordedEntry.HeaderPair header : headers) {
            if (!"set-cookie".equalsIgnoreCase(header.name())) {
                continue;
            }
            String value = header.value();
            int semi = value.indexOf(';');
            String first = semi >= 0 ? value.substring(0, semi) : value;
            int eq = first.indexOf('=');
            if (eq > 0) {
                LinkedHashMap<String, Object> c = new LinkedHashMap<>();
                c.put("name", first.substring(0, eq).trim());
                c.put("value", CaptureRedactor.MASK);
                cookies.add(c);
            }
        }
        return cookies;
    }

    private static List<Map<String, Object>> queryParams(String url) {
        if (url == null) {
            return List.of();
        }
        int hash = url.indexOf('#');
        String clean = hash >= 0 ? url.substring(0, hash) : url;
        int q = clean.indexOf('?');
        if (q < 0 || q + 1 >= clean.length()) {
            return List.of();
        }
        String query = clean.substring(q + 1);
        ArrayList<Map<String, Object>> params = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? decode(pair.substring(0, eq)) : decode(pair);
            String v = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("name", k);
            row.put("value", v);
            params.add(row);
        }
        return params;
    }

    private static String headerValue(List<RecordedEntry.HeaderPair> headers, String target) {
        for (RecordedEntry.HeaderPair pair : headers) {
            if (target.equalsIgnoreCase(pair.name())) {
                return pair.value();
            }
        }
        return "";
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }
}
