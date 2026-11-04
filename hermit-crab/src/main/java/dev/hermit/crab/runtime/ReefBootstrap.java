package dev.hermit.crab.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.store.CrabPaths;

/**
 * Reef connection file at {@code $HERMIT_HOME/reef-bootstrap.json}. When the file exists it
 * overrides {@code reef.url}, {@code reef.token}, and {@code reef.public_key} from config.yml.
 * The token stays in this file and in the process secret list; it is not returned by the console.
 */
public final class ReefBootstrap {
    public static final String KIND = "hermit-crab-bootstrap";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 64 * 1024;
    public static final String FILE_NAME = "reef-bootstrap.json";
    public static final String REJECTED_TOKEN = "reef rejected the token";
    public static final String UNREACHABLE = "cannot reach reef";
    public static final String KEY_CONFLICT = "public key differs from installed conches";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Set<String> IMPORT_FIELDS = Set.of(
            "kind", "version", "reef", "owner", "tokenName", "createdAt");
    private static final Set<String> STORED_FIELDS = Set.of(
            "kind", "version", "reef", "owner", "tokenName", "createdAt", "importedAt");
    private static final Set<String> REEF_FIELDS = Set.of("url", "token", "publicKey");
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private ReefBootstrap() {
    }

    public static Path file(CrabPaths paths) {
        return paths.root().resolve(FILE_NAME);
    }

    public record Document(
            String url,
            String token,
            String publicKeyPem,
            PublicKey publicKey,
            String owner,
            String tokenName,
            String createdAt,
            String importedAt
    ) {
    }

    /** Missing file leaves config.yml unchanged. A present but invalid file refuses startup. */
    public static CrabConfig overlay(CrabPaths paths, CrabConfig config) {
        Document document = readStored(paths).orElse(null);
        if (document == null) {
            return config;
        }
        return config.withReef(document.url(), document.token(), document.publicKeyPem());
    }

