package dev.hermit.crab.engine;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.hc.client5.http.cookie.CommonCookieAttributeHandler;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.cookie.CookieOrigin;
import org.apache.hc.client5.http.cookie.CookieSpec;
import org.apache.hc.client5.http.cookie.CookieSpecFactory;
import org.apache.hc.client5.http.cookie.MalformedCookieException;
import org.apache.hc.client5.http.cookie.SetCookie;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.impl.cookie.BasicDomainHandler;
import org.apache.hc.client5.http.impl.cookie.BasicHttpOnlyHandler;
import org.apache.hc.client5.http.impl.cookie.BasicMaxAgeHandler;
import org.apache.hc.client5.http.impl.cookie.BasicPathHandler;
import org.apache.hc.client5.http.impl.cookie.BasicSecureHandler;
import org.apache.hc.client5.http.impl.cookie.RFC6265CookieSpec;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.utils.DateUtils;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpResponseInterceptor;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Args;

/**
 * 规则引擎客户端的 Cookie 规格。在 RFC 6265 strict 上补两处老站点写法：
 * 全名星期加两位年的 Expires，以及没有前导斜杠的 Path（按默认路径，不存成对不上的值）。
 * 明文连接上的 Secure、解析失败，记进这次请求的跟踪，不改变「HTTP 上不发送 Secure」和账号罐子隔离。
 */
final class LegacyCookies {
    /** HttpCalls 放一份可变列表，响应拦截器往里追加。别的客户端没有这个属性就不记。 */
    static final String NOTICES = "hermit.cookie.notices";

    private static final Map<String, String> WEEKDAYS = Map.of(
            "sunday", "Sun",
            "monday", "Mon",
            "tuesday", "Tue",
            "wednesday", "Wed",
            "thursday", "Thu",
            "friday", "Fri",
            "saturday", "Sat");

    /**
     * 星期和日期对不上时，标准格式会失败。去掉星期再解析，两位年仍用 {@code yy} 的 2000 起点。
     */
    private static final DateTimeFormatter[] WITHOUT_WEEKDAY = {
            datePattern("dd-MMM-yy HH:mm:ss zzz"),
            datePattern("dd MMM yyyy HH:mm:ss zzz"),
            datePattern("MMM d HH:mm:ss yyyy"),
            datePattern("MMM dd HH:mm:ss yyyy")
    };

    private static final CookieSpecFactory FACTORY = context -> new Spec();
    private static final Lookup<CookieSpecFactory> REGISTRY = RegistryBuilder.<CookieSpecFactory>create()
            .register(StandardCookieSpec.STRICT, FACTORY)
            .register(StandardCookieSpec.RELAXED, FACTORY)
            .build();
    private static final HttpResponseInterceptor INTERCEPTOR = new Notices();

    private LegacyCookies() {
    }

    static Lookup<CookieSpecFactory> registry() {
        return REGISTRY;
    }

    static HttpResponseInterceptor notices() {
        return INTERCEPTOR;
    }

