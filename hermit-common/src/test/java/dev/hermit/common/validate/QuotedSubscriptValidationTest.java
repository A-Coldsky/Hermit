package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.Test;

import dev.hermit.common.parse.ConchParser;

class QuotedSubscriptValidationTest {

    @Test
    void hyphenatedResponseHeaderSavesAndRootBracketDoesNotRenameTheVariable() {
        ValidationResult saved = validate(yaml(
                "{{response.headers['content-type']}}",
                """
                      - set:
                          kind: "{{response.headers['content-type']}}"
                """));
        assertThat(saved.errors()).isEmpty();

        ValidationResult unquoted = validate(yaml(
                "{{response.headers.content-type}}",
                """
                      - request: {method: GET, path: /p}
                """));
        assertThat(unquoted.errors())
                .extracting(ValidationIssue::code, ValidationIssue::message)
                .contains(tuple(
                        ValidationCodes.TEMPLATE_SYNTAX,
                        "response.headers.content: 模板语法错误"));

        ValidationResult root = validate(yaml(
                "ready",
                """
                      - extract:
                          row: {jsonpath: $.row}
                          items: {jsonpath: $.items}
                      - set:
                          cell: "{{row['a-b']}}"
                          first: "{{items[0].id}}"
                """));
        assertThat(root.errors())
                .extracting(ValidationIssue::code, ValidationIssue::message)
                .contains(
                        tuple(ValidationCodes.UNDECLARED_VARIABLE, "引用了未定义的变量 row['a-b']。"),
                        tuple(ValidationCodes.UNDECLARED_VARIABLE, "引用了未定义的变量 items[0].id。"));
    }

    @Test
    void quotedCredentialInOutputIsRejectedWithoutTheValue() {
        ValidationResult leaked = validate(yaml(
                "{{credential['password']}}",
                """
                      - request: {method: GET, path: /p}
                """));
        assertThat(leaked.errors())
                .extracting(ValidationIssue::code, ValidationIssue::path, ValidationIssue::message)
                .contains(tuple(
                        ValidationCodes.CREDENTIAL_IN_OUTPUT,
                        "actions.ping.output.kind",
                        "返回值不得引用凭证"));
        assertThat(leaked.errors())
                .extracting(ValidationIssue::message)
                .noneMatch(message -> message.contains("s3cret") || message.contains("BEGIN"));
    }

    private static ValidationResult validate(String yaml) {
        return ConchValidator.validate(ConchParser.parse(yaml), ConchValidator.Context.of(null));
    }

    private static String yaml(String value, String steps) {
        return """
                conch: header_lab
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
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
                """.formatted(value, steps);
    }
}
