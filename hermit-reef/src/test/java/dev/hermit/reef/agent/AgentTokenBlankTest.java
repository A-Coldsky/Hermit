package dev.hermit.reef.agent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class AgentTokenBlankTest extends AgentRouteRejection {

    @DynamicPropertySource
    static void blankToken(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("agent-token-blank").toString());
        registry.add("hermit.reef.agent-token", () -> "   ");
    }

    @Autowired
    ReefProperties properties;

    @Test
    void configuredValueIsWhitespaceAndStillUnset() {
        assertThat(properties.getAgentToken()).isEqualTo("   ");
        assertThat(properties.configuredAgentToken()).isNull();
    }
}
