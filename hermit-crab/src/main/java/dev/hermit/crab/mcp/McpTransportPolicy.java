package dev.hermit.crab.mcp;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.hermit.crab.config.HttpOrigin;
import io.modelcontextprotocol.spec.ProtocolVersions;

/**
 * Streamable HTTP admission (MCP 2025-11-25).
 *
 * <p>{@link #ALLOW_MISSING_ORIGIN} and {@link #NEGOTIATE_UNKNOWN_PROTOCOL} are the only
 * switches for the two checks that stay looser than a reject-all reading. Flip a constant
 * to require an {@code Origin} header, or to refuse an initialize {@code protocolVersion}
 * this process does not implement.
 */
final class McpTransportPolicy {
    /** CLI, desktop, and stdio clients omit Origin. Flip to false to reject that. */
    static final boolean ALLOW_MISSING_ORIGIN = true;

    /** Unknown initialize {@code protocolVersion} answers with {@link #NEGOTIATED_FALLBACK}. */
    static final boolean NEGOTIATE_UNKNOWN_PROTOCOL = true;

    static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    static final String NEGOTIATED_FALLBACK = ProtocolVersions.MCP_2025_11_25;

    static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
            ProtocolVersions.MCP_2024_11_05,
            ProtocolVersions.MCP_2025_03_26,
            ProtocolVersions.MCP_2025_06_18,
            ProtocolVersions.MCP_2025_11_25);

    private McpTransportPolicy() {
    }

    static String negotiate(String requested) {
        return negotiate(requested, NEGOTIATE_UNKNOWN_PROTOCOL);
    }

    /** {@code null} when {@code negotiateUnknown} is false and {@code requested} is not supported. */
    static String negotiate(String requested, boolean negotiateUnknown) {
        if (requested != null && SUPPORTED_PROTOCOLS.contains(requested)) {
            return requested;
        }
        return negotiateUnknown ? NEGOTIATED_FALLBACK : null;
    }

    static boolean originAllowed(List<String> headerValues, String bindHost, int port, List<String> allowlist) {
        return originAllowed(headerValues, bindHost, port, allowlist, ALLOW_MISSING_ORIGIN);
    }

    static boolean originAllowed(
            List<String> headerValues,
            String bindHost,
            int port,
            List<String> allowlist,
            boolean allowMissing
    ) {
        if (headerValues == null || headerValues.isEmpty()) {
            return allowMissing;
        }
        if (headerValues.size() != 1) {
            return false;
        }
        String normalized = HttpOrigin.normalize(headerValues.get(0));
        if (normalized == null) {
            return false;
        }
        return allowedOrigins(bindHost, port, allowlist).contains(normalized);
    }

    /**
     * A missing header keeps the version negotiated at initialize (the session already has it).
     * A present header must be one supported version; it does not have to equal the session.
     */
    static boolean protocolHeaderAllowed(List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) {
            return true;
        }
        if (headerValues.size() != 1) {
            return false;
        }
        String value = headerValues.get(0);
        if (value == null) {
            return false;
        }
        return SUPPORTED_PROTOCOLS.contains(value.trim());
    }

    static Set<String> allowedOrigins(String bindHost, int port, Collection<String> allowlist) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        if (port >= 1 && port <= 65535) {
            add(allowed, "http://localhost:" + port);
            add(allowed, "http://127.0.0.1:" + port);
            add(allowed, "http://[::1]:" + port);
            if (!wildcardBind(bindHost)) {
                add(allowed, httpBind(bindHost, port));
            }
        }
        if (allowlist != null) {
            for (String extra : allowlist) {
                add(allowed, extra);
            }
        }
        return Set.copyOf(allowed);
    }

    private static void add(Set<String> allowed, String raw) {
        String normalized = HttpOrigin.normalize(raw);
        if (normalized != null) {
            allowed.add(normalized);
        }
    }

    private static String httpBind(String host, int port) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String trimmed = host.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            return "http://" + trimmed + ":" + port;
        }
        if (trimmed.indexOf(':') >= 0) {
            return "http://[" + trimmed + "]:" + port;
        }
        return "http://" + trimmed + ":" + port;
    }

    /** {@code 0.0.0.0} and {@code ::} are not browser origins. */
    private static boolean wildcardBind(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String trimmed = host.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return "0.0.0.0".equals(lower)
                || "::".equals(lower)
                || "::0".equals(lower)
                || "0:0:0:0:0:0:0:0".equals(lower);
    }
}
