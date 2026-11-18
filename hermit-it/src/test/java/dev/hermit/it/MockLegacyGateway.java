package dev.hermit.it;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 回环上的老网关。读到的是请求行字节，不经过 {@code HttpServer} 的解码。
 * 命令表按原始查询串匹配：{@code func=mbox:getAllFolders} 成功，冒号被编成 {@code %3A} 时返回 2004。
 * 下面的期望串是手写的，不调用 {@code UrlComponents}。
 */
final class MockLegacyGateway implements AutoCloseable {
    static final String PASSWORD = "p+词:9";
    static final String SID = "gatesidvalue";

    /** 默认 URL 编码：冒号原样，空格 {@code %20}，{@code +} 是 {@code %2B}。 */
    static final String FOLDERS = "/api?func=mbox:getAllFolders&q=a%20b&plus=a%2Bb";
    /** {@code query_style: form}：冒号 {@code %3A}，空格是 {@code +}。 */
    static final String FOLDERS_FORM = "/api?func=mbox%3AgetAllFolders&q=a+b&plus=a%2Bb";
    /** UTF-8 路径。空格是 {@code %20}，中文不再变成问号。 */
    static final String CHINESE = "/mail/%E6%96%87%E4%BB%B6%E5%A4%B9%20%E4%B8%80";
    /** 相对路径里的 {@code //} 不合并。base 是 {@code /app/}。 */
    static final String SLASHES = "/app/a//b";
    /** GBK 的「邮件」。UTF-8 会是 {@code %E9%82%AE%E4%BB%B6}。 */
    static final String GBK_QUERY = "/gbk?q=%D3%CA%BC%FE";
    /** GBK 的「夹」。 */
    static final String GBK_FORM = "title=%BC%D0";

    static final String LOGIN_FORM = "password=p%2B%E8%AF%8D%3A9";
    /** query 保留冒号，path 保留 {@code +} 和冒号，form 把两者都编掉。UTF-8。 */
    static final String CARRY = "/echo/p+%E8%AF%8D:9?token=p%2B%E8%AF%8D:9";
    static final String CARRY_FORM = "token=p%2B%E8%AF%8D%3A9";
    /** 同一口令按 GBK。 */
    static final String CARRY_GBK = "/gbk-echo/p+%B4%CA:9?q=p%2B%B4%CA:9";
    static final String CARRY_GBK_FORM = "title=p%2B%B4%CA%3A9";
    static final String JSON_TEXT = "{\"token\":\"p+词:9\"}";

    static final String REDACTED_LOGIN = "password=***";
    static final String REDACTED_CARRY = "/echo/***?token=***";
    static final String REDACTED_CARRY_FORM = "token=***";
    static final String REDACTED_CARRY_PATH = "/echo/***";
    static final String REDACTED_CARRY_GBK = "/gbk-echo/***?q=***";
    static final String REDACTED_CARRY_GBK_FORM = "title=***";
    static final String REDACTED_CARRY_GBK_PATH = "/gbk-echo/***";
    static final String REDACTED_JSON = "{\"token\":\"***\"}";

