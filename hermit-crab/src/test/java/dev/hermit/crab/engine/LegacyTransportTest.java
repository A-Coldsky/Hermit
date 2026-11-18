package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.sync.SyncException;
import dev.hermit.crab.tls.OutboundTls;
import dev.hermit.crab.tls.TransportFailure;

class LegacyTransportTest {

    @Test
    void localhostRefusedNamesBothPeers() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = closed.getLocalPort();
        }
        RuleProblem refused = failure(Map.of("url", "http://localhost:" + port + "/"));
        assertThat(refused.transport).containsEntry("kind", "REFUSED");
        assertThat(refused.summary).contains("localhost:" + port).contains("127.0.0.1").contains("::1");
        assertThat(refused.summary).doesNotContain("Connection refused");
    }

    @Test
    void silentCloseAndLongHeaderStaySpecific() throws Exception {
        try (Loopback silent = Loopback.start(socket -> {
            readHeaders(socket.getInputStream());
        })) {
            RuleProblem noResponse = failure(Map.of("url", "http://127.0.0.1:" + silent.port() + "/"));
            assertThat(noResponse.transport).containsEntry("kind", "NO_RESPONSE");
            assertThat(noResponse.summary).isEqualTo("上游没有返回 HTTP 响应。");
            assertThat(String.valueOf(noResponse.transport.get("cause"))).contains("NoHttpResponseException");
        }

        try (Loopback huge = Loopback.start(socket -> {
            readHeaders(socket.getInputStream());
            byte[] line = ("X-Long: " + "a".repeat(9000) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(line);
            socket.getOutputStream().flush();
        })) {
            RuleProblem header = failure(Map.of("url", "http://127.0.0.1:" + huge.port() + "/"));
            assertThat(header.transport).containsEntry("kind", "HEADER_LIMIT");
            assertThat(header.summary).isEqualTo("上游响应头超过长度或个数上限。");
        }
    }

    @Test
    void trailingDotStillReachesTheListeningAddress() throws Exception {
        try (Loopback server = Loopback.start(LegacyTransportTest::ok)) {
            HttpResult ip = call(Map.of("url", "http://127.0.0.1.:" + server.port() + "/ping"));
            assertThat(ip.status()).isEqualTo(200);
            assertThat(ip.body()).isEqualTo("ok");
            HttpResult name = call(Map.of("url", "http://localhost.:" + server.port() + "/ping"));
            assertThat(name.status()).isEqualTo(200);
        }
        assertThat(HttpCalls.sameOrigin(
                URI.create("http://localhost.:9/a"),
                URI.create("http://localhost:9/b"))).isTrue();
    }

    @Test
    void tlsAlertsClassifyProtocolCipherAndSni() throws Exception {
        assertAlert(70, "TLS_PROTOCOL", "旧协议");
        assertAlert(40, "TLS_CIPHER", "常见原因是对方只提供已禁用的加密套件");
        assertAlert(112, "TLS_SNI", "SNI");
    }

    @Test
    void syncClassifiesTheSameProtocolAlert() throws Exception {
        try (Loopback alert = alertServer(70)) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + alert.port() + "/"))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            IOException failure = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    client.send(request, HttpResponse.BodyHandlers.discarding());
                } catch (IOException ex) {
                    failure = ex;
                    break;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
            assertThat(failure).isNotNull();
            assertThat(new SyncException("reef request failed", failure).clientError().orElseThrow().message())
                    .contains("旧协议");
        }
    }

    @Test
    void rsa512LeafIsRejectedForTheCertificate(@TempDir Path dir) throws Exception {
        Path work = dir.resolve("rsa512");
        Files.createDirectories(work);
        write(work.resolve("leaf.ext"), """
                basicConstraints=CA:FALSE
                keyUsage=digitalSignature,keyEncipherment
                extendedKeyUsage=serverAuth
                subjectAltName=IP:127.0.0.1
                """);
        write(work.resolve("openssl.cnf"), """
                openssl_conf = openssl_init
                [openssl_init]
                ssl_conf = ssl_sect
                [ssl_sect]
                system_default = system_default_sect
                [system_default_sect]
                CipherString = DEFAULT@SECLEVEL=0
                """);
        run(work, "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256",
                "-keyout", "root.key", "-out", "root.crt", "-days", "30",
                "-subj", "/CN=Hermit Lab Root");
        run(work, "openssl", "req", "-newkey", "rsa:512", "-nodes", "-sha256",
                "-keyout", "leaf.key", "-out", "leaf.csr", "-subj", "/CN=127.0.0.1");
        run(work, "openssl", "x509", "-req", "-in", "leaf.csr", "-sha256",
                "-CA", "root.crt", "-CAkey", "root.key", "-CAcreateserial",
                "-out", "leaf.crt", "-days", "30", "-extfile", "leaf.ext");
        Path caDir = dir.resolve("ca");
        Files.createDirectories(caDir);
        Files.copy(work.resolve("root.crt"), caDir.resolve("root.crt"));
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = free.getLocalPort();
        }
        ProcessBuilder serverBuilder = new ProcessBuilder(
                "openssl", "s_server",
                "-accept", "127.0.0.1:" + port,
                "-cert", "leaf.crt",
                "-key", "leaf.key",
                "-CAfile", "root.crt",
                "-www",
                "-tls1_2");
        serverBuilder.directory(work.toFile());
        serverBuilder.redirectErrorStream(true);
        serverBuilder.environment().put("OPENSSL_CONF", work.resolve("openssl.cnf").toString());
        Process server = serverBuilder.start();
        StringBuilder output = new StringBuilder();
        Thread pump = new Thread(() -> {
            try {
                output.append(new String(server.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 进程结束。
            }
        });
        pump.setDaemon(true);
        pump.start();
        OutboundTls tls = OutboundTls.open(caDir, true);
        OutboundTls previous = OutboundTls.install(tls);
        try {
            RuleProblem problem = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                if (!server.isAlive()) {
                    break;
                }
                problem = failure(Map.of("url", "https://127.0.0.1:" + port + "/"));
                if (problem.transport != null && !"REFUSED".equals(problem.transport.get("kind"))) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(problem).as(output.toString()).isNotNull();
            assertThat(problem.transport).as(output.toString()).containsEntry("kind", "TLS_CERT_ALGORITHM");
            assertThat(problem.summary).contains("RSA 512");
            assertThat(problem.summary).doesNotContain("BEGIN CERTIFICATE");
        } finally {
            OutboundTls.restore(previous);
            server.destroyForcibly();
            server.waitFor(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void traceRedactsTheCauseButKeepsTheKind() {
        String secret = "token-secret-value";
        TransportFailure.View view = TransportFailure.classify(
                new SSLHandshakeException("Received fatal alert: protocol_version " + secret), "hr", 443);
        assertThat(view.message()).doesNotContain(secret);
        DevTraceDraft draft = new DevTraceDraft(Instant.now().plusSeconds(60));
        Map<String, Object> piece = new LinkedHashMap<>(DevTraceDraft.requestPiece(
                Map.of(), "GET", "/", null, Map.of(), view.message()));
        piece.put("transport", view.fields());
        draft.add("request", 0, 1, piece);
        String rendered = draft.render(Redactor.of(Set.of(secret)), List.of(), false);
        assertThat(rendered).doesNotContain(secret);
        assertThat(rendered).contains("TLS_PROTOCOL");
    }

    private static void assertAlert(int description, String kind, String message) throws Exception {
        try (Loopback alert = alertServer(description)) {
            RuleProblem problem = failure(Map.of("url", "https://127.0.0.1:" + alert.port() + "/"));
            assertThat(problem.transport).containsEntry("kind", kind);
            assertThat(problem.summary).contains(message);
        }
    }

    private static Loopback alertServer(int description) throws IOException {
        return Loopback.start(socket -> {
            socket.setSoTimeout(2000);
            byte[] buffer = new byte[1024];
            InputStream in = socket.getInputStream();
            int total = 0;
            while (total < 5) {
                int read = in.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    return;
                }
                total += read;
            }
            socket.getOutputStream().write(new byte[] {
                    0x15, 0x03, 0x03, 0x00, 0x02, 0x02, (byte) description
            });
            socket.getOutputStream().flush();
        });
    }

    private static RuleProblem failure(Map<String, Object> spec) throws Exception {
        try (SessionManager sessions = new SessionManager()) {
            SessionKey key = new SessionKey(Origin.LOCAL, "lab", "a");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                return failure(sessions.client(key), spec);
            } finally {
                sessions.unlock(key);
            }
        }
    }

    private static HttpResult call(Map<String, Object> spec) throws Exception {
        try (SessionManager sessions = new SessionManager()) {
            SessionKey key = new SessionKey(Origin.LOCAL, "lab", "a");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                URI base = URI.create(String.valueOf(spec.get("url")));
                return HttpCalls.execute(spec, sessions.client(key), () -> { }, base, null, deadline());
            } finally {
                sessions.unlock(key);
            }
        }
    }

    private static RuleProblem failure(CloseableHttpClient client, Map<String, Object> spec) {
        URI base = URI.create(String.valueOf(spec.get("url")));
        try {
            HttpCalls.execute(spec, client, () -> { }, base, null, deadline());
        } catch (RuleProblem problem) {
            return problem;
        }
        throw new AssertionError("expected a RuleProblem");
    }

    private static void ok(Socket socket) throws IOException {
        readHeaders(socket.getInputStream());
        byte[] body = "ok".getBytes(StandardCharsets.US_ASCII);
        socket.getOutputStream().write((
                "HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static void readHeaders(InputStream in) throws IOException {
        byte[] buffer = new byte[1];
        int matched = 0;
        int[] marker = {'\r', '\n', '\r', '\n'};
        while (matched < marker.length) {
            int read = in.read(buffer);
            if (read < 0) {
                return;
            }
            matched = buffer[0] == marker[matched] ? matched + 1 : (buffer[0] == '\r' ? 1 : 0);
        }
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text);
    }

    private static void run(Path directory, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("OPENSSL_CONF", directory.resolve("openssl.cnf").toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + "\n" + output);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void accept(Socket socket) throws IOException;
    }

    private static final class Loopback implements AutoCloseable {
        private final ServerSocket server;

        private Loopback(ServerSocket server) {
            this.server = server;
        }

        static Loopback start(Handler handler) throws IOException {
            ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        handler.accept(socket);
                    } catch (IOException ignored) {
                        // 测试结束，或这一轮提前断开。
                    }
                }
            }, "legacy-loopback");
            thread.setDaemon(true);
            thread.start();
            return new Loopback(server);
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
