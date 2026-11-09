package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hermit.common.validate.ValidationIssue;

class ImportIssueLinesTest {

    @Test
    void locatesAKeyAndLeavesAMissingKeyWithoutALine() {
        String yaml = """
                conch: local_ping
                version: 1.0.0
                driver: http
                actions:
                  ping:
                    steps:
                      - set:
                          marker: ok
                """;
        List<ValidationIssue> located = ImportIssueLines.locate(yaml, List.of(
                new ValidationIssue("DRIVER", "driver", "driver 只允许 http 或 mcp"),
                new ValidationIssue("PARSE", "min_crab_version", "min_crab_version: 缺少 min_crab_version"),
                new ValidationIssue("SCRIPT_STEP", "actions.ping.steps[0]", "步骤")));

        assertThat(located.get(0).line()).isEqualTo(lineOf(yaml, "driver:"));
        assertThat(located.get(0).message()).isEqualTo("driver 只允许 http 或 mcp");
        assertThat(located.get(1).line()).isNull();
        assertThat(located.get(1).message()).isEqualTo("min_crab_version: 缺少 min_crab_version");
        assertThat(located.get(2).line()).isEqualTo(lineOf(yaml, "- set:"));
    }

    @Test
    void usesTheLineAlreadyNamedInTheMessageAndIgnoresBrokenYaml() {
        String broken = "conch: \"unterminated\nversion: 1.0.0\n";
        List<ValidationIssue> located = ImportIssueLines.locate(broken, List.of(
                new ValidationIssue("PARSE", "", "YAML 无法解析（第 2 行）")));
        assertThat(located).singleElement().satisfies(issue -> {
            assertThat(issue.line()).isEqualTo(2);
            assertThat(issue.message()).isEqualTo("YAML 无法解析（第 2 行）");
            assertThat(issue.message()).doesNotContain("unterminated");
        });
    }

    private static int lineOf(String text, String token) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(token)) {
                return i + 1;
            }
        }
        throw new AssertionError(token);
    }
}
