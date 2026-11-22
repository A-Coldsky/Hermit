package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

/** if、repeat、retry：1.9.0 起可用。写法不对在校验时报错，报错里给出可用的键。 */
class FlowStepValidationTest {

    private static final String IF = """
            - request: {method: GET, path: /order}
            - if:
                contains: "请再次确认"
                then:
                  - request: {method: POST, path: /confirm}
                else:
                  - set: {confirmed: "no"}
            """;
    private static final String REPEAT = """
            - repeat:
                max: 20
                steps:
                  - request: {method: GET, path: /list, query: {page: "{{loop.index}}"}}
                  - extract:
                      rows: {css: "tr.item", each: {id: {css: ".id"}}}
                      next: {css: "a.next", attr: href, optional: true}
                collect: {all_rows: rows}
                until: {not_present: next}
            - set: {count: "{{all_rows}}", cut: "{{loop.truncated}}"}
            """;
    private static final String RETRY = """
            - request:
                method: GET
                path: /report
                retry: {times: 2, delay_ms: 500, on: [timeout, connect, 502, 503, 504]}
            """;

    @Test
    void theThreeFormsFromTheGuideValidate() {
        assertThat(validate("1.9.0", IF).errors()).isEmpty();
        assertThat(validate("1.9.0", REPEAT).errors()).isEmpty();
        assertThat(validate("1.9.0", RETRY).errors()).isEmpty();
    }

    @Test
    void eachNeedsOneNine() {
        assertThat(messages(validate("1.8.0", IF)))
                .containsExactly("使用 if 时 min_crab_version 必须不低于 1.9.0");
        assertThat(messages(validate("1.8.0", REPEAT)))
                .containsExactly("使用 repeat 时 min_crab_version 必须不低于 1.9.0");
        assertThat(messages(validate("1.8.0", RETRY)))
                .containsExactly("使用 retry 时 min_crab_version 必须不低于 1.9.0");
        assertThat(ConchValidator.validate(
                ConchParser.parse(conch("1.9.0", IF, "")), ConchValidator.Context.of(SemVer.parse("1.8.0")))
                .errors()).isNotEmpty();
    }

    @Test
    void unknownKeysAreErrorsThatNameTheRightKey() {
        assertThat(messages(validate("1.9.0", """
                - request: {method: GET, path: /a}
                - if:
                    body_contains: "x"
                    then:
                      - set: {a: "1"}
                    otherwise:
                      - set: {a: "2"}
                """))).anySatisfy(text -> assertThat(text).contains("未知的 if 键 body_contains").contains("是不是想写 contains？"))
                .anySatisfy(text -> assertThat(text).contains("otherwise").contains("是不是想写 else？"))
                .anySatisfy(text -> assertThat(text).contains("if 缺少判断键"));
        assertThat(messages(validate("1.9.0", """
                - repeat:
                    max_pages: 3
                    steps:
                      - request: {method: GET, path: /a}
                    while: {present: next}
                """))).anySatisfy(text -> assertThat(text).contains("max_pages").contains("是不是想写 max？"))
                .anySatisfy(text -> assertThat(text).contains("while").contains("是不是想写 until？"))
                .anySatisfy(text -> assertThat(text).contains("repeat 缺少 max"));
        assertThat(messages(validate("1.9.0", """
                - request: {method: GET, path: /a, retry: {attempts: 2}}
                """))).anySatisfy(text -> assertThat(text).contains("attempts").contains("是不是想写 times？"));
    }

