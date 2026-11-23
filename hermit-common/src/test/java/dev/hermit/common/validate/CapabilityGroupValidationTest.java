package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hermit.common.model.CapabilityGroups;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

class CapabilityGroupValidationTest {

    private static final ConchValidator.Context CTX =
            ConchValidator.Context.of(SemVer.parse("1.10.0"));

    private static String baseGrouped(String minVersion, String groupsYaml, String actionsYaml) {
        return """
                conch: sample_oa
                version: 1.0.0
                driver: http
                min_crab_version: %s
                base_url: http://127.0.0.1:8080
                title: 示例系统
                description: 测试分组
                credentials: {}
                %s
                actions:
                %s
                """.formatted(minVersion, groupsYaml, actionsYaml);
    }

    private static String simpleAction(String name, String groupLine) {
        String group = groupLine == null ? "" : "    group: " + groupLine + "\n";
        return """
                  %s:
                %s    title: 能力 %s
                    description: 说明 %s
                    method: GET
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {ok: "1"}
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: "1"}
                    notes: 备注
                    related: []
                """.formatted(name, group, name, name);
    }

    @Test
    void acceptsTwoLevelGroupsMixedWithTopLevelAndDirectParentActions() {
        String yaml = baseGrouped("1.10.0", """
                groups:
                  approval:
                    title: 审批管理
                    description: 流程审批
                    children:
                      leave:
                        title: 请假审批
                        description: 请假单查询与审批
                      expense:
                        title: 报销审批
                  hr:
                    title: 人事档案
                """,
                simpleAction("get_me", null)
                        + simpleAction("list_all_todos", "approval")
                        + simpleAction("list_leave", "approval/leave")
                        + simpleAction("list_expense", "approval/expense")
                        + simpleAction("get_employee", "hr"));

        ConchDoc doc = ConchParser.parse(yaml);
        ValidationResult result = ConchValidator.validate(doc, CTX);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();

        assertThat(doc.groups().keySet()).containsExactly("approval", "hr");
        assertThat(doc.groups().get("approval").children().keySet()).containsExactly("leave", "expense");
        assertThat(doc.actions().get("get_me").group()).isNull();
        assertThat(doc.actions().get("list_all_todos").group()).isEqualTo("approval");
        assertThat(doc.actions().get("list_leave").group()).isEqualTo("approval/leave");

        assertThat(CapabilityGroups.displayTitle(doc, "approval")).isEqualTo("审批管理");
        assertThat(CapabilityGroups.displayTitle(doc, "approval/leave")).isEqualTo("审批管理 / 请假审批");
        assertThat(CapabilityGroups.matches("approval", "approval")).isTrue();
        assertThat(CapabilityGroups.matches("approval/leave", "approval")).isTrue();
        assertThat(CapabilityGroups.matches("approval_extra", "approval")).isFalse();
        assertThat(CapabilityGroups.matches("approval/leave", "approval/leave")).isTrue();
        assertThat(CapabilityGroups.matches("approval/expense", "approval/leave")).isFalse();
        assertThat(CapabilityGroups.matches(null, "approval")).isFalse();
        assertThat(CapabilityGroups.actionsInGroup(doc, "approval"))
                .extracting(a -> a.name())
                .containsExactly("list_all_todos", "list_leave", "list_expense");
        assertThat(CapabilityGroups.actionsInGroup(doc, "approval/leave"))
                .extracting(a -> a.name())
                .containsExactly("list_leave");
    }

    @Test
    void oldFlatPackageNeedsNoGroupsAndNoHigherVersion() {
        String yaml = baseGrouped("1.0.0", "", simpleAction("show_status", null));
        ConchDoc doc = ConchParser.parse(yaml);
        ValidationResult result = ConchValidator.validate(doc, ConchValidator.Context.of(SemVer.parse("1.9.0")));
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(CapabilityGroups.usesGroups(doc)).isFalse();
    }

