package dev.hermit.crab.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 操作系统已经信任的 CA。加载失败只留下说明，不阻止启动。
 * Windows 读 {@code Windows-ROOT} 和 {@code Windows-MY} 里的 CA，macOS 读钥匙串里的 CA，
 * Linux 读系统 CA 包。
 */
final class SystemTrust {

    private static final List<Path> LINUX_BUNDLES = List.of(
            Path.of("/etc/ssl/certs/ca-certificates.crt"),
            Path.of("/etc/pki/tls/certs/ca-bundle.crt"),
            Path.of("/etc/ssl/cert.pem"));

    private SystemTrust() {
    }

    static Result load() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return windows();
            }
            if (os.contains("mac")) {
                return mac();
            }
            return linux();
        } catch (GeneralSecurityException | IOException | RuntimeException ex) {
            return Result.failed(null, reason(ex));
        }
    }

    static Result disabled() {
        return new Result(null, List.of(), null);
    }

    static Result readBundle(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Result.failed(file == null ? null : file.toString(), "没有找到操作系统 CA 包");
        }
        try {
            byte[] bytes = readLimited(file);
            List<X509Certificate> certificates = parsePem(bytes);
            return new Result(file.toString(), certificates, null);
        } catch (IOException | CertificateException ex) {
            return Result.failed(file.toString(), reason(ex));
        }
    }

    private static Result linux() {
        for (Path candidate : LINUX_BUNDLES) {
            if (Files.isRegularFile(candidate)) {
                return readBundle(candidate);
            }
        }
        return Result.failed(null, "没有找到操作系统 CA 包");
    }

    private static Result windows() throws GeneralSecurityException, IOException {
        ArrayList<X509Certificate> certificates = new ArrayList<>();
        for (String type : List.of("Windows-ROOT", "Windows-MY")) {
            certificates.addAll(caEntries(type));
        }
        return new Result("Windows-ROOT+Windows-MY", List.copyOf(certificates), null);
    }

    private static Result mac() throws GeneralSecurityException, IOException {
        List<X509Certificate> certificates = caEntries("KeychainStore");
        return new Result("KeychainStore", certificates, null);
    }

    private static List<X509Certificate> caEntries(String storeType) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(storeType);
        store.load(null, null);
        ArrayList<X509Certificate> certificates = new ArrayList<>();
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            Certificate certificate = store.getCertificate(aliases.nextElement());
            if (certificate instanceof X509Certificate x509 && x509.getBasicConstraints() >= 0) {
                certificates.add(x509);
            }
        }
        return List.copyOf(certificates);
    }

    private static byte[] readLimited(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readAllBytes();
        }
    }

    private static List<X509Certificate> parsePem(byte[] bytes) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> parsed = factory.generateCertificates(new ByteArrayInputStream(bytes));
        ArrayList<X509Certificate> certificates = new ArrayList<>();
        if (parsed != null) {
            for (Certificate certificate : parsed) {
                if (certificate instanceof X509Certificate x509) {
                    certificates.add(x509);
                }
            }
        }
        return List.copyOf(certificates);
    }

    private static String reason(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String text = ex.getClass().getSimpleName() + ": " + message.strip();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    record Result(String source, List<X509Certificate> certificates, String error) {
        Result {
            certificates = certificates == null ? List.of() : List.copyOf(certificates);
        }

        static Result failed(String source, String error) {
            return new Result(source, List.of(), error);
        }
    }
}
