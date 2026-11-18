package dev.hermit.crab.tls;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.hermit.common.http.HostNames;

import javax.net.ssl.SSLException;

/**
 * 把出站连接失败收成稳定的种类和给用户的句子。判定沿 cause 链走，不只看最外层消息。
 * 错误码仍是 {@code UPSTREAM_ERROR}；超时仍由原来的分支处理。
 */
public final class TransportFailure {

    private static final String PKIX_CLASS = "sun.security.provider.certpath.SunCertPathBuilderException";
    private static final String UNRESOLVED = "java.nio.channels.UnresolvedAddressException";
    private static final String HOST_CONNECT = "org.apache.hc.client5.http.HttpHostConnectException";
    /**
     * Apache 把解析到的地址写成 {@code 主机/地址}，放在方括号里。
     * 不把 URL 里的 {@code //} 当成这种写法，IPv6 先于单独的一段数字。
     */
    private static final Pattern EMBEDDED_PEER = Pattern.compile(
            "(?<![/:])(?:[A-Za-z0-9._-]+|\\[[^\\]]+\\])/((?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F]{0,4}|(?:\\d{1,3}\\.){3}\\d{1,3})");
    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    private TransportFailure() {
    }

    public enum Kind {
        DNS,
        REFUSED,
        TIMEOUT,
        TLS_UNTRUSTED,
        TLS_INTERMEDIATE,
        TLS_HOSTNAME,
        TLS_EXPIRED,
        TLS_PROTOCOL,
        TLS_CIPHER,
        TLS_CERT_ALGORITHM,
        TLS_SNI,
        TLS_OTHER,
        NO_RESPONSE,
        HEADER_LIMIT,
        OTHER
    }

    public record View(Kind kind, String message, String host, int port, String cause, List<Map<String, Object>> chain) {
        public View {
            chain = chain == null ? List.of() : List.copyOf(chain);
            cause = cause == null ? "" : cause;
            message = message == null ? "" : message;
        }

