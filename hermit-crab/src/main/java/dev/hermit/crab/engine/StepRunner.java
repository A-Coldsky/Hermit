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
        Object rendered = "script".equals(step.type())
                ? renderScript(step.def(), vars)
                : templates.render(step.def(), vars);
        Map<String, Object> spec = Values.map(rendered);
        if (spec == null) {
            throw new RuleProblem(ErrorCode.RULE_MISMATCH, "步骤定义无效", null, null, null);
        }
        return switch (step.type()) {
            case "request" -> request(
                    spec, vars, cookies, expectPrefix, client, afterExchange, base, deadlineNanos, capture);
            case "extract" -> extract(spec, vars, deadlineNanos, capture);
            case "assert" -> check(spec, vars, cookies, capture);
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
            boolean capture
    ) {
        HttpResult result;
        try {
            result = HttpCalls.execute(
                    spec,
                    client,
                    afterExchange,
                    base,
                    expectPrefix,
                    deadlineNanos,
                    HttpCalls.sensitiveValues(vars, cookies),
                    bodyIdle);
        } catch (RuleProblem ex) {
            if (capture) {
                Map<String, Object> piece = DevTraceDraft.requestPiece(
                        spec, ex.method, ex.path, ex.status, ex.responseHeaders, ex.responseBody, ex.wire);
                if (ex.transport != null) {
                    piece.put("transport", ex.transport);
                }
                HttpCalls.attachWarnings(piece, ex.warnings);
                DevTraceScope.stash(piece);
            }
            throw ex;
        }
        dev.hermit.crab.files.StagedFile saved = result.file();
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

    private Outcome check(Map<String, Object> spec, Map<String, Object> vars, BasicCookieStore cookies, boolean capture) {
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
                case "cookie_exists" -> checkCookie(Values.text(entry.getValue()), cookies);
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

    private static void checkCookie(String name, BasicCookieStore cookies) {
        for (Cookie cookie : cookies.getCookies()) {
            if (name.equals(cookie.getName())) {
                return;
            }
        }
        fail(COOKIE_MISSING_SUMMARY);
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
