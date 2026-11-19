package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hermit.common.McpFixtures;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

class QuotedSubscriptValidationTest {

    @Test
    void quotedHeaderRequiresOneSixAndSavesWhenTheMinimumIsMet() {
        ValidationResult low = validate(yaml(
                "1.5.0",
                "{{response.headers['content-type']}}",
                """
                      - set:
                          kind: "{{response.headers['content-type']}}"
                """));
        assertThat(low.errors())
                .extracting(ValidationIssue::code, ValidationIssue::path, ValidationIssue::message)
                .contains(tuple(
                        ValidationCodes.MIN_CRAB_VERSION,
                        "min_crab_version",
                        QuotedSubscripts.VERSION));

        ValidationResult saved = validate(yaml(
                "1.6.0",
                "{{response.headers['content-type']}}",
                """
                      - set:
                          kind: '{{response.headers["content-type"]}}'
                """));
        assertThat(saved.errors()).isEmpty();

        ValidationResult plain = validate(yaml(
                "1.0.0",
                "{{response.status}}",
                """
                      - request: {method: GET, path: /p}
                """));
        assertThat(plain.errors()).isEmpty();
    }

    @Test
    void unquotedHyphenAndNumericIndexDoNotRaiseTheQuotedSubscriptVersion() {
        ValidationResult unquoted = validate(yaml(
                "1.0.0",
                "{{response.headers.content-type}}",
                """
                      - request: {method: GET, path: /p}
                """));
        assertThat(unquoted.errors())
                .extracting(ValidationIssue::code, ValidationIssue::message)
                .contains(tuple(
                        ValidationCodes.TEMPLATE_SYNTAX,
                        "response.headers.content: 模板语法错误"));
        assertThat(messages(unquoted)).noneMatch(message -> message.contains("引号下标"));

        ValidationResult index = validate(yaml(
                "1.0.0",
                "ready",
                """
                      - extract:
                          items: {jsonpath: $.items}
                      - set:
                          first: "{{items[0].id}}"
                """));
        assertThat(index.errors())
                .extracting(ValidationIssue::code, ValidationIssue::message)
                .contains(tuple(
                        ValidationCodes.UNDECLARED_VARIABLE,
                        "引用了未定义的变量 items[0].id。"));
        assertThat(messages(index)).noneMatch(message -> message.contains("引号下标"));
    }

    @Test
    void userRootQuotedSubscriptStaysUndeclaredAndReservedRootsAskForADot() {
        ValidationResult user = validate(yaml(
                "1.6.0",
                "ready",
                """
                      - extract:
                          row: {jsonpath: $.row}
                      - set:
                          cell: "{{row['a-b']}}"
                """));
        assertThat(user.errors())
                .extracting(ValidationIssue::code, ValidationIssue::message)
                .containsExactly(tuple(
                        ValidationCodes.UNDECLARED_VARIABLE,
                        "引用了未定义的变量 row['a-b']。"));

        ValidationResult reserved = validate(yaml(
                "1.6.0",
                "ready",
                """
                      - set:
                          secret: "{{credential['password']}}"
                          sid: "{{login['sid']}}"
                          base: "{{config['base_url']}}"
                          page: "{{input['page']}}"
                          clock: "{{now['x']}}"
                """));
        assertThat(reserved.errors())
                .filteredOn(issue -> QuotedSubscripts.USE_DOT.equals(issue.message()))
                .hasSize(5);
        assertThat(messages(reserved)).allMatch(QuotedSubscripts.USE_DOT::equals);

        ValidationResult numeric = validate(yaml(
                "1.0.0",
                "ready",
                """
                      - set:
                          sid: "{{login[0]}}"
                """));
        assertThat(messages(numeric)).containsExactly(QuotedSubscripts.USE_DOT);
    }

    @Test
    void quotedCredentialInOutputKeepsTheCredentialErrorAndAsksForADot() {
        ValidationResult leaked = validate(yaml(
                "1.0.0",
                "{{credential['password']}}",
                """
                      - request: {method: GET, path: /p}
                """));
        assertThat(leaked.errors())
                .extracting(ValidationIssue::code, ValidationIssue::path, ValidationIssue::message)
                .contains(
                        tuple(
                                ValidationCodes.CREDENTIAL_IN_OUTPUT,
                                "actions.ping.output.kind",
                                "返回值不得引用凭证"),
                        tuple(
                                ValidationCodes.MIN_CRAB_VERSION,
                                "min_crab_version",
                                QuotedSubscripts.VERSION),
                        tuple(
                                ValidationCodes.TEMPLATE_SYNTAX,
                                "actions.ping.output.kind.value",
                                QuotedSubscripts.USE_DOT));
        assertThat(messages(leaked)).noneMatch(message -> message.contains("引用了未定义的变量")
                || message.contains("s3cret")
                || message.contains("BEGIN"));
    }

