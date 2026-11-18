package dev.hermit.common.mcp;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import dev.hermit.common.http.HostNames;

/**
 * 包内 URL 和 {@code allowed_hosts}。只解析字面量，不查 DNS。
 * {@code *.suffix} 匹配多出来的下级主机，不匹配 suffix 自己。
 */
public final class McpAddresses {

    public enum Problem {
        TEMPLATE,
        SYNTAX,
        SCHEME,
        USERINFO,
        FRAGMENT,
        HOST
    }

    public record Parsed(String scheme, String host, int port) {
    }

    public record ParseResult(Parsed url, Problem problem) {
        public boolean ok() {
            return problem == null;
        }

        static ParseResult bad(Problem problem) {
            return new ParseResult(null, problem);
        }
    }

    private static final Pattern HOSTNAME = Pattern.compile(
            "(?i)^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");
    private static final Pattern IPV6_CHARS = Pattern.compile("(?i)^[0-9a-f:]+$");

    private McpAddresses() {
    }

    public static ParseResult parse(String text) {
        if (text == null || text.isBlank()) {
            return ParseResult.bad(Problem.HOST);
        }
        if (text.contains("{{") || text.contains("}}") || containsWhitespace(text)) {
            return ParseResult.bad(Problem.TEMPLATE);
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException ex) {
            return ParseResult.bad(Problem.SYNTAX);
        }
        if (!uri.isAbsolute() || uri.getRawSchemeSpecificPart() == null) {
            return ParseResult.bad(Problem.SYNTAX);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return ParseResult.bad(Problem.SCHEME);
        }
        if (uri.getRawUserInfo() != null) {
            return ParseResult.bad(Problem.USERINFO);
        }
        if (uri.getRawFragment() != null) {
            return ParseResult.bad(Problem.FRAGMENT);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank() || host.endsWith(".")) {
            URI stripped = HostNames.withoutTrailingDot(uri);
            if (stripped.getHost() != null && !stripped.getHost().isBlank()) {
                host = stripped.getHost();
                if (port < 0 && stripped.getPort() >= 0) {
                    port = stripped.getPort();
                }
            }
        }
        if (host == null || host.isBlank()) {
            return ParseResult.bad(Problem.HOST);
        }
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return new ParseResult(new Parsed(scheme, normalizeHost(host), port), null);
    }

    public static boolean sameOrigin(Parsed left, Parsed right) {
        return left != null && right != null
                && left.scheme().equals(right.scheme())
                && left.host().equals(right.host())
                && left.port() == right.port();
    }

    public static boolean validPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String normalized = normalizeHost(pattern);
        if (normalized.startsWith("*.")) {
            return hostname(normalized.substring(2));
        }
        return hostname(normalized) || ipv6Literal(normalized);
    }

    public static boolean hostAllowed(String host, List<String> patterns) {
        if (host == null || patterns == null) {
            return false;
        }
        String normalized = normalizeHost(host);
        for (String pattern : patterns) {
            if (pattern != null && hostMatches(normalized, pattern)) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeHost(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        return HostNames.stripTrailingDots(value);
    }

    private static boolean hostMatches(String normalizedHost, String pattern) {
        String normalizedPattern = normalizeHost(pattern);
        if (normalizedPattern.startsWith("*.")) {
            String suffix = normalizedPattern.substring(2);
            return normalizedHost.endsWith("." + suffix);
        }
        return normalizedHost.equals(normalizedPattern);
    }

    private static boolean hostname(String value) {
        return !value.isEmpty() && value.indexOf(':') < 0 && HOSTNAME.matcher(value).matches();
    }

    private static boolean ipv6Literal(String value) {
        if (value.indexOf(':') < 0 || !IPV6_CHARS.matcher(value).matches()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return address instanceof Inet6Address;
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
