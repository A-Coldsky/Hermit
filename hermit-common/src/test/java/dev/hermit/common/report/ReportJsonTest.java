package dev.hermit.common.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import dev.hermit.common.error.ErrorCode;

class ReportJsonTest {

    @Test
    void writesCamelCaseUtcJsonWithoutBusinessFields() throws Exception {
        ReportBatch batch = sample();
        String json = ReportJson.write(batch);
        assertThat(json).doesNotContain("account", "cookie", "password", "request", "response", "params");
        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(names(node)).containsExactly(
                "instanceId", "crabVersion", "sentAt", "failures", "counts", "events");
        assertThat(node.get("sentAt").asText()).isEqualTo("2026-10-12T08:00:00Z");
        JsonNode failure = node.get("failures").get(0);
        assertThat(names(failure)).containsExactly(
                "traceId", "shell", "shellVersion", "action", "stepIndex", "stepType",
                "errorCode", "summary", "occurredAt");
        assertThat(failure.get("errorCode").asText()).isEqualTo("RULE_MISMATCH");
        assertThat(failure.get("stepIndex").isNull()).isTrue();
        assertThat(failure.get("occurredAt").asText()).isEqualTo("2026-10-12T07:59:00Z");
        JsonNode count = node.get("counts").get(0);
        assertThat(names(count)).containsExactly(
                "shell", "shellVersion", "action", "hourStart", "success", "failure", "avgMillis", "p95Millis");
        assertThat(count.get("hourStart").asText()).isEqualTo("2026-10-12T07:00:00Z");
        assertThat(names(node.get("events").get(0))).containsExactly(
                "shell", "fromVersion", "toVersion", "reason", "occurredAt");
        assertThat(ReportJson.readBatch(json)).isEqualTo(batch);
    }

    @Test
    void truncatesSummaryAndRequiresAnUtcHour() {
        String emoji = "😀";
        String raw = "a".repeat(199) + emoji + "b";
        FailureReport report = new FailureReport(
                "t", "legacy_oa", "1.0.0", "list_todo", 1, "extract",
                ErrorCode.TIMEOUT, raw, Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(report.summary()).isEqualTo("a".repeat(199) + emoji);
        assertThat(report.summary().codePointCount(0, report.summary().length())).isEqualTo(200);

        assertThatThrownBy(() -> new CountReport(
                "legacy_oa", "1.0.0", "list_todo",
                Instant.parse("2026-10-12T08:30:00Z"), 1, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        Instant shifted = Instant.parse("2026-10-12T00:00:00+08:00");
        CountReport count = new CountReport(
                "legacy_oa", "1.0.0", "list_todo", shifted, 1, 0, 1, 1);
        assertThat(count.hourStart()).isEqualTo(Instant.parse("2026-10-11T16:00:00Z"));
    }

    @Test
    void rejectsUnknownJsonFields() {
        String json = ReportJson.write(sample()).replace(
                "\"instanceId\"", "\"account\":\"bob\",\"instanceId\"");
        assertThatThrownBy(() -> ReportJson.readBatch(json)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ReportBatch sample() {
        return new ReportBatch(
                "11111111-1111-1111-1111-111111111111",
                "1.2.3",
                Instant.parse("2026-10-12T08:00:00Z"),
                List.of(new FailureReport(
                        "t1", "legacy_oa", "1.0.0", "list_todo", null, null,
                        ErrorCode.RULE_MISMATCH, "状态码 302", Instant.parse("2026-10-12T07:59:00Z"))),
                List.of(new CountReport(
                        "legacy_oa", "1.0.0", "list_todo",
                        Instant.parse("2026-10-12T07:00:00Z"), 3, 1, 12, 40)),
                List.of(new RollbackEvent(
                        "legacy_oa", "1.0.1", "1.0.0", "RULE_MISMATCH",
                        Instant.parse("2026-10-12T08:00:00Z"))));
    }

    private static List<String> names(JsonNode node) {
        ArrayList<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
