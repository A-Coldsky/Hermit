package dev.hermit.crab.engine;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回环上的原始套接字。测试读到的是请求行字节，不是 {@code HttpServer} 解码后的路径。
 */
final class RawLoopback implements AutoCloseable {
    private final ServerSocket server;
    private final CloseableHttpClient client;

    private RawLoopback(ServerSocket server, CloseableHttpClient client) {
        this.server = server;
        this.client = client;
    }

    static RawLoopback open() throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(3000);
        CloseableHttpClient client = HttpClients.custom()
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
        return new RawLoopback(server, client);
    }

    int port() {
        return server.getLocalPort();
    }

    Hit exchange(Map<String, Object> spec) throws Exception {
        return exchange(spec, "/", 200);
    }

    Hit exchange(Map<String, Object> spec, String basePath, int status) throws Exception {
        Attempt attempt = send(spec, basePath, status);
        if (attempt.problem != null) {
            throw attempt.problem;
        }
        return new Hit(attempt.captured, attempt.result);
    }

    /** 上游已经收到请求。5xx 时 {@code problem} 带着这次的 wire，结果为空。 */
    Attempt send(Map<String, Object> spec, String basePath, int status) throws Exception {
        server.setSoTimeout(3000);
        AtomicReference<Captured> box = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try (Socket socket = server.accept()) {
                Captured captured = Captured.read(socket);
                box.set(captured);
                socket.getOutputStream().write(response(status));
                socket.getOutputStream().flush();
            } catch (Exception ex) {
                failure.set(ex);
            }
        }, "raw-loopback");
        worker.start();
        HttpResult result = null;
        RuleProblem problem = null;
        try {
            result = HttpCalls.execute(
                    spec,
                    client,
                    () -> { },
                    URI.create("http://127.0.0.1:" + port() + basePath),
                    null,
                    System.nanoTime() + 5_000_000_000L);
        } catch (RuleProblem ex) {
            problem = ex;
        }
        worker.join(5000);
        assertThat(box.get()).as("request reached the socket: %s", failure.get()).isNotNull();
        return new Attempt(box.get(), result, problem);
    }

    /** 步骤在连接之前失败。套接字上不应出现请求。 */
    RuleProblem refuse(Map<String, Object> spec) throws Exception {
        RuleProblem problem;
        try {
            HttpCalls.execute(
                    spec,
                    client,
                    () -> { },
                    URI.create("http://127.0.0.1:" + port() + "/"),
                    null,
                    System.nanoTime() + 3_000_000_000L);
            throw new AssertionError("请求应当在连接前失败");
        } catch (RuleProblem ex) {
            problem = ex;
        }
        server.setSoTimeout(200);
        assertThatThrownBy(server::accept).isInstanceOf(SocketTimeoutException.class);
        return problem;
    }

    @Override
    public void close() throws IOException {
        try {
            client.close();
        } finally {
            server.close();
        }
    }

    private static byte[] response(int status) {
        return ("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    record Hit(Captured captured, HttpResult result) {
    }

    record Attempt(Captured captured, HttpResult result, RuleProblem problem) {
    }

    static final class Captured {
        final String requestLine;
        final String target;
        final List<String> headerLines;
        final Map<String, String> headers;
        final byte[] head;
        final byte[] body;

        private Captured(
                String requestLine,
                String target,
                List<String> headerLines,
                Map<String, String> headers,
                byte[] head,
                byte[] body
        ) {
            this.requestLine = requestLine;
            this.target = target;
            this.headerLines = headerLines;
            this.headers = headers;
            this.head = head;
            this.body = body;
        }

        static Captured read(Socket socket) throws IOException {
            socket.setSoTimeout(3000);
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] marker = {'\r', '\n', '\r', '\n'};
            int state = 0;
            while (state < marker.length) {
                int value = in.read();
                if (value < 0) {
                    throw new EOFException("请求头没有结束");
                }
                raw.write(value);
                if (value == marker[state]) {
                    state++;
                } else {
                    state = value == '\r' ? 1 : 0;
                }
            }
            byte[] head = raw.toByteArray();
            String text = new String(head, StandardCharsets.ISO_8859_1);
            String[] lines = text.split("\r\n", -1);
            String requestLine = lines[0];
            String[] parts = requestLine.split(" ", -1);
            if (parts.length != 3) {
                throw new IOException("请求行无法拆开: " + requestLine);
            }
            List<String> headerLines = new ArrayList<>();
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) {
                    continue;
                }
                headerLines.add(lines[i]);
                int colon = lines[i].indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = lines[i].substring(0, colon).trim();
                String value = lines[i].substring(colon + 1).trim();
                headers.putIfAbsent(name.toLowerCase(Locale.ROOT), value);
            }
            int length = 0;
            if (headers.containsKey("content-length")) {
                length = Integer.parseInt(headers.get("content-length"));
            }
            byte[] body = in.readNBytes(length);
            if (body.length != length) {
                throw new EOFException("正文短了");
            }
            return new Captured(requestLine, parts[1], headerLines, headers, head, body);
        }

        long headerCount(String name) {
            String prefix = name.toLowerCase(Locale.ROOT) + ":";
            return headerLines.stream().filter(line -> line.toLowerCase(Locale.ROOT).startsWith(prefix)).count();
        }
    }
}
