package dev.hermit.crab.engine;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    static final String EQUALS_MISMATCH =
            "变量值与规则要求不一致。请核对上游返回是否变化，或更新规则中的断言条件。";

    static final String NOT_EQUALS_MISMATCH =
            "变量值命中了规则不允许的值。请核对上游返回，或更新规则中的排除条件。";

    static final String PRESENT_MISSING =
            "规则要求的变量没有值。请核对前面的提取或上游返回是否变化。";

    static final String NOT_PRESENT_FOUND =
            "规则不允许出现的变量存在值。请核对上游返回，或更新规则中的断言条件。";

    private static final java.util.Set<String> ASSERT_RESPONSE_KEYS = java.util.Set.of(
            "status", "contains", "not_contains", "cookie_exists", "header_exists", "not_redirected_to");

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
                AuthReach.NONE, List.of(), false);
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
        return run(step, vars, expectPrefix, client, afterExchange, cookies, base, deadlineNanos, capture, libraries,
                reach, List.of(), false);
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
            AuthReach reach,
            List<Map<String, Object>> expiredWhen,
            boolean auth
    ) {
        return run(step, vars, expectPrefix, client, afterExchange, cookies, base, deadlineNanos, capture, libraries,
                reach, expiredWhen, auth, List.of(), null);
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
            AuthReach reach,
            List<Map<String, Object>> expiredWhen,
            boolean auth,
            Collection<String> extraSecrets,
            Set<String> taintedVars
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
                    spec, vars, cookies, expectPrefix, client, afterExchange, base, deadlineNanos, capture, reach,
                    auth ? List.of() : expiredWhen, extraSecrets);
            case "extract" -> extract(spec, step.def(), vars, deadlineNanos, capture, auth, taintedVars);
            case "assert" -> check(spec, vars, cookies, base, capture, !auth);
            case "set" -> set(spec, step.def(), vars, capture, auth, taintedVars);
            case "script" -> script(spec, vars, deadlineNanos, capture, libraries, auth, taintedVars);
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
            AuthReach reach,
            List<Map<String, Object>> expiredWhen,
            Collection<String> extraSecrets
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
                    HttpCalls.sensitiveValues(vars, cookies, extraSecrets),
                    bodyIdle,
                    reach);
            String reason = retry == null ? null : retry.reason(result.status());
            if (reason != null && retry.again(retried == null ? 0 : retried.size(), deadlineNanos)) {
                if (result.spool() != null) {
                    result.spool().discard();
                }
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
        boolean sessionLost = result.sessionLost();
        List<TraceWarning> allWarnings = result.warnings();
        if (!sessionLost && expiredWhen != null && !expiredWhen.isEmpty()) {
            try {
                int matched = SessionExpiry.matchExpired(expiredWhen, result, cookies, base, deadlineNanos);
                if (matched >= 0) {
                    sessionLost = true;
                    java.util.ArrayList<TraceWarning> merged = new java.util.ArrayList<>(allWarnings);
                    merged.add(new TraceWarning(
                            TraceWarning.SESSION_EXPIRED,
                            "命中 auth.expired_when 第 " + (matched + 1) + " 条规则，按会话失效处理"));
                    allWarnings = List.copyOf(merged);
                }
            } catch (RuntimeException ex) {
                if (result.spool() != null) {
                    result.spool().discard();
                }
                throw ex;
            }
        }
        dev.hermit.crab.files.StagedFile saved = null;
        if (FileSteps.downloads(spec)) {
            if (sessionLost) {
                // 会话失效的下载响应不入文件暂存区，避免把登录页或报错内容当附件保存。
                if (result.spool() != null) {
                    result.spool().discard();
                }
            } else {
                saved = FileSteps.save(spec, result);
            }
        }
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
                    sessionLost,
                    result.bodyBytes(),
                    allWarnings);
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
        HttpCalls.attachWarnings(piece, allWarnings);
        return new Outcome(
                result.method(),
                result.path(),
                result.status(),
                null,
                sessionLost,
                piece,
                result.bodyBytes(),
                allWarnings);
    }

    private Outcome extract(
            Map<String, Object> spec,
            Map<String, Object> rawDef,
            Map<String, Object> vars,
            long deadlineNanos,
            boolean capture,
            boolean auth,
            Set<String> taintedVars
    ) {
        Map<String, Object> before = capture ? new LinkedHashMap<>(vars) : null;
        int count = Extractors.apply(spec, vars, filters, templates, deadlineNanos);
        if (!auth && taintedVars != null) {
            Set<String> taintedBefore = withAuthView(taintedVars, vars);
            for (Map.Entry<String, Object> entry : spec.entrySet()) {
                String key = entry.getKey();
                Map<String, Object> child = Values.map(entry.getValue());
                Object rawChild = rawDef == null ? null : rawDef.get(key);
                if (extractReadsLoginOrTainted(child, taintedBefore)
                        || referencesLoginOrTainted(rawChild, taintedBefore)) {
                    CallSecrets.remember(vars.get(key));
                    taintedVars.add(key);
                } else {
                    taintedVars.remove(key);
                }
            }
        }
        if (!capture) {
            return new Outcome(null, null, null, count, false);
        }
        Map<String, Object> extracted = changed(before, vars);
        rememberDerived(extracted);
        return new Outcome(null, null, null, count, false,
                DevTraceDraft.extractPiece(spec, count, extracted));
    }

    private Outcome set(
            Map<String, Object> spec,
            Map<String, Object> rawDef,
            Map<String, Object> vars,
            boolean capture,
            boolean auth,
            Set<String> taintedVars
    ) {
        Set<String> taintedBefore = withAuthView(taintedVars, vars);
        for (Map.Entry<String, Object> entry : spec.entrySet()) {
            String key = entry.getKey();
            if (ConchRegistry.RESERVED.contains(key)) {
                throw new RuleProblem(ErrorCode.RULE_MISMATCH, "不能写入保留变量", null, null, null);
            }
            vars.put(key, entry.getValue());
            if (!auth) {
                Object rawValue = rawDef == null ? null : rawDef.get(key);
                if (referencesLoginOrTainted(rawValue, taintedBefore)) {
                    CallSecrets.remember(entry.getValue());
                    if (taintedVars != null) {
                        taintedVars.add(key);
                    }
                } else if (taintedVars != null) {
                    taintedVars.remove(key);
                }
            }
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
            Map<String, String> libraries,
            boolean auth,
            Set<String> taintedVars
    ) {
        boolean mask = Boolean.TRUE.equals(spec.get("credential"));
        boolean trackChanges = capture || mask || (!auth && taintedVars != null && !taintedVars.isEmpty());
        Map<String, Object> before = trackChanges ? new LinkedHashMap<>(vars) : null;
        Set<String> hidden = (!auth && taintedVars != null && !taintedVars.isEmpty())
                ? Set.copyOf(taintedVars)
                : Set.of();
        Outcome outcome = scripts.run(spec, vars, deadlineNanos, libraries, hidden);
        Map<String, Object> produced = trackChanges ? changed(before, vars) : Map.of();
        if (mask) {
            CallSecrets.remember(produced);
        } else if (!auth && taintedVars != null && !produced.isEmpty()) {
            taintedVars.removeAll(produced.keySet());
        }
        rememberDerived(produced);
        if (!capture) {
            return outcome;
        }
        return new Outcome(outcome.method(), outcome.path(), outcome.status(), outcome.extractCount(),
                outcome.sessionLost(), DevTraceDraft.variablePiece("script", produced));
    }

    /**
     * 引用授权器的包里 {@code auth} 是授权器导出值的视图，和 {@code login} 一样是秘密的来源；
     * 别的包里 {@code auth} 只是一个普通变量名，不受影响。
     */
    private static Set<String> withAuthView(Set<String> taintedVars, Map<String, Object> vars) {
        boolean view = vars.get(ConchRegistry.AUTH_VAR) instanceof HttpCalls.SecretSource;
        if (taintedVars == null || taintedVars.isEmpty()) {
            return view ? Set.of(ConchRegistry.AUTH_VAR) : Set.of();
        }
        if (!view) {
            return Set.copyOf(taintedVars);
        }
        LinkedHashSet<String> all = new LinkedHashSet<>(taintedVars);
        all.add(ConchRegistry.AUTH_VAR);
        return all;
    }

    private boolean referencesLoginOrTainted(Object rawNode, Set<String> taintedVars) {
        if (rawNode == null) {
            return false;
        }
        Set<String> paths;
        try {
            paths = templates.referencedPaths(rawNode);
        } catch (RuntimeException ex) {
            return false;
        }
        for (String path : paths) {
            if (referencesName(path, "login") || referencesAny(path, taintedVars)) {
                return true;
            }
        }
        return false;
    }

    private boolean extractReadsLoginOrTainted(Map<String, Object> spec, Set<String> tainted) {
        if (spec == null) {
            return false;
        }
        Object from = spec.get("from");
        if (from != null) {
            String source = Values.text(from);
            if ("login".equals(source) || (tainted != null && tainted.contains(source))) {
                return true;
            }
        }
        Object filter = spec.get("filter");
        if (filter instanceof String chain && !chain.isBlank()
                && referencesLoginOrTainted("{{_ | " + chain + "}}", tainted)) {
            return true;
        }
        Map<String, Object> each = Values.map(spec.get("each"));
        if (each != null) {
            for (Object item : each.values()) {
                if (extractReadsLoginOrTainted(Values.map(item), tainted)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean referencesAny(String path, Set<String> names) {
        if (path == null || names == null || names.isEmpty()) {
            return false;
        }
        for (String name : names) {
            if (referencesName(path, name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean referencesName(String path, String name) {
        if (path == null || name == null || name.isEmpty()) {
            return false;
        }
        if (path.equals(name)) {
            return true;
        }
        if (path.length() > name.length() && path.startsWith(name)) {
            char next = path.charAt(name.length());
            return next == '.' || next == '[';
        }
        return false;
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
            Map<String, Object> spec,
            Map<String, Object> vars,
            BasicCookieStore cookies,
            URI base,
            boolean capture,
            boolean allowSessionExpired
    ) {
        boolean sessionExpiredOnFail = false;
        if (spec.containsKey("on_fail")) {
            Object mode = spec.get("on_fail");
            if (!(mode instanceof String text) || !"session_expired".equals(text)) {
                fail("on_fail 目前只支持 session_expired");
            }
            sessionExpiredOnFail = allowSessionExpired;
        }
        boolean needsResponse = false;
        for (String key : spec.keySet()) {
            if (ASSERT_RESPONSE_KEYS.contains(key)) {
                needsResponse = true;
                break;
            }
        }
        Map<String, Object> map = Values.map(vars.get("response"));
        if (needsResponse && (map == null || !map.containsKey("status"))) {
            throw new RuleProblem(ErrorCode.RULE_MISMATCH, "没有响应", null, null, null);
        }
        int status = map == null ? -1 : Values.integer(map.get("status"), -1);
        String body = map == null || map.get("body") == null ? "" : Values.text(map.get("body"));
        String redirect = map == null || map.get("redirect") == null ? null : Values.text(map.get("redirect"));
        String url = map == null || map.get("url") == null ? "" : Values.text(map.get("url"));
        Map<String, Object> headers = map == null ? null : Values.map(map.get("headers"));
        if (headers == null) {
            headers = Map.of();
        }
        try {
            for (Map.Entry<String, Object> entry : spec.entrySet()) {
                switch (entry.getKey()) {
                    case "on_fail" -> {
                        // 修饰键已在上面校验。
                    }
                    case "status" -> checkStatus(entry.getValue(), status);
                    case "contains" -> {
                        if (!body.contains(Values.text(entry.getValue()))) {
                            assertFail(CONTAINS_MISMATCH);
                        }
                    }
                    case "not_contains" -> {
                        if (body.contains(Values.text(entry.getValue()))) {
                            assertFail(NOT_CONTAINS_MISMATCH);
                        }
                    }
                    case "cookie_exists" -> checkCookie(Values.text(entry.getValue()), cookies, base);
                    case "header_exists" -> checkHeader(Values.text(entry.getValue()), headers);
                    case "not_redirected_to" -> {
                        if (redirectedTo(redirect, url, Values.text(entry.getValue()))) {
                            assertFail(REDIRECT_FORBIDDEN);
                        }
                    }
                    case "equals" -> {
                        for (Map.Entry<String, Object> pair : pairs(entry.getValue()).entrySet()) {
                            if (!same(lookup(vars, pair.getKey()), pair.getValue())) {
                                assertFail(EQUALS_MISMATCH);
                            }
                        }
                    }
                    case "not_equals" -> {
                        for (Map.Entry<String, Object> pair : pairs(entry.getValue()).entrySet()) {
                            if (same(lookup(vars, pair.getKey()), pair.getValue())) {
                                assertFail(NOT_EQUALS_MISMATCH);
                            }
                        }
                    }
                    case "present" -> {
                        for (String name : names(entry.getValue())) {
                            if (!present(lookup(vars, name))) {
                                assertFail(PRESENT_MISSING);
                            }
                        }
                    }
                    case "not_present" -> {
                        for (String name : names(entry.getValue())) {
                            if (present(lookup(vars, name))) {
                                assertFail(NOT_PRESENT_FOUND);
                            }
                        }
                    }
                    default -> fail("未知的 assert 键 " + entry.getKey());
                }
            }
        } catch (AssertionFailed ex) {
            if (sessionExpiredOnFail) {
                TraceWarning warning = new TraceWarning(
                        TraceWarning.SESSION_EXPIRED,
                        "断言未通过（on_fail: session_expired），按会话失效处理");
                if (!capture) {
                    return new Outcome(null, null, null, null, true, null, null, List.of(warning));
                }
                Map<String, Object> piece = DevTraceDraft.assertPiece(spec, false);
                HttpCalls.attachWarnings(piece, List.of(warning));
                return new Outcome(null, null, null, null, true, piece, null, List.of(warning));
            }
            fail(ex.summary);
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
            assertFail(statusMismatch(expected, actual));
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
            if (numbers) {
                assertFail(statusMismatch(expected, actual));
            }
            fail(STATUS_NOT_NUMERIC);
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
            assertFail(COOKIE_MISSING_SUMMARY);
        }
    }

    /** 只看会发往 base_url 主机的 Cookie。认证地址上的同名 Cookie 不算。 */
    private static boolean hasCookie(String name, BasicCookieStore cookies, URI base) {
        for (Cookie cookie : cookies.getCookies()) {
            if (name.equals(cookie.getName()) && AuthReach.readable(cookie, base, cookies)) {
                return true;
            }
        }
        return false;
    }

    private static void checkHeader(String name, Map<String, Object> headers) {
        if (!headers.containsKey(name.toLowerCase(Locale.ROOT))) {
            assertFail(HEADER_MISSING);
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

    private static void assertFail(String summary) {
        throw new AssertionFailed(summary);
    }

    /** 断言条件本身未通过（区别于规则写错的 RuleProblem），供 on_fail: session_expired 转换。 */
    private static final class AssertionFailed extends RuntimeException {
        private final String summary;

        private AssertionFailed(String summary) {
            super(summary, null, false, false);
            this.summary = summary;
        }
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
