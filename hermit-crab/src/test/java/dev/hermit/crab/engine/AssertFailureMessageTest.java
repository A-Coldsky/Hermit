package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.junit.jupiter.api.Test;

import dev.hermit.common.model.Step;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;

class AssertFailureMessageTest {

    private static final String SECRET = "sid-secret-cookie-91";

    private final StepRunner runner = new StepRunner(new TemplateEngine(FilterRegistry.defaults()), FilterRegistry.defaults());

    @Test
    void assertFailuresExplainTheNextStepAndOmitSecrets() {
        String status = summary(response(404, "ok", null, Map.of()), Map.of("status", 200));
        assertPlain(status);
        assertThat(status).contains("期望 200").contains("实际 404");

        String choices = summary(response(500, "ok", null, Map.of()), Map.of("status", List.of(200, 201)));
        assertPlain(choices);
        assertThat(choices).contains("200、201").contains("实际 500");

        String poisoned = summary(response(404, SECRET, null, Map.of()), Map.of("status", List.of(200, SECRET)));
        assertPlain(poisoned);
        assertThat(poisoned).contains("规则声明的状态").contains("实际 404");

        assertPlain(summary(response(200, "page", null, Map.of()), Map.of("contains", SECRET)));
        assertThat(summary(response(200, "page", null, Map.of()), Map.of("contains", SECRET)))
                .isEqualTo(StepRunner.CONTAINS_MISMATCH);

        String excluded = summary(response(200, "seen " + SECRET, null, Map.of()), Map.of("not_contains", SECRET));
        assertPlain(excluded);
        assertThat(excluded).isEqualTo(StepRunner.NOT_CONTAINS_MISMATCH);

        String header = summary(
                response(200, "ok", null, Map.of("content-type", "text/html")),
                Map.of("header_exists", "X-" + SECRET));
        assertPlain(header);
        assertThat(header).isEqualTo(StepRunner.HEADER_MISSING);

        String redirect = summary(
                response(302, "ok", "/" + SECRET + "?session=" + SECRET, Map.of()),
                Map.of("not_redirected_to", "/" + SECRET));
        assertPlain(redirect);
        assertThat(redirect).isEqualTo(StepRunner.REDIRECT_FORBIDDEN);

        String cookie = summary(response(200, "ok", null, Map.of()), Map.of("cookie_exists", SECRET));
        assertPlain(cookie);
        assertThat(cookie).isEqualTo(StepRunner.COOKIE_MISSING_SUMMARY);
    }

    private void assertPlain(String summary) {
        assertThat(summary)
                .doesNotStartWith("assert 未通过")
                .doesNotContain("assert 未通过")
                .doesNotContain(SECRET)
                .contains("请核对");
    }

    private String summary(Map<String, Object> response, Map<String, Object> spec) {
        LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
        vars.put("response", response);
        try {
            runner.run(new Step("assert", spec), vars, null, null, () -> { }, new BasicCookieStore(), null, Long.MAX_VALUE);
        } catch (RuleProblem problem) {
            return problem.summary;
        }
        throw new AssertionError("assert did not fail");
    }

    private static Map<String, Object> response(int status, String body, String redirect, Map<String, Object> headers) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("body", body);
        response.put("redirect", redirect);
        response.put("url", "http://127.0.0.1/app");
        response.put("headers", headers);
        return response;
    }
}
