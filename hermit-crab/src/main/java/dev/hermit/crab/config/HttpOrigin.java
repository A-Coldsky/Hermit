package dev.hermit.crab.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Absolute http(s) origin with no userinfo, path, query, or fragment.
 * Comparison ignores scheme and host case and default ports. IPv6 loopback literals
 * become {@code [::1]}. Hostnames are not resolved.
 */
public final class HttpOrigin {
    private HttpOrigin() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.indexOf('@') >= 0 || value.indexOf('\\') >= 0) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) <= ' ') {
                return null;
            }
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            return null;
        }
        if (!uri.isAbsolute()) {
            return null;
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return null;
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty()) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        String host = canonicalHost(uri.getHost());
        if (host == null) {
            return null;
        }
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        if (defaultPort) {
            return scheme + "://" + shown;
        }
        return scheme + "://" + shown + ":" + port;
    }

    private static String canonicalHost(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]") && lower.length() > 2) {
            lower = lower.substring(1, lower.length() - 1);
        }
        if (lower.isEmpty() || lower.indexOf('[') >= 0 || lower.indexOf(']') >= 0 || lower.endsWith(".")) {
            return null;
        }
        if (lower.indexOf(':') >= 0) {
            if (!numericAddress(lower)) {
                return null;
            }
            try {
                if (InetAddress.getByName(lower).isLoopbackAddress()) {
                    return "::1";
                }
            } catch (UnknownHostException ex) {
                return null;
            }
        }
        return lower;
    }

    /** Hex, digits, colons, and dots only, so {@link InetAddress#getByName} cannot leave the literal. */
    private static boolean numericAddress(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == ':' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
