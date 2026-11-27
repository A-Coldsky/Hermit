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

    @Test
    void unknownToolHintDependsOnWhetherSearchExists() {
        String direct = McpErrors.toolHint("NOT_FOUND", McpErrors.UNKNOWN_TOOL, false);
        assertThat(direct).contains("tools/list").doesNotContain("search_capabilities");
        String directory = McpErrors.toolHint("NOT_FOUND", McpErrors.UNKNOWN_TOOL, true);
        assertThat(directory).contains("search_capabilities").doesNotContain("tools/list");
        assertThat(McpErrors.toolHint("CAPABILITY_DISABLED", "该能力已在本机停用，调用前需要重新启用。"))
                .contains("控制台")
                .contains("capabilities.disabled")
                .doesNotContain("search_capabilities");
    }

    /** 上游 4xx 不是暂时故障：不再叫人「稍后重试」。5xx 仍可以重试。 */
    @Test
    void upstreamClientErrorsDoNotSayRetryLater() {
        assertThat(McpErrors.toolHint("UPSTREAM_ERROR", "上游状态码 404"))
                .contains("404").contains("重试结果一样").doesNotContain("稍后重试");
        assertThat(McpErrors.toolHint("UPSTREAM_ERROR", "上游状态码 503")).contains("稍后重试");
    }

    /** 普通调用方看不到规则和抓包，不能叫它去核对 HAR；只有开发者试跑给这句。 */
    @Test
    void upstreamClientErrorMentionsHarOnlyForTrialRuns() {
        assertThat(McpErrors.toolHint("UPSTREAM_ERROR", "上游状态码 404", true, false))
                .contains("核对参数").contains("维护者").doesNotContain("HAR").doesNotContain("规则里");
        assertThat(McpErrors.toolHint("UPSTREAM_ERROR", "上游状态码 404", true, true)).contains("HAR");
    }
}