    @Test
    void filterArgumentQuotedSubscriptUsesTheSameGate() {
        ValidationResult low = validate(yaml(
                "1.0.0",
                "{{name | default(response.headers['content-type'])}}",
                """
                      - set:
                          name: ready
                """));
        assertThat(messages(low)).containsExactly(QuotedSubscripts.VERSION);
    }

    @Test
    void oldCrabRejectsThePackageAtInstallWhenTheMinimumIsOneSix() {
        String yaml = yaml(
                "1.6.0",
                "{{response.headers['content-type']}}",
                """
                      - request: {method: GET, path: /p}
                """);
        ValidationResult old = ConchValidator.validate(
                ConchParser.parse(yaml), ConchValidator.Context.of(SemVer.parse("1.5.0")));
        assertThat(messages(old)).containsExactly("min_crab_version 1.6.0 高于当前 Crab 1.5.0");

        ValidationResult current = ConchValidator.validate(
                ConchParser.parse(yaml), ConchValidator.Context.of(SemVer.parse("1.6.0")));
        assertThat(current.errors()).isEmpty();

        ValidationResult undeclaredMinimum = ConchValidator.validate(
                ConchParser.parse(yaml(
                        "1.5.0",
                        "{{response.headers['content-type']}}",
                        """
                              - request: {method: GET, path: /p}
                        """)),
                ConchValidator.Context.of(QuotedSubscripts.MIN_CRAB_VERSION));
        assertThat(messages(undeclaredMinimum)).containsExactly(QuotedSubscripts.VERSION);
    }

    @Test
    void mcpHeadersShareTheGateAndKeepLowerLevelSubscripts() {
        ValidationResult existing = validate(McpFixtures.headersYaml());
        assertThat(existing.errors()).isEmpty();

        String bracket = McpFixtures.headersYaml().replace(
                "Authorization: 'Bearer {{credential.token}}'",
                "Authorization: \"Bearer {{credential['token']}}\"");
        ValidationResult root = validate(bracket);
        assertThat(messages(root)).contains(QuotedSubscripts.USE_DOT, QuotedSubscripts.VERSION);
        assertThat(messages(root)).noneMatch(message -> message.contains("只能引用")
                || message.contains("引用了未定义的变量")
                || message.contains("未声明的凭证"));

        String nested = McpFixtures.headersYaml()
                .replace("min_crab_version: 1.1.0", "min_crab_version: 1.6.0")
                .replace("X-Tenant: '{{config.tenant}}'", "X-Tenant: \"{{config.tenant['x']}}\"");
        assertThat(validate(nested).errors()).isEmpty();

        String nestedLow = nested.replace("min_crab_version: 1.6.0", "min_crab_version: 1.1.0");
        assertThat(messages(validate(nestedLow))).containsExactly(QuotedSubscripts.VERSION);
    }

    private static List<String> messages(ValidationResult result) {
        return result.errors().stream().map(ValidationIssue::message).toList();
    }

    private static ValidationResult validate(String yaml) {
        return ConchValidator.validate(ConchParser.parse(yaml), ConchValidator.Context.of(null));
    }

    private static String yaml(String minCrab, String value, String steps) {
        return """
                conch: header_lab
                version: 1.0.0
                driver: http
                min_crab_version: %s
                title: 标题
                description: 说明
                base_url: https://hr.example/
                credentials:
                  password:
                    type: secret
                    label: 密码
                    required: true
                config: {}
                auth:
                  steps:
                    - request: {method: GET, path: /login}
                  alive_check:
                    request: {method: GET, path: /home}
                actions:
                  ping:
                    title: 查询
                    description: 说明
                    output:
                      kind:
                        type: string
                        description: 类型
                        value: "%s"
                    examples:
                      - input: {}
                        output: {kind: json}
                    notes: 注意
                    related: []
                    steps:
                %s
                """.formatted(minCrab, value, steps);
    }
}
