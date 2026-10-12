package dev.hermit.crab.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopbackTest {

    @Test
    void acceptsOnlyLocalSocketAddresses() {
        assertThat(Loopback.address("127.0.0.1")).isTrue();
        assertThat(Loopback.address("::1")).isTrue();
        assertThat(Loopback.address("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(Loopback.address("::ffff:127.0.0.1")).isTrue();
        assertThat(Loopback.address("::FFFF:127.0.0.1")).isTrue();
        assertThat(Loopback.address("10.0.0.8")).isFalse();
        assertThat(Loopback.address("0.0.0.0")).isFalse();
        assertThat(Loopback.address(null)).isFalse();
        assertThat(Loopback.address("")).isFalse();
    }
}