    public static Optional<Document> readStored(CrabPaths paths) {
        Path file = file(paths);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("reef-bootstrap.json is not a file");
        }
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                throw new IllegalStateException("reef-bootstrap.json is larger than 64 KiB");
            }
            return Optional.of(parse(Files.readAllBytes(file), true));
        } catch (ReefBootstrapException ex) {
            throw new IllegalStateException("reef-bootstrap.json is invalid");
        } catch (IOException ex) {
            throw new IllegalStateException("reef-bootstrap.json cannot be read");
        }
    }

    public static Document parseImport(byte[] body) {
        return parse(body, false);
    }

    /**
     * {@code GET /agent/conches/manifest} with the new URL, token, and this instance id.
     * 2xx and 304 continue. 401 and 403 become 400 {@link #REJECTED_TOKEN}. Any other HTTP
     * status becomes 400 and names that status. A connection failure or timeout becomes 502.
     * Redirects are not followed.
     */
    public static void probe(Document document, UUID instanceId) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(endpoint(URI.create(document.url()), "agent/conches/manifest"))
                .timeout(REQUEST_TIMEOUT)
                .header("X-Hermit-Instance", instanceId.toString())
                .header("X-Hermit-Token", document.token())
                .GET()
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new ReefBootstrapException(400, ErrorCode.INVALID_INPUT, REJECTED_TOKEN);
            }
            if (status == 304 || (status >= 200 && status <= 299)) {
                return;
            }
            throw new ReefBootstrapException(400, ErrorCode.INVALID_INPUT, notReef(status));
        } catch (ReefBootstrapException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ReefBootstrapException(502, ErrorCode.UPSTREAM_ERROR, UNREACHABLE);
        } catch (IOException ex) {
            throw new ReefBootstrapException(502, ErrorCode.UPSTREAM_ERROR, UNREACHABLE);
        }
    }

    /** Message for a manifest response that is neither 2xx, 304, 401, nor 403. */
    public static String notReef(int status) {
        return "reef url did not answer as Reef (HTTP " + status + ")";
    }

    public static void write(CrabPaths paths, Document document, Instant importedAt) {
        if (importedAt == null) {
            throw new IllegalArgumentException("importedAt is required");
        }
        String json = writeJson(document, importedAt);
        Path target = file(paths);
        Path directory = target.getParent();
        Path temp;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, ".reef-bootstrap-", ".tmp");
        } catch (IOException ex) {
            throw new IllegalStateException("cannot write reef-bootstrap.json");
        }
        try {
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            restrict(temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(target);
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // The original file is unchanged when the replace did not finish.
            }
            throw new IllegalStateException("cannot write reef-bootstrap.json");
        }
    }

    public static boolean sameKey(PublicKey left, PublicKey right) {
        if (left == null || right == null) {
            return false;
        }
        byte[] a = left.getEncoded();
        byte[] b = right.getEncoded();
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    /** Lowercase SHA-256 of the X.509 SubjectPublicKeyInfo. Null when there is no key. */
    public static String fingerprint(PublicKey key) {
        if (key == null || key.getEncoded() == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("cannot fingerprint public key");
        }
    }

    /**
     * First 12 characters when the token is longer than that. A token that is not longer
     * than the prefix is not returned, so the prefix is never the whole secret.
     */
    public static String tokenPrefix(String token) {
        if (token == null || token.length() <= 12) {
            return null;
        }
        return token.substring(0, 12);
    }

    private static Document parse(byte[] body, boolean stored) {
        if (body == null) {
            throw invalid("invalid bootstrap");
        }
        if (body.length > MAX_BYTES) {
            throw invalid("bootstrap is larger than 64 KiB");
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException ex) {
            throw invalid("invalid bootstrap");
        }
        if (root == null || !root.isObject()) {
            throw invalid("invalid bootstrap");
        }
        rejectUnknown(root, stored ? STORED_FIELDS : IMPORT_FIELDS, "");
        requireText(root, "kind", KIND);
        JsonNode version = root.get("version");
        if (version == null || !version.isIntegralNumber() || version.isFloatingPointNumber()
                || version.intValue() != VERSION) {
            throw invalid("version must be 1");
        }
        JsonNode reef = root.get("reef");
        if (reef == null || !reef.isObject()) {
            throw invalid("missing field: reef");
        }
        rejectUnknown(reef, REEF_FIELDS, "reef.");
        String url = requireString(reef, "url", "reef.url", 2048);
        parseUrl(url);
        String token = requireString(reef, "token", "reef.token", 512);
        String pem = requirePem(reef.get("publicKey"));
        PublicKey key = readKey(pem);
        String owner = optionalString(root, "owner", 256);
        String tokenName = optionalString(root, "tokenName", 256);
        String createdAt = optionalString(root, "createdAt", 64);
        String importedAt = stored ? optionalString(root, "importedAt", 64) : null;
        return new Document(url, token, pem, key, owner, tokenName, createdAt, importedAt);
    }

    private static URI parseUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw invalid("reef.url must be http or https");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || uri.getRawUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid("reef.url must be http or https");
        }
        return uri;
    }

    private static void requireText(JsonNode root, String field, String expected) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || !expected.equals(node.asText())) {
            throw invalid(field + " must be " + expected);
        }
    }

    private static String requireString(JsonNode parent, String field, String path, int max) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw invalid("missing field: " + path);
        }
        if (!node.isTextual()) {
            throw invalid(path + " must be a string");
        }
        String value = node.asText().strip();
        if (value.isEmpty() || value.length() > max || hasControl(value)) {
            throw invalid(path + " is invalid");
        }
        return value;
    }

    private static String optionalString(JsonNode parent, String field, int max) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode node = parent.get(field);
        if (!node.isTextual()) {
            throw invalid(field + " must be a string");
        }
        String value = node.asText().strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max || hasControl(value)) {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private static String requirePem(JsonNode node) {
        if (node == null || node.isNull()) {
            throw invalid("missing field: reef.publicKey");
        }
        if (!node.isTextual()) {
            throw invalid("reef.publicKey must be a string");
        }
        String pem = node.asText().strip();
        if (pem.isEmpty() || pem.length() > 16 * 1024 || !pem.contains("-----BEGIN")) {
            throw invalid("reef.publicKey is not a usable Ed25519 public key");
        }
        return pem;
    }

    private static PublicKey readKey(String pem) {
        try {
            return Ed25519Keys.readPublicPem(pem);
        } catch (RuntimeException ex) {
            throw invalid("reef.publicKey is not a usable Ed25519 public key");
        }
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String prefix) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid("unknown field: " + prefix + name);
            }
        }
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static String writeJson(Document document, Instant importedAt) {
        ObjectNode reef = JSON.createObjectNode();
        reef.put("url", document.url());
        reef.put("token", document.token());
        reef.put("publicKey", document.publicKeyPem());
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("kind", KIND);
        root.put("version", VERSION);
        root.put("reef", reef);
        if (document.owner() != null) {
            root.put("owner", document.owner());
        }
        if (document.tokenName() != null) {
            root.put("tokenName", document.tokenName());
        }
        if (document.createdAt() != null) {
            root.put("createdAt", document.createdAt());
        }
        root.put("importedAt", importedAt.toString());
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot write reef-bootstrap.json");
        }
    }

    private static URI endpoint(URI base, String path) {
        String text = base.toString();
        if (!text.endsWith("/")) {
            text = text + "/";
        }
        return URI.create(text).resolve(path);
    }

    private static void restrict(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException ex) {
            // Non-POSIX file systems have no owner mode bits.
        }
    }

    private static ReefBootstrapException invalid(String message) {
        return new ReefBootstrapException(400, ErrorCode.INVALID_INPUT, message);
    }
}
