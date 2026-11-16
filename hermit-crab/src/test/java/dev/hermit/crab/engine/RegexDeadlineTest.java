package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.crab.store.Origin;

/**
 * JDK 17 对 {@code (a+)+$} 一次 find 就会返回。再套一层的 {@code ((a+)+)+$} 对一串 a
 * 再加一个匹配不上的字符，仍会在一次 find 里指数回溯。调用要在能力 deadline 附近结束，
 * 并放开这个账号的锁。
 */
class RegexDeadlineTest {

    private static final int BACKTRACK_AS = 26;
    private static final Duration BUDGET = Duration.ofMillis(200);

    private final FilterRegistry filters = FilterRegistry.defaults();
    private final TemplateEngine templates = new TemplateEngine(filters);

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void catastrophicRegexTimesOutAndReleasesTheAccountLock() {
        String payload = "a".repeat(BACKTRACK_AS) + "!";
        EngineSettings settings = new EngineSettings(BUDGET, Duration.ofMinutes(10), 3);
        try (SessionManager sessions = new SessionManager(new NopSessionPersistence(), settings, Clock.systemUTC())) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(conch(payload)));
            RuleEngine engine = new RuleEngine(registry, sessions, settings);
            AccountContext account = EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, "http://127.0.0.1");

            ExecutionResult stuck = engine.execute(EngineTestSupport.request(
                    Origin.OFFICIAL, "regex_lock", "stuck", "alice", Map.of(), account));
            assertThat(stuck.success()).isFalse();
            assertThat(stuck.data()).isNull();
            assertThat(stuck.error().code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(stuck.error().message()).isEqualTo("执行超时");
            assertThat(stuck.error().failedStepIndex()).isEqualTo(1);
            assertThat(stuck.error().failedStepType()).isEqualTo("extract");
            assertThat(stuck.durationMillis()).isBetween(100L, 1_000L);

            ExecutionResult plain = engine.execute(EngineTestSupport.request(
                    Origin.OFFICIAL, "regex_lock", "plain", "alice", Map.of(), account));
            assertThat(plain.success()).as(plain.error() == null ? "" : plain.error().message()).isTrue();
            assertThat(plain.data()).isEqualTo(Map.of("value", "A1024"));
            assertThat(plain.durationMillis()).isLessThan(1_000L);
        }
    }

    @Test
    void ordinaryRegexStillExtracts() {
        Map<String, Object> vars = response(">A1024<");
        int count = Extractors.apply(
                Map.of("code", Map.of("regex", ">(A\\d+)<")),
                vars,
                filters,
                templates,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertThat(vars.get("code")).isEqualTo("A1024");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void regexDoesNotSucceedAfterTheDeadline() {
        Map<String, Object> vars = response(">A1024<");
        assertThatThrownBy(() -> Extractors.apply(
                Map.of("code", Map.of("regex", ">(A\\d+)<")),
                vars,
                filters,
                templates,
                System.nanoTime() - 1))
                .isInstanceOf(DeadlineExceeded.class);
        assertThat(vars).doesNotContainKey("code");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void repeatedFindsStopAtTheDeadline() {
        Map<String, Object> spec = Map.of("bit", Map.of("regex", "a", "group", 0));
        Map<String, Object> warm = response("aaaa");
        Extractors.apply(spec, warm, filters, templates, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertThat(warm.get("bit")).isEqualTo("a");

        Map<String, Object> vars = response("a".repeat(4_000_000));
        long started = System.nanoTime();
        assertThatThrownBy(() -> Extractors.apply(
                spec, vars, filters, templates, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30)))
                .isInstanceOf(DeadlineExceeded.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000L);
        assertThat(vars).doesNotContainKey("bit");
    }

    private static Map<String, Object> response(String body) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("response", Map.of("body", body));
        return vars;
    }

    private static String conch(String payload) {
        return """
                conch: regex_lock
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                actions:
                  stuck:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - set:
                          raw: "%s"
                      - extract:
                          hit:
                            from: raw
                            regex: '((a+)+)+$'
                  plain:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果 }
                    examples:
                      - input: {}
                        output: { value: A1024 }
                    notes: 注意
                    steps:
                      - set:
                          raw: ">A1024<"
                      - extract:
                          value:
                            from: raw
                            regex: '>(A\\d+)<'
                """.formatted(payload);
    }
}
