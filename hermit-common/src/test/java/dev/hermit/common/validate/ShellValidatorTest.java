package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.hermit.common.Fixtures;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.common.version.SemVer;

class ShellValidatorTest {

    @Test
    void legacyOaHasNoErrorsAndWarnsOnUnknownRelated() {
        ValidationResult result = Fixtures.validate("legacy_oa_valid.yaml");
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).extracting(ValidationIssue::code)
                .containsExactly(ValidationCodes.UNKNOWN_RELATED, ValidationCodes.UNKNOWN_RELATED);
        assertThat(result.warnings()).extracting(ValidationIssue::path)
                .containsExactly("actions.list_todo.related[0]", "actions.list_todo.related[1]");
    }

    @Test
    void rejectsTheIllegalFixtures() {
        assertThat(codes(Fixtures.validate("missing_description.yaml")))
                .containsExactly(ValidationCodes.MISSING_DESCRIPTION);
        assertThat(Fixtures.validate("missing_description.yaml").errors().get(0).path())
                .isEqualTo("actions.list_todo.description");

        ValidationResult mapping = Fixtures.validate("mapping_output.yaml");
        assertThat(codes(mapping)).containsExactly(ValidationCodes.MISSING_DESCRIPTION);
        assertThat(mapping.errors().get(0).path()).isEqualTo("actions.list_todo.output.items");

        ValidationResult plaintext = Fixtures.validate("plaintext_password.yaml");
        assertThat(codes(plaintext)).containsExactly(ValidationCodes.PLAINTEXT_CREDENTIAL);
        assertThat(plaintext.errors().get(0).path()).isEqualTo("auth.steps[2].form.password");

        ValidationResult undeclared = Fixtures.validate("undeclared_credential.yaml");
        assertThat(codes(undeclared)).containsExactly(ValidationCodes.UNDECLARED_CREDENTIAL);
        assertThat(undeclared.errors().get(0).path()).isEqualTo("auth.steps[2].form.username");
        assertThat(undeclared.errors().get(0).message()).contains("token");

        ValidationResult unknown = Fixtures.validate("unknown_step.yaml");
        assertThat(codes(unknown)).containsExactly(ValidationCodes.UNSUPPORTED_STEP);
        assertThat(unknown.errors().get(0).path()).isEqualTo("actions.list_todo.steps[1]");
    }

    @Test
    void section54MappingOutputCannotBePublished() {
        ValidationResult result = ShellValidator.validate(ShellParser.parse("""
                connector: legacy_oa
                version: 1.0.0
                driver: http
                min_agent_version: 1.0.0
                base_url: "http://oa.intranet.local"
                auth:
                  steps:
                    - request: { method: GET, path: /login.aspx }
                actions:
                  list_todo:
                    description: 查询待办列表
                    input:
                      page: { type: integer, default: 1 }
                    output:
                      items: "{{items}}"
                    idempotent: true
                """), ShellValidator.Context.of(null));
        assertThat(codes(result)).contains(
                ValidationCodes.MISSING_TITLE,
                ValidationCodes.MISSING_DESCRIPTION,
                ValidationCodes.MISSING_CREDENTIALS,
                ValidationCodes.MISSING_EXAMPLES);
        assertThat(result.errors()).anyMatch(issue ->
                issue.path().equals("actions.list_todo.output.items")
                        && issue.code().equals(ValidationCodes.MISSING_DESCRIPTION));
    }

    @Test
    void checksNamesDriverCredentialsVersionAndMetadata() {
        ShellDoc doc = ShellParser.parse("""
                shell: A
                version: 2.0.0
                driver: browser
                min_crab_version: 1.2.0
                credentials:
                  username: { type: text, required: false }
                actions:
                  Bad:
                    input:
                      page: { type: integer }
                    output: {}
                    steps:
                      - script: { source: "1" }
                """);
        ValidationResult result = ShellValidator.validate(doc, ShellValidator.Context.of(SemVer.parse("1.0.0")));
        assertThat(codes(result)).contains(
                ValidationCodes.SHELL_NAME,
                ValidationCodes.DRIVER,
                ValidationCodes.MISSING_TITLE,
                ValidationCodes.MISSING_DESCRIPTION,
                ValidationCodes.MIN_CRAB_VERSION,
                ValidationCodes.ACTION_NAME,
                ValidationCodes.MISSING_OUTPUT,
                ValidationCodes.MISSING_EXAMPLES);
        assertThat(result.warnings()).extracting(ValidationIssue::code)
                .contains(ValidationCodes.MISSING_NOTES, ValidationCodes.MISSING_RELATED);
        assertThat(result.errors()).anyMatch(issue -> issue.path().equals("actions.Bad.input.page"));

        ValidationResult sameVersion = ShellValidator.validate(
                withVersion(doc, "1.0.0"),
                ShellValidator.Context.of(SemVer.parse("1.0.0")));
        assertThat(codes(sameVersion)).doesNotContain(ValidationCodes.MIN_CRAB_VERSION);

        ValidationResult skipped = ShellValidator.validate(doc, ShellValidator.Context.of(null));
        assertThat(codes(skipped)).doesNotContain(ValidationCodes.MIN_CRAB_VERSION);

        ValidationResult prerelease = ShellValidator.validate(
                ShellParser.parse(minimal("shell: ab\ndriver: http\nmin_crab_version: 1.0.0")),
                ShellValidator.Context.of(SemVer.parse("1.0.0-rc.1")));
        assertThat(codes(prerelease)).contains(ValidationCodes.MIN_CRAB_VERSION);
    }

    @Test
    void plaintextHeuristicSkipsTemplatesExtractNamesAndEmptyValues() {
        ValidationResult result = ShellValidator.validate(ShellParser.parse("""
                shell: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  password: { type: secret, required: true }
                actions:
                  list_todo:
                    title: 查询
                    description: 说明
                    input:
                      page: { type: integer, description: 页码 }
                    output:
                      items: { type: string, description: 结果 }
                    examples:
                      - input: { page: "{{credential.not_declared}}" }
                        output: { items: x }
                    notes: 注意
                    related: [list_todo]
                    steps:
                      - extract:
                          password: "variable-name-not-a-value"
                          row:
                            css: "td"
                            token: "literal-token"
                      - request:
                          form:
                            Password: "{{credential.password}}"
                            api_key: ""
                            apiKey: "{{input.token}}"
                            secret: "literal-secret"
                """), ShellValidator.Context.of(null));
        assertThat(result.errors()).extracting(ValidationIssue::path).containsExactly(
                "actions.list_todo.steps[0].row.token",
                "actions.list_todo.steps[1].form.secret");
        assertThat(result.errors()).extracting(ValidationIssue::code)
                .containsOnly(ValidationCodes.PLAINTEXT_CREDENTIAL);
    }

    @Test
    void configBaseUrlIsAllowedWithoutADeclaration() {
        ValidationResult allowed = ShellValidator.validate(ShellParser.parse(referencing("""
                - request: { path: "{{config.base_url}}/home" }
                """)), ShellValidator.Context.of(null));
        assertThat(codes(allowed)).doesNotContain(ValidationCodes.UNDECLARED_CONFIG);

        ValidationResult missing = ShellValidator.validate(ShellParser.parse(referencing("""
                - request: { path: "{{config.page_size | default('20')}}" }
                """)), ShellValidator.Context.of(null));
        assertThat(missing.errors()).anyMatch(issue ->
                issue.code().equals(ValidationCodes.UNDECLARED_CONFIG) && issue.message().contains("page_size"));
    }

    @Test
    void customStepTypesAndTemplateSyntax() {
        ShellDoc doc = ShellParser.parse(referencing("""
                - browse: { url: /x }
                """));
        ValidationResult custom = ShellValidator.validate(
                doc, ShellValidator.Context.of(null, Set.of("browse")));
        assertThat(codes(custom)).doesNotContain(ValidationCodes.UNSUPPORTED_STEP);

        ValidationResult broken = ShellValidator.validate(ShellParser.parse(referencing("""
                - request: { path: "{{input.page" }
                """)), ShellValidator.Context.of(null));
        assertThat(codes(broken)).contains(ValidationCodes.TEMPLATE_SYNTAX);
    }

    @Test
    void nameLengthBoundaries() {
        assertThat(codes(ShellValidator.validate(
                ShellParser.parse(named("ab", "list_todo")), ShellValidator.Context.of(null))))
                .doesNotContain(ValidationCodes.SHELL_NAME, ValidationCodes.ACTION_NAME);
        String max = "a" + "b".repeat(63);
        assertThat(max).hasSize(64);
        assertThat(codes(ShellValidator.validate(
                ShellParser.parse(named(max, max)), ShellValidator.Context.of(null))))
                .doesNotContain(ValidationCodes.SHELL_NAME, ValidationCodes.ACTION_NAME);
        assertThat(codes(ShellValidator.validate(
                ShellParser.parse(named("a", "list-todo")), ShellValidator.Context.of(null))))
                .contains(ValidationCodes.SHELL_NAME, ValidationCodes.ACTION_NAME);
    }

    private static ShellDoc withVersion(ShellDoc ignored, String min) {
        return ShellParser.parse(minimal("min_crab_version: " + min + "\nshell: A\ndriver: browser"));
    }

    private static String minimal(String extra) {
        return """
                version: 1.0.0
                credentials: {}
                actions: {}
                """ + extra + "\n";
    }

    private static String named(String shell, String action) {
        return """
                shell: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  %s:
                    title: 标题
                    description: 说明
                    input:
                      page: { type: integer, description: 页码 }
                    output:
                      items: { type: string, description: 结果 }
                    examples:
                      - input: { page: 1 }
                        output: { items: ok }
                    notes: 注意
                    related: [%s]
                """.formatted(shell, action, action);
    }

    private static String referencing(String steps) {
        String base = """
                shell: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  list_todo:
                    title: 查询
                    description: 说明
                    input:
                      page: { type: integer, description: 页码 }
                    output:
                      items: { type: string, description: 结果 }
                    examples:
                      - input: { page: 1 }
                        output: { items: ok }
                    notes: 注意
                    related: [list_todo]
                    steps:
                """.stripIndent();
        String indented = steps.stripIndent().stripTrailing().replace("\n", "\n      ");
        return base + "      " + indented + "\n";
    }

    private static java.util.List<String> codes(ValidationResult result) {
        return result.errors().stream().map(ValidationIssue::code).toList();
    }
}
