package dev.hermit.crab.recorder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.runtime.ApiJson;

/**
 * Lightweight Chrome DevTools Protocol (CDP) network collector built solely on JDK 17
 * {@link java.net.http.WebSocket}. Tracks multi-tab page sessions via {@code Target.setAutoAttach},
 * captures redirects, {@code ExtraInfo} Cookie/Set-Cookie headers, and text response bodies,
 * while immediately scrubbing wire auth/cookie values.
 */
public final class CdpNetworkCollector implements AutoCloseable {
    public static final int DEFAULT_MAX_ENTRIES = 500;
    public static final int MAX_RAW_BODY_CHARS = 512 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Logger LOG = Logger.getLogger(CdpNetworkCollector.class.getName());

    private final Clock clock;
    private final int maxEntries;
    private final AtomicInteger nextCommandId = new AtomicInteger(1);
    private final AtomicInteger nextEntrySeq = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean navigatedInitial = new AtomicBoolean(false);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingCommands = new ConcurrentHashMap<>();
    private final Set<String> enabledSessions = ConcurrentHashMap.newKeySet();
    private final Set<String> attachedTargetIds = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<CompletableFuture<?>> pendingBodies = new CopyOnWriteArrayList<>();

    private final Object stateLock = new Object();
    private final Map<String, MutableHop> activeHops = new LinkedHashMap<>();
    private final Map<String, RecordedEntry> lastRedirectHop = new LinkedHashMap<>();
    private final Map<String, List<RecordedEntry.HeaderPair>> earlyReqExtraHeaders = new LinkedHashMap<>();
    private final Map<String, List<RecordedEntry.HeaderPair>> earlyRespExtraHeaders = new LinkedHashMap<>();
    private final List<RecordedEntry> entries = new ArrayList<>();
    private final Map<String, Integer> entryIndexById = new LinkedHashMap<>();

    private final Object sendLock = new Object();
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    private volatile WebSocket webSocket;
    private volatile String pendingNavigateUrl;
    private volatile String disconnectReason;

