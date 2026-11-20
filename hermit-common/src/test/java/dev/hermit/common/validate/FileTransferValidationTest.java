package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

/** type: file、multipart、download：1.8.0 起可用，写法不对或版本不够时报错。 */
class FileTransferValidationTest {
    private static final String UPLOAD = """
                  - request:
                      method: POST
                      path: /upload
                      multipart:
                        note: hello
                        attachment: {file: "{{input.fid}}", filename: a.pdf, content_type: application/pdf}
            """;
    private static final String DOWNLOAD = """
                  - request: {method: GET, path: /export, download: {filename: report.pdf}}
            """;

    @Test
    void eachFeatureRequiresOneEight() {
        assertThat(messages(validate(http("1.7.0", "", UPLOAD)))).containsExactly(FileTransfers.MULTIPART_VERSION);
        assertThat(messages(validate(http("1.7.0", "", DOWNLOAD)))).containsExactly(FileTransfers.DOWNLOAD_VERSION);
        assertThat(messages(validate(fileParam(http("1.7.0", "", UPLOAD)))))
                .contains(FileTransfers.FILE_PARAM_VERSION, FileTransfers.MULTIPART_VERSION);

        assertThat(validate(http("1.8.0", "", UPLOAD)).errors()).isEmpty();
        assertThat(validate(http("1.8.0", "", DOWNLOAD)).errors()).isEmpty();
        assertThat(validate(fileParam(http("1.8.0", "", UPLOAD))).errors()).isEmpty();
        // download: false 等于没写。
        assertThat(validate(http("1.7.0", "", """
                      - request: {method: GET, path: /export, download: false}
                """)).errors()).isEmpty();
    }

    @Test
    void anOlderCrabRefusesThePackage() {
        ValidationResult old = ConchValidator.validate(
                ConchParser.parse(http("1.8.0", "", DOWNLOAD)), ConchValidator.Context.of(SemVer.parse("1.7.0")));
        assertThat(old.errors()).isNotEmpty();
    }

    @Test
    void shapesAreChecked() {
        assertThat(messages(validate(http("1.8.0", "", """
                      - request:
                          method: POST
                          path: /upload
                          form: {a: b}
                          multipart: {note: hello}
                """)))).containsExactly(FileTransfers.EXCLUSIVE);
        assertThat(messages(validate(http("1.8.0", "", """
                      - request: {method: POST, path: /upload, multipart: nope}
                """)))).containsExactly(FileTransfers.MULTIPART_TYPE);
        assertThat(messages(validate(http("1.8.0", "", """
                      - request:
                          method: POST
                          path: /upload
                          multipart:
                            attachment: {file: "{{input.fid}}", path: /etc/passwd}
                """)))).containsExactly(FileTransfers.PART_TYPE);
        assertThat(messages(validate(http("1.8.0", "", """
                      - request: {method: GET, path: /export, download: {dir: /tmp}}
                """)))).containsExactly(FileTransfers.DOWNLOAD_TYPE);
    }

    @Test
    void loginStepsCannotTransferFiles() {
        assertThat(messages(validate(http("1.8.0", """
                auth:
                  steps:
                    - request: {method: GET, path: /login, download: true}
                """, DOWNLOAD)))).containsExactly(FileTransfers.AUTH_STEPS);
    }

    @Test
    void fileParamsAreStringsInTheSchema() {
        ConchDoc doc = ConchParser.parse(fileParam(http("1.8.0", "", UPLOAD)));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> schema = (java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) doc.actions().get("list").inputJsonSchema().get("properties")).get("fid");
        assertThat(schema).containsEntry("type", "string");
        assertThat(String.valueOf(schema.get("description"))).startsWith("文件夹").contains("fileId", "base64");
    }

    private static String fileParam(String yaml) {
        return yaml.replace("fid: {type: integer, description: 文件夹}", "fid: {type: file, description: 文件夹}")
                .replace("input: {fid: 1, order: receiveDate}", "input: {fid: f_1, order: receiveDate}");
    }

    private static ValidationResult validate(String yaml) {
        return ConchValidator.validate(ConchParser.parse(yaml), ConchValidator.Context.of(SemVer.parse("1.8.0")));
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
