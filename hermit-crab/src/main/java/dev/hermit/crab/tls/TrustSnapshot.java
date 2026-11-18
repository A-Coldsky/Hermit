package dev.hermit.crab.tls;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 一次加载得到的不可变信任材料：JDK 默认锚、操作系统锚、附加 CA，以及当时的指纹表。
 */
final class TrustSnapshot {

    private final X509ExtendedTrustManager manager;
    private final Set<String> fingerprints;
    private final Set<String> anchorSubjects;
    private final HostPins pins;
    private final List<String> loadErrors;
    private final int jdkCount;
    private final String systemSource;
    private final int systemCount;
    private final String systemError;
    private final int caCount;
    private final String caDirectory;
    private final String caDirectoryError;
    private final List<CaDirectory.FileEntry> caFiles;

    private TrustSnapshot(
            X509ExtendedTrustManager manager,
            Set<String> fingerprints,
            Set<String> anchorSubjects,
            HostPins pins,
            List<String> loadErrors,
            int jdkCount,
            String systemSource,
            int systemCount,
            String systemError,
            int caCount,
            String caDirectory,
            String caDirectoryError,
            List<CaDirectory.FileEntry> caFiles
    ) {
        this.manager = manager;
        this.fingerprints = fingerprints;
        this.anchorSubjects = anchorSubjects;
        this.pins = pins == null ? HostPins.NONE : pins;
        this.loadErrors = loadErrors;
        this.jdkCount = jdkCount;
        this.systemSource = systemSource;
        this.systemCount = systemCount;
        this.systemError = systemError;
        this.caCount = caCount;
        this.caDirectory = caDirectory;
        this.caDirectoryError = caDirectoryError;
        this.caFiles = caFiles;
    }

    static TrustSnapshot load(java.nio.file.Path caDir, boolean systemTrust, HostPins pins) {
        ArrayList<String> errors = new ArrayList<>();
        List<X509Certificate> jdk = jdkAnchors();
        SystemTrust.Result os = systemTrust ? SystemTrust.load() : SystemTrust.disabled();
        if (os.error() != null) {
            errors.add("操作系统信任库没有加载：" + os.error());
        }
        CaDirectory.Result extra = CaDirectory.load(caDir);
        errors.addAll(extra.errors());
        ArrayList<X509Certificate> merged = new ArrayList<>();
        LinkedHashSet<String> fingerprints = new LinkedHashSet<>();
        LinkedHashSet<String> subjects = new LinkedHashSet<>();
        addAll(merged, fingerprints, subjects, jdk);
        addAll(merged, fingerprints, subjects, os.certificates());
        addAll(merged, fingerprints, subjects, extra.certificates());
        X509ExtendedTrustManager manager = managerFor(merged, errors);
        return new TrustSnapshot(
                manager,
                Set.copyOf(fingerprints),
                Set.copyOf(subjects),
                pins,
                List.copyOf(errors),
                jdk.size(),
                os.source(),
                os.certificates().size(),
                os.error(),
                extra.certificates().size(),
                caDir == null ? null : caDir.toAbsolutePath().normalize().toString(),
                directoryError(extra.errors()),
                extra.files());
    }

    TrustSnapshot withPins(HostPins pins) {
        return new TrustSnapshot(
                manager,
                fingerprints,
                anchorSubjects,
                pins,
                loadErrors,
                jdkCount,
                systemSource,
                systemCount,
                systemError,
                caCount,
                caDirectory,
                caDirectoryError,
                caFiles);
    }

    /** JDK、操作系统、CA 目录各一条。控制台接口在后续的信任管理里暴露。 */
    List<TrustSource> sources() {
        return List.of(
                new TrustSource("JDK", "JDK", jdkCount, null, null),
                new TrustSource("OS", "操作系统", systemCount, systemSource, systemError),
                new TrustSource("CA_DIR", "CA 目录", caCount, caDirectory, caDirectoryError));
    }

    private static String directoryError(List<String> errors) {
        for (String error : errors) {
            if (error.startsWith("目录不存在")
                    || error.startsWith("不是目录")
                    || "读不到目录".equals(error)
                    || "证书解析不可用".equals(error)) {
                return error;
            }
        }
        return null;
    }

    X509ExtendedTrustManager manager() {
        return manager;
    }

    Set<String> fingerprints() {
        return fingerprints;
    }

    Set<String> anchorSubjects() {
        return anchorSubjects;
    }

    Set<String> pinKeys() {
        return pins.keys();
    }

    HostPins pins() {
        return pins;
    }

    List<String> loadErrors() {
        return loadErrors;
    }

    String systemSource() {
        return systemSource;
    }

    int systemCount() {
        return systemCount;
    }

    String systemError() {
        return systemError;
    }

    List<CaDirectory.FileEntry> caFiles() {
        return caFiles;
    }

    int anchorCount() {
        return fingerprints.size();
    }

    private static void addAll(
            List<X509Certificate> merged,
            Set<String> fingerprints,
            Set<String> subjects,
            List<X509Certificate> certificates
    ) {
        for (X509Certificate certificate : certificates) {
            String fingerprint;
            try {
                fingerprint = TrustCert.sha256(certificate);
            } catch (RuntimeException ex) {
                continue;
            }
            if (!fingerprints.add(fingerprint)) {
                continue;
            }
            subjects.add(TrustCert.dn(certificate.getSubjectX500Principal()));
            merged.add(certificate);
        }
    }

    private static List<X509Certificate> jdkAnchors() {
        try {
            X509TrustManager manager = defaultManager();
            X509Certificate[] issuers = manager.getAcceptedIssuers();
            if (issuers == null || issuers.length == 0) {
                return List.of();
            }
            ArrayList<X509Certificate> certificates = new ArrayList<>(issuers.length);
            for (X509Certificate issuer : issuers) {
                if (issuer != null) {
                    certificates.add(issuer);
                }
            }
            return List.copyOf(certificates);
        } catch (GeneralSecurityException ex) {
            return List.of();
        }
    }

    private static X509ExtendedTrustManager managerFor(List<X509Certificate> certificates, List<String> errors) {
        try {
            if (certificates.isEmpty()) {
                return requireExtended(defaultManager());
            }
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, new char[0]);
            int index = 0;
            for (X509Certificate certificate : certificates) {
                store.setCertificateEntry("anchor-" + index, certificate);
                index++;
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            return requireExtended(firstX509(factory));
        } catch (GeneralSecurityException | java.io.IOException ex) {
            errors.add("信任库没有建起来：" + ex.getClass().getSimpleName());
            try {
                return requireExtended(defaultManager());
            } catch (GeneralSecurityException fallback) {
                throw new IllegalStateException(fallback);
            }
        }
    }

    private static X509TrustManager defaultManager() throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return firstX509(factory);
    }

    private static X509TrustManager firstX509(TrustManagerFactory factory) {
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("no X509 trust manager");
    }

    private static X509ExtendedTrustManager requireExtended(X509TrustManager manager) {
        if (manager instanceof X509ExtendedTrustManager extended) {
            return extended;
        }
        throw new IllegalStateException("trust manager does not support TLS handshakes");
    }
}
