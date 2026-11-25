package dev.hermit.crab.recorder;

import java.time.Instant;
import java.util.List;

/**
 * Immutable HTTP round-trip captured via Chrome DevTools Protocol or imported from HAR 1.2.
 * Sensitive headers (Cookie, Set-Cookie, Authorization, etc.) are scrubbed immediately on capture.
 */
public record RecordedEntry(
        String id,
        int index,
        Instant startedAt,
        long durationMs,
        String method,
        String url,
        String origin,
        String path,
        String resourceType,
        boolean staticAsset,
        int status,
        String statusText,
        String mimeType,
        String redirectTo,
        boolean binary,
        String contentDisposition,
        long responseBytes,
        String errorText,
        List<HeaderPair> requestHeaders,
        String requestBody,
        boolean requestBodyTruncated,
        List<HeaderPair> responseHeaders,
        String responseBody,
        boolean responseBodyTruncated
) {
    public RecordedEntry {
        id = id == null ? "" : id;
        startedAt = startedAt == null ? Instant.EPOCH : startedAt;
        method = method == null ? "GET" : method;
        url = url == null ? "" : url;
        origin = origin == null ? "" : origin;
        path = path == null ? "" : path;
        resourceType = resourceType == null ? "Other" : resourceType;
        statusText = statusText == null ? "" : statusText;
        mimeType = mimeType == null ? "" : mimeType;
        requestHeaders = requestHeaders == null ? List.of() : List.copyOf(requestHeaders);
        responseHeaders = responseHeaders == null ? List.of() : List.copyOf(responseHeaders);
    }

    public record HeaderPair(String name, String value) {
        public HeaderPair {
            name = name == null ? "" : name;
            value = value == null ? "" : value;
        }
    }
}
