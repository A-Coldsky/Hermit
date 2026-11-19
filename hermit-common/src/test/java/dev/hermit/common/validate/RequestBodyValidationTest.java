package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.Test;

import dev.hermit.common.McpFixtures;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

class RequestBodyValidationTest {

    @Test
    void bodyAndXmlEscapeRequireOneSevenOnLoginAndCapabilitySteps() {
        ValidationResult low = validate(http("1.6.0", """
                auth:
                  steps:
                    - request:
                        method: POST
                        path: /login
                        headers:
                          Content-Type: application/xml
                        body: "<object><string name=\\"pw\\">{{credential.password | xml_escape}}</string></object>"
                  alive_check:
                    request:
                      method: POST
                      path: /alive
                      body: "<object/>"
                """, """
                      - request:
                          method: POST
                          path: /list
                          headers:
                            Content-Type: text/xml
                          body: "<object><int name=\\"fid\\">{{input.fid}}</int></object>"
                """));
        assertThat(messages(low)).containsExactly(RequestBodies.BODY_VERSION, RequestBodies.XML_ESCAPE_VERSION);
        assertThat(low.warnings())
                .extracting(ValidationIssue::code, ValidationIssue::path)
                .contains(tuple(ValidationCodes.BODY_CONTENT_TYPE, "auth.alive_check.request.body"));

        ValidationResult current = validate(http("1.7.0", """
                auth:
                  steps:
                    - request:
                        method: POST
                        path: /login
                        headers:
                          Content-Type: application/xml
                        body: "<object><string name=\\"pw\\">{{credential.password | xml_escape}}</string></object>"
                """, """
                      - request:
                          method: POST
                          path: /list
                          headers:
                            content-type: text/xml
                          body: "<object><int name=\\"fid\\">{{input.fid}}</int><string name=\\"order\\">{{input.order | xml_escape}}</string></object>"
                """));
        assertThat(current.errors()).isEmpty();
        assertThat(current.warnings()).isEmpty();

        ValidationResult prerelease = validate(http("1.7.0-rc.1", "", """
                      - request:
                          method: POST
                          path: /list
                          headers:
                            Content-Type: application/xml
                          body: "<object/>"
                """));
        assertThat(messages(prerelease)).containsExactly(RequestBodies.BODY_VERSION);
    }

    @Test
    void bodyFormAndJsonAreExclusiveAndBodyMustBeText() {
        ValidationResult both = validate(http("1.7.0", "", """
                      - request:
                          method: POST
                          path: /list
                          form: {a: 1}
                          json: {b: 2}
                """));
        assertThat(both.errors())
                .extracting(ValidationIssue::code, ValidationIssue::path, ValidationIssue::message)
                .containsExactly(tuple(
                        ValidationCodes.REQUEST_BODY,
                        "actions.list.steps[0].request",
                        RequestBodies.EXCLUSIVE));

        ValidationResult mixed = validate(http("1.6.0", "", """
                      - request:
                          method: POST
                          path: /list
                          body: "<object/>"
                          json: {b: 2}
                """));
        assertThat(messages(mixed)).containsExactly(RequestBodies.BODY_VERSION, RequestBodies.EXCLUSIVE);
        assertThat(mixed.warnings()).extracting(ValidationIssue::code).doesNotContain(ValidationCodes.BODY_CONTENT_TYPE);

        ValidationResult number = validate(http("1.6.0", "", """
                      - request:
                          method: POST
                          path: /list
                          body: 1
                """));
        assertThat(number.errors())
                .extracting(ValidationIssue::code, ValidationIssue::message)
                .containsExactly(
                        tuple(ValidationCodes.MIN_CRAB_VERSION, RequestBodies.BODY_VERSION),
                        tuple(ValidationCodes.REQUEST_BODY, RequestBodies.BODY_TYPE));
    }

    @Test
    void missingContentTypeWarnsAndAHeaderOverrideDoesNotRaiseTheVersion() {
        ValidationResult warned = validate(http("1.7.0", "", """
                      - request:
                          method: POST
                          path: /list
                          headers:
                            Content-Type: "  "
                          body: "<object/>"
                """));
        assertThat(warned.errors()).isEmpty();
        assertThat(warned.warnings())
                .extracting(ValidationIssue::code, ValidationIssue::message)
                .containsExactly(tuple(ValidationCodes.BODY_CONTENT_TYPE, RequestBodies.CONTENT_TYPE));

        ValidationResult form = validate(http("1.0.0", "", """
                      - request:
                          method: POST
                          path: /list
                          headers:
                            Content-Type: application/xml
                          form: {a: 1}
                """));
        assertThat(form.errors()).isEmpty();
        assertThat(form.warnings()).isEmpty();
    }

