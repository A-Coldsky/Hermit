package dev.hermit.crab.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 从目录读取附加信任锚。坏文件只跳过这一个，不阻止启动。
 * 不监听目录变化，也不从网络下载证书。
 */
public final class CaDirectory {

    public static final int MAX_FILE_BYTES = 64 * 1024;
    public static final int MAX_FILES = 64;
    public static final int MAX_CERTIFICATES = 256;

    private CaDirectory() {
    }

    public static Result load(Path directory) {
        if (directory == null) {
            return Result.empty();
        }
        if (!Files.exists(directory)) {
            return Result.errors(List.of("目录不存在：" + directory.getFileName()));
        }
        if (!Files.isDirectory(directory)) {
            return Result.errors(List.of("不是目录：" + directory.getFileName()));
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile).filter(CaDirectory::accepted).sorted().toList();
        } catch (IOException ex) {
            return Result.errors(List.of("读不到目录"));
        }
        ArrayList<String> errors = new ArrayList<>();
        if (files.size() > MAX_FILES) {
            errors.add("证书文件超过 " + MAX_FILES + " 个，其余未加载");
            files = files.subList(0, MAX_FILES);
        }
        ArrayList<X509Certificate> certificates = new ArrayList<>();
        ArrayList<FileEntry> entries = new ArrayList<>();
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException ex) {
            return Result.errors(List.of("证书解析不可用"));
        }
        for (Path file : files) {
            if (certificates.size() >= MAX_CERTIFICATES) {
                errors.add("证书超过 " + MAX_CERTIFICATES + " 张，其余未加载");
                break;
            }
            loadFile(factory, file, certificates, entries, errors);
        }
        return new Result(List.copyOf(certificates), List.copyOf(entries), List.copyOf(errors));
    }

    private static void loadFile(
            CertificateFactory factory,
            Path file,
            List<X509Certificate> certificates,
            List<FileEntry> entries,
            List<String> errors
    ) {
        String name = file.getFileName().toString();
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException ex) {
            errors.add(name + "：读不到");
            return;
        }
        if (bytes.length > MAX_FILE_BYTES) {
            errors.add(name + "：超过 64 KiB，已跳过");
            return;
        }
        if (containsPrivateKey(bytes)) {
            errors.add(name + "：含有私钥，已跳过整个文件");
            return;
        }
        Collection<? extends Certificate> parsed;
        try {
            parsed = factory.generateCertificates(new ByteArrayInputStream(bytes));
        } catch (CertificateException ex) {
            errors.add(name + "：不是证书");
            return;
        }
        if (parsed == null || parsed.isEmpty()) {
            errors.add(name + "：不是证书");
            return;
        }
        ArrayList<TrustCert> described = new ArrayList<>();
        for (Certificate certificate : parsed) {
            if (certificates.size() >= MAX_CERTIFICATES) {
                errors.add("证书超过 " + MAX_CERTIFICATES + " 张，其余未加载");
                break;
            }
            if (!(certificate instanceof X509Certificate x509)) {
                errors.add(name + "：不是证书");
                continue;
            }
            certificates.add(x509);
            described.add(TrustCert.of(x509));
        }
        if (!described.isEmpty()) {
            entries.add(new FileEntry(name, List.copyOf(described)));
        }
    }

    private static boolean accepted(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pem") || name.endsWith(".crt") || name.endsWith(".cer");
    }

    static boolean containsPrivateKey(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        return text.contains("PRIVATE KEY-----");
    }

    public record FileEntry(String name, List<TrustCert> certificates) {
    }

    public record Result(List<X509Certificate> certificates, List<FileEntry> files, List<String> errors) {
        public Result {
            certificates = List.copyOf(certificates);
            files = List.copyOf(files);
            errors = List.copyOf(errors);
        }

        static Result empty() {
            return new Result(List.of(), List.of(), List.of());
        }

        static Result errors(List<String> errors) {
            return new Result(List.of(), List.of(), errors);
        }
    }
}
