package dev.hermit.crab.upstream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.mcp.McpAddresses;
import dev.hermit.common.model.McpAuth;
import dev.hermit.crab.http.HttpMessageLimits;
import dev.hermit.crab.http.OutboundHttp;
import dev.hermit.crab.tls.OutboundTls;
import dev.hermit.crab.tls.TransportFailure;

/**
 * OAuth 2.1 授权码 + PKCE，以及端点省略时的发现。
 * 不跟随重定向。每个 URL 先过 {@code allowed_hosts}，连接时再检查解析后的地址。
 */
final class McpOAuthClient implements AutoCloseable {

    private static final int MAX_BODY = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RESOURCE_METADATA = Pattern.compile(
            "(?i)resource_metadata\\s*=\\s*(\"([^\"]*)\"|([^\\s,]+))");
    private static final ThreadLocal<Boolean> ALLOW_LOOPBACK = new ThreadLocal<>();

    private final CloseableHttpClient http;
    private final PoolingHttpClientConnectionManager pool;
    private final OutboundTls tls;

    McpOAuthClient() {
        this(HttpMessageLimits.defaults());
    }

    /**
     * 发现和令牌请求都不重试。POST 换令牌不能重放，发现用的 GET 也不跟着规则引擎的陈旧连接重试走。
     * 响应头上限和 2 秒的连接复查与另外两个 Apache 客户端相同。连接超时仍是 10 秒。
     */
    McpOAuthClient(HttpMessageLimits headers) {
        this.tls = OutboundTls.current();
        this.pool = OutboundHttp.connectionManager(
                tls, headers, OutboundHttp.OTHER_CONNECT, null, checkedDns(), 8, 32);
        this.http = HttpClients.custom()
                .setConnectionManager(pool)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    Resolved resolve(Target target, McpAuth auth, long deadlineNanos) {
        String authorize = blankToNull(auth == null ? null : auth.authorizationEndpoint());
        String token = blankToNull(auth == null ? null : auth.tokenEndpoint());
        if (authorize != null && token != null) {
            return validatePair(target, authorize, token, deadlineNanos);
        }
        Discovered discovered = discover(target, deadlineNanos);
        if (discovered.failure != null) {
            return discovered.failure;
        }
        if (authorize == null) {
            authorize = discovered.authorizationEndpoint;
        }
        if (token == null) {
            token = discovered.tokenEndpoint;
        }
        return validatePair(target, authorize, token, deadlineNanos);
    }

    Exchange exchangeCode(
            Target target,
            String tokenEndpoint,
            String clientId,
            String resource,
            String code,
            String redirectUri,
            String verifier,
            String clientSecret,
            Instant now,
            long deadlineNanos
    ) {
        String body = form(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirectUri,
                "client_id", clientId,
                "code_verifier", verifier,
                "resource", resource), clientSecret);
        return postToken(target, tokenEndpoint, body, null, now, deadlineNanos);
    }

    Exchange refresh(
            Target target,
            String tokenEndpoint,
            String clientId,
            String resource,
            String refreshToken,
            String clientSecret,
            Instant now,
            long deadlineNanos
    ) {
        String body = form(Map.of(
                "grant_type", "refresh_token",
                "refresh_token", refreshToken,
                "client_id", clientId,
                "resource", resource), clientSecret);
        return postToken(target, tokenEndpoint, body, refreshToken, now, deadlineNanos);
    }

    @Override
    public void close() {
        tls.untrack(pool);
        try {
            http.close();
        } catch (IOException ignored) {
            // 关闭失败不影响进程退出。
        }
    }

    private Resolved validatePair(Target target, String authorize, String token, long deadlineNanos) {
        if (authorize == null || token == null) {
            return Resolved.failure(ErrorCode.NOT_CONFIGURED, McpOAuth.DISCOVERY_FAILED);
        }
        ExchangeKind authorizeKind = screen(authorize, target);
        ExchangeKind tokenKind = screen(token, target);
        ExchangeKind kind = authorizeKind != null ? authorizeKind : tokenKind;
        if (kind != null) {
            return Resolved.failure(code(kind), message(kind));
        }
        ExchangeKind authorizeDns = nameCheck(authorize, target, deadlineNanos);
        if (authorizeDns != null) {
            return namedFailure(authorize, authorizeDns);
        }
        ExchangeKind tokenDns = nameCheck(token, target, deadlineNanos);
        if (tokenDns != null) {
            return namedFailure(token, tokenDns);
        }
        return Resolved.ok(authorize, token);
    }

