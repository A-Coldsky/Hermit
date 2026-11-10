package dev.hermit.crab.engine;

import java.util.Map;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.store.Origin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UndefinedVariableTrialTest {
    @Test
    void trialNamesTheMissingVariableInsteadOfAVersionMismatch() {
        String yaml = EngineTestSupport.actionConch("undef_ping", "ping", """
                - request:
                    method: GET
                    path: "/{{token}}"
                """, true);
        ConchRegistry registry = new ConchRegistry();
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = new RuleEngine(registry, sessions);
            ExecutionResult result = engine.execute(
                    EngineTestSupport.request(
                            Origin.LOCAL,
                            "undef_ping",
                            "ping",
                            "alice",
                            Map.of(),
                            EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, "http://127.0.0.1")),
                    ConchParser.parse(yaml));
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().message()).isEqualTo("引用了未定义的变量 token。");
            assertThat(result.error().message()).doesNotContain("上游").doesNotContain("版本不一致");
        }
    }
}
