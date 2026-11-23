package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

class SessionExpiryValidationTest {

    private static final SemVer V112 = SemVer.parse("1.12.0");

    @Test
    void validExpiredWhenAliveExpectAndAssertExtensionsPassAt112() {
        String yaml = conch("1.12.0", """
                  expired_when:
                    - json_equals: {"$.code": [401, 10001], "$.ok": false}
                    - json_not_equals: {"$.status": "ok"}
                    - json_exists: "$.needLogin"
                    - contains: "top.location.href='/login'"
                    - regex: "\\"errcode\\"\\\\s*:\\\\s*(401|40001)"
                    - status: 400
                      contains: "TOKEN_EXPIRED"
                    - status: [419, 440]
                    - header_exists: X-Session-Expired
                    - header_contains: {content-type: ["text/html", "application/xhtml"]}
                      contains: "<form"
                  alive_check:
                    request: {method: GET, path: /api/me}
                    expect:
                      status: 200
                      json_equals: {"$.code": 0}
                      json_exists: ["$.data.userId", "$.data.name"]
                      cookie_exists: SID
                """, """
                  - request: {method: GET, path: /api/items}
                  - extract:
                      code: {jsonpath: "$.code", optional: true}
                  - assert:
                      not_equals: {code: "EXPIRED"}
                      present: code
                      on_fail: session_expired
                """);
        ValidationResult result = validate(yaml);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void versionGateRejects1110ForAll112FeaturesIncludingEmptyDeclared() {
        List<String> cases = List.of(
                conch("1.11.0", "  expired_when:\n    - contains: \"请登录\"\n", "- request: {method: GET, path: /a}\n"),
                conch("1.11.0", "  expired_when: []\n", "- request: {method: GET, path: /a}\n"),
                conch("1.11.0", "  expired_when: null\n", "- request: {method: GET, path: /a}\n"),
                conch("1.11.0", "  alive_check:\n    request: {method: GET, path: /me}\n    expect: {status: 200}\n",
                        "- request: {method: GET, path: /a}\n"),
                conch("1.11.0", "  alive_check:\n    request: {method: GET, path: /me}\n    expect: {}\n",
                        "- request: {method: GET, path: /a}\n"),
                conch("1.11.0", "  alive_check:\n    request: {method: GET, path: /me}\n    expect: null\n",
                        "- request: {method: GET, path: /a}\n"),
                conch("1.11.0", "", """
                          - request: {method: GET, path: /a}
                          - assert: {not_contains: "请登录", on_fail: session_expired}
                        """),
                conch("1.11.0", "", """
                          - request: {method: GET, path: /a}
                          - set: {ok: "yes"}
                          - assert: {equals: {ok: "yes"}}
                        """),
                conch("1.11.0", "", """
                          - request: {method: GET, path: /a}
                          - if:
                              status: 200
                              then:
                                - assert: {not_contains: "登录", on_fail: session_expired}
                        """)
        );
        for (String yaml : cases) {
            ValidationResult result = validate(yaml);
            assertThat(result.errors())
                    .as(yaml)
                    .anyMatch(issue -> ValidationCodes.MIN_CRAB_VERSION.equals(issue.code())
                            && issue.message().contains("1.12.0"));
        }
    }

    @Test
    void emptyDeclaredExpiredWhenAndAliveExpectAreAllowedAt112() {
        assertThat(validate(conch("1.12.0", "  expired_when: []\n", "- request: {method: GET, path: /a}\n")).errors())
                .isEmpty();
        assertThat(validate(conch("1.12.0", "  expired_when: null\n", "- request: {method: GET, path: /a}\n")).errors())
                .isEmpty();
        assertThat(validate(conch("1.12.0",
                "  alive_check:\n    request: {method: GET, path: /me}\n    expect: {}\n",
                "- request: {method: GET, path: /a}\n")).errors()).isEmpty();
    }

    @Test
    void expiredWhenRejectsBadShapes2xxOnly5xxBadRegexAndTooManyRules() {
        // 2xx alone in expired_when is forbidden
        assertThat(validate(conch("1.12.0", "  expired_when:\n    - status: 200\n",
                "- request: {method: GET, path: /a}\n")).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("2xx"));

        // 5xx status is forbidden
        assertThat(validate(conch("1.12.0", "  expired_when:\n    - status: 500\n",
                "- request: {method: GET, path: /a}\n")).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("5xx"));

        // status list containing 2xx without any body/header/json predicate is forbidden
        assertThat(validate(conch("1.12.0", "  expired_when:\n    - status: [200, 419]\n",
                "- request: {method: GET, path: /a}\n")).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("2xx"));

        // quoted status is forbidden
        assertThat(validate(conch("1.12.0", "  expired_when:\n    - status: \"419\"\n",
                "- request: {method: GET, path: /a}\n")).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("不要加引号"));

        // empty rule map
        assertThat(validate(conch("1.12.0", "  expired_when:\n    - {}\n",
                "- request: {method: GET, path: /a}\n")).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code()));

        // unknown key gets alias hint (jsonpath -> json_equals)
        assertThat(validate(conch("1.12.0", "  expired_when:\n    - jsonpath: \"$.code\"\n",
                "- request: {method: GET, path: /a}\n")).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("json_equals"));

        // invalid regex
        assertThat(validate(conch("1.12.0", "  expired_when:\n    - regex: \"[unclosed\"\n",
                "- request: {method: GET, path: /a}\n")).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("regex 无法解析"));

        // non-$ jsonpath key
        assertThat(validate(conch("1.12.0", "  expired_when:\n    - json_equals: {code: 401}\n",
                "- request: {method: GET, path: /a}\n")).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("$ 开头"));

        // > 16 rules
        StringBuilder many = new StringBuilder("  expired_when:\n");
        for (int i = 0; i < 17; i++) {
            many.append("    - contains: \"expired_").append(i).append("\"\n");
        }
        assertThat(validate(conch("1.12.0", many.toString(), "- request: {method: GET, path: /a}\n")).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("16"));

        // parser rejects non-list expired_when and non-map alive_check.expect
        assertThatThrownBy(() -> ConchParser.parse(
                conch("1.12.0", "  expired_when: {contains: \"login\"}\n", "- request: {method: GET, path: /a}\n")))
                .isInstanceOf(ConchParseException.class);
        assertThatThrownBy(() -> ConchParser.parse(
                conch("1.12.0", "  alive_check:\n    request: {method: GET, path: /me}\n    expect: [200]\n",
                        "- request: {method: GET, path: /a}\n")))
                .isInstanceOf(ConchParseException.class);
    }

