package dev.hermit.crab.engine;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.Cookie;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.FlowSteps;
import dev.hermit.common.model.Step;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.crab.http.OutboundHttp;

final class StepRunner {

    /**
     * 能力步骤找不到规则要求的 Cookie。登录路径在 {@code RuleEngine} 里换成登录专用句。
     * 不写 Cookie 的名字和值，也不提密码。
     */
    static final String COOKIE_MISSING_SUMMARY =
            "没有找到规则要求的 Cookie。请核对前面的请求是否还在设置会话。";

    /** 期望值不是数字，也不是数字列表。不写渲染结果，也不把责任推给上游改版。 */
    static final String STATUS_NOT_NUMERIC = "status 的期望值不是数字";

    static final String CONTAINS_MISMATCH =
            "响应正文里没有规则要求的内容。请核对上游页面是否改版，或更新规则中的包含条件。";

    static final String NOT_CONTAINS_MISMATCH =
            "响应正文里出现了规则不允许的内容。请核对上游返回，或更新规则中的排除条件。";

    static final String HEADER_MISSING =
            "响应里没有规则要求的响应头。请核对上游是否仍返回该头，或更新规则中的响应头条件。";

    static final String REDIRECT_FORBIDDEN =
            "响应被重定向到了规则不允许的地址。请核对登录是否仍有效，或上游跳转是否改版。";

    private final TemplateEngine templates;
    private final FilterRegistry filters;
    private final ScriptStep scripts;
    private final Duration bodyIdle;

    StepRunner(TemplateEngine templates, FilterRegistry filters) {
        this(templates, filters, Clock.systemUTC());
    }

    StepRunner(TemplateEngine templates, FilterRegistry filters, Clock clock) {
        this(templates, filters, clock, OutboundHttp.DEFAULT_BODY_IDLE);
    }

    StepRunner(TemplateEngine templates, FilterRegistry filters, Clock clock, Duration bodyIdle) {
        this.templates = templates;
        this.filters = filters;
        this.scripts = new ScriptStep(clock);
        this.bodyIdle = bodyIdle == null || bodyIdle.isZero() || bodyIdle.isNegative()
                ? OutboundHttp.DEFAULT_BODY_IDLE
                : bodyIdle;
    }

    /**
     * 步骤定义在这里渲染一次。提取出来的文本不会再送回模板引擎。
     */
    Outcome run(
            Step step,
            Map<String, Object> vars,
            String expectPrefix,
            CloseableHttpClient client,
            Runnable afterExchange,
            BasicCookieStore cookies,
            URI base,
            long deadlineNanos
    ) {
        return run(step, vars, expectPrefix, client, afterExchange, cookies, base, deadlineNanos, false, Map.of());
    }

    Outcome run(
            Step step,
            Map<String, Object> vars,
            String expectPrefix,
            CloseableHttpClient client,
            Runnable afterExchange,
            BasicCookieStore cookies,
            URI base,
            long deadlineNanos,
            boolean capture,
            Map<String, String> libraries
    ) {
        return run(step, vars, expectPrefix, client, afterExchange, cookies, base, deadlineNanos, capture, libraries,
                AuthReach.NONE);
    }

    Outcome run(
            Step step,
            Map<String, Object> vars,
            String expectPrefix,
            CloseableHttpClient client,
            Runnable afterExchange,
            BasicCookieStore cookies,
            URI base,
            long deadlineNanos,
            boolean capture,
            Map<String, String> libraries,
            AuthReach reach
    ) {
        Object rendered = "script".equals(step.type())
                ? renderScript(step.def(), vars)
                : templates.render(step.def(), vars);
        Map<String, Object> spec = Values.map(rendered);
        if (spec == null) {
            throw new RuleProblem(ErrorCode.RULE_MISMATCH, "步骤定义无效", null, null, null);
        }
        return switch (step.type()) {
            case "request" -> request(
                    spec, vars, cookies, expectPrefix, client, afterExchange, base, deadlineNanos, capture, reach);
            case "extract" -> extract(spec, vars, deadlineNanos, capture);
            case "assert" -> check(spec, vars, cookies, base, capture);
            case "set" -> set(spec, vars, capture);
            case "script" -> script(spec, vars, deadlineNanos, capture, libraries);
            default -> throw new RuleProblem(
                    ErrorCode.RULE_MISMATCH, "不支持的步骤类型 " + step.type(), null, null, null);
        };
    }

