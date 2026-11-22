package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ValidationIssue;

class LiteralCredentialWarningsTest {

    @Test
    void skipsCookieAndHeaderExistenceChecksInsideFlowAndNestedAssertSteps() {
        ConchDoc doc = ConchParser.parse("""
                conch: flow_warn_clean
                version: 1.0.0
                driver: http
                min_crab_version: 1.9.0
                title: 标题
                description: 说明
                credentials: {}
                auth:
                  steps:
                    - request: { method: GET, path: /login }
                    - if:
                        cookie_exists: JSESSIONID
                        header_exists: Set-Cookie
                        then:
                          - assert:
                              cookie_exists: SID
                              header_exists: X-Cookie-Flag
                        else:
                          - assert:
                              cookie_exists: ALT_SID
                actions:
                  run:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: v, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - repeat:
                          max: 3
                          collect:
                            all_cookies: page_cookies
                            password_policies: policies
                          steps:
                            - request: { method: GET, path: /poll }
                            - extract:
                                password: { jsonpath: "$.label" }
                            - assert:
                                cookie_exists: SESSION
                                header_exists: X-Auth
                          until:
                            cookie_exists: DONE_COOKIE
                            header_exists: X-Done
                """);
        assertThat(LiteralCredentialWarnings.scan(doc)).isEmpty();
    }

    @Test
    void detectsLiteralSecretsInsideFlowConditionsAndNestedBranches() {
        ConchDoc doc = ConchParser.parse("""
                conch: flow_warn_hits
                version: 1.0.0
                driver: http
                min_crab_version: 1.9.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  run:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: v, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - if:
                          equals:
                            password: hardcoded-pass
                          then:
                            - request:
                                method: POST
                                path: /submit
                                form:
                                  password: literal-form-pass
                          else:
                            - request:
                                method: GET
                                path: /alt
                                headers:
                                  Cookie: "sid=literal-cookie"
                      - repeat:
                          max: 2
                          steps:
                            - request:
                                method: GET
                                path: /poll
                                headers:
                                  X-Token: "Bearer token12345678"
                          until:
                            contains: "Bearer until12345678"
                """);
        List<ValidationIssue> issues = LiteralCredentialWarnings.scan(doc);
        assertThat(issues).allSatisfy(issue -> assertThat(issue.code()).isEqualTo(LiteralCredentialWarnings.CODE));
        assertThat(issues).extracting(ValidationIssue::path).containsExactlyInAnyOrder(
                "actions.run.steps[0].if.equals.password",
                "actions.run.steps[0].if.then[0].request.form.password",
                "actions.run.steps[0].if.else[0].request.headers.Cookie",
                "actions.run.steps[1].repeat.until.contains",
                "actions.run.steps[1].repeat.steps[0].request.headers.X-Token");
    }
}
