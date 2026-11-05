package dev.hermit.common.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class McpAddressesTest {

    @Test
    void wildcardMatchesExtraLabelsOnly() {
        assertThat(McpAddresses.hostAllowed("crm.intra.example.com", List.of("*.example.com"))).isTrue();
        assertThat(McpAddresses.hostAllowed("example.com", List.of("*.example.com"))).isFalse();
        assertThat(McpAddresses.hostAllowed("CRM.Intra.Example.com", List.of("crm.intra.example.com"))).isTrue();
        assertThat(McpAddresses.validPattern("*.example.com")).isTrue();
        assertThat(McpAddresses.validPattern("*")).isFalse();
        assertThat(McpAddresses.validPattern("https://example.com")).isFalse();
        assertThat(McpAddresses.validPattern("::1")).isTrue();
        assertThat(McpAddresses.hostAllowed("::1", List.of("[::1]"))).isTrue();
    }

    @Test
    void httpAndHttpsDefaultPortsShareOrigin() {
        McpAddresses.Parsed https = McpAddresses.parse("https://crm.intra.example.com/mcp").url();
        McpAddresses.Parsed explicit = McpAddresses.parse("https://crm.intra.example.com:443/oauth/token").url();
        McpAddresses.Parsed otherPort = McpAddresses.parse("http://127.0.0.1:8080/mcp").url();
        McpAddresses.Parsed plain = McpAddresses.parse("http://127.0.0.1/mcp").url();
        assertThat(McpAddresses.sameOrigin(https, explicit)).isTrue();
        assertThat(McpAddresses.sameOrigin(otherPort, plain)).isFalse();
        assertThat(McpAddresses.parse("https://user:secret@crm.example/mcp").problem())
                .isEqualTo(McpAddresses.Problem.USERINFO);
        assertThat(McpAddresses.parse("https://{{config.tenant}}.example/mcp").problem())
                .isEqualTo(McpAddresses.Problem.TEMPLATE);
        assertThat(McpAddresses.parse("stdio://local").problem()).isEqualTo(McpAddresses.Problem.SCHEME);
    }
}
