package dev.hermit.crab.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Forwards newline-delimited JSON-RPC to a running Crab {@code /mcp}. The API key is read only
 * from {@code HERMIT_API_KEY}. Protocol messages go to stdout; everything else goes to stderr.
 */
public final class McpStdio {
    private static final Duration CONNECT = Duration.ofSeconds(2);
    private static final Duration REQUEST = Duration.ofSeconds(60);
    static final String HELP = """
            mcp-stdio [--url http://127.0.0.1:7777/mcp] [--account NAME]

            把标准输入上的 MCP JSON-RPC 转发到正在运行的 Crab /mcp。
            协议消息写到标准输出，诊断写到标准错误。

              --url URL        Crab 的 MCP 地址，默认 http://127.0.0.1:7777/mcp
              --account NAME   可选。作为请求头 X-Hermit-Account 选择账号
              --help           打印本说明并退出

            环境变量 HERMIT_API_KEY：Crab 签发的 API Key，不要写在命令行上。
            """;
    static final String KEY_MISSING = "未设置环境变量 HERMIT_API_KEY。请在 Crab 控制台或用 CLI apikey create 签发后写入该变量。";
    static final String CRAB_NOT_RUNNING = "Crab 没有运行。请先启动 Crab，再连接 MCP。";
    static final String URL_INVALID = "URL 不合法。请使用 http 或 https 地址，例如 http://127.0.0.1:7777/mcp。";

    private final Object stdoutLock = new Object();
    private final AtomicInteger exit = new AtomicInteger();
    private final AtomicReference<String> session = new AtomicReference<>();
    private volatile boolean sseStarted;

