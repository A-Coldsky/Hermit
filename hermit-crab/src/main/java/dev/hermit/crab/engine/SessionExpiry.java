package dev.hermit.crab.engine;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.Cookie;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import dev.hermit.common.error.ErrorCode;

/**
 * 1.12.0 自定义会话失效与探活正向预期求值器：
 * <ul>
 *   <li>{@code auth.expired_when}：列表中任意一条规则全部键成立即视为会话失效。</li>
 *   <li>{@code auth.alive_check.expect}：映射中所有键全部成立才算会话仍有效。</li>
 * </ul>
 * 对 {@code download} 响应（{@code result.spool() != null}）不扫描文件内容：仅检查状态码与响应头，
 * 涉及正文的条件一律返回 {@code false}，避免把大文件读入内存或误杀二进制内容。
 */
final class SessionExpiry {

    private SessionExpiry() {
    }

    /** 返回命中的第一条 {@code auth.expired_when} 规则下标（从 0 开始）；均未命中返回 -1。 */
    static int matchExpired(
            List<Map<String, Object>> rules,
            HttpResult result,
            BasicCookieStore cookies,
            URI base,
            long deadlineNanos
    ) {
        if (rules == null || rules.isEmpty() || result == null || result.status() >= 500) {
            return -1;
        }
        ResponseView view = new ResponseView(result, cookies, base);
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = rules.get(i);
            if (rule == null || rule.isEmpty()) {
                continue;
            }
            if (firstUnmet(rule, view, deadlineNanos) == null) {
                return i;
            }
        }
        return -1;
    }

    /** 返回 {@code auth.alive_check.expect} 中第一个不成立的键名；全部成立返回 null。 */
    static String firstUnmetExpect(
            Map<String, Object> expect,
            HttpResult result,
            BasicCookieStore cookies,
            URI base,
            long deadlineNanos
    ) {
        if (expect == null || expect.isEmpty() || result == null) {
            return null;
        }
        return firstUnmet(expect, new ResponseView(result, cookies, base), deadlineNanos);
    }

    private static String firstUnmet(Map<String, Object> rule, ResponseView view, long deadlineNanos) {
        for (Map.Entry<String, Object> entry : rule.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            boolean holds = switch (key) {
                case "status" -> statusMatches(view.status(), expected);
                case "header_exists" -> headerExists(view.headers(), expected);
                case "header_contains" -> headerContains(view.headers(), expected);
                case "cookie_exists" -> cookieExists(view.cookies(), view.base(), expected);
                case "contains" -> view.hasTextBody()
                        && expected instanceof String text && !text.isEmpty()
                        && view.body().contains(text);
                case "not_contains" -> view.hasTextBody()
                        && expected instanceof String text && !text.isEmpty()
                        && !view.body().contains(text);
                case "regex" -> view.hasTextBody()
                        && expected instanceof String text && !text.isEmpty()
                        && Extractors.regexFind(text, view.body(), deadlineNanos);
                case "json_equals" -> jsonEquals(view, expected, true);
                case "json_not_equals" -> jsonEquals(view, expected, false);
                case "json_exists" -> jsonExists(view, expected);
                default -> false;
            };
            if (!holds) {
                return key;
            }
        }
        return null;
    }

    private static boolean statusMatches(int actual, Object expected) {
        if (expected instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number number && number.intValue() == actual) {
                    return true;
                }
            }
            return false;
        }
        return expected instanceof Number number && number.intValue() == actual;
    }

    private static boolean headerExists(Map<String, String> headers, Object expected) {
        if (headers == null || !(expected instanceof String name) || name.isEmpty()) {
            return false;
        }
        return headers.containsKey(name.toLowerCase(Locale.ROOT));
    }

    private static boolean headerContains(Map<String, String> headers, Object expected) {
        if (headers == null || !(expected instanceof Map<?, ?> map) || map.isEmpty()) {
            return false;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                return false;
            }
            String actual = headers.get(name);
            if (actual == null) {
                return false;
            }
            String actualLower = actual.toLowerCase(Locale.ROOT);
            Object needle = entry.getValue();
            if (needle instanceof List<?> list) {
                boolean any = false;
                for (Object item : list) {
                    if (item instanceof String text && !text.isEmpty()
                            && actualLower.contains(text.toLowerCase(Locale.ROOT))) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return false;
                }
            } else if (needle instanceof String text && !text.isEmpty()) {
                if (!actualLower.contains(text.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean cookieExists(BasicCookieStore cookies, URI base, Object expected) {
        if (cookies == null || !(expected instanceof String name) || name.isEmpty()) {
            return false;
        }
        for (Cookie cookie : cookies.getCookies()) {
            if (name.equals(cookie.getName()) && AuthReach.readable(cookie, base, cookies)) {
                return true;
            }
        }
        return false;
    }

    private static boolean jsonEquals(ResponseView view, Object expected, boolean wantEqual) {
        if (!view.hasTextBody() || !(expected instanceof Map<?, ?> map) || map.isEmpty()) {
            return false;
        }
        if (view.json() == null) {
            return false;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String expr = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
            if (expr.isEmpty()) {
                return false;
            }
            Object actual;
            try {
                actual = view.readJson(expr);
            } catch (PathNotFoundException ex) {
                return false;
            } catch (InvalidPathException ex) {
                throw new RuleProblem(ErrorCode.RULE_MISMATCH, "JSON 路径无法解析", null, null, null);
            } catch (RuntimeException ex) {
                return false;
            }
            if (!matchesJsonPredicate(actual, entry.getValue(), wantEqual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean jsonExists(ResponseView view, Object expected) {
        if (!view.hasTextBody()) {
            return false;
        }
        if (view.json() == null) {
            return false;
        }
        List<?> paths = expected instanceof List<?> list ? list : List.of(expected);
        if (paths.isEmpty()) {
            return false;
        }
        for (Object item : paths) {
            if (!(item instanceof String raw) || raw.isBlank()) {
                return false;
            }
            Object actual;
            try {
                actual = view.readJson(raw.trim());
            } catch (PathNotFoundException ex) {
                return false;
            } catch (InvalidPathException ex) {
                throw new RuleProblem(ErrorCode.RULE_MISMATCH, "JSON 路径无法解析", null, null, null);
            } catch (RuntimeException ex) {
                return false;
            }
            if (actual instanceof List<?> list) {
                if (list.stream().noneMatch(StepRunner::present)) {
                    return false;
                }
            } else if (!StepRunner.present(actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesJsonPredicate(Object actual, Object expected, boolean wantEqual) {
        if (actual instanceof Map<?, ?>) {
            return false;
        }
        if (actual instanceof List<?> list) {
            if (list.isEmpty()) {
                return false;
            }
            if (wantEqual) {
                for (Object elem : list) {
                    if (elem instanceof Map<?, ?> || elem instanceof List<?>) {
                        continue;
                    }
                    if (matchesScalarOrList(elem, expected)) {
                        return true;
                    }
                }
                return false;
            }
            boolean sawScalar = false;
            for (Object elem : list) {
                if (elem instanceof Map<?, ?> || elem instanceof List<?>) {
                    continue;
                }
                sawScalar = true;
                if (matchesScalarOrList(elem, expected)) {
                    return false;
                }
            }
            return sawScalar;
        }
        boolean matched = matchesScalarOrList(actual, expected);
        return wantEqual ? matched : !matched;
    }

    private static boolean matchesScalarOrList(Object actual, Object expected) {
        if (actual instanceof Map<?, ?> || actual instanceof List<?>) {
            return false;
        }
        if (expected instanceof List<?> list) {
            for (Object item : list) {
                if (sameJsonScalar(actual, item)) {
                    return true;
                }
            }
            return false;
        }
        return sameJsonScalar(actual, expected);
    }

    private static boolean sameJsonScalar(Object actual, Object expected) {
        if (expected == null) {
            return actual == null;
        }
        if (actual == null) {
            return false;
        }
        if (actual instanceof Number a && expected instanceof Number b) {
            BigDecimal da = toDecimal(a);
            BigDecimal db = toDecimal(b);
            return da != null && db != null && da.compareTo(db) == 0;
        }
        if (actual instanceof Boolean a && expected instanceof Boolean b) {
            return a.equals(b);
        }
        return Values.text(actual).equals(Values.text(expected));
    }

    private static BigDecimal toDecimal(Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Double d && !Double.isFinite(d)) {
            return null;
        }
        if (number instanceof Float f && !Float.isFinite(f)) {
            return null;
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 懒解析一次响应 JSON，多条规则共用；非 JSON 时缓存失败避免重复解析。 */
    private static final class ResponseView {
        private final HttpResult result;
        private final BasicCookieStore cookies;
        private final URI base;
        private boolean jsonParsed;
        private DocumentContext jsonDoc;
        private String jsonText;

        private ResponseView(HttpResult result, BasicCookieStore cookies, URI base) {
            this.result = result;
            this.cookies = cookies;
            this.base = base;
        }

        private int status() {
            return result.status();
        }

        private Map<String, String> headers() {
            return result.headers();
        }

        private BasicCookieStore cookies() {
            return cookies;
        }

        private URI base() {
            return base;
        }

        private boolean hasTextBody() {
            return !result.downloaded() && result.spool() == null;
        }

        private String body() {
            return result.body() == null ? "" : result.body();
        }

        private Object readJson(String expr) {
            DocumentContext doc = json();
            if (expr.indexOf('(') >= 0) {
                // 函数（append 等）会就地改数组或对象；带括号的表达式单独解析，不污染共享文档。
                return JsonPath.parse(jsonText).read(expr);
            }
            return doc.read(expr);
        }

        private DocumentContext json() {
            if (jsonParsed) {
                return jsonDoc;
            }
            jsonParsed = true;
            if (!hasTextBody()) {
                return null;
            }
            String text = body().trim();
            if (text.isEmpty()) {
                return null;
            }
            char first = text.charAt(0);
            if (first != '{' && first != '[') {
                return null;
            }
            try {
                jsonDoc = JsonPath.parse(text);
                jsonText = text;
            } catch (RuntimeException ignored) {
                jsonDoc = null;
                jsonText = null;
            }
            return jsonDoc;
        }
    }
}
