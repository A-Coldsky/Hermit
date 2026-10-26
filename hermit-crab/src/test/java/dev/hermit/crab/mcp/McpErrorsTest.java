package dev.hermit.crab.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpErrorsTest {

    @Test
    void upstreamHttp400HintSaysToChangeTheInput() {
        String upstream = McpErrors.toolHint("INVALID_INPUT", "上游拒绝了输入（HTTP 400）。请修改参数后重试。");
        assertThat(upstream).contains("修改参数").doesNotContain("inputSchema");
        assertThat(McpErrors.toolHint("INVALID_INPUT", "字段 page 超出范围")).contains("inputSchema");
    }
}
