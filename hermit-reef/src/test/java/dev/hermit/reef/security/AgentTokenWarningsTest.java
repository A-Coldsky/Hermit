package dev.hermit.reef.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentTokenWarningsTest {

    @Test
    void warnsOnlyWhenTheConfigTokenAndStoredTokensAreBothMissing() {
        assertThat(AgentTokenWarnings.shouldWarn(null, 0)).isTrue();
        assertThat(AgentTokenWarnings.shouldWarn("", 0)).isTrue();
        assertThat(AgentTokenWarnings.shouldWarn("   ", 0)).isTrue();
        assertThat(AgentTokenWarnings.shouldWarn(null, 1)).isFalse();
        assertThat(AgentTokenWarnings.shouldWarn("token", 0)).isFalse();
        assertThat(AgentTokenWarnings.NONE_AVAILABLE)
                .contains("All /agent/** requests will be rejected")
                .contains("Set HERMIT_REEF_AGENT_TOKEN.");
    }
}
