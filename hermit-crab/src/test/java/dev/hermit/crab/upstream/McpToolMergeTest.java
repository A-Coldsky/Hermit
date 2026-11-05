package dev.hermit.crab.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.hermit.common.mcp.ToolSnapshots;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchDiff;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ToolChange;

class McpToolMergeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant FETCHED = Instant.parse("2026-11-05T03:04:05Z");

    @Test
    void mergeKeepsLocalDecisionsAndHashesTheFullList() {
        ArrayNode tools = JSON.createArrayNode();
        tools.add(tool("searchCustomer", "Search", "上游描述", schema("q"), schema("out"),
                hints(false, true, null), null));
        tools.add(tool("stayTool", null, null, schema("same"), null, hints(null, null, true), null));
        tools.add(tool("get-annotated-message", "上游标题", "上游说明\u200B", schema("same"), null,
                hints(false, null, null), null));
        tools.add(tool("gzip-file", null, "打包\u001B[31m文件\u001B[0m\u200B。见 https://example.test/a",
                schema("path"), null, hints(false, false, true), "打包"));
        tools.add(tool("create-note", null, "记一笔", schema("text"), null, hints(true, null, true), null));
        tools.add(tool("drop-row", null, "删除一行", schema("id"), null, hints(false, null, null), null));
        tools.add(tool("admin_drop", "管理", "不要", schema("id"), null, hints(false, true, null), null));
        tools.add(tool("2fa-reset", null, "\u001B]0;hidden\u0007", schema("id"), null, hints(false, true, null), null));

        byte[] rewritten = McpToolMerge.rewrite(
                yaml().getBytes(StandardCharsets.UTF_8), nodes(tools), "2025-11-25", "crm-mcp", "3.2.0", FETCHED);
        ConchDoc doc = ConchParser.parse(new String(rewritten, StandardCharsets.UTF_8));
        assertThat(ConchValidator.validate(doc, ConchValidator.Context.of(null)).errors()).isEmpty();
        assertThat(doc.version().toString()).isEqualTo("1.4.2");
        assertThat(doc.snapshot().fetchedAt()).isEqualTo("2026-11-05T03:04:05Z");
        assertThat(doc.snapshot().protocolVersion()).isEqualTo("2025-11-25");
        assertThat(doc.snapshot().server().name()).isEqualTo("crm-mcp");
        assertThat(doc.snapshot().server().version()).isEqualTo("3.2.0");
        assertThat(doc.snapshot().toolsSha256()).isEqualTo(ToolSnapshots.toolsSha256(nodes(tools)));
        assertThat(doc.snapshot().toolsSha256()).isNotEqualTo(ToolSnapshots.toolsSha256(List.of(tools.get(0))));

        Action search = doc.actions().get("search_customer");
        assertThat(search.title()).isEqualTo("搜索客户");
        assertThat(search.description()).isEqualTo("按名称搜索。\u200B");
        assertThat(search.notes()).isEqualTo("只读查询。");
        assertThat(search.idempotent()).isTrue();
        assertThat(search.mcp().expose()).isFalse();
        assertThat(search.mcp().readOnly()).isTrue();
        assertThat(search.mcp().destructive()).isFalse();
        assertThat(search.tool().confirm()).isFalse();
        assertThat(search.tool().inputSchema()).containsKey("properties");
        assertThat(search.tool().schemaSha256())
                .isEqualTo(ToolSnapshots.schemaSha256(search.tool().inputSchema(), search.tool().outputSchema()));
        assertThat(search.tool().upstreamAnnotations()).containsEntry("destructiveHint", true);
        assertThat(search.tool().upstreamAnnotations()).doesNotContainKey("_meta");

        Action lookup = doc.actions().get("lookup");
        assertThat(lookup.tool().name()).isEqualTo("get-annotated-message");
        assertThat(lookup.title()).isEqualTo("保留标题");
        assertThat(lookup.description()).isEqualTo("保留的说明");
        assertThat(lookup.mcp().destructive()).isTrue();
        assertThat(lookup.tool().confirm()).isTrue();

        Action stayed = doc.actions().get("gzip_file");
        assertThat(stayed.tool().name()).isEqualTo("stayTool");
        assertThat(doc.actions().get("gzip_file_2").tool().name()).isEqualTo("gzip-file");
        Action gzip = doc.actions().get("gzip_file_2");
        assertThat(gzip.title()).isEqualTo("打包");
        assertThat(gzip.description()).isEqualTo("打包文件。见 https://example.test/a");
        assertThat(gzip.mcp().readOnly()).isFalse();
        assertThat(gzip.mcp().destructive()).isFalse();
        assertThat(gzip.mcp().destructiveSpecified()).isTrue();
        assertThat(gzip.tool().confirm()).isFalse();
        assertThat(gzip.idempotent()).isFalse();
        assertThat(gzip.tool().upstreamAnnotations()).containsEntry("idempotentHint", true);

        Action note = doc.actions().get("create_note");
        assertThat(note.mcp().readOnly()).isTrue();
        assertThat(note.mcp().destructive()).isFalse();
        assertThat(note.tool().confirm()).isFalse();
        assertThat(note.idempotent()).isFalse();

        Action drop = doc.actions().get("drop_row");
        assertThat(drop.mcp().readOnly()).isFalse();
        assertThat(drop.mcp().destructive()).isTrue();
        assertThat(drop.tool().confirm()).isTrue();

        Action reset = doc.actions().get("fa_reset");
        assertThat(reset.description()).isEqualTo(McpToolMerge.DESCRIPTION_FALLBACK);
        assertThat(doc.actions()).doesNotContainKeys("create_ticket", "admin_drop");

        ConchDiff diff = ConchDiff.between(ConchParser.parse(yaml()), doc);
        assertThat(names(diff, ToolChange.ADDED)).contains("gzip_file_2", "create_note", "drop_row", "fa_reset");
        assertThat(names(diff, ToolChange.REMOVED)).containsExactly("create_ticket");
        assertThat(names(diff, ToolChange.CHANGED)).contains("search_customer");
        assertThat(names(diff, ToolChange.CHANGED)).doesNotContain("lookup", "gzip_file");
    }

    @Test
    void globIsCaseSensitiveAndExcludeWins() {
        assertThat(McpToolMerge.glob("admin_*", "admin_drop")).isTrue();
        assertThat(McpToolMerge.glob("admin_*", "admin")).isFalse();
        assertThat(McpToolMerge.glob("Admin_*", "admin_drop")).isFalse();
        assertThat(McpToolMerge.glob("a?c", "abc")).isTrue();
        LinkedHashSet<String> used = new LinkedHashSet<>();
        used.add("tool");
        assertThat(McpToolMerge.localName("---", used)).isEqualTo("tool_2");
        assertThat(McpToolMerge.localName("A", used)).isEqualTo("a_");
    }

    private static List<String> names(ConchDiff diff, String kind) {
        return diff.toolChanges().stream().filter(change -> kind.equals(change.kind())).map(ToolChange::name).toList();
    }

    private static List<JsonNode> nodes(ArrayNode tools) {
        ArrayList<JsonNode> nodes = new ArrayList<>();
        tools.forEach(nodes::add);
        return nodes;
    }

    private static ObjectNode schema(String property) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject(property).put("type", "string");
        return schema;
    }

    private static ObjectNode hints(Boolean readOnly, Boolean destructive, Boolean idempotent) {
        ObjectNode hints = JSON.createObjectNode();
        if (readOnly != null) {
            hints.put("readOnlyHint", readOnly);
        }
        if (destructive != null) {
            hints.put("destructiveHint", destructive);
        }
        if (idempotent != null) {
            hints.put("idempotentHint", idempotent);
        }
        hints.putObject("_meta").put("secret", "nope");
        return hints;
    }

    private static ObjectNode tool(
            String name,
            String title,
            String description,
            ObjectNode input,
            ObjectNode output,
            ObjectNode annotations,
            String annotationTitle
    ) {
        ObjectNode tool = JSON.createObjectNode();
        tool.put("name", name);
        if (title != null) {
            tool.put("title", title);
        }
        if (description != null) {
            tool.put("description", description);
        }
        tool.set("inputSchema", input);
        if (output != null) {
            tool.set("outputSchema", output);
        }
        if (annotationTitle != null) {
            annotations.put("title", annotationTitle);
        }
        tool.set("annotations", annotations);
        tool.putArray("icons").addObject().put("src", "https://example.test/icon.png");
        return tool;
    }

    private static String yaml() {
        return """
                conch: vendor_crm
                version: 1.4.2
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户
                description: 本地开发包。
                credentials:
                  token: {type: secret, label: 令牌, required: true, description: 令牌}
                upstream:
                  url: https://crm.intra.example.com/mcp
                  transport: streamable_http
                  auth: {type: headers}
                  headers:
                    Authorization: 'Bearer {{credential.token}}'
                tools:
                  exclude: ['admin_*']
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: "按名称搜索。\u200B"
                    mcp: {expose: false, read_only: true, destructive: false}
                    confirm: false
                    idempotent: true
                    input_schema: {type: object}
                    output_schema: {type: object}
                    examples:
                      - {input: {q: 张三}, output: {ok: true}}
                    notes: 只读查询。
                    related: [search_customer]
                  gzip_file:
                    tool: stayTool
                    title: 留下
                    description: 原样留下。
                    mcp: {read_only: true, destructive: false}
                    confirm: false
                    input_schema:
                      type: object
                      properties:
                        same: {type: string}
                  lookup:
                    tool: get-annotated-message
                    title: 保留标题
                    description: 保留的说明
                    mcp: {read_only: false, destructive: true}
                    confirm: true
                    input_schema:
                      type: object
                      properties:
                        same: {type: string}
                  create_ticket:
                    tool: createTicket
                    title: 创建
                    description: 将被删除。
                    mcp: {read_only: false, destructive: true}
                    confirm: true
                    input_schema: {type: object}
                """;
    }
}