        public Map<String, Object> fields() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind.name());
            map.put("host", host == null ? "" : host);
            map.put("port", port);
            map.put("cause", cause);
            map.put("chain", chain);
            return map;
        }
    }

    public static boolean isTimeout(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String name = current.getClass().getName();
            if (name.endsWith("TimeoutException") || name.contains("Timeout")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDns(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (current instanceof UnknownHostException) {
                if ("hermit-rejected-address".equals(current.getMessage())) {
                    continue;
                }
                return true;
            }
            if (UNRESOLVED.equals(current.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    public static int portOf(URI uri) {
        if (uri == null) {
            return -1;
        }
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        return -1;
    }

    /** 分类并按 host:port 加种类打限流日志。超时不打这条日志。 */
    public static View observe(Throwable failure, String host, int port) {
        View view = classify(failure, host, port);
        if (view.kind() != Kind.TIMEOUT) {
            TransportLog.warn(view, failure);
        }
        TlsMemory.note(failure, view);
        return view;
    }

    public static View classify(Throwable failure, String host, int port) {
        HandshakeRecord record = HandshakeRecord.find(failure);
        String resolvedHost = firstText(host, record == null ? null : record.peerHost());
        int resolvedPort = port >= 0 ? port : (record == null ? -1 : record.peerPort());
        String detail = clip(rootMessage(failure));
        String cause = causeField(failure);
        List<Map<String, Object>> chain = chainFields(record);
        Kind kind = kindOf(failure, record);
        String message = message(kind, resolvedHost, resolvedPort, detail, record, failure);
        return new View(kind, message, resolvedHost, resolvedPort, cause, chain);
    }

    private static Kind kindOf(Throwable failure, HandshakeRecord record) {
        if (failure == null) {
            return Kind.OTHER;
        }
        if (isTimeout(failure)) {
            return Kind.TIMEOUT;
        }
        if (isDns(failure)) {
            return Kind.DNS;
        }
        if (hostname(failure)) {
            return Kind.TLS_HOSTNAME;
        }
        if (expired(failure)) {
            return Kind.TLS_EXPIRED;
        }
        if (certAlgorithm(failure)) {
            return Kind.TLS_CERT_ALGORITHM;
        }
        if (pkix(failure)) {
            return missingIntermediate(record) ? Kind.TLS_INTERMEDIATE : Kind.TLS_UNTRUSTED;
        }
        if (mentions(failure, "unrecognized_name")) {
            return Kind.TLS_SNI;
        }
        if (mentions(failure, "protocol_version") || mentions(failure, "no appropriate protocol")) {
            return Kind.TLS_PROTOCOL;
        }
        if (cipher(failure)) {
            return Kind.TLS_CIPHER;
        }
        if (ssl(failure)) {
            return Kind.TLS_OTHER;
        }
        if (named(failure, "NoHttpResponseException") || mentions(failure, "the target server failed to respond")) {
            return Kind.NO_RESPONSE;
        }
        if (named(failure, "MessageConstraintException")
                || mentions(failure, "maximum line length")
                || mentions(failure, "maximum header count")) {
            return Kind.HEADER_LIMIT;
        }
        if (refused(failure)) {
            return Kind.REFUSED;
        }
        return Kind.OTHER;
    }

    /** 证书算法或密钥长度。DH 约束算套件，不算这一种。 */
    private static boolean certAlgorithm(Throwable failure) {
        if (dhConstraint(failure)) {
            return false;
        }
        return mentions(failure, "keysize") || mentions(failure, "algorithm constraints");
    }

    /** {@code handshake_failure}，或 DH 不满足算法约束。 */
    private static boolean cipher(Throwable failure) {
        return dhConstraint(failure) || mentions(failure, "handshake_failure");
    }

    /**
     * DH 约束可以确定是套件。{@code handshake_failure} 还可能是要客户端证书或曲线不匹配。
     */
    private static String cipherMessage(Throwable failure) {
        if (dhConstraint(failure)) {
            return "对方只提供已禁用的加密套件（如 3DES、RC4、静态 RSA、弱 DH）。";
        }
        return "TLS 握手失败，常见原因是对方只提供已禁用的加密套件（如 3DES、RC4、静态 RSA、弱 DH）。";
    }

    private static boolean dhConstraint(Throwable failure) {
        for (Throwable current : chain(failure)) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("diffie") || lower.contains("dh serverkeyexchange") || lower.contains("dh key")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 只有对端把颁发者证书也发来了，才能看见它的上级。
     * 只拿到 leaf 时归为不受信任，提示导入 leaf 的颁发者。
     */
    private static boolean missingIntermediate(HandshakeRecord record) {
        if (record == null || record.chain().size() < 2) {
            return false;
        }
        TrustCert issuer = record.chain().get(1);
        if (issuer.subject().equals(issuer.issuer())) {
            return false;
        }
        return record.anchorSubjects().contains(issuer.issuer());
    }

    private static boolean hostname(Throwable failure) {
        for (Throwable current : chain(failure)) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("subject alternative") || message.contains("No name matching")) {
                return true;
            }
        }
        return false;
    }

    private static boolean expired(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (current instanceof CertificateExpiredException || current instanceof CertificateNotYetValidException) {
                return true;
            }
        }
        return false;
    }

    private static boolean notYetValid(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (current instanceof CertificateNotYetValidException) {
                return true;
            }
        }
        return false;
    }

    private static boolean pkix(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (PKIX_CLASS.equals(current.getClass().getName())) {
                return true;
            }
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            if (message.contains("unable to find valid certification path")
                    || message.contains("PKIX path building failed")) {
                return true;
            }
        }
        return false;
    }

    private static boolean ssl(Throwable failure) {
        for (Throwable current : chain(failure)) {
            if (current instanceof SSLException || current instanceof CertificateException) {
                return true;
            }
        }
        return false;
    }

    private static boolean refused(Throwable failure) {
        for (Throwable current : chain(failure)) {
            boolean type = current instanceof ConnectException || HOST_CONNECT.equals(current.getClass().getName());
            if (!type) {
                continue;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("refused")) {
                return true;
            }
        }
        return false;
    }

    private static String message(
            Kind kind,
            String host,
            int port,
            String cause,
            HandshakeRecord record,
            Throwable failure
    ) {
        String name = host == null || host.isBlank() ? "" : host;
        return switch (kind) {
            case DNS -> name.isEmpty() ? "无法解析上游地址" : "无法解析上游地址 " + name;
            case REFUSED -> refusedMessage(name, port, failure);
            case TIMEOUT -> "上游超时";
            case TLS_UNTRUSTED -> "上游证书不受信任：颁发者 " + issuer(record)
                    + "。可在控制台「HTTPS 信任」导入这个颁发者的根证书，或信任这张证书";
            case TLS_INTERMEDIATE -> "上游没有发送中间证书：请把颁发者 " + issuer(record) + " 的证书导入「HTTPS 信任」";
            case TLS_HOSTNAME -> name.isEmpty() ? "上游证书与主机名不符" : "上游证书与主机名 " + name + " 不符";
            case TLS_EXPIRED -> expiryMessage(record, failure);
            case TLS_PROTOCOL -> "对方只支持 TLS 1.0/1.1 等旧协议，Crab 默认不连。";
            case TLS_CIPHER -> cipherMessage(failure);
            case TLS_CERT_ALGORITHM -> "对方证书使用被禁的算法或密钥过短（如 RSA 512），需要换证书。";
            case TLS_SNI -> "对方拒绝了 SNI 主机名，可改用 IP 或单标签名。";
            case TLS_OTHER -> "与上游的 TLS 握手失败：" + (cause.isEmpty() ? root(failure).getClass().getSimpleName() : cause);
            case NO_RESPONSE -> "上游没有返回 HTTP 响应。";
            case HEADER_LIMIT -> "上游响应头超过长度或个数上限。";
            case OTHER -> "上游连接失败：" + simpleName(failure);
        };
    }

    private static String refusedMessage(String host, int port, Throwable failure) {
        String target = endpoint(host, port);
        String peers = peersOf(failure, host);
        if (peers != null && !peers.isBlank()) {
            if (target.isEmpty()) {
                return "上游拒绝连接（对端 " + peers + "）";
            }
            return "上游拒绝连接（" + target + "，对端 " + peers + "）";
        }
        return refusedTarget(target);
    }

    private static String refusedTarget(String target) {
        if (target.isEmpty()) {
            return "上游拒绝连接";
        }
        return "上游拒绝连接（" + target + "）";
    }

    private static String endpoint(String host, int port) {
        if (host == null || host.isBlank()) {
            return "";
        }
        if (port < 0) {
            return host;
        }
        return host + ":" + port;
    }

    /**
     * 异常原文里的对端地址优先。没有时再解析主机名，这样 {@code localhost} 先落到
     * {@code ::1} 时用户能看见实际地址。字面 IP 不再解析。
     */
    private static String peersOf(Throwable failure, String host) {
        String embedded = embeddedPeers(failure, host);
        if (embedded != null) {
            return embedded;
        }
        return resolvedPeers(host);
    }

    private static String embeddedPeers(Throwable failure, String host) {
        LinkedHashSet<String> peers = new LinkedHashSet<>();
        for (Throwable current : chain(failure)) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            Matcher matcher = EMBEDDED_PEER.matcher(message);
            while (matcher.find()) {
                String peer = compactAddress(matcher.group(1));
                if (!peer.isBlank() && !sameEndpoint(peer, host)) {
                    peers.add(peer);
                }
            }
        }
        return peers.isEmpty() ? null : String.join("、", peers);
    }

    private static boolean sameEndpoint(String peer, String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String name = HostNames.stripTrailingDots(host.trim());
        if (name.startsWith("[") && name.endsWith("]") && name.length() >= 2) {
            name = name.substring(1, name.length() - 1);
        }
        return peer.equalsIgnoreCase(name);
    }

    private static String resolvedPeers(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String name = HostNames.stripTrailingDots(host.trim());
        if (name.isEmpty() || name.indexOf(':') >= 0 || IPV4.matcher(name).matches()) {
            return null;
        }
        try {
            InetAddress[] all = InetAddress.getAllByName(name);
            LinkedHashSet<String> peers = new LinkedHashSet<>();
            for (InetAddress address : all) {
                peers.add(compactAddress(address.getHostAddress()));
            }
            if (peers.isEmpty() || (peers.size() == 1 && peers.iterator().next().equalsIgnoreCase(name))) {
                return null;
            }
            return String.join("、", peers);
        } catch (Exception ex) {
            return null;
        }
    }

    static String compactAddress(String hostAddress) {
        if (hostAddress == null || hostAddress.isBlank()) {
            return "";
        }
        int zone = hostAddress.indexOf('%');
        String text = zone < 0 ? hostAddress : hostAddress.substring(0, zone);
        if (text.indexOf(':') < 0) {
            return text;
        }
        String[] parts = text.split(":", -1);
        if (parts.length != 8) {
            return text;
        }
        int bestStart = -1;
        int bestLength = 0;
        int index = 0;
        while (index < parts.length) {
            if (!zero(parts[index])) {
                index++;
                continue;
            }
            int end = index;
            while (end < parts.length && zero(parts[end])) {
                end++;
            }
            if (end - index > bestLength) {
                bestStart = index;
                bestLength = end - index;
            }
            index = end;
        }
        if (bestLength < 2) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (cursor < parts.length) {
            if (cursor == bestStart) {
                out.append("::");
                cursor += bestLength;
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(parts[cursor].isEmpty() ? "0" : parts[cursor]);
            cursor++;
        }
        return out.toString();
    }

    private static boolean zero(String part) {
        return part == null || part.isEmpty() || "0".equals(part);
    }

    private static boolean mentions(Throwable failure, String needle) {
        String expected = needle.toLowerCase(Locale.ROOT);
        for (Throwable current : chain(failure)) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean named(Throwable failure, String simpleName) {
        for (Throwable current : chain(failure)) {
            if (simpleName.equals(current.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static String causeField(Throwable failure) {
        if (failure == null) {
            return "";
        }
        Throwable root = root(failure);
        String name = root.getClass().getSimpleName();
        if (name == null || name.isBlank()) {
            name = root.getClass().getName();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return clip(name);
        }
        return clip(name + ": " + message.strip());
    }

    private static String expiryMessage(HandshakeRecord record, Throwable failure) {
        boolean pending = notYetValid(failure);
        String day = "";
        if (record != null && !record.chain().isEmpty()) {
            TrustCert leaf = record.chain().get(0);
            day = pending ? leaf.dayNotBefore() : leaf.dayNotAfter();
        }
        if (day.isEmpty()) {
            return pending ? "上游证书尚未生效" : "上游证书已过期";
        }
        return pending ? "上游证书尚未生效（" + day + "）" : "上游证书已过期（" + day + "）";
    }

    private static String issuer(HandshakeRecord record) {
        if (record == null || record.chain().isEmpty()) {
            return "未知";
        }
        String issuer = record.chain().get(0).issuer();
        return issuer == null || issuer.isBlank() ? "未知" : issuer;
    }

    private static List<Map<String, Object>> chainFields(HandshakeRecord record) {
        if (record == null || record.chain().isEmpty()) {
            return List.of();
        }
        ArrayList<Map<String, Object>> chain = new ArrayList<>();
        for (TrustCert certificate : record.chain()) {
            chain.add(certificate.fields());
        }
        return List.copyOf(chain);
    }

    private static String rootMessage(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = root(failure).getMessage();
        return message == null ? "" : message;
    }

    private static String simpleName(Throwable failure) {
        if (failure == null) {
            return "IOException";
        }
        String name = root(failure).getClass().getSimpleName();
        return name == null || name.isBlank() ? root(failure).getClass().getName() : name;
    }

    static Throwable root(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && current.getCause() != null && current.getCause() != current && seen.add(current)) {
            current = current.getCause();
        }
        return current == null ? failure : current;
    }

    static String clip(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.strip();
        if (trimmed.codePointCount(0, trimmed.length()) <= 300) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 300));
    }

    private static String firstText(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return "";
    }

    private static List<Throwable> chain(Throwable failure) {
        ArrayList<Throwable> list = new ArrayList<>();
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            list.add(current);
            current = current.getCause();
        }
        return list;
    }
}