    @Test
    void shapesAreChecked() {
        assertThat(messages(validate("1.9.0", """
                - if:
                    present: a
                """))).anySatisfy(text -> assertThat(text).contains("if 缺少 then"));
        assertThat(messages(validate("1.9.0", """
                - if:
                    present: "{{a}}"
                    then: []
                """))).anySatisfy(text -> assertThat(text).contains("不要写 {{ }}"))
                .anySatisfy(text -> assertThat(text).contains("非空的步骤列表"));
        assertThat(messages(validate("1.9.0", """
                - repeat:
                    max: 101
                    steps:
                      - set: {a: "1"}
                    collect: {loop: a}
                    until: {}
                """))).anySatisfy(text -> assertThat(text).contains("max 必须是 1 到 100"))
                .anySatisfy(text -> assertThat(text).contains("不能写入保留变量 loop"))
                .anySatisfy(text -> assertThat(text).contains("until 必须是判断键的映射"));
        assertThat(messages(validate("1.9.0", """
                - request: {method: GET, path: /a, retry: {times: 4, delay_ms: 9000, on: [404, "503", slow]}}
                """))).hasSize(5);
    }

    @Test
    void nestingIsLimited() {
        assertThat(messages(validate("1.9.0", """
                - repeat:
                    max: 2
                    steps:
                      - if:
                          present: a
                          then:
                            - repeat:
                                max: 2
                                steps:
                                  - set: {a: "1"}
                """))).containsExactly("repeat 里不能再写 repeat");
        assertThat(messages(validate("1.9.0", """
                - if:
                    present: a
                    then:
                      - if:
                          present: a
                          then:
                            - if:
                                present: a
                                then:
                                  - if:
                                      present: a
                                      then:
                                        - set: {a: "1"}
                """))).containsExactly("if / repeat 最多嵌套 3 层");
    }

    @Test
    void retryOnlyOnRequestsThatCanBeRepeated() {
        String post = """
                - request: {method: POST, path: /submit, retry: {times: 1}}
                """;
        assertThat(messages(validate("1.9.0", post)))
                .anySatisfy(text -> assertThat(text).contains("retry 只能用在 GET、HEAD 请求上"));
        assertThat(ConchValidator.validate(ConchParser.parse(conch("1.9.0", post, "idempotent: true")),
                ConchValidator.Context.of(SemVer.parse("1.9.0"))).errors()).isEmpty();
    }

    @Test
    void misspelledExtractAndAssertKeysBlockOnlyFromOneNine() {
        String steps = """
                - request: {method: GET, path: /a}
                - assert: {statuss: 200}
                - extract:
                    title: {csss: "h1", css: "h1"}
                """;
        ValidationResult old = validate("1.8.0", steps);
        assertThat(old.errors()).isEmpty();
        assertThat(old.warnings()).extracting(ValidationIssue::code)
                .contains(ValidationCodes.ASSERT_KEY, ValidationCodes.EXTRACT_FIELD);
        ValidationResult strict = validate("1.9.0", steps);
        assertThat(strict.errors()).extracting(ValidationIssue::code)
                .containsExactlyInAnyOrder(ValidationCodes.ASSERT_KEY, ValidationCodes.EXTRACT_FIELD);
        assertThat(messages(strict)).anySatisfy(text -> assertThat(text).contains("是不是想写 status？"))
                .anySatisfy(text -> assertThat(text).contains("是不是想写 css？"));
        assertThat(messages(validate("1.9.0", """
                - request: {method: GET, path: /a, params: {a: b}}
                """))).anySatisfy(text -> assertThat(text).contains("未知的 request 键 params"));
    }

    @Test
    void variablesWrittenInsideBranchesAreKnownAfterwards() {
        assertThat(validate("1.9.0", """
                - request: {method: GET, path: /a}
                - if:
                    status: 200
                    then:
                      - set: {mode: "a"}
                    else:
                      - set: {mode: "b"}
                - request: {method: GET, path: "/b/{{mode}}"}
                """).errors()).isEmpty();
        assertThat(messages(validate("1.9.0", """
                - request: {method: GET, path: "/b/{{loop.index}}"}
                """))).isNotEmpty();
        assertThat(messages(validate("1.9.0", """
                - if:
                    present: a
                    then:
                      - set: {login: "x"}
                """))).isNotEmpty();
    }