    @Test
    void assertOnFailChecksAuthStepUnknownModeAndMissingCondition() {
        // on_fail in auth.steps is forbidden
        String inAuth = """
                conch: demo_expiry
                version: 1.0.0
                driver: http
                min_crab_version: 1.12.0
                base_url: http://127.0.0.1:9
                title: 示例
                description: 示例说明
                credentials: {}
                auth:
                  steps:
                    - request: {method: GET, path: /login}
                    - assert: {status: 200, on_fail: session_expired}
                actions:
                  list:
                    title: 列表
                    description: 查询
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /list}
                    output: {ok: {type: string, description: 结果, value: ok}}
                    examples: [{input: {}, output: {ok: ok}}]
                    notes: 测试
                    related: []
                """;
        assertThat(validate(inAuth).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("auth.steps"));

        // invalid on_fail value
        assertThat(validate(conch("1.12.0", "", """
                  - request: {method: GET, path: /a}
                  - assert: {status: 200, on_fail: retry}
                """)).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("session_expired"));

        // on_fail without any condition
        assertThat(validate(conch("1.12.0", "", """
                  - request: {method: GET, path: /a}
                  - assert: {on_fail: session_expired}
                """)).errors())
                .anyMatch(i -> ValidationCodes.SESSION_EXPIRY.equals(i.code())
                        && i.message().contains("至少要写一个断言条件"));
    }

    @Test
    void canonicalJsonUnchangedWhenNewFieldsOmitted() throws Exception {
        ConchDoc plain = ConchParser.parse(conch("1.11.0",
                "  alive_check:\n    request: {method: GET, path: /me}\n    expect_not_redirect: /login\n",
                "- request: {method: GET, path: /a}\n"));
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(plain.auth());
        assertThat(json)
                .doesNotContain("expiredWhen")
                .doesNotContain("expiredWhenDeclared")
                .doesNotContain("expectDeclared")
                .doesNotContain("\"expect\":");
    }

    private static ValidationResult validate(String yaml) {
        return ConchValidator.validate(ConchParser.parse(yaml), ConchValidator.Context.of(V112));
    }

    private static String conch(String minCrab, String authExtra, String actionSteps) {
        String indentedSteps = actionSteps.lines()
                .map(line -> line.isBlank() ? "" : "      " + line)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return """
                conch: demo_expiry
                version: 1.0.0
                driver: http
                min_crab_version: %s
                base_url: http://127.0.0.1:9
                title: 示例
                description: 示例说明
                credentials: {}
                auth:
                  steps:
                    - request: {method: GET, path: /login}
                    - assert: {status: 200}
                %sactions:
                  list:
                    title: 列表
                    description: 查询
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                %s
                    output: {ok: {type: string, description: 结果, value: ok}}
                    examples: [{input: {}, output: {ok: ok}}]
                    notes: 测试
                    related: []
                """.formatted(minCrab, authExtra, indentedSteps);
    }
}
