package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ValidationCodes;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.store.Origin;

class ConchRegistryTest {

    @Test
    void loadsLegacySampleAndIsolatesSnapshot() {
        ConchRegistry registry = new ConchRegistry();
        assertThat(registry.find("legacy_oa")).isEmpty();
        registry.put(EngineTestSupport.legacy());
        assertThat(registry.origin("legacy_oa")).isEqualTo(Origin.OFFICIAL);
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
    void sameNameKeepsBothCopiesAndOnlyOneIsActive() {
        ConchRegistry registry = new ConchRegistry();
        var official = EngineTestSupport.legacy();
        var local = ConchParser.parse(EngineTestSupport.actionConch("legacy_oa", "ping", """
                - set:
                    marker: ok
                """, false));
        java.util.HashMap<String, Origin> saved = new java.util.HashMap<>();
        registry.bindSelections(new ConchRegistry.Selections() {
            @Override
            public java.util.Map<String, Origin> load() {
                return java.util.Map.of();
            }

            @Override
            public void save(String conch, Origin origin) {
                if (origin == null) {
                    saved.remove(conch);
                } else {
                    saved.put(conch, origin);
                }
            }
        });
        java.util.ArrayList<String> dropped = new java.util.ArrayList<>();
        registry.onRemove(dropped::add);
        ConchRegistry localView = registry.scoped(Origin.LOCAL);
        ConchRegistry officialView = registry.scoped(Origin.OFFICIAL);

        officialView.put(official);
        localView.put(local);
        // 后来的那份不自动生效。
        assertThat(registry.find("legacy_oa")).containsSame(official);
        assertThat(registry.origin("legacy_oa")).isEqualTo(Origin.OFFICIAL);
        assertThat(saved).containsEntry("legacy_oa", Origin.OFFICIAL);
        assertThat(localView.find("legacy_oa")).containsSame(local);
        assertThat(localView.origin("legacy_oa")).isEqualTo(Origin.LOCAL);
        assertThat(officialView.origin("legacy_oa")).isEqualTo(Origin.OFFICIAL);
        assertThat(registry.snapshot()).hasSize(1);
        assertThat(dropped).isEmpty();

        assertThat(registry.activate("legacy_oa", Origin.LOCAL)).isTrue();
        assertThat(registry.active("legacy_oa").orElseThrow().doc()).isSameAs(local);
        assertThat(registry.active("legacy_oa").orElseThrow().origin()).isEqualTo(Origin.LOCAL);
        assertThat(saved).containsEntry("legacy_oa", Origin.LOCAL);
        assertThat(officialView.find("legacy_oa")).containsSame(official);
        assertThat(dropped).containsExactly("legacy_oa");
        assertThat(registry.activate("missing", Origin.LOCAL)).isFalse();

        // 单来源视图只移出自己的槽，剩下的那份生效。
        localView.remove("legacy_oa");
        assertThat(registry.find("legacy_oa")).containsSame(official);
        assertThat(registry.origin("legacy_oa")).isEqualTo(Origin.OFFICIAL);
        assertThat(saved).isEmpty();
        assertThat(localView.find("legacy_oa")).isEmpty();
        assertThatThrownBy(() -> localView.put(official, Origin.OFFICIAL)).isInstanceOf(IllegalArgumentException.class);
        officialView.remove("legacy_oa");
        assertThat(registry.find("legacy_oa")).isEmpty();
        assertThat(registry.origin("legacy_oa")).isNull();
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
    void reservedLoginIsReportedOnce() {
        ConchRegistry registry = new ConchRegistry();
        assertThatThrownBy(() -> registry.put(ConchParser.parse("""
                conch: sample_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  list_todo:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - set: { login: x }
                """)))
                .isInstanceOf(ConchRejectedException.class)
                .satisfies(error -> {
                    ConchRejectedException rejected = (ConchRejectedException) error;
                    assertThat(rejected.issues())
                            .filteredOn(issue -> "不能写入保留变量 login".equals(issue.message()))
                            .hasSize(1);
                });
    }

    @Test
    void rejectsWritingReservedLoopVariable() {
        assertThat(ConchRegistry.RESERVED).contains("loop");
        ConchRegistry registry = new ConchRegistry();
        List<ValidationIssue> issues = registry.inspect(ConchParser.parse("""
                conch: loop_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.9.0
                title: 标题
                description: 说明
                credentials: {}
                auth:
                  steps:
                    - ask:
                        field: code
                        prompt: 请输入验证码
                        into: loop
                actions:
                  list_todo:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - set: { loop: x }
                      - extract:
                          loop: { jsonpath: "$.x" }
                      - script:
                          code: "return 1;"
                          into: loop
                """));
        List<ValidationIssue> reserved = issues.stream()
                .filter(issue -> ValidationCodes.RESERVED_VARIABLE.equals(issue.code())
                        && "不能写入保留变量 loop".equals(issue.message()))
                .toList();
        assertThat(reserved).extracting(ValidationIssue::path).contains(
                "auth.steps[0].ask.into",
                "actions.list_todo.steps[0].loop",
                "actions.list_todo.steps[1].loop",
                "actions.list_todo.steps[2].into");
    }

    @Test
    void capsReservedStepsRecursionDepth() {
        StringBuilder steps = new StringBuilder();
        String indent = "      ";
        for (int i = 0; i < 10; i++) {
            steps.append(indent).append("- if:\n");
            steps.append(indent).append("    present: a\n");
            steps.append(indent).append("    then:\n");
            indent += "      ";
        }
        steps.append(indent).append("- set: { input: x }\n");
        String yaml = """
                conch: deep_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.9.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  list_todo:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                """ + steps;
        ConchRegistry registry = new ConchRegistry();
        List<ValidationIssue> issues = registry.inspect(ConchParser.parse(yaml));
        assertThat(issues).extracting(ValidationIssue::code)
                .contains(ValidationCodes.FLOW_STEP)
                .doesNotContain(ValidationCodes.RESERVED_VARIABLE);
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