    @Test
    void untilSeesWhatTheRoundWroteAndElseDoesNotSeeThen() {
        assertThat(messages(validate("1.9.0", """
                - repeat:
                    max: 3
                    steps:
                      - request: {method: GET, path: /list}
                      - extract:
                          total: {jsonpath: "$.total"}
                          names: {jsonpath: "$.names"}
                    collect: {cookie_names: names}
                    until: {equals: {loop.index: "{{total}}"}}
                """))).isEmpty();
        assertThat(messages(validate("1.9.0", """
                - request: {method: GET, path: /list}
                - if:
                    status: 200
                    then:
                      - set: {a: "1"}
                    else:
                      - set: {b: "{{a}}"}
                - set: {c: "{{a}}"}
                """))).singleElement().asString().contains("a");
        assertThat(messages(validate("1.9.0", """
                - repeat:
                    max: 3
                    steps:
                      - if:
                          equals: {loop.index: "1"}
                          then:
                            - set: {cursor: "0"}
                          else:
                            - set: {cursor: "{{cursor}}1"}
                """))).as("上一轮的分支写的变量").isEmpty();
    }

    @Test
    void concurrentReadsParsesNeedsOnePointNineAndStaysOutOfOldPackages() throws Exception {
        String on = withAuth("1.9.0", "concurrent_reads: true", "mcp: {read_only: true}");
        dev.hermit.common.model.ConchDoc doc = ConchParser.parse(on);
        assertThat(doc.auth().concurrentReads()).isTrue();
        ValidationResult ok = ConchValidator.validate(doc, ConchValidator.Context.of(SemVer.parse("1.9.0")));
        assertThat(ok.errors()).isEmpty();
        assertThat(ok.warnings()).noneMatch(w -> w.code().equals(ValidationCodes.CONCURRENT_READS));

        ValidationResult low = ConchValidator.validate(
                ConchParser.parse(withAuth("1.8.0", "concurrent_reads: true", "mcp: {read_only: true}")),
                ConchValidator.Context.of(SemVer.parse("1.9.0")));
        assertThat(low.errors()).anyMatch(e -> e.code().equals(ValidationCodes.MIN_CRAB_VERSION)
                && e.message().contains("auth.concurrent_reads"));

        ValidationResult unused = ConchValidator.validate(
                ConchParser.parse(withAuth("1.9.0", "concurrent_reads: true", "")),
                ConchValidator.Context.of(SemVer.parse("1.9.0")));
        assertThat(unused.errors()).isEmpty();
        assertThat(unused.warnings()).anyMatch(w -> w.code().equals(ValidationCodes.CONCURRENT_READS));

        dev.hermit.common.model.ConchDoc off = ConchParser.parse(withAuth("1.8.0", "cookies: {}", ""));
        assertThat(off.auth().concurrentReads()).isFalse();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.writeValueAsString(off.auth())).doesNotContain("oncurrent");
        assertThat(mapper.writeValueAsString(doc.auth())).contains("oncurrent");
    }

    @Test
    void concurrentReadsMustBeABoolean() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> ConchParser.parse(withAuth("1.9.0", "concurrent_reads: \"yes\"", "")))
                .hasMessageContaining("concurrent_reads");
    }

    @Test
    void deeplyNestedIfInsideRepeatCompletesQuicklyWithoutBlowup() {
        StringBuilder nested = new StringBuilder();
        nested.append("- repeat:\n");
        nested.append("    max: 2\n");
        nested.append("    steps:\n");
        int depth = 30;
        for (int i = 0; i < depth; i++) {
            String pad = "  ".repeat(3 + i * 2);
            nested.append(pad).append("- if:\n");
            nested.append(pad).append("    present: flag\n");
            nested.append(pad).append("    else:\n");
            nested.append(pad).append("      - set: {other: \"1\"}\n");
            nested.append(pad).append("    then:\n");
        }
        nested.append("  ".repeat(3 + depth * 2)).append("- set: {leaf: \"1\"}\n");

        ValidationResult inRepeat = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2),
                () -> validate("1.9.0", nested.toString()));
        assertThat(messages(inRepeat)).containsExactly("if / repeat 最多嵌套 3 层");

        StringBuilder plainNested = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            String pad = "  ".repeat(i * 2);
            plainNested.append(pad).append("- if:\n");
            plainNested.append(pad).append("    present: flag\n");
            plainNested.append(pad).append("    then:\n");
        }
        plainNested.append("  ".repeat(depth * 2)).append("- set: {leaf: \"1\"}\n");

        ValidationResult outside = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2),
                () -> validate("1.9.0", plainNested.toString()));
        assertThat(messages(outside)).containsExactly("if / repeat 最多嵌套 3 层");
    }

    @Test
    void repeatCursorPaginationValidatesCleanlyAndStillCatchesUndeclaredNames() {
        ValidationResult cursor = validate("1.9.0", """
                - repeat:
                    max: 5
                    steps:
                      - if:
                          present: cursor
                          then:
                            - request: {method: GET, path: /list, query: {cursor: "{{cursor}}"}}
                          else:
                            - request: {method: GET, path: /list}
                      - extract:
                          rows: {jsonpath: "$.rows"}
                          cursor: {jsonpath: "$.next_cursor", optional: true}
                    collect: {all_rows: rows}
                    until: {not_present: cursor}
                - set: {out: "{{all_rows}}", rounds: "{{loop.rounds}}", cut: "{{loop.truncated}}"}
                """);
        assertThat(cursor.errors()).isEmpty();

        ValidationResult missing = validate("1.9.0", """
                - repeat:
                    max: 5
                    steps:
                      - request: {method: GET, path: "/list/{{never_written}}"}
                      - extract:
                          rows: {jsonpath: "$.rows"}
                    collect: {all_rows: rows}
                """);
        assertThat(missing.errors()).extracting(ValidationIssue::code)
                .containsExactly(ValidationCodes.UNDECLARED_VARIABLE);

        ValidationResult earlyCollect = validate("1.9.0", """
                - repeat:
                    max: 5
                    steps:
                      - request: {method: GET, path: "/list/{{all_rows}}"}
                      - extract:
                          rows: {jsonpath: "$.rows"}
                    collect: {all_rows: rows}
                """);
        assertThat(earlyCollect.errors()).extracting(ValidationIssue::code)
                .containsExactly(ValidationCodes.UNDECLARED_VARIABLE);
    }

    @Test
    void loopIsReservedForExtractSetScriptAskAndCollectWhileReadableInRepeat() {
        ValidationResult writes = validate("1.9.0", """
                - request: {method: GET, path: /list}
                - extract:
                    loop: {jsonpath: "$.x"}
                - set: {loop: "1"}
                - script:
                    code: "return 1;"
                    into: loop
                - repeat:
                    max: 2
                    steps:
                      - set: {a: "1"}
                      - if:
                          present: a
                          then:
                            - set: {loop: "2"}
                    collect: {loop: a}
                """);
        assertThat(writes.errors())
                .filteredOn(issue -> ValidationCodes.RESERVED_VARIABLE.equals(issue.code())
                        && "不能写入保留变量 loop".equals(issue.message()))
                .extracting(ValidationIssue::path)
                .containsExactlyInAnyOrder(
                        "actions.run.steps[1].loop",
                        "actions.run.steps[2].loop",
                        "actions.run.steps[3].into",
                        "actions.run.steps[4].repeat.steps[1].if.then[0].loop",
                        "actions.run.steps[4].repeat.collect.loop");

        ValidationResult legacy = validate("1.0.0", """
                - set: {loop: "1"}
                """);
        assertThat(legacy.errors())
                .extracting(ValidationIssue::code, ValidationIssue::path, ValidationIssue::message)
                .containsExactly(org.assertj.core.api.Assertions.tuple(
                        ValidationCodes.RESERVED_VARIABLE,
                        "actions.run.steps[0].loop",
                        "不能写入保留变量 loop"));

        ValidationResult read = validate("1.9.0", """
                - repeat:
                    max: 3
                    steps:
                      - request: {method: GET, path: "/p/{{loop.index}}"}
                - set: {r: "{{loop.rounds}}", t: "{{loop.truncated}}"}
                """);
        assertThat(read.errors()).isEmpty();
    }

    @Test
    void unknownRequestKeysAreErrorOnlyInOneNineAndWarningOnlyBelowOneNine() {
        String step = """
                - request: {method: GET, path: /a, params: {a: b}}
                """;
        ValidationResult strict = validate("1.9.0", step);
        assertThat(strict.errors()).extracting(ValidationIssue::code)
                .containsExactly(ValidationCodes.IGNORED_REQUEST_FIELD);
        assertThat(strict.errors().get(0).message()).contains("未知的 request 键 params");
        assertThat(strict.warnings()).extracting(ValidationIssue::code)
                .doesNotContain(ValidationCodes.IGNORED_REQUEST_FIELD);

        ValidationResult legacy = validate("1.8.0", step);
        assertThat(legacy.errors()).isEmpty();
        assertThat(legacy.warnings()).extracting(ValidationIssue::code, ValidationIssue::message)
                .contains(org.assertj.core.api.Assertions.tuple(
                        ValidationCodes.IGNORED_REQUEST_FIELD, "这个字段不会被发送"));

        String aliveStrictYaml = conch("1.9.0", "- request: {method: GET, path: /a}", "").replace("actions:", """
                auth:
                  steps:
                    - request: {method: GET, path: /login}
                  alive_check:
                    request: {method: GET, path: /alive, params: {a: b}}
                actions:""");
        ValidationResult aliveStrict = ConchValidator.validate(
                ConchParser.parse(aliveStrictYaml), ConchValidator.Context.of(SemVer.parse("1.9.0")));
        assertThat(aliveStrict.errors())
                .extracting(ValidationIssue::code, ValidationIssue::path)
                .containsExactly(org.assertj.core.api.Assertions.tuple(
                        ValidationCodes.IGNORED_REQUEST_FIELD, "auth.alive_check.request.params"));
        assertThat(aliveStrict.warnings()).extracting(ValidationIssue::code)
                .doesNotContain(ValidationCodes.IGNORED_REQUEST_FIELD);

        ValidationResult aliveLegacy = ConchValidator.validate(
                ConchParser.parse(aliveStrictYaml.replace("min_crab_version: 1.9.0", "min_crab_version: 1.8.0")),
                ConchValidator.Context.of(SemVer.parse("1.9.0")));
        assertThat(aliveLegacy.errors()).isEmpty();
        assertThat(aliveLegacy.warnings())
                .extracting(ValidationIssue::code, ValidationIssue::path, ValidationIssue::message)
                .contains(org.assertj.core.api.Assertions.tuple(
                        ValidationCodes.IGNORED_REQUEST_FIELD, "auth.alive_check.request.params", "这个字段不会被发送"));
    }

    private static String withAuth(String min, String line, String extra) {
        return conch(min, "- request: {method: GET, path: /a}", extra).replace("actions:", """
                auth:
                  %s
                  steps:
                    - request: {method: GET, path: /login}
                actions:""".formatted(line));
    }

    private static ValidationResult validate(String min, String steps) {
        return ConchValidator.validate(
                ConchParser.parse(conch(min, steps, "")), ConchValidator.Context.of(SemVer.parse("1.9.0")));
    }

    private static List<String> messages(ValidationResult result) {
        return result.errors().stream().map(ValidationIssue::message).toList();
    }

    private static String conch(String min, String steps, String extra) {
        return """
                conch: flow_conch
                version: 1.0.0
                driver: http
                min_crab_version: %s
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                actions:
                  run:
                    title: 能力
                    description: 说明
                    %s
                    output:
                      value: {type: string, description: 结果, value: ok}
                    examples:
                      - input: {}
                        output: {value: ok}
                    notes: 注意
                    steps:
                %s
                """.formatted(min, extra, steps.stripIndent().indent(6).stripTrailing());
    }
}
