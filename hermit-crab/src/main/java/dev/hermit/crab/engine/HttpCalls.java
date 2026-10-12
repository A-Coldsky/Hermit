package dev.hermit.crab.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import dev.hermit.common.error.ErrorCode;

/**
 * 不自动跟随重定向。绝对地址和重定向目标的 scheme、host、port 必须与 base_url 一致，
 * 避免把会话 Cookie 发到别的主机。跨源时停在当前响应。
 */
final class HttpCalls {

    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CHARSET = Pattern.compile(
            "(?i)charset\\s*=\\s*['\"]?\\s*([A-Za-z0-9._+-]+)");
    private static final Pattern META = Pattern.compile(
            "(?i)<meta[^>]*charset\\s*=\\s*['\"]?\\s*([A-Za-z0-9._+-]+)");

    private HttpCalls() {
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
        int stepTimeout = positive(spec.get("timeout_ms"), 15_000);
        URI target = resolve(base, spec);
        Map<String, Object> query = optionalMap(spec.get("query"), "query");
        if (query != null && !query.isEmpty()) {
            target = withQuery(target, appendQuery(target.getRawQuery(), query, charset));
        }
        byte[] body = null;
        ContentType contentType = null;
        if (spec.get("form") != null) {
            Map<String, Object> form = optionalMap(spec.get("form"), "form");
            String encoded = appendQuery(null, form, charset);
            body = encoded.getBytes(charset);
            contentType = ContentType.create("application/x-www-form-urlencoded", charset);
        } else if (spec.get("json") != null) {
            try {
                body = JSON.writeValueAsBytes(spec.get("json"));
            } catch (JsonProcessingException ex) {
                throw problem(ErrorCode.RULE_MISMATCH, "json 无法编码");
            }
            contentType = ContentType.APPLICATION_JSON;
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
                    stepTimeout,
                    deadlineNanos);
            if (expectPrefix != null && isRedirect(exchange.status)
                    && pathHasPrefix(exchange.redirect, target, expectPrefix)) {
                lost = true;
            }
            if (!follow || !isRedirect(exchange.status) || exchange.redirect == null) {
                if (exchange.status >= 500) {
                    throw new RuleProblem(
                            ErrorCode.UPSTREAM_ERROR,
                            "上游状态码 " + exchange.status,
                            method,
                            exchange.path,
                            exchange.status);
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
            int stepTimeout,
            long deadlineNanos
    ) {
        long remainingMs = remainingMillis(deadlineNanos);
        long timeoutMs = Math.min(stepTimeout, remainingMs);
        boolean capabilityBound = remainingMs <= stepTimeout;
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
        try {
            return client.execute(request, response -> {
                int status = response.getCode();
                LinkedHashMap<String, String> headerMap = new LinkedHashMap<>();
                for (Header header : response.getHeaders()) {
                    headerMap.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
                }
                byte[] bytes = readBody(response.getEntity(), method, path, status);
                String location = headerMap.get("location");
                String redirect = isRedirect(status) ? location : null;
                String decoded = decode(bytes, headerMap.get("content-type"), stepEncoding);
                return new Exchange(status, target.toString(), method, path, headerMap, decoded, redirect);
            });
        } catch (RuleProblem ex) {
            throw ex;
        } catch (IOException ex) {
            if (isTimeout(ex) && capabilityBound) {
                throw new DeadlineExceeded(-1, "request", List.of());
            }
            if (isTimeout(ex)) {
                throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, "上游读取超时", method, path, null);
            }
            throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, "上游连接失败", method, path, null);
        } finally {
            afterExchange.run();
        }
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

    private static byte[] readBody(HttpEntity entity, String method, String path, int status) throws IOException {
        if (entity == null) {
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
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, "响应体超过 5MB", method, path, status);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static URI resolve(URI base, Map<String, Object> spec) {
        try {
            URI resolved;
            if (present(spec, "url")) {
                URI url = URI.create(Values.text(spec.get("url")));
                resolved = url.isAbsolute() ? url : base.resolve(url);
            } else {
                resolved = base.resolve(Values.text(spec.get("path")));
            }
            if (!sameOrigin(base, resolved)) {
                throw new RuleProblem(ErrorCode.INTERNAL, "目标地址与 base_url 不同源", null, null, null);
            }
            return resolved;
        } catch (IllegalArgumentException ex) {
            throw problem(ErrorCode.RULE_MISMATCH, "地址无效");
        }
    }

    private static String appendQuery(String existing, Map<String, Object> query, Charset charset) {
        StringBuilder builder = new StringBuilder();
        if (existing != null && !existing.isEmpty()) {
            builder.append(existing);
        }
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), charset));
            builder.append('=');
            builder.append(URLEncoder.encode(Values.text(entry.getValue()), charset));
        }
        return builder.toString();
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

    private static int positive(Object value, int fallback) {
        int parsed = Values.integer(value, fallback);
        if (parsed <= 0) {
            throw problem(ErrorCode.RULE_MISMATCH, "timeout_ms 无效");
        }
        return parsed;
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
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String name = current.getClass().getName();
            if (name.endsWith("TimeoutException") || name.contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
        String path = uri.getPath();
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
            String redirect
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
                    sessionLost);
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
        boolean sessionLost
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
