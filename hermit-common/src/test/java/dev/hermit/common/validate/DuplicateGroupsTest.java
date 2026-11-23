package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DuplicateGroupsTest {

    @Test
    void reportsRepeatedTopLevelGroupsAndChildrenWithLines() {
        String yaml = """
                conch: legacy_oa
                groups:
                  approval:
                    title: 审批
                    children:
                      leave:
                        title: 请假
                      "leave":
                        title: 重复请假
                      expense: { title: 报销 }
                      expense: { title: 重复报销 }
                  "approval":
                    title: 重复审批
                  hr:
                    title: 人事
                    children:
                      leave:
                        title: 同名但不同父组不重复
                  hr: { title: 重复人事 }
                """;
        assertThat(DuplicateGroups.find(yaml)).containsExactly(
                new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        "groups.approval.children.leave",
                        "子分组名 leave 重复",
                        lineOf(yaml, "\"leave\":")),
                new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        "groups.approval.children.expense",
                        "子分组名 expense 重复",
                        lineOf(yaml, "expense: { title: 重复报销 }")),
                new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        "groups.approval",
                        "分组名 approval 重复",
                        lineOf(yaml, "\"approval\":")),
                new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        "groups.hr",
                        "分组名 hr 重复",
                        lineOf(yaml, "hr: { title: 重复人事 }")));
    }

    @Test
    void detectsDuplicatesAcrossRepeatedGroupsAndChildrenBlocks() {
        String yaml = """
                conch: legacy_oa
                groups:
                  approval:
                    title: 审批
                    children:
                      leave: { title: 一 }
                    children:
                      leave: { title: 二 }
                groups:
                  approval:
                    title: 另一份
                """;
        assertThat(DuplicateGroups.find(yaml)).containsExactly(
                new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        "groups.approval.children.leave",
                        "子分组名 leave 重复",
                        lineOf(yaml, "leave: { title: 二 }")),
                new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        "groups.approval",
                        "分组名 approval 重复",
                        lineOf(yaml, "title: 另一份") - 1));
    }

    @Test
    void ignoresOtherDuplicateKeysUniqueGroupsAndMalformedYaml() {
        String yaml = """
                title: 一份
                title: 另一份
                groups:
                  approval:
                    title: 一
                    title: 二
                    children:
                      leave:
                        title: 请假
                  hr:
                    title: 人事
                    children:
                      leave:
                        title: 人事请假
                """;
        assertThat(DuplicateGroups.find(yaml)).isEmpty();
        assertThat(DuplicateGroups.find("groups: [approval]\n")).isEmpty();
        assertThat(DuplicateGroups.find("groups:\n  a:\n    children: [b]\n")).isEmpty();
        assertThat(DuplicateGroups.find(":\n")).isEmpty();
        assertThat(DuplicateGroups.find("")).isEmpty();
        assertThat(DuplicateGroups.find(null)).isEmpty();
    }

    @Test
    void sameAnchoredMappingIsNotCountedTwice() {
        String yaml = """
                groups: &all_groups
                  approval:
                    title: 审批
                    children: &items
                      leave:
                        title: 请假
                    children: *items
                groups: *all_groups
                """;
        assertThat(DuplicateGroups.find(yaml)).isEmpty();
    }

    @Test
    void fallsBackToParentPathForEmptyOrNonSimpleNames() {
        String yaml = """
                groups:
                  "": { title: 一 }
                  "": { title: 二 }
                  "bad name":
                    title: 一
                    children:
                      "x y": { title: 一 }
                      "x y": { title: 二 }
                  ok:
                    title: 正常
                    children:
                      "": { title: 一 }
                      "": { title: 二 }
                """;
        assertThat(DuplicateGroups.find(yaml)).containsExactly(
                new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        "groups",
                        "分组名重复",
                        lineOf(yaml, "  \"\": { title: 二 }")),
                new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        "groups",
                        "子分组名 x y 重复",
                        lineOf(yaml, "\"x y\": { title: 二 }")),
                new ValidationIssue(
                        ValidationCodes.GROUP_NAME,
                        "groups.ok.children",
                        "子分组名重复",
                        lineOf(yaml, "      \"\": { title: 二 }")));
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
