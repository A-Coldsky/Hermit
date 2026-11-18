package dev.hermit.common.http;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 包里的字面主机名。Java 的 {@link URI#getHost()} 会丢掉下划线和非 ASCII，
 * 末尾的点则留在主机上。这里把这两种名字说清楚，并把末尾的点去掉。
 */
public final class HostNames {

    public enum Problem {
        UNDERSCORE,
        NON_ASCII,
        SINGLE_LABEL_HTTPS
    }

    public record Finding(Problem problem, String host, String message) {
    }

    private HostNames() {
    }

    public static String stripTrailingDots(String host) {
        if (host == null || host.isEmpty()) {
            return host == null ? "" : host;
        }
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') {
            end--;
        }
        return host.substring(0, end);
    }

    /**
     * 主机末尾有点时返回去掉这些点之后的 URI。{@code 127.0.0.1.} 这种
     * {@link URI#getHost()} 为 null 的写法也改回来。改不了就返回原来的 URI。
     */
    public static URI withoutTrailingDot(URI uri) {
        if (uri == null || uri.getScheme() == null) {
            return uri;
        }
        String host = uri.getHost();
        String source = host != null && !host.isBlank() ? host : authorityHost(uri);
        if (source == null || !source.endsWith(".")) {
            return uri;
        }
        String stripped = stripTrailingDots(unwrap(source));
        if (stripped.isEmpty() || stripped.equals(source)) {
            return uri;
        }
        URI rebuilt = rebuild(uri, stripped, portOf(uri));
        if (rebuilt.getHost() == null || rebuilt.getHost().isBlank()) {
            return uri;
        }
        return rebuilt;
    }

    /**
     * 只看一条字面的 http(s) URL。相对路径、主机里的模板、普通主机名返回 null。
     * 下划线和非 ASCII 是错误；https 的单标签主机是警告。
     */
    public static Finding inspect(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || containsWhitespace(trimmed)) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            return null;
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            host = authorityHost(uri);
        }
        if (host == null || host.isBlank() || host.contains("{{") || host.contains("}}")) {
            return null;
        }
        String decoded = percentDecode(host);
        if (decoded.contains("{{") || decoded.contains("}}")) {
            return null;
        }
        host = stripTrailingDots(unwrap(decoded));
        if (host.isEmpty()) {
            return null;
        }
        if (host.indexOf('_') >= 0) {
            return new Finding(Problem.UNDERSCORE, host, underscoreMessage(host));
        }
        if (hasNonAscii(host)) {
            return new Finding(Problem.NON_ASCII, host, nonAsciiMessage(host));
        }
        if ("https".equals(scheme) && singleLabel(host)) {
            return new Finding(Problem.SINGLE_LABEL_HTTPS, host, singleLabelMessage(host));
        }
        return null;
    }

    public static String underscoreMessage(String host) {
        return "主机名 " + host + " 含下划线，Java 客户端无法连接；请改用 IP 或 DNS 别名";
    }

    public static String nonAsciiMessage(String host) {
        try {
            String punycode = IDN.toASCII(host);
            return "主机名 " + host + " 含非 ASCII 字符，Java 客户端无法连接；请改成 " + punycode;
        } catch (IllegalArgumentException ex) {
            return "主机名 " + host + " 含非 ASCII 字符，Java 客户端无法连接；无法转成 punycode";
        }
    }

    public static String singleLabelMessage(String host) {
        return "单标签主机名 " + host + " 不会发送 SNI，虚拟主机可能返回别的证书";
    }

    static String authorityHost(URI uri) {
        if (uri == null) {
            return null;
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            return null;
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 1) {
                return null;
            }
            return authority.substring(1, end);
        }
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && digits(authority.substring(colon + 1))) {
            authority = authority.substring(0, colon);
        }
        return authority.isEmpty() ? null : authority;
    }

    /** {@link URI#getPort()} 在主机解析失败时是 -1，这时从原始 authority 把端口取回来。 */
    private static int portOf(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            return -1;
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0 || end + 1 >= authority.length() || authority.charAt(end + 1) != ':') {
                return -1;
            }
            return parsePort(authority.substring(end + 2));
        }
        int colon = authority.lastIndexOf(':');
        if (colon < 0) {
            return -1;
        }
        return parsePort(authority.substring(colon + 1));
    }

    private static int parsePort(String text) {
        if (!digits(text) || text.length() > 5) {
            return -1;
        }
        int port = Integer.parseInt(text);
        return port <= 65535 ? port : -1;
    }

    private static URI rebuild(URI uri, String host, int port) {
        StringBuilder raw = new StringBuilder();
        raw.append(uri.getScheme()).append("://");
        if (uri.getRawUserInfo() != null) {
            raw.append(uri.getRawUserInfo()).append('@');
        }
        if (host.indexOf(':') >= 0) {
            raw.append('[').append(host).append(']');
        } else {
            raw.append(host);
        }
        if (port >= 0) {
            raw.append(':').append(port);
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty()) {
            raw.append(path);
        }
        if (uri.getRawQuery() != null) {
            raw.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            raw.append('#').append(uri.getRawFragment());
        }
        try {
            return URI.create(raw.toString());
        } catch (IllegalArgumentException ex) {
            return uri;
        }
    }

    private static String unwrap(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() >= 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean singleLabel(String host) {
        return host.indexOf('.') < 0 && host.indexOf(':') < 0 && !host.startsWith("[");
    }

    private static boolean hasNonAscii(String host) {
        return host.codePoints().anyMatch(cp -> cp > 127);
    }

    private static String percentDecode(String host) {
        if (host.indexOf('%') < 0) {
            return host;
        }
        byte[] bytes = new byte[host.length()];
        int length = 0;
        for (int i = 0; i < host.length();) {
            char current = host.charAt(i);
            if (current == '%' && i + 2 < host.length() && hex(host.charAt(i + 1)) && hex(host.charAt(i + 2))) {
                bytes[length++] = (byte) Integer.parseInt(host.substring(i + 1, i + 3), 16);
                i += 3;
                continue;
            }
            if (current > 127) {
                return host;
            }
            bytes[length++] = (byte) current;
            i++;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException ex) {
            return host;
        }
    }

    private static boolean hex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean digits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
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
