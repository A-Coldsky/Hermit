package dev.hermit.crab.mcp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpTransportPolicyTest {
    @Test
    void missingOriginFollowsTheSwitch() {
        assertThat(McpTransportPolicy.originAllowed(List.of(), "127.0.0.1", 7777, List.of(), true)).isTrue();
        assertThat(McpTransportPolicy.originAllowed(List.of(), "127.0.0.1", 7777, List.of(), false)).isFalse();
        assertThat(McpTransportPolicy.originAllowed(List.of(" "), "127.0.0.1", 7777, List.of(), true)).isFalse();
        assertThat(McpTransportPolicy.originAllowed(
                List.of("http://127.0.0.1:7777", "http://evil.example"), "127.0.0.1", 7777, List.of(), true)).isFalse();
    }

    @Test
    void allowsLoopbackBindHostAndAllowlist() {
        assertThat(McpTransportPolicy.originAllowed(
                List.of("http://127.0.0.1:7777"), "10.1.2.3", 7777, List.of())).isTrue();
        assertThat(McpTransportPolicy.originAllowed(
                List.of("http://localhost:7777"), "10.1.2.3", 7777, List.of())).isTrue();
        assertThat(McpTransportPolicy.originAllowed(
                List.of("http://[::1]:7777"), "10.1.2.3", 7777, List.of())).isTrue();
        assertThat(McpTransportPolicy.originAllowed(
                List.of("http://10.1.2.3:7777"), "10.1.2.3", 7777, List.of())).isTrue();
        assertThat(McpTransportPolicy.originAllowed(
                List.of("http://10.1.2.4:7777"), "10.1.2.3", 7777, List.of())).isFalse();
        assertThat(McpTransportPolicy.originAllowed(
                List.of("http://0.0.0.0:7777"), "0.0.0.0", 7777, List.of())).isFalse();
        assertThat(McpTransportPolicy.originAllowed(
                List.of("http://[::]:7777"), "::", 7777, List.of())).isFalse();
        assertThat(McpTransportPolicy.originAllowed(
                List.of("https://mcp.example"), "0.0.0.0", 7777, List.of("https://MCP.Example:443"))).isTrue();
        assertThat(McpTransportPolicy.originAllowed(
                List.of("http://127.0.0.2:7777"), "127.0.0.1", 7777, List.of())).isFalse();
    }

    @Test
    void protocolHeaderAndNegotiationStaySeparate() {
        assertThat(McpTransportPolicy.protocolHeaderAllowed(List.of())).isTrue();
        assertThat(McpTransportPolicy.protocolHeaderAllowed(null)).isTrue();
        assertThat(McpTransportPolicy.protocolHeaderAllowed(List.of("2025-11-25"))).isTrue();
        assertThat(McpTransportPolicy.protocolHeaderAllowed(List.of("1999-01-01"))).isFalse();
        assertThat(McpTransportPolicy.protocolHeaderAllowed(List.of(" "))).isFalse();
        assertThat(McpTransportPolicy.protocolHeaderAllowed(List.of("2025-11-25", "2025-03-26"))).isFalse();

        assertThat(McpTransportPolicy.negotiate("2025-06-18")).isEqualTo("2025-06-18");
        assertThat(McpTransportPolicy.negotiate("1999-01-01", true)).isEqualTo(McpTransportPolicy.NEGOTIATED_FALLBACK);
        assertThat(McpTransportPolicy.negotiate("1999-01-01", false)).isNull();
        assertThat(McpTransportPolicy.negotiate(null, false)).isNull();
    }
}
