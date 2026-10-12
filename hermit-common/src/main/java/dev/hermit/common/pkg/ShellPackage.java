package dev.hermit.common.pkg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParseException;
import dev.hermit.common.parse.ShellParser;

public final class ShellPackage {

    public static final int MAX_ENTRY_BYTES = 1024 * 1024;

    private static final String SHELL_YAML = "shell.yaml";
    private static final String MANIFEST_JSON = "manifest.json";
    private static final String SIGNATURE_SIG = "signature.sig";
    private static final Set<String> ALLOWED = Set.of(SHELL_YAML, MANIFEST_JSON, SIGNATURE_SIG);
    private static final ObjectMapper JSON = new ObjectMapper();

    private ShellPackage() {
    }

    public static byte[] write(byte[] shellYaml, PrivateKey key) {
        if (shellYaml == null) {
            throw new IllegalArgumentException("shellYaml 为空");
        }
        if (key == null) {
            throw new IllegalArgumentException("私钥为空");
        }
        ShellDoc doc = ShellParser.parse(new String(shellYaml, StandardCharsets.UTF_8));
        byte[] manifest = canonicalManifest(
                doc.shell(), doc.version().toString(), doc.minCrabVersion().toString(), sha256(shellYaml));
        byte[] signature = sign(manifest, key);
        String encoded = Base64.getEncoder().encodeToString(signature);
        return zip(Map.of(
                SHELL_YAML, shellYaml,
                MANIFEST_JSON, manifest,
                SIGNATURE_SIG, encoded.getBytes(StandardCharsets.US_ASCII)));
    }