    private Discovered discover(Target target, long deadlineNanos) {
        DiscoveryNotes notes = new DiscoveryNotes();
        JsonNode document = null;
        String hinted = probeResourceMetadata(target, deadlineNanos, notes);
        Discovered blocked = notes.blocked();
        if (blocked != null) {
            return blocked;
        }
        if (hinted != null && screen(hinted, target) == null) {
            document = metadataDocument(fetchMetadata(hinted, target, deadlineNanos, notes));
            blocked = notes.blocked();
            if (blocked != null) {
                return blocked;
            }
        }
        if (document == null) {
            for (String url : protectedResourceUrls(target.upstreamUrl)) {
                if (System.nanoTime() >= deadlineNanos) {
                    return Discovered.failure(ErrorCode.TIMEOUT, McpForwarder.DEADLINE);
                }
                if (screen(url, target) != null) {
                    continue;
                }
                document = metadataDocument(fetchMetadata(url, target, deadlineNanos, notes));
                blocked = notes.blocked();
                if (blocked != null) {
                    return blocked;
                }
                if (document != null && document.has("authorization_servers")) {
                    break;
                }
                document = null;
            }
        }
        if (document == null) {
            return notes.fallback();
        }
        JsonNode servers = document.get("authorization_servers");
        if (servers == null || !servers.isArray() || servers.isEmpty()) {
            return notes.fallback();
        }
        boolean sawDisallowed = false;
        for (JsonNode issuerNode : servers) {
            if (System.nanoTime() >= deadlineNanos) {
                return Discovered.failure(ErrorCode.TIMEOUT, McpForwarder.DEADLINE);
            }
            String issuer = issuerNode.isTextual() ? issuerNode.asText("").trim() : "";
            if (issuer.isEmpty()) {
                continue;
            }
            ExchangeKind kind = screen(issuer, target);
            if (kind == ExchangeKind.HOST) {
                sawDisallowed = true;
                continue;
            }
            if (kind != null) {
                continue;
            }
            JsonNode metadata = authorizationServerMetadata(issuer, target, deadlineNanos, notes);
            blocked = notes.blocked();
            if (blocked != null) {
                return blocked;
            }
            if (metadata == null) {
                continue;
            }
            String authorize = text(metadata.get("authorization_endpoint"));
            String token = text(metadata.get("token_endpoint"));
            if (authorize == null || token == null) {
                continue;
            }
            if (screen(authorize, target) == ExchangeKind.HOST || screen(token, target) == ExchangeKind.HOST) {
                sawDisallowed = true;
                continue;
            }
            return new Discovered(authorize, token, null);
        }
        if (sawDisallowed) {
            return Discovered.failure(ErrorCode.NOT_CONFIGURED, McpForwarder.HOST_DENIED);
        }
        return notes.fallback();
    }

    private String probeResourceMetadata(Target target, long deadlineNanos, DiscoveryNotes notes) {
        if (screen(target.upstreamUrl, target) != null) {
            return null;
        }
        HttpResult response = get(target.upstreamUrl, target, deadlineNanos);
        notes.noteProbe(response);
        if (notes.immediate() != null || response.kind != ExchangeKind.TOKEN || response.status != 401) {
            return null;
        }
        return resourceMetadata(response.wwwAuthenticate);
    }

    private HttpResult fetchMetadata(String url, Target target, long deadlineNanos, DiscoveryNotes notes) {
        HttpResult response = get(url, target, deadlineNanos);
        notes.noteMetadata(response);
        return response;
    }

