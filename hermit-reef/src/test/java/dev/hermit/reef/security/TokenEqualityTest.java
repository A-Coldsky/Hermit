package dev.hermit.reef.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenEqualityTest {

    @Test
    void matchesIdenticalTokens() {
        assertThat(TokenEquality.constantTimeEquals("agent-token", "agent-token")).isTrue();
    }

    @Test
    void rejectsDifferentValuesIncludingLengthAndNull() {
        assertThat(TokenEquality.constantTimeEquals("agent-token", "agent-tokzn")).isFalse();
        assertThat(TokenEquality.constantTimeEquals("agent-token", "short")).isFalse();
        assertThat(TokenEquality.constantTimeEquals("agent-token", "agent-token-extra")).isFalse();
        assertThat(TokenEquality.constantTimeEquals("agent-token", null)).isFalse();
        assertThat(TokenEquality.constantTimeEquals("agent-token", "")).isFalse();
    }
}
