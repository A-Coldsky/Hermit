package dev.hermit.crab.engine;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.cookie.BasicCookieStore;

import dev.hermit.common.model.OutputShapes;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;

/**
 * 纯离线响应试炼器（供 {@code hermit_dev__dry_run_response} 调用）：
 * 不发起任何网络请求，也不读取任何真实账号凭证或 Cookie，只用与 {@link RuleEngine} 完全一致的
 * {@link Extractors}、{@link ScriptStep}、{@link SessionExpiry} 与 {@link TemplateEngine}
 * 对抓包快照响应或内联模拟响应执行提取、脚本、会话失效判断与输出模板渲染。
 */
public final class ResponseDryRunner {

    private static final long DRY_RUN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final FilterRegistry filters = FilterRegistry.defaults();
    private final TemplateEngine templates = new TemplateEngine(filters);
    private final ScriptStep scripts;
    private final Clock clock;

    public ResponseDryRunner(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.scripts = new ScriptStep(this.clock);
    }

    public record MockResponse(
            int status,
            String url,
            String method,
            String path,
            Map<String, String> headers,
            String body,
            String redirect
    ) {
    }

    public record DryRunResult(
            int extractCount,
            Map<String, Object> extracted,
            boolean expired,
            Integer matchedExpiredRuleIndex,
            Object renderedOutput
    ) {
    }

    public DryRunResult run(
            MockResponse response,
            Map<String, Object> initialVars,
            Map<String, Object> extractSpec,
            Map<String, Object> scriptSpec,
            List<Map<String, Object>> expiredWhenRules,
            Map<String, Object> outputSpec
    ) {
        long deadline = System.nanoTime() + DRY_RUN_TIMEOUT_NANOS;
        Map<String, String> lowerHeaders = new LinkedHashMap<>();
        if (response.headers() != null) {
            response.headers().forEach((k, v) -> {
                if (k != null && v != null) {
                    lowerHeaders.putIfAbsent(k.toLowerCase(Locale.ROOT), v);
                }
            });
        }
        String body = response.body() == null ? "" : response.body();
        String url = response.url() == null || response.url().isBlank() ? "https://dry-run.local/" : response.url();
        String method = response.method() == null || response.method().isBlank() ? "GET" : response.method();
        String path = response.path() == null || response.path().isBlank() ? "/" : response.path();

        HttpResult httpResult = new HttpResult(
                response.status(),
                url,
                method,
                path,
                lowerHeaders,
                body,
                response.redirect(),
                false,
                body.getBytes(StandardCharsets.UTF_8),
                List.of(),
                null,
                null);

        LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
        vars.put("input", Map.of());
        vars.put("config", Map.of());
        vars.put("now", Instant.now(clock).toString());
        if (initialVars != null) {
            for (Map.Entry<String, Object> entry : initialVars.entrySet()) {
                String key = entry.getKey();
                if (!"credential".equals(key) && !"login".equals(key) && !"response".equals(key)) {
                    vars.put(key, entry.getValue());
                }
            }
        }
        vars.put("response", FileSteps.view(httpResult, null));

        LinkedHashMap<String, Object> before = new LinkedHashMap<>(vars);
        int extractCount = 0;
        try {
            if (extractSpec != null && !extractSpec.isEmpty()) {
                extractCount = Extractors.apply(extractSpec, vars, filters, templates, deadline);
            }
            if (scriptSpec != null && !scriptSpec.isEmpty()) {
                LinkedHashMap<String, Object> safeScript = new LinkedHashMap<>(scriptSpec);
                // 离线试炼没有真实凭证，强制走非凭证沙箱。
                safeScript.remove("credential");
                scripts.run(safeScript, vars, deadline, Map.of(), Set.of());
            }
            boolean expired = false;
            Integer matchedRule = null;
            if (expiredWhenRules != null && !expiredWhenRules.isEmpty()) {
                URI baseUri;
                try {
                    baseUri = URI.create(url);
                } catch (IllegalArgumentException ex) {
                    baseUri = URI.create("https://dry-run.local/");
                }
                int idx = SessionExpiry.matchExpired(
                        expiredWhenRules, httpResult, new BasicCookieStore(), baseUri, deadline);
                if (idx >= 0) {
                    expired = true;
                    matchedRule = idx;
                }
            }
            Object renderedOutput = null;
            if (outputSpec != null && !outputSpec.isEmpty()) {
                renderedOutput = templates.render(OutputShapes.template(outputSpec), vars);
            }
            LinkedHashMap<String, Object> produced = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                String k = entry.getKey();
                if ("response".equals(k) || "now".equals(k)) {
                    continue;
                }
                if (!before.containsKey(k) || !java.util.Objects.equals(before.get(k), entry.getValue())) {
                    produced.put(k, entry.getValue());
                }
            }
            return new DryRunResult(extractCount, produced, expired, matchedRule, renderedOutput);
        } catch (RuleProblem ex) {
            throw new IllegalArgumentException(ex.summary);
        } catch (DeadlineExceeded ex) {
            throw new IllegalArgumentException("离线试炼超时");
        } catch (TemplateException ex) {
            throw new IllegalArgumentException(ex.getMessage());
        }
    }
}
