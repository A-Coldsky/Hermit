package dev.hermit.crab.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.http.HostNames;
import dev.hermit.common.http.UrlComponents;
import dev.hermit.common.validate.FileTransfers;
import dev.hermit.common.validate.RequestBodies;
import dev.hermit.crab.http.OutboundHttp;
import dev.hermit.crab.tls.TransportFailure;

/**
 * 不自动跟随重定向。初始地址必须与 base_url 同源；登录步骤还可以请求 {@code auth.origins} 里的认证地址，
 * 并在 base_url 和认证地址之间跟随跳转（跨源的 307、308 除外）。
 * {@code follow_redirects: true} 时，同源跳转保留规则请求头；默认端口的 http 升到 https 也可以跟随，
 * 但这一跳去掉认证头和带凭证的头。其它跨源停在当前 3xx。
 */
final class HttpCalls {

    private static final Logger LOG = Logger.getLogger(HttpCalls.class.getName());
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    static final int WIRE_BODY_LIMIT = 64 * 1024;
    static final String WIRE_BODY_NOTE = "实际发送的正文超过 64 KiB，已截断。";
    private static final int MAX_REDIRECTS = 5;
    /** 5xx 错误文案里，状态行之后的正文上限。先整段脱敏，再按 UTF-8 字节截。 */
    static final int UPSTREAM_BODY_LIMIT = 4096;
    static final String TOO_MANY_REDIRECTS = "重定向过多";
    /** 这一跳是跟随的跨源升级。请求拦截器据此去掉 Cookie 和带凭证的头。 */
    static final String STRIP_SECRETS = "hermit.strip.secrets";
    private static final HttpRequestInterceptor STRIPPER = (request, entity, context) -> {
        Object raw = context.getAttribute(STRIP_SECRETS);
        if (!(raw instanceof Set<?> sensitive)) {
            return;
        }
        Header[] headers = request.getHeaders();
        ArrayList<String> drop = new ArrayList<>();
        for (Header header : headers) {
            if (header.getName() != null && dropOnCrossOrigin(header.getName(), header.getValue(), sensitive)) {
                drop.add(header.getName());
            }
        }
        for (String name : drop) {
            request.removeHeaders(name);
        }
    };
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
        return execute(spec, client, afterExchange, base, expectPrefix, deadlineNanos, Set.of());
    }

    static HttpResult execute(
            Map<String, Object> spec,
            CloseableHttpClient client,
            Runnable afterExchange,
            URI base,
            String expectPrefix,
            long deadlineNanos,
            Set<String> sensitive
    ) {
        return execute(
                spec, client, afterExchange, base, expectPrefix, deadlineNanos,
                sensitive, OutboundHttp.DEFAULT_BODY_IDLE);
    }

    static HttpResult execute(
            Map<String, Object> spec,
            CloseableHttpClient client,
            Runnable afterExchange,
            URI base,
            String expectPrefix,
            long deadlineNanos,
            Set<String> sensitive,
            Duration bodyIdle
    ) {
        return execute(spec, client, afterExchange, base, expectPrefix, deadlineNanos, sensitive, bodyIdle,
                AuthReach.NONE);
    }

    static HttpResult execute(
            Map<String, Object> spec,
            CloseableHttpClient client,
            Runnable afterExchange,
            URI base,
            String expectPrefix,
            long deadlineNanos,
            Set<String> sensitive,
            Duration bodyIdle,
            AuthReach reach
    ) {
        // multipart 的请求体和 download 的响应正文是文件：追踪里只留摘要，正文上限改用暂存区的单文件上限。
        if (spec.get("multipart") == null && !FileSteps.downloads(spec)) {
            return run(spec, client, afterExchange, base, expectPrefix, deadlineNanos, sensitive, bodyIdle, reach);
        }
        FileMode mode = new FileMode();
        if (FileSteps.downloads(spec)) {
            mode.bodyLimit = FileSteps.scope().stage().settings().maxFileBytes();
        }
        FILE_MODE.set(mode);
        HttpResult result = null;
        try {
            result = run(spec, client, afterExchange, base, expectPrefix, deadlineNanos, sensitive, bodyIdle,
                    reach);
            return result;
        } finally {
            FILE_MODE.remove();
            // 收到一半就失败的，或者收完但没有交给调用方的，都不留在暂存区。
            for (dev.hermit.crab.files.FileStage.Spool spool : mode.spools) {
                if (result == null || spool != result.spool()) {
                    spool.discard();
                }
            }
        }
    }

    private static final ThreadLocal<FileMode> FILE_MODE = new ThreadLocal<>();

    private static final class FileMode {
        /** 大于 0 时是 download：响应正文按文件收，不解码成文本。 */
        long bodyLimit;
        String wireBody;
        /** 这次请求里开始收的文件，包括跟随跳转时前几跳的。 */
        final ArrayList<dev.hermit.crab.files.FileStage.Spool> spools = new ArrayList<>();
    }

    private static HttpResult run(
            Map<String, Object> spec,
            CloseableHttpClient client,
            Runnable afterExchange,
            URI base,
            String expectPrefix,
            long deadlineNanos,
            Set<String> sensitive,
            Duration bodyIdle,
            AuthReach reach
    ) {
        Duration idle = bodyIdle == null || bodyIdle.isZero() || bodyIdle.isNegative()
                ? OutboundHttp.DEFAULT_BODY_IDLE
                : bodyIdle;
        if (present(spec, "path") == present(spec, "url")) {
            throw problem(ErrorCode.RULE_MISMATCH, "path 与 url 必须二选一");
        }
        if ((spec.get("json") != null && spec.get("form") != null)
                || (spec.get("body") != null && (spec.get("json") != null || spec.get("form") != null))) {
            throw problem(ErrorCode.RULE_MISMATCH, RequestBodies.EXCLUSIVE);
        }
        if (spec.get("multipart") != null
                && (spec.get("json") != null || spec.get("form") != null || spec.get("body") != null)) {
            throw problem(ErrorCode.RULE_MISMATCH, FileTransfers.EXCLUSIVE);
        }
        String method = Values.upper(spec.get("method"), "GET");
        if (!method.matches("[A-Z]+")) {
            throw problem(ErrorCode.RULE_MISMATCH, "方法无效");
        }
        Charset charset = charset(spec.get("encoding"));
        Charset responseCharset = explicitEncoding(spec.get("encoding"), charset);
        boolean follow = Values.bool(spec.get("follow_redirects"), false);
        boolean formQuery = formQuery(spec);
        Long stepLimit = timeoutLimit(spec.get("timeout_ms"));
        ArrayList<TraceWarning> warnings = new ArrayList<>();
        URI target = resolve(base, spec, charset, reach);
        Map<String, Object> query = optionalMap(spec.get("query"), "query");
        if (query != null && !query.isEmpty()) {
            target = withQuery(target, appendQuery(target.getRawQuery(), query, charset, formQuery, "query", warnings));
        }
        byte[] body = null;
        HttpEntity entity = null;
        ContentType generated = null;
        String multipartType = null;
        if (spec.get("multipart") != null) {
            FileSteps.Multipart multipart = FileSteps.multipart(spec.get("multipart"), charset);
            entity = multipart.entity();
            multipartType = multipart.contentType();
            FILE_MODE.get().wireBody = multipart.wire();
        } else if (spec.get("form") != null) {
            Map<String, Object> form = optionalMap(spec.get("form"), "form");
            String encoded = appendQuery(null, form, charset, true, "form", warnings);
            body = encoded.getBytes(charset);
            generated = ContentType.create("application/x-www-form-urlencoded", charset);
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
            generated = StandardCharsets.UTF_8.equals(charset)
                    ? ContentType.APPLICATION_JSON
                    : ContentType.create("application/json", charset);
        } else if (spec.get("body") != null) {
            if (!(spec.get("body") instanceof String text)) {
                throw problem(ErrorCode.RULE_MISMATCH, RequestBodies.NOT_TEXT);
            }
            try {
                body = UrlComponents.encode(text, charset);
            } catch (UrlComponents.Unencodable ex) {
                throw problem(ErrorCode.RULE_MISMATCH, "body 含有编码无法表示的字符");
            }
        }
        Map<String, Object> headers = optionalMap(spec.get("headers"), "headers");
        String declared = declaredContentType(headers);
        String contentType = null;
        if (body != null || entity != null) {
            if (multipartType != null) {
                // 分隔符由 Crab 生成，headers 里声明的 Content-Type 不覆盖它。
                contentType = multipartType;
            } else if (declared != null) {
                contentType = declared;
            } else if (generated != null) {
                contentType = generated.toString();
            } else {
                contentType = "text/plain; charset=" + charset.name();
            }
        }
        Set<String> secrets = sensitive == null ? Set.of() : sensitive;
        int hops = 0;
        boolean stripSecrets = false;
        while (true) {
            Exchange exchange = once(
                    client,
                    afterExchange,
                    method,
                    target,
                    body,
                    entity,
                    contentType,
                    headers,
                    charset,
                    responseCharset,
                    stepLimit,
                    deadlineNanos,
                    idle,
                    stripSecrets ? secrets : null);
            warnings.addAll(exchange.warnings);
            // 能力步骤和探活被送去登录页（命中 expect_not_redirect）：会话已经失效，不再跟随过去，
            // 避免把登录页写入临时 spool 或被登录页的 5xx 掩盖会话失效。
            if (expectPrefix != null && isRedirect(exchange.status)
                    && pathHasPrefix(exchange.redirect, target, expectPrefix)) {
                return exchange.toResult(true, warnings);
            }
            // 能力步骤和探活被送去认证地址：会话已经失效。写没写 follow_redirects 都一样，
            // 也不跟过去（同主机 http 升 https 的那种也不跟）：能力步骤不访问认证地址，
            // 认证地址那边的 5xx 也不该盖住「会话失效」。
            if (!reach.login() && isRedirect(exchange.status) && leadsToAuth(exchange.redirect, target, reach)) {
                return exchange.toResult(true, warnings);
            }
            if (!follow || !isRedirect(exchange.status) || exchange.redirect == null) {
                if (exchange.status >= 500) {
                    throw upstreamFailure(exchange, warnings);
                }
                return exchange.toResult(false, warnings);
            }
            URI next;
            try {
                next = target.resolve(exchange.redirect);
            } catch (IllegalArgumentException ex) {
                warnings.add(new TraceWarning(TraceWarning.LOCATION_UNRESOLVED, LOCATION_WARNING));
                return exchange.toResult(false, warnings);
            }
            boolean same = sameOrigin(target, next);
            boolean upgrade = httpUpgrade(target, next);
            // 登录步骤在 base_url 和声明过的认证地址之间跳转。
            boolean authHop = !same && !upgrade && reach.login() && reach.allows(base, next);
            if (!same && !upgrade && !authHop) {
                warnings.add(new TraceWarning(
                        TraceWarning.CROSS_ORIGIN,
                        "跳转到不同源 " + originText(next) + "，未跟随"));
                return exchange.toResult(false, warnings);
            }
            if (authHop && (exchange.status == 307 || exchange.status == 308)) {
                // 307、308 要把原来的正文原样重发，正文里可能有密码，不发到另一个主机。
                warnings.add(new TraceWarning(
                        TraceWarning.CROSS_ORIGIN,
                        exchange.status + " 跳转到不同源 " + originText(next) + " 要重发请求正文，未跟随"));
                return exchange.toResult(false, warnings);
            }
            if (hops >= MAX_REDIRECTS) {
                throw new RuleProblem(
                        ErrorCode.UPSTREAM_ERROR,
                        TOO_MANY_REDIRECTS,
                        method,
                        exchange.path,
                        exchange.status,
                        exchange.wire).withExchange(exchange.headers, exchange.body, warnings);
            }
            hops++;
            boolean toGet = exchange.status == 301 || exchange.status == 302 || exchange.status == 303;
            if (toGet) {
                method = "GET";
                body = null;
                entity = null;
                contentType = null;
                FileMode mode = FILE_MODE.get();
                if (mode != null) {
                    mode.wireBody = null;
                }
            }
            // 去认证地址或从它回来的那一跳：规则写的认证头和带凭证的头去掉，Cookie 由罐按域名决定。
            headers = headersForHop(headers, toGet, upgrade || authHop, secrets);
            stripSecrets = upgrade;
            target = next;
        }
    }

    static boolean sameOrigin(URI base, URI other) {
        base = HostNames.withoutTrailingDot(base);
        other = HostNames.withoutTrailingDot(other);
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

    /**
     * 写了步骤 encoding 时用它，盖过 Content-Type 和 meta。没写则 Content-Type、meta、UTF-8。
     * 解码后去掉全部前导 U+FEFF，避免 BOM 挡住 JSON 提取。
     */
    static String decode(byte[] body, String contentType, Charset stepEncoding) {
        Charset charset = stepEncoding;
        if (charset == null) {
            charset = charsetFromContentType(contentType);
        }
        if (charset == null) {
            charset = charsetFromMeta(body);
        }
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        String text = new String(body == null ? new byte[0] : body, charset);
        int start = 0;
        while (start < text.length() && text.charAt(start) == '\uFEFF') {
            start++;
        }
        return start == 0 ? text : text.substring(start);
    }

    /** 同一主机、http 默认端口到 https 默认端口。省略的端口按默认端口算。 */
    static boolean httpUpgrade(URI from, URI to) {
        if (from == null || to == null || from.getHost() == null || to.getHost() == null) {
            return false;
        }
        if (!"http".equalsIgnoreCase(from.getScheme()) || !"https".equalsIgnoreCase(to.getScheme())) {
            return false;
        }
        return from.getHost().equalsIgnoreCase(to.getHost()) && port(from) == 80 && port(to) == 443;
    }

    /** scheme://host[:port]。默认端口省略。IPv6 加方括号。不含路径和查询。 */
    static String originText(URI uri) {
        if (uri == null) {
            return "";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null) {
            return scheme;
        }
        String shown = host;
        if (shown.indexOf(':') >= 0 && !(shown.startsWith("[") && shown.endsWith("]"))) {
            shown = "[" + shown + "]";
        }
        int explicit = uri.getPort();
        boolean omit = explicit < 0
                || ("http".equals(scheme) && explicit == 80)
                || ("https".equals(scheme) && explicit == 443);
        if (scheme.isEmpty()) {
            return omit ? shown : shown + ":" + explicit;
        }
        return omit ? scheme + "://" + shown : scheme + "://" + shown + ":" + explicit;
    }

    /**
     * Apache 把响应头按 ISO-8859-1 读。裸 UTF-8 的 Location 先按字节收回来，再把空格等非法字符百分号编码。
     * 已经写好的 {@code %HH} 和 URI 结构字符不动。
     */
    static String normalizeLocation(String location) {
        if (location == null) {
            return null;
        }
        String recovered = recoverUtf8(location);
        return encodeIllegal(recovered);
    }

    static String presentUpstream(String text) {
        if (text == null || !text.startsWith("上游状态码 ")) {
            return text;
        }
        int newline = text.indexOf('\n');
        if (newline < 0) {
            return text;
        }
        return text.substring(0, newline + 1) + clipUtf8(text.substring(newline + 1), UPSTREAM_BODY_LIMIT);
    }

    /**
     * 打码之后的对外说明。不超过 8 KiB 的原文保留；更长的截到 4 KiB UTF-8。
     * 上游 5xx 的正文已经先截过，短前缀加 4 KiB 不会再进这里。
     */
    static String clipPublic(String text) {
        if (text == null) {
            return null;
        }
        if (text.getBytes(StandardCharsets.UTF_8).length <= 8192) {
            return text;
        }
        return clipUtf8(text, UPSTREAM_BODY_LIMIT);
    }

    /** 为按需取值的变量视图（如 login）提供全部已知敏感值，避免空 entrySet 漏掉跨源头剥离。 */
    interface SecretSource {
        Collection<String> knownValues();

        default Map<String, String> snapshotForScript() {
            return Map.of();
        }
    }

    static Set<String> sensitiveValues(Map<String, Object> vars, CookieStore cookies) {
        return sensitiveValues(vars, cookies, List.of());
    }

    static Set<String> sensitiveValues(
            Map<String, Object> vars,
            CookieStore cookies,
            Collection<String> extraSecrets
    ) {
        LinkedHashSet<String> raw = new LinkedHashSet<>();
        if (vars != null) {
            collectSecrets(raw, vars.get("credential"), 0);
            Object login = vars.get("login");
            if (login instanceof SecretSource source) {
                collectSecrets(raw, source.knownValues(), 0);
            } else {
                collectSecrets(raw, login, 0);
            }
            // 业务包里 auth 是授权器导出值的视图；旧包里叫 auth 的普通变量不算秘密。
            if (vars.get(ConchRegistry.AUTH_VAR) instanceof SecretSource source) {
                collectSecrets(raw, source.knownValues(), 0);
            }
        }
        raw.addAll(CallSecrets.current());
        DevTraceDraft draft = DevTraceScope.current();
        if (draft != null) {
            raw.addAll(draft.derivedSecrets());
            raw.addAll(draft.traceSecrets());
        }
        if (cookies != null) {
            for (Cookie cookie : cookies.getCookies()) {
                collectSecrets(raw, cookie.getValue(), 0);
            }
        }
        if (extraSecrets != null) {
            collectSecrets(raw, extraSecrets, 0);
        }
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String secret : raw) {
            if (secret == null || secret.length() < 4) {
                continue;
            }
            expanded.add(secret);
            String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
            if (encoded.length() >= 4) {
                expanded.add(encoded);
            }
            String spaces = encoded.replace("+", "%20");
            if (spaces.length() >= 4) {
                expanded.add(spaces);
            }
        }
        return Set.copyOf(expanded);
    }

    static void attachWarnings(Map<String, Object> piece, List<TraceWarning> warnings) {
        if (piece == null || warnings == null || warnings.isEmpty()) {
            return;
        }
        ArrayList<Object> rows = new ArrayList<>();
        for (TraceWarning warning : warnings) {
            if (warning == null) {
                continue;
            }
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            row.put("code", warning.code());
            row.put("message", warning.message());
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            piece.put("warnings", rows);
        }
    }

    static HttpRequestInterceptor crossOriginStripper() {
        return STRIPPER;
    }

    private static Exchange once(
            CloseableHttpClient client,
            Runnable afterExchange,
            String method,
            URI target,
            byte[] body,
            HttpEntity entity,
            String contentType,
            Map<String, Object> headers,
            Charset requestCharset,
            Charset responseCharset,
            Long stepLimit,
            long deadlineNanos,
            Duration bodyIdle,
            Set<String> stripSecrets
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
        HttpEntity requestEntity = entity != null
                ? entity
                : (body != null ? new ByteArrayEntity(body, (ContentType) null) : null);
        if (requestEntity != null) {
            // 实体不带 ContentType，避免客户端按字符集再序列化或补 charset。
            request.setEntity(requestEntity);
            if (contentType != null && !contentType.isEmpty()) {
                if (unsendableHeader(contentType)) {
                    throw new RuleProblem(
                            ErrorCode.RULE_MISMATCH,
                            "请求头 Content-Type 含有无法发送的字符，请先用 urlencode 处理",
                            method,
                            pathOf(target),
                            null);
                }
                request.setHeader("Content-Type", contentType);
            }
        }
        applyHeaders(request, headers, target, method, requestEntity != null);
        String path = pathOf(target);
        // responseTimeout 只限制相邻读取的空闲。持续慢速输出不会触发它，到能力 deadline 时取消请求，
        // 才能打断已经阻塞在 InputStream.read 上的线程。没写 timeout_ms 时，这段空闲上限就是剩余 deadline。
        Cancellable cancellable = request;
        AtomicBoolean deadlineFired = new AtomicBoolean();
        ScheduledFuture<?> cancellation = scheduleCancellation(cancellable, deadlineNanos, deadlineFired);
        final byte[] requestBody = body;
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        AtomicReference<Exchange> kept = new AtomicReference<>();
        HttpClientContext context = HttpClientContext.create();
        ArrayList<Object> notices = new ArrayList<>();
        context.setAttribute(LegacyCookies.NOTICES, notices);
        if (stripSecrets != null) {
            context.setAttribute(STRIP_SECRETS, stripSecrets);
        }
        try {
            Exchange exchange = client.execute(request, context, response -> {
                captured.set(snapshotWire(executed(context, request), requestBody, requestCharset));
                int status = response.getCode();
                LinkedHashMap<String, String> headerMap = new LinkedHashMap<>();
                for (Header header : response.getHeaders()) {
                    headerMap.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
                }
                BodyBytes read = readBody(
                        response.getEntity(),
                        method,
                        path,
                        status,
                        deadlineNanos,
                        deadlineFired,
                        cancellable,
                        bodyIdle,
                        persistent(response));
                String location = normalizeLocation(headerMap.get("location"));
                if (location != null) {
                    headerMap.put("location", location);
                }
                String redirect = isRedirect(status) && location != null && !location.isBlank() ? location : null;
                FileMode fileMode = FILE_MODE.get();
                boolean file = fileMode != null && fileMode.bodyLimit > 0 && status >= 200 && status < 300;
                // 文件正文不解码：response.body 留空，追踪里也就没有文件内容。
                String decoded = file ? "" : decode(read.bytes(), headerMap.get("content-type"), responseCharset);
                ArrayList<TraceWarning> stepWarnings = new ArrayList<>(warningsOf(notices));
                if (read.endedByIdle()) {
                    logIdleCutoff(method, target);
                    stepWarnings.add(new TraceWarning(
                            TraceWarning.BODY_ENDED_BY_IDLE, OutboundHttp.BODY_ENDED_BY_IDLE_MESSAGE));
                }
                Exchange built = new Exchange(
                        status,
                        target.toString(),
                        method,
                        path,
                        headerMap,
                        decoded,
                        redirect,
                        read.bytes(),
                        stepWarnings,
                        captured.get(),
                        read.spool(),
                        file);
                if (read.endedByIdle()) {
                    kept.set(built);
                }
                return built;
            });
            if (pastDeadline(deadlineNanos, deadlineFired)) {
                cancellable.cancel();
                throw new DeadlineExceeded(-1, "request", List.of());
            }
            return exchange;
        } catch (DeadlineExceeded ex) {
            throw ex;
        } catch (RuleProblem ex) {
            throw ex.withWire(captured.get());
        } catch (IOException ex) {
            Exchange done = kept.get();
            if (done != null && !pastDeadline(deadlineNanos, deadlineFired)) {
                return done;
            }
            Map<String, Object> wire = captured.get() != null
                    ? captured.get()
                    : snapshotWire(executed(context, request), body, requestCharset);
            dev.hermit.crab.files.FileStageException stageError = findFileStageCause(ex);
            if (stageError != null) {
                throw new RuleProblem(ErrorCode.INVALID_INPUT, stageError.getMessage(), method, path, null, wire);
            }
            // 到期取消线程在调用 cancel() 前已置位 deadlineFired：即使读超时与取消线程在边界上竞态，
            // HttpRequestRetryExec 把 SocketTimeoutException 改写成 RequestFailedException("Request aborted")，
            // 这里仍按能力超时返回，不误判成上游传输错误。
            if (pastDeadline(deadlineNanos, deadlineFired)) {
                cancellable.cancel();
                throw new DeadlineExceeded(-1, "request", List.of());
            }
            if (isTimeout(ex) && capabilityBound) {
                throw new DeadlineExceeded(-1, "request", List.of(), isConnectTimeout(ex) ? "connect" : "timeout");
            }
            if (isTimeout(ex)) {
                throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, STEP_TIMEOUT, method, path, null, wire)
                        .withRetryReason(isConnectTimeout(ex) ? "connect" : "timeout");
            }
            TransportFailure.View view = TransportFailure.observe(ex, target.getHost(), port(target));
            throw new RuleProblem(
                    ErrorCode.UPSTREAM_ERROR, view.message(), method, path, null, wire, view.fields());
        } finally {
            cancellation.cancel(false);
            afterExchange.run();
        }
    }

    private static dev.hermit.crab.files.FileStageException findFileStageCause(Throwable error) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (cursor instanceof dev.hermit.crab.files.FileStageException stageError) {
                return stageError;
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private static ScheduledFuture<?> scheduleCancellation(
            Cancellable cancellable, long deadlineNanos, AtomicBoolean deadlineFired) {
        long delayNanos = deadlineNanos - System.nanoTime();
        if (delayNanos < 0) {
            delayNanos = 0;
        }
        return DEADLINES.schedule(() -> {
            deadlineFired.set(true);
            cancellable.cancel();
        }, delayNanos, TimeUnit.NANOSECONDS);
    }

    private static void applyHeaders(
            HttpUriRequestBase request,
            Map<String, Object> headers,
            URI target,
            String method,
            boolean skipContentType
    ) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name.equalsIgnoreCase("Cookie")) {
                continue;
            }
            if (skipContentType && name.equalsIgnoreCase("Content-Type")) {
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
                    throw new RuleProblem(ErrorCode.RULE_MISMATCH, HOST_MISMATCH, method, pathOf(target), null);
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

    /**
     * 只有看起来会保持连接的响应才按空闲截断：HTTP/1.1 且没有 {@code Connection: close}，
     * 或 HTTP/1.0 且有 {@code Connection: keep-alive}。{@code Connection: close} 和没有
     * keep-alive 的 HTTP/1.0 读到对端关闭，仍受步骤或能力 deadline 约束。
     */
    private static boolean persistent(ClassicHttpResponse response) {
        ProtocolVersion version = response.getVersion();
        boolean close = headerToken(response, "close");
        if (version != null && version.getMajor() == 1 && version.getMinor() <= 0) {
            return headerToken(response, "keep-alive") && !close;
        }
        if (version != null && version.getMajor() == 1 && version.getMinor() >= 1) {
            return !close;
        }
        return false;
    }

    private static boolean headerToken(ClassicHttpResponse response, String token) {
        Header[] headers = response.getHeaders("Connection");
        if (headers == null) {
            return false;
        }
        for (Header header : headers) {
            String value = header.getValue();
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                if (part.trim().equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 一行警告：方法、scheme、主机和端口，以及能拿到的规则包和能力。不含路径、查询、正文和请求头。 */
    private static void logIdleCutoff(String method, URI target) {
        StringBuilder line = new StringBuilder("idle body cutoff");
        line.append(" method=").append(logToken(method));
        line.append(" target=").append(originOf(target));
        CallSite site = CallSite.current();
        if (site != null && site.conch() != null) {
            line.append(" conch=").append(logToken(site.conch()));
        }
        if (site != null && site.capability() != null) {
            line.append(" capability=").append(logToken(site.capability()));
        }
        LOG.warning(line.toString());
    }

    private static String originOf(URI target) {
        if (target == null || target.getScheme() == null || target.getHost() == null) {
            return "";
        }
        String host = target.getHost();
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        int port = target.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(target.getScheme()) ? 443 : 80;
        }
        return target.getScheme() + "://" + host + ":" + port;
    }

    private static String logToken(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static BodyBytes readBody(
            HttpEntity entity,
            String method,
            String path,
            int status,
            long deadlineNanos,
            AtomicBoolean deadlineFired,
            Cancellable request,
            Duration bodyIdle,
            boolean persistent
    ) throws IOException {
        if (entity == null) {
            ensureWithinDeadline(deadlineNanos, deadlineFired, request);
            return new BodyBytes(new byte[0], false, null);
        }
        FileMode mode = FILE_MODE.get();
        boolean asFile = mode != null && mode.bodyLimit > 0 && status >= 200 && status < 300;
        long limit = asFile ? mode.bodyLimit : MAX_BODY_BYTES;
        String tooLarge = asFile ? dev.hermit.crab.files.FileStage.TOO_LARGE : "响应体超过 5MB";
        long declared = entity.getContentLength();
        if (declared > limit) {
            throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, tooLarge, method, path, status);
        }
        // Content-Length、分块编码，以及会由对端关闭来结束的正文，仍受步骤或能力超时约束。
        // 只有保持连接又没有长度时，才按相邻读取的空闲截断。
        boolean indefinite = persistent && declared < 0 && !entity.isChunked();
        IdleWatch watch = indefinite ? new IdleWatch(bodyIdle, request) : null;
        // 文件边读边写进暂存区，不在内存里攒整份；大小和 SHA-256 在写的时候算。
        dev.hermit.crab.files.FileStage.Spool spool = null;
        if (asFile) {
            try {
                spool = FileSteps.scope().stage().spool();
            } catch (dev.hermit.crab.files.FileStageException ex) {
                throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, ex.getMessage(), method, path, status);
            }
            mode.spools.add(spool);
        }
        InputStream in = entity.getContent();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            while (true) {
                ensureWithinDeadline(deadlineNanos, deadlineFired, request);
                if (watch != null) {
                    watch.arm();
                }
                int read;
                try {
                    read = in.read(buffer);
                } catch (IOException ex) {
                    if (pastDeadline(deadlineNanos, deadlineFired)) {
                        request.cancel();
                        throw new DeadlineExceeded(-1, "request", List.of());
                    }
                    if (watch != null && watch.endedByIdle()) {
                        if (asFile) {
                            // 文本可以带着警告用已收到的部分；半个文件不能当成文件交出去。
                            throw new RuleProblem(
                                    ErrorCode.UPSTREAM_ERROR,
                                    "文件没有收完：上游没有给出长度，传到一半停住了",
                                    method, path, status);
                        }
                        return new BodyBytes(out.toByteArray(), true, null);
                    }
                    throw ex;
                }
                if (watch != null) {
                    watch.disarm();
                }
                if (read < 0) {
                    return new BodyBytes(asFile ? new byte[0] : out.toByteArray(), false, spool);
                }
                if (pastDeadline(deadlineNanos, deadlineFired)) {
                    request.cancel();
                    throw new DeadlineExceeded(-1, "request", List.of());
                }
                total += read;
                if (total > limit) {
                    throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, tooLarge, method, path, status);
                }
                if (spool == null) {
                    out.write(buffer, 0, read);
                    continue;
                }
                try {
                    spool.write(buffer, 0, read);
                } catch (dev.hermit.crab.files.FileStageException ex) {
                    throw new RuleProblem(ErrorCode.UPSTREAM_ERROR, ex.getMessage(), method, path, status);
                }
            }
        } finally {
            if (watch != null) {
                watch.close();
            }
            try {
                in.close();
            } catch (IOException ignored) {
                // 空闲截断之后关闭流不能把已经读到的正文变成连接失败。
            }
        }
    }

    /**
     * 空闲是两次读取之间的沉默，不是整段正文的总时长。到点取消请求，让阻塞的 read 返回。
     * 对端在窗口内自己关掉连接时 read 得到 -1，不当作这条警告。
     */
    private static final class IdleWatch {
        private final long idleMillis;
        private final Cancellable request;
        private final AtomicBoolean ended = new AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
        private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();

        private IdleWatch(Duration idle, Cancellable request) {
            Duration resolved = idle == null || idle.isZero() || idle.isNegative()
                    ? OutboundHttp.DEFAULT_BODY_IDLE
                    : idle;
            this.idleMillis = Math.max(1, resolved.toMillis());
            this.request = request;
        }

        private void arm() {
            disarm();
            int token = generation.incrementAndGet();
            ScheduledFuture<?> future = DEADLINES.schedule(() -> {
                if (generation.get() == token && ended.compareAndSet(false, true)) {
                    request.cancel();
                }
            }, idleMillis, TimeUnit.MILLISECONDS);
            if (generation.get() != token) {
                future.cancel(false);
                return;
            }
            pending.set(future);
        }

        private void disarm() {
            generation.incrementAndGet();
            ScheduledFuture<?> future = pending.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
        }

        private boolean endedByIdle() {
            return ended.get();
        }

        private void close() {
            disarm();
        }
    }

    private record BodyBytes(byte[] bytes, boolean endedByIdle, dev.hermit.crab.files.FileStage.Spool spool) {
    }

    private static boolean pastDeadline(long deadlineNanos, AtomicBoolean deadlineFired) {
        return (deadlineFired != null && deadlineFired.get()) || System.nanoTime() - deadlineNanos >= 0;
    }

    private static void ensureWithinDeadline(long deadlineNanos, AtomicBoolean deadlineFired, Cancellable request) {
        if (pastDeadline(deadlineNanos, deadlineFired)) {
            request.cancel();
            throw new DeadlineExceeded(-1, "request", List.of());
        }
    }

    private static URI resolve(URI base, Map<String, Object> spec, Charset charset, AuthReach reach) {
        try {
            String reference = present(spec, "url")
                    ? Values.text(spec.get("url"))
                    : Values.text(spec.get("path"));
            URI resolved = HostNames.withoutTrailingDot(
                    UrlComponents.resolve(HostNames.withoutTrailingDot(base), reference, charset));
            if (!reach.allows(base, resolved)) {
                throw new RuleProblem(
                        ErrorCode.RULE_MISMATCH,
                        reach.declares(resolved) ? AUTH_ORIGIN_TARGET : CROSS_ORIGIN_TARGET,
                        null, null, null);
            }
            return resolved;
        } catch (UrlComponents.Unencodable ex) {
            throw problem(ErrorCode.RULE_MISMATCH, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw problem(ErrorCode.RULE_MISMATCH, "地址无效");
        }
    }

    /**
     * {@code form} 为 true 时用 HTML 表单编码。query 默认走 URL 编码；form 正文始终为 true。
     * 对象和数组先写成 JSON 再编码，并记一条只含字段名的警告。数字和布尔仍是文本，不警告。
     */
    private static String appendQuery(
            String existing,
            Map<String, Object> query,
            Charset charset,
            boolean form,
            String section,
            List<TraceWarning> warnings
    ) {
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
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                warnings.add(new TraceWarning(
                        TraceWarning.NOT_TEXT, notTextWarning(section, entry.getKey())));
            }
            builder.append(encodeComponent(Values.text(value), charset, form));
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

    /** 第一个非空白 Content-Type。原样返回，不解析、不补 charset。 */
    private static String declaredContentType(Map<String, Object> headers) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Content-Type")) {
                String value = Values.text(entry.getValue());
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
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
        // 全文留给追踪里的脱敏。64 KiB 在替换之后再截，否则正好压在截断点上的秘密会留下前半段。
        FileMode mode = FILE_MODE.get();
        if (mode != null && mode.wireBody != null) {
            wire.put("body", mode.wireBody);
            return wire;
        }
        wire.put("body", body == null ? null : new String(body, charset));
        return wire;
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
        return DevTraceDraft.droppedTraceHeader(name);
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

    /** 请求的 query、form、JSON 仍默认 UTF-8。空白不算写成了 encoding。 */
    private static Charset explicitEncoding(Object encoding, Charset parsed) {
        if (encoding == null) {
            return null;
        }
        String name = Values.text(encoding);
        if (name == null || name.isBlank()) {
            return null;
        }
        return parsed;
    }

    private static RuleProblem upstreamFailure(Exchange exchange, List<TraceWarning> warnings) {
        return new RuleProblem(
                ErrorCode.UPSTREAM_ERROR,
                withUpstreamBody(exchange.status, exchange.body),
                exchange.method,
                exchange.path,
                exchange.status,
                exchange.wire).withExchange(exchange.headers, exchange.body, warnings);
    }

    private static String withUpstreamBody(int status, String body) {
        if (body == null || body.isEmpty()) {
            return upstreamStatus(status);
        }
        return upstreamStatus(status) + "\n" + body;
    }

    private static Map<String, Object> headersForHop(
            Map<String, Object> headers,
            boolean toGet,
            boolean crossOrigin,
            Set<String> sensitive
    ) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (toGet && (name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Content-Length"))) {
                continue;
            }
            if (crossOrigin && dropOnCrossOrigin(name, Values.text(entry.getValue()), sensitive)) {
                continue;
            }
            copy.put(name, entry.getValue());
        }
        return copy;
    }

    private static boolean dropOnCrossOrigin(String name, String value, Set<?> sensitive) {
        if (name.equalsIgnoreCase("Authorization")
                || name.equalsIgnoreCase("Proxy-Authorization")
                || name.equalsIgnoreCase("Cookie")) {
            return true;
        }
        if (value == null || value.isEmpty() || sensitive == null) {
            return false;
        }
        for (Object secret : sensitive) {
            if (secret instanceof String text && text.length() >= 4 && value.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static String recoverUtf8(String raw) {
        byte[] bytes = new byte[raw.length()];
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c > 0xFF) {
                return raw;
            }
            bytes[i] = (byte) c;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String utf8 = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            return utf8.equals(raw) ? raw : utf8;
        } catch (CharacterCodingException ex) {
            return raw;
        }
    }

    private static String encodeIllegal(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                out.append('%').append(value.charAt(i + 1)).append(value.charAt(i + 2));
                i += 3;
                continue;
            }
            if (isUriChar(c)) {
                out.append(c);
                i++;
                continue;
            }
            int cp = value.codePointAt(i);
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                int unsigned = b & 0xFF;
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                out.append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** RFC 3986 unreserved、gen-delims 和 sub-delims。空格和控制字符不在这里。 */
    private static boolean isUriChar(char c) {
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
            return true;
        }
        return switch (c) {
            case '-', '.', '_', '~', ':', '/', '?', '#', '[', ']', '@',
                    '!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=' -> true;
            default -> false;
        };
    }

    static String clipUtf8(String text, int maxBytes) {
        if (text == null || text.isEmpty() || maxBytes <= 0) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static void collectSecrets(Set<String> into, Object value, int depth) {
        if (value == null || depth > 8) {
            return;
        }
        if (value instanceof String text) {
            if (text.length() >= 4) {
                into.add(text);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                collectSecrets(into, item, depth + 1);
            }
            return;
        }
        if (value instanceof Collection<?> items) {
            for (Object item : items) {
                collectSecrets(into, item, depth + 1);
            }
        }
    }

    private static List<TraceWarning> warningsOf(List<?> notices) {
        ArrayList<TraceWarning> warnings = new ArrayList<>();
        for (Object item : notices) {
            if (item instanceof TraceWarning warning) {
                warnings.add(warning);
            }
        }
        return warnings;
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
    /** 能力 GET 停在无法解析的 Location 上时的调用错误。不带 Location。 */
    static final String UNRESOLVED_LOCATION = "重定向地址无法解析，未跟随";
    static final String LOCATION_WARNING = "Location 无法解析，未跟随";
    static final String CROSS_ORIGIN_TARGET = "目标地址与 base_url 不同源";
    static final String AUTH_ORIGIN_TARGET = "认证地址只能在登录步骤里访问，能力步骤和 alive_check 只能访问 base_url";
    static final String HOST_MISMATCH = "Host 与目标地址不一致";

    static String upstreamStatus(int status) {
        return "上游状态码 " + status;
    }

    /** 开发追踪里只写 query 或 form 的字段名，不写值。 */
    static String notTextWarning(String section, String field) {
        return section + "." + field + " 不是文本，已按 JSON 写入";
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

    private static boolean isConnectTimeout(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if (current instanceof ConnectTimeoutException) {
                return true;
            }
            if (current instanceof SocketTimeoutException) {
                String message = current.getMessage();
                if (message != null && message.toLowerCase(Locale.ROOT).contains("connect")) {
                    return true;
                }
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

    private static boolean leadsToAuth(String location, URI current, AuthReach reach) {
        if (location == null || reach.origins().isEmpty()) {
            return false;
        }
        try {
            URI next = current.resolve(location);
            return !sameOrigin(current, next) && reach.declares(next);
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
            List<TraceWarning> warnings,
            Map<String, Object> wire,
            dev.hermit.crab.files.FileStage.Spool spool,
            boolean downloaded
    ) {
        private HttpResult toResult(boolean sessionLost, List<TraceWarning> all) {
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
                    List.copyOf(all),
                    wire,
                    spool,
                    downloaded);
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
        List<TraceWarning> warnings,
        Map<String, Object> wire,
        /** download 收到的文件，还没有提交进暂存区；不是 download 或没有正文时为 null。 */
        dev.hermit.crab.files.FileStage.Spool spool,
        /** 本次 2xx 响应按 download 处理（未解码为文本；即使 204 无实体时 spool 为 null 也为 true）。 */
        boolean downloaded
) {
    HttpResult(
            int status,
            String url,
            String method,
            String path,
            Map<String, String> headers,
            String body,
            String redirect,
            boolean sessionLost,
            byte[] bodyBytes,
            List<TraceWarning> warnings,
            Map<String, Object> wire,
            dev.hermit.crab.files.FileStage.Spool spool
    ) {
        this(status, url, method, path, headers, body, redirect, sessionLost, bodyBytes, warnings, wire, spool,
                spool != null);
    }

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
