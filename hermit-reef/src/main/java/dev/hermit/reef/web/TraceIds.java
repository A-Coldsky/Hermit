package dev.hermit.reef.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    public static final String ATTRIBUTE = "dev.hermit.reef.traceId";
    public static final String MDC_KEY = "traceId";
    public static final int MAX_LENGTH = 128;

    private TraceIds() {
    }

    public static String resolve(String headerValue) {
        if (headerValue == null) {
            return newId();
        }
        String trimmed = headerValue.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH || hasControlChar(trimmed)) {
            return newId();
        }
        return trimmed;
    }

    public static String current(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        String resolved = resolve(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, resolved);
        return resolved;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    private static boolean hasControlChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
