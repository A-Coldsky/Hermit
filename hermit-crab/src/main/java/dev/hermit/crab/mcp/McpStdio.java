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

/**
 * Forwards newline-delimited JSON-RPC to a running Crab {@code /mcp}. The API key is read only
 * from {@code HERMIT_API_KEY}. Protocol messages go to stdout; everything else goes to stderr.
 */
public final class McpStdio {
    private static final Duration CONNECT = Duration.ofSeconds(2);
    private static final Duration REQUEST = Duration.ofSeconds(60);

    private final Object stdoutLock = new Object();
    private final AtomicInteger exit = new AtomicInteger();
    private final AtomicReference<String> session = new AtomicReference<>();
    private volatile boolean sseStarted;

    public int run(String[] args, Function<String, String> env, InputStream in, PrintStream out, PrintStream err) {
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
            err.println("HERMIT_API_KEY is not set");
            return 1;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            err.println("invalid url");
            return 1;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            err.println("invalid url");
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
                err.println("crab is not running");
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
                err.println("crab is not running");
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
                err.println("authentication failed: " + rpcErrorMessage(response.body()));
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
                err.println("crab is not running");
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
