package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ValidationCodes;
import dev.hermit.common.validate.ValidationIssue;

class ConchRegistryTest {

    @Test
    void loadsLegacySampleAndIsolatesSnapshot() {
        ConchRegistry registry = new ConchRegistry();
        assertThat(registry.find("legacy_oa")).isEmpty();
        registry.put(EngineTestSupport.legacy());
        assertThat(registry.find("legacy_oa")).isPresent();
        assertThat(registry.find("legacy_oa").orElseThrow().version().toString()).isEqualTo("1.0.0");

        Map<String, ?> snapshot = registry.snapshot();
        registry.put(ConchParser.parse(EngineTestSupport.actionConch("other_conch", "ping", """
                - set:
                    marker: ok
                """, false)));
        assertThat(snapshot).containsOnlyKeys("legacy_oa");
        assertThat(registry.snapshot()).containsOnlyKeys("legacy_oa", "other_conch");
        assertThatThrownBy(() -> registry.snapshot().remove("legacy_oa"))
                .isInstanceOf(UnsupportedOperationException.class);

        registry.remove("legacy_oa");
        assertThat(registry.find("legacy_oa")).isEmpty();
        assertThat(registry.find("other_conch")).isPresent();
        registry.remove("missing");
        assertThat(registry.find("other_conch")).isPresent();
    }

    @Test
    void rejectsEveryIssueAndLeavesRegistryUnchanged() {
        ConchRegistry registry = new ConchRegistry();
        registry.put(EngineTestSupport.legacy());
        String yaml = """
                conch: sample_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                credentials: {}
                actions:
                  list_todo:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果 }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - set: { input: x }
                      - browser: { source: "1" }
                      - request:
                          headers:
                            Authorization: "Basic xxx"
                """;
        assertThatThrownBy(() -> registry.put(ConchParser.parse(yaml)))
                .isInstanceOf(ConchRejectedException.class)
                .satisfies(error -> {
                    ConchRejectedException rejected = (ConchRejectedException) error;
                    assertThat(rejected.issues()).extracting(ValidationIssue::code).contains(
                            ValidationCodes.MISSING_TITLE,
                            ValidationCodes.MISSING_DESCRIPTION,
                            "RESERVED_VARIABLE",
                            ValidationCodes.UNSUPPORTED_STEP,
                            ValidationCodes.PLAINTEXT_CREDENTIAL);
                    assertThat(rejected.issues().size()).isGreaterThanOrEqualTo(5);
                });
        assertThat(registry.snapshot()).containsOnlyKeys("legacy_oa");
        assertThat(registry.find("sample_conch")).isEmpty();
    }

    @Test
    void notifiesListenersAfterSuccessfulChanges() {
        ConchRegistry registry = new ConchRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.addListener(() -> {
            throw new IllegalStateException("listener failed");
        });
        registry.addListener(calls::incrementAndGet);
        registry.put(EngineTestSupport.legacy());
        assertThat(calls).hasValue(1);
        registry.remove("missing");
        assertThat(calls).hasValue(1);
        registry.remove("legacy_oa");
        assertThat(calls).hasValue(2);
        registry.put(EngineTestSupport.legacy());
        assertThatThrownBy(() -> registry.put(ConchParser.parse("""
                conch: sample_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                credentials: {}
                actions:
                  list_todo:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果 }
                    examples:
                      - input: {}
                        output: { value: ok }
                    steps:
                      - set: { input: x }
                """)))
                .isInstanceOf(ConchRejectedException.class);
        assertThat(calls).hasValue(3);
        assertThat(registry.find("legacy_oa")).isPresent();
        assertThat(registry.find("sample_conch")).isEmpty();
    }

    @Test
    void defaultsMatchTheContract() {
        EngineSettings settings = EngineSettings.defaults();
        assertThat(settings.capabilityTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(settings.sessionIdleLimit()).isEqualTo(Duration.ofMinutes(10));
        assertThat(settings.maxLoginFailures()).isEqualTo(3);
        assertThatThrownBy(() -> new EngineSettings(Duration.ZERO, Duration.ofMinutes(1), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineSettings(Duration.ofSeconds(1), Duration.ZERO, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineSettings(Duration.ofSeconds(1), Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
