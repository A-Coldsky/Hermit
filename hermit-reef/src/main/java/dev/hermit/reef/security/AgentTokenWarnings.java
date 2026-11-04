package dev.hermit.reef.security;

import dev.hermit.reef.config.ReefProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时看一次。配置令牌为空，并且没有未吊销的库内令牌，才警告 Agent 接口会被拒绝。
 */
@Component
public class AgentTokenWarnings implements ApplicationRunner {

    static final String NONE_AVAILABLE = "hermit.reef.agent-token is blank and no agent tokens are stored. "
            + "All /agent/** requests will be rejected. Set HERMIT_REEF_AGENT_TOKEN.";

    private static final Logger log = LoggerFactory.getLogger(AgentTokenWarnings.class);

    private final JdbcTemplate jdbc;
    private final ReefProperties properties;

    public AgentTokenWarnings(JdbcTemplate jdbc, ReefProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (shouldWarn(properties.configuredAgentToken(), activeTokens())) {
            log.warn(NONE_AVAILABLE);
        }
    }

    static boolean shouldWarn(String configuredToken, int activeTokens) {
        return (configuredToken == null || configuredToken.isBlank()) && activeTokens == 0;
    }

    private int activeTokens() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_agent_tokens WHERE revoked_at IS NULL",
                Integer.class);
        return count == null ? 0 : count;
    }
}
