package dev.hermit.crab.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.http.UrlComponents;
import dev.hermit.crab.tls.TransportFailure;

/**
 * 不自动跟随重定向。绝对地址和重定向目标的 scheme、host、port 必须与 base_url 一致，
 * 避免把会话 Cookie 发到别的主机。跨源时停在当前响应。
 */
final class HttpCalls {

    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    static final int WIRE_BODY_LIMIT = 64 * 1024;
    static final String WIRE_BODY_NOTE = "实际发送的正文超过 64 KiB，已截断。";
    private static final int MAX_REDIRECTS = 5;
    /**
     * 一条共享的定时线程。任务在 {@code once} 的 finally 里取消，并开启 remove-on-cancel，
     * 避免每个请求留下线程或在队列里堆积已完成的超时任务。
     */
    private static final ScheduledThreadPoolExecutor DEADLINES = newDeadlineExecutor();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CHARSET = Pattern.compile(
            "(?i)charset\\s*=\\s*['\"]?\\s*([A-Za-z0-9._+-]+)");
    private static final Pattern META = Pattern.compile(
            "(?i)<meta[^>]*charset\\s*=\\s*['\"]?\\s*([A-Za-z0-9._+-]+)");

    private HttpCalls() {
    }

    private static ScheduledThreadPoolExecutor newDeadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "hermit-http-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    static HttpResult execute(
            Map<String, Object> spec,
            CloseableHttpClient client,
            Runnable afterExchange,
            URI base,
            String expectPrefix,
            long deadlineNanos
    ) {
        if (present(spec, "path") == present(spec, "url")) {
            throw problem(ErrorCode.RULE_MISMATCH, "path 与 url 必须二选一");
        }
        if (spec.get("json") != null && spec.get("form") != null) {
            throw problem(ErrorCode.RULE_MISMATCH, "json 与 form 互斥");
        }
        String method = Values.upper(spec.get("method"), "GET");
        if (!method.matches("[A-Z]+")) {
            throw problem(ErrorCode.RULE_MISMATCH, "方法无效");
        }
        Charset charset = charset(spec.get("encoding"));
        boolean follow = Values.bool(spec.get("follow_redirects"), false);
        boolean formQuery = formQuery(spec);
        Long stepLimit = timeoutLimit(spec.get("timeout_ms"));
        URI target = resolve(base, spec, charset);
        Map<String, Object> query = optionalMap(spec.get("query"), "query");
        if (query != null && !query.isEmpty()) {
            target = withQuery(target, appendQuery(target.getRawQuery(), query, charset, formQuery));
        }
        byte[] body = null;
        ContentType contentType = null;
        if (spec.get("form") != null) {
            Map<String, Object> form = optionalMap(spec.get("form"), "form");
            String encoded = appendQuery(null, form, charset, true);
            body = encoded.getBytes(charset);
            contentType = ContentType.create("application/x-www-form-urlencoded", charset);
        } else if (spec.get("json") != null) {
            try {
                // 字符写出保留辅助平面字符，再按步骤编码。字节写出把它们换成代理项转义，字符集就用不上。
                String json = JSON.writeValueAsString(spec.get("json"));
                body = UrlComponents.encode(json, charset);
            } catch (JsonProcessingException ex) {
                throw problem(ErrorCode.RULE_MISMATCH, "json 无法编码");
            } catch (UrlComponents.Unencodable ex) {
                throw problem(ErrorCode.RULE_MISMATCH, "json 含有编码无法表示的字符");
            }
            contentType = StandardCharsets.UTF_8.equals(charset)
                    ? ContentType.APPLICATION_JSON
                    : ContentType.create("application/json", charset);
        }
        Map<String, Object> headers = optionalMap(spec.get("headers"), "headers");
        boolean lost = false;
        int hops = 0;
        while (true) {
            Exchange exchange = once(
                    client,
                    afterExchange,
                    method,
                    target,
                    body,
                    contentType,
                    headers,
                    charset,
                    stepLimit,
                    deadlineNanos);
            if (expectPrefix != null && isRedirect(exchange.status)
                    && pathHasPrefix(exchange.redirect, target, expectPrefix)) {
                lost = true;
            }
            if (!follow || !isRedirect(exchange.status) || exchange.redirect == null) {
                if (exchange.status >= 500) {
                    throw new RuleProblem(
                            ErrorCode.UPSTREAM_ERROR,
                            upstreamStatus(exchange.status),
                            method,
                            exchange.path,
                            exchange.status,
                            exchange.wire);
                }
                return exchange.toResult(lost);
            }
            URI next;
            try {
                next = target.resolve(exchange.redirect);
            } catch (IllegalArgumentException ex) {
                return exchange.toResult(lost);
            }
            if (!sameOrigin(base, next)) {
                return exchange.toResult(lost);
            }
            if (expectPrefix != null && pathStarts(next, expectPrefix)) {
                lost = true;
            }
            if (hops >= MAX_REDIRECTS) {
                return exchange.toResult(lost);
            }
            hops++;
            if (exchange.status == 301 || exchange.status == 302 || exchange.status == 303) {
                method = "GET";
                body = null;
                contentType = null;
            }
            target = next;
            headers = null;
        }
    }

    static boolean sameOrigin(URI base, URI other) {
        if (base == null || other == null || base.getScheme() == null || other.getScheme() == null) {
            return false;
        }
        if (base.getHost() == null || other.getHost() == null) {
            return false;
        }
        return base.getScheme().equalsIgnoreCase(other.getScheme())
                && base.getHost().equalsIgnoreCase(other.getHost())
                && port(base) == port(other);
    }

    static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        return -1;
    }

    static String decode(byte[] body, String contentType, Charset stepEncoding) {
        Charset charset = charsetFromContentType(contentType);
        if (charset == null) {
            charset = charsetFromMeta(body);
        }
        if (charset == null) {
            charset = stepEncoding;
        }
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        return new String(body, charset);
    }

    private static Exchange once(
            CloseableHttpClient client,
            Runnable afterExchange,
            String method,
            URI target,
            byte[] body,
            ContentType contentType,
            Map<String, Object> headers,
            Charset stepEncoding,
            Long stepLimit,
            long deadlineNanos
    ) {
        long remainingMs = remainingMillis(deadlineNanos);
        // 没写 timeout_ms 时，空闲读等到能力 deadline。写了则用该值，并且不超过剩余 deadline。
        long timeoutMs = stepLimit == null ? remainingMs : Math.min(stepLimit, remainingMs);
        boolean capabilityBound = stepLimit == null || remainingMs <= stepLimit;
        HttpUriRequestBase request = new HttpUriRequestBase(method, target);
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.min(timeoutMs, 15_000)))
                .build());
        if (body != null) {
            request.setEntity(new ByteArrayEntity(body, contentType));
        }
        applyHeaders(request, headers, target, method);
        String path = pathOf(target);
        // responseTimeout 只限制相邻读取的空闲。持续慢速输出不会触发它，到能力 deadline 时取消请求，
        // 才能打断已经阻塞在 InputStream.read 上的线程。没写 timeout_ms 时，这段空闲上限就是剩余 deadline。
        Cancellable cancellable = request;
        ScheduledFuture<?> cancellation = scheduleCancellation(cancellable, deadlineNanos);
        final byte[] requestBody = body;
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        HttpClientContext context = HttpClientContext.create();
        try {
            Exchange exchange = client.execute(request, context, response -> {
                captured.set(snapshotWire(executed(context, request), requestBody, stepEncoding));
                int status = response.getCode();
                LinkedHashMap<String, String> headerMap = new LinkedHashMap<>();
                for (Header header : response.getHeaders()) {
                    headerMap.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
                }
                byte[] bytes = readBody(response.getEntity(), method, path, status, deadlineNanos, cancellable);
                String location = headerMap.get("location");
                String redirect = isRedirect(status) ? location : null;
                String decoded = decode(bytes, headerMap.get("content-type"), stepEncoding);
                return new Exchange(
                        status, target.toString(), method, path, headerMap, decoded, redirect, bytes, captured.get());
            });
            if (System.nanoTime() >= deadlineNanos) {
                cancellable.cancel();
                throw new DeadlineExceeded(-1, "request", List.of());
            }
            return exchange;
        } catch (DeadlineExceeded ex) {
            throw ex;
        } catch (RuleProblem ex) {
            throw ex.withWire(captured.get());
        } catch (IOException ex) {
            Map<String, Object> wire = captured.get() != null
                    ? captured.get()
                    : snapshotWire(executed(context, request), body, stepEncoding);
            if (System.nanoTime() >= deadlineNanos) {
                cancellable.cancel();
                throw new DeadlineExceeded(-1, "request", List.of());
            }
            if (isTimeout(ex) && capabilityBound) {
                throw new DeadlineExceeded(-1, "request", List.of());
            }
            if (isTimeout(ex)) {
                throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, STEP_TIMEOUT, method, path, null, wire);
            }
            TransportFailure.View view = TransportFailure.observe(ex, target.getHost(), port(target));
            throw new RuleProblem(
                    ErrorCode.UPSTREAM_ERROR, view.message(), method, path, null, wire, view.fields());
        } finally {
            cancellation.cancel(false);
            afterExchange.run();
        }
    }

    private static ScheduledFuture<?> scheduleCancellation(Cancellable cancellable, long deadlineNanos) {
        long delayNanos = deadlineNanos - System.nanoTime();
        if (delayNanos < 0) {
            delayNanos = 0;
        }
        return DEADLINES.schedule(() -> cancellable.cancel(), delayNanos, TimeUnit.NANOSECONDS);
    }

    private static void applyHeaders(HttpUriRequestBase request, Map<String, Object> headers, URI target, String method) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name.equalsIgnoreCase("Cookie")) {
                continue;
            }
            String value = Values.text(entry.getValue());
            if (unsendableHeader(value)) {
                throw new RuleProblem(
                        ErrorCode.RULE_MISMATCH,
                        "请求头 " + name + " 含有无法发送的字符，请先用 urlencode 处理",
                        method,
                        pathOf(target),
                        null);
            }
            if (name.equalsIgnoreCase("Host")) {
                if (!hostMatches(target, value)) {
                    throw new RuleProblem(ErrorCode.INTERNAL, "目标地址与 base_url 不同源", method, pathOf(target), null);
                }
                continue;
            }
            request.setHeader(name, value);
        }
    }

    private static boolean hostMatches(URI target, String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim();
        Integer port = null;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0) {
                return false;
            }
            String rest = host.substring(end + 1);
            host = host.substring(1, end);
            if (rest.startsWith(":")) {
                port = parsePort(rest.substring(1));
            }
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) {
                port = parsePort(host.substring(colon + 1));
                host = host.substring(0, colon);
            }
        }
        if (target.getHost() == null || !host.equalsIgnoreCase(target.getHost())) {
            return false;
        }
        return port == null || port == HttpCalls.port(target);
    }

    private static Integer parsePort(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static byte[] readBody(
            HttpEntity entity,
            String method,
            String path,
            int status,
            long deadlineNanos,
            Cancellable request
    ) throws IOException {
        if (entity == null) {
            ensureWithinDeadline(deadlineNanos, request);
            return new byte[0];
        }
        long declared = entity.getContentLength();
        if (declared > MAX_BODY_BYTES) {
            throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, "响应体超过 5MB", method, path, status);
        }
        try (InputStream in = entity.getContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                ensureWithinDeadline(deadlineNanos, request);
                int read;
                try {
                    read = in.read(buffer);
                } catch (IOException ex) {
                    if (System.nanoTime() >= deadlineNanos) {
                        request.cancel();
                        throw new DeadlineExceeded(-1, "request", List.of());
                    }
                    throw ex;
                }
                if (read < 0) {
                    break;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    request.cancel();
                    throw new DeadlineExceeded(-1, "request", List.of());
                }
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, "响应体超过 5MB", method, path, status);
                }
                out.write(buffer, 0, read);
            }
            ensureWithinDeadline(deadlineNanos, request);
            return out.toByteArray();
        }
    }

    private static void ensureWithinDeadline(long deadlineNanos, Cancellable request) {
        if (System.nanoTime() >= deadlineNanos) {
            request.cancel();
            throw new DeadlineExceeded(-1, "request", List.of());
        }
    }

    private static URI resolve(URI base, Map<String, Object> spec, Charset charset) {
        try {
            String reference = present(spec, "url")
                    ? Values.text(spec.get("url"))
                    : Values.text(spec.get("path"));
            URI resolved = UrlComponents.resolve(base, reference, charset);
            if (!sameOrigin(base, resolved)) {
                throw new RuleProblem(ErrorCode.INTERNAL, "目标地址与 base_url 不同源", null, null, null);
            }
            return resolved;
        } catch (UrlComponents.Unencodable ex) {
            throw problem(ErrorCode.RULE_MISMATCH, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw problem(ErrorCode.RULE_MISMATCH, "地址无效");
        }
    }

    /** {@code form} 为 true 时用 HTML 表单编码。query 默认走 URL 编码；form 正文始终为 true。 */
    private static String appendQuery(String existing, Map<String, Object> query, Charset charset, boolean form) {
        StringBuilder builder = new StringBuilder();
        if (existing != null && !existing.isEmpty()) {
            builder.append(existing);
        }
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(encodeComponent(entry.getKey(), charset, form));
            builder.append('=');
            builder.append(encodeComponent(Values.text(entry.getValue()), charset, form));
        }
        return builder.toString();
    }

    private static String encodeComponent(String text, Charset charset, boolean form) {
        if (form) {
            return URLEncoder.encode(text, charset);
        }
        try {
            return UrlComponents.query(text, charset);
        } catch (UrlComponents.Unencodable ex) {
            throw problem(ErrorCode.RULE_MISMATCH, ex.getMessage());
        }
    }

    private static boolean formQuery(Map<String, Object> spec) {
        Object style = spec.get("query_style");
        if (style == null) {
            return false;
        }
        String text = Values.text(style);
        if ("url".equals(text)) {
            return false;
        }
        if ("form".equals(text)) {
            return true;
        }
        throw problem(ErrorCode.RULE_MISMATCH, "query_style 只允许 url 或 form");
    }

    private static boolean unsendableHeader(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0xFF) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拦截器加在送出去的副本上，原请求对象看不到 User-Agent 和 Accept-Encoding。
     * 上下文里的是那份副本。
     */
    private static ClassicHttpRequest executed(HttpClientContext context, HttpUriRequestBase fallback) {
        HttpRequest sent = context.getRequest();
        if (sent instanceof ClassicHttpRequest classic) {
            return classic;
        }
        return fallback;
    }

    private static Map<String, Object> snapshotWire(ClassicHttpRequest request, byte[] body, Charset charset) {
        LinkedHashMap<String, Object> wire = new LinkedHashMap<>();
        wire.put("method", request.getMethod());
        wire.put("target", requestTarget(request));
        Header contentType = request.getFirstHeader("Content-Type");
        wire.put("contentType", contentType == null ? null : contentType.getValue());
        LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
        for (Header header : request.getHeaders()) {
            if (header.getName() == null || droppedWireHeader(header.getName())) {
                continue;
            }
            headers.putIfAbsent(header.getName(), header.getValue());
        }
        wire.put("headers", headers);
        if (body == null) {
            wire.put("body", null);
        } else {
            String text = new String(body, charset);
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            if (utf8.length > WIRE_BODY_LIMIT) {
                wire.put("body", truncateUtf8(text, WIRE_BODY_LIMIT));
                wire.put("truncated", true);
                wire.put("truncation", WIRE_BODY_NOTE);
            } else {
                wire.put("body", text);
            }
        }
        return wire;
    }

    private static String truncateUtf8(String text, int limit) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int end = limit;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** 请求行里的 path 加查询串。fragment 不在请求行上。 */
    private static String requestTarget(ClassicHttpRequest request) {
        URI uri;
        try {
            uri = request.getUri();
        } catch (URISyntaxException ex) {
            uri = null;
        }
        if (uri == null) {
            String raw = request.getRequestUri();
            if (raw == null || raw.isEmpty()) {
                return "/";
            }
            int hash = raw.indexOf('#');
            return hash >= 0 ? raw.substring(0, hash) : raw;
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    private static boolean droppedWireHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return "cookie".equals(lower) || "authorization".equals(lower) || "mcp-session-id".equals(lower);
    }

    private static URI withQuery(URI uri, String rawQuery) {
        StringBuilder raw = new StringBuilder();
        raw.append(uri.getScheme()).append("://");
        if (uri.getRawAuthority() != null) {
            raw.append(uri.getRawAuthority());
        }
        raw.append(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (rawQuery != null && !rawQuery.isEmpty()) {
            raw.append('?').append(rawQuery);
        }
        if (uri.getRawFragment() != null) {
            raw.append('#').append(uri.getRawFragment());
        }
        return URI.create(raw.toString());
    }

    private static Charset charset(Object encoding) {
        String name = encoding == null ? "UTF-8" : Values.text(encoding);
        if (name.isBlank()) {
            name = "UTF-8";
        }
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
            throw problem(ErrorCode.RULE_MISMATCH, "不支持的编码");
        }
    }

    static Charset charsetFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
            return null;
        }
    }

    static Charset charsetFromMeta(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        int length = Math.min(body.length, 8192);
        String head = new String(body, 0, length, StandardCharsets.ISO_8859_1);
        Matcher matcher = META.matcher(head);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
            return null;
        }
    }

    /** 规则没写 {@code timeout_ms} 时返回 null，读超时改用剩余的能力 deadline。 */
    private static Long timeoutLimit(Object value) {
        if (value == null) {
            return null;
        }
        int parsed = Values.integer(value, -1);
        if (parsed <= 0) {
            throw problem(ErrorCode.RULE_MISMATCH, "timeout_ms 无效");
        }
        return (long) parsed;
    }

    static final String STEP_TIMEOUT = "步骤超时";

    static String upstreamStatus(int status) {
        return "上游状态码 " + status;
    }

    private static long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new DeadlineExceeded(-1, "request", List.of());
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
        return Math.max(1, millis);
    }

    private static boolean isTimeout(Throwable failure) {
        return TransportFailure.isTimeout(failure);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean pathHasPrefix(String location, URI current, String prefix) {
        if (location == null || prefix == null || prefix.isEmpty()) {
            return false;
        }
        try {
            return pathStarts(current.resolve(location), prefix);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean pathStarts(URI uri, String prefix) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return path.startsWith(prefix);
    }

    private static String pathOf(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static boolean present(Map<String, Object> spec, String key) {
        Object value = spec.get(key);
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || !text.isBlank();
    }

    private static Map<String, Object> optionalMap(Object value, String name) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = Values.map(value);
        if (map == null) {
            throw problem(ErrorCode.RULE_MISMATCH, name + " 必须是映射");
        }
        return map;
    }

    private static RuleProblem problem(ErrorCode code, String summary) {
        return new RuleProblem(code, summary, null, null, null);
    }

    private record Exchange(
            int status,
            String url,
            String method,
            String path,
            Map<String, String> headers,
            String body,
            String redirect,
            byte[] bodyBytes,
            Map<String, Object> wire
    ) {
        private HttpResult toResult(boolean sessionLost) {
            return new HttpResult(
                    status,
                    url,
                    method,
                    path,
                    Collections.unmodifiableMap(headers),
                    body,
                    redirect,
                    sessionLost,
                    bodyBytes,
                    wire);
        }
    }
}

record HttpResult(
        int status,
        String url,
        String method,
        String path,
        Map<String, String> headers,
        String body,
        String redirect,
        boolean sessionLost,
        byte[] bodyBytes,
        Map<String, Object> wire
) {
    Map<String, Object> view() {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>();
        view.put("status", status);
        view.put("url", url);
        view.put("headers", headers);
        view.put("body", body);
        view.put("redirect", redirect);
        return Collections.unmodifiableMap(view);
    }
}
