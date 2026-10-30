package dev.hermit.crab.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpOriginTest {
    @Test
    void normalizesCaseDefaultPortsAndLoopback() {
        assertThat(HttpOrigin.normalize("HTTP://127.0.0.1:80")).isEqualTo("http://127.0.0.1");
        assertThat(HttpOrigin.normalize("https://MCP.Example:443")).isEqualTo("https://mcp.example");
        assertThat(HttpOrigin.normalize("http://[::1]:7777")).isEqualTo("http://[::1]:7777");
        assertThat(HttpOrigin.normalize("http://[0:0:0:0:0:0:0:1]:7777")).isEqualTo("http://[::1]:7777");
        assertThat(HttpOrigin.normalize("http://localhost:7777")).isEqualTo("http://localhost:7777");
    }

    @Test
    void rejectsOriginsThatAreNotABareSchemeHostPort() {
        assertThat(HttpOrigin.normalize(null)).isNull();
        assertThat(HttpOrigin.normalize("")).isNull();
        assertThat(HttpOrigin.normalize("evil.example")).isNull();
        assertThat(HttpOrigin.normalize("null")).isNull();
        assertThat(HttpOrigin.normalize("http://127.0.0.1:7777/")).isNull();
        assertThat(HttpOrigin.normalize("http://127.0.0.1:7777/mcp")).isNull();
        assertThat(HttpOrigin.normalize("http://user@127.0.0.1:7777")).isNull();
        assertThat(HttpOrigin.normalize("http://127.0.0.1:7777?x=1")).isNull();
        assertThat(HttpOrigin.normalize("ftp://127.0.0.1:7777")).isNull();
        assertThat(HttpOrigin.normalize("http://127.0.0.1:7777#a")).isNull();
    }
}
