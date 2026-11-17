package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DuplicateScriptsTest {

    @Test
    void reportsRepeatedLibraryNamesWithTheirLine() {
        String yaml = """
                conch: sample_conch
                scripts:
                  lib:
                    unused
                  "lib": |
                    function leaked() { return "source-marker"; }
                  other: "function b(){return 1;}"
                """;
        assertThat(DuplicateScripts.find(yaml)).containsExactly(new ValidationIssue(
                ValidationCodes.SCRIPT_STEP,
                "scripts.lib",
                "脚本库 lib 重复",
                lineOf(yaml, "\"lib\":")));
        assertThat(DuplicateScripts.find(yaml).get(0).message()).doesNotContain("source-marker");
    }

    @Test
    void ignoresOtherDuplicateKeys() {
        String yaml = """
                title: 一份
                title: 另一份
                scripts:
                  lib: "function a(){return 1;}"
                """;
        assertThat(DuplicateScripts.find(yaml)).isEmpty();
        assertThat(DuplicateScripts.find("")).isEmpty();
        assertThat(DuplicateScripts.find("scripts: [lib]\n")).isEmpty();
    }

    private static int lineOf(String yaml, String line) {
        String[] lines = yaml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(line)) {
                return i + 1;
            }
        }
        throw new AssertionError(line);
    }
}
