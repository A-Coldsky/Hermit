package dev.hermit.crab.engine;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.Cookie;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Step;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;

final class StepRunner {

    /** 登录路径会换成带下一步的说明；其它 cookie 断言也用这段，不再暴露 assert 键名。 */
    static final String COOKIE_MISSING_SUMMARY =
            "没有找到会话 Cookie。请核对账号和密码；若凭证无误，再确认上游是否仍会建立会话。";

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
    private final ScriptStep scripts = new ScriptStep();

    StepRunner(TemplateEngine templates, FilterRegistry filters) {
        this.templates = templates;
        this.filters = filters;
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
        Object rendered = "script".equals(step.type())
                ? renderScript(step.def(), vars)
                : templates.render(step.def(), vars);
        Map<String, Object> spec = Values.map(rendered);
        if (spec == null) {
            throw new RuleProblem(ErrorCode.RULE_MISMATCH, "步骤定义无效", null, null, null);
        }
        return switch (step.type()) {
            case "request" -> request(spec, vars, expectPrefix, client, afterExchange, base, deadlineNanos);
            case "extract" -> extract(spec, vars);
            case "assert" -> check(spec, vars, cookies);
            case "set" -> set(spec, vars);
            case "script" -> scripts.run(spec, vars, deadlineNanos);
            default -> throw new RuleProblem(
                    ErrorCode.RULE_MISMATCH, "不支持的步骤类型 " + step.type(), null, null, null);
        };
    }

    /** code 是字面量。into 和 timeout_ms 仍走模板。 */
    private Map<String, Object> renderScript(Map<String, Object> def, Map<String, Object> vars) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : def.entrySet()) {
            if ("code".equals(entry.getKey())) {
                copy.put("code", entry.getValue());
            } else {
                copy.put(entry.getKey(), templates.render(entry.getValue(), vars));
            }
        }
        return copy;
    }

    private Outcome request(
            Map<String, Object> spec,
            Map<String, Object> vars,
            String expectPrefix,
            CloseableHttpClient client,
            Runnable afterExchange,
            URI base,
            long deadlineNanos
    ) {
        HttpResult result = HttpCalls.execute(spec, client, afterExchange, base, expectPrefix, deadlineNanos);
        vars.put("response", result.view());
        return new Outcome(result.method(), result.path(), result.status(), null, result.sessionLost());
    }

    private Outcome extract(Map<String, Object> spec, Map<String, Object> vars) {
        int count = Extractors.apply(spec, vars, filters, templates);
        return new Outcome(null, null, null, count, false);
    }

    private Outcome set(Map<String, Object> spec, Map<String, Object> vars) {
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            if (ConchRegistry.RESERVED.contains(entry.getKey())) {
                throw new RuleProblem(ErrorCode.RULE_MISMATCH, "不能写入保留变量", null, null, null);
            }
            vars.put(entry.getKey(), entry.getValue());
        }
        return new Outcome(null, null, null, null, false);
    }

    private Outcome check(Map<String, Object> spec, Map<String, Object> vars, BasicCookieStore cookies) {
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
                default -> fail("未知的 assert 键");
            }
        }
        return new Outcome(null, null, null, null, false);
    }

    private static void checkStatus(Object expected, int actual) {
        if (expected instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item instanceof Number number && number.intValue() == actual) {
                    return;
                }
            }
            fail(statusMismatch(expected, actual));
        }
        if (expected instanceof Number number && number.intValue() == actual) {
            return;
        }
        fail(statusMismatch(expected, actual));
    }

    /** 只写出数字状态码。期望值若不是数字，不能把渲染后的原文放进错误说明。 */
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

record Outcome(String method, String path, Integer status, Integer extractCount, boolean sessionLost) {
}
