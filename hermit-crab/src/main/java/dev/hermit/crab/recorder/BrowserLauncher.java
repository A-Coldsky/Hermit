package dev.hermit.crab.recorder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.runtime.ApiJson;

/**
 * Launches an isolated Chrome/Edge recording instance with a temporary {@code --user-data-dir}
 * and ephemeral {@code --remote-debugging-port=0}, or resolves a loopback-only attach WebSocket URI.
 */
public final class BrowserLauncher {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    private static final long[] DELETE_RETRY_DELAYS_MS = {100L, 200L, 350L};

    private BrowserLauncher() {
    }

    public static final class LaunchedBrowser implements AutoCloseable {
        private final Process process;
        private final Path profileDir;
        private final URI wsUri;

        LaunchedBrowser(Process process, Path profileDir, URI wsUri) {
            this.process = process;
            this.profileDir = profileDir;
            this.wsUri = wsUri;
        }

        public URI wsUri() {
            return wsUri;
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void close() {
            close(false);
        }

        public void close(boolean gracefulCloseRequested) {
            try {
                if (process.isAlive() && gracefulCloseRequested) {
                    // Wait briefly for Chrome/Edge to exit cleanly after CDP Browser.close so Windows releases locks,
                    // while staying well inside Windows' ~5s CTRL_CLOSE_EVENT console shutdown window.
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                }
                if (process.isAlive()) {
                    process.toHandle().descendants().forEach(ProcessHandle::destroy);
                    process.destroy();
                    if (!process.waitFor(1000, TimeUnit.MILLISECONDS)) {
                        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                        process.waitFor(800, TimeUnit.MILLISECONDS);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            } finally {
                deleteDirectoryQuietly(profileDir);
            }
        }
    }

    public static LaunchedBrowser launch(
            Path executable,
            Path profileDir,
            String startUrl,
            boolean headless
    ) {
        String normalizedUrl = normalizeStartUrl(startUrl);
        try {
            Files.createDirectories(profileDir);
        } catch (IOException ex) {
            throw new RecorderException(500, ErrorCode.INTERNAL, "无法创建临时浏览器配置目录。", ex);
        }
        seedRelaxedProfilePreferences(profileDir);

        List<String> command = buildLaunchCommand(executable, profileDir, normalizedUrl, headless);

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException ex) {
            deleteDirectoryQuietly(profileDir);
            throw new RecorderException(500, ErrorCode.INTERNAL, "启动浏览器失败：" + ex.getMessage(), ex);
        }

        AtomicReference<String> stderrWs = new AtomicReference<>();
        StringBuilder tailLog = new StringBuilder();
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int marker = line.indexOf("DevTools listening on ");
                    if (marker >= 0) {
                        String candidate = line.substring(marker + "DevTools listening on ".length()).trim();
                        if (candidate.startsWith("ws://")) {
                            stderrWs.compareAndSet(null, candidate);
                        }
                    }
                    synchronized (tailLog) {
                        if (tailLog.length() < 1200) {
                            tailLog.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException ignored) {
                // Process closed stream.
            }
        }, "hermit-chrome-stderr");
        readerThread.setDaemon(true);
        readerThread.start();

        Path activePortFile = profileDir.resolve("DevToolsActivePort");
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        URI resolved = null;
        while (System.nanoTime() < deadline) {
            Optional<URI> fromFile = readDevToolsActivePort(activePortFile);
            if (fromFile.isPresent()) {
                resolved = fromFile.get();
                break;
            }
            String fromStderr = stderrWs.get();
            if (fromStderr != null) {
                try {
                    resolved = validateLoopbackWs(URI.create(fromStderr));
                    break;
                } catch (RuntimeException ignored) {
                    // Keep waiting for valid endpoint.
                }
            }
            if (!process.isAlive()) {
                break;
            }
            try {
                Thread.sleep(80);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (resolved == null) {
            LaunchedBrowser cleanup = new LaunchedBrowser(process, profileDir, URI.create("ws://127.0.0.1"));
            cleanup.close(false);
            throw new RecorderException(500, ErrorCode.INTERNAL,
                    "浏览器启动超时，未监听到 Chrome DevTools 调试端口。请确认本机浏览器可正常启动；若企业策略或安全软件禁用了远程调试，可改用「导入本地 .har 文件」。");
        }
        return new LaunchedBrowser(process, profileDir, resolved);
    }

    /**
     * Builds minimal, relaxed Chrome/Edge launch flags so the isolated recording window behaves
     * as close to a normal browser as possible without blocking popups, redirects, or normal web flows.
     */
    static List<String> buildLaunchCommand(
            Path executable,
            Path profileDir,
            String normalizedUrl,
            boolean headless
    ) {
        ArrayList<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + profileDir.toAbsolutePath().normalize());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-popup-blocking");
        command.add("--disable-sync");
        if (headless) {
            command.add("--headless=new");
            command.add("--disable-gpu");
            command.add("--no-sandbox");
        }
        command.add(normalizedUrl);
        return List.copyOf(command);
    }

    /**
     * Pre-seeds standard Chromium/Edge {@code Default/Preferences} inside the temporary profile
     * so fresh-profile restrictions (popup/redirect blocks, third-party cookie / Edge tracking prevention,
     * mixed HTTP content inside HTTPS intranet portals, and automatic download blocks) do not break
     * enterprise OA/SSO navigation during HAR recording, without triggering unsupported-flag infobars.
     */
    static void seedRelaxedProfilePreferences(Path profileDir) {
        if (profileDir == null) {
            return;
        }
        Path defaultDir = profileDir.resolve("Default");
        Path prefsFile = defaultDir.resolve("Preferences");
        if (Files.exists(prefsFile)) {
            return;
        }
        Map<String, Object> defaultContentSettings = new LinkedHashMap<>();
        defaultContentSettings.put("popups", 1);
        defaultContentSettings.put("automatic_downloads", 1);
        defaultContentSettings.put("mixed_script", 1);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("cookie_controls_mode", 0);
        profile.put("block_third_party_cookies", false);
        profile.put("default_content_setting_values", defaultContentSettings);

        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("browser", Map.of("has_seen_welcome_page", true));
        prefs.put("distribution", Map.of("suppress_first_run_bubble", true));
        prefs.put("https_only_mode_enabled", false);
        prefs.put("profile", profile);
        prefs.put("tracking_prevention", Map.of("tracking_prevention_level", 0));

        try {
            Files.createDirectories(defaultDir);
            Files.writeString(prefsFile, ApiJson.write(prefs), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // Best-effort: browser still launches normally if seeding preferences fails.
        }
    }

    /**
     * Resolves an attach WebSocket URI for an already-running local Chrome/Edge instance.
     * Supports:
     * <ul>
     *   <li>Blank input → locates Chrome 144+ {@code DevToolsActivePort} in default user data dir</li>
     *   <li>{@code ws://127.0.0.1:<port>/...} → validated loopback WebSocket URI</li>
     *   <li>{@code http://127.0.0.1:<port>} or bare port {@code 9222} → fetches {@code /json/version}</li>
     * </ul>
     */
    public static URI resolveAttachUri(String rawEndpoint) {
        if (rawEndpoint == null || rawEndpoint.isBlank()) {
            return resolveDefaultAttachUri(
                    BrowserDetector.findDefaultDevToolsActivePorts(),
                    BrowserLauncher::isLoopbackPortOpen);
        }
        String trimmed = rawEndpoint.trim();
        if (trimmed.matches("\\d{2,5}")) {
            trimmed = "http://127.0.0.1:" + trimmed;
        } else if (!trimmed.contains("://")) {
            trimmed = "http://" + trimmed;
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "调试地址格式无效。");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("ws".equals(scheme)) {
            return preferReachableLoopbackWs(validateLoopbackWs(uri), BrowserLauncher::isLoopbackPortOpen);
        }
        if (!"http".equals(scheme)) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "调试地址只支持本机 http:// 或 ws://。");
        }
        assertLoopbackHost(uri.getHost());
        int port = uri.getPort();
        if (port <= 0 || port > 65535) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "调试地址缺少有效端口号（例如 http://127.0.0.1:9222）。");
        }
        return fetchVersionWebSocketUri(uri.getHost(), port);
    }

    static boolean hasLiveDefaultDevToolsPort(List<Path> candidates) {
        return hasLiveDefaultDevToolsPort(candidates, uri -> isLoopbackPortOpen(uri, 150));
    }

    static boolean hasLiveDefaultDevToolsPort(List<Path> candidates, Predicate<URI> reachable) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (Path portFile : candidates) {
            Optional<URI> parsed = readDevToolsActivePort(portFile);
            if (parsed.isPresent() && (reachable == null || reachable.test(parsed.get()))) {
                return true;
            }
        }
        return false;
    }

    static URI preferReachableLoopbackWs(URI validated, Predicate<URI> reachable) {
        if (validated == null || !"localhost".equalsIgnoreCase(validated.getHost())) {
            return validated;
        }
        String path = validated.getRawPath() == null ? "" : validated.getRawPath();
        String query = validated.getRawQuery() == null ? "" : "?" + validated.getRawQuery();
        URI ipv4 = URI.create("ws://127.0.0.1:" + validated.getPort() + path + query);
        if (reachable != null && reachable.test(ipv4)) {
            return ipv4;
        }
        return validated;
    }

    static URI resolveDefaultAttachUri(List<Path> candidates, Predicate<URI> reachable) {
        if (candidates == null || candidates.isEmpty()) {
            throw new RecorderException(409, ErrorCode.NOT_CONFIGURED,
                    "未检测到已开启远程调试的本机浏览器。请在 Chrome（144+）打开 chrome://inspect/#remote-debugging 并启用远程调试，或填写本机调试地址（如 http://127.0.0.1:9222）。");
        }
        URI parsedFallback = null;
        for (Path portFile : candidates) {
            Optional<URI> parsed = readDevToolsActivePort(portFile);
            if (parsed.isEmpty()) {
                continue;
            }
            URI candidateUri = parsed.get();
            if (parsedFallback == null) {
                parsedFallback = candidateUri;
            }
            if (reachable == null || reachable.test(candidateUri)) {
                return candidateUri;
            }
        }
        if (parsedFallback != null) {
            throw new RecorderException(409, ErrorCode.NOT_CONFIGURED,
                    "检测到历史 DevToolsActivePort 文件，但对应调试端口未在监听（浏览器可能已关闭）。请先打开浏览器并启用远程调试，或手动填写调试地址（如 http://127.0.0.1:9222）。");
        }
        throw new RecorderException(
                409,
                ErrorCode.NOT_CONFIGURED,
                "无法读取本机浏览器 DevToolsActivePort 文件，请确认远程调试已开启或手动填写调试端口。");
    }

    private static URI fetchVersionWebSocketUri(String rawHost, int port) {
        List<String> hostsToTry = loopbackHostsToTry(rawHost);
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        IOException lastIo = null;
        for (String host : hostsToTry) {
            URI versionUri = URI.create("http://" + host + ":" + port + "/json/version");
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(versionUri).timeout(HTTP_TIMEOUT).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new RecorderException(502, ErrorCode.UPSTREAM_ERROR,
                            "无法连接本机浏览器调试端口（HTTP " + response.statusCode() + "）。请确认目标浏览器已开启远程调试。");
                }
                JsonNode json = ApiJson.tree(response.body());
                String wsUrl = json.path("webSocketDebuggerUrl").asText("");
                if (wsUrl.isBlank()) {
                    throw new RecorderException(502, ErrorCode.UPSTREAM_ERROR,
                            "调试端口未返回 webSocketDebuggerUrl。");
                }
                URI validated = validateLoopbackWs(URI.create(wsUrl));
                // If 127.0.0.1 answered while Chrome returned ws://localhost, rewrite to 127.0.0.1
                // to avoid Windows 10 IPv6 ::1 loopback fallback delays during WebSocket handshake.
                if ("127.0.0.1".equals(host) && "localhost".equalsIgnoreCase(validated.getHost())) {
                    String path = validated.getRawPath() == null ? "" : validated.getRawPath();
                    String query = validated.getRawQuery() == null ? "" : "?" + validated.getRawQuery();
                    return URI.create("ws://127.0.0.1:" + validated.getPort() + path + query);
                }
                return validated;
            } catch (RecorderException ex) {
                throw ex;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RecorderException(502, ErrorCode.UPSTREAM_ERROR, "连接本机浏览器调试端口被中断。");
            } catch (IOException ex) {
                lastIo = ex;
            }
        }
        String detail = lastIo == null || lastIo.getMessage() == null ? "连接失败" : lastIo.getMessage();
        throw new RecorderException(502, ErrorCode.UPSTREAM_ERROR,
                "连接本机浏览器调试端口失败：" + detail + "。提示：Chrome 136+ 需要指定非默认 --user-data-dir，或在 Chrome 144+ 中开启 chrome://inspect/#remote-debugging。");
    }

    static List<String> loopbackHostsToTry(String host) {
        String normalized = normalizedLoopbackHost(host);
        if ("localhost".equals(normalized)) {
            // On Windows 10, localhost resolves to ::1 first while Chrome typically listens on 127.0.0.1.
            return List.of("127.0.0.1", "[::1]", "localhost");
        }
        return List.of(normalized);
    }

    private static boolean isLoopbackPortOpen(URI uri) {
        return isLoopbackPortOpen(uri, 300);
    }

    private static boolean isLoopbackPortOpen(URI uri, int timeoutMs) {
        if (uri == null || uri.getPort() <= 0) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || "localhost".equalsIgnoreCase(host)) {
            host = "127.0.0.1";
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, uri.getPort()), timeoutMs);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    static Optional<URI> readDevToolsActivePort(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                return Optional.empty();
            }
            String first = lines.get(0);
            if (!first.isEmpty() && first.charAt(0) == '\uFEFF') {
                first = first.substring(1);
            }
            int port = Integer.parseInt(first.trim());
            String path = lines.get(1).trim();
            if (port <= 0 || port > 65535 || !path.startsWith("/")) {
                return Optional.empty();
            }
            return Optional.of(URI.create("ws://127.0.0.1:" + port + path));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    public static String normalizeStartUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "about:blank";
        }
        String trimmed = raw.trim();
        if ("about:blank".equalsIgnoreCase(trimmed)) {
            return "about:blank";
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "起始地址不是有效的 http(s) URL。");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "起始地址必须以 http:// 或 https:// 开头。");
        }
        return uri.toString();
    }

    static URI validateLoopbackWs(URI uri) {
        if (uri == null || !"ws".equalsIgnoreCase(uri.getScheme())) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "CDP WebSocket 地址无效。");
        }
        assertLoopbackHost(uri.getHost());
        if (uri.getPort() <= 0 || uri.getPort() > 65535) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "CDP WebSocket 缺少有效端口。");
        }
        return uri;
    }

    static void assertLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "调试地址仅允许本机回环地址（127.0.0.1 / localhost / ::1）。");
        }
        String lower = host.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            lower = lower.substring(1, lower.length() - 1);
        }
        if (!"127.0.0.1".equals(lower) && !"localhost".equals(lower) && !"::1".equals(lower)) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT,
                    "安全限制：浏览器调试地址仅允许本机回环（127.0.0.1 / localhost / ::1），拒绝连接远端主机。");
        }
    }

    private static String normalizedLoopbackHost(String host) {
        String lower = host.trim().toLowerCase(Locale.ROOT);
        if ("::1".equals(lower) || "[::1]".equals(lower)) {
            return "[::1]";
        }
        return lower;
    }

    public static void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        deleteDirectoryPass(dir);
        for (long delayMs : DELETE_RETRY_DELAYS_MS) {
            if (!Files.exists(dir)) {
                return;
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            deleteDirectoryPass(dir);
        }
    }

    private static void deleteDirectoryPass(Path dir) {
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs
                ) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException | RuntimeException ignored) {
                        // Best-effort cleanup on concurrent Chrome temp file unlinks or Windows locks.
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path subDir, IOException exc) {
                    try {
                        Files.deleteIfExists(subDir);
                    } catch (IOException | RuntimeException ignored) {
                        // Best-effort cleanup.
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // Best-effort cleanup.
        }
    }
}