    private CdpNetworkCollector(Clock clock, int maxEntries) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.maxEntries = Math.max(10, maxEntries);
    }

    public static CdpNetworkCollector connect(URI wsUri, Clock clock, int maxEntries, String initialNavigateUrl) {
        CdpNetworkCollector collector = new CdpNetworkCollector(clock, maxEntries);
        collector.pendingNavigateUrl = (initialNavigateUrl != null && !initialNavigateUrl.isBlank()
                && !"about:blank".equalsIgnoreCase(initialNavigateUrl))
                ? initialNavigateUrl
                : null;
        collector.open(wsUri);
        return collector;
    }

    private void open(URI wsUri) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        try {
            this.webSocket = client.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(wsUri, new Listener())
                    .get(CONNECT_TIMEOUT.toMillis() + 1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RecorderException(502, ErrorCode.UPSTREAM_ERROR, "连接 Chrome DevTools 被中断。");
        } catch (ExecutionException | TimeoutException ex) {
            throw new RecorderException(502, ErrorCode.UPSTREAM_ERROR,
                    "无法连接 Chrome DevTools WebSocket：" + rootMessage(ex));
        }

        String path = wsUri.getPath() == null ? "" : wsUri.getPath();
        boolean pageLevelEndpoint = path.startsWith("/devtools/page/");
        if (pageLevelEndpoint) {
            enableNetworkOnSession(null, true);
        } else {
            bootstrapBrowserEndpoint();
        }
    }

    private void bootstrapBrowserEndpoint() {
        CompletableFuture<JsonNode> autoAttach = sendCommand(null, "Target.setAutoAttach", Map.of(
                "autoAttach", true,
                "waitForDebuggerOnStart", false,
                "flatten", true));
        try {
            JsonNode result = autoAttach.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (result != null && result.has("error")) {
                // Target domain unsupported (e.g. direct page endpoint or lightweight CDP server): fall back.
                enableNetworkOnSession(null, true);
                return;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            close();
            throw new RecorderException(502, ErrorCode.UPSTREAM_ERROR, "初始化 Chrome 调试会话被中断。");
        } catch (ExecutionException | TimeoutException ex) {
            // Fall back to direct Network.enable in case the endpoint is a simple page target.
            enableNetworkOnSession(null, true);
            return;
        }

        // Attach to any already-open page targets not already auto-attached by Target.setAutoAttach.
        sendCommand(null, "Target.getTargets", Map.of()).thenAccept(response -> {
            if (response == null || response.has("error")) {
                return;
            }
            JsonNode infos = response.path("result").path("targetInfos");
            if (!infos.isArray()) {
                return;
            }
            for (JsonNode info : infos) {
                if ("page".equals(info.path("type").asText(""))) {
                    String targetId = info.path("targetId").asText("");
                    if (!targetId.isBlank() && attachedTargetIds.add(targetId)) {
                        sendCommand(null, "Target.attachToTarget", Map.of(
                                "targetId", targetId,
                                "flatten", true)).thenAccept(attached -> {
                            String sid = attached == null ? "" : attached.path("result").path("sessionId").asText("");
                            if (!sid.isBlank()) {
                                enableNetworkOnSession(sid, true);
                            }
                        });
                    }
                }
            }
        });
    }

    private void enableNetworkOnSession(String sessionId, boolean allowInitialNavigate) {
        String key = sessionId == null ? "__root__" : sessionId;
        if (!enabledSessions.add(key)) {
            return;
        }
        CompletableFuture<JsonNode> enabled = sendCommand(sessionId, "Network.enable", Map.of(
                "maxTotalBufferSize", 16 * 1024 * 1024,
                "maxResourceBufferSize", 2 * 1024 * 1024));
        if (allowInitialNavigate && pendingNavigateUrl != null) {
            enabled.thenRun(() -> {
                String url = pendingNavigateUrl;
                if (url != null && navigatedInitial.compareAndSet(false, true)) {
                    sendCommand(sessionId, "Page.navigate", Map.of("url", url));
                }
            });
        }
    }

    public boolean isConnected() {
        return !closed.get() && webSocket != null && !webSocket.isOutputClosed() && !webSocket.isInputClosed();
    }

    public String disconnectReason() {
        return disconnectReason;
    }

    public int entryCount() {
        synchronized (stateLock) {
            return entries.size();
        }
    }

    public List<RecordedEntry> snapshotEntries() {
        synchronized (stateLock) {
            return List.copyOf(entries);
        }
    }

    /**
     * Waits briefly for any in-flight {@code Network.getResponseBody} calls before closing the WebSocket.
     */
    public List<RecordedEntry> finishAndCollect() {
        return finishAndCollect(false);
    }

    /**
     * Waits briefly for in-flight response bodies, optionally sends CDP {@code Browser.close}
     * (for Crab-launched isolated browsers so Windows releases profile locks cleanly), then closes.
     */
    public List<RecordedEntry> finishAndCollect(boolean closeBrowser) {
        if (!pendingBodies.isEmpty()) {
            try {
                CompletableFuture.allOf(pendingBodies.toArray(new CompletableFuture[0]))
                        .get(600, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
                // Best-effort flush; entries without bodies are still kept.
            }
        }
        close(closeBrowser);
        return snapshotEntries();
    }

    public void close(boolean closeBrowser) {
        if (closeBrowser && !closed.get() && webSocket != null) {
            try {
                sendCommand(null, "Browser.close", Map.of()).get(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
                // Process teardown in BrowserLauncher will handle cleanup if CDP close does not complete.
            }
        }
        close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                try {
                    ws.abort();
                } catch (Exception ignoredAbort) {
                    // Ignore abort errors.
                }
            }
        }
        for (CompletableFuture<JsonNode> future : pendingCommands.values()) {
            future.cancel(false);
        }
        pendingCommands.clear();
    }

    private CompletableFuture<JsonNode> sendCommand(String sessionId, String method, Map<String, Object> params) {
        if (closed.get() || webSocket == null) {
            return CompletableFuture.completedFuture(null);
        }
        int id = nextCommandId.getAndIncrement();
        LinkedHashMap<String, Object> frame = new LinkedHashMap<>();
        frame.put("id", id);
        frame.put("method", method);
        if (sessionId != null && !sessionId.isBlank()) {
            frame.put("sessionId", sessionId);
        }
        if (params != null && !params.isEmpty()) {
            frame.put("params", params);
        }
        String payload = ApiJson.write(frame);
        CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        pendingCommands.put(id, reply);
        synchronized (sendLock) {
            WebSocket ws = this.webSocket;
            if (ws == null || closed.get()) {
                pendingCommands.remove(id);
                reply.complete(null);
                return reply;
            }
            sendChain = sendChain.handle((ignored, err) -> null)
                    .thenCompose(ignored -> {
                        if (closed.get()) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return ws.sendText(payload, true).whenComplete((sent, sendErr) -> {
                            if (sendErr != null) {
                                CompletableFuture<JsonNode> pending = pendingCommands.remove(id);
                                if (pending != null) {
                                    pending.completeExceptionally(sendErr);
                                }
                            }
                        });
                    });
        }
        return reply;
    }

    private void handleMessage(String raw) {
        JsonNode msg;
        try {
            msg = ApiJson.tree(raw);
        } catch (RuntimeException ex) {
            return;
        }
        if (msg.has("id") && msg.get("id").isInt()) {
            int id = msg.get("id").asInt();
            CompletableFuture<JsonNode> future = pendingCommands.remove(id);
            if (future != null) {
                future.complete(msg);
            }
            return;
        }
        String method = msg.path("method").asText("");
        if (method.isEmpty()) {
            return;
        }
        String sessionId = msg.has("sessionId") && msg.get("sessionId").isTextual()
                ? msg.get("sessionId").asText()
                : null;
        JsonNode params = msg.path("params");
        switch (method) {
            case "Target.attachedToTarget" -> onAttachedToTarget(params);
            case "Network.requestWillBeSent" -> onRequestWillBeSent(sessionId, params);
            case "Network.requestWillBeSentExtraInfo" -> onRequestExtraInfo(sessionId, params);
            case "Network.responseReceived" -> onResponseReceived(sessionId, params);
            case "Network.responseReceivedExtraInfo" -> onResponseExtraInfo(sessionId, params);
            case "Network.loadingFinished" -> onLoadingFinished(sessionId, params);
            case "Network.loadingFailed" -> onLoadingFailed(sessionId, params);
            default -> {
                // Ignore unrelated CDP events.
            }
        }
    }

    private void onAttachedToTarget(JsonNode params) {
        String childSession = params.path("sessionId").asText("");
        JsonNode info = params.path("targetInfo");
        String targetId = info.path("targetId").asText("");
        String type = info.path("type").asText("");
        if (!childSession.isBlank() && ("page".equals(type) || "iframe".equals(type))) {
            if (targetId.isBlank() || attachedTargetIds.add(targetId)) {
                enableNetworkOnSession(childSession, "page".equals(type));
            }
        }
    }

    private void onRequestWillBeSent(String sessionId, JsonNode params) {
        String requestId = params.path("requestId").asText("");
        JsonNode req = params.path("request");
        String url = req.path("url").asText("");
        if (requestId.isBlank() || url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return;
        }
        String key = hopKey(sessionId, requestId);
        double ts = params.path("timestamp").asDouble(0.0);
        Instant now = wallTimeOrNow(params.path("wallTime").asDouble(0.0));

        synchronized (stateLock) {
            // If this event represents a 3xx redirect, finalize the previous hop with redirectResponse first.
            JsonNode redirectResp = params.path("redirectResponse");
            MutableHop previous = activeHops.remove(key);
            if (previous != null && redirectResp.isObject() && !redirectResp.isEmpty()) {
                applyResponseNode(previous, redirectResp);
                previous.redirectTo = url;
                previous.durationMs = computeDuration(previous.startMonoSec, ts);
                RecordedEntry finalized = finalizeHopLocked(previous);
                if (finalized != null) {
                    lastRedirectHop.put(key, finalized);
                }
            }

            MutableHop hop = new MutableHop();
            hop.sessionId = sessionId;
            hop.requestId = requestId;
            hop.startedAt = now;
            hop.startMonoSec = ts;
            hop.method = req.path("method").asText("GET").toUpperCase(Locale.ROOT);
            hop.url = url;
            hop.resourceType = params.path("type").asText("Other");
            hop.requestHeaders.addAll(parseHeaderObject(req.path("headers")));
            if (req.has("postData") && req.get("postData").isTextual()) {
                String postData = req.get("postData").asText();
                if (postData.length() > MAX_RAW_BODY_CHARS) {
                    hop.requestBody = postData.substring(0, MAX_RAW_BODY_CHARS);
                    hop.requestBodyTruncated = true;
                } else {
                    hop.requestBody = postData;
                }
            }
            List<RecordedEntry.HeaderPair> earlyReq = earlyReqExtraHeaders.remove(key);
            if (earlyReq != null) {
                mergeHeaders(hop.requestHeaders, earlyReq);
            }
            List<RecordedEntry.HeaderPair> earlyResp = earlyRespExtraHeaders.remove(key);
            if (earlyResp != null) {
                mergeHeaders(hop.responseHeaders, earlyResp);
            }
            activeHops.put(key, hop);
        }
    }

    private void onRequestExtraInfo(String sessionId, JsonNode params) {
        String requestId = params.path("requestId").asText("");
        if (requestId.isBlank()) {
            return;
        }
        List<RecordedEntry.HeaderPair> extra = parseHeaderObject(params.path("headers"));
        if (extra.isEmpty()) {
            return;
        }
        String key = hopKey(sessionId, requestId);
        synchronized (stateLock) {
            MutableHop hop = activeHops.get(key);
            if (hop != null) {
                // Avoid merging delayed POST-only headers onto a subsequent GET redirect hop.
                if ("GET".equalsIgnoreCase(hop.method) && firstHeader(extra, "content-length") != null) {
                    RecordedEntry prev = lastRedirectHop.get(key);
                    if (prev != null && !"GET".equalsIgnoreCase(prev.method())) {
                        return;
                    }
                }
                mergeHeaders(hop.requestHeaders, extra);
            } else {
                earlyReqExtraHeaders.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(extra);
            }
        }
    }

    private void onResponseReceived(String sessionId, JsonNode params) {
        String requestId = params.path("requestId").asText("");
        if (requestId.isBlank()) {
            return;
        }
        String key = hopKey(sessionId, requestId);
        synchronized (stateLock) {
            MutableHop hop = activeHops.get(key);
            if (hop == null) {
                return;
            }
            String type = params.path("type").asText("");
            if (!type.isBlank()) {
                hop.resourceType = type;
            }
            applyResponseNode(hop, params.path("response"));
        }
    }

    private void onResponseExtraInfo(String sessionId, JsonNode params) {
        String requestId = params.path("requestId").asText("");
        if (requestId.isBlank()) {
            return;
        }
        List<RecordedEntry.HeaderPair> extra = parseHeaderObject(params.path("headers"));
        if (extra.isEmpty()) {
            return;
        }
        int statusCode = params.path("statusCode").asInt(0);
        String key = hopKey(sessionId, requestId);
        synchronized (stateLock) {
            MutableHop hop = activeHops.get(key);
            // If a 3xx redirect already finalized previous hop and this ExtraInfo carries its Set-Cookie/Location headers:
            RecordedEntry prevRedirect = lastRedirectHop.get(key);
            if (prevRedirect != null && statusCode >= 300 && statusCode < 400
                    && prevRedirect.status() == statusCode) {
                mergeExtraHeadersIntoFinalizedLocked(prevRedirect.id(), extra);
                return;
            }
            if (hop != null) {
                mergeHeaders(hop.responseHeaders, extra);
                if (hop.status == 0 && statusCode > 0) {
                    hop.status = statusCode;
                }
            } else {
                earlyRespExtraHeaders.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(extra);
            }
        }
    }

    private void onLoadingFinished(String sessionId, JsonNode params) {
        String requestId = params.path("requestId").asText("");
        if (requestId.isBlank()) {
            return;
        }
        String key = hopKey(sessionId, requestId);
        double ts = params.path("timestamp").asDouble(0.0);
        long encodedBytes = Math.max(0L, Math.round(params.path("encodedDataLength").asDouble(0.0)));

        RecordedEntry finalized;
        synchronized (stateLock) {
            lastRedirectHop.remove(key);
            earlyReqExtraHeaders.remove(key);
            earlyRespExtraHeaders.remove(key);
            MutableHop hop = activeHops.remove(key);
            if (hop == null) {
                return;
            }
            hop.durationMs = computeDuration(hop.startMonoSec, ts);
            if (encodedBytes > 0) {
                hop.responseBytes = encodedBytes;
            }
            finalized = finalizeHopLocked(hop);
        }

        if (finalized != null && shouldFetchBody(finalized)) {
            CompletableFuture<Void> fetch = sendCommand(sessionId, "Network.getResponseBody", Map.of("requestId", requestId))
                    .thenAccept(reply -> {
                        if (reply == null || reply.has("error")) {
                            return;
                        }
                        JsonNode res = reply.path("result");
                        if (!res.has("body") || !res.get("body").isTextual()) {
                            return;
                        }
                        String rawBody = res.get("body").asText();
                        boolean base64 = res.path("base64Encoded").asBoolean(false);
                        String decoded = rawBody;
                        if (base64) {
                            try {
                                decoded = new String(Base64.getDecoder().decode(rawBody), StandardCharsets.UTF_8);
                            } catch (IllegalArgumentException ex) {
                                return;
                            }
                        }
                        boolean truncated = false;
                        if (decoded.length() > MAX_RAW_BODY_CHARS) {
                            decoded = decoded.substring(0, MAX_RAW_BODY_CHARS);
                            truncated = true;
                        }
                        attachResponseBody(finalized.id(), decoded, truncated);
                    });
            pendingBodies.add(fetch);
            fetch.whenComplete((ignored, err) -> pendingBodies.remove(fetch));
        }
    }

    private void onLoadingFailed(String sessionId, JsonNode params) {
        String requestId = params.path("requestId").asText("");
        if (requestId.isBlank()) {
            return;
        }
        String key = hopKey(sessionId, requestId);
        double ts = params.path("timestamp").asDouble(0.0);
        String errorText = params.path("errorText").asText("loading failed");
        synchronized (stateLock) {
            lastRedirectHop.remove(key);
            earlyReqExtraHeaders.remove(key);
            earlyRespExtraHeaders.remove(key);
            MutableHop hop = activeHops.remove(key);
            if (hop == null) {
                return;
            }
            hop.durationMs = computeDuration(hop.startMonoSec, ts);
            hop.errorText = errorText;
            finalizeHopLocked(hop);
        }
    }

    private RecordedEntry finalizeHopLocked(MutableHop hop) {
        if (entries.size() >= maxEntries) {
            return null;
        }
        int seq = nextEntrySeq.incrementAndGet();
        String id = "e" + seq;
        String contentDisposition = firstHeader(hop.responseHeaders, "content-disposition");
        String mimeType = hop.mimeType;
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = firstHeader(hop.responseHeaders, "content-type");
        }
        String redirectTo = hop.redirectTo;
        if ((redirectTo == null || redirectTo.isBlank()) && hop.status >= 300 && hop.status < 400) {
            redirectTo = firstHeader(hop.responseHeaders, "location");
        }
        boolean staticAsset = CaptureRedactor.isStaticAsset(hop.method, hop.status, hop.resourceType, hop.url, mimeType);
        boolean binary = CaptureRedactor.isBinaryContent(mimeType, contentDisposition);

        RecordedEntry entry = new RecordedEntry(
                id,
                seq,
                hop.startedAt,
                hop.durationMs,
                hop.method,
                hop.url,
                CaptureRedactor.extractOrigin(hop.url),
                CaptureRedactor.extractPath(hop.url),
                hop.resourceType,
                staticAsset,
                hop.status,
                hop.statusText,
                mimeType == null ? "" : mimeType,
                (redirectTo == null || redirectTo.isBlank()) ? null : redirectTo,
                binary,
                contentDisposition,
                hop.responseBytes,
                hop.errorText,
                CaptureRedactor.scrubWireHeaders(hop.requestHeaders),
                hop.requestBody,
                hop.requestBodyTruncated,
                CaptureRedactor.scrubWireHeaders(hop.responseHeaders),
                null,
                false);
        entryIndexById.put(id, entries.size());
        entries.add(entry);
        return entry;
    }

    private void mergeExtraHeadersIntoFinalizedLocked(String entryId, List<RecordedEntry.HeaderPair> extra) {
        Integer idx = entryIndexById.get(entryId);
        if (idx == null || idx < 0 || idx >= entries.size()) {
            return;
        }
        RecordedEntry existing = entries.get(idx);
        ArrayList<RecordedEntry.HeaderPair> merged = new ArrayList<>(existing.responseHeaders());
        mergeHeaders(merged, CaptureRedactor.scrubWireHeaders(extra));
        RecordedEntry updated = new RecordedEntry(
                existing.id(),
                existing.index(),
                existing.startedAt(),
                existing.durationMs(),
                existing.method(),
                existing.url(),
                existing.origin(),
                existing.path(),
                existing.resourceType(),
                existing.staticAsset(),
                existing.status(),
                existing.statusText(),
                existing.mimeType(),
                existing.redirectTo(),
                existing.binary(),
                existing.contentDisposition(),
                existing.responseBytes(),
                existing.errorText(),
                existing.requestHeaders(),
                existing.requestBody(),
                existing.requestBodyTruncated(),
                merged,
                existing.responseBody(),
                existing.responseBodyTruncated());
        entries.set(idx, updated);
    }

    private void attachResponseBody(String entryId, String body, boolean truncated) {
        synchronized (stateLock) {
            Integer idx = entryIndexById.get(entryId);
            if (idx == null || idx < 0 || idx >= entries.size()) {
                return;
            }
            RecordedEntry existing = entries.get(idx);
            long size = existing.responseBytes() > 0
                    ? existing.responseBytes()
                    : body.getBytes(StandardCharsets.UTF_8).length;
            RecordedEntry updated = new RecordedEntry(
                    existing.id(),
                    existing.index(),
                    existing.startedAt(),
                    existing.durationMs(),
                    existing.method(),
                    existing.url(),
                    existing.origin(),
                    existing.path(),
                    existing.resourceType(),
                    existing.staticAsset(),
                    existing.status(),
                    existing.statusText(),
                    existing.mimeType(),
                    existing.redirectTo(),
                    existing.binary(),
                    existing.contentDisposition(),
                    size,
                    existing.errorText(),
                    existing.requestHeaders(),
                    existing.requestBody(),
                    existing.requestBodyTruncated(),
                    existing.responseHeaders(),
                    body,
                    truncated);
            entries.set(idx, updated);
        }
    }

    private static boolean shouldFetchBody(RecordedEntry entry) {
        if (entry.staticAsset() || entry.binary()) {
            return false;
        }
        if (entry.status() == 204 || entry.status() == 304 || (entry.status() >= 300 && entry.status() < 400)) {
            return false;
        }
        return entry.responseBytes() <= MAX_RAW_BODY_CHARS * 2L;
    }

    private static void applyResponseNode(MutableHop hop, JsonNode resp) {
        if (resp == null || !resp.isObject()) {
            return;
        }
        int status = resp.path("status").asInt(0);
        if (status > 0) {
            hop.status = status;
        }
        String statusText = resp.path("statusText").asText("");
        if (!statusText.isEmpty()) {
            hop.statusText = statusText;
        }
        String mimeType = resp.path("mimeType").asText("");
        if (!mimeType.isEmpty()) {
            hop.mimeType = mimeType;
        }
        mergeHeaders(hop.responseHeaders, parseHeaderObject(resp.path("headers")));
        if (resp.has("requestHeaders") && resp.get("requestHeaders").isObject()) {
            mergeHeaders(hop.requestHeaders, parseHeaderObject(resp.get("requestHeaders")));
        }
    }

    private static List<RecordedEntry.HeaderPair> parseHeaderObject(JsonNode headersNode) {
        if (headersNode == null || !headersNode.isObject()) {
            return List.of();
        }
        ArrayList<RecordedEntry.HeaderPair> list = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = headersNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> field = it.next();
            String name = field.getKey();
            if (name == null || name.isEmpty() || name.startsWith(":")) {
                continue;
            }
            String value = field.getValue().asText("");
            if ("set-cookie".equalsIgnoreCase(name) && value.contains("\n")) {
                for (String line : value.split("\\r?\\n")) {
                    if (!line.isBlank()) {
                        list.add(new RecordedEntry.HeaderPair(name, line.trim()));
                    }
                }
            } else {
                list.add(new RecordedEntry.HeaderPair(name, value));
            }
        }
        return list;
    }

    private static void mergeHeaders(
            List<RecordedEntry.HeaderPair> target,
            List<RecordedEntry.HeaderPair> incoming
    ) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        for (RecordedEntry.HeaderPair next : incoming) {
            if ("set-cookie".equalsIgnoreCase(next.name())) {
                boolean exists = false;
                for (RecordedEntry.HeaderPair existing : target) {
                    if ("set-cookie".equalsIgnoreCase(existing.name()) && existing.value().equals(next.value())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    target.add(next);
                }
                continue;
            }
            int replaceIdx = -1;
            for (int i = 0; i < target.size(); i++) {
                if (target.get(i).name().equalsIgnoreCase(next.name())) {
                    replaceIdx = i;
                    break;
                }
            }
            if (replaceIdx >= 0) {
                target.set(replaceIdx, next);
            } else {
                target.add(next);
            }
        }
    }

    private static String firstHeader(List<RecordedEntry.HeaderPair> headers, String target) {
        for (RecordedEntry.HeaderPair pair : headers) {
            if (target.equalsIgnoreCase(pair.name())) {
                return pair.value();
            }
        }
        return null;
    }

    private Instant wallTimeOrNow(double wallTimeSec) {
        if (wallTimeSec > 1_000_000_000.0) {
            return Instant.ofEpochMilli(Math.round(wallTimeSec * 1000.0));
        }
        return clock.instant();
    }

    private static long computeDuration(double startMonoSec, double endMonoSec) {
        if (startMonoSec <= 0.0 || endMonoSec <= startMonoSec) {
            return 0L;
        }
        return Math.max(0L, Math.round((endMonoSec - startMonoSec) * 1000.0));
    }

    private static String hopKey(String sessionId, String requestId) {
        return (sessionId == null ? "" : sessionId) + ":" + requestId;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private static final class MutableHop {
        String sessionId;
        String requestId;
        Instant startedAt;
        double startMonoSec;
        long durationMs;
        String method;
        String url;
        String resourceType;
        int status;
        String statusText;
        String mimeType;
        String redirectTo;
        long responseBytes;
        String errorText;
        final List<RecordedEntry.HeaderPair> requestHeaders = new ArrayList<>();
        String requestBody;
        boolean requestBodyTruncated;
        final List<RecordedEntry.HeaderPair> responseHeaders = new ArrayList<>();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    handleMessage(message);
                } catch (RuntimeException ex) {
                    LOG.warning("CDP message handling failed: " + ex.getClass().getSimpleName());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            disconnectReason = (reason == null || reason.isBlank()) ? "浏览器调试连接已断开" : reason;
            closed.set(true);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            disconnectReason = "浏览器调试连接异常：" + rootMessage(error);
            closed.set(true);
        }
    }
}
