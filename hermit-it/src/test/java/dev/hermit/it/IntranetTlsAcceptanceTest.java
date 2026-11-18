package dev.hermit.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.X509ExtendedKeyManager;

import dev.hermit.crab.CrabMain;
import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.tls.TlsTrust;
import dev.hermit.crab.tls.TrustCert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 内网 HTTPS 信任。Crab 以独立进程跑 {@code crab serve}，上游是回环上的 HTTPS。
 * 证书在测试里用 openssl 现生成，只把 leaf 发给客户端，不读真实证书，也不连真实站点。
 * 私有 CA 签的 leaf 没有 Authority Key Identifier。自签名 leaf 只在探测之后按指纹放行。
 */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class IntranetTlsAcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HTTP_CONCH = "tls_http";
    private static final String MCP_CONCH = "tls_mcp";
    private static final String PIN_CONCH = "tls_pin";
    private static final String ACCOUNT = "ada";
    private static final String CA_FILE = "lab-root.pem";
    private static final String UNTRUSTED = "上游证书不受信任";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path temp;

    private Path home;
    private CrabProc crab;
    private String apiKey;

    @Test
    void privateCaAndSelfSignedPinFollowTheConsole() throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(temp.resolve("ca"));
        assertThat(certs.leaf().getExtensionValue("2.5.29.35")).isNull();
        assertThat(certs.leaf().getBasicConstraints()).isNegative();
        Leaf selfSigned = selfSignedLeaf(temp.resolve("self"));
        assertThat(selfSigned.certificate().getSubjectX500Principal())
                .isEqualTo(selfSigned.certificate().getIssuerX500Principal());
        assertThat(selfSigned.certificate().getBasicConstraints()).isNegative();

        home = temp.resolve("crab");
        Files.createDirectories(home);
        try (LoopbackHttps intranet = LoopbackHttps.start(
                        new X509Certificate[] {certs.leaf()}, certs.leafKey(), true);
                LoopbackHttps pinned = LoopbackHttps.start(
                        new X509Certificate[] {selfSigned.certificate()}, selfSigned.key(), false)) {
            writeHome(intranet.port(), pinned.port());
            crab = CrabProc.start(home);
            try {
                exercise(certs, selfSigned, intranet, pinned);
            } finally {
                crab.close();
            }
        }
    }

    private void exercise(
            LabCertificates.Material certs,
            Leaf selfSigned,
            LoopbackHttps intranet,
            LoopbackHttps pinned
    ) throws Exception {
        enableDevMode();
        assertLoaded();
        apiKey = createKey();
        for (String conch : List.of(HTTP_CONCH, MCP_CONCH, PIN_CONCH)) {
            JsonNode account = consoleOk("POST", "/console/api/conches/" + conch + "/accounts",
                    "{\"name\":\"" + ACCOUNT + "\"}");
            assertThat(account.path("data").path("default").asBoolean()).isTrue();
        }

        apiKeyCannotReachTls(certs.root(), pinned.port());
        JsonNode before = trust();
        assertThat(names(before.path("caFiles"), "name")).doesNotContain(CA_FILE);
        assertThat(before.path("trustedHosts")).isEmpty();

        String leafSha = TrustCert.sha256(certs.leaf());
        HttpResponse<String> httpFailed = call(HTTP_CONCH);
        assertUntrusted(httpFailed, intranet.port(), leafSha, "Hermit Lab Root");
        assertThat(intranet.pings()).isZero();
        HttpResponse<String> mcpFailed = call(MCP_CONCH);
        assertUntrusted(mcpFailed, intranet.port(), leafSha, "Hermit Lab Root");
        assertThat(intranet.tools()).isZero();
        JsonNode remembered = failure(intranet.port());
        assertThat(remembered.path("kind").asText()).isEqualTo("TLS_UNTRUSTED");
        assertThat(remembered.path("chain").get(0).path("sha256").asText()).isEqualTo(leafSha);

        String pem = TrustCert.pem(certs.root());
        JsonNode imported = consoleOk("POST", "/console/api/tls/ca",
                JSON.writeValueAsString(Map.of("name", CA_FILE, "pem", pem)));
        assertThat(names(imported.path("data").path("caFiles"), "name")).contains(CA_FILE);
        assertThat(callOk(HTTP_CONCH).path("ok").asBoolean()).isTrue();
        assertThat(intranet.pings()).isEqualTo(1);
        assertThat(callOk(MCP_CONCH).path("ok").asBoolean()).isTrue();
        assertThat(intranet.tools()).isEqualTo(1);
        assertThat(intranet.initializes()).isEqualTo(1);

        JsonNode removed = consoleOk("DELETE", "/console/api/tls/ca/" + CA_FILE, null);
        assertThat(removed.path("data").path("deleted").asBoolean()).isTrue();
        assertThat(Files.exists(home.resolve("trust").resolve("ca").resolve(CA_FILE))).isFalse();
        assertUntrusted(call(HTTP_CONCH), intranet.port(), leafSha, "Hermit Lab Root");
        assertThat(intranet.pings()).isEqualTo(1);
        assertUntrusted(call(MCP_CONCH), intranet.port(), leafSha, "Hermit Lab Root");
        assertThat(intranet.tools()).isEqualTo(1);

        String pinSha = TrustCert.sha256(selfSigned.certificate());
        HttpResponse<String> unprobed = console("POST", "/console/api/tls/trusted-hosts",
                pinBody(pinned.port(), pinSha));
        assertThat(unprobed.statusCode()).as(detail(unprobed.body())).isEqualTo(400);
        assertThat(json(unprobed).path("error").path("message").asText()).isEqualTo(TlsTrust.NOT_SEEN);

        HttpResponse<String> foreignProbe = console("POST", "/console/api/tls/probe",
                JSON.writeValueAsString(Map.of("host", "127.0.0.1", "port", 1)));
        assertThat(foreignProbe.statusCode()).as(detail(foreignProbe.body())).isEqualTo(400);
        assertThat(json(foreignProbe).path("error").path("message").asText()).isEqualTo(TlsTrust.NOT_ALLOWED);
        HttpResponse<String> foreignPin = console("POST", "/console/api/tls/trusted-hosts", pinBody(1, pinSha));
        assertThat(foreignPin.statusCode()).isEqualTo(400);
        assertThat(json(foreignPin).path("error").path("message").asText()).isEqualTo(TlsTrust.NOT_ALLOWED);
        assertThat(pinned.pings()).isZero();

        JsonNode probed = consoleOk("POST", "/console/api/tls/probe",
                JSON.writeValueAsString(Map.of("host", "127.0.0.1", "port", pinned.port())));
        assertThat(probed.path("data").path("trusted").asBoolean()).isFalse();
        assertThat(probed.path("data").path("kind").asText()).isEqualTo("TLS_UNTRUSTED");
        assertThat(probed.path("data").path("chain").get(0).path("sha256").asText()).isEqualTo(pinSha);
        assertThat(probed.path("data").path("chain").get(0).path("pem").asText()).contains("BEGIN CERTIFICATE");

        HttpResponse<String> wrong = console("POST", "/console/api/tls/trusted-hosts", pinBody(pinned.port(), leafSha));
        assertThat(wrong.statusCode()).as(detail(wrong.body())).isEqualTo(400);
        assertThat(json(wrong).path("error").path("message").asText()).isEqualTo(TlsTrust.NOT_SEEN);

        JsonNode trusted = consoleOk("POST", "/console/api/tls/trusted-hosts", pinBody(pinned.port(), pinSha));
        assertThat(trusted.path("data").path("sha256").asText()).isEqualTo(pinSha);
        assertThat(trusted.path("data").path("createdBy").asText()).isEqualTo("console");
        assertThat(callOk(PIN_CONCH).path("ok").asBoolean()).isTrue();
        assertThat(pinned.pings()).isEqualTo(1);

        JsonNode unpinned = consoleOk(
                "DELETE", "/console/api/tls/trusted-hosts/127.0.0.1/" + pinned.port(), null);
        assertThat(unpinned.path("data").path("deleted").asBoolean()).isTrue();
        assertUntrusted(call(PIN_CONCH), pinned.port(), pinSha, "localhost");
        assertThat(pinned.pings()).isEqualTo(1);
    }

    private void apiKeyCannotReachTls(X509Certificate root, int pinPort) throws Exception {
        String pem = TrustCert.pem(root);
        List<String[]> calls = List.of(
                new String[] {"GET", "/console/api/tls/trust", null},
                new String[] {"GET", "/console/api/tls/failures", null},
                new String[] {"POST", "/console/api/tls/ca",
                        JSON.writeValueAsString(Map.of("name", CA_FILE, "pem", pem))},
                new String[] {"POST", "/console/api/tls/reload", "{}"},
                new String[] {"POST", "/console/api/tls/probe",
                        JSON.writeValueAsString(Map.of("host", "127.0.0.1", "port", 1))},
                new String[] {"POST", "/console/api/tls/trusted-hosts", pinBody(pinPort, "ab".repeat(32))},
                new String[] {"DELETE", "/console/api/tls/ca/" + CA_FILE, null},
                new String[] {"DELETE", "/console/api/tls/trusted-hosts/127.0.0.1/" + pinPort, null});
        for (String[] call : calls) {
            HttpResponse<String> response = api(call[0], call[1], call[2]);
            assertThat(response.statusCode()).as(call[0] + " " + call[1] + " " + detail(response.body())).isEqualTo(403);
            JsonNode error = json(response).path("error");
            assertThat(error.path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(error.path("message").asText()).isEqualTo("控制台只接受本机访问。");
        }
        HttpResponse<String> alternate = api("GET", "/console/api/tls/trust", null, true);
        assertThat(alternate.statusCode()).as(detail(alternate.body())).isEqualTo(403);
        assertThat(Files.exists(home.resolve("trust").resolve("ca").resolve(CA_FILE))).isFalse();
    }

    private void assertUntrusted(HttpResponse<String> response, int port, String sha256, String issuer) throws Exception {
        assertThat(response.statusCode()).as(detail(response.body())).isEqualTo(502);
        JsonNode body = json(response);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");
        String message = body.path("error").path("message").asText();
        assertThat(message).contains(UNTRUSTED).contains(issuer);
        assertThat(message).doesNotContain("上游连接失败");
        String traceId = body.path("traceId").asText();
        assertThat(traceId).isNotBlank();
        JsonNode detail = consoleOk("GET", "/console/api/calls/" + traceId, null).path("data");
        assertThat(detail.path("errorCode").asText()).isEqualTo("UPSTREAM_ERROR");
        assertThat(detail.path("errorMessage").asText()).isEqualTo(message);
        JsonNode transport = transport(detail.path("devTrace"));
        assertThat(transport).as(detail.toString()).isNotNull();
        assertThat(transport.path("kind").asText()).isEqualTo("TLS_UNTRUSTED");
        assertThat(transport.path("host").asText()).isEqualTo("127.0.0.1");
        assertThat(transport.path("port").asInt()).isEqualTo(port);
        assertThat(transport.path("cause").asText()).isNotBlank();
        JsonNode chain = transport.path("chain");
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).path("sha256").asText()).isEqualTo(sha256);
        assertThat(chain.get(0).has("pem")).isFalse();
        assertThat(chain.get(0).path("issuer").asText()).contains(issuer);
    }

    private JsonNode callOk(String conch) throws Exception {
        HttpResponse<String> response = call(conch);
        assertThat(response.statusCode()).as(detail(response.body())).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("success").asBoolean()).as(detail(response.body())).isTrue();
        return body.path("data");
    }

    private HttpResponse<String> call(String conch) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(crab.base + "/api/" + conch + "/ping"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode failure(int port) throws Exception {
        for (JsonNode row : consoleOk("GET", "/console/api/tls/failures", null).path("data").path("failures")) {
            if ("127.0.0.1".equals(row.path("host").asText()) && row.path("port").asInt() == port) {
                return row;
            }
        }
        throw new AssertionError("no failure for 127.0.0.1:" + port);
    }

    private JsonNode trust() throws Exception {
        return consoleOk("GET", "/console/api/tls/trust", null).path("data");
    }

    private void assertLoaded() throws Exception {
        JsonNode rows = consoleOk("GET", "/console/api/local-conches", null).path("data").path("conches");
        for (String conch : List.of(HTTP_CONCH, MCP_CONCH, PIN_CONCH)) {
            JsonNode row = null;
            for (JsonNode candidate : rows) {
                if (conch.equals(candidate.path("conch").asText())) {
                    row = candidate;
                }
            }
            assertThat(row).as(conch).isNotNull();
            assertThat(row.path("loaded").asBoolean()).as(row.toString()).isTrue();
            assertThat(row.path("broken").asBoolean()).isFalse();
        }
    }

    private void enableDevMode() throws Exception {
        JsonNode debug = consoleOk("POST", "/console/api/debug", "{\"enabled\":true}");
        assertThat(debug.path("data").path("enabled").asBoolean()).isTrue();
    }

    private String createKey() throws Exception {
        JsonNode created = consoleOk("POST", "/console/api/apikeys", "{\"name\":\"caller\",\"all\":true}");
        String plaintext = created.path("data").path("plaintext").asText();
        assertThat(plaintext).startsWith("hk_");
        return plaintext;
    }

    private static String pinBody(int port, String sha256) throws Exception {
        return JSON.writeValueAsString(Map.of("host", "127.0.0.1", "port", port, "sha256", sha256));
    }

    private JsonNode consoleOk(String method, String path, String body) throws Exception {
        HttpResponse<String> response = console(method, path, body);
        assertThat(response.statusCode()).as(detail(response.body())).isEqualTo(200);
        return json(response);
    }

    private HttpResponse<String> console(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(crab.base + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1");
        return send(builder, method, body);
    }

    private HttpResponse<String> api(String method, String path, String body) throws Exception {
        return api(method, path, body, false);
    }

    private HttpResponse<String> api(String method, String path, String body, boolean alternateHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(crab.base + path))
                .timeout(Duration.ofSeconds(20));
        if (alternateHeader) {
            builder.header("X-Hermit-Key", apiKey);
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return send(builder, method, body);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String method, String body) throws Exception {
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void writeHome(int intranetPort, int pinPort) throws Exception {
        Files.writeString(home.resolve("config.yml"), """
                server:
                  host: 127.0.0.1
                  port: 0
                report:
                  enabled: false
                engine:
                  capability_timeout_seconds: 20
                """);
        Path local = home.resolve("local-conches");
        Files.createDirectories(local);
        Files.writeString(local.resolve(HTTP_CONCH + ".yaml"), httpYaml(intranetPort));
        Files.writeString(local.resolve(MCP_CONCH + ".yaml"), mcpYaml(intranetPort));
        Files.writeString(local.resolve(PIN_CONCH + ".yaml"), httpYaml(PIN_CONCH, "自签名", pinPort));
    }

    private static String httpYaml(int port) {
        return httpYaml(HTTP_CONCH, "私有 CA", port);
    }

    private static String httpYaml(String conch, String note, int port) {
        return """
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 内网 HTTPS
                description: %s，只在本机。
                base_url: https://127.0.0.1:%d/
                credentials: {}
                actions:
                  ping:
                    title: 探测
                    description: 打到回环 HTTPS。
                    method: POST
                    idempotent: true
                    steps:
                      - request:
                          method: GET
                          path: /ping
                      - assert:
                          status: 200
                    output:
                      ok: { type: boolean, description: 结果, value: true }
                    examples:
                      - input: {}
                        output: { ok: true }
                    notes: 注意
                    related: [ping]
                """.formatted(conch, note, port);
    }

    private static String mcpYaml(int port) {
        return """
                conch: %s
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 内网 MCP
                description: 只在本机的 HTTPS 上游。
                credentials: {}
                upstream:
                  url: https://127.0.0.1:%d/mcp
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  ping:
                    tool: ping
                    title: 探测
                    description: 打到回环 HTTPS。
                    idempotent: true
                    mcp: {read_only: true}
                    input_schema: {type: object}
                    examples:
                      - input: {}
                        output: {ok: true}
                    notes: 只读。
                    related: [ping]
                """.formatted(MCP_CONCH, port);
    }

    private static Leaf selfSignedLeaf(Path directory) throws Exception {
        Files.createDirectories(directory);
        run(directory, "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256",
                "-keyout", "self.key", "-out", "self.crt", "-days", "825",
                "-subj", "/CN=localhost",
                "-addext", "basicConstraints=CA:FALSE",
                "-addext", "keyUsage=digitalSignature,keyEncipherment",
                "-addext", "extendedKeyUsage=serverAuth",
                "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate;
        try (InputStream in = Files.newInputStream(directory.resolve("self.crt"))) {
            certificate = (X509Certificate) factory.generateCertificate(in);
        }
        return new Leaf(certificate, privateKey(directory.resolve("self.key")));
    }

    private static PrivateKey privateKey(Path file) throws Exception {
        String pem = Files.readString(file);
        String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
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

    private static JsonNode transport(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject() && node.path("kind").asText("").startsWith("TLS_")) {
            return node;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                JsonNode found = transport(field.getValue());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = transport(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> names(JsonNode rows, String field) {
        ArrayList<String> values = new ArrayList<>();
        if (rows != null) {
            for (JsonNode row : rows) {
                values.add(row.path(field).asText());
            }
        }
        return values;
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private String detail(String body) {
        String logs = crab == null ? "" : crab.logs();
        if (logs.length() > 4000) {
            logs = logs.substring(logs.length() - 4000);
        }
        return redact(body) + "\n" + logs;
    }

    private static String redact(String body) {
        if (body == null) {
            return "";
        }
        return body.replaceAll("hk_[A-Za-z0-9_\\-]+", "hk_[redacted]");
    }

    private record Leaf(X509Certificate certificate, PrivateKey key) {
    }

    /** 只听 127.0.0.1。{@code mcp} 时同一端口同时回答 {@code GET /ping} 和 Streamable HTTP。 */
    private static final class LoopbackHttps implements AutoCloseable {
        private final SSLServerSocket server;
        private final ExecutorService workers;
        private final boolean mcp;
        private final AtomicInteger pings = new AtomicInteger();
        private final AtomicInteger tools = new AtomicInteger();
        private final AtomicInteger initializes = new AtomicInteger();
        private final AtomicReference<String> session = new AtomicReference<>("");

        private LoopbackHttps(SSLServerSocket server, ExecutorService workers, boolean mcp) {
            this.server = server;
            this.workers = workers;
            this.mcp = mcp;
        }

        private static LoopbackHttps start(X509Certificate[] chain, PrivateKey key, boolean mcp) throws Exception {
            AtomicReference<X509Certificate[]> certificates = new AtomicReference<>(chain);
            AtomicReference<PrivateKey> privateKey = new AtomicReference<>(key);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[] {new SelectingKeyManager(certificates, privateKey)}, null, null);
            SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            socket.setNeedClientAuth(false);
            socket.setWantClientAuth(false);
            ExecutorService workers = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "it-https");
                thread.setDaemon(true);
                return thread;
            });
            LoopbackHttps https = new LoopbackHttps(socket, workers, mcp);
            workers.submit(https::acceptLoop);
            return https;
        }

        private int port() {
            return server.getLocalPort();
        }

        private int pings() {
            return pings.get();
        }

        private int tools() {
            return tools.get();
        }

        private int initializes() {
            return initializes.get();
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // 测试结束。
            }
            workers.shutdownNow();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    workers.submit(() -> respond(socket));
                } catch (IOException ex) {
                    if (server.isClosed()) {
                        return;
                    }
                }
            }
        }

        private void respond(Socket socket) {
            try (Socket client = socket) {
                client.setSoTimeout(10_000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                byte[] header = readHeaders(in);
                if (header == null) {
                    return;
                }
                String text = new String(header, StandardCharsets.ISO_8859_1);
                String[] lines = text.split("\r\n");
                String[] request = lines[0].split(" ");
                if (request.length < 2) {
                    return;
                }
                String method = request[0];
                String path = request[1];
                int query = path.indexOf('?');
                if (query >= 0) {
                    path = path.substring(0, query);
                }
                Map<String, String> headers = headers(lines);
                int length = 0;
                if (headers.containsKey("content-length")) {
                    length = Integer.parseInt(headers.get("content-length").trim());
                }
                byte[] body = readBody(in, length);
                if ("GET".equals(method) && "/ping".equals(path)) {
                    pings.incrementAndGet();
                    write(out, 200, "OK", "text/plain", "ok".getBytes(StandardCharsets.US_ASCII), null);
                    return;
                }
                if (mcp && "POST".equals(method) && "/mcp".equals(path)) {
                    writeMcp(out, body, headers);
                    return;
                }
                write(out, 404, "Not Found", "text/plain", "missing".getBytes(StandardCharsets.US_ASCII), null);
            } catch (Exception ignored) {
                // 握手失败或对端关掉连接。
            }
        }

        private void writeMcp(OutputStream out, byte[] body, Map<String, String> headers) throws Exception {
            JsonNode node = body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
            String method = node.path("method").asText("");
            if ("notifications/initialized".equals(method)) {
                write(out, 202, "Accepted", null, new byte[0], session.get());
                return;
            }
            if ("initialize".equals(method)) {
                initializes.incrementAndGet();
                String id = "it-tls-" + initializes.get();
                session.set(id);
                ObjectNode result = JSON.createObjectNode();
                result.put("protocolVersion", "2025-11-25");
                result.putObject("capabilities");
                ObjectNode info = result.putObject("serverInfo");
                info.put("name", "it-tls");
                info.put("version", "1");
                write(out, 200, "OK", "application/json", JSON.writeValueAsBytes(envelope(node.get("id"), result)), id);
                return;
            }
            if ("tools/call".equals(method)) {
                String seen = headers.get("mcp-session-id");
                if (seen == null || !seen.equals(session.get())) {
                    write(out, 404, "Not Found", "application/json",
                            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Session not found\"},\"id\":null}"
                                    .getBytes(StandardCharsets.UTF_8),
                            null);
                    return;
                }
                tools.incrementAndGet();
                ObjectNode result = JSON.createObjectNode();
                result.putArray("content").addObject().put("type", "text").put("text", "pong");
                result.putObject("structuredContent").put("ok", true);
                write(out, 200, "OK", "application/json",
                        JSON.writeValueAsBytes(envelope(node.get("id"), result)), session.get());
                return;
            }
            write(out, 400, "Bad Request", "text/plain", "bad".getBytes(StandardCharsets.US_ASCII), null);
        }

        private static ObjectNode envelope(JsonNode id, ObjectNode result) {
            ObjectNode envelope = JSON.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            if (id == null || id.isNull() || id.isMissingNode()) {
                envelope.put("id", 1);
            } else {
                envelope.set("id", id);
            }
            envelope.set("result", result);
            return envelope;
        }

        private static Map<String, String> headers(String[] lines) {
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int split = lines[i].indexOf(':');
                if (split > 0) {
                    headers.put(lines[i].substring(0, split).trim().toLowerCase(Locale.ROOT), lines[i].substring(split + 1).trim());
                }
            }
            return headers;
        }

        private static byte[] readHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int previous = 0;
            while (header.size() < 16384) {
                int value = in.read();
                if (value < 0) {
                    return header.size() == 0 ? null : header.toByteArray();
                }
                header.write(value);
                if (previous == '\r' && value == '\n' && header.size() >= 4) {
                    byte[] bytes = header.toByteArray();
                    int end = bytes.length;
                    if (bytes[end - 4] == '\r' && bytes[end - 3] == '\n' && bytes[end - 2] == '\r' && bytes[end - 1] == '\n') {
                        return bytes;
                    }
                }
                previous = value;
            }
            throw new IOException("header too large");
        }

        private static byte[] readBody(InputStream in, int length) throws IOException {
            if (length < 0 || length > 65536) {
                throw new IOException("body length");
            }
            byte[] body = in.readNBytes(length);
            if (body.length != length) {
                throw new IOException("short body");
            }
            return body;
        }

        private static void write(
                OutputStream out, int status, String reason, String type, byte[] body, String session
        ) throws IOException {
            StringBuilder header = new StringBuilder();
            header.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
            if (type != null) {
                header.append("Content-Type: ").append(type).append("\r\n");
            }
            if (session != null) {
                header.append("mcp-session-id: ").append(session).append("\r\n");
            }
            header.append("Content-Length: ").append(body.length).append("\r\n");
            header.append("Connection: close\r\n\r\n");
            out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }
    }

    private static final class SelectingKeyManager extends X509ExtendedKeyManager {
        private final AtomicReference<X509Certificate[]> chain;
        private final AtomicReference<PrivateKey> key;

        private SelectingKeyManager(AtomicReference<X509Certificate[]> chain, AtomicReference<PrivateKey> key) {
            this.chain = chain;
            this.key = key;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[] {"lab"};
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return "lab";
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return chain.get();
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key.get();
        }
    }

    private static final class CrabProc implements AutoCloseable {
        private final Process process;
        private final StringBuilder out;
        private final StringBuilder err;
        private final String base;
        private boolean closed;

        private CrabProc(Process process, StringBuilder out, StringBuilder err, String base) {
            this.process = process;
            this.out = out;
            this.err = err;
            this.base = base;
        }

        private static CrabProc start(Path home) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    System.getProperty("java.class.path"),
                    CrabMain.class.getName(),
                    "serve");
            builder.directory(home.toFile());
            builder.environment().put("HERMIT_HOME", home.toAbsolutePath().toString());
            builder.environment().remove("HERMIT_MASTER_KEY");
            builder.environment().remove("HERMIT_CONSOLE_PASSWORD");
            builder.environment().remove("HERMIT_API_KEY");
            builder.redirectInput(ProcessBuilder.Redirect.PIPE);
            Process process = builder.start();
            process.getOutputStream().close();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            pump(process.getInputStream(), out);
            pump(process.getErrorStream(), err);
            try {
                String line = awaitListening(process, out, err);
                return new CrabProc(process, out, err, "http://" + line.substring("listening on ".length()).trim());
            } catch (Throwable ex) {
                process.destroyForcibly();
                throw ex;
            }
        }

        private static String awaitListening(Process process, StringBuilder out, StringBuilder err) throws InterruptedException {
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < end) {
                synchronized (out) {
                    for (String line : out.toString().split("\n", -1)) {
                        if (line.startsWith("listening on 127.0.0.1:")) {
                            return line;
                        }
                    }
                    if (!process.isAlive()) {
                        throw new AssertionError("crab exited " + process.exitValue() + "\n" + text(out) + text(err));
                    }
                    out.wait(200);
                }
            }
            throw new AssertionError("crab did not listen\n" + text(out) + text(err));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(20, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }

        private String logs() {
            return redact(text(out) + text(err));
        }
    }

    private static Thread pump(InputStream input, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                        sink.notifyAll();
                    }
                }
            } catch (Exception ex) {
                synchronized (sink) {
                    sink.append(ex.getClass().getName()).append('\n');
                    sink.notifyAll();
                }
            }
        }, "crab-log");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String text(StringBuilder builder) {
        synchronized (builder) {
            return builder.toString();
        }
    }
}
