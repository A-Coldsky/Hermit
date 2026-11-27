package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hermit.common.mcp.ToolSnapshots;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;

class McpConchDiffTest {

    @Test
    void reportsAddedRemovedAndEditedTools() {
        ConchDoc previous = doc("""
                  search_customer:
                    tool: searchCustomer
                    title: 搜索
                    description: 旧描述
                    mcp: {read_only: true}
                    input_schema: {type: object, properties: {q: {type: string}}}
                  create_ticket:
                    tool: createTicket
                    title: 创建
                    description: 创建工单
                    mcp: {read_only: false}
                    input_schema: {type: object}
                """);
        ConchDoc current = doc("""
                  search_customer:
                    tool: findCustomer
                    title: 搜索
                    description: 新描述
                    mcp: {read_only: false}
                    input_schema: {type: object, properties: {name: {type: string}}}
                  close_ticket:
                    tool: closeTicket
                    title: 关闭
                    description: 关闭工单
                    mcp: {read_only: false, destructive: true}
                    input_schema: {type: object}
                """);

        ConchDiff diff = ConchDiff.between(previous, current);
        assertThat(diff.addedActions()).containsExactly("close_ticket");
        assertThat(diff.removedActions()).containsExactly("create_ticket");
        assertThat(diff.changedActions()).extracting(ActionChange::name).containsExactly("search_customer");
        assertThat(diff.toolChanges()).extracting(ToolChange::name)
                .containsExactly("search_customer", "create_ticket", "close_ticket");

        ToolChange edited = diff.toolChanges().get(0);
        assertThat(edited.kind()).isEqualTo(ToolChange.CHANGED);
        assertThat(edited.previousUpstreamName()).isEqualTo("searchCustomer");
        assertThat(edited.upstreamName()).isEqualTo("findCustomer");
        assertThat(edited.previousDescription()).isEqualTo("旧描述");
        assertThat(edited.description()).isEqualTo("新描述");
        assertThat(edited.previousReadOnly()).isTrue();
        assertThat(edited.readOnly()).isFalse();
        assertThat(edited.previousDestructive()).isFalse();
        assertThat(edited.destructive()).isTrue();
        assertThat(edited.schemaSha256()).isNotEqualTo(edited.previousSchemaSha256());
        assertThat(edited.schemaSha256()).isEqualTo(ToolSnapshots.schemaSha256(
                current.actions().get("search_customer").tool().inputSchema(), null));

        ToolChange removed = diff.toolChanges().get(1);
        assertThat(removed.kind()).isEqualTo(ToolChange.REMOVED);
        assertThat(removed.upstreamName()).isNull();
        assertThat(removed.previousUpstreamName()).isEqualTo("createTicket");

        ToolChange added = diff.toolChanges().get(2);
        assertThat(added.kind()).isEqualTo(ToolChange.ADDED);
        assertThat(added.upstreamName()).isEqualTo("closeTicket");
        assertThat(added.destructive()).isTrue();
        assertThat(added.previousUpstreamName()).isNull();
    }

    @Test
    void schemaKeyOrderAloneIsNotAToolChange() {
        ConchDoc forward = doc("""
                  search_customer:
                    tool: searchCustomer
                    title: 搜索
                    description: 搜索
                    mcp: {read_only: true}
                    input_schema:
                      type: object
                      required: [q]
                      properties:
                        q: {type: string}
                """);
        ConchDoc reversed = doc("""
                  search_customer:
                    tool: searchCustomer
                    title: 搜索
                    description: 搜索
                    mcp: {read_only: true}
                    input_schema:
                      properties:
                        q: {type: string}
                      required: [q]
                      type: object
                """);
        Map<String, Object> left = forward.actions().get("search_customer").tool().inputSchema();
        Map<String, Object> right = reversed.actions().get("search_customer").tool().inputSchema();
        assertThat(new LinkedHashMap<>(left).keySet()).containsExactly("type", "required", "properties");
        assertThat(new LinkedHashMap<>(right).keySet()).containsExactly("properties", "required", "type");
        ConchDiff diff = ConchDiff.between(forward, reversed);
        assertThat(diff.toolChanges()).isEmpty();
        assertThat(diff.changedActions()).isEmpty();
    }

    @Test
    void destructiveDefaultIsVisibleWithoutChangingMcpHintsEquality() {
        ConchDoc implied = doc("""
                  create_ticket:
                    tool: createTicket
                    title: 创建
                    description: 创建
                    mcp: {read_only: false}
                    input_schema: {type: object}
                """);
        ConchDoc explicit = doc("""
                  create_ticket:
                    tool: createTicket
                    title: 创建
                    description: 创建
                    mcp: {read_only: false, destructive: false}
                    input_schema: {type: object}
                """);
        assertThat(implied.actions().get("create_ticket").mcp())
                .isEqualTo(explicit.actions().get("create_ticket").mcp());
        ToolChange change = ConchDiff.between(implied, explicit).toolChanges().get(0);
        assertThat(change.kind()).isEqualTo(ToolChange.CHANGED);
        assertThat(change.previousDestructive()).isTrue();
        assertThat(change.destructive()).isFalse();
        assertThat(change.schemaSha256()).isEqualTo(change.previousSchemaSha256());
    }

    private static ConchDoc doc(String actions) {
        String body = actions.stripIndent().stripTrailing().replace("\n", "\n  ");
        return ConchParser.parse("""
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 标题
                description: 说明
                credentials: {}
                upstream:
                  url: https://crm.intra.example.com/mcp
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  %s
                """.formatted(body));
    }
}