    public int run(String[] args, Function<String, String> env, InputStream in, PrintStream out, PrintStream err) {
        if (helpRequested(args)) {
            out.print(HELP);
            out.flush();
            return 0;
        }
        String url = "http://127.0.0.1:7777/mcp";
        String accountName = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--url".equals(arg) || "--account".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    err.println("option value is required");
                    return 1;
                }
                String value = args[++i];
                if ("--url".equals(arg)) {
                    url = value;
                } else {
                    accountName = value;
                }
                continue;
            }
            err.println(arg.startsWith("--") ? "unknown option" : "unexpected argument");
            return 1;
        }
        String key = env.apply("HERMIT_API_KEY");
        if (key == null || key.isBlank()) {
            err.println(KEY_MISSING);
            return 1;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            err.println(URL_INVALID);
            return 1;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            err.println(URL_INVALID);
            return 1;
        }
        String account = accountName;
        ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "hermit-mcp-stdio");
            thread.setDaemon(true);
            return thread;
        });
        HttpClient client = HttpClient.newBuilder().executor(executor).connectTimeout(CONNECT).build();
        try {
            if (!probe(client, uri, err)) {
                return exit.get() == 0 ? 2 : exit.get();
            }
            List<CompletableFuture<Void>> inflight = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String message = line;
                    boolean wait = session.get() == null || initialize(message);
                    CompletableFuture<Void> future = CompletableFuture.runAsync(
                            () -> forward(client, uri, key, account, message, out, err), executor);
                    if (wait) {
                        future.join();
                        startSse(client, uri, key, account, out, err, executor);
                    } else {
                        inflight.add(future);
                    }
                    if (exit.get() != 0) {
                        break;
                    }
                }
            }
            for (CompletableFuture<Void> future : inflight) {
                future.join();
            }
            return exit.get();
        } catch (Exception ex) {
            if (connectFailure(ex)) {
                err.println(CRAB_NOT_RUNNING);
                return 2;
            }
            err.println(ex.getClass().getName());
            return exit.get() == 0 ? 1 : exit.get();
        } finally {
            executor.shutdown();
        }
    }

    private boolean probe(HttpClient client, URI uri, PrintStream err) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(CONNECT).GET().build();
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            err.println(ex.getClass().getName());
            exit.compareAndSet(0, 1);
            return false;
        } catch (IOException ex) {
            if (connectFailure(ex)) {
                err.println(CRAB_NOT_RUNNING);
                exit.compareAndSet(0, 2);
                return false;
            }
            err.println(ex.getClass().getName());
            exit.compareAndSet(0, 1);
            return false;
        }
    }

    private void forward(
            HttpClient client,
            URI uri,
            String key,
            String account,
            String line,
            PrintStream out,
            PrintStream err
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8));
        String current = session.get();
        if (current != null) {
            builder.header("Mcp-Session-Id", current);
        }
        if (account != null) {
            builder.header("X-Hermit-Account", account);
        }
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(session::set);
            if (response.statusCode() == 401) {
                byte[] body = response.body();
                emit(out, authenticationStdout(body, line));
                err.println("authentication failed: " + rpcErrorMessage(body));
                exit.compareAndSet(0, 1);
                return;
            }
            if (response.statusCode() == 202) {
                return;
            }
            byte[] body = response.body();
            if (body != null && body.length > 0) {
                emit(out, new String(body, StandardCharsets.UTF_8).trim());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            exit.compareAndSet(0, 1);
        } catch (IOException ex) {
            if (connectFailure(ex)) {
                err.println(CRAB_NOT_RUNNING);
                exit.compareAndSet(0, 2);
                return;
            }
            err.println(ex.getClass().getName());
            exit.compareAndSet(0, 1);
        }
    }

    private void startSse(
            HttpClient client,
            URI uri,
            String key,
            String account,
            PrintStream out,
            PrintStream err,
            ExecutorService executor
    ) {
        String current = session.get();
        if (current == null || sseStarted) {
            return;
        }
        sseStarted = true;
        executor.execute(() -> readSse(client, uri, key, account, current, out, err));
    }

    private void readSse(
            HttpClient client,
            URI uri,
            String key,
            String account,
            String sessionId,
            PrintStream out,
            PrintStream err
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofHours(24))
                .header("Authorization", "Bearer " + key)
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .GET();
        if (account != null) {
            builder.header("X-Hermit-Account", account);
        }
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 401) {
                byte[] body = response.body().readAllBytes();
                emit(out, authenticationStdout(body, null));
                err.println("authentication failed");
                exit.compareAndSet(0, 1);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (!data.isEmpty()) {
                            emit(out, data);
                        }
                    }
                }
            }
        } catch (IOException ex) {
            if (!connectFailure(ex)) {
                err.println(ex.getClass().getName());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void emit(PrintStream out, String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        synchronized (stdoutLock) {
            out.println(json);
            out.flush();
        }
    }

    private static boolean initialize(String line) {
        try {
            JsonNode node = McpJson.tree(line.getBytes(StandardCharsets.UTF_8));
            return "initialize".equals(node.path("method").asText());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean connectFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 401 时先把 Crab 的 JSON-RPC 错误写到 stdout。没有可解析的错误对象时，用请求 id 写一条
     * {@code -32001}，避免客户端只看到进程退出。
     */
    static String authenticationStdout(byte[] body, String requestLine) {
        String trimmed = body == null ? "" : new String(body, StandardCharsets.UTF_8).trim();
        if (jsonRpcError(trimmed)) {
            return fillNullId(trimmed, requestLine);
        }
        JsonNode id = requestId(requestLine);
        String idJson = id == null || id.isNull() ? "null" : McpJson.write(id);
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                + ",\"error\":{\"code\":" + McpErrors.AUTH_RPC + ",\"message\":\"authentication failed\"}}";
    }

    private static boolean helpRequested(String[] args) {
        if (args == null) {
            return false;
        }
        for (int i = 1; i < args.length; i++) {
            if ("--help".equals(args[i])) {
                return true;
            }
        }
        return false;
    }

    /** 服务端鉴权发生在读 body 之前，401 的 id 是 null。能解析出本次请求 id 时补上。 */
    private static String fillNullId(String body, String requestLine) {
        JsonNode requestId = requestId(requestLine);
        if (requestId == null) {
            return body;
        }
        try {
            JsonNode node = McpJson.tree(body.getBytes(StandardCharsets.UTF_8));
            if (!node.isObject()) {
                return body;
            }
            JsonNode id = node.get("id");
            if (id != null && !id.isNull()) {
                return body;
            }
            ((ObjectNode) node).set("id", requestId);
            return McpJson.write(node);
        } catch (RuntimeException ex) {
            return body;
        }
    }

    private static boolean jsonRpcError(String body) {
        if (body.isEmpty() || body.charAt(0) != '{') {
            return false;
        }
        try {
            JsonNode node = McpJson.tree(body.getBytes(StandardCharsets.UTF_8));
            return node.path("error").isObject() && node.path("error").has("code");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static JsonNode requestId(String requestLine) {
        if (requestLine == null || requestLine.isBlank()) {
            return null;
        }
        try {
            JsonNode id = McpJson.tree(requestLine.getBytes(StandardCharsets.UTF_8)).get("id");
            if (id == null || id.isNull()) {
                return null;
            }
            return id;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 401 的 body 是 JSON-RPC 错误；读不出就只报状态，避免把未知内容写到终端。 */
    private static String rpcErrorMessage(byte[] body) {
        if (body == null || body.length == 0) {
            return "HTTP 401";
        }
        try {
            String message = McpJson.tree(body).path("error").path("message").asText("");
            return message.isBlank() ? "HTTP 401" : message;
        } catch (RuntimeException ex) {
            return "HTTP 401";
        }
    }
}
