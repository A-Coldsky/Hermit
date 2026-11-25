package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

/** 授权器（driver: auth）和 auth.use：写法、版本门槛、两类包各自不能写什么，以及旧包的规范 JSON 不变。 */
class AuthenticatorValidationTest {

    /** 现在的兼容版本。授权器的运行时合入之前不升。 */
    private static final SemVer RELEASED = SemVer.parse("1.12.0");
    private static final ConchValidator.Context NEXT = ConchValidator.Context.of(SemVer.parse("1.13.0"));

    private static final String AUTHENTICATOR = """
            conch: corp_sso
            version: 1.2.0
            driver: auth
            min_crab_version: 1.13.0
            title: 公司统一登录
            description: 公司 SSO 的登录
            credentials:
              username: { type: text, label: 用户名 }
              password: { type: secret, label: 密码 }
            auth:
              origins: ["https://sso.corp.example"]
              steps:
                - request:
                    method: POST
                    url: https://sso.corp.example/login
                    form: { u: "{{credential.username}}", p: "{{credential.password}}" }
                - extract: { access_token: "$.token" }
              alive_check:
                request:
                  method: GET
                  url: https://sso.corp.example/me
                  headers: { Authorization: "Bearer {{login.access_token}}" }
              exports:
                access_token: { description: 访问令牌 }
            """;

    private static final String CONSUMER = """
            conch: corp_oa
            version: 1.0.0
            driver: http
            min_crab_version: 1.13.0
            base_url: https://oa.corp.example
            title: OA
            description: 公司 OA
            auth:
              use: corp_sso
              min_version: 1.2.0
              exchange:
                - request:
                    method: POST
                    path: /api/ticket
                    headers: { Authorization: "Bearer {{auth.access_token}}" }
                - extract: { ticket: "$.ticket" }
              alive_check:
                request: { method: GET, path: /api/me }
            actions:
              list_todos:
                title: 待办
                description: 列出待办
                method: GET
                output:
                  marker: { type: string, description: 标记, value: ok }
                examples:
                  - input: {}
                    output: { marker: ok }
                steps:
                  - request:
                      method: GET
                      path: /api/todos
                      headers: { X-Ticket: "{{login.ticket}}", Authorization: "Bearer {{auth.access_token}}" }
            """;

    @Test
    void authenticatorAndConsumerAreCleanOnOneThirteen() {
        ValidationResult authenticator = validate(AUTHENTICATOR);
        assertThat(authenticator.errors()).isEmpty();
        assertThat(authenticator.warnings()).noneMatch(issue -> issue.code().equals(ValidationCodes.AUTHENTICATOR));
        ConchDoc doc = ConchParser.parse(AUTHENTICATOR);
        assertThat(AuthenticatorChecks.isAuthenticator(doc)).isTrue();
        assertThat(doc.auth().exports().get("access_token").description()).isEqualTo("访问令牌");

        ValidationResult consumer = validate(CONSUMER);
        assertThat(consumer.errors()).isEmpty();
        ConchDoc biz = ConchParser.parse(CONSUMER);
        assertThat(AuthenticatorChecks.uses(biz)).isTrue();
        assertThat(biz.credentials()).isNullOrEmpty();
        assertThat(biz.auth().exchange()).hasSize(2);
        assertThat(AuthenticatorChecks.referencedExports(biz)).containsExactly("access_token");
    }

    @Test
    void currentCrabStillRejectsBothKindsOfPackage() {
        // 运行时在后续版本才有；兼容版本升上去之前，新包必须按版本过高被拒绝。
        for (String yaml : List.of(AUTHENTICATOR, CONSUMER)) {
            ValidationResult result = ConchValidator.validate(
                    ConchParser.parse(yaml), ConchValidator.Context.of(RELEASED));
            assertThat(result.errors()).anySatisfy(issue -> {
                assertThat(issue.code()).isEqualTo(ValidationCodes.MIN_CRAB_VERSION);
                assertThat(issue.message()).contains("高于当前 Crab");
            });
        }
    }

