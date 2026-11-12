package dev.hermit.crab.gateway;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import dev.hermit.crab.config.HttpOrigin;
import jakarta.servlet.http.HttpServletRequest;

/**
 * DNS rebinding guard for {@code /console/**}. The socket check in {@link Loopback} cannot tell a
 * rebound page from a local one: both connect to 127.0.0.1. The browser still sends the name it
 * resolved, so {@code Host} (and {@code Origin} when present) must name the loopback interface.
 *
 * <p>Ports are not compared. An SSH tunnel such as {@code localhost:9000 -> 127.0.0.1:7777}
 * keeps working; a rebound name never matches regardless of port. A request without
 * {@code Host} (HTTP/1.0 tools) passes; browsers always send it.
 */
final class ConsoleHosts {
    /** Same sentence for a foreign Host and a foreign Origin. */
    static final String DENIED = "控制台只接受 localhost、127.0.0.1 或 [::1] 作为访问地址。";

    private ConsoleHosts() {
    }

    static boolean allowed(HttpServletRequest request) {
        List<String> host = values(request, "Host");
        List<String> origin = values(request, "Origin");
        if (host.size() > 1 || origin.size() > 1) {
            return false;
        }
        if (!host.isEmpty() && !hostAllowed(host.get(0))) {
            return false;
        }
        return origin.isEmpty() || originAllowed(origin.get(0));
    }

    /** {@code Host} header value, with or without a port. */
    static boolean hostAllowed(String header) {
        if (header == null) {
            return false;
        }
        String value = header.trim().toLowerCase(Locale.ROOT);
        String name;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0 || !portOrEmpty(value.substring(end + 1))) {
                return false;
            }
            name = value.substring(1, end);
        } else {
            int colon = value.indexOf(':');
            if (colon >= 0 && (value.indexOf(':', colon + 1) >= 0 || !portOrEmpty(value.substring(colon)))) {
                return false;
            }
            name = colon < 0 ? value : value.substring(0, colon);
        }
        return loopbackName(name);
    }

    /** An http(s) origin whose host is loopback. {@code null} (opaque) and other schemes are refused. */
    static boolean originAllowed(String header) {
        String normalized = HttpOrigin.normalize(header);
        if (normalized == null) {
            return false;
        }
        String rest = normalized.substring(normalized.indexOf("://") + 3);
        return hostAllowed(rest);
    }

    private static boolean loopbackName(String name) {
        return "localhost".equals(name)
                || "127.0.0.1".equals(name)
                || "::1".equals(name)
                || "0:0:0:0:0:0:0:1".equals(name);
    }

    private static boolean portOrEmpty(String suffix) {
        if (suffix.isEmpty()) {
            return true;
        }
        if (suffix.charAt(0) != ':' || suffix.length() < 2 || suffix.length() > 6) {
            return false;
        }
        for (int i = 1; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static List<String> values(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }
}
