package dev.hermit.common.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hermit.common.McpFixtures;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.HttpMethod;

class McpConchParserTest {

    @Test
    void readsHeadersPackageAndKeepsRawSchema() {
        ConchDoc doc = McpFixtures.headersDoc();
        assertThat(doc.driver()).isEqualTo("mcp");
        assertThat(doc.upstream().url()).isEqualTo("https://crm.intra.example.com/mcp");
        assertThat(doc.upstream().transport()).isEqualTo("streamable_http");
        assertThat(doc.upstream().auth().headers()).isTrue();
        assertThat(doc.upstream().headers()).containsEntry("Authorization", "Bearer {{credential.token}}");
        assertThat(doc.upstream().timeoutMs()).isEqualTo(30000);
        assertThat(doc.upstream().allowedHosts()).containsExactly("crm.intra.example.com");
        assertThat(doc.tools().include()).containsExactly("*");
        assertThat(doc.tools().exclude()).containsExactly("admin_*");
        assertThat(doc.snapshot()).isNull();
        assertThat(doc.baseUrl()).isNull();
        assertThat(doc.auth()).isNull();

        var action = doc.actions().get("search_customer");
        assertThat(action.tool().name()).isEqualTo("searchCustomer");
        assertThat(action.tool().inputSchema()).containsEntry("type", "object");
        assertThat(action.tool().inputSchema()).doesNotContainKey("additionalProperties");
        assertThat(action.inputJsonSchema()).isEqualTo(action.tool().inputSchema());
        assertThat(action.inputJsonSchema().get("required")).isEqualTo(List.of("q"));
        assertThat(action.effectiveMethod()).isEqualTo(HttpMethod.POST);
        assertThat(action.mcp().readOnly()).isTrue();
        assertThat(action.mcp().destructiveSpecified()).isFalse();
        assertThat(action.effectiveDestructive()).isFalse();
        assertThat(action.effectiveConfirm()).isFalse();
        assertThat(action.tool().upstreamAnnotations()).containsEntry("readOnlyHint", true);
    }

    @Test
    void defaultsAllowedHostFromUrlAndReadsOauth() {
        ConchDoc doc = ConchParser.parse("""
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 标题
                description: 说明
                credentials: {}
                upstream:
                  url: https://CRM.Intra.Example.com/mcp
                  transport: streamable_http
                  auth:
                    type: oauth
                    scopes: [mcp]
                    client_id: hermit-crab
                    authorization_endpoint: https://crm.intra.example.com/oauth/authorize
                    token_endpoint: https://crm.intra.example.com/oauth/token
                actions:
                  create_ticket:
                    tool: createTicket
                    title: 创建工单
                    description: 为客户创建服务工单。
                    mcp: {read_only: false, destructive: true}
                    confirm: true
                    input_schema: {type: object}
                    upstream_annotations: {destructiveHint: true}
                """);
        assertThat(doc.upstream().allowedHosts()).containsExactly("crm.intra.example.com");
        assertThat(doc.upstream().auth().oauth()).isTrue();
        assertThat(doc.upstream().auth().clientId()).isEqualTo("hermit-crab");
        assertThat(doc.upstream().auth().scopes()).containsExactly("mcp");
        assertThat(doc.upstream().headers()).isEmpty();
        var action = doc.actions().get("create_ticket");
        assertThat(action.effectiveMethod()).isEqualTo(HttpMethod.POST);
        assertThat(action.mcp().destructiveSpecified()).isTrue();
        assertThat(action.effectiveDestructive()).isTrue();
        assertThat(action.effectiveConfirm()).isTrue();
        assertThat(action.tool().confirm()).isTrue();
    }

    @Test
    void omittedDestructiveUsesUpstreamHint() {
        ConchDoc hinted = ConchParser.parse(action("""
                mcp: {read_only: false}
                upstream_annotations: {destructiveHint: false}
                """));
        assertThat(hinted.actions().get("search_customer").effectiveDestructive()).isFalse();
        assertThat(hinted.actions().get("search_customer").effectiveConfirm()).isFalse();

        ConchDoc implied = ConchParser.parse(action("""
                mcp: {read_only: false}
                """));
        assertThat(implied.actions().get("search_customer").effectiveDestructive()).isTrue();
        assertThat(implied.actions().get("search_customer").effectiveConfirm()).isTrue();
    }

    @Test
    void rejectsUnknownMcpKeysAndKeepsStdioAsText() {
        assertThatThrownBy(() -> ConchParser.parse(action("command: [crm]")))
                .isInstanceOf(ConchParseException.class)
                .hasMessageContaining("actions.search_customer.command");
        assertThatThrownBy(() -> ConchParser.parse("""
                conch: vendor_crm
                version: 1.0.0
                min_crab_version: 1.1.0
                driver: mcp
                upstream:
                  url: https://crm.example/mcp
                  transport: streamable_http
                  auth: {type: headers}
                  command: [crm]
                """)).isInstanceOf(ConchParseException.class).hasMessageContaining("upstream.command");
        assertThatThrownBy(() -> ConchParser.parse("""
                conch: vendor_crm
                version: 1.0.0
                min_crab_version: 1.1.0
                driver: mcp
                upstream:
                  url: https://crm.example/mcp
                  transport: streamable_http
                  auth: {type: headers, client_secret: hidden}
                """)).isInstanceOf(ConchParseException.class).hasMessageContaining("client_secret");
        ConchDoc stdio = ConchParser.parse(action("").replace("streamable_http", "stdio"));
        assertThat(stdio.upstream().transport()).isEqualTo("stdio");
    }

    @Test
    void readsSnapshot() {
        ConchDoc doc = ConchParser.parse("""
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
                snapshot:
                  fetched_at: 2026-11-05T03:00:00Z
                  protocol_version: 2025-06-18
                  server: {name: crm-mcp, version: 3.2.0}
                  tools_sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索
                    description: 搜索
                    input_schema: {type: object}
                """);
        assertThat(doc.snapshot().fetchedAt()).isEqualTo("2026-11-05T03:00:00Z");
        assertThat(doc.snapshot().protocolVersion()).isEqualTo("2025-06-18");
        assertThat(doc.snapshot().server().name()).isEqualTo("crm-mcp");
        assertThat(doc.snapshot().server().version()).isEqualTo("3.2.0");
        assertThat(doc.snapshot().toolsSha256()).hasSize(64);
    }

    private static String action(String extra) {
        String fields = extra.isBlank() ? "" : extra.stripIndent().stripTrailing().replace("\n", "\n    ");
        return """
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
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 搜索
                    input_schema: {type: object, properties: {q: {type: string}}}
                """.stripIndent() + (fields.isEmpty() ? "" : "    " + fields + "\n");
    }

    @Test
    void httpActionStillOmitsMcpTool() {
        ConchDoc doc = ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    method: GET
                    mcp: {read_only: true}
                """);
        assertThat(doc.actions().get("list_todo").tool()).isNull();
        assertThat(doc.actions().get("list_todo").effectiveMethod()).isEqualTo(HttpMethod.GET);
        assertThat(doc.actions().get("list_todo").inputJsonSchema())
                .containsEntry("additionalProperties", false);
        assertThat(doc.upstream()).isNull();
        assertThat(doc.actions().get("list_todo").inputJsonSchema().get("properties")).isEqualTo(Map.of());
    }
}