    private final ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "legacy-gateway");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Hit> hits = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final Thread accept;
    private volatile boolean closed;

    private MockLegacyGateway(ServerSocket server) {
        this.server = server;
        this.accept = new Thread(this::acceptLoop, "legacy-gateway-accept");
        this.accept.setDaemon(true);
    }

    static MockLegacyGateway open() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(200);
        MockLegacyGateway gateway = new MockLegacyGateway(server);
        gateway.accept.start();
        return gateway;
    }

    int port() {
        return server.getLocalPort();
    }

    List<Hit> hits() {
        return List.copyOf(hits);
    }

    Hit hit(int index) {
        return hits.get(index);
    }

    String requestLines() {
        StringBuilder builder = new StringBuilder();
        for (Hit hit : hits) {
            builder.append(hit.requestLine()).append('\n');
        }
        if (!errors.isEmpty()) {
            builder.append(errors);
        }
        return builder.toString();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 测试结束，连接关掉即可。
            }
        }
        try {
            accept.join(5_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                workers.execute(() -> serve(socket));
            } catch (SocketTimeoutException ex) {
                // 继续等下一次连接，直到 close。
            } catch (IOException ex) {
                if (!closed) {
                    errors.add(ex.getClass().getSimpleName());
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(20_000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while (!closed && !socket.isClosed()) {
                byte[] headerBytes;
                try {
                    headerBytes = readHeaders(in);
                } catch (SocketTimeoutException | EOFException ex) {
                    return;
                }
                Parsed parsed = parse(headerBytes);
                if (parsed.expectContinue()) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                byte[] body = parsed.length() == 0 ? new byte[0] : readFully(in, parsed.length());
                Hit hit = new Hit(parsed.method(), parsed.target(), parsed.requestLine(), parsed.headers(), parsed.counts(), body);
                hits.add(hit);
                byte[] response = response(hit);
                out.write(response);
                out.flush();
                if ("close".equalsIgnoreCase(hit.header("connection"))) {
                    return;
                }
            }
        } catch (Exception ex) {
            if (!closed) {
                errors.add(ex.getClass().getSimpleName());
            }
        } finally {
            sockets.remove(socket);
        }
    }

    private static byte[] response(Hit hit) {
        boolean cookie = false;
        String json;
        if ("POST".equals(hit.method()) && "/login".equals(hit.target())) {
            cookie = Arrays.equals(hit.body(), LOGIN_FORM.getBytes(StandardCharsets.US_ASCII));
            json = cookie ? "{\"ok\":true}" : "{\"ok\":false}";
        } else if ("GET".equals(hit.method()) && FOLDERS.equals(hit.target())) {
            json = "{\"code\":0,\"name\":\"inbox\"}";
        } else if ("GET".equals(hit.method()) && FOLDERS_FORM.equals(hit.target())) {
            json = "{\"code\":2004}";
        } else if (hit.target().startsWith("/api")) {
            json = "{\"code\":-1,\"name\":\"\"}";
        } else {
            json = "{\"ok\":true}";
        }
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 200 OK\r\n");
        headers.append("Content-Type: application/json; charset=UTF-8\r\n");
        headers.append("Content-Length: ").append(payload.length).append("\r\n");
        if (cookie) {
            headers.append("Set-Cookie: sid=").append(SID).append("; Path=/\r\n");
        }
        headers.append("Connection: keep-alive\r\n\r\n");
        byte[] head = headers.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] response = Arrays.copyOf(head, head.length + payload.length);
        System.arraycopy(payload, 0, response, head.length, payload.length);
        return response;
    }

    private static Parsed parse(byte[] headerBytes) throws IOException {
        String head = new String(headerBytes, StandardCharsets.ISO_8859_1);
        int split = head.indexOf("\r\n\r\n");
        if (split < 0) {
            throw new IOException("missing header end");
        }
        String[] lines = head.substring(0, split).split("\r\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new IOException("missing request line");
        }
        String requestLine = lines[0];
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) {
            throw new IOException("bad request line");
        }
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            headers.putIfAbsent(name, value);
            counts.merge(name, 1, Integer::sum);
        }
        int length = 0;
        if (headers.containsKey("content-length")) {
            length = Integer.parseInt(headers.get("content-length"));
            if (length < 0 || length > 1_000_000) {
                throw new IOException("bad content length");
            }
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new IOException("chunked");
        }
        boolean expect = headers.getOrDefault("expect", "").toLowerCase(Locale.ROOT).contains("100-continue");
        return new Parsed(parts[0], parts[1], requestLine, headers, counts, length, expect);
    }

    private static byte[] readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int matched = 0;
        byte[] delimiter = {'\r', '\n', '\r', '\n'};
        while (matched < delimiter.length) {
            int value = in.read();
            if (value < 0) {
                throw new EOFException("headers");
            }
            out.write(value);
            if (value == delimiter[matched]) {
                matched++;
            } else {
                matched = value == delimiter[0] ? 1 : 0;
            }
            if (out.size() > 65_536) {
                throw new IOException("headers too large");
            }
        }
        return out.toByteArray();
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("body");
            }
            offset += read;
        }
        return buffer;
    }

    private record Parsed(
            String method,
            String target,
            String requestLine,
            Map<String, String> headers,
            Map<String, Integer> counts,
            int length,
            boolean expectContinue
    ) {
    }

    static final class Hit {
        private final String method;
        private final String target;
        private final String requestLine;
        private final Map<String, String> headers;
        private final Map<String, Integer> counts;
        private final byte[] body;

        private Hit(
                String method,
                String target,
                String requestLine,
                Map<String, String> headers,
                Map<String, Integer> counts,
                byte[] body
        ) {
            this.method = method;
            this.target = target;
            this.requestLine = requestLine;
            this.headers = Map.copyOf(headers);
            this.counts = Map.copyOf(counts);
            this.body = body.clone();
        }

        String method() {
            return method;
        }

        String target() {
            return target;
        }

        String requestLine() {
            return requestLine;
        }

        String header(String name) {
            return headers.get(name);
        }

        int headerCount(String name) {
            return counts.getOrDefault(name, 0);
        }

        byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            String shown = requestLine;
            List<String> secrets = List.of(
                    CARRY, CARRY_GBK, CARRY_FORM, CARRY_GBK_FORM, LOGIN_FORM, JSON_TEXT, PASSWORD, SID);
            ArrayList<String> ordered = new ArrayList<>(secrets);
            ordered.sort((left, right) -> Integer.compare(right.length(), left.length()));
            for (String secret : ordered) {
                shown = shown.replace(secret, "***");
            }
            return shown;
        }
    }
}
