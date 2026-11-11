package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DuplicateActionsTest {

    @Test
    void reportsEachRepeatedActionNameWithItsLine() {
        String yaml = """
                conch: legacy_oa
                actions:
                  list_todo:
                    title: 查询
                  "list_todo":
                    title: 另一个
                  list_done:
                    title: 已办
                  list_done: { title: 再一次 }
                """;
        assertThat(DuplicateActions.find(yaml)).containsExactly(
                new ValidationIssue(
                        ValidationCodes.DUPLICATE_ACTION,
                        "actions.list_todo",
                        "能力名 list_todo 重复",
                        lineOf(yaml, "\"list_todo\":")),
                new ValidationIssue(
                        ValidationCodes.DUPLICATE_ACTION,
                        "actions.list_done",
                        "能力名 list_done 重复",
                        lineOf(yaml, "list_done: { title: 再一次 }")));
    }

    @Test
    void ignoresOtherDuplicateKeysAndUniqueActions() {
        String yaml = """
                title: 一份
                title: 另一份
                actions:
                  list_todo:
                    title: 查询
                    title: 覆盖
                  list_done:
                    title: 已办
                """;
        assertThat(DuplicateActions.find(yaml)).isEmpty();
        assertThat(DuplicateActions.find("actions: [list_todo]\n")).isEmpty();
        assertThat(DuplicateActions.find(":\n")).isEmpty();
        assertThat(DuplicateActions.find("")).isEmpty();
    }

    @Test
    void sameAnchoredMappingIsNotCountedTwice() {
        String yaml = """
                actions: &items
                  list_todo:
                    title: 查询
                actions: *items
                """;
        assertThat(DuplicateActions.find(yaml)).isEmpty();
    }

    private static int lineOf(String yaml, String text) {
        String[] lines = yaml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(text)) {
                return i + 1;
            }
        }
        throw new AssertionError("missing line " + text);
    }
}
