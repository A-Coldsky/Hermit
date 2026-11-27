package dev.hermit.crab.recorder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.secure.Redactor;

/**
 * Classifies static noise and performs structural + secret-value redaction on captured HTTP exchanges.
 * Preserves Cookie and Set-Cookie names (replacing only values) so Conch authoring knows {@code auth.cookies}
 * without ever exposing live session tokens, passwords, or Authorization credentials.
 */
public final class CaptureRedactor {
    public static final String MASK = "***";
    public static final int DEFAULT_PREVIEW_BODY_CHARS = 32_768;
    public static final int DEFAULT_SNAPSHOT_BODY_CHARS = 65_536;

    private static final Set<String> STATIC_RESOURCE_TYPES = Set.of(
            "image", "stylesheet", "font", "media", "manifest", "script",
            "websocket", "eventsource", "ping", "cspviolationreport", "preflight");

    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".js", ".mjs", ".cjs", ".css", ".map",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp", ".avif",
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
            ".mp4", ".mp3", ".webm", ".ogg", ".wav", ".wasm");

    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "password", "passwd", "pwd", "passcode", "secret", "token", "otp",
            "apikey", "api_key", "api-key", "cookie", "authorization", "set-cookie",
            "session", "jwt", "ticket", "csrf", "xsrf", "credential", "private_key",
            "privatekey", "access_token", "refresh_token", "id_token", "bearer", "authcode",
            "verifycode", "smscode", "captcha");

    private static final Set<String> SENSITIVE_EXACT_KEYS = Set.of(
            "code", "lt", "execution", "samlresponse", "samlrequest", "st", "tgc");

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-hermit-key", "x-hermit-api-key", "x-auth-token",
            "x-access-token", "x-refresh-token", "x-csrf-token", "x-xsrf-token",
            "csrf-token", "xsrf-token", "x-session-id", "x-session-token",
            "mcp-session-id", "token");

    private static final Pattern PATH_SESSION_PARAM = Pattern.compile(
            "(?i)(;(?:jsessionid|phpsessid|aspsessionid|sid|sessionid)=)([^;/?#]+)");

    private static final Pattern XML_SENSITIVE_TAG = Pattern.compile(
            "(?is)<([a-zA-Z0-9_:-]*(?:password|passwd|pwd|secret|token|ticket|session|cookie|credential|otp)[a-zA-Z0-9_:-]*)([^>]*)>([^<]+)</\\1>");

    private static final Pattern HTML_SENSITIVE_INPUT = Pattern.compile(
            "(?is)<input\\b([^>]*?)>");

    private static final Pattern HTML_ATTR_NAME = Pattern.compile(
            "(?i)\\bname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");

    private static final Pattern HTML_ATTR_TYPE = Pattern.compile(
            "(?i)\\btype\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");

    private static final Pattern HTML_ATTR_VALUE = Pattern.compile(
            "(?i)(\\bvalue\\s*=\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+)");

    private CaptureRedactor() {
    }

    public static String extractOrigin(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "";
            }
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = ("http".equals(lowerScheme) && port == 80)
                    || ("https".equals(lowerScheme) && port == 443);
            if (port > 0 && !defaultPort) {
                return lowerScheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
            }
            return lowerScheme + "://" + host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    public static String extractPath(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String rawPath = uri.getRawPath();
            return (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        } catch (IllegalArgumentException ex) {
            return url;
        }
    }

    public static boolean isStaticAsset(String method, int status, String resourceType, String url, String mimeType) {
        if (method != null) {
            String m = method.toUpperCase(Locale.ROOT);
            if (!"GET".equals(m) && !"HEAD".equals(m) && !"OPTIONS".equals(m)) {
                return false;
            }
        }
        if (status >= 300 && status < 400) {
            return false;
        }
        String resType = resourceType == null ? "" : resourceType.trim().toLowerCase(Locale.ROOT);
        String mime = mimeType == null ? "" : baseMime(mimeType);
        if (!resType.isEmpty() && !"image".equals(resType) && STATIC_RESOURCE_TYPES.contains(resType)) {
            return true;
        }
        if (!mime.isEmpty()
                && (mime.startsWith("font/") || mime.startsWith("audio/") || mime.startsWith("video/")
                        || "text/css".equals(mime)
                        || "application/javascript".equals(mime)
                        || "text/javascript".equals(mime)
                        || "application/x-javascript".equals(mime)
                        || "application/wasm".equals(mime))) {
            return true;
        }
        String path = extractPath(url).toLowerCase(Locale.ROOT);
        int semi = path.indexOf(';');
        if (semi >= 0) {
            path = path.substring(0, semi);
        }
        boolean imageCandidate = "image".equals(resType) || mime.startsWith("image/");
        if (!imageCandidate) {
            for (String ext : STATIC_EXTENSIONS) {
                if (path.endsWith(ext)) {
                    if (isImageExtension(ext)) {
                        imageCandidate = true;
                        break;
                    }
                    return true;
                }
            }
        }
        if (imageCandidate) {
            return !isLikelyCaptchaImageUrl(url, path);
        }
        return false;
    }

    private static boolean isImageExtension(String ext) {
        return ".png".equals(ext) || ".jpg".equals(ext) || ".jpeg".equals(ext)
                || ".gif".equals(ext) || ".webp".equals(ext) || ".svg".equals(ext)
                || ".ico".equals(ext) || ".bmp".equals(ext) || ".avif".equals(ext);
    }

    private static boolean isLikelyCaptchaImageUrl(String url, String lowerPath) {
        String lowerUrl = url == null ? lowerPath : url.toLowerCase(Locale.ROOT);
        return lowerUrl.contains("captcha")
                || lowerUrl.contains("kaptcha")
                || lowerUrl.contains("valicode")
                || lowerUrl.contains("validcode")
                || lowerUrl.contains("verifycode")
                || lowerUrl.contains("verify_code")
                || lowerUrl.contains("verify-code")
                || lowerUrl.contains("vcode")
                || lowerUrl.contains("checkcode")
                || lowerUrl.contains("check_code")
                || lowerUrl.contains("authcode")
                || lowerUrl.contains("auth_code")
                || lowerUrl.contains("randcode")
                || lowerUrl.contains("yzm")
                || lowerPath.contains("/verify")
                || lowerPath.endsWith("verify");
    }

    public static boolean isBinaryContent(String mimeType, String contentDisposition) {
        if (contentDisposition != null && contentDisposition.toLowerCase(Locale.ROOT).contains("attachment")) {
            return true;
        }
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String base = baseMime(mimeType);
        if (base.startsWith("text/")) {
            return false;
        }
        if ("application/json".equals(base)
                || base.endsWith("+json")
                || "application/xml".equals(base)
                || base.endsWith("+xml")
                || "application/x-www-form-urlencoded".equals(base)
                || "application/javascript".equals(base)
                || "application/x-javascript".equals(base)
                || "application/xhtml+xml".equals(base)
                || "application/soap+xml".equals(base)) {
            return false;
        }
        return true;
    }

    /**
     * Immediately scrubs raw Cookie, Set-Cookie, Authorization, and sensitive headers right on capture/import
     * so live credentials never remain even in the transient active session buffer.
     */
    public static List<RecordedEntry.HeaderPair> scrubWireHeaders(List<RecordedEntry.HeaderPair> headers) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }
        ArrayList<RecordedEntry.HeaderPair> out = new ArrayList<>(headers.size());
        for (RecordedEntry.HeaderPair pair : headers) {
            out.add(redactHeader(pair, Redactor.of(Set.of())));
        }
        return List.copyOf(out);
    }

    public static List<String> extractCookieNames(Collection<RecordedEntry> entries) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (entries == null) {
            return List.of();
        }
        for (RecordedEntry entry : entries) {
            for (RecordedEntry.HeaderPair header : entry.responseHeaders()) {
                if ("set-cookie".equalsIgnoreCase(header.name())) {
                    String name = parseSetCookieName(header.value());
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
            for (RecordedEntry.HeaderPair header : entry.requestHeaders()) {
                if ("cookie".equalsIgnoreCase(header.name())) {
                    for (String name : parseCookieHeaderNames(header.value())) {
                        names.add(name);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    public static List<String> extractOrigins(Collection<RecordedEntry> entries) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        if (entries == null) {
            return List.of();
        }
        for (RecordedEntry entry : entries) {
            if (!entry.origin().isEmpty()) {
                origins.add(entry.origin());
            }
            if (entry.redirectTo() != null && !entry.redirectTo().isBlank()) {
                String redirectOrigin = extractOrigin(entry.redirectTo());
                if (!redirectOrigin.isEmpty()) {
                    origins.add(redirectOrigin);
                }
            }
        }
        return List.copyOf(origins);
    }

    public static RecordedEntry redactEntry(
            RecordedEntry raw,
            int newIndex,
            Collection<String> extraSecrets,
            int maxBodyChars
    ) {
        Set<String> secrets = cleanSecrets(extraSecrets);
        Redactor secretRedactor = Redactor.of(secrets);

        String redactedUrl = redactUrl(raw.url(), secretRedactor);
        String redactedPath = redactPath(raw.path(), secretRedactor);
        String redactedRedirect = raw.redirectTo() == null ? null : redactUrl(raw.redirectTo(), secretRedactor);

        ArrayList<RecordedEntry.HeaderPair> reqHeaders = new ArrayList<>(raw.requestHeaders().size());
        String reqContentType = "";
        for (RecordedEntry.HeaderPair header : raw.requestHeaders()) {
            if ("content-type".equalsIgnoreCase(header.name())) {
                reqContentType = header.value();
            }
            reqHeaders.add(redactHeader(header, secretRedactor));
        }

        ArrayList<RecordedEntry.HeaderPair> respHeaders = new ArrayList<>(raw.responseHeaders().size());
        for (RecordedEntry.HeaderPair header : raw.responseHeaders()) {
            respHeaders.add(redactHeader(header, secretRedactor));
        }

        TruncatedText reqBody = redactBody(raw.requestBody(), reqContentType, false, secretRedactor, maxBodyChars);
        TruncatedText respBody = raw.binary()
                ? new TruncatedText(null, false)
                : redactBody(raw.responseBody(), raw.mimeType(), true, secretRedactor, maxBodyChars);

        String redactedDisposition = raw.contentDisposition() == null
                ? null
                : secretRedactor.redact(raw.contentDisposition());
        String redactedError = raw.errorText() == null
                ? null
                : secretRedactor.redact(raw.errorText());

        return new RecordedEntry(
                raw.id(),
                newIndex,
                raw.startedAt(),
                raw.durationMs(),
                raw.method(),
                redactedUrl,
                raw.origin(),
                redactedPath,
                raw.resourceType(),
                raw.staticAsset(),
                raw.status(),
                raw.statusText(),
                raw.mimeType(),
                redactedRedirect,
                raw.binary(),
                redactedDisposition,
                raw.responseBytes(),
                redactedError,
                reqHeaders,
                reqBody.text(),
                raw.requestBodyTruncated() || reqBody.truncated(),
                respHeaders,
                respBody.text(),
                raw.responseBodyTruncated() || respBody.truncated());
    }

    public static String redactUrl(String url, Redactor secretRedactor) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String withoutFragment = url;
        String fragment = null;
        int hash = url.indexOf('#');
        if (hash >= 0) {
            withoutFragment = url.substring(0, hash);
            fragment = url.substring(hash + 1);
        }
        String basePart = withoutFragment;
        String query = null;
        int q = withoutFragment.indexOf('?');
        if (q >= 0) {
            basePart = withoutFragment.substring(0, q);
            query = withoutFragment.substring(q + 1);
        }
        StringBuilder out = new StringBuilder();
        out.append(redactPath(basePart, secretRedactor));
        if (query != null) {
            out.append('?').append(redactFormLike(query, secretRedactor));
        }
        if (fragment != null) {
            out.append('#').append(redactFormLike(fragment, secretRedactor));
        }
        return out.toString();
    }

    private static String redactPath(String path, Redactor secretRedactor) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String masked = PATH_SESSION_PARAM.matcher(path).replaceAll("$1" + MASK);
        return secretRedactor.redact(masked);
    }

    private static RecordedEntry.HeaderPair redactHeader(RecordedEntry.HeaderPair header, Redactor secretRedactor) {
        String name = header.name();
        String lower = name.toLowerCase(Locale.ROOT);
        String value = header.value();
        if ("cookie".equals(lower)) {
            return new RecordedEntry.HeaderPair(name, redactCookieHeader(value));
        }
        if ("set-cookie".equals(lower)) {
            return new RecordedEntry.HeaderPair(name, redactSetCookieHeader(value, secretRedactor));
        }
        if ("authorization".equals(lower) || "proxy-authorization".equals(lower)) {
            return new RecordedEntry.HeaderPair(name, redactAuthorization(value));
        }
        if ("location".equals(lower)) {
            return new RecordedEntry.HeaderPair(name, redactUrl(value, secretRedactor));
        }
        if (isSensitiveHeader(lower)) {
            return new RecordedEntry.HeaderPair(name, value.isEmpty() ? "" : MASK);
        }
        return new RecordedEntry.HeaderPair(name, secretRedactor.redact(value));
    }

    private static String redactCookieHeader(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String[] parts = value.split(";");
        ArrayList<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                out.add(trimmed.substring(0, eq).trim() + "=" + MASK);
            } else {
                out.add(MASK);
            }
        }
        return String.join("; ", out);
    }

    private static String redactSetCookieHeader(String value, Redactor secretRedactor) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int semi = value.indexOf(';');
        String first = semi >= 0 ? value.substring(0, semi) : value;
        String rest = semi >= 0 ? value.substring(semi) : "";
        int eq = first.indexOf('=');
        String head;
        if (eq > 0) {
            head = first.substring(0, eq).trim() + "=" + MASK;
        } else {
            head = MASK;
        }
        if (rest.isEmpty()) {
            return head;
        }
        return head + secretRedactor.redact(rest);
    }

    private static String redactAuthorization(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        int space = trimmed.indexOf(' ');
        if (space > 0 && space <= 16) {
            String scheme = trimmed.substring(0, space);
            if (scheme.matches("[A-Za-z0-9_-]+")) {
                return scheme + " " + MASK;
            }
        }
        return MASK;
    }

    private static String parseSetCookieName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int semi = value.indexOf(';');
        String first = semi >= 0 ? value.substring(0, semi) : value;
        int eq = first.indexOf('=');
        if (eq <= 0) {
            return "";
        }
        return first.substring(0, eq).trim();
    }

    private static List<String> parseCookieHeaderNames(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        ArrayList<String> names = new ArrayList<>();
        for (String part : value.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String name = trimmed.substring(0, eq).trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static TruncatedText redactBody(
            String body,
            String contentType,
            boolean isResponse,
            Redactor secretRedactor,
            int maxBodyChars
    ) {
        if (body == null) {
            return new TruncatedText(null, false);
        }
        String mime = baseMime(contentType);
        String redacted;
        if ("application/json".equals(mime) || mime.endsWith("+json") || looksLikeJson(body)) {
            redacted = redactJsonOrFallback(body, isResponse, secretRedactor);
        } else if (!isResponse && "application/x-www-form-urlencoded".equals(mime)) {
            redacted = redactFormLike(body, secretRedactor);
        } else if (!isResponse && mime.startsWith("multipart/")) {
            redacted = redactMultipart(body, contentType, secretRedactor);
        } else if ("text/html".equals(mime) || "application/xhtml+xml".equals(mime)) {
            redacted = redactHtml(body, secretRedactor);
        } else if ("application/xml".equals(mime) || "text/xml".equals(mime) || mime.endsWith("+xml")) {
            redacted = redactXml(body, secretRedactor);
        } else {
            String temp = redactXml(body, secretRedactor);
            if (!isResponse && looksLikeForm(temp)) {
                temp = redactFormLike(temp, secretRedactor);
            }
            redacted = secretRedactor.redact(temp);
        }
        if (maxBodyChars > 0 && redacted.length() > maxBodyChars) {
            return new TruncatedText(redacted.substring(0, maxBodyChars), true);
        }
        return new TruncatedText(redacted, false);
    }

    private static String redactJsonOrFallback(String body, boolean isResponse, Redactor secretRedactor) {
        try {
            JsonNode tree = ApiJson.tree(body);
            JsonNode clean = redactJsonNode(tree, isResponse, secretRedactor);
            return ApiJson.write(clean);
        } catch (RuntimeException ex) {
            return secretRedactor.redact(body);
        }
    }

    private static JsonNode redactJsonNode(JsonNode node, boolean isResponse, Redactor secretRedactor) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                boolean responseStatusCode = isResponse && "code".equalsIgnoreCase(key);
                if (!responseStatusCode && isSensitiveKey(key)
                        && !value.isNull() && !value.isContainerNode() && !value.isBoolean()) {
                    copy.set(key, TextNode.valueOf(MASK));
                } else {
                    copy.set(key, redactJsonNode(value, isResponse, secretRedactor));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = ((ArrayNode) node).arrayNode();
            for (JsonNode item : node) {
                copy.add(redactJsonNode(item, isResponse, secretRedactor));
            }
            return copy;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(secretRedactor.redact(node.asText()));
        }
        return node;
    }

    private static String redactFormLike(String raw, Redactor secretRedactor) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String[] pairs = raw.split("&", -1);
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                out.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.append(secretRedactor.redact(pair));
                continue;
            }
            String rawKey = pair.substring(0, eq);
            String rawValue = pair.substring(eq + 1);
            String decodedKey = safeUrlDecode(rawKey);
            if (isSensitiveKey(decodedKey) && !rawValue.isEmpty()) {
                out.append(rawKey).append('=').append(MASK);
            } else {
                out.append(rawKey).append('=').append(secretRedactor.redact(rawValue));
            }
        }
        return out.toString();
    }

    private static String redactMultipart(String body, String contentType, Redactor secretRedactor) {
        String boundary = extractBoundary(contentType);
        if (boundary == null || boundary.isEmpty()) {
            return secretRedactor.redact(body);
        }
        String delimiter = "--" + boundary;
        String[] parts = body.split(Pattern.quote(delimiter), -1);
        if (parts.length <= 1) {
            return secretRedactor.redact(body);
        }
        StringBuilder out = new StringBuilder(Math.min(body.length(), 4096));
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(delimiter);
            }
            String part = parts[i];
            if (part.isEmpty() || part.startsWith("--")) {
                out.append(part);
                continue;
            }
            String sep = "\r\n\r\n";
            int headerEnd = part.indexOf(sep);
            if (headerEnd < 0) {
                sep = "\n\n";
                headerEnd = part.indexOf(sep);
            }
            if (headerEnd < 0) {
                out.append(secretRedactor.redact(part));
                continue;
            }
            String headers = part.substring(0, headerEnd);
            String payload = part.substring(headerEnd + sep.length());
            String lineBreak = payload.endsWith("\r\n") ? "\r\n" : (payload.endsWith("\n") ? "\n" : "");
            String content = lineBreak.isEmpty() ? payload : payload.substring(0, payload.length() - lineBreak.length());
            String lowerHeaders = headers.toLowerCase(Locale.ROOT);
            boolean hasFilename = lowerHeaders.contains("filename=");
            String fieldName = extractDispositionName(headers);

            out.append(secretRedactor.redact(headers)).append(sep);
            if (hasFilename) {
                out.append("[BINARY FILE CONTENT OMITTED, ").append(content.length()).append(" chars]");
            } else if (fieldName != null && isSensitiveKey(fieldName) && !content.isEmpty()) {
                out.append(MASK);
            } else {
                out.append(secretRedactor.redact(content));
            }
            out.append(lineBreak);
        }
        return out.toString();
    }

    private static String redactHtml(String body, Redactor secretRedactor) {
        Matcher m = HTML_SENSITIVE_INPUT.matcher(body);
        StringBuilder out = new StringBuilder(body.length());
        while (m.find()) {
            String attrs = m.group(1);
            String name = firstGroup(HTML_ATTR_NAME, attrs);
            String type = firstGroup(HTML_ATTR_TYPE, attrs);
            boolean maskInput = (type != null && "password".equalsIgnoreCase(type))
                    || (name != null && isSensitiveKey(name));
            if (maskInput) {
                String maskedAttrs = HTML_ATTR_VALUE.matcher(attrs).replaceAll("$1\"" + MASK + "\"");
                m.appendReplacement(out, Matcher.quoteReplacement("<input" + maskedAttrs + ">"));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(out);
        return secretRedactor.redact(out.toString());
    }

    private static String redactXml(String body, Redactor secretRedactor) {
        String masked = XML_SENSITIVE_TAG.matcher(body).replaceAll("<$1$2>" + MASK + "</$1>");
        return secretRedactor.redact(masked);
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String param : contentType.split(";")) {
            String trimmed = param.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String value = trimmed.substring("boundary=".length()).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    private static String extractDispositionName(String headers) {
        Matcher m = Pattern.compile("(?i)\\bname\\s*=\\s*\"([^\"]+)\"").matcher(headers);
        return m.find() ? m.group(1) : null;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return m.group(i);
            }
        }
        return null;
    }

    private static boolean looksLikeJson(String body) {
        String trimmed = body.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private static boolean looksLikeForm(String body) {
        if (body.isEmpty() || body.contains("\n") || body.contains("<")) {
            return false;
        }
        return body.contains("=");
    }

    static boolean isSensitiveHeader(String lowerName) {
        if (SENSITIVE_HEADERS.contains(lowerName)) {
            return true;
        }
        for (String part : SENSITIVE_KEY_PARTS) {
            if (lowerName.contains(part)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.trim().toLowerCase(Locale.ROOT);
        if (SENSITIVE_EXACT_KEYS.contains(lower)) {
            return true;
        }
        for (String part : SENSITIVE_KEY_PARTS) {
            if (lower.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> cleanSecrets(Collection<String> extraSecrets) {
        if (extraSecrets == null || extraSecrets.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String secret : extraSecrets) {
            if (secret != null && !secret.isBlank()) {
                cleaned.add(secret.trim());
            }
        }
        return cleaned;
    }

    private static String baseMime(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeUrlDecode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    private record TruncatedText(String text, boolean truncated) {
    }
}