    @Test
    void xmlEscapeInTemplatesExtractAndMcpHeadersUsesTheSameGate() {
        ValidationResult extract = validate(http("1.6.0", "", """
                      - request: {method: GET, path: /list}
                      - extract:
                          name:
                            css: td
                            filter: trim | xml_escape
                            each:
                              title: {css: h1, filter: xml_escape}
                """));
        assertThat(messages(extract)).containsExactly(RequestBodies.XML_ESCAPE_VERSION);

        ValidationResult output = validate(http("1.6.0", "", """
                      - set: {name: ready}
                """, """
                      echo: {type: string, description: 回显, value: "{{input.order | xml_escape}}"}
                """));
        assertThat(messages(output)).containsExactly(RequestBodies.XML_ESCAPE_VERSION);

        String mcp = McpFixtures.headersYaml().replace(
                "Authorization: 'Bearer {{credential.token}}'",
                "Authorization: 'Bearer {{credential.token | xml_escape}}'");
        ValidationResult forwarded = ConchValidator.validate(ConchParser.parse(mcp), ConchValidator.Context.of(null));
        assertThat(messages(forwarded)).containsExactly(RequestBodies.XML_ESCAPE_VERSION);
        String raised = mcp.replace("min_crab_version: 1.1.0", "min_crab_version: 1.7.0");
        assertThat(ConchValidator.validate(ConchParser.parse(raised), ConchValidator.Context.of(null)).errors()).isEmpty();
    }

    @Test
    void scriptSourceAndALiteralWordDoNotCountAsTheFilter() {
        ValidationResult script = validate(http("1.0.0", "", """
                      - script:
                          code: |
                            var xml_escape = '{{order | xml_escape}}';
                            return xml_escape;
                          into: value
                """));
        assertThat(messages(script)).isEmpty();

        ValidationResult library = validate("""
                conch: mail_box
                version: 1.0.0
                driver: http
                min_crab_version: 1.2.0
                title: 标题
                description: 说明
                scripts:
                  pack: |
                    function pack(text) {
                      return '{{text | xml_escape}}';
                    }
                credentials: {}
                actions:
                  list:
                    title: 查询
                    description: 说明
                    output:
                      value: {type: string, description: 结果, value: ok}
                    examples:
                      - input: {}
                        output: {value: ok}
                    notes: 注意
                    related: []
                    steps:
                      - script:
                          use: [pack]
                          code: |
                            return pack('xml_escape');
                          into: value
                """);
        assertThat(messages(library)).isEmpty();

        ValidationResult literal = validate(http("1.6.0", "", """
                      - request:
                          method: POST
                          path: /list
                          headers:
                            Content-Type: application/xml
                          body: "<note>xml_escape</note>"
                """));
        assertThat(messages(literal)).containsExactly(RequestBodies.BODY_VERSION);
    }

    private static ValidationResult validate(String yaml) {
        return ConchValidator.validate(ConchParser.parse(yaml), ConchValidator.Context.of(SemVer.parse("1.7.0")));
    }

    private static java.util.List<String> messages(ValidationResult result) {
        return result.errors().stream().map(ValidationIssue::message).toList();
    }

    private static String http(String version, String auth, String steps) {
        return http(version, auth, steps, "value: {type: string, description: 结果, value: ok}");
    }

    private static String http(String version, String auth, String steps, String output) {
        String authBlock = auth == null || auth.isBlank() ? "" : auth.stripIndent();
        return """
                conch: mail_box
                version: 1.0.0
                driver: http
                min_crab_version: %s
                title: 标题
                description: 说明
                credentials:
                  password: {type: secret, required: true, label: 密码}
                %s
                actions:
                  list:
                    title: 查询
                    description: 说明
                    input:
                      fid: {type: integer, description: 文件夹}
                      order: {type: string, description: 排序}
                    output:
                      %s
                    examples:
                      - input: {fid: 1, order: receiveDate}
                        output: {value: ok}
                    notes: 注意
                    related: []
                    steps:
                %s
                """.formatted(version, authBlock, output.stripIndent().strip(), steps.stripIndent().indent(6).stripTrailing());
    }
}