    @Test
    void needsMinCrabVersionOneThirteen() {
        assertThat(validate(AUTHENTICATOR.replace("min_crab_version: 1.13.0", "min_crab_version: 1.12.0")).errors())
                .anyMatch(issue -> issue.code().equals(ValidationCodes.MIN_CRAB_VERSION)
                        && issue.message().contains("driver 为 auth"));
        assertThat(validate(CONSUMER.replace("min_crab_version: 1.13.0", "min_crab_version: 1.12.0")).errors())
                .anyMatch(issue -> issue.code().equals(ValidationCodes.MIN_CRAB_VERSION)
                        && issue.message().contains("auth.use"));
        // 空值也算写过：旧版本 Crab 解析到这些键会报未知字段。
        String empty = legacy("1.12.0", "exports: {}");
        assertThat(validate(empty).errors())
                .anyMatch(issue -> issue.code().equals(ValidationCodes.MIN_CRAB_VERSION));
    }

    @Test
    void authenticatorCannotCarryBusinessFields() {
        String yaml = AUTHENTICATOR
                .replace("title: 公司统一登录", "title: 公司统一登录\nbase_url: https://oa.corp.example\n"
                        + "health_check:\n  - request: { method: GET, path: / }\n"
                        + "groups: { a_b: { title: 组 } }")
                .replace("  exports:", "  use: other_sso\n  min_version: 1.0.0\n  concurrent_reads: true\n"
                        + "  expired_when:\n    - status: 419\n  exchange:\n"
                        + "    - request: { method: GET, url: https://sso.corp.example/x }\n  exports:")
                + """
                actions:
                  ping:
                    title: 探测
                    description: 探测
                    method: GET
                    steps:
                      - request: { method: GET, url: https://sso.corp.example/ping }
                """;
        assertThat(paths(validate(yaml))).contains(
                "base_url", "health_check", "groups", "actions", "auth.use", "auth.min_version",
                "auth.exchange", "auth.concurrent_reads", "auth.expired_when");
    }

    @Test
    void authenticatorNeedsOriginsStepsAndAliveCheck() {
        String bare = """
                conch: corp_sso
                version: 1.0.0
                driver: auth
                min_crab_version: 1.13.0
                title: 登录
                description: 登录
                credentials: {}
                auth:
                  cookies: {}
                """;
        assertThat(paths(validate(bare))).contains("auth.origins", "auth.steps", "auth.alive_check");
        assertThat(paths(validate(bare.replace("auth:\n  cookies: {}\n", "")))).contains("auth");
    }

    @Test
    void authenticatorRequestsMustStayOnDeclaredOrigins() {
        assertThat(paths(validate(AUTHENTICATOR.replace("url: https://sso.corp.example/login", "path: /login"))))
                .contains("auth.steps[0].request.path");
        assertThat(paths(validate(AUTHENTICATOR.replace(
                "url: https://sso.corp.example/login", "url: https://evil.example/login"))))
                .contains("auth.steps[0].request.url");
        assertThat(paths(validate(AUTHENTICATOR.replace("url: https://sso.corp.example/login", "follow_redirects: true"))))
                .contains("auth.steps[0].request");
        assertThat(paths(validate(AUTHENTICATOR
                .replace("url: https://sso.corp.example/login", "url: \"{{config.other}}/login\"")
                .replace("credentials:", "config:\n  other: { type: url, label: 别的地址 }\ncredentials:"))))
                .contains("auth.steps[0].request.url");
        assertThat(paths(validate(AUTHENTICATOR.replace("url: https://sso.corp.example/me", "path: /me"))))
                .contains("auth.alive_check.request.path");
        assertThat(paths(validate(AUTHENTICATOR.replace(
                "url: https://sso.corp.example/me", "url: https://evil.example/me"))))
                .contains("auth.alive_check.request.url");
        assertThat(paths(validate(AUTHENTICATOR.replace(
                "url: https://sso.corp.example/me", "url: \"{{login.access_token}}\""))))
                .contains("auth.alive_check.request.url");
    }