    public static VerifiedShell readVerified(byte[] zipBytes, PublicKey key) {
        if (zipBytes == null) {
            throw new PackageVerificationException(PackageReason.UNREADABLE, "zip 为空");
        }
        if (key == null) {
            throw new PackageVerificationException(PackageReason.BAD_SIGNATURE, "公钥为空");
        }
        Map<String, byte[]> entries = readEntries(zipBytes);
        byte[] manifestBytes = entries.get(MANIFEST_JSON);
        byte[] shellYaml = entries.get(SHELL_YAML);
        byte[] signatureText = entries.get(SIGNATURE_SIG);
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureText);
        } catch (IllegalArgumentException ex) {
            throw new PackageVerificationException(PackageReason.BAD_SIGNATURE, "signature.sig 不是单行 Base64", ex);
        }
        if (!verify(manifestBytes, signature, key)) {
            throw new PackageVerificationException(PackageReason.BAD_SIGNATURE, "签名验证失败");
        }
        ShellManifest manifest = parseManifest(manifestBytes);
        if (!sha256(shellYaml).equals(manifest.sha256())) {
            throw new PackageVerificationException(PackageReason.DIGEST_MISMATCH, "shell.yaml 的 sha256 与 manifest 不一致");
        }
        ShellDoc doc;
        try {
            doc = ShellParser.parse(new String(shellYaml, StandardCharsets.UTF_8));
        } catch (ShellParseException ex) {
            throw new PackageVerificationException(PackageReason.YAML_INVALID, "shell.yaml 无法解析", ex);
        }
        if (!manifest.shell().equals(doc.shell())
                || !manifest.version().equals(doc.version().toString())
                || !manifest.minCrabVersion().equals(doc.minCrabVersion().toString())) {
            throw new PackageVerificationException(
                    PackageReason.MANIFEST_MISMATCH, "manifest 与 shell.yaml 的标识不一致");
        }
        return new VerifiedShell(manifest, shellYaml, doc);
    }

    private static byte[] canonicalManifest(String shell, String version, String minCrabVersion, String sha256) {
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", 1);
        manifest.put("min_crab_version", minCrabVersion);
        manifest.put("sha256", sha256);
        manifest.put("shell", shell);
        manifest.put("version", version);
        try {
            return JSON.writeValueAsBytes(manifest);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("manifest 无法生成", ex);
        }
    }

    private static ShellManifest parseManifest(byte[] bytes) {
        JsonNode node;
        try {
            node = JSON.readTree(bytes);
        } catch (IOException ex) {
            throw new PackageVerificationException(PackageReason.MANIFEST_INVALID, "manifest.json 不是 JSON", ex);
        }
        if (node == null || !node.isObject()) {
            throw new PackageVerificationException(PackageReason.MANIFEST_INVALID, "manifest.json 必须是对象");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        if (!names.equals(Set.of("format", "shell", "version", "min_crab_version", "sha256"))) {
            throw new PackageVerificationException(PackageReason.MANIFEST_INVALID, "manifest.json 字段不匹配");
        }
        JsonNode format = node.get("format");
        if (format == null || !format.isInt() || format.intValue() != 1) {
            throw new PackageVerificationException(PackageReason.MANIFEST_INVALID, "format 必须为 1");
        }
        return new ShellManifest(
                1,
                text(node, "shell"),
                text(node, "version"),
                text(node, "min_crab_version"),
                text(node, "sha256"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new PackageVerificationException(PackageReason.MANIFEST_INVALID, field + " 必须是字符串");
        }
        return value.textValue();
    }

    private static byte[] sign(byte[] manifest, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(manifest);
            return signature.sign();
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("无法用 Ed25519 私钥签名", ex);
        }
    }

    private static boolean verify(byte[] manifest, byte[] signature, PublicKey key) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(manifest);
            return verifier.verify(signature);
        } catch (GeneralSecurityException ex) {
            return false;
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (String name : new String[] {SHELL_YAML, MANIFEST_JSON, SIGNATURE_SIG}) {
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(entries.get(name));
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法写入 zip", ex);
        }
        return buffer.toByteArray();
    }

    private static Map<String, byte[]> readEntries(byte[] zipBytes) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zip.getNextEntry()) != null) {
                seen++;
                if (entry.isDirectory()) {
                    throw new PackageVerificationException(PackageReason.ENTRY_SET, "zip 含目录条目");
                }
                byte[] data = readLimited(zip);
                if (seen > ALLOWED.size()) {
                    throw new PackageVerificationException(
                            PackageReason.ENTRY_SET,
                            "zip 条目必须恰好是 shell.yaml、manifest.json、signature.sig");
                }
                String name = entry.getName();
                if (entries.containsKey(name)) {
                    throw new PackageVerificationException(PackageReason.DUPLICATE_ENTRY, "重复条目 " + name);
                }
                if (!ALLOWED.contains(name)) {
                    throw new PackageVerificationException(PackageReason.ENTRY_SET, "意外条目 " + name);
                }
                entries.put(name, data);
            }
        } catch (PackageVerificationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new PackageVerificationException(PackageReason.UNREADABLE, "zip 无法读取", ex);
        }
        // ZipInputStream 对非 zip 静默返回空，不抛异常。EOCD 用来区分「不是 zip」和「空 zip」。
        if (entries.isEmpty() && !hasEndOfCentralDirectory(zipBytes)) {
            throw new PackageVerificationException(PackageReason.UNREADABLE, "zip 无法读取");
        }
        if (entries.size() != ALLOWED.size()) {
            throw new PackageVerificationException(
                    PackageReason.ENTRY_SET,
                    "zip 条目必须恰好是 shell.yaml、manifest.json、signature.sig");
        }
        return entries;
    }

    private static boolean hasEndOfCentralDirectory(byte[] zipBytes) {
        int min = 22;
        if (zipBytes.length < min) {
            return false;
        }
        int start = Math.max(0, zipBytes.length - (65535 + min));
        for (int i = zipBytes.length - min; i >= start; i--) {
            if (zipBytes[i] == 0x50
                    && zipBytes[i + 1] == 0x4b
                    && zipBytes[i + 2] == 0x05
                    && zipBytes[i + 3] == 0x06) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new PackageVerificationException(PackageReason.ENTRY_TOO_LARGE, "zip 条目超过 1MB");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
