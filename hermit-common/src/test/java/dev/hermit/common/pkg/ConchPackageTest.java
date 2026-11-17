package dev.hermit.common.pkg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import dev.hermit.common.Fixtures;
import dev.hermit.common.McpFixtures;
import dev.hermit.common.parse.ConchParser;

class ConchPackageTest {

    private final KeyPair keys = Ed25519Keys.generate();

    @Test
    void roundTripsCanonicalManifestAndPemKeys() throws Exception {
        byte[] yaml = Fixtures.yaml("legacy_oa_valid.yaml").getBytes(StandardCharsets.UTF_8);
        byte[] zip = ConchPackage.write(yaml, keys.getPrivate());
        Map<String, byte[]> entries = unzip(zip);
        assertThat(entries.keySet()).containsExactly("conch.yaml", "manifest.json", "signature.sig");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml));
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertThat(manifest).isEqualTo("{\"format\":1,\"min_crab_version\":\"1.0.0\",\"sha256\":\""
                + sha + "\",\"conch\":\"legacy_oa\",\"version\":\"1.0.0\"}");
        assertThat(new String(entries.get("signature.sig"), StandardCharsets.US_ASCII)).doesNotContain("\n");

        VerifiedConch verified = ConchPackage.readVerified(zip, keys.getPublic());
        assertThat(verified.conchYaml()).containsExactly(yaml);
        assertThat(verified.conchDoc()).isEqualTo(ConchParser.parse(Fixtures.yaml("legacy_oa_valid.yaml")));
        assertThat(verified.manifest().format()).isEqualTo(1);
        assertThat(verified.manifest().conch()).isEqualTo("legacy_oa");

        String privatePem = Ed25519Keys.writePrivatePem(keys.getPrivate());
        String publicPem = Ed25519Keys.writePublicPem(keys.getPublic());
        byte[] again = ConchPackage.write(yaml, Ed25519Keys.readPrivatePem(privatePem));
        assertThat(unzip(again).get("signature.sig")).containsExactly(entries.get("signature.sig"));
        assertThat(ConchPackage.readVerified(again, Ed25519Keys.readPublicPem(publicPem)).manifest().sha256())
                .isEqualTo(sha);
    }

    @Test
    void mcpYamlRoundTripsAndStillRejectsEntriesOverOneMib() {
        byte[] yaml = McpFixtures.headersYaml().getBytes(StandardCharsets.UTF_8);
        assertThat(yaml.length).isLessThan(ConchPackage.MAX_ENTRY_BYTES);
        byte[] zip = ConchPackage.write(yaml, keys.getPrivate());
        VerifiedConch verified = ConchPackage.readVerified(zip, keys.getPublic());
        assertThat(verified.conchYaml()).containsExactly(yaml);
        assertThat(verified.conchDoc()).isEqualTo(McpFixtures.headersDoc());
        assertThat(verified.conchDoc().driver()).isEqualTo("mcp");
        assertThat(verified.manifest().minCrabVersion()).isEqualTo("1.1.0");

        String huge = McpFixtures.headersYaml().replace(
                "按名称或手机号搜索客户。",
                "注".repeat(400_000));
        byte[] tooBig = huge.getBytes(StandardCharsets.UTF_8);
        assertThat(tooBig.length).isGreaterThan(ConchPackage.MAX_ENTRY_BYTES);
        byte[] oversized = ConchPackage.write(tooBig, keys.getPrivate());
        assertThatThrownBy(() -> ConchPackage.readVerified(oversized, keys.getPublic()))
                .isInstanceOf(PackageVerificationException.class)
                .extracting(ex -> ((PackageVerificationException) ex).reason())
                .isEqualTo(PackageReason.ENTRY_TOO_LARGE);
    }

    @Test
    void rejectsTamperingAndStructuralZipProblems() throws Exception {
        byte[] yaml = Fixtures.yaml("legacy_oa_valid.yaml").getBytes(StandardCharsets.UTF_8);
        byte[] zip = ConchPackage.write(yaml, keys.getPrivate());
        Map<String, byte[]> entries = unzip(zip);

        byte[] flippedYaml = yaml.clone();
        flippedYaml[0] ^= 0x01;
        assertReason(rezip(replaced(entries, "conch.yaml", flippedYaml)), PackageReason.DIGEST_MISMATCH);

        byte[] badSig = entries.get("signature.sig").clone();
        badSig[0] ^= 0x01;
        assertReason(rezip(replaced(entries, "signature.sig", badSig)), PackageReason.BAD_SIGNATURE);

        KeyPair other = Ed25519Keys.generate();
        assertReason(zip, other.getPublic(), PackageReason.BAD_SIGNATURE);

        Map<String, byte[]> extra = new LinkedHashMap<>(entries);
        extra.put("readme.txt", "hi".getBytes(StandardCharsets.UTF_8));
        assertReason(rezip(extra), PackageReason.ENTRY_SET);

        byte[] duplicate = duplicateConch(entries);
        assertReason(duplicate, PackageReason.DUPLICATE_ENTRY);

        Map<String, byte[]> missing = new LinkedHashMap<>(entries);
        missing.remove("signature.sig");
        assertReason(rezip(missing), PackageReason.ENTRY_SET);

        byte[] huge = new byte[ConchPackage.MAX_ENTRY_BYTES + 1];
        assertReason(rezip(replaced(entries, "conch.yaml", huge)), PackageReason.ENTRY_TOO_LARGE);
        assertReason(new byte[] {1, 2, 3}, PackageReason.UNREADABLE);
    }

    @Test
    void checksManifestAgainstYaml() throws Exception {
        byte[] yaml = Fixtures.yaml("legacy_oa_valid.yaml").getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = unzip(ConchPackage.write(yaml, keys.getPrivate()));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode manifest = (ObjectNode) mapper.readTree(entries.get("manifest.json"));
        manifest.put("version", "9.9.9");
        assertReason(rezip(signed(entries, manifest.toString().getBytes(StandardCharsets.UTF_8))),
                PackageReason.MANIFEST_MISMATCH);

        manifest.put("version", "1.0.0");
        manifest.put("format", 2);
        assertReason(rezip(signed(entries, mapper.writeValueAsBytes(manifest))), PackageReason.MANIFEST_INVALID);

        byte[] garbage = ":::".getBytes(StandardCharsets.UTF_8);
        ObjectNode broken = mapper.createObjectNode();
        broken.put("format", 1);
        broken.put("min_crab_version", "1.0.0");
        broken.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(garbage)));
        broken.put("conch", "legacy_oa");
        broken.put("version", "1.0.0");
        Map<String, byte[]> invalid = new LinkedHashMap<>();
        invalid.put("conch.yaml", garbage);
        invalid.put("manifest.json", canonical(broken));
        invalid.put("signature.sig", sign(invalid.get("manifest.json")));
        assertReason(rezip(invalid), PackageReason.YAML_INVALID);
    }

    private byte[] sign(byte[] manifest) throws Exception {
        java.security.Signature signature = java.security.Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(manifest);
        return java.util.Base64.getEncoder().encodeToString(signature.sign()).getBytes(StandardCharsets.US_ASCII);
    }

    private Map<String, byte[]> signed(Map<String, byte[]> entries, byte[] manifest) throws Exception {
        Map<String, byte[]> copy = replaced(entries, "manifest.json", manifest);
        copy.put("signature.sig", sign(manifest));
        return copy;
    }

    private static byte[] canonical(ObjectNode node) throws Exception {
        // 测试只需要可解析的 JSON；签名覆盖这里写出的字节。
        return new ObjectMapper().writeValueAsBytes(node);
    }

    @Test
    void signatureCoversScriptLibraryBytes() throws Exception {
        String yaml = """
                conch: sample_conch
                version: 1.0.0
                min_crab_version: 1.2.0
                scripts:
                  lib: |
                    function tag() { return "A"; }
                """;
        byte[] zip = ConchPackage.write(yaml.getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        VerifiedConch verified = ConchPackage.readVerified(zip, keys.getPublic());
        assertThat(verified.conchDoc().scripts().get("lib")).contains("return \"A\"");

        Map<String, byte[]> entries = unzip(zip);
        byte[] tampered = entries.get("conch.yaml").clone();
        byte[] marker = "return \"A\"".getBytes(StandardCharsets.UTF_8);
        int at = indexOf(tampered, marker);
        assertThat(at).isGreaterThanOrEqualTo(0);
        tampered[at + "return \"".length()] = 'B';
        assertReason(rezip(replaced(entries, "conch.yaml", tampered)), PackageReason.DIGEST_MISMATCH);
    }

    private static int indexOf(byte[] data, byte[] needle) {
        for (int i = 0; i + needle.length <= data.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private void assertReason(byte[] zip, PackageReason reason) {
        assertReason(zip, keys.getPublic(), reason);
    }

    private static void assertReason(byte[] zip, PublicKey key, PackageReason reason) {
        assertThatThrownBy(() -> ConchPackage.readVerified(zip, key))
                .isInstanceOf(PackageVerificationException.class)
                .extracting(ex -> ((PackageVerificationException) ex).reason())
                .isEqualTo(reason);
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static Map<String, byte[]> replaced(Map<String, byte[]> entries, String name, byte[] value) {
        LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>(entries);
        copy.put(name, value);
        return copy;
    }

    private static byte[] rezip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] duplicateConch(Map<String, byte[]> entries) throws Exception {
        byte[] first = storedEntry("conch.yaml", entries.get("conch.yaml"));
        byte[] second = storedEntry("conch.yaml", entries.get("conch.yaml"));
        byte[] third = storedEntry("manifest.json", entries.get("manifest.json"));
        return zipStored(new byte[][] {first, second, third});
    }

    private static byte[] zipStored(byte[][] locals) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        int offset = 0;
        for (byte[] local : locals) {
            out.write(local);
            int nameLength = readShort(local, 26);
            byte[] name = new byte[nameLength];
            System.arraycopy(local, 30, name, 0, nameLength);
            int crc = readInt(local, 14);
            int size = readInt(local, 18);
            writeCentral(central, name, crc, size, offset);
            offset += local.length;
        }
        byte[] centralBytes = central.toByteArray();
        out.write(centralBytes);
        writeEnd(out, locals.length, centralBytes.length, offset);
        return out.toByteArray();
    }

    private static byte[] storedEntry(String name, byte[] data) throws Exception {
        byte[] filename = name.getBytes(StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        crc32.update(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 0x04034b50);
        writeShort(out, 20);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, (int) crc32.getValue());
        writeInt(out, data.length);
        writeInt(out, data.length);
        writeShort(out, filename.length);
        writeShort(out, 0);
        out.write(filename);
        out.write(data);
        return out.toByteArray();
    }

    private static void writeCentral(ByteArrayOutputStream out, byte[] name, int crc, int size, int offset)
            throws Exception {
        writeInt(out, 0x02014b50);
        writeShort(out, 20);
        writeShort(out, 20);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, crc);
        writeInt(out, size);
        writeInt(out, size);
        writeShort(out, name.length);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, 0);
        writeInt(out, offset);
        out.write(name);
    }

    private static void writeEnd(ByteArrayOutputStream out, int count, int centralSize, int centralOffset)
            throws Exception {
        writeInt(out, 0x06054b50);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, count);
        writeShort(out, count);
        writeInt(out, centralSize);
        writeInt(out, centralOffset);
        writeShort(out, 0);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
    }

    private static int readShort(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }
}
