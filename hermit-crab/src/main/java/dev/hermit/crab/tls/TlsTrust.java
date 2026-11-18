package dev.hermit.crab.tls;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.sql.DataSource;

import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;

/**
 * 控制台 HTTPS 信任。只由控制台路由调用。规则包、API Key 和 MCP 不能经这里加信任。
 */
public final class TlsTrust {

    public static final String NOT_ALLOWED = "这个地址不在已安装规则、MCP 上游或 Reef 地址里。";
    public static final String NOT_SEEN = "没有在最近一小时内见过这张证书。请先检测这个站点。";
    public static final String EXPIRED = "这张证书已经过期，不能登记。";
    public static final String NOT_YET_VALID = "这张证书尚未生效，不能登记。";
    public static final String BAD_FINGERPRINT = "指纹需要是 64 位十六进制。";
    public static final String BAD_PORT = "端口需要在 1 到 65535 之间。";
    public static final String BAD_HOST = "主机名无效。";
    public static final String BAD_NAME = "名字只能包含字母、数字、点、下划线和连字符，长度 1 到 64，并且以 .pem、.crt 或 .cer 结尾。";
    public static final String PRIVATE_KEY = "不能导入私钥";
    public static final String NOT_A_CERTIFICATE = "不是证书";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:-]{1,253}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final DateTimeFormatter INSTANT = DateTimeFormatter.ISO_INSTANT;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final OutboundTls tls;
    private final TlsHostStore hosts;
    private final TlsMemory memory;
    private final DataSource dataSource;
    private final Clock clock;
    private final Path caDir;
    private final boolean systemTrust;
    private final BiPredicate<String, Integer> allowed;

    public TlsTrust(
            OutboundTls tls,
            TlsHostStore hosts,
            DataSource dataSource,
            Clock clock,
            Path caDir,
            boolean systemTrust,
            BiPredicate<String, Integer> allowed
    ) {
        this(tls, hosts, new TlsMemory(clock), dataSource, clock, caDir, systemTrust, allowed);
    }

    TlsTrust(
            OutboundTls tls,
            TlsHostStore hosts,
            TlsMemory memory,
            DataSource dataSource,
            Clock clock,
            Path caDir,
            boolean systemTrust,
            BiPredicate<String, Integer> allowed
    ) {
        this.tls = tls;
        this.hosts = hosts;
        this.memory = memory;
        this.dataSource = dataSource;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.caDir = caDir;
        this.systemTrust = systemTrust;
        this.allowed = allowed == null ? (host, port) -> false : allowed;
        TlsMemory.install(memory);
    }

    public void close() {
        TlsMemory.clear(memory);
    }

    public Map<String, Object> view() {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("sources", sources());
        body.put("caFiles", caFiles());
        body.put("trustedHosts", trustedHosts());
        body.put("loadErrors", loadErrors());
        return body;
    }

    public Map<String, Object> importCa(String name, String pem) {
        String file = fileName(name);
        byte[] bytes = pem == null ? new byte[0] : pem.getBytes(StandardCharsets.UTF_8);
        if (CaDirectory.containsPrivateKey(bytes)) {
            throw new IllegalArgumentException(file + "：含有私钥，已跳过整个文件");
        }
        if (bytes.length > CaDirectory.MAX_FILE_BYTES) {
            throw new IllegalArgumentException(file + "：超过 64 KiB，已跳过");
        }
        List<X509Certificate> parsed = parse(file, bytes);
        reserveRoom(file, parsed.size());
        Path directory = directory();
        Path target = directory.resolve(file);
        byte[] previous = Files.isRegularFile(target) ? readQuiet(target) : null;
        write(directory, target, bytes);
        reloadSnapshot();
        if (failedToLoad(file)) {
            restore(target, previous);
            reloadSnapshot();
            throw new IllegalArgumentException(errorFor(file));
        }
        audit(AuditKind.TLS_CA_ADDED, file, detail("file", file, "fields", List.of(file)));
        return view();
    }

    public Map<String, Object> deleteCa(String name) {
        String file = fileName(name);
        Path target = directory().resolve(file);
        boolean deleted;
        try {
            deleted = Files.deleteIfExists(target);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("读不到目录");
        }
        if (deleted) {
            reloadSnapshot();
            audit(AuditKind.TLS_CA_REMOVED, file, detail("file", file, "fields", List.of(file)));
        }
        return Map.of("deleted", deleted);
    }

    public Map<String, Object> reload() {
        reloadSnapshot();
        audit(AuditKind.TLS_RELOADED, "tls", detail("fields", List.of()));
        return view();
    }

    public Map<String, Object> probe(String host, int port) {
        Endpoint endpoint = endpoint(host, port);
        requireAllowed(endpoint);
        List<TrustCert> chain = List.of();
        boolean trusted = false;
        String kind = null;
        String message = null;
        try (SSLSocket socket = (SSLSocket) sslContext().getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            chain = describe(socket.getSession().getPeerCertificates());
            trusted = true;
        } catch (Exception ex) {
            HandshakeRecord record = HandshakeRecord.find(ex);
            if (record != null && !record.chain().isEmpty()) {
                chain = record.chain();
            }
            TransportFailure.View view = TransportFailure.classify(ex, endpoint.host(), endpoint.port());
            kind = view.kind().name();
            message = view.message();
        }
        memory.rememberProbe(endpoint.host(), endpoint.port(), chain);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("host", endpoint.host());
        body.put("port", endpoint.port());
        body.put("trusted", trusted);
        body.put("kind", kind);
        body.put("message", message);
        body.put("chain", chainMaps(chain));
        return body;
    }

    public Map<String, Object> trustHost(String host, int port, String sha256) {
        Endpoint endpoint = endpoint(host, port);
        requireAllowed(endpoint);
        String fingerprint = fingerprint(sha256);
        TrustCert leaf = memory.seenLeaf(endpoint.host(), endpoint.port(), fingerprint, clock.instant());
        if (leaf == null) {
            throw new IllegalArgumentException(NOT_SEEN);
        }
        Instant now = clock.instant();
        Instant notAfter = Instant.parse(leaf.notAfter());
        Instant notBefore = leaf.notBefore() == null ? null : Instant.parse(leaf.notBefore());
        if (!now.isBefore(notAfter)) {
            throw new IllegalArgumentException(EXPIRED);
        }
        if (notBefore != null && now.isBefore(notBefore)) {
            throw new IllegalArgumentException(NOT_YET_VALID);
        }
        TlsHostStore.Row row = hosts.save(new TlsHostStore.Row(
                endpoint.host(),
                endpoint.port(),
                fingerprint,
                leaf.subject() == null ? "" : leaf.subject(),
                leaf.issuer() == null ? "" : leaf.issuer(),
                notAfter,
                now,
                Actors.CONSOLE));
        tls.setPins(hosts);
        String address = endpoint.host() + ":" + endpoint.port();
        audit(AuditKind.TLS_HOST_TRUSTED, address, detail(
                "host", endpoint.host(),
                "port", endpoint.port(),
                "sha256", fingerprint,
                "fields", List.of(address)));
        return hostMap(row);
    }

    public Map<String, Object> removeHost(String host, int port) {
        Endpoint endpoint = endpoint(host, port);
        boolean deleted = hosts.delete(endpoint.host(), endpoint.port());
        if (deleted) {
            tls.setPins(hosts);
            String address = endpoint.host() + ":" + endpoint.port();
            audit(AuditKind.TLS_HOST_REMOVED, address, detail(
                    "host", endpoint.host(),
                    "port", endpoint.port(),
                    "fields", List.of(address)));
        }
        return Map.of("deleted", deleted);
    }

    public Map<String, Object> failures() {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (TlsMemory.Failure failure : memory.failures()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("host", failure.host());
            row.put("port", failure.port());
            row.put("kind", failure.kind());
            row.put("message", failure.message());
            row.put("at", INSTANT.format(failure.at().atOffset(java.time.ZoneOffset.UTC)));
            row.put("chain", chainMaps(failure.chain()));
            rows.add(row);
        }
        return Map.of("failures", List.copyOf(rows));
    }

    private void requireAllowed(Endpoint endpoint) {
        if (!allowed.test(endpoint.host(), endpoint.port())) {
            throw new IllegalArgumentException(NOT_ALLOWED);
        }
    }

    private SSLContext sslContext() {
        return tls.sslContext();
    }

    private void reloadSnapshot() {
        tls.reload(caDir, systemTrust);
    }

    private Path directory() {
        try {
            Files.createDirectories(caDir);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("读不到目录");
        }
        if (!Files.isDirectory(caDir)) {
            throw new IllegalArgumentException("不是目录：" + caDir.getFileName());
        }
        return caDir;
    }

    private void reserveRoom(String file, int incoming) {
        CaDirectory.Result current = CaDirectory.load(caDir);
        int replaced = 0;
        boolean known = false;
        for (CaDirectory.FileEntry entry : current.files()) {
            if (entry.name().equals(file)) {
                known = true;
                replaced = entry.certificates().size();
            }
        }
        if (!known && current.files().size() >= CaDirectory.MAX_FILES) {
            throw new IllegalArgumentException("证书文件超过 64 个，其余未加载");
        }
        if (current.certificates().size() - replaced + incoming > CaDirectory.MAX_CERTIFICATES) {
            throw new IllegalArgumentException("证书超过 256 张，其余未加载");
        }
    }

    private boolean failedToLoad(String file) {
        for (String error : tls.loadErrors()) {
            if (error.startsWith(file + "：")) {
                return true;
            }
        }
        return false;
    }

    private String errorFor(String file) {
        for (String error : tls.loadErrors()) {
            if (error.startsWith(file + "：")) {
                return error;
            }
        }
        return file + "：" + NOT_A_CERTIFICATE;
    }

    private List<Map<String, Object>> sources() {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (TrustSource source : tls.sources()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("kind", source.kind());
            row.put("label", source.label());
            row.put("count", source.count());
            row.put("location", source.location());
            row.put("error", source.error());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> caFiles() {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (CaDirectory.FileEntry entry : tls.caFiles()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.name());
            row.put("certificates", chainMaps(entry.certificates()));
            row.put("error", null);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> trustedHosts() {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (TlsHostStore.Row row : hosts.list()) {
            rows.add(hostMap(row));
        }
        return rows;
    }

    private static Map<String, Object> hostMap(TlsHostStore.Row row) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("host", row.host());
        map.put("port", row.port());
        map.put("sha256", row.sha256());
        map.put("subject", row.subject());
        map.put("issuer", row.issuer());
        map.put("notAfter", INSTANT.format(row.notAfter().atOffset(java.time.ZoneOffset.UTC)));
        map.put("createdAt", INSTANT.format(row.createdAt().atOffset(java.time.ZoneOffset.UTC)));
        map.put("createdBy", row.createdBy());
        return map;
    }

    private List<Map<String, Object>> loadErrors() {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (String error : tls.loadErrors()) {
            if (error.startsWith("操作系统信任库没有加载")) {
                continue;
            }
            String file = null;
            String message = error;
            int split = error.indexOf('：');
            if (split > 0) {
                String prefix = error.substring(0, split);
                if (NAME.matcher(prefix).matches()) {
                    file = prefix;
                    message = error.substring(split + 1);
                }
            }
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("file", file);
            row.put("message", message);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> chainMaps(List<TrustCert> chain) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        if (chain != null) {
            for (TrustCert certificate : chain) {
                if (certificate != null) {
                    rows.add(certificate.consoleFields());
                }
            }
        }
        return rows;
    }

    private static List<TrustCert> describe(Certificate[] certificates) {
        ArrayList<TrustCert> chain = new ArrayList<>();
        if (certificates != null) {
            for (Certificate certificate : certificates) {
                if (certificate instanceof X509Certificate x509) {
                    chain.add(TrustCert.of(x509));
                }
            }
        }
        return List.copyOf(chain);
    }

    private static List<X509Certificate> parse(String file, byte[] bytes) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> parsed = factory.generateCertificates(new ByteArrayInputStream(bytes));
            if (parsed == null || parsed.isEmpty()) {
                throw new IllegalArgumentException(file + "：" + NOT_A_CERTIFICATE);
            }
            ArrayList<X509Certificate> certificates = new ArrayList<>();
            for (Certificate certificate : parsed) {
                if (!(certificate instanceof X509Certificate x509)) {
                    throw new IllegalArgumentException(file + "：" + NOT_A_CERTIFICATE);
                }
                certificates.add(x509);
            }
            return certificates;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(file + "：" + NOT_A_CERTIFICATE);
        }
    }

    private static void write(Path directory, Path target, byte[] bytes) {
        Path temporary = directory.resolve(target.getFileName().toString() + ".writing");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException ex) {
            try {
                Files.deleteIfExists(temporary);
            } catch (java.io.IOException ignored) {
                // 临时文件留在目录里也不会被加载：加载只认证书扩展名。
            }
            throw new IllegalArgumentException("读不到目录");
        }
    }

    private static void restore(Path target, byte[] previous) {
        try {
            if (previous == null) {
                Files.deleteIfExists(target);
            } else {
                Files.write(target, previous);
            }
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("读不到目录");
        }
    }

    private static byte[] readQuiet(Path target) {
        try {
            return Files.readAllBytes(target);
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private void audit(AuditKind kind, String target, Map<String, Object> detail) {
        AuditLog.record(dataSource, Actors.CONSOLE, kind, target, ApiJson.write(detail));
    }

    private static Map<String, Object> detail(Object... pairs) {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            detail.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return detail;
    }

    static String fileName(String name) {
        String file = name == null ? "" : name.trim();
        String lower = file.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(file).matches()
                || file.equals(".")
                || file.equals("..")
                || !(lower.endsWith(".pem") || lower.endsWith(".crt") || lower.endsWith(".cer"))) {
            throw new IllegalArgumentException(BAD_NAME);
        }
        return file;
    }

    static Endpoint endpoint(String host, int port) {
        String value = host == null ? "" : host.trim();
        if (!HOST.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(BAD_HOST);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(BAD_PORT);
        }
        return new Endpoint(value.toLowerCase(Locale.ROOT), port);
    }

    private static String fingerprint(String sha256) {
        String value = sha256 == null ? "" : sha256.strip().toLowerCase(Locale.ROOT);
        if (!FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(BAD_FINGERPRINT);
        }
        return value;
    }

    public record Endpoint(String host, int port) {
    }
}