    @Test
    void exportsMustComeFromLoginStepsOrCookies() {
        assertThat(validate(AUTHENTICATOR.replace("access_token: { description: 访问令牌 }",
                "missing: { description: 没有 }")).errors())
                .anyMatch(issue -> issue.path().equals("auth.exports.missing"));
        assertThat(validate(AUTHENTICATOR.replace("access_token: { description: 访问令牌 }",
                "\"bad-name\": {}")).errors())
                .anyMatch(issue -> issue.path().equals("auth.exports.bad-name"));
        String viaCookie = AUTHENTICATOR.replace("  exports:\n    access_token: { description: 访问令牌 }",
                "  cookies: { sid: SESSIONID }\n  exports:\n    sid:");
        assertThat(validate(viaCookie).errors()).isEmpty();
        // 一期没有降级开关：导出值一律是秘密。
        assertThatThrownBy(() -> ConchParser.parse(AUTHENTICATOR.replace(
                "{ description: 访问令牌 }", "{ description: 访问令牌, secret: false }")))
                .isInstanceOf(ConchParseException.class);
    }

    @Test
    void aliveCheckThatIgnoresExportsOnlyWarns() {
        ValidationResult result = validate(AUTHENTICATOR.replace(
                "      headers: { Authorization: \"Bearer {{login.access_token}}\" }\n", ""));
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).anyMatch(issue -> issue.code().equals(ValidationCodes.AUTHENTICATOR)
                && issue.path().equals("auth.alive_check.request"));
    }

    @Test
    void allowHostsAcceptsExactAndSubdomainWildcardsOnly() {
        String ok = withAllowHosts("\"https://oa.corp.example\", \"https://*.corp.example.com:8443\", \"{{config.extra}}\"")
                .replace("credentials:", "config:\n  extra: { type: url, label: 额外地址 }\ncredentials:");
        assertThat(validate(ok).errors()).isEmpty();
        for (String bad : new String[] {
                "*", "https://*", "https://*.com", "https://*.corp.example.com/path", "https://a.*.example.com",
                "https://user:pw@oa.corp.example", "https://u@*.corp.example.com", "oa.corp.example",
                "https://oa.corp.example/app", "{{config.missing}}"}) {
            assertThat(validate(withAllowHosts("\"" + bad + "\"")).errors()).as(bad)
                    .anyMatch(issue -> issue.path().equals("auth.allow_hosts[0]"));
        }
    }

    @Test
    void consumerCannotKeepItsOwnLogin() {
        String yaml = CONSUMER
                .replace("title: OA", "title: OA\ncredentials:\n  password: { type: secret, label: 密码 }")
                .replace("  use: corp_sso", "  use: corp_sso\n  origins: [\"https://sso.corp.example\"]\n"
                        + "  steps:\n    - request: { method: GET, path: /login }\n"
                        + "  exports:\n    ticket:\n  allow_hosts: [\"https://oa.corp.example\"]");
        assertThat(paths(validate(yaml))).contains(
                "credentials", "auth.steps", "auth.origins", "auth.exports", "auth.allow_hosts");
        assertThat(paths(validate(CONSUMER.replace("use: corp_sso", "use: corp_oa")))).contains("auth.use");
        assertThat(paths(validate(CONSUMER.replace("use: corp_sso", "use: Bad-Name")))).contains("auth.use");
        assertThat(paths(validate(CONSUMER.replace("min_version: 1.2.0", "min_version: ^1.2.0"))))
                .contains("auth.min_version");
    }

    @Test
    void exchangeCannotAskOrReadCredentials() {
        ValidationResult ask = validate(CONSUMER.replace("    - extract: { ticket: \"$.ticket\" }",
                "    - extract: { ticket: \"$.ticket\" }\n    - ask: { into: code, prompt: 验证码 }"));
        assertThat(ask.errors()).anyMatch(issue -> issue.path().startsWith("auth.exchange[2].ask"));
        ValidationResult credential = validate(CONSUMER.replace(
                "headers: { Authorization: \"Bearer {{auth.access_token}}\" }\n    - extract",
                "headers: { Authorization: \"{{credential.password}}\" }\n    - extract"));
        assertThat(credential.errors()).anyMatch(issue -> issue.code().equals(ValidationCodes.UNDECLARED_CREDENTIAL)
                && issue.path().startsWith("auth.exchange[0]"));
    }

    @Test
    void exchangeCannotTransferFiles() {
        for (String key : new String[] {"download: true", "multipart: { f: { text: x } }"}) {
            ValidationResult result = validate(CONSUMER.replace(
                    "path: /api/ticket\n", "path: /api/ticket\n        " + key + "\n"));
            assertThat(result.errors()).as(key).anyMatch(issue -> issue.path().equals("auth")
                    && issue.message().equals(FileTransfers.AUTH_STEPS));
        }
    }

    @Test
    void authVariablesAreReservedOnlyWhenAnAuthenticatorIsUsed() {
        assertThat(validate(CONSUMER.replace("{{login.ticket}}", "{{auth.access_token | default('')}}")).errors())
                .anyMatch(issue -> issue.code().equals(ValidationCodes.LOGIN_DEFAULT));
        assertThat(validate(CONSUMER.replace("{{login.ticket}}", "{{auth}}")).errors())
                .anyMatch(issue -> issue.message().contains("auth.名字"));
        assertThat(validate(CONSUMER.replace("- extract: { ticket: \"$.ticket\" }",
                "- extract: { ticket: \"$.ticket\", auth: \"$.x\" }")).errors())
                .anyMatch(issue -> issue.code().equals(ValidationCodes.RESERVED_VARIABLE));
        // 没有 auth.use 的旧包：auth 只是普通变量名，照旧可用。
        String old = legacy("1.0.0", "cookies: {}").replace(
                "- request: { method: GET, path: /api }",
                "- request: { method: GET, path: /api }\n      - extract: { auth: \"$.a\" }\n"
                        + "      - request: { method: GET, path: \"/api/{{auth}}\" }");
        assertThat(ConchValidator.validate(ConchParser.parse(old), ConchValidator.Context.of(RELEASED))
                .errors()).isEmpty();
        // 没有 auth.use 时 {{auth.x}} 仍是未声明变量。
        assertThat(validate(legacy("1.13.0", "cookies: {}").replace("path: /api }", "path: \"/{{auth.x}}\" }")).errors())
                .anyMatch(issue -> issue.code().equals(ValidationCodes.UNDECLARED_VARIABLE));
    }

    @Test
    void mcpPackagesCannotUseAnAuthenticator() {
        String mcp = """
                conch: corp_mcp
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.13.0
                title: MCP
                description: MCP
                credentials: {}
                upstream: { url: "https://mcp.corp.example/mcp" }
                auth:
                  use: corp_sso
                """;
        assertThat(paths(validate(mcp))).contains("auth");
    }

    @Test
    void linkChecksTheReferencedAuthenticator() {
        ConchDoc consumer = ConchParser.parse(CONSUMER);
        ConchDoc authenticator = ConchParser.parse(AUTHENTICATOR);
        assertThat(AuthenticatorChecks.link(consumer, authenticator)).isEmpty();
        assertThat(AuthenticatorChecks.link(consumer, null)).extracting(ValidationIssue::path)
                .containsExactly("auth.use");
        assertThat(AuthenticatorChecks.link(consumer, consumer)).extracting(ValidationIssue::message)
                .anyMatch(message -> message.contains("不是授权器"));
        assertThat(AuthenticatorChecks.link(consumer,
                ConchParser.parse(AUTHENTICATOR.replace("version: 1.2.0", "version: 1.1.9"))))
                .extracting(ValidationIssue::path).containsExactly("auth.min_version");
        ConchDoc renamed = ConchParser.parse(AUTHENTICATOR.replace("access_token", "token"));
        assertThat(AuthenticatorChecks.link(consumer, renamed)).extracting(ValidationIssue::message)
                .anyMatch(message -> message.contains("没有导出变量 access_token"));
        assertThat(AuthenticatorChecks.link(ConchParser.parse(legacy("1.0.0", "cookies: {}")), null)).isEmpty();
    }

    @Test
    void scriptReadsOfAuthVariablesCountAsReferences() {
        ConchDoc doc = ConchParser.parse(CONSUMER.replace("    - extract: { ticket: \"$.ticket\" }",
                "    - extract: { ticket: \"$.ticket\" }\n    - script:\n        credential: true\n        into: sig\n"
                        + "        code: \"return vars.auth.refresh_token + '.';\""));
        assertThat(AuthenticatorChecks.referencedExports(doc)).containsExactly("access_token", "refresh_token");
    }

    @Test
    void bareVariableNamesInConditionsCountAsReferences() {
        String yaml = CONSUMER.replace("    - extract: { ticket: \"$.ticket\" }",
                "    - extract: { ticket: \"$.ticket\" }\n    - assert: { present: auth.refresh_token }\n"
                        + "    - assert: { not_equals: { auth.id_token: \"\" } }");
        assertThat(validate(yaml).errors()).isEmpty();
        ConchDoc doc = ConchParser.parse(yaml);
        assertThat(AuthenticatorChecks.referencedExports(doc))
                .containsExactlyInAnyOrder("access_token", "refresh_token", "id_token");
        assertThat(AuthenticatorChecks.link(doc, ConchParser.parse(AUTHENTICATOR)))
                .extracting(ValidationIssue::message)
                .anyMatch(message -> message.contains("没有导出变量 refresh_token"));
    }

    @Test
    void legacyPackagesKeepTheirCanonicalJson() throws Exception {
        ConchDoc plain = ConchParser.parse(legacy("1.0.0", "cookies: {}"));
        JsonMapper canonical = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        String json = canonical.writeValueAsString(plain);
        assertThat(json).doesNotContain("\"use\"").doesNotContain("minVersion").doesNotContain("exports")
                .doesNotContain("allowHosts").doesNotContain("exchange").doesNotContain("sharedDeclared");
        assertThat(plain.auth().sharedDeclared()).isFalse();
        // 新字段写了才进规范 JSON，并参与相等性。
        ConchDoc consumer = ConchParser.parse(CONSUMER);
        assertThat(canonical.writeValueAsString(consumer)).contains("\"use\":\"corp_sso\"")
                .contains("\"minVersion\":\"1.2.0\"").contains("\"exchange\"").doesNotContain("sharedDeclared");
        assertThat(consumer).isNotEqualTo(ConchParser.parse(CONSUMER.replace("use: corp_sso", "use: other_sso")));
        assertThat(canonical.writeValueAsString(ConchParser.parse(AUTHENTICATOR)))
                .contains("\"exports\":{\"access_token\":{\"description\":\"访问令牌\"}}");
    }

    private static String withAllowHosts(String items) {
        return AUTHENTICATOR.replace("  exports:", "  allow_hosts: [" + items + "]\n  exports:");
    }

    private static String legacy(String minCrab, String authLine) {
        return """
                conch: demo_site
                version: 1.0.0
                driver: http
                min_crab_version: %s
                base_url: https://demo.example
                title: 演示
                description: 演示
                credentials: {}
                auth:
                  %s
                  steps:
                    - request: { method: GET, path: /login }
                actions:
                  ping:
                    title: 探测
                    description: 探测
                    method: GET
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    steps:
                      - request: { method: GET, path: /api }
                """.formatted(minCrab, authLine);
    }

    private static List<String> paths(ValidationResult result) {
        return result.errors().stream().map(ValidationIssue::path).toList();
    }

    private static ValidationResult validate(String yaml) {
        return ConchValidator.validate(ConchParser.parse(yaml), NEXT);
    }
}
