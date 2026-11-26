package dev.hermit.crab.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.Origin;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

class McpCatalogTest {

    private static final ApiKeyPrincipal ALL_KEY = new ApiKeyPrincipal(
            "k-all", "all", ApiKeyScope.explicit(true, Set.of(), Set.of(), Set.of(), false));

    @Test
    void parseGuideUriRejectsOverlappingPrefixAndSuffixWithoutThrowing() {
        ConchRegistry registry = new ConchRegistry();
        registry.put(ConchParser.parse(groupedYaml("oa", 1)), Origin.OFFICIAL);
        McpCatalog catalog = new McpCatalog(registry, (c, a) -> false, () -> false, 40);

        assertThat(catalog.guide(ALL_KEY, "hermit://conches/guide")).isEmpty();
        assertThat(catalog.guide(ALL_KEY, "hermit://conches//guide")).isEmpty();
        assertThat(catalog.guide(ALL_KEY, "hermit://conches/oa/guide")).isPresent();
    }

    @Test
    void directLimitZeroForcesDirectoryModeEvenWithZeroVisibleCapabilities() {
        ConchRegistry registry = new ConchRegistry();
        McpCatalog catalog = new McpCatalog(registry, (c, a) -> false, () -> false, 0);

        assertThat(catalog.directory(ALL_KEY)).isTrue();
        List<String> names = catalog.tools(ALL_KEY, "2025-11-25").stream()
                .map(McpSchema.Tool::name)
                .toList();
        assertThat(names).containsExactlyInAnyOrder(
                McpCatalog.LIST_SYSTEMS,
                McpCatalog.SEARCH,
                McpCatalog.DESCRIBE,
                McpCatalog.CALL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchSplitsTheQueryIntoTermsAndAlsoMatchesTheConchAndCapabilityName() {
        ConchRegistry registry = new ConchRegistry();
        registry.put(ConchParser.parse("""
                conch: group_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:8080
                title: 集团 OA
                description: 办公自动化系统。
                credentials: {}
                actions:
                  list_todo:
                    title: 查询待办
                    description: 列出等我处理的事项。
                    mcp: {read_only: true}
                    steps: []
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: yes}
                  leave_apply:
                    title: 提交请假申请
                    description: 提交请假（休假、调休）申请，进入审批。
                    steps: []
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: yes}
                """), Origin.OFFICIAL);
        registry.put(ConchParser.parse("""
                conch: expense
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:8081
                title: 报销系统
                description: 费用报销。
                credentials: {}
                actions:
                  submit:
                    title: 提交报销单
                    description: 提交费用报销，进入审批。
                    steps: []
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: yes}
                """), Origin.OFFICIAL);
        McpCatalog catalog = new McpCatalog(registry, (c, a) -> false, () -> false, 0);
        java.util.function.Function<String, List<String>> names = query ->
                ((List<java.util.Map<String, Object>>) catalog.search(ALL_KEY, query, null, null, false).get("matches"))
                        .stream().map(row -> (String) row.get("name")).toList();

        // 只写在规则包标题、说明里的词也能搜到这个包的能力。
        assertThat(names.apply("OA")).containsExactly("group_oa__list_todo", "group_oa__leave_apply");
        assertThat(names.apply("办公自动化")).hasSize(2);
        // 多个词不必连在一起；全部命中的能力存在时只给这些。
        assertThat(names.apply("审批 休假")).containsExactly("group_oa__leave_apply");
        assertThat(names.apply("OA 待办")).containsExactly("group_oa__list_todo");
        // 中文输入法的全角空格也算分隔。
        assertThat(names.apply("　OA　待办 ")).containsExactly("group_oa__list_todo");
        // 没有能力命中全部词时退到部分命中，命中多的在前，同分时能力自己命中的在前。
        assertThat(names.apply("OA 审批 出差"))
                .containsExactly("group_oa__leave_apply", "expense__submit", "group_oa__list_todo");
        // 能力的英文名也可以搜。
        assertThat(names.apply("leave_apply")).containsExactly("group_oa__leave_apply");
        assertThat(names.apply("GROUP_OA__LEAVE_APPLY")).containsExactly("group_oa__leave_apply");
        assertThat(names.apply("不存在的词")).isEmpty();
        assertThat(catalog.search(ALL_KEY, "审批", null, null, false).get("total")).isEqualTo(2);
    }

    @Test
    void describeCarriesTheForwardedToolOutputSchemaAndGuideNamesTheDirectoryAccountSelector() {
        ConchRegistry registry = new ConchRegistry();
        registry.put(ConchParser.parse("""
                conch: vendor_crm
                version: 9.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户
                description: 客户系统。
                credentials:
                  token: {type: secret, label: 令牌, required: true}
                upstream:
                  url: https://crm.example.com/mcp
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称搜索。
                    mcp: {read_only: true}
                    input_schema: {type: object}
                    output_schema:
                      $schema: https://json-schema.org/draft/2020-12/schema
                      type: object
                      properties:
                        total: {type: integer}
                  ping:
                    tool: ping
                    title: 探测
                    description: 探测。
                    mcp: {read_only: true}
                    input_schema: {type: object}
                """), Origin.OFFICIAL);
        McpCatalog catalog = new McpCatalog(registry, (c, a) -> false, () -> false, 0);

        Object schema = catalog.describe(ALL_KEY, "vendor_crm__search_customer").orElseThrow().get("outputSchema");
        assertThat(schema).isInstanceOf(java.util.Map.class);
        assertThat(schema.toString()).contains("total").doesNotContain("$schema");
        assertThat(catalog.describe(ALL_KEY, "vendor_crm__ping").orElseThrow()).doesNotContainKey("outputSchema");

        assertThat(catalog.guide(ALL_KEY, "hermit://conches/vendor_crm/guide").orElseThrow())
                .contains("经 `call_capability` 调用时，用它自己的 `account` 参数")
                .contains("不放进 `arguments`");
    }

    @Test
    void toolsScansVisibleCapabilitiesOnceToAvoidModeSplit() {
        ConchRegistry registry = new ConchRegistry();
        registry.put(ConchParser.parse(groupedYaml("oa", 1)), Origin.OFFICIAL);
        AtomicInteger checks = new AtomicInteger();
        McpCatalog catalog = new McpCatalog(
                registry,
                (c, a) -> {
                    checks.incrementAndGet();
                    return false;
                },
                () -> false,
                40);

        List<McpSchema.Tool> tools = catalog.tools(ALL_KEY, "2025-11-25");
        assertThat(tools).hasSize(2);
        // Each action is evaluated once during a single visible() scan (not once in directory() and again in tools()).
        assertThat(checks.get()).isEqualTo(2);
    }

    @Test
    void directModeGuideListsAllTopLevelAndAvoidsSearchCapabilitiesHint() {
        ConchRegistry registry = new ConchRegistry();
        // 12 ungrouped (> TOP_LEVEL_INLINE_LIMIT) + 1 grouped = 13 visible capabilities
        registry.put(ConchParser.parse(groupedYaml("oa", 12)), Origin.OFFICIAL);

        McpCatalog directCatalog = new McpCatalog(registry, (c, a) -> false, () -> false, 40);
        assertThat(directCatalog.directory(ALL_KEY)).isFalse();
        String directGuide = directCatalog.guide(ALL_KEY, "hermit://conches/oa/guide").orElseThrow();
        assertThat(directGuide)
                .contains("## 顶层能力")
                .contains("### oa__top_1")
                .contains("### oa__top_12")
                .contains("## 分组目录")
                .contains("tools/list")
                .doesNotContain(McpCatalog.SEARCH);

        McpCatalog directoryCatalog = new McpCatalog(registry, (c, a) -> false, () -> false, 0);
        assertThat(directoryCatalog.directory(ALL_KEY)).isTrue();
        String directoryGuide = directoryCatalog.guide(ALL_KEY, "hermit://conches/oa/guide").orElseThrow();
        assertThat(directoryGuide)
                .contains("共有 12 个未分组顶层能力")
                .contains(McpCatalog.SEARCH)
                .contains("ungrouped: true")
                .doesNotContain("### oa__top_12");
    }

    @Test
    @SuppressWarnings("unchecked")
    void devConchSearchListsAllOnEmptyQueryAndMatchesPackageTitle() {
        ConchRegistry registry = new ConchRegistry();
        McpCatalog catalog = new McpCatalog(registry, (c, a) -> false, () -> true, 0);
        ApiKeyPrincipal devKey = new ApiKeyPrincipal(
                "k-dev", "dev", ApiKeyScope.explicit(true, Set.of(), Set.of(), Set.of(), false, true));

        List<java.util.Map<String, Object>> allDev = (List<java.util.Map<String, Object>>)
                catalog.search(devKey, null, McpCatalog.DEV_CONCH, null, false, true).get("matches");
        assertThat(allDev).extracting(row -> (String) row.get("name"))
                .contains("hermit_dev__guide", "hermit_dev__auth_list", "hermit_dev__validate", "hermit_dev__save_local");

        List<java.util.Map<String, Object>> byTitle = (List<java.util.Map<String, Object>>)
                catalog.search(devKey, "开发工具", null, null, false, true).get("matches");
        assertThat(byTitle).isNotEmpty();
        assertThat(McpCatalog.DEV_DESCRIPTION).contains("hermit_dev__auth_list");
    }

    private static String groupedYaml(String conch, int ungroupedCount) {
        StringBuilder actions = new StringBuilder();
        for (int i = 1; i <= ungroupedCount; i++) {
            actions.append("""
                      top_%d:
                        title: 顶层 %d
                        description: 顶层能力 %d
                        idempotent: true
                        mcp: {expose: true, read_only: true}
                        steps:
                          - set: {ok: "yes"}
                        output:
                          ok: {type: string, description: 结果}
                        examples:
                          - input: {}
                            output: {ok: "yes"}
                        notes: 无
                        related: []
                    """.formatted(i, i, i));
        }
        actions.append("""
                  hr_read:
                    group: hr
                    title: 查询员工
                    description: 人事能力
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {ok: "yes"}
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: "yes"}
                    notes: 无
                    related: []
                """);
        return """
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.10.0
                base_url: http://127.0.0.1:9
                title: 示例系统
                description: 测试目录与指南
                credentials: {}
                groups:
                  hr:
                    title: 人事
                actions:
                %s
                """.formatted(conch, actions);
    }
}
