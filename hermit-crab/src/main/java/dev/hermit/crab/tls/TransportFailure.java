package dev.hermit.crab.tls;

import java.net.ConnectException;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLException;

/**
 * 把出站连接失败收成稳定的种类和给用户的句子。判定沿 cause 链走，不只看最外层消息。
 * 错误码仍是 {@code UPSTREAM_ERROR}；超时仍由原来的分支处理。
 */
public final class TransportFailure {

    private static final String PKIX_CLASS = "sun.security.provider.certpath.SunCertPathBuilderException";
    private static final String UNRESOLVED = "java.nio.channels.UnresolvedAddressException";
    private static final String HOST_CONNECT = "org.apache.hc.client5.http.HttpHostConnectException";

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
        TLS_OTHER,
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
        String cause = clip(rootMessage(failure));
        List<Map<String, Object>> chain = chainFields(record);
        Kind kind = kindOf(failure, record);
        String message = message(kind, resolvedHost, resolvedPort, cause, record, failure);
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
        if (pkix(failure)) {
            return missingIntermediate(record) ? Kind.TLS_INTERMEDIATE : Kind.TLS_UNTRUSTED;
        }
        if (ssl(failure)) {
            return Kind.TLS_OTHER;
        }
        if (refused(failure)) {
            return Kind.REFUSED;
        }
        return Kind.OTHER;
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
            case REFUSED -> refusedMessage(name, port);
            case TIMEOUT -> "上游超时";
            case TLS_UNTRUSTED -> "上游证书不受信任：颁发者 " + issuer(record)
                    + "。可在控制台「HTTPS 信任」导入这个颁发者的根证书，或信任这张证书";
            case TLS_INTERMEDIATE -> "上游没有发送中间证书：请把颁发者 " + issuer(record) + " 的证书导入「HTTPS 信任」";
            case TLS_HOSTNAME -> name.isEmpty() ? "上游证书与主机名不符" : "上游证书与主机名 " + name + " 不符";
            case TLS_EXPIRED -> expiryMessage(record, failure);
            case TLS_OTHER -> "与上游的 TLS 握手失败：" + (cause.isEmpty() ? root(failure).getClass().getSimpleName() : cause);
            case OTHER -> "上游连接失败：" + simpleName(failure);
        };
    }

    private static String refusedMessage(String host, int port) {
        if (host.isEmpty()) {
            return "上游拒绝连接";
        }
        if (port < 0) {
            return "上游拒绝连接（" + host + "）";
        }
        return "上游拒绝连接（" + host + ":" + port + "）";
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
