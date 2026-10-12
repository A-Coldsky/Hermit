package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.common.validate.ShellValidator;
import dev.hermit.common.validate.ValidationCodes;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.store.Origin;

@Timeout(60)
class ScriptStepTest {

    private static final String SAMPLE = "in-sample-zz9";
    private static final String SOURCE_TOKEN = "SRC_TOKEN_zz";

    @Test
    void summarizesListsWritesIntoAndConvertsNumbers() {
        ExecutionResult summarized = run("""
                - set:
                    rows:
                      - { n: 2 }
                      - { n: 5 }
                - script:
                    code: |
                      var rows = vars.rows;
                      var total = 0;
                      for (var i = 0; i < rows.length; i++) total = total + rows[i].n;
                      return { total: total };
                """, """
                total: { type: integer, description: 合计 }
                """);
        assertThat(summarized.success()).isTrue();
        assertThat(data(summarized)).containsEntry("total", 7L);

        ExecutionResult written = run("""
                - script:
                    code: |
                      return { whole: 2, half: 1.5, one: 1.0, flag: true, name: "a", items: [1, null, "b"], empty: undefined };
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        assertThat(written.success()).isTrue();
        Map<String, Object> value = map(data(written).get("value"));
        assertThat(value.get("whole")).isEqualTo(2L);
        assertThat(value.get("one")).isEqualTo(1L);
        assertThat(value.get("half")).isEqualTo(1.5d);
        assertThat(value.get("flag")).isEqualTo(true);
        assertThat(value.get("name")).isEqualTo("a");
        assertThat(value.get("empty")).isNull();
        assertThat(value.get("items")).isEqualTo(java.util.Arrays.asList(1L, null, "b"));

        ExecutionResult merged = run("""
                - script:
                    code: |
                      return { total: 4, label: "ok" };
                """, """
                total: { type: integer, description: 合计 }
                label: { type: string, description: 标签 }
                """);
        assertThat(merged.success()).isTrue();
        assertThat(data(merged)).containsEntry("total", 4L).containsEntry("label", "ok");
    }

    @Test
    void hidesCredentialsAndIgnoresMutations() {
        ExecutionResult hidden = run("""
                - script:
                    code: |
                      return {
                        missing: typeof vars.credential === "undefined",
                        base: vars.config.base_url,
                        sample: vars.input.sample
                      };
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        assertThat(hidden.success()).isTrue();
        Map<String, Object> value = map(data(hidden).get("value"));
        assertThat(value).containsEntry("missing", true)
                .containsEntry("base", "http://127.0.0.1")
                .containsEntry("sample", SAMPLE);
        EngineTestSupport.secretsAbsent(EngineTestSupport.flatten(hidden), EngineTestSupport.PASSWORD);

        ExecutionResult isolated = run("""
                - set:
                    marker: original
                    rows:
                      - { n: 1 }
                      - { n: 2 }
                - script:
                    code: |
                      vars.marker = "changed";
                      vars.rows.push(9);
                      vars.credential = { password: "planted" };
                      return { marker: vars.marker, length: vars.rows.length };
                    into: scratch
                - script:
                    code: |
                      return {
                        marker: vars.marker,
                        length: vars.rows.length,
                        scratchMarker: vars.scratch.marker,
                        seen: typeof vars.credential
                      };
                """, """
                marker: { type: string, description: 标记 }
                length: { type: integer, description: 长度 }
                scratchMarker: { type: string, description: 脚本内看到的值 }
                seen: { type: string, description: 凭证是否可见 }
                """);
        assertThat(isolated.success()).as(isolated.error() == null ? "" : isolated.error().message()).isTrue();
        assertThat(data(isolated)).containsEntry("marker", "original")
                .containsEntry("length", 2L)
                .containsEntry("scratchMarker", "changed")
                .containsEntry("seen", "undefined");
    }

    @Test
    void leavesCodeUnrendered() {
        ExecutionResult result = run("""
                - script:
                    code: |
                      return { raw: "X{{credential.password}}Y" };
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        assertThat(result.success()).isTrue();
        assertThat(map(data(result).get("value"))).containsEntry("raw", "X{{credential.password}}Y");
        EngineTestSupport.secretsAbsent(EngineTestSupport.flatten(result), EngineTestSupport.PASSWORD);
    }

    @Test
    void rejectsEscapes() {
        List<String> escapes = List.of(
                "java.lang.System.exit(0); return { ok: 1 };",
                "var File = Packages.java.io.File; return { ok: 1 };",
                """
                var g = this.constructor.constructor("return this")();
                g.java.lang.System.exit(0);
                return { ok: 1 };
                """,
                "return { ok: eval(\"1\") };",
                "return { ok: new Function(\"return 1\")() };",
                "importPackage(java.io); return { ok: 1 };",
                "return { name: String(vars.getClass()) };",
                "return { name: String(\"x\".getClass()) };",
                "})(); java.lang.System.exit(0); (function(){ return { ok: 1 };");
        for (String code : escapes) {
            ExecutionResult result = run("""
                    - script:
                        code: |
                    %s
                        into: value
                    """.formatted(code.stripIndent().indent(6).stripTrailing()), """
                    value: { type: string, description: 结果 }
                    """);
            assertThat(result.success()).as(code).isFalse();
            assertThat(result.error().code()).as(code).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().message()).as(code).doesNotContain(EngineTestSupport.PASSWORD);
        }
    }

    @Test
    void stopsInfiniteLoopsAtTheScriptTimeout() {
        long started = System.nanoTime();
        ExecutionResult result = run("""
                - script:
                    code: |
                      while (true) {}
                      return { ok: 1 };
                    timeout_ms: 200
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(result.error().message()).isEqualTo("script timeout");
        assertThat(result.error().failedStepType()).isEqualTo("script");
        assertThat(elapsed).isLessThan(2_000);

        long defaultStarted = System.nanoTime();
        ExecutionResult defaults = run("""
                - script:
                    code: |
                      while (true) {}
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        long defaultElapsed = Duration.ofNanos(System.nanoTime() - defaultStarted).toMillis();
        assertThat(defaults.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(defaults.error().message()).isEqualTo("script timeout");
        assertThat(defaultElapsed).isBetween(500L, 4_000L);
    }

    @Test
    void usesTheCapabilityDeadlineWhenItIsShorter() {
        long started = System.nanoTime();
        ExecutionResult result = run("""
                - script:
                    code: |
                      while (true) {}
                    timeout_ms: 5000
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """, new EngineSettings(Duration.ofMillis(800), Duration.ofMinutes(10), 3));
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(result.error().message()).isEqualTo("执行超时");
        assertThat(result.error().failedStepType()).isEqualTo("script");
        assertThat(elapsed).isLessThan(4_000);
    }

    @Test
    void rejectsOversizedResultsAndSources() {
        ExecutionResult huge = run("""
                - script:
                    code: |
                      var s = "a";
                      for (var i = 0; i < 21; i++) s = s + s;
                      return s;
                    timeout_ms: 5000
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        assertThat(huge.success()).isFalse();
        assertThat(huge.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(huge.error().message()).isEqualTo("脚本结果超过 1MB");
        assertThat(huge.error().message().length()).isLessThan(80);

        String bulky = "a".repeat(ScriptStep.MAX_SOURCE_BYTES + 1);
        assertThatThrownBy(() -> new ShellRegistry().put(ShellParser.parse(shell("""
                - script:
                    code: "%s"
                    into: value
                """.formatted(bulky), """
                value: { type: string, description: 结果 }
                """))))
                .isInstanceOf(ShellRejectedException.class)
                .satisfies(error -> {
                    ShellRejectedException rejected = (ShellRejectedException) error;
                    assertThat(rejected.issues()).anyMatch(issue ->
                            issue.code().equals(ValidationCodes.SCRIPT_STEP)
                                    && issue.message().equals("脚本源码超过 64KB")
                                    && issue.message().length() < 80);
                });
    }

    @Test
    void keepsSourceAndValuesOutOfErrors() {
        ExecutionResult result = run("""
                - set:
                    marker: "%s"
                - script:
                    code: |
                      var sample = vars.input.sample;
                      throw new TypeError(String(sample) + String(vars.marker) + "%s");
                    into: value
                """.formatted(EngineTestSupport.PASSWORD, SOURCE_TOKEN), """
                value: { type: string, description: 结果 }
                """);
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(result.error().message()).matches("Rhino \\w+ line \\d+");
        assertThat(result.error().summary()).matches("Rhino \\w+ line \\d+");
        String flat = EngineTestSupport.flatten(result);
        EngineTestSupport.secretsAbsent(flat, EngineTestSupport.PASSWORD);
        EngineTestSupport.secretsAbsent(flat, SAMPLE);
        EngineTestSupport.secretsAbsent(flat, SOURCE_TOKEN);
        assertThat(result.stepLogs()).anyMatch(log -> "script".equals(log.type()));
    }

    @Test
    void rejectsIllegalReturnTypesAndReservedNames() {
        ExecutionResult function = run("""
                - script:
                    code: |
                      return function () { return 1; };
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        assertThat(function.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(function.error().message()).isEqualTo("脚本返回值类型无效");

        ExecutionResult date = run("""
                - script:
                    code: |
                      return new Date();
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        assertThat(date.error().message()).isEqualTo("脚本返回值类型无效");

        ExecutionResult bare = run("""
                - script:
                    code: |
                      return 1;
                """, """
                value: { type: string, description: 结果 }
                """);
        assertThat(bare.error().message()).isEqualTo("脚本返回值类型无效");

        ExecutionResult reserved = run("""
                - script:
                    code: |
                      return { credential: 1, total: 2 };
                """, """
                total: { type: integer, description: 合计 }
                """);
        assertThat(reserved.error().message()).isEqualTo("不能写入保留变量");
        assertThat(reserved.success()).isFalse();

        assertThatThrownBy(() -> new ShellRegistry().put(ShellParser.parse(shell("""
                - script:
                    code: "return 1;"
                    into: input
                """, """
                value: { type: string, description: 结果 }
                """))))
                .isInstanceOf(ShellRejectedException.class)
                .satisfies(error -> assertThat(((ShellRejectedException) error).issues())
                        .extracting(ValidationIssue::code)
                        .contains("RESERVED_VARIABLE"));
    }

    @Test
    void doesNotKeepBuiltinPrototypeChanges() throws Exception {
        String yaml = shell("""
                - script:
                    code: |
                      if (vars.input.sample === "first") {
                        Object.prototype.x = 1;
                        Array.prototype.map = null;
                        return { ok: 1 };
                      }
                      var mapped = [1].map(function (n) { return n + 1; });
                      return { x: ({}).x, mapped: mapped[0] };
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), EngineSettings.defaults(), Clock.systemUTC())) {
            ShellRegistry registry = new ShellRegistry();
            registry.put(ShellParser.parse(yaml));
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults());
            ExecutionResult first = execute(engine, "first");
            assertThat(first.success()).as(first.error() == null ? "" : first.error().message()).isTrue();
            ExecutionResult second = execute(engine, "second");
            assertThat(second.success()).as(second.error() == null ? "" : second.error().message()).isTrue();
            assertThat(map(data(second).get("value"))).containsEntry("x", null).containsEntry("mapped", 2L);
        }
    }

    @Test
    void rejectsDeepRecursion() {
        ExecutionResult result = run("""
                - script:
                    code: |
                      function f(){ return f(); }
                      return f();
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(result.error().failedStepType()).isEqualTo("script");
        assertThat(result.error().message()).doesNotContain("function f");
        EngineTestSupport.secretsAbsent(EngineTestSupport.flatten(result), EngineTestSupport.PASSWORD);
    }

    @Test
    void doesNotShareScopeAcrossCalls() throws Exception {
        String yaml = shell("""
                - script:
                    code: |
                      var mine = vars.input.sample;
                      this.slot = mine;
                      for (var i = 0; i < 20000; i++) this.slot = mine;
                      return { slot: this.slot };
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), EngineSettings.defaults(), Clock.systemUTC())) {
            ShellRegistry registry = new ShellRegistry();
            registry.put(ShellParser.parse(yaml));
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ExecutionResult> first = pool.submit(() -> execute(engine, "AAAA"));
                Future<ExecutionResult> second = pool.submit(() -> execute(engine, "BBBB"));
                assertThat(map(data(first.get()).get("value"))).containsEntry("slot", "AAAA");
                assertThat(map(data(second.get()).get("value"))).containsEntry("slot", "BBBB");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static ExecutionResult run(String steps, String output) {
        return run(steps, output, EngineSettings.defaults());
    }

    private static ExecutionResult run(String steps, String output, EngineSettings settings) {
        try (SessionManager sessions = new SessionManager(new NopSessionPersistence(), settings, Clock.systemUTC())) {
            ShellRegistry registry = new ShellRegistry();
            registry.put(ShellParser.parse(shell(steps, output)));
            RuleEngine engine = new RuleEngine(registry, sessions, settings);
            return execute(engine, SAMPLE);
        }
    }

    private static ExecutionResult execute(RuleEngine engine, String sample) {
        return engine.execute(EngineTestSupport.request(
                Origin.OFFICIAL,
                "sample_shell",
                "run",
                "alice",
                Map.of("sample", sample),
                EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, "http://127.0.0.1")));
    }

    private static String shell(String steps, String output) {
        String indentedSteps = steps.stripIndent().indent(6).stripTrailing();
        String indentedOutput = output.stripIndent().indent(6).stripTrailing();
        return """
                shell: sample_shell
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                actions:
                  run:
                    title: 能力
                    description: 说明
                    input:
                      sample: { type: string, description: 样本 }
                    output:
                %s
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    related: [run]
                    steps:
                %s
                """.formatted(indentedOutput, indentedSteps);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ExecutionResult result) {
        if (!(result.data() instanceof Map<?, ?> map)) {
            throw new AssertionError("data was not a map: " + (result.error() == null ? null : result.error().message()));
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AssertionError("value was not a map");
        }
        return (Map<String, Object>) map;
    }
}
