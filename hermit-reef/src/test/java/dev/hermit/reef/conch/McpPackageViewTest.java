package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import org.junit.jupiter.api.Test;

class McpPackageViewTest {

    @Test
    void missingDriverIsHttpAndDropsUpstreamFields() {
        ConchDoc missing = ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  list_todo:
                    title: 待办
                    description: 查询
                """);
        assertThat(missing.driver()).isNull();
        McpPackageView.Projection view = McpPackageView.of(missing);
        assertThat(view.driver()).isEqualTo("http");
        assertThat(view.upstream()).isNull();
        assertThat(view.snapshot()).isNull();
        assertThat(view.tools()).isNull();
        assertThat(view.upstreamHost()).isNull();

        ConchDoc blank = ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                driver: " "
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                upstream:
                  url: https://crm.intra.example.com/mcp
                  transport: streamable_http
                  auth: {type: headers}
                actions: {}
                """);
        McpPackageView.Projection hidden = McpPackageView.of(blank);
        assertThat(hidden.driver()).isEqualTo("http");
        assertThat(hidden.upstream()).isNull();
        assertThat(hidden.tools()).isNull();
        assertThat(hidden.upstreamHost()).isNull();
    }

    @Test
    void mcpProjectionKeepsPackageFieldsAndDropsHeaderTemplates() {
        ConchDoc doc = ConchParser.parse("""
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 标题
                description: 说明
                credentials: {}
                upstream:
                  url: https://CRM.intra.example.com:8443/mcp
                  transport: streamable_http
                  auth:
                    type: oauth
                    client_id: hermit-crab
                    authorization_endpoint: https://CRM.intra.example.com:8443/oauth/authorize
                    token_endpoint: https://CRM.intra.example.com:8443/oauth/token
                  headers:
                    Authorization: 'Bearer {{credential.token}}'
                  allowed_hosts: [CRM.intra.example.com]
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索
                    description: 搜索
                    mcp: {read_only: true}
                    input_schema: {type: object}
                """);
        McpPackageView.Projection view = McpPackageView.of(doc);
        assertThat(view.driver()).isEqualTo("mcp");
        assertThat(view.upstream().url()).isEqualTo("https://CRM.intra.example.com:8443/mcp");
        assertThat(view.upstream().transport()).isEqualTo("streamable_http");
        assertThat(view.upstream().auth().type()).isEqualTo("oauth");
        assertThat(view.upstream().allowedHosts()).containsExactly("CRM.intra.example.com");
        assertThat(view.upstreamHost()).isEqualTo("crm.intra.example.com");
        assertThat(view.snapshot()).isNull();
        assertThat(view.tools()).hasSize(1);
        assertThat(view.tools().get(0).name()).isEqualTo("search_customer");
        assertThat(view.tools().get(0).upstreamName()).isEqualTo("searchCustomer");
        assertThat(view.tools().get(0).readOnly()).isTrue();
        assertThat(view.tools().get(0).destructive()).isFalse();
        assertThat(view.upstream().toString()).doesNotContain("hermit-crab");
        assertThat(view.upstream().toString()).doesNotContain("credential.token");
        assertThat(view.tools().toString()).doesNotContain("authorize");
    }
}
