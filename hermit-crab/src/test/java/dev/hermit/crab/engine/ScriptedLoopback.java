package dev.hermit.crab.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 回环上按顺序回放预先写好的响应，并留下每一跳的请求行和头。 */
final class ScriptedLoopback implements AutoCloseable {

    private final ServerSocket server;
    private final ExecutorService workers;
    private final ConcurrentLinkedQueue<byte[]> script = new ConcurrentLinkedQueue<>();
    private final List<Hit> hits = Collections.synchronizedList(new ArrayList<>());

    private ScriptedLoopback(ServerSocket server, ExecutorService workers) {
        this.server = server;
        this.workers = workers;
    }

    static ScriptedLoopback start() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        ExecutorService workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "scripted-loopback");
            thread.setDaemon(true);
            return thread;
        });
        ScriptedLoopback loop = new ScriptedLoopback(server, workers);
        workers.submit(loop::acceptLoop);
        return loop;
    }

    int port() {
        return server.getLocalPort();
    }

    String base() {
        return "http://127.0.0.1:" + port();
    }

    void reply(String raw) {
        script.add(raw.getBytes(StandardCharsets.ISO_8859_1));
    }

    void reply(byte[] raw) {
        script.add(raw);
    }

    List<Hit> hits() {
        return List.copyOf(hits);
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
                workers.submit(() -> handle(socket));
            } catch (IOException ex) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(5_000);
            InputStream in = client.getInputStream();
            byte[] head = readHeaders(in);
            if (head == null) {
                return;
            }
            String text = new String(head, StandardCharsets.ISO_8859_1);
            int split = text.indexOf("\r\n");
            String requestLine = split < 0 ? text.trim() : text.substring(0, split);
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String target = parts.length > 1 ? parts[1] : "";
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            for (String line : text.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                headers.putIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            int length = 0;
            if (headers.containsKey("content-length")) {
                try {
                    length = Integer.parseInt(headers.get("content-length"));
                } catch (NumberFormatException ignored) {
                    length = 0;
                }
            }
            byte[] body = length <= 0 ? new byte[0] : in.readNBytes(length);
            hits.add(new Hit(requestLine, method, target, headers, body));
            byte[] response = script.poll();
            if (response == null) {
                response = "HTTP/1.1 500 NOSCRIPT\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII);
            }
            OutputStream out = client.getOutputStream();
            out.write(response);
            out.flush();
        } catch (IOException ignored) {
            // 对端关掉连接。
        }
    }

    private static byte[] readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int previous = 0;
        int breaks = 0;
        while (header.size() < 65_536) {
            int value = in.read();
            if (value < 0) {
                return header.size() == 0 ? null : header.toByteArray();
            }
            header.write(value);
            if (previous == '\r' && value == '\n') {
                breaks++;
                if (breaks == 2) {
                    return header.toByteArray();
                }
            } else if (value != '\r') {
                breaks = 0;
            }
            previous = value;
        }
        return header.toByteArray();
    }

    static byte[] response(int status, String contentType, byte[] body, String... extra) {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(" X\r\n");
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        for (String line : extra) {
            head.append(line).append("\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        byte[] prefix = head.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] raw = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, raw, 0, prefix.length);
        System.arraycopy(body, 0, raw, prefix.length, body.length);
        return raw;
    }

    record Hit(String requestLine, String method, String target, Map<String, String> headers, byte[] body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }
}