    /** code、use 和 credential 是字面量。into 和 timeout_ms 仍走模板。 */
    private Map<String, Object> renderScript(Map<String, Object> def, Map<String, Object> vars) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : def.entrySet()) {
            if ("code".equals(entry.getKey()) || "use".equals(entry.getKey()) || "credential".equals(entry.getKey())) {
                copy.put(entry.getKey(), entry.getValue());
            } else {
                copy.put(entry.getKey(), templates.render(entry.getValue(), vars));
            }
        }
        return copy;
    }

    private Outcome request(
            Map<String, Object> spec,
            Map<String, Object> vars,
            BasicCookieStore cookies,
            String expectPrefix,
            CloseableHttpClient client,
            Runnable afterExchange,
            URI base,
            long deadlineNanos,
            boolean capture,
            AuthReach reach
    ) {
        HttpResult result;
        Retry retry = Retry.of(spec.get("retry"));
        if (retry != null) {
            LinkedHashMap<String, Object> plain = new LinkedHashMap<>(spec);
            plain.remove("retry");
            spec = plain;
        }
        List<Map<String, Object>> retried = null;
        while (true) {
        try {
            result = HttpCalls.execute(
                    spec,
                    client,
                    afterExchange,
                    base,
                    expectPrefix,
                    deadlineNanos,
                    HttpCalls.sensitiveValues(vars, cookies),
                    bodyIdle,
                    reach);
            String reason = retry == null ? null : retry.reason(result.status());
            if (reason != null && retry.again(retried == null ? 0 : retried.size(), deadlineNanos)) {
                retried = Retry.note(retried, reason);
                continue;
            }
            break;
        } catch (DeadlineExceeded ex) {
            if (retry != null && ex.retryReason != null && System.nanoTime() < deadlineNanos
                    && retry.on().contains(ex.retryReason)
                    && retry.again(retried == null ? 0 : retried.size(), deadlineNanos)) {
                retried = Retry.note(retried, ex.retryReason);
                continue;
            }
            throw ex;
        } catch (RuleProblem ex) {
            String reason = retry == null ? null : retry.reason(ex);
            if (reason != null && retry.again(retried == null ? 0 : retried.size(), deadlineNanos)) {
                retried = Retry.note(retried, reason);
                continue;
            }
            if (capture) {
                Map<String, Object> piece = DevTraceDraft.requestPiece(
                        spec, ex.method, ex.path, ex.status, ex.responseHeaders, ex.responseBody, ex.wire);
                if (ex.transport != null) {
                    piece.put("transport", ex.transport);
                }
                if (retried != null) {
                    piece.put("retries", retried);
                }
                HttpCalls.attachWarnings(piece, ex.warnings);
                DevTraceScope.stash(piece);
            }
            throw ex;
        }
        }
        dev.hermit.crab.files.StagedFile saved = FileSteps.downloads(spec) ? FileSteps.save(spec, result) : null;
        if (saved != null) {
            DevTraceDraft draft = DevTraceScope.current();
            if (draft != null) {
                draft.rememberTraceSecret(saved.token());
            }
        }
        vars.put("response", FileSteps.view(result, saved));
        if (!capture) {
            return new Outcome(
                    result.method(),
                    result.path(),
                    result.status(),
                    null,
                    result.sessionLost(),
                    result.bodyBytes(),
                    result.warnings());
        }
        Map<String, Object> piece = DevTraceDraft.requestPiece(
                spec, result.method(), result.path(), result.status(), result.headers(), result.body(),
                result.wire());
        if (saved != null) {
            piece.put("file", saved.metadata());
        }
        if (retried != null) {
            piece.put("retries", retried);
        }
        HttpCalls.attachWarnings(piece, result.warnings());
        return new Outcome(
                result.method(),
                result.path(),
                result.status(),
                null,
                result.sessionLost(),
                piece,
                result.bodyBytes(),
                result.warnings());
    }

    private Outcome extract(
            Map<String, Object> spec,
            Map<String, Object> vars,
            long deadlineNanos,
            boolean capture
    ) {
        Map<String, Object> before = capture ? new LinkedHashMap<>(vars) : null;
        int count = Extractors.apply(spec, vars, filters, templates, deadlineNanos);
        if (!capture) {
            return new Outcome(null, null, null, count, false);
        }
        Map<String, Object> extracted = changed(before, vars);
        rememberDerived(extracted);
        return new Outcome(null, null, null, count, false,
                DevTraceDraft.extractPiece(spec, count, extracted));
    }

    private Outcome set(Map<String, Object> spec, Map<String, Object> vars, boolean capture) {
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            if (ConchRegistry.RESERVED.contains(entry.getKey())) {
                throw new RuleProblem(ErrorCode.RULE_MISMATCH, "不能写入保留变量", null, null, null);
            }
            vars.put(entry.getKey(), entry.getValue());
        }
        rememberDerived(spec);
        if (!capture) {
            return new Outcome(null, null, null, null, false);
        }
        return new Outcome(null, null, null, null, false, DevTraceDraft.variablePiece("set", spec));
    }

    private Outcome script(
            Map<String, Object> spec,
            Map<String, Object> vars,
            long deadlineNanos,
            boolean capture,
            Map<String, String> libraries
    ) {
        boolean mask = Boolean.TRUE.equals(spec.get("credential"));
        Map<String, Object> before = (capture || mask) ? new LinkedHashMap<>(vars) : null;
        Outcome outcome = scripts.run(spec, vars, deadlineNanos, libraries);
        Map<String, Object> produced = changed(before, vars);
        if (mask) {
            CallSecrets.remember(produced);
        }
        rememberDerived(produced);
        if (!capture) {
            return outcome;
        }
        return new Outcome(outcome.method(), outcome.path(), outcome.status(), outcome.extractCount(),
                outcome.sessionLost(), DevTraceDraft.variablePiece("script", produced));
    }

    private static void rememberDerived(Object value) {
        DevTraceDraft draft = DevTraceScope.current();
        if (draft != null && draft.collectingDerived()) {
            draft.rememberDerived(value);
        }
    }

    private static Map<String, Object> changed(Map<String, Object> before, Map<String, Object> after) {
        LinkedHashMap<String, Object> changed = new LinkedHashMap<>();
        if (after == null) {
            return changed;
        }
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (ConchRegistry.RESERVED.contains(entry.getKey())) {
                continue;
            }
            if (before == null || !before.containsKey(entry.getKey())
                    || !Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        return changed;
    }

    private Outcome check(
            Map<String, Object> spec, Map<String, Object> vars, BasicCookieStore cookies, URI base, boolean capture) {
        Object response = vars.get("response");
        Map<String, Object> map = Values.map(response);
        if (map == null || !map.containsKey("status")) {
            throw new RuleProblem(ErrorCode.RULE_MISMATCH, "没有响应", null, null, null);
        }
        int status = Values.integer(map.get("status"), -1);
        String body = map.get("body") == null ? "" : Values.text(map.get("body"));
        String redirect = map.get("redirect") == null ? null : Values.text(map.get("redirect"));
        String url = map.get("url") == null ? "" : Values.text(map.get("url"));
        Map<String, Object> headers = Values.map(map.get("headers"));
        if (headers == null) {
            headers = Map.of();
        }
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            switch (entry.getKey()) {
                case "status" -> checkStatus(entry.getValue(), status);
                case "contains" -> {
                    if (!body.contains(Values.text(entry.getValue()))) {
                        fail(CONTAINS_MISMATCH);
                    }
                }
                case "not_contains" -> {
                    if (body.contains(Values.text(entry.getValue()))) {
                        fail(NOT_CONTAINS_MISMATCH);
                    }
                }
                case "cookie_exists" -> checkCookie(Values.text(entry.getValue()), cookies, base);
                case "header_exists" -> checkHeader(Values.text(entry.getValue()), headers);
                case "not_redirected_to" -> {
                    if (redirectedTo(redirect, url, Values.text(entry.getValue()))) {
                        fail(REDIRECT_FORBIDDEN);
                    }
                }
                default -> fail("未知的 assert 键 " + entry.getKey());
            }
        }
        if (!capture) {
            return new Outcome(null, null, null, null, false);
        }
        return new Outcome(null, null, null, null, false, DevTraceDraft.assertPiece(spec, true));
    }

    /**
     * if 和 repeat.until 的判断。写了几个键就要全部成立。和 assert 不同，不成立只返回 false；
     * 要看响应的键在还没有响应时算规则写错。
     */
    boolean test(Map<String, Object> spec, Map<String, Object> vars, BasicCookieStore cookies, URI base) {
        Map<String, Object> response = Values.map(vars.get("response"));
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (FlowSteps.RESPONSE_CONDITIONS.contains(key) && (response == null || !response.containsKey("status"))) {
                fail("判断键 " + key + " 要看响应，但前面还没有 request");
            }
            boolean holds = switch (key) {
                case "status" -> statusIn(value, Values.integer(response.get("status"), -1));
                case "contains" -> bodyOf(response).contains(Values.text(value));
                case "not_contains" -> !bodyOf(response).contains(Values.text(value));
                case "cookie_exists" -> hasCookie(Values.text(value), cookies, base);
                case "header_exists" -> {
                    Map<String, Object> headers = Values.map(response.get("headers"));
                    yield headers != null && headers.containsKey(Values.text(value).toLowerCase(Locale.ROOT));
                }
                case "present" -> names(value).stream().allMatch(name -> present(lookup(vars, name)));
                case "not_present" -> names(value).stream().noneMatch(name -> present(lookup(vars, name)));
                case "equals" -> pairs(value).entrySet().stream()
                        .allMatch(pair -> same(lookup(vars, pair.getKey()), pair.getValue()));
                case "not_equals" -> pairs(value).entrySet().stream()
                        .noneMatch(pair -> same(lookup(vars, pair.getKey()), pair.getValue()));
                default -> {
                    fail("未知的判断键 " + key);
                    yield false;
                }
            };
            if (!holds) {
                return false;
            }
        }
        return true;
    }

    private static String bodyOf(Map<String, Object> response) {
        return response.get("body") == null ? "" : Values.text(response.get("body"));
    }

    private static boolean statusIn(Object expected, int actual) {
        if (expected instanceof List<?> list) {
            return list.stream().anyMatch(item -> statusIn(item, actual));
        }
        if (expected instanceof Number number) {
            return number.intValue() == actual;
        }
        try {
            return expected != null && Integer.parseInt(Values.text(expected).trim()) == actual;
        } catch (NumberFormatException ex) {
            throw new RuleProblem(ErrorCode.RULE_MISMATCH, STATUS_NOT_NUMERIC, null, null, null);
        }
    }

    private static List<String> names(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(Values::text).toList();
        }
        return value == null ? List.of() : List.of(Values.text(value));
    }

    private static Map<String, Object> pairs(Object value) {
        Map<String, Object> map = Values.map(value);
        if (map == null) {
            fail("equals / not_equals 必须是「变量名: 期望值」的映射");
        }
        return map;
    }

    private static boolean same(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        return Values.text(actual).equals(Values.text(expected));
    }

    /** null、空字符串、空列表和空映射都算没有。 */
    static boolean present(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence text) {
            return text.length() > 0;
        }
        if (value instanceof java.util.Collection<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    /** 按 {@code a.b.0.c} 取变量；中途取不到返回 null。 */
    static Object lookup(Map<String, Object> vars, String path) {
        Object current = vars;
        for (String part : path.split("\\.")) {
            if (current instanceof Peek peek) {
                current = peek.peek(part);
            } else if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(part);
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException ex) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** 取不到值时返回 null、不抛错的变量视图（login）。 */
    interface Peek {
        Object peek(String name);
    }

    /** request 的 retry：失败后再试几次、隔多久、哪些失败算。 */
    record Retry(int times, long delayMillis, List<Object> on) {
        private static final List<Object> DEFAULT_ON = List.of("timeout", "connect", 502, 503, 504);

        static Retry of(Object raw) {
            Map<String, Object> map = Values.map(raw);
            if (map == null) {
                return null;
            }
            int times = Math.max(0, Math.min(FlowSteps.RETRY_TIMES_MAX, Values.integer(map.get("times"), 0)));
            long delay = Math.max(0, Math.min(FlowSteps.RETRY_DELAY_MAX, Values.integer(map.get("delay_ms"), 0)));
            List<Object> on = map.get("on") instanceof List<?> list && !list.isEmpty()
                    ? List.copyOf(list)
                    : DEFAULT_ON;
            return new Retry(times, delay, on);
        }

        String reason(int status) {
            for (Object item : on) {
                if (!"timeout".equals(item) && !"connect".equals(item)
                        && Values.integer(item, -1) == status) {
                    return Integer.toString(status);
                }
            }
            return null;
        }

        String reason(RuleProblem ex) {
            if (ex.code != ErrorCode.UPSTREAM_ERROR) {
                return null;
            }
            if (ex.status != null) {
                return reason(ex.status);
            }
            if (ex.retryReason != null) {
                return on.contains(ex.retryReason) ? ex.retryReason : null;
            }
            if (HttpCalls.STEP_TIMEOUT.equals(ex.summary)) {
                return on.contains("timeout") ? "timeout" : null;
            }
            return ex.transport != null && on.contains("connect") ? "connect" : null;
        }

        /** 还有次数，并且等完间隔之后能力的时间还没用完。 */
        boolean again(int done, long deadlineNanos) {
            if (done >= times) {
                return false;
            }
            long left = deadlineNanos - System.nanoTime() - delayMillis * 1_000_000L;
            if (left <= 0) {
                return false;
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        static List<Map<String, Object>> note(List<Map<String, Object>> retried, String reason) {
            java.util.ArrayList<Map<String, Object>> all =
                    retried == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(retried);
            all.add(Map.of("attempt", all.size() + 1, "reason", reason));
            return all;
        }
    }

    private static void checkStatus(Object expected, int actual) {
        if (expected instanceof Number number) {
            if (number.intValue() == actual) {
                return;
            }
            fail(statusMismatch(expected, actual));
        }
        if (expected instanceof java.util.List<?> list) {
            boolean numbers = !list.isEmpty();
            boolean matched = false;
            for (Object item : list) {
                if (item instanceof Number number) {
                    if (number.intValue() == actual) {
                        matched = true;
                    }
                } else {
                    numbers = false;
                }
            }
            if (matched) {
                return;
            }
            // 列表里夹着非数字时，不能把那些渲染结果写进「期望 …」。有数字命中时上面已经通过。
            fail(numbers ? statusMismatch(expected, actual) : STATUS_NOT_NUMERIC);
        }
        fail(STATUS_NOT_NUMERIC);
    }

    /** 只写出数字状态码。调用方已经确认期望值全是数字。 */
    private static String statusMismatch(Object expected, int actual) {
        return "响应状态与规则不一致：期望 " + statusText(expected) + "，实际 " + actual
                + "。请核对上游接口是否改版，或更新规则里的状态断言。";
    }

    private static String statusText(Object expected) {
        if (expected instanceof Number number) {
            return Integer.toString(number.intValue());
        }
        if (expected instanceof java.util.List<?> list && !list.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (Object item : list) {
                if (!(item instanceof Number number)) {
                    return "规则声明的状态";
                }
                if (builder.length() > 0) {
                    builder.append('、');
                }
                builder.append(number.intValue());
            }
            return builder.toString();
        }
        return "规则声明的状态";
    }

    private static void checkCookie(String name, BasicCookieStore cookies, URI base) {
        if (!hasCookie(name, cookies, base)) {
            fail(COOKIE_MISSING_SUMMARY);
        }
    }

    /** 只看会发往 base_url 主机的 Cookie。认证地址上的同名 Cookie 不算。 */
    private static boolean hasCookie(String name, BasicCookieStore cookies, URI base) {
        for (Cookie cookie : cookies.getCookies()) {
            if (name.equals(cookie.getName()) && AuthReach.sentTo(cookie, base)) {
                return true;
            }
        }
        return false;
    }

    private static void checkHeader(String name, Map<String, Object> headers) {
        if (!headers.containsKey(name.toLowerCase(Locale.ROOT))) {
            fail(HEADER_MISSING);
        }
    }

    private static boolean redirectedTo(String redirect, String currentUrl, String prefix) {
        if (redirect == null || prefix == null || prefix.isEmpty()) {
            return false;
        }
        try {
            URI current = URI.create(currentUrl);
            URI target = current.resolve(redirect);
            String path = target.getPath() == null ? "" : target.getPath();
            return path.startsWith(prefix);
        } catch (IllegalArgumentException ex) {
            String path = redirect;
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            return path.startsWith(prefix);
        }
    }

    private static void fail(String summary) {
        throw new RuleProblem(ErrorCode.RULE_MISMATCH, summary, null, null, null);
    }
}

record Outcome(
        String method,
        String path,
        Integer status,
        Integer extractCount,
        boolean sessionLost,
        Map<String, Object> capture,
        byte[] bodyBytes,
        List<TraceWarning> warnings
) {
    Outcome {
        warnings = warnings == null || warnings.isEmpty() ? List.of() : List.copyOf(warnings);
    }

    Outcome(String method, String path, Integer status, Integer extractCount, boolean sessionLost) {
        this(method, path, status, extractCount, sessionLost, null, null, List.of());
    }

    Outcome(
            String method,
            String path,
            Integer status,
            Integer extractCount,
            boolean sessionLost,
            Map<String, Object> capture
    ) {
        this(method, path, status, extractCount, sessionLost, capture, null, List.of());
    }

    Outcome(String method, String path, Integer status, Integer extractCount, boolean sessionLost, byte[] bodyBytes) {
        this(method, path, status, extractCount, sessionLost, null, bodyBytes, List.of());
    }

    Outcome(
            String method,
            String path,
            Integer status,
            Integer extractCount,
            boolean sessionLost,
            byte[] bodyBytes,
            List<TraceWarning> warnings
    ) {
        this(method, path, status, extractCount, sessionLost, null, bodyBytes, warnings);
    }

    Outcome(
            String method,
            String path,
            Integer status,
            Integer extractCount,
            boolean sessionLost,
            Map<String, Object> capture,
            byte[] bodyBytes
    ) {
        this(method, path, status, extractCount, sessionLost, capture, bodyBytes, List.of());
    }
}
