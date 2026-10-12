package dev.hermit.reef.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceIdsTest {

    @Test
    void usesCallerTraceIdWhenItIsSafe() {
        assertThat(TraceIds.resolve("call-trace-1")).isEqualTo("call-trace-1");
        assertThat(TraceIds.resolve("  call-trace-1  ")).isEqualTo("call-trace-1");
    }

    @Test
    void generatesIdWhenHeaderIsMissingOrUnsafe() {
        assertThat(TraceIds.resolve(null)).matches(TraceIdsTest::isUuid);
        assertThat(TraceIds.resolve("  ")).matches(TraceIdsTest::isUuid);
        assertThat(TraceIds.resolve("line\nbreak")).matches(TraceIdsTest::isUuid);
        assertThat(TraceIds.resolve("a".repeat(TraceIds.MAX_LENGTH + 1))).matches(TraceIdsTest::isUuid);
        assertThat(TraceIds.resolve("a".repeat(TraceIds.MAX_LENGTH))).isEqualTo("a".repeat(TraceIds.MAX_LENGTH));
    }

    private static boolean isUuid(String value) {
        return value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