    private JsonNode authorizationServerMetadata(String issuer, Target target, long deadlineNanos, DiscoveryNotes notes) {
        URI uri;
        try {
            uri = URI.create(issuer);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String oauth = insertWellKnown(uri, "/.well-known/oauth-authorization-server");
        String oidc = origin(uri) + pathOf(uri) + "/.well-known/openid-configuration";
        for (String url : List.of(oauth, oidc)) {
            if (screen(url, target) != null) {
                continue;
            }
            JsonNode document = metadataDocument(fetchMetadata(url, target, deadlineNanos, notes));
            if (notes.immediate() != null) {
                return null;
            }
            if (document != null && document.hasNonNull("authorization_endpoint")) {
                return document;
            }
        }
        return null;
    }

    private Exchange postToken(
            Target target,
            String tokenEndpoint,
            String body,
            String previousRefresh,
            Instant now,
            long deadlineNanos
    ) {
        HttpResult response = send(
                "POST",
                tokenEndpoint,
                body.getBytes(StandardCharsets.UTF_8),
                target,
                deadlineNanos);
        if (response.kind != ExchangeKind.TOKEN) {
            return new Exchange(response.kind, null, response.detail());
        }
        if (response.status >= 300 && response.status < 400) {
            return new Exchange(ExchangeKind.REJECTED, null);
        }
        if (response.status != 200) {
            return new Exchange(ExchangeKind.REJECTED, null);
        }
        JsonNode node = json(response.body);
        McpOAuthToken token = McpOAuthToken.fromResponse(node, now, previousRefresh);
        if (token == null) {
            return new Exchange(ExchangeKind.REJECTED, null);
        }
        return new Exchange(ExchangeKind.TOKEN, token);
    }

    private HttpResult get(String url, Target target, long deadlineNanos) {
        return send("GET", url, null, target, deadlineNanos);
    }

    private HttpResult send(
            String method,
            String url,
            byte[] body,
            Target target,
            long deadlineNanos
    ) {
        ExchangeKind screened = screen(url, target);
        if (screened != null) {
            return HttpResult.stop(screened);
        }
        long now = System.nanoTime();
        if (now >= deadlineNanos) {
            return HttpResult.stop(ExchangeKind.TIMEOUT);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return HttpResult.stop(ExchangeKind.HOST);
        }
        HttpUriRequestBase request = new HttpUriRequestBase(method, uri);
        long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - now));
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(remainingMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.min(remainingMs, 10_000)))
                .setRedirectsEnabled(false)
                .build());
        request.setHeader("Accept", "application/json");
        if (body != null) {
            request.setEntity(new ByteArrayEntity(
                    body, ContentType.create("application/x-www-form-urlencoded", (Charset) null)));
        }
        ALLOW_LOOPBACK.set(target.allowLoopback());
        try {
            return http.execute(request, response -> {
                byte[] bytes;
                try {
                    bytes = readLimited(response.getEntity() == null ? null : response.getEntity().getContent());
                } catch (TooLarge ex) {
                    return HttpResult.stop(ExchangeKind.REJECTED);
                }
                return HttpResult.done(response.getCode(), bytes, header(response, "WWW-Authenticate"));
            });
        } catch (IOException ex) {
            if (rejected(ex)) {
                return HttpResult.stop(ExchangeKind.ADDRESS);
            }
            if (System.nanoTime() >= deadlineNanos || TransportFailure.isTimeout(ex)) {
                return HttpResult.stop(ExchangeKind.TIMEOUT);
            }
            TransportFailure.View view = TransportFailure.observe(ex, uri.getHost(), TransportFailure.portOf(uri));
            return HttpResult.stop(ExchangeKind.CONNECT, view.message());
        } finally {
            ALLOW_LOOPBACK.remove();
        }
    }

    private static ExchangeKind screen(String url, Target target) {
        McpAddresses.ParseResult parsed = McpAddresses.parse(url);
        McpAddresses.ParseResult upstream = McpAddresses.parse(target.upstreamUrl);
        if (!parsed.ok() || !upstream.ok()) {
            return ExchangeKind.HOST;
        }
        if (!McpAddresses.hostAllowed(parsed.url().host(), target.allowedHosts)) {
            return ExchangeKind.HOST;
        }
        if ("http".equals(parsed.url().scheme())
                && (!"http".equals(upstream.url().scheme())
                || !McpAddresses.sameOrigin(upstream.url(), parsed.url()))) {
            return ExchangeKind.HOST;
        }
        if (literal(parsed.url().host()) && rejectedLiteral(parsed.url().host(), target.allowLoopback())) {
            return ExchangeKind.ADDRESS;
        }
        return null;
    }

    private static Resolved namedFailure(String url, ExchangeKind kind) {
        if (kind == ExchangeKind.CONNECT) {
            String host = hostOf(url);
            int port = -1;
            try {
                port = TransportFailure.portOf(URI.create(url));
            } catch (IllegalArgumentException ignored) {
                port = -1;
            }
            TransportFailure.View view = TransportFailure.observe(
                    new UnknownHostException(host == null ? "" : host), host, port);
            return Resolved.failure(ErrorCode.UPSTREAM_ERROR, view.message());
        }
        return Resolved.failure(code(kind), message(kind));
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ExchangeKind nameCheck(String url, Target target, long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            return ExchangeKind.TIMEOUT;
        }
        McpAddresses.ParseResult parsed = McpAddresses.parse(url);
        if (!parsed.ok() || literal(parsed.url().host())) {
            return null;
        }
        try {
            InetAddress[] all = InetAddress.getAllByName(parsed.url().host());
            for (InetAddress address : all) {
                if (McpAddressGuard.rejected(address, target.allowLoopback())) {
                    return ExchangeKind.ADDRESS;
                }
            }
            return null;
        } catch (UnknownHostException ex) {
            return ExchangeKind.CONNECT;
        }
    }

    private static boolean rejectedLiteral(String host, boolean allowLoopback) {
        try {
            return McpAddressGuard.rejected(InetAddress.getByName(host), allowLoopback);
        } catch (UnknownHostException ex) {
            return true;
        }
    }

    private static boolean literal(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    static List<String> protectedResourceUrls(String mcpUrl) {
        URI uri = URI.create(mcpUrl);
        String path = pathOf(uri);
        ArrayList<String> urls = new ArrayList<>();
        if (!path.isEmpty()) {
            urls.add(origin(uri) + "/.well-known/oauth-protected-resource" + path);
        }
        urls.add(origin(uri) + "/.well-known/oauth-protected-resource");
        return urls;
    }

    private static String insertWellKnown(URI issuer, String wellKnown) {
        return origin(issuer) + wellKnown + pathOf(issuer);
    }

    private static String pathOf(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        if ("/".equals(path)) {
            return "";
        }
        return path;
    }

    private static String origin(URI uri) {
        String host = uri.getHost();
        if (host != null && host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        int port = uri.getPort();
        String scheme = uri.getScheme();
        boolean defaultPort = port < 0
                || ("https".equalsIgnoreCase(scheme) && port == 443)
                || ("http".equalsIgnoreCase(scheme) && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    static String resourceMetadata(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher matcher = RESOURCE_METADATA.matcher(header);
        if (!matcher.find()) {
            return null;
        }
        String quoted = matcher.group(2);
        String value = quoted != null ? quoted : matcher.group(3);
        return value == null || value.isBlank() ? null : value;
    }

    private static JsonNode metadataDocument(HttpResult response) {
        if (response.kind != ExchangeKind.TOKEN || response.status != 200) {
            return null;
        }
        JsonNode node = json(response.body);
        return node != null && node.isObject() ? node : null;
    }

    private static JsonNode json(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            return node.isObject() ? node : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private static String form(Map<String, String> fields, String clientSecret) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            appendForm(body, entry.getKey(), entry.getValue());
        }
        if (clientSecret != null && !clientSecret.isBlank()) {
            appendForm(body, "client_secret", clientSecret);
        }
        return body.toString();
    }

    static String withQuery(String endpoint, List<Map.Entry<String, String>> query) {
        StringBuilder url = new StringBuilder(endpoint);
        char join = endpoint.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> entry : query) {
            if (entry.getValue() == null) {
                continue;
            }
            url.append(join);
            join = '&';
            url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return url.toString();
    }

    private static void appendForm(StringBuilder body, String key, String value) {
        if (value == null) {
            return;
        }
        if (body.length() > 0) {
            body.append('&');
        }
        body.append(encode(key)).append('=').append(encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ErrorCode code(ExchangeKind kind) {
        return switch (kind) {
            case HOST -> ErrorCode.NOT_CONFIGURED;
            case ADDRESS, CONNECT, REJECTED -> ErrorCode.UPSTREAM_ERROR;
            case TIMEOUT -> ErrorCode.TIMEOUT;
            case TOKEN -> null;
        };
    }

    private static String message(ExchangeKind kind) {
        return switch (kind) {
            case HOST -> McpForwarder.HOST_DENIED;
            case ADDRESS -> McpForwarder.ADDRESS_DENIED;
            case CONNECT, REJECTED -> McpForwarder.CONNECT_FAILED;
            case TIMEOUT -> McpForwarder.DEADLINE;
            case TOKEN -> null;
        };
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buf)) != -1) {
                total += read;
                if (total > MAX_BODY) {
                    throw new TooLarge();
                }
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String header(org.apache.hc.core5.http.HttpResponse response, String name) {
        for (Header header : response.getHeaders()) {
            if (header.getName() != null && header.getName().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    private static boolean rejected(IOException ex) {
        Throwable current = ex;
        while (current != null) {
            if ("hermit-rejected-address".equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static DnsResolver checkedDns() {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] all = InetAddress.getAllByName(host);
                boolean allow = Boolean.TRUE.equals(ALLOW_LOOPBACK.get());
                for (InetAddress address : all) {
                    if (McpAddressGuard.rejected(address, allow)) {
                        throw new UnknownHostException("hermit-rejected-address");
                    }
                }
                return all;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    record Target(String upstreamUrl, List<String> allowedHosts) {
        static Target of(dev.hermit.common.model.ConchDoc doc) {
            return new Target(doc.upstream().url(), doc.upstream().allowedHosts());
        }

        boolean allowLoopback() {
            McpAddresses.ParseResult parsed = McpAddresses.parse(upstreamUrl);
            return parsed.ok() && McpAddressGuard.packageAllowsLoopback(parsed.url().host());
        }
    }

    enum ExchangeKind {
        TOKEN,
        HOST,
        ADDRESS,
        REJECTED,
        TIMEOUT,
        CONNECT
    }

    record Resolved(String authorizationEndpoint, String tokenEndpoint, ErrorCode code, String message) {
        boolean ok() {
            return code == null;
        }

        static Resolved ok(String authorizationEndpoint, String tokenEndpoint) {
            return new Resolved(authorizationEndpoint, tokenEndpoint, null, null);
        }

        static Resolved failure(ErrorCode code, String message) {
            return new Resolved(null, null, code, message);
        }
    }

    record Exchange(ExchangeKind kind, McpOAuthToken token, String detail) {
        Exchange(ExchangeKind kind, McpOAuthToken token) {
            this(kind, token, null);
        }

        boolean ok() {
            return kind == ExchangeKind.TOKEN && token != null;
        }
    }

    private record Discovered(String authorizationEndpoint, String tokenEndpoint, Resolved failure) {
        static Discovered failure(ErrorCode code, String message) {
            return new Discovered(null, null, Resolved.failure(code, message));
        }
    }

    private record HttpResult(ExchangeKind kind, int status, byte[] body, String wwwAuthenticate, String detail) {
        static HttpResult done(int status, byte[] body, String wwwAuthenticate) {
            return new HttpResult(ExchangeKind.TOKEN, status, body, wwwAuthenticate, null);
        }

        static HttpResult stop(ExchangeKind kind) {
            return new HttpResult(kind, 0, null, null, null);
        }

        static HttpResult stop(ExchangeKind kind, String detail) {
            return new HttpResult(kind, 0, null, null, detail);
        }
    }

    private static final class DiscoveryNotes {
        private boolean metadataHttp;
        private HttpResult transport;
        private Resolved immediate;

        void noteProbe(HttpResult response) {
            if (response.kind == ExchangeKind.TIMEOUT) {
                immediate = Resolved.failure(ErrorCode.TIMEOUT, McpForwarder.DEADLINE);
                return;
            }
            rememberTransport(response);
        }

        void noteMetadata(HttpResult response) {
            if (response.kind == ExchangeKind.TIMEOUT) {
                immediate = Resolved.failure(ErrorCode.TIMEOUT, McpForwarder.DEADLINE);
                return;
            }
            if (response.kind == ExchangeKind.TOKEN) {
                metadataHttp = true;
                return;
            }
            rememberTransport(response);
        }

        private void rememberTransport(HttpResult response) {
            if (transport != null) {
                return;
            }
            if (response.kind == ExchangeKind.CONNECT || response.kind == ExchangeKind.ADDRESS) {
                transport = response;
            }
        }

        Resolved immediate() {
            return immediate;
        }

        Discovered blocked() {
            if (immediate == null) {
                return null;
            }
            return Discovered.failure(immediate.code(), immediate.message());
        }

        Discovered fallback() {
            Discovered blocked = blocked();
            if (blocked != null) {
                return blocked;
            }
            if (!metadataHttp && transport != null) {
                String text = transport.detail == null || transport.detail.isBlank()
                        ? message(transport.kind)
                        : transport.detail;
                return Discovered.failure(code(transport.kind), text);
            }
            return Discovered.failure(ErrorCode.NOT_CONFIGURED, McpOAuth.DISCOVERY_FAILED);
        }
    }

    private static final class TooLarge extends IOException {
    }
}
