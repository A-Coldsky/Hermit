package dev.hermit.crab.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 测试用证书，全部由本机 openssl 现生成。不读取任何真实证书。
 */
public final class LabCertificates {

    public record Material(
            X509Certificate root,
            X509Certificate intermediate,
            X509Certificate leaf,
            X509Certificate wrongName,
            X509Certificate expired,
            X509Certificate notYetValid,
            X509Certificate intermediateLeaf,
            PrivateKey leafKey,
            PrivateKey wrongKey,
            PrivateKey intermediateLeafKey,
            Path rootPem
    ) {
    }

    private LabCertificates() {
    }

    public static Material generate(Path directory) throws Exception {
        Files.createDirectories(directory);
        write(directory.resolve("leaf.ext"), """
                basicConstraints=CA:FALSE
                keyUsage=digitalSignature,keyEncipherment
                extendedKeyUsage=serverAuth
                subjectAltName=DNS:localhost,IP:127.0.0.1
                subjectKeyIdentifier=hash
                authorityKeyIdentifier=none
                """);
        write(directory.resolve("wrong.ext"), """
                basicConstraints=CA:FALSE
                keyUsage=digitalSignature,keyEncipherment
                extendedKeyUsage=serverAuth
                subjectAltName=DNS:wrong.example
                subjectKeyIdentifier=hash
                authorityKeyIdentifier=none
                """);
        write(directory.resolve("inter.ext"), """
                basicConstraints=critical,CA:TRUE,pathlen:0
                keyUsage=critical,keyCertSign,cRLSign
                subjectKeyIdentifier=hash
                authorityKeyIdentifier=keyid:always
                """);
        run(directory, "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256",
                "-keyout", "root.key", "-out", "root.crt", "-days", "3650",
                "-subj", "/CN=Hermit Lab Root",
                "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign",
                "-addext", "subjectKeyIdentifier=hash");
        run(directory, "openssl", "req", "-newkey", "rsa:2048", "-nodes", "-sha256",
                "-keyout", "inter.key", "-out", "inter.csr", "-subj", "/CN=Hermit Lab Intermediate");
        run(directory, "openssl", "x509", "-req", "-in", "inter.csr", "-sha256",
                "-CA", "root.crt", "-CAkey", "root.key", "-CAcreateserial",
                "-out", "inter.crt", "-days", "3650", "-extfile", "inter.ext");
        run(directory, "openssl", "req", "-newkey", "rsa:2048", "-nodes", "-sha256",
                "-keyout", "leaf.key", "-out", "leaf.csr", "-subj", "/CN=localhost");
        run(directory, "openssl", "x509", "-req", "-in", "leaf.csr", "-sha256",
                "-CA", "root.crt", "-CAkey", "root.key", "-CAcreateserial",
                "-out", "leaf.crt", "-days", "825", "-extfile", "leaf.ext");
        write(directory.resolve("ca.cnf"), """
                [ ca ]
                default_ca = ca_default
                [ ca_default ]
                database = index.txt
                new_certs_dir = .
                certificate = root.crt
                private_key = root.key
                serial = serial.txt
                default_md = sha256
                policy = policy_any
                unique_subject = no
                [ policy_any ]
                commonName = supplied
                """);
        write(directory.resolve("index.txt"), "");
        write(directory.resolve("serial.txt"), "1000\n");
        signDated(directory, "leaf.csr", "expired.crt", "leaf.ext", "200101000000Z", "200102000000Z");
        signDated(directory, "leaf.csr", "future.crt", "leaf.ext", "350101000000Z", "360101000000Z");
        run(directory, "openssl", "req", "-newkey", "rsa:2048", "-nodes", "-sha256",
                "-keyout", "wrong.key", "-out", "wrong.csr", "-subj", "/CN=wrong.example");
        run(directory, "openssl", "x509", "-req", "-in", "wrong.csr", "-sha256",
                "-CA", "root.crt", "-CAkey", "root.key", "-CAcreateserial",
                "-out", "wrong.crt", "-days", "825", "-extfile", "wrong.ext");
        run(directory, "openssl", "req", "-newkey", "rsa:2048", "-nodes", "-sha256",
                "-keyout", "ileaf.key", "-out", "ileaf.csr", "-subj", "/CN=localhost");
        run(directory, "openssl", "x509", "-req", "-in", "ileaf.csr", "-sha256",
                "-CA", "inter.crt", "-CAkey", "inter.key", "-CAcreateserial",
                "-out", "ileaf.crt", "-days", "825", "-extfile", "leaf.ext");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate leaf = read(factory, directory.resolve("leaf.crt"));
        if (leaf.getExtensionValue("2.5.29.35") != null) {
            throw new IllegalStateException("generated leaf still has an Authority Key Identifier");
        }
        Path rootPem = directory.resolve("root.pem");
        Files.copy(directory.resolve("root.crt"), rootPem);
        return new Material(
                read(factory, directory.resolve("root.crt")),
                read(factory, directory.resolve("inter.crt")),
                leaf,
                read(factory, directory.resolve("wrong.crt")),
                read(factory, directory.resolve("expired.crt")),
                read(factory, directory.resolve("future.crt")),
                read(factory, directory.resolve("ileaf.crt")),
                privateKey(directory.resolve("leaf.key")),
                privateKey(directory.resolve("wrong.key")),
                privateKey(directory.resolve("ileaf.key")),
                rootPem);
    }

    public static void writePem(Path file, X509Certificate... certificates) throws Exception {
        StringBuilder pem = new StringBuilder();
        for (X509Certificate certificate : certificates) {
            pem.append("-----BEGIN CERTIFICATE-----\n");
            pem.append(Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded()));
            pem.append("\n-----END CERTIFICATE-----\n");
        }
        Files.writeString(file, pem.toString());
    }

    private static X509Certificate read(CertificateFactory factory, Path file) throws Exception {
        try (var in = Files.newInputStream(file)) {
            return (X509Certificate) factory.generateCertificate(in);
        }
    }

    private static PrivateKey privateKey(Path file) throws Exception {
        String pem = Files.readString(file);
        String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static void signDated(
            Path directory, String csr, String out, String extfile, String notBefore, String notAfter
    ) throws Exception {
        run(directory, "openssl", "ca", "-batch", "-notext", "-config", "ca.cnf",
                "-in", csr, "-out", out,
                "-startdate", notBefore, "-enddate", notAfter,
                "-extfile", extfile);
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text);
    }

    private static void run(Path directory, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + "\n" + output);
        }
    }
}
