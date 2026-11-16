package dev.hermit.crab.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

class McpAddressGuardTest {

    @Test
    void rejectsWildcardAddresses() throws Exception {
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("0.0.0.0"), false)).isTrue();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("0.0.0.0"), true)).isTrue();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("::"), false)).isTrue();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("::"), true)).isTrue();
        byte[] mapped = new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0
        };
        assertThat(McpAddressGuard.rejected(InetAddress.getByAddress(mapped), false)).isTrue();
        assertThat(McpAddressGuard.rejected(InetAddress.getByAddress(mapped), true)).isTrue();

        assertThat(McpAddressGuard.rejected(InetAddress.getByName("8.8.8.8"), false)).isFalse();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("10.1.2.3"), false)).isFalse();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("100.64.1.1"), false)).isFalse();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("100.100.100.200"), false)).isFalse();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("127.0.0.1"), false)).isTrue();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("127.0.0.1"), true)).isFalse();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("::1"), true)).isFalse();
        assertThat(McpAddressGuard.rejected(InetAddress.getByName("169.254.169.254"), true)).isTrue();
    }
}
