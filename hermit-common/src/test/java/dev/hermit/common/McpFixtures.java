package dev.hermit.common;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;

public final class McpFixtures {

    private McpFixtures() {
    }

    public static String headersYaml() {
        return """
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户系统（源站 MCP）
                description: 源站提供的 MCP 服务，Hermit 只做权限和转发。
                credentials:
                  token: {type: secret, label: 访问令牌, required: true, description: 在客户系统生成}
                config:
                  tenant: {type: text, label: 租户, required: false, default: main}
                upstream:
                  url: https://crm.intra.example.com/mcp
                  transport: streamable_http
                  auth: {type: headers}
                  headers:
                    Authorization: 'Bearer {{credential.token}}'
                    X-Tenant: '{{config.tenant}}'
                  timeout_ms: 30000
                tools:
                  include: ['*']
                  exclude: ['admin_*']
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称或手机号搜索客户。
                    mcp: {expose: true, read_only: true}
                    idempotent: true
                    input_schema:
                      type: object
                      properties:
                        q: {type: string}
                      required: [q]
                    output_schema: {type: object}
                    upstream_annotations: {readOnlyHint: true}
                    examples:
                      - input: {q: 张三}
                        output: {ok: true}
                    notes: 只读查询。
                    related: [search_customer]
                """;
    }

    public static ConchDoc headersDoc() {
        return ConchParser.parse(headersYaml());
    }
}
