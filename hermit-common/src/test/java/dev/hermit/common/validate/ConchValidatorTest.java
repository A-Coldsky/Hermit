package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.hermit.common.Fixtures;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

class ConchValidatorTest {

    @Test
    void legacyOaHasNoErrorsAndWarnsWhenRelatedIsOmitted() {
        ValidationResult result = Fixtures.validate("legacy_oa_valid.yaml");
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(ValidationCodes.MISSING_RELATED);
            assertThat(issue.path()).isEqualTo("actions.list_todo.related");
            assertThat(issue.message()).isEqualTo("缺少 related");
        });
    }

    @Test
    void explicitEmptyRelatedIsNotMissingForHttp() {
        String yaml = explicitEmptyRelatedYaml();
        assertThat(yaml).contains("    related: []\n");
        ValidationResult empty = validate(yaml);
        assertThat(empty.errors()).isEmpty();
        assertThat(empty.warnings()).extracting(ValidationIssue::code)
                .doesNotContain(ValidationCodes.MISSING_RELATED);
        assertThat(ConchParser.parse(yaml).actions().get("list_todo").related()).isEmpty();

        String omitted = yaml.replace("    related: []\n", "");
        assertThat(omitted).doesNotContain("related:");
        ValidationResult missing = validate(omitted);
        assertThat(missing.errors()).isEmpty();
        assertThat(missing.warnings()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(ValidationCodes.MISSING_RELATED);
            assertThat(issue.path()).isEqualTo("actions.list_todo.related");
            assertThat(issue.message()).isEqualTo("缺少 related");
        });
        assertThat(ConchParser.parse(omitted).actions().get("list_todo").related()).isNull();

        ValidationResult nulled = validate(yaml.replace("    related: []\n", "    related: null\n"));
        assertThat(nulled.errors()).isEmpty();
        assertThat(nulled.warnings()).extracting(ValidationIssue::code)
                .containsExactly(ValidationCodes.MISSING_RELATED);
        assertThat(nulled.warnings().get(0).message()).isEqualTo("缺少 related");
        assertThat(ConchParser.parse(yaml.replace("    related: []\n", "    related: null\n"))
                .actions().get("list_todo").related()).isNull();
    }

    @Test
    void explicitEmptyRelatedIsNotMissingForMcp() {
        ValidationResult empty = validate(mcpRelated("    related: []\n"));
        assertThat(empty.errors()).isEmpty();
        assertThat(empty.warnings()).extracting(ValidationIssue::code)
                .doesNotContain(ValidationCodes.MISSING_RELATED);
        assertThat(ConchParser.parse(mcpRelated("    related: []\n"))
                .actions().get("search_customer").related()).isEmpty();

        ValidationResult missing = validate(mcpRelated(""));
        assertThat(missing.errors()).isEmpty();
        assertThat(missing.warnings()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(ValidationCodes.MISSING_RELATED);
            assertThat(issue.path()).isEqualTo("actions.search_customer.related");
            assertThat(issue.message()).isEqualTo("缺少 related");
        });
        assertThat(ConchParser.parse(mcpRelated(""))
                .actions().get("search_customer").related()).isNull();

        ValidationResult nulled = validate(mcpRelated("    related: null\n"));
        assertThat(nulled.errors()).isEmpty();
        assertThat(nulled.warnings()).extracting(ValidationIssue::code)
                .containsExactly(ValidationCodes.MISSING_RELATED);

        ValidationResult unknown = validate(mcpRelated("    related: [approve]\n"));
        assertThat(unknown.errors()).isEmpty();
        assertThat(unknown.warnings()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(ValidationCodes.UNKNOWN_RELATED);
            assertThat(issue.path()).isEqualTo("actions.search_customer.related[0]");
            assertThat(issue.message()).isEqualTo("related 指向不存在的能力 approve");
        });
    }

    @Test
    void danglingRelatedStaysAWarning() {
        ValidationResult result = ConchValidator.validate(ConchParser.parse("""
                conch: legacy_oa
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
                    related: [approve, list_done]
                """), ConchValidator.Context.of(null));
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).extracting(ValidationIssue::code)
                .containsExactly(ValidationCodes.UNKNOWN_RELATED, ValidationCodes.UNKNOWN_RELATED);
        assertThat(result.warnings()).extracting(ValidationIssue::path)
                .containsExactly("actions.list_todo.related[0]", "actions.list_todo.related[1]");
        assertThat(result.warnings()).extracting(ValidationIssue::message)
                .containsExactly("related 指向不存在的能力 approve", "related 指向不存在的能力 list_done");
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
        assertThat(plaintext.errors().get(0).message()).isEqualTo("疑似明文凭证。把它声明成凭证字段，不要写进规则。");
        assertThat(plaintext.errors().get(0).path()).isEqualTo("auth.steps[2].request.form.password");

        ValidationResult undeclared = Fixtures.validate("undeclared_credential.yaml");
        assertThat(codes(undeclared)).containsExactly(ValidationCodes.UNDECLARED_CREDENTIAL);
        assertThat(undeclared.errors().get(0).path()).isEqualTo("auth.steps[2].request.form.username");
        assertThat(undeclared.errors().get(0).message()).contains("token");

        ValidationResult unknown = Fixtures.validate("unknown_step.yaml");
        assertThat(codes(unknown)).containsExactly(ValidationCodes.UNSUPPORTED_STEP);
        assertThat(unknown.errors().get(0).path()).isEqualTo("actions.list_todo.steps[1]");
    }

    @Test
    void section54MappingOutputCannotBePublished() {
        ValidationResult result = ConchValidator.validate(ConchParser.parse("""
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
                """), ConchValidator.Context.of(null));
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
        ConchDoc doc = ConchParser.parse("""
                conch: A
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
        ValidationResult result = ConchValidator.validate(doc, ConchValidator.Context.of(SemVer.parse("1.0.0")));
        assertThat(codes(result)).contains(
                ValidationCodes.CONCH_NAME,
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

        ValidationResult sameVersion = ConchValidator.validate(
                withVersion(doc, "1.0.0"),
                ConchValidator.Context.of(SemVer.parse("1.0.0")));
        assertThat(codes(sameVersion)).doesNotContain(ValidationCodes.MIN_CRAB_VERSION);

        ValidationResult skipped = ConchValidator.validate(doc, ConchValidator.Context.of(null));
        assertThat(codes(skipped)).doesNotContain(ValidationCodes.MIN_CRAB_VERSION);

        ValidationResult prerelease = ConchValidator.validate(
                ConchParser.parse(minimal("conch: ab\ndriver: http\nmin_crab_version: 1.0.0")),
                ConchValidator.Context.of(SemVer.parse("1.0.0-rc.1")));
        assertThat(codes(prerelease)).contains(ValidationCodes.MIN_CRAB_VERSION);
    }

    @Test
    void plaintextHeuristicSkipsTemplatesExtractNamesAndEmptyValues() {
        ValidationResult result = ConchValidator.validate(ConchParser.parse("""
                conch: legacy_oa
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
                """), ConchValidator.Context.of(null));
        assertThat(result.errors()).extracting(ValidationIssue::path).containsExactly(
                "actions.list_todo.steps[0].extract.row.token",
                "actions.list_todo.steps[1].request.form.secret");
        assertThat(result.errors()).extracting(ValidationIssue::code)
                .containsOnly(ValidationCodes.PLAINTEXT_CREDENTIAL);
    }

    @Test
    void plaintextCoversNumbersHeadersAndSkipsExtractSelectors() {
        ValidationResult result = ConchValidator.validate(ConchParser.parse("""
                conch: legacy_oa
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
                      - input: { page: 1 }
                        output: { items: ok }
                    notes: 注意
                    related: [list_todo]
                    steps:
                      - request:
                          headers:
                            Authorization: "Basic xxx"
                            Cookie: "sid=1"
                          form:
                            password: 123456
                            token_enabled: true
                      - extract:
                          css: "div.password"
                          attr: "data-token"
                          row:
                            css: "#id"
                            attr: "href"
                            authorization: "Bearer literal"
                      - assert: { cookie_exists: ASP.NET_SessionId }
                """), ConchValidator.Context.of(null));
        assertThat(result.errors()).extracting(ValidationIssue::path).containsExactly(
                "actions.list_todo.steps[0].request.headers.Authorization",
                "actions.list_todo.steps[0].request.headers.Cookie",
                "actions.list_todo.steps[0].request.form.password",
                "actions.list_todo.steps[1].extract.row.authorization");
        assertThat(result.errors()).extracting(ValidationIssue::code)
                .containsOnly(ValidationCodes.PLAINTEXT_CREDENTIAL);
    }

    @Test
    void assertExistenceKeysAreNamesButCookiePrefixesElsewhereAreSecrets() {
        ValidationResult result = ConchValidator.validate(ConchParser.parse(referencing("""
                - assert:
                    cookie_exists: ASP.NET_SessionId
                    header_exists: X-Request-Id
                - request:
                    headers:
                      cookie_value: "abc"
                      authorization_token: "Bearer literal"
                    form:
                      cookie_exists: "not-a-name-here"
                """)), ConchValidator.Context.of(null));
        assertThat(result.errors()).extracting(ValidationIssue::path).containsExactly(
                "actions.list_todo.steps[1].request.headers.cookie_value",
                "actions.list_todo.steps[1].request.headers.authorization_token",
                "actions.list_todo.steps[1].request.form.cookie_exists");
        assertThat(result.errors()).extracting(ValidationIssue::code)
                .containsOnly(ValidationCodes.PLAINTEXT_CREDENTIAL);
    }

    @Test
    void outputTemplateMustNotReferenceCredentials() {
        ValidationResult leaked = ConchValidator.validate(ConchParser.parse(conchWithOutput("""
                      items:
                        type: string
                        description: 结果
                        value: "{{credential.password}}"
                """)), ConchValidator.Context.of(null));
        assertThat(leaked.errors()).extracting(ValidationIssue::code)
                .containsExactly(ValidationCodes.CREDENTIAL_IN_OUTPUT);
        assertThat(leaked.errors().get(0).path()).isEqualTo("actions.list_todo.output.items");

        ValidationResult viaDefault = ConchValidator.validate(ConchParser.parse(conchWithOutput("""
                      token:
                        type: string
                        description: 令牌
                        value: "id={{input.id | default(credential.password)}}"
                """)), ConchValidator.Context.of(null));
        assertThat(viaDefault.errors()).extracting(ValidationIssue::path)
                .containsExactly("actions.list_todo.output.token");

        ValidationResult allowed = ConchValidator.validate(ConchParser.parse(conchWithOutput("""
                      items:
                        type: string
                        description: 结果
                        value: "{{config.base_url}}"
                """)), ConchValidator.Context.of(null));
        assertThat(allowed.errors()).isEmpty();
    }

    @Test
    void rejectsConfigSecretAndAllowsCredentialSecret() {
        ValidationResult rejected = ConchValidator.validate(ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  password: { type: secret, required: true }
                config:
                  access_value: { type: secret, label: Access value }
                  page_size: { type: text, default: "20" }
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
                      - request: { path: "{{credential.password}}" }
                """), ConchValidator.Context.of(null));
        assertThat(rejected.errors())
                .filteredOn(issue -> issue.code().equals(ValidationCodes.CONFIG_SECRET))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.path()).isEqualTo("config.access_value.type");
                    assertThat(issue.message()).isEqualTo("秘密要放在 credentials");
                });
        assertThat(rejected.warnings()).extracting(ValidationIssue::code)
                .doesNotContain(ValidationCodes.CONFIG_SECRET);

        ValidationResult credentialsOnly = ConchValidator.validate(ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  password: { type: secret, required: true }
                config:
                  page_size: { type: text, default: "20" }
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
                      - request: { path: "{{credential.password}}" }
                """), ConchValidator.Context.of(null));
        assertThat(codes(credentialsOnly)).doesNotContain(ValidationCodes.CONFIG_SECRET);
        assertThat(credentialsOnly.errors()).isEmpty();
    }

    @Test
    void configBaseUrlIsAllowedWithoutADeclaration() {
        ValidationResult allowed = ConchValidator.validate(ConchParser.parse(referencing("""
                - request: { path: "{{config.base_url}}/home" }
                """)), ConchValidator.Context.of(null));
        assertThat(codes(allowed)).doesNotContain(ValidationCodes.UNDECLARED_CONFIG);

        ValidationResult missing = ConchValidator.validate(ConchParser.parse(referencing("""
                - request: { path: "{{config.page_size | default('20')}}" }
                """)), ConchValidator.Context.of(null));
        assertThat(missing.errors()).anyMatch(issue ->
                issue.code().equals(ValidationCodes.UNDECLARED_CONFIG) && issue.message().contains("page_size"));
    }

    @Test
    void scriptCodeIsLiteralAndBounded() {
        ValidationResult literal = ConchValidator.validate(ConchParser.parse(referencing("""
                - script:
                    code: |
                      var raw = "{{credential.not_a_field}}";
                      return { raw: raw };
                    timeout_ms: 5000
                """)), ConchValidator.Context.of(null));
        assertThat(literal.errors()).isEmpty();

        ValidationResult missing = ConchValidator.validate(ConchParser.parse(referencing("""
                - script: { into: value }
                """)), ConchValidator.Context.of(null));
        assertThat(missing.errors()).anyMatch(issue ->
                issue.code().equals(ValidationCodes.SCRIPT_STEP) && issue.path().endsWith(".code"));

        ValidationResult timeout = ConchValidator.validate(ConchParser.parse(referencing("""
                - script: { code: "return { ok: 1 };", timeout_ms: 5001 }
                """)), ConchValidator.Context.of(null));
        assertThat(timeout.errors()).anyMatch(issue ->
                issue.code().equals(ValidationCodes.SCRIPT_STEP)
                        && issue.path().endsWith(".timeout_ms")
                        && !issue.message().contains("5001"));

        String bulky = "a".repeat(64 * 1024 + 1);
        ValidationResult oversized = ConchValidator.validate(ConchParser.parse(referencing("""
                - script: { code: "%s" }
                """.formatted(bulky))), ConchValidator.Context.of(null));
        assertThat(oversized.errors()).anyMatch(issue ->
                issue.code().equals(ValidationCodes.SCRIPT_STEP)
                        && issue.message().equals("脚本源码超过 64KB")
                        && !issue.message().contains("aaa"));
    }

    @Test
    void customStepTypesAndTemplateSyntax() {
        ConchDoc doc = ConchParser.parse(referencing("""
                - browse: { url: /x }
                """));
        ValidationResult custom = ConchValidator.validate(
                doc, ConchValidator.Context.of(null, Set.of("browse")));
        assertThat(codes(custom)).doesNotContain(ValidationCodes.UNSUPPORTED_STEP);

        ValidationResult broken = ConchValidator.validate(ConchParser.parse(referencing("""
                - request: { path: "{{input.page" }
                """)), ConchValidator.Context.of(null));
        assertThat(codes(broken)).contains(ValidationCodes.TEMPLATE_SYNTAX);
    }

    @Test
    void nameLengthBoundaries() {
        assertThat(codes(ConchValidator.validate(
                ConchParser.parse(named("ab", "list_todo")), ConchValidator.Context.of(null))))
                .doesNotContain(ValidationCodes.CONCH_NAME, ValidationCodes.ACTION_NAME);
        String max = "a" + "b".repeat(63);
        assertThat(max).hasSize(64);
        assertThat(codes(ConchValidator.validate(
                ConchParser.parse(named(max, max)), ConchValidator.Context.of(null))))
                .doesNotContain(ValidationCodes.CONCH_NAME, ValidationCodes.ACTION_NAME);
        assertThat(codes(ConchValidator.validate(
                ConchParser.parse(named("a", "list-todo")), ConchValidator.Context.of(null))))
                .contains(ValidationCodes.CONCH_NAME, ValidationCodes.ACTION_NAME);
    }

    private static ConchDoc withVersion(ConchDoc ignored, String min) {
        return ConchParser.parse(minimal("min_crab_version: " + min + "\nconch: A\ndriver: browser"));
    }

    private static String minimal(String extra) {
        return """
                version: 1.0.0
                credentials: {}
                actions: {}
                """ + extra + "\n";
    }

    private static String named(String conch, String action) {
        return """
                conch: %s
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
                """.formatted(conch, action, action);
    }

    private static String conchWithOutput(String output) {
        String base = """
                conch: legacy_oa
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
                    examples:
                      - input: { page: 1 }
                        output: { items: ok }
                    notes: 注意
                    related: [list_todo]
                    steps:
                      - request: { path: "{{credential.password}}" }
                    output:
                """.stripIndent();
        String indented = output.stripIndent().stripTrailing().replace("\n", "\n      ");
        return base + "      " + indented + "\n";
    }

    @Test
    void undeclaredVariablesFailAndDeclaredOnesStillPass() {
        ValidationResult missing = ConchValidator.validate(ConchParser.parse("""
                conch: legacy_oa
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
                      - input: { page: 1 }
                        output: { items: ok }
                    notes: 注意
                    related: [list_todo]
                    steps:
                      - request:
                          method: GET
                          path: "/{{vars.token}}"
                          headers:
                            X-A: "{{token}}"
                          query:
                            q: "{{auth.token}}"
                """), ConchValidator.Context.of(null));
        assertThat(missing.errors()).extracting(ValidationIssue::message).containsExactly(
                "引用了未定义的变量 vars.token。",
                "引用了未定义的变量 token。",
                "引用了未定义的变量 auth.token。");
        assertThat(missing.errors()).extracting(ValidationIssue::code)
                .containsOnly(ValidationCodes.UNDECLARED_VARIABLE);
        assertThat(missing.errors()).extracting(ValidationIssue::path).containsExactly(
                "actions.list_todo.steps[0].request.path",
                "actions.list_todo.steps[0].request.headers.X-A",
                "actions.list_todo.steps[0].request.query.q");

        ValidationResult declared = ConchValidator.validate(ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  password: { type: secret, required: true, label: 密码 }
                auth:
                  steps:
                    - extract:
                        viewstate: { jsonpath: $.view }
                    - request:
                        method: POST
                        path: /login
                        form:
                          state: "{{viewstate}}"
                          password: "{{credential.password}}"
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
                      - request:
                          method: GET
                          path: /items
                          query: { p: "{{input.page}}" }
                      - extract:
                          items: { jsonpath: $.items }
                      - request:
                          method: GET
                          path: "/item/{{items}}"
                """), ConchValidator.Context.of(null));
        assertThat(declared.errors()).isEmpty();

        ValidationResult loginOnly = ConchValidator.validate(ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  password: { type: secret, required: true, label: 密码 }
                auth:
                  steps:
                    - extract:
                        token: { jsonpath: $.token }
                actions:
                  list_todo:
                    title: 查询
                    description: 说明
                    output:
                      items: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { items: ok }
                    notes: 注意
                    related: [list_todo]
                    steps:
                      - request:
                          method: GET
                          path: "/{{token}}"
                      - request:
                          method: GET
                          path: "/{{token | default('')}}"
                      - request:
                          method: GET
                          path: "/{{missing | default('')}}"
                      - request:
                          method: GET
                          path: "/{{credential.absent | default('')}}"
                """), ConchValidator.Context.of(null));
        assertThat(loginOnly.errors()).extracting(ValidationIssue::message).containsExactly(
                "能力不能引用登录步骤里的变量。",
                "引用了未声明的凭证 absent");
        assertThat(loginOnly.errors()).extracting(ValidationIssue::code).containsExactly(
                ValidationCodes.UNDECLARED_VARIABLE,
                ValidationCodes.UNDECLARED_CREDENTIAL);
        assertThat(loginOnly.errors()).extracting(ValidationIssue::message)
                .noneMatch(message -> message.toString().contains("default"));
        assertThat(loginOnly.warnings()).extracting(ValidationIssue::message).contains(
                "能力引用登录步骤变量，运行时为空串；令牌请声明成凭证");
        assertThat(loginOnly.warnings()).extracting(ValidationIssue::code)
                .filteredOn(ValidationCodes.LOGIN_VARIABLE_EMPTY::equals)
                .hasSize(1);
    }

    private static String referencing(String steps) {
        String base = """
                conch: legacy_oa
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

    @Test
    void undeclaredInputFieldsFailEvenWithADefault() {
        String yaml = """
                conch: legacy_oa
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
                      - input: { page: 1 }
                        output: { items: ok }
                    notes: 注意
                    related: [list_todo]
                    steps:
                      - request:
                          method: GET
                          path: "/{{input.missing | default(1)}}"
                          query: { p: "{{input.page | default(1)}}" }
                """;
        ValidationResult result = validate(yaml);
        java.util.List<ValidationIssue> located = IssueLines.locate(yaml, result.errors());
        assertThat(located).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(ValidationCodes.UNDECLARED_VARIABLE);
            assertThat(issue.message()).isEqualTo("引用了未声明的输入字段 missing");
            assertThat(issue.path()).isEqualTo("actions.list_todo.steps[0].request.path");
            assertThat(issue.line()).isNotNull().isGreaterThan(0);
        });
        assertThat(validate("""
                conch: legacy_oa
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
                    output:
                      items: { type: string, description: 结果 }
                    examples:
                      - input: {}
                        output: { items: ok }
                    notes: 注意
                    related: [list_todo]
                    steps:
                      - set: { token: "{{name | default('')}}" }
                """).errors()).extracting(ValidationIssue::message)
                .noneMatch(message -> message.contains("未声明的输入字段"));
    }

    private static java.util.List<String> codes(ValidationResult result) {
        return result.errors().stream().map(ValidationIssue::code).toList();
    }

    private static ValidationResult validate(String yaml) {
        return ConchValidator.validate(ConchParser.parse(yaml), ConchValidator.Context.of(null));
    }

    private static String explicitEmptyRelatedYaml() {
        try (InputStream in = ConchValidatorTest.class.getClassLoader()
                .getResourceAsStream("related/explicit-empty.yaml")) {
            if (in == null) {
                throw new IllegalStateException("missing related/explicit-empty.yaml");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String mcpRelated(String relatedField) {
        return """
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户系统
                description: 源站提供的 MCP 服务。
                credentials: {}
                upstream:
                  url: https://crm.intra.example.com/mcp
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称搜索客户。
                    input_schema: {type: object}
                    examples:
                      - input: {}
                        output: {}
                    notes: 只读查询。
                %s
                """.formatted(relatedField);
    }
}