    private static DateTimeFormatter datePattern(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseLenient()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    static String abbreviateWeekday(String value) {
        int comma = value.indexOf(',');
        if (comma <= 0) {
            return value;
        }
        String day = value.substring(0, comma).trim().toLowerCase(Locale.ROOT);
        String shortDay = WEEKDAYS.get(day);
        if (shortDay == null) {
            return value;
        }
        return shortDay + value.substring(comma);
    }

    private static final class Spec extends RFC6265CookieSpec {
        private Spec() {
            super(
                    new DefaultingPathHandler(),
                    BasicDomainHandler.INSTANCE,
                    BasicMaxAgeHandler.INSTANCE,
                    BasicSecureHandler.INSTANCE,
                    BasicHttpOnlyHandler.INSTANCE,
                    new LegacyExpiresHandler());
        }
    }

    /**
     * Path 为空或不以 / 开头时留下规格已经放上的默认路径（RFC 6265 5.2.4），不改成对不上的字面值。
     */
    private static final class DefaultingPathHandler implements CommonCookieAttributeHandler {
        @Override
        public void parse(SetCookie cookie, String value) {
            Args.notNull(cookie, "Cookie");
            if (value != null && value.startsWith("/")) {
                cookie.setPath(value);
            }
        }

        @Override
        public void validate(Cookie cookie, CookieOrigin origin) {
        }

        @Override
        public boolean match(Cookie cookie, CookieOrigin origin) {
            return BasicPathHandler.INSTANCE.match(cookie, origin);
        }

        @Override
        public String getAttributeName() {
            return Cookie.PATH_ATTR;
        }
    }

    private static final class LegacyExpiresHandler implements CommonCookieAttributeHandler {
        @Override
        public void parse(SetCookie cookie, String value) throws MalformedCookieException {
            Args.notNull(cookie, "Cookie");
            if (value == null) {
                throw new MalformedCookieException("Missing value for 'expires' attribute");
            }
            Instant expiry = parseExpiry(value);
            if (expiry == null) {
                throw new MalformedCookieException("Invalid 'expires' attribute: " + value);
            }
            cookie.setExpiryDate(expiry);
        }

        @Override
        public void validate(Cookie cookie, CookieOrigin origin) {
        }

        @Override
        public boolean match(Cookie cookie, CookieOrigin origin) {
            return true;
        }

        @Override
        public String getAttributeName() {
            return Cookie.EXPIRES_ATTR;
        }

        private static Instant parseExpiry(String value) {
            Instant expiry = DateUtils.parseDate(value, DateUtils.STANDARD_PATTERNS);
            if (expiry != null) {
                return expiry;
            }
            String shortened = abbreviateWeekday(value);
            if (!shortened.equals(value)) {
                expiry = DateUtils.parseDate(shortened, DateUtils.STANDARD_PATTERNS);
                if (expiry != null) {
                    return expiry;
                }
            }
            String rest = withoutWeekday(shortened);
            if (rest == null || rest.isBlank()) {
                return null;
            }
            return DateUtils.parseDate(rest, WITHOUT_WEEKDAY);
        }

        /** 只在开头确实是英文星期时去掉它，避免把普通日期切坏。 */
        private static String withoutWeekday(String value) {
            int comma = value.indexOf(',');
            if (comma > 0 && isWeekday(value.substring(0, comma))) {
                return value.substring(comma + 1).trim();
            }
            int space = value.indexOf(' ');
            if (space > 0 && isWeekday(value.substring(0, space))) {
                return value.substring(space + 1).trim();
            }
            return null;
        }

        private static boolean isWeekday(String day) {
            String trimmed = day.trim();
            if (WEEKDAYS.containsKey(trimmed.toLowerCase(Locale.ROOT))) {
                return true;
            }
            for (String shortDay : WEEKDAYS.values()) {
                if (shortDay.equalsIgnoreCase(trimmed)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Notices implements HttpResponseInterceptor {
        @Override
        public void process(HttpResponse response, EntityDetails entity, HttpContext context) {
            Object raw = context.getAttribute(NOTICES);
            if (!(raw instanceof List<?> notices)) {
                return;
            }
            HttpClientContext client = HttpClientContext.cast(context);
            CookieSpec spec = client.getCookieSpec();
            CookieOrigin origin = client.getCookieOrigin();
            if (spec == null || origin == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> sink = (List<Object>) notices;
            var headers = response.headerIterator(HttpHeaders.SET_COOKIE);
            while (headers.hasNext()) {
                Header header = headers.next();
                try {
                    List<Cookie> cookies = spec.parse(header, origin);
                    if (cookies.isEmpty()) {
                        if (header.getValue() != null && !header.getValue().isBlank()) {
                            sink.add(rejected(cookieName(header.getValue())));
                        }
                        continue;
                    }
                    for (Cookie cookie : cookies) {
                        try {
                            spec.validate(cookie, origin);
                        } catch (MalformedCookieException ex) {
                            sink.add(rejected(cookie.getName()));
                            continue;
                        }
                        if (cookie.isSecure() && !origin.isSecure()) {
                            String name = cookie.getName() == null ? "" : cookie.getName();
                            sink.add(new TraceWarning(
                                    TraceWarning.SECURE_COOKIE,
                                    "收到 Secure cookie " + name + "，明文连接不会发送"));
                        }
                    }
                } catch (MalformedCookieException ex) {
                    sink.add(rejected(cookieName(header.getValue())));
                }
            }
        }

        private static TraceWarning rejected(String name) {
            if (name == null || name.isBlank()) {
                return new TraceWarning(TraceWarning.COOKIE_REJECTED, "有一条 Set-Cookie 无法解析，已忽略");
            }
            return new TraceWarning(TraceWarning.COOKIE_REJECTED, "Cookie " + name + " 无法解析，已忽略");
        }

        /** 只取名字。值、日期和整段头都不进跟踪。 */
        private static String cookieName(String header) {
            if (header == null) {
                return null;
            }
            int end = header.length();
            int eq = header.indexOf('=');
            int semi = header.indexOf(';');
            if (eq >= 0) {
                end = Math.min(end, eq);
            }
            if (semi >= 0) {
                end = Math.min(end, semi);
            }
            String name = header.substring(0, end).trim();
            if (name.isEmpty() || name.length() > 128) {
                return null;
            }
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c <= ' ' || c == '"' || c == ',' || c == ';') {
                    return null;
                }
            }
            return name;
        }
    }
}