    @Test
    void rejectsGroupsWhenMinCrabVersionBelow110() {
        String yaml = baseGrouped("1.9.0", """
                groups:
                  hr:
                    title: 人事档案
                """, simpleAction("get_employee", "hr"));
        ConchDoc doc = ConchParser.parse(yaml);
        ValidationResult result = ConchValidator.validate(doc, CTX);
        assertThat(result.errors()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo(ValidationCodes.MIN_CRAB_VERSION);
            assertThat(issue.message()).contains("1.10.0");
        });
    }

    @Test
    void rejectsEmptyOrNullGroupsAndNullActionGroupBelow110WithoutChangingCanonicalJson() throws Exception {
        String emptyGroupsLow = baseGrouped("1.9.0", "groups: {}", simpleAction("get_me", null));
        String nullGroupsLow = baseGrouped("1.9.0", "groups: null", simpleAction("get_me", null));
        String nullActionGroupLow = baseGrouped("1.9.0", "", simpleAction("get_me", "null"));

        for (String lowYaml : List.of(emptyGroupsLow, nullGroupsLow, nullActionGroupLow)) {
            ValidationResult result = ConchValidator.validate(ConchParser.parse(lowYaml), CTX);
            assertThat(result.errors()).as(lowYaml).anySatisfy(issue -> {
                assertThat(issue.code()).isEqualTo(ValidationCodes.MIN_CRAB_VERSION);
                assertThat(issue.message()).contains("1.10.0");
            });
        }

        // 1.10.0 及以上允许显式空/null 的 groups 或 action.group，且规范 JSON 与省略写法完全一致。
        ConchDoc plain = ConchParser.parse(baseGrouped("1.10.0", "", simpleAction("get_me", null)));
        ConchDoc emptyGroups = ConchParser.parse(baseGrouped("1.10.0", "groups: {}", simpleAction("get_me", null)));
        ConchDoc nullGroups = ConchParser.parse(baseGrouped("1.10.0", "groups: null", simpleAction("get_me", null)));
        ConchDoc nullActionGroup = ConchParser.parse(baseGrouped("1.10.0", "", simpleAction("get_me", "null")));

        com.fasterxml.jackson.databind.json.JsonMapper canonical = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        String plainJson = canonical.writeValueAsString(plain);
        assertThat(plainJson).doesNotContain("groupsDeclared").doesNotContain("groupDeclared").doesNotContain("originsDeclared");

        for (ConchDoc doc : List.of(emptyGroups, nullGroups, nullActionGroup)) {
            ValidationResult ok = ConchValidator.validate(doc, CTX);
            assertThat(ok.errors()).isEmpty();
            assertThat(ok.warnings()).isEmpty();
            assertThat(doc).isEqualTo(plain);
            assertThat(doc.hashCode()).isEqualTo(plain.hashCode());
            assertThat(canonical.writeValueAsString(doc)).isEqualTo(plainJson);
        }
    }

    @Test
    void rejectsThirdLevelChildrenAtParseTime() {
        String yaml = baseGrouped("1.10.0", """
                groups:
                  a:
                    title: 一级
                    children:
                      b:
                        title: 二级
                        children:
                          c:
                            title: 三级
                """, simpleAction("act", "a/b"));
        assertThatThrownBy(() -> ConchParser.parse(yaml))
                .isInstanceOf(ConchParseException.class)
                .hasMessageContaining("最多支持两层");
    }

    @Test
    void validatesGroupNamesTitlesUnknownRefsAndEmptyWarningsWithLines() {
        String yaml = baseGrouped("1.10.0", """
                groups:
                  BadName:
                    description: 缺标题且命名非法
                    children:
                      ok_child:
                        title: 子组
                  empty_top:
                    title: 空一级组
                  used_top:
                    title: 已用一级组
                    children:
                      used_child:
                        title: 已用子组
                      empty_child:
                        title: 空子组
                """,
                simpleAction("a1", "used_top/used_child")
                        + simpleAction("a2", "missing_group")
                        + simpleAction("a3", "\"\"")
                        + simpleAction("a4", "used_top/used_child/deep"));

        ConchDoc doc = ConchParser.parse(yaml);
        ValidationResult result = ConchValidator.validate(doc, CTX);
        List<ValidationIssue> errors = IssueLines.locate(yaml, result.errors());
        List<ValidationIssue> warnings = IssueLines.locate(yaml, result.warnings());

        assertThat(errors).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo(ValidationCodes.GROUP_NAME);
            assertThat(i.path()).isEqualTo("groups.BadName");
            assertThat(i.line()).isNotNull();
        });
        assertThat(errors).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo(ValidationCodes.MISSING_TITLE);
            assertThat(i.path()).isEqualTo("groups.BadName.title");
        });
        assertThat(errors).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo(ValidationCodes.UNKNOWN_GROUP);
            assertThat(i.path()).isEqualTo("actions.a2.group");
            assertThat(i.line()).isNotNull();
        });
        assertThat(errors).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo(ValidationCodes.UNKNOWN_GROUP);
            assertThat(i.path()).isEqualTo("actions.a3.group");
            assertThat(i.message()).contains("不能为空字符串");
        });
        assertThat(errors).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo(ValidationCodes.UNKNOWN_GROUP);
            assertThat(i.path()).isEqualTo("actions.a4.group");
            assertThat(i.message()).contains("两层");
        });
        assertThat(warnings).extracting(ValidationIssue::code, ValidationIssue::path)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(ValidationCodes.EMPTY_GROUP, "groups.empty_top"),
                        org.assertj.core.groups.Tuple.tuple(ValidationCodes.EMPTY_GROUP, "groups.used_top.children.empty_child"));
    }

    @Test
    void validatesGroupsOnMcpDriverToo() {
        String yaml = """
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.10.0
                title: 客户系统
                description: 源站 MCP
                credentials: {}
                upstream:
                  url: https://crm.intra.example.com/mcp
                  transport: streamable_http
                  auth: {type: headers}
                groups:
                  customer:
                    title: 客户管理
                    children:
                      search:
                        title: 客户查询
                actions:
                  search_customer:
                    group: customer/search
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称搜索客户
                    input_schema: {type: object}
                    examples:
                      - input: {}
                        output: {}
                    notes: 只读
                    related: []
                  ping_crm:
                    tool: ping
                    title: 健康探测
                    description: 顶层直挂能力
                    input_schema: {type: object}
                    examples:
                      - input: {}
                        output: {}
                    notes: 只读
                    related: []
                """;
        ConchDoc doc = ConchParser.parse(yaml);
        ValidationResult result = ConchValidator.validate(doc, CTX);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(doc.actions().get("search_customer").group()).isEqualTo("customer/search");
        assertThat(doc.actions().get("ping_crm").group()).isNull();
    }

    @Test
    void diffTracksGroupAdditionsRemovalsMetadataChangesAndActionMoves() {
        ConchDoc oldDoc = ConchParser.parse(baseGrouped("1.10.0", """
                groups:
                  hr:
                    title: 人事
                    description: 旧说明
                  old_mod:
                    title: 旧模块
                """, simpleAction("a1", "hr") + simpleAction("a2", "old_mod")));

        ConchDoc newDoc = ConchParser.parse(baseGrouped("1.10.0", """
                groups:
                  hr:
                    title: 人事档案
                    description: 新说明
                  finance:
                    title: 财务
                    children:
                      reimburse:
                        title: 报销
                """, simpleAction("a1", null) + simpleAction("a2", "finance/reimburse")));

        ConchDiff diff = ConchDiff.between(oldDoc, newDoc);
        assertThat(diff.addedGroups()).containsExactly("finance", "finance/reimburse");
        assertThat(diff.removedGroups()).containsExactly("old_mod");
        assertThat(diff.changedGroups()).containsExactly("hr");
        assertThat(diff.actionGroupChanges()).containsExactly(
                new ActionGroupChange("a1", "hr", null),
                new ActionGroupChange("a2", "old_mod", "finance/reimburse"));
        assertThat(diff.changedActions()).isEmpty();
    }
}
