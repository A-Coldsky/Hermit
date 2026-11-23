package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hermit.common.http.AuthOrigins;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

/** auth.origins：写法、版本门槛、哪些步骤能访问认证地址，以及它怎样计入包的地址范围。 */
class AuthOriginsValidationTest {

    private static final ConchValidator.Context CURRENT = ConchValidator.Context.of(SemVer.parse("1.11.0"));

    private static final String GUARDED = """
            - request: { method: GET, path: /, follow_redirects: true }
            - if:
                contains: 'name="password"'
                then:
                  - request:
                      method: POST
                      url: https://sso.example/login
                      form: { u: "{{credential.username}}", p: "{{credential.password}}" }
                      follow_redirects: true
            """;

    @Test
    void declaredOriginWithAGuardedLoginIsClean() {
        ValidationResult result = validate(yaml("1.11.0", "origins: [\"https://sso.example\"]", GUARDED, "", ""));
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).noneMatch(issue -> issue.code().equals(ValidationCodes.AUTH_ORIGIN));
    }

    @Test
    void needsCrabVersionOneEleven() {
        ValidationResult result = validate(yaml("1.10.0", "origins: [\"https://sso.example\"]", GUARDED, "", ""));
        assertThat(result.errors()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo(ValidationCodes.MIN_CRAB_VERSION);
            assertThat(issue.message()).contains("auth.origins").contains("1.11.0");
        });
        // 没写 auth.origins 的旧包不受影响。
        assertThat(validate(yaml("1.0.0", "cookies: {}", "- request: { method: GET, path: / }", "", "")).errors())
                .isEmpty();
    }

    @Test
    void malformedEntriesAreRejected() {
        for (String bad : new String[] {
                "https://sso.example/cas", "https://sso.example?x=1", "sso.example", "ftp://sso.example",
                "https://user:pw@sso.example", "https://{{config.sso}}.example", "\"\""}) {
            ValidationResult result = validate(yaml("1.11.0", "origins: [" + quote(bad) + "]", GUARDED, "", ""));
            assertThat(result.errors()).as(bad)
                    .anyMatch(issue -> issue.code().equals(ValidationCodes.AUTH_ORIGIN)
                            && issue.path().equals("auth.origins[0]"));
        }
        ValidationResult many = validate(yaml("1.11.0",
                "origins: [\"https://a.example\", \"https://b.example\", \"https://c.example\","
                        + " \"https://d.example\", \"https://e.example\"]", GUARDED, "", ""));
        assertThat(many.errors()).anyMatch(issue -> issue.path().equals("auth.origins"));
        assertThatThrownBy(() -> ConchParser.parse(yaml("1.11.0", "origins: https://sso.example", GUARDED, "", "")))
                .isInstanceOf(ConchParseException.class);
    }

    @Test
    void configReferenceMustBeADeclaredUrlField() {
        String login = GUARDED.replace("https://sso.example/login", "\"{{config.sso_url}}/login\"");
        String origins = "origins: [\"{{config.sso_url}}\"]";
        assertThat(validate(yaml("1.11.0", origins, login, "sso_url: { type: url, label: 认证中心 }", "")).errors())
                .isEmpty();
        assertThat(validate(yaml("1.11.0", origins, login, "", "")).errors())
                .anyMatch(issue -> issue.code().equals(ValidationCodes.AUTH_ORIGIN));
        assertThat(validate(yaml("1.11.0", origins, login, "sso_url: { type: text, label: 认证中心 }", "")).errors())
                .anyMatch(issue -> issue.code().equals(ValidationCodes.AUTH_ORIGIN));
        assertThat(validate(yaml("1.11.0", "origins: [\"{{config.base_url}}\"]", GUARDED,
                "base_url: { type: url, label: 应用 }", "")).errors())
                .anyMatch(issue -> issue.message().contains("应用地址"));
    }

    @Test
    void capabilityStepsAndAliveCheckCannotNameTheAuthOrigin() {
        String action = "- request: { method: GET, url: https://sso.example/profile }";
        ValidationResult result = validate(yaml("1.11.0", "origins: [\"https://sso.example\"]", GUARDED, "", action));
        assertThat(result.errors()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo(ValidationCodes.AUTH_ORIGIN);
            assertThat(issue.path()).isEqualTo("actions.ping.steps[0].request.url");
        });
        String nested = """
                - if:
                    status: 200
                    then:
                      - request: { method: GET, path: "https://sso.example/profile" }
                """;
        assertThat(validate(yaml("1.11.0", "origins: [\"https://sso.example\"]", GUARDED, "", nested)).errors())
                .anyMatch(issue -> issue.path().equals("actions.ping.steps[0].if.then[0].request.path"));
        String alive = "origins: [\"https://sso.example\"]\n  alive_check:\n"
                + "    request: { method: GET, url: https://sso.example/me }";
        assertThat(validate(yaml("1.11.0", alive, GUARDED, "", "")).errors())
                .anyMatch(issue -> issue.path().equals("auth.alive_check.request.url"));
    }

    @Test
    void warnsAboutUnconditionalCredentialsAndUndeclaredHosts() {
        String direct = """
                - request:
                    method: POST
                    url: https://sso.example/login
                    form: { u: "{{credential.username}}", p: "{{credential.password}}" }
                """;
        ValidationResult unconditional = validate(yaml("1.11.0", "origins: [\"https://sso.example\"]", direct, "", ""));
        assertThat(unconditional.errors()).isEmpty();
        assertThat(unconditional.warnings()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo(ValidationCodes.AUTH_ORIGIN);
            assertThat(issue.message()).contains("每次登录都向认证地址提交凭证");
        });
        ValidationResult undeclared = validate(yaml("1.11.0", "origins: [\"https://other.example\"]", GUARDED, "", ""));
        assertThat(undeclared.warnings()).anySatisfy(issue -> {
            assertThat(issue.path()).isEqualTo("auth.steps[1].if.then[0].request.url");
            assertThat(issue.message()).contains("https://sso.example:443").contains("auth.origins");
        });
        ValidationResult same = validate(yaml("1.11.0", "origins: [\"https://app.example\"]",
                "- request: { method: GET, path: / }", "", ""));
        assertThat(same.warnings()).anyMatch(issue -> issue.message().contains("同一个地址"));
    }

    @Test
    void mcpPackagesCannotDeclareAuthOrigins() {
        ConchDoc doc = ConchParser.parse("""
                conch: remote_tools
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.11.0
                title: 标题
                description: 说明
                upstream:
                  url: https://mcp.example/mcp
                auth:
                  origins: ["https://sso.example"]
                actions: {}
                """);
        assertThat(ConchValidator.validate(doc, CURRENT).errors())
                .anyMatch(issue -> issue.path().equals("auth"));
    }

    @Test
    void emptyOriginsLeaveTheCanonicalDocumentUntouched() {
        ConchDoc plain = ConchParser.parse(yaml("1.0.0", "cookies: {}", "- request: { method: GET, path: / }", "", ""));
        ConchDoc empty = ConchParser.parse(yaml("1.0.0", "origins: []", "- request: { method: GET, path: / }", "", ""));
        assertThat(empty).isEqualTo(plain);
        assertThat(AuthOrigins.used(empty)).isFalse();
    }

    @Test
    void effectiveOriginsFollowTheConfiguredValue() {
        ConchDoc doc = ConchParser.parse(yaml("1.11.0",
                "origins: [\"https://SSO.example/\", \"{{ config.sso_url }}\"]", GUARDED,
                "sso_url: { type: url, label: 认证中心, default: \"https://login.example/cas\" }", ""));
        assertThat(AuthOrigins.declared(doc)).containsExactly("https://sso.example:443", "https://login.example:443");
        assertThat(AuthOrigins.effective(doc, Map.of("sso_url", "http://10.0.0.8:8080/cas/")))
                .containsExactly("https://sso.example:443", "http://10.0.0.8:8080");
        assertThat(AuthOrigins.effective(doc, Map.of("sso_url", "not a url")))
                .containsExactly("https://sso.example:443");
        assertThat(AuthOrigins.effective(doc, Map.of())).containsExactly("https://sso.example:443");
    }

    @Test
    void authOriginsCountTowardThePackageBoundary() {
        ConchDoc before = ConchParser.parse(yaml("1.11.0", "cookies: {}", "- request: { method: GET, path: / }", "", ""));
        ConchDoc literal = ConchParser.parse(yaml("1.11.0", "origins: [\"https://sso.example\"]", GUARDED, "", ""));
        ConchDestinations.Boundary added = ConchDestinations.between(before, literal);
        assertThat(added.sameBoundary()).isFalse();
        assertThat(added.addedDestinations()).containsExactly("https://sso.example:443");
        assertThat(ConchDestinations.between(literal, before).sameBoundary()).isTrue();

        // 引用配置项：以它开头的登录地址不算「无法静态确定」，默认值计入范围。
        String login = GUARDED.replace("https://sso.example/login", "\"{{config.sso_url}}/login\"");
        ConchDoc configured = ConchParser.parse(yaml("1.11.0", "origins: [\"{{config.sso_url}}\"]", login,
                "sso_url: { type: url, label: 认证中心, default: \"https://login.example\" }", ""));
        ConchDestinations.Boundary viaConfig = ConchDestinations.between(before, configured);
        assertThat(viaConfig.undetermined()).isFalse();
        assertThat(viaConfig.addedDestinations()).containsExactly("https://login.example:443");

        // 没声明成认证地址的配置项拼出来的主机，仍然是无法静态确定。
        ConchDoc loose = ConchParser.parse(yaml("1.11.0", "cookies: {}", login,
                "sso_url: { type: url, label: 认证中心 }", ""));
        assertThat(ConchDestinations.between(before, loose).undetermined()).isTrue();
    }

    private static ValidationResult validate(String yaml) {
        return ConchValidator.validate(ConchParser.parse(yaml), CURRENT);
    }

    private static String quote(String text) {
        return text.startsWith("\"") ? text : "\"" + text + "\"";
    }

    private static String yaml(String minCrab, String authHead, String loginSteps, String config, String actionSteps) {
        String steps = actionSteps.isBlank() ? "- request: { method: GET, path: /ping }" : actionSteps;
        String configBlock = config.isBlank() ? "" : "config:\n  " + config;
        return """
                conch: demo_status
                version: 1.0.0
                driver: http
                min_crab_version: %s
                base_url: https://app.example
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                %s
                auth:
                  %s
                  steps:
                %s
                actions:
                  ping:
                    title: 探测
                    description: 返回标记。
                    steps:
                %s
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 本地
                    related: []
                """.formatted(minCrab, configBlock, authHead,
                loginSteps.stripIndent().indent(4).stripTrailing(),
                steps.stripIndent().indent(6).stripTrailing());
    }
}
