package dev.hermit.crab.tls;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.testing.LabCertificates;

class CaDirectoryTest {

    @TempDir
    static Path root;

    private static LabCertificates.Material certs;

    @BeforeAll
    static void generate() throws Exception {
        certs = LabCertificates.generate(root.resolve("lab"));
    }

    @Test
    void loadsSeveralCertificatesAndSkipsABadFile(@TempDir Path dir) throws Exception {
        LabCertificates.writePem(dir.resolve("bundle.pem"), certs.root(), certs.intermediate());
        LabCertificates.writePem(dir.resolve("leaf.crt"), certs.leaf());
        Files.writeString(dir.resolve("notes.txt"), "not a certificate");
        Files.writeString(dir.resolve("bad.pem"), "this is not a certificate");
        Files.writeString(dir.resolve("secret.pem"), """
                -----BEGIN CERTIFICATE-----
                MIIB
                -----END CERTIFICATE-----
                -----BEGIN PRIVATE KEY-----
                MIIB
                -----END PRIVATE KEY-----
                """);

        CaDirectory.Result result = CaDirectory.load(dir);

        assertThat(result.certificates()).hasSize(3);
        assertThat(result.files()).extracting(CaDirectory.FileEntry::name)
                .containsExactly("bundle.pem", "leaf.crt");
        CaDirectory.FileEntry bundle = result.files().get(0);
        assertThat(bundle.certificates()).hasSize(2);
        assertThat(bundle.certificates()).anyMatch(TrustCert::ca);
        CaDirectory.FileEntry leaf = result.files().get(1);
        assertThat(leaf.certificates()).hasSize(1);
        assertThat(leaf.certificates().get(0).ca()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("bad.pem") && error.contains("不是证书"));
        assertThat(result.errors()).anyMatch(error -> error.contains("secret.pem") && error.contains("私钥"));
        assertThat(result.errors()).noneMatch(error -> error.contains("notes.txt"));
    }

    @Test
    void skipsOversizedFilesExtraFilesAndExtraCertificates(@TempDir Path dir) throws Exception {
        String pem = Files.readString(certs.rootPem());
        int perFile = Math.max(1, CaDirectory.MAX_FILE_BYTES / pem.getBytes(StandardCharsets.UTF_8).length - 1);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < perFile; i++) {
            many.append(pem);
        }
        int files = CaDirectory.MAX_CERTIFICATES / perFile + 1;
        for (int i = 0; i < files; i++) {
            Files.writeString(dir.resolve("batch-" + i + ".pem"), many);
        }
        byte[] huge = new byte[CaDirectory.MAX_FILE_BYTES + 1];
        Files.write(dir.resolve("0-huge.crt"), huge);

        CaDirectory.Result result = CaDirectory.load(dir);

        assertThat(result.certificates()).hasSize(CaDirectory.MAX_CERTIFICATES);
        assertThat(result.errors()).anyMatch(error -> error.contains("超过 256"));
        assertThat(result.errors()).anyMatch(error -> error.contains("0-huge.crt") && error.contains("64 KiB"));
    }

    @Test
    void extraFilesAreSkippedAndAMissingDirectoryDoesNotThrow(@TempDir Path dir) throws Exception {
        String pem = Files.readString(certs.rootPem());
        for (int i = 0; i < CaDirectory.MAX_FILES + 1; i++) {
            Files.writeString(dir.resolve(String.format("ca-%02d.cer", i)), pem);
        }
        CaDirectory.Result limited = CaDirectory.load(dir);
        assertThat(limited.certificates()).hasSize(CaDirectory.MAX_FILES);
        assertThat(limited.errors()).anyMatch(error -> error.contains("超过 64"));

        CaDirectory.Result missing = CaDirectory.load(dir.resolve("absent"));
        assertThat(missing.certificates()).isEmpty();
        assertThat(missing.errors()).anyMatch(error -> error.contains("目录不存在"));
    }

    @Test
    void acceptsANonCaTrustAnchor(@TempDir Path dir) throws Exception {
        X509Certificate leaf = certs.leaf();
        assertThat(leaf.getBasicConstraints()).isNegative();
        LabCertificates.writePem(dir.resolve("Leaf.CER"), leaf);
        CaDirectory.Result result = CaDirectory.load(dir);
        assertThat(result.certificates()).containsExactly(leaf);
        assertThat(result.files().get(0).certificates().get(0).ca()).isFalse();
        assertThat(result.errors()).isEmpty();
    }
}
