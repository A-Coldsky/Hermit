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
