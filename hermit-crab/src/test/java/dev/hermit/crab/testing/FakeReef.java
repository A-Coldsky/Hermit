package dev.hermit.crab.testing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.VerifiedConch;

public final class FakeReef implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final int port;
    private volatile boolean listening = true;
    private final KeyPair keys = Ed25519Keys.generate();
    private final Object lock = new Object();
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> reportBodies = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> downloadStatuses = new LinkedHashMap<>();
    private int etagSalt;
    private int manifestStatus = 200;
    private int reportStatus = 200;
    private boolean redirectManifest;
    private String expectedToken;
    private boolean tokenHeaderSeen;
    private boolean tokenMatched;
    private String latestVersion = "1.4.0";
    private String downloadUrl;

    public FakeReef() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        downloadUrl = base() + "/agent/crab.jar";
        server.createContext("/", this::handle);
        server.start();
    }

    public URI base() {
        return URI.create("http://127.0.0.1:" + port + "/");
    }

    /** Stop accepting connections so clients fail immediately. {@link #listen()} binds the same URL again. */
    public void disconnect() {
        if (!listening) {
            return;
        }
        listening = false;
        server.stop(0);
    }

    /** Bind {@link #base()} again after {@link #disconnect()}. */
    public void listen() throws IOException {
        if (listening) {
            return;
        }
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                HttpServer next = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                next.createContext("/", this::handle);
                next.start();
                server = next;
                listening = true;
                return;
            } catch (IOException ex) {
                last = ex;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw last;
    }

    public KeyPair keys() {
        return keys;
    }

    public void expectToken(String token) {
        expectedToken = token;
    }

    public boolean tokenHeaderSeen() {
        return tokenHeaderSeen;
    }

    public boolean tokenMatched() {
        return tokenMatched;
    }

    public void manifestStatus(int status) {
        manifestStatus = status;
    }

    /** Next downloads of {@code conch} return {@code status} and an empty body. 200 serves the package. */
    public void downloadStatus(String conch, int status) {
        synchronized (lock) {
            if (status == 200) {
                downloadStatuses.remove(conch);
            } else {
                downloadStatuses.put(conch, status);
            }
        }
    }

    public void redirectManifest() {
        redirectManifest = true;
    }

    public void reportStatus(int status) {
        reportStatus = status;
    }

    public void bustEtag() {
        synchronized (lock) {
            etagSalt++;
        }
    }

    public String latestVersion() {
        return latestVersion;
    }

    public String downloadUrl() {
        return downloadUrl;
    }

    public List<String> paths() {
        return List.copyOf(paths);
    }

    public List<String> reportBodies() {
        return List.copyOf(reportBodies);
    }

    public int downloads() {
        int count = 0;
        for (String path : paths) {
            if (path.startsWith("/agent/conches/") && !"/agent/conches/manifest".equals(path)) {
                count++;
            }
        }
        return count;
    }

    public void publish(String yaml) {
        byte[] zip = ConchPackage.write(yaml.getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        VerifiedConch verified = ConchPackage.readVerified(zip, keys.getPublic());
        put(new Entry(
                verified.conchDoc().conch(),
                verified.manifest().version(),
                verified.manifest().sha256(),
                verified.manifest().minCrabVersion(),
                verified.conchDoc().title(),
                zip));
    }

    public void publishSignedBy(String yaml, PrivateKey privateKey, java.security.PublicKey publicKey) {
        byte[] zip = ConchPackage.write(yaml.getBytes(StandardCharsets.UTF_8), privateKey);
        VerifiedConch verified = ConchPackage.readVerified(zip, publicKey);
        put(new Entry(
                verified.conchDoc().conch(),
                verified.manifest().version(),
                verified.manifest().sha256(),
                verified.manifest().minCrabVersion(),
                verified.conchDoc().title(),
                zip));
    }

    public void publishTamperedSignature(String yaml) {
        byte[] zip = ConchPackage.write(yaml.getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        VerifiedConch verified = ConchPackage.readVerified(zip, keys.getPublic());
        Map<String, byte[]> files = unzip(zip);
        byte[] signature = files.get("signature.sig");
        signature[0] ^= 0x01;
        put(new Entry(
                verified.conchDoc().conch(),
                verified.manifest().version(),
                verified.manifest().sha256(),
                verified.manifest().minCrabVersion(),
                verified.conchDoc().title(),
                rezip(files)));
    }

    public void publishWrongSha(String yaml) {
        byte[] zip = ConchPackage.write(yaml.getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        VerifiedConch verified = ConchPackage.readVerified(zip, keys.getPublic());
        put(new Entry(
                verified.conchDoc().conch(),
                verified.manifest().version(),
                "0000000000000000000000000000000000000000000000000000000000000000",
                verified.manifest().minCrabVersion(),
                verified.conchDoc().title(),
                zip));
    }

    public void catalog(String conch, String version) {
        put(new Entry(conch, version, "abc", "1.0.0", "标题", new byte[0]));
    }

    public void publishOversize(String conch) {
        byte[] body = new byte[1024 * 1024 + 1];
        put(new Entry(conch, "1.0.0", "abc", "1.0.0", "标题", body));
    }

    public void remove(String conch) {
        synchronized (lock) {
            entries.removeIf(entry -> entry.conch.equals(conch));
        }
    }

    @Override
    public void close() {
        if (!listening) {
            return;
        }
        listening = false;
        server.stop(0);
    }

    private void put(Entry entry) {
        synchronized (lock) {
            entries.removeIf(existing -> existing.conch.equals(entry.conch));
            entries.add(entry);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        paths.add(path);
        try {
            if (!authorized(exchange)) {
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/agent/conches/manifest".equals(path)) {
                manifest(exchange);
                return;
            }
            if ("GET".equals(method) && "/agent/version".equals(path)) {
                version(exchange);
                return;
            }
            if ("POST".equals(method) && "/agent/reports".equals(path)) {
                reports(exchange);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/agent/conches/")) {
                download(exchange, path);
                return;
            }
            send(exchange, 404, new byte[0], null, null);
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        String instance = exchange.getRequestHeaders().getFirst("X-Hermit-Instance");
        if (instance == null || instance.isBlank()) {
            send(exchange, 400, new byte[0], null, null);
            return false;
        }
        String token = exchange.getRequestHeaders().getFirst("X-Hermit-Token");
        if (token != null) {
            tokenHeaderSeen = true;
        }
        if (expectedToken != null) {
            if (!expectedToken.equals(token)) {
                send(exchange, 401, new byte[0], null, null);
                return false;
            }
            tokenMatched = true;
        }
        return true;
    }

    private void manifest(HttpExchange exchange) throws IOException {
        if (redirectManifest) {
            exchange.getResponseHeaders().set("Location", "/agent/stolen");
            exchange.sendResponseHeaders(302, -1);
            return;
        }
        if (manifestStatus != 200) {
            send(exchange, manifestStatus, new byte[0], null, null);
            return;
        }
        String etag;
        byte[] body;
        synchronized (lock) {
            etag = etag();
            body = manifestBody();
        }
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            send(exchange, 304, null, null, etag);
            return;
        }
        send(exchange, 200, body, "application/json", etag);
    }

    private void version(HttpExchange exchange) throws IOException {
        ObjectNode data = JSON.createObjectNode();
        data.put("latestVersion", latestVersion);
        data.put("downloadUrl", downloadUrl);
        send(exchange, 200, envelope(data).toString().getBytes(StandardCharsets.UTF_8), "application/json", null);
    }

    private void reports(HttpExchange exchange) throws IOException {
        reportBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        send(exchange, reportStatus, new byte[0], null, null);
    }

    private void download(HttpExchange exchange, String path) throws IOException {
        String rest = path.substring("/agent/conches/".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            send(exchange, 404, new byte[0], null, null);
            return;
        }
        String conch = rest.substring(0, slash);
        String version = rest.substring(slash + 1);
        Integer forced;
        Entry match;
        synchronized (lock) {
            forced = downloadStatuses.get(conch);
            match = entries.stream()
                    .filter(entry -> entry.conch.equals(conch) && entry.version.equals(version))
                    .findFirst()
                    .orElse(null);
        }
        if (forced != null) {
            send(exchange, forced, new byte[0], null, null);
            return;
        }
        if (match == null) {
            send(exchange, 404, new byte[0], null, null);
            return;
        }
        send(exchange, 200, match.body, "application/zip", null);
    }

    private byte[] manifestBody() {
        ArrayNode data = JSON.createArrayNode();
        entries.stream().sorted(Comparator.comparing(entry -> entry.conch)).forEach(entry -> {
            ObjectNode node = data.addObject();
            node.put("conch", entry.conch);
            node.put("version", entry.version);
            node.put("sha256", entry.sha256);
            node.put("minCrabVersion", entry.minCrabVersion);
            node.put("title", entry.title);
        });
        return envelope(data).toString().getBytes(StandardCharsets.UTF_8);
    }

    private String etag() {
        StringBuilder payload = new StringBuilder();
        entries.stream()
                .sorted(Comparator.comparing(entry -> entry.conch))
                .forEach(entry -> payload.append(entry.conch).append('|').append(entry.version).append('|')
                        .append(entry.sha256).append('|').append(etagSalt).append('\n'));
        return "\"" + sha256(payload.toString().getBytes(StandardCharsets.UTF_8)) + "\"";
    }

    private static ObjectNode envelope(com.fasterxml.jackson.databind.JsonNode data) {
        ObjectNode root = JSON.createObjectNode();
        root.put("success", true);
        root.set("data", data);
        root.putNull("error");
        root.put("traceId", "trace-reef");
        return root;
    }

    private static void send(HttpExchange exchange, int status, byte[] body, String contentType, String etag)
            throws IOException {
        if (etag != null) {
            exchange.getResponseHeaders().set("ETag", etag);
        }
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip) {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return files;
    }

    private static byte[] rezip(Map<String, byte[]> files) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String name : List.of("conch.yaml", "manifest.json", "signature.sig")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(files.get(name));
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return buffer.toByteArray();
    }

    private record Entry(String conch, String version, String sha256, String minCrabVersion, String title, byte[] body) {
    }
}
