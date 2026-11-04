package dev.hermit.reef.tide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.report.CountReport;
import dev.hermit.common.report.FailureReport;
import dev.hermit.common.report.ReportBatch;
import dev.hermit.common.report.ReportJson;
import dev.hermit.common.report.RollbackEvent;
import dev.hermit.reef.security.AgentAccessFilter;
import dev.hermit.reef.testsupport.AdjustableClock;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "hermit.reef.agent-token=" + TideQueryTest.TOKEN)
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class TideQueryTest {

    static final String TOKEN = "tide-query-agent-token";
    private static final String INSTANCE = "123e4567-e89b-12d3-a456-426614174000";
    private static final Instant DAY = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant NEXT_HOUR = Instant.parse("2026-06-01T01:00:00Z");

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("tide-query").toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AdjustableClock clock;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        clock.setInstant(ReefTestConfig.START);
        jdbc.update("DELETE FROM hermit_failure_reports");
        jdbc.update("DELETE FROM hermit_hourly_counts");
        jdbc.update("DELETE FROM hermit_rollback_events");
        jdbc.update("DELETE FROM hermit_admin_users");
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
                TestSecrets.USERNAME,
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(clock.instant()));
    }

    @Test
    void postedBatchShowsUpInSummaryFailuresAndRollbacks() throws Exception {
        Instant occurred = Instant.parse("2026-06-01T00:30:00Z");
        ReportBatch batch = new ReportBatch(INSTANCE, "1.2.3", occurred,
                List.of(new FailureReport("t1", "legacy_oa", "1.0.0", "list_todo", 2, "extract",
                        ErrorCode.RULE_MISMATCH, "状态码 302", occurred)),
                List.of(new CountReport("legacy_oa", "1.0.0", "list_todo", DAY, 3, 1, 12, 40)),
                List.of(new RollbackEvent("legacy_oa", "1.0.1", "1.0.0", "RULE_MISMATCH", occurred, "evt-posted")));
        mockMvc.perform(post("/agent/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .header(AgentAccessFilter.TOKEN_HEADER, TOKEN)
                        .content(ReportJson.write(batch)))
                .andExpect(status().isOk());

        JsonNode summary = admin(get("/api/admin/tide/summary")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z")
                .param("bucket", "hour"));
        JsonNode point = summary.get("data").get("series").get(0);
        assertThat(point.get("success").asLong()).isEqualTo(3);
        assertThat(point.get("failure").asLong()).isEqualTo(1);
        assertThat(point.get("failureRate").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(point.get("avgMillis").decimalValue()).isEqualByComparingTo("12.000");
        assertThat(point.get("p95Millis").asLong()).isEqualTo(40);
        assertThat(point.has("owner")).isFalse();
        assertThat(summary.get("data").get("p95Note").asText()).isEqualTo(TideSummary.P95_NOTE);

        JsonNode failures = admin(get("/api/admin/tide/failures")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z"));
        JsonNode group = failures.get("data").get("groups").get(0);
        assertThat(group.get("conchVersion").asText()).isEqualTo("1.0.0");
        assertThat(group.get("action").asText()).isEqualTo("list_todo");
        assertThat(group.get("stepIndex").asInt()).isEqualTo(2);
        assertThat(group.get("stepType").asText()).isEqualTo("extract");
        assertThat(group.get("errorCode").asText()).isEqualTo("RULE_MISMATCH");
        assertThat(group.get("count").asLong()).isEqualTo(1);
        assertThat(group.get("recent").get(0).get("summary").asText()).isEqualTo("状态码 302");
        assertThat(group.get("recent").get(0).get("traceId").asText()).isEqualTo("t1");
        assertThat(group.get("owner").asText()).isEqualTo(TestSecrets.USERNAME);
        assertThat(group.get("recent").get(0).get("owner").asText()).isEqualTo(TestSecrets.USERNAME);

        JsonNode rollbacks = admin(get("/api/admin/tide/rollbacks")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z"));
        assertThat(rollbacks.get("data").get("events").get(0).get("reason").asText()).isEqualTo("RULE_MISMATCH");
        assertThat(rollbacks.get("data").get("events").get(0).get("fromVersion").asText()).isEqualTo("1.0.1");
        assertThat(rollbacks.get("data").get("events").get(0).get("owner").asText()).isEqualTo(TestSecrets.USERNAME);
    }

    @Test
    void summaryWeightsAverageByCallsAndTakesTheMaxP95() throws Exception {
        hour("legacy_oa", "1.0.0", "list_todo", DAY, "a", 10, 0, 100, 150);
        hour("legacy_oa", "1.0.0", "list_todo", DAY, "b", 30, 10, 50, 80);
        hour("legacy_oa", "1.1.0", "list_todo", DAY, "a", 10, 0, 10, 200);
        hour("legacy_oa", "1.0.0", "list_todo", DAY, "c", 0, 0, 0, 999);
        hour("legacy_oa", "1.0.0", "list_todo", NEXT_HOUR, "a", 5, 5, 200, 40);
        hour("legacy_oa", "1.0.0", "search_todo", DAY, "a", 4, 1, 8, 12);
        hour("other_app", "1.0.0", "list_todo", DAY, "a", 7, 0, 3, 4);

        JsonNode day = admin(get("/api/admin/tide/summary")
                .param("conch", "legacy_oa")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z")
                .param("bucket", "day"));
        JsonNode series = day.get("data").get("series");
        assertThat(series).hasSize(2);
        JsonNode list = point(series, "legacy_oa", "list_todo", "2026-06-01T00:00:00Z");
        assertThat(list.get("success").asLong()).isEqualTo(55);
        assertThat(list.get("failure").asLong()).isEqualTo(15);
        assertThat(list.get("failureRate").decimalValue()).isEqualByComparingTo("0.214286");
        assertThat(list.get("avgMillis").decimalValue()).isEqualByComparingTo("72.857");
        assertThat(list.get("p95Millis").asLong()).isEqualTo(999);
        JsonNode search = point(series, "legacy_oa", "search_todo", "2026-06-01T00:00:00Z");
        assertThat(search.get("success").asLong()).isEqualTo(4);
        assertThat(search.get("failureRate").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(search.get("avgMillis").decimalValue()).isEqualByComparingTo("8.000");

        JsonNode hours = admin(get("/api/admin/tide/summary")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z")
                .param("bucket", "hour"));
        JsonNode firstHour = point(hours.get("data").get("series"), "legacy_oa", "list_todo", DAY.toString());
        assertThat(firstHour.get("success").asLong()).isEqualTo(50);
        assertThat(firstHour.get("failure").asLong()).isEqualTo(10);
        assertThat(firstHour.get("failureRate").decimalValue()).isEqualByComparingTo("0.166667");
        assertThat(firstHour.get("avgMillis").decimalValue()).isEqualByComparingTo("51.667");
        assertThat(firstHour.get("p95Millis").asLong()).isEqualTo(999);
        JsonNode secondHour = point(hours.get("data").get("series"), "legacy_oa", "list_todo", NEXT_HOUR.toString());
        assertThat(secondHour.get("success").asLong()).isEqualTo(5);
        assertThat(secondHour.get("p95Millis").asLong()).isEqualTo(40);
        assertThat(point(hours.get("data").get("series"), "other_app", "list_todo", DAY.toString())
                .get("success").asLong()).isEqualTo(7);
    }

    @Test
    void failuresGroupAndKeepTheFiveNewestSummaries() throws Exception {
        for (int i = 0; i < 6; i++) {
            failure("legacy_oa", "1.0.0", "list_todo", null, null, "UPSTREAM_ERROR", "s" + i, "up-" + i,
                    DAY.plusSeconds(i));
        }
        failure("legacy_oa", "1.0.0", "list_todo", 1, "extract", "RULE_MISMATCH", "rule", "rule-1", DAY);
        failure("legacy_oa", "1.0.0", "list_todo", 1, "extract", "RULE_MISMATCH", "rule-2", "rule-2",
                DAY.plusSeconds(1));
        failure("other_app", "1.0.0", "list_todo", 1, "extract", "RULE_MISMATCH", "other", "other-1", DAY);

        JsonNode groups = admin(get("/api/admin/tide/failures")
                .param("conch", "legacy_oa")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z"))
                .get("data").get("groups");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("errorCode").asText()).isEqualTo("UPSTREAM_ERROR");
        assertThat(groups.get(0).get("count").asLong()).isEqualTo(6);
        assertThat(groups.get(0).get("stepIndex").isNull()).isTrue();
        assertThat(texts(groups.get(0).get("recent"), "summary")).containsExactly("s5", "s4", "s3", "s2", "s1");
        assertThat(texts(groups.get(0).get("recent"), "traceId")).containsExactly("up-5", "up-4", "up-3", "up-2", "up-1");
        assertThat(groups.get(1).get("count").asLong()).isEqualTo(2);
        assertThat(groups.get(1).get("conch").asText()).isEqualTo("legacy_oa");
        assertThat(groups.get(0).get("owner").isNull()).isTrue();

        JsonNode filtered = admin(get("/api/admin/tide/failures")
                .param("version", "1.0.0")
                .param("action", "list_todo")
                .param("conch", "other_app")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z"))
                .get("data").get("groups");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("conch").asText()).isEqualTo("other_app");
    }

    @Test
    void compareReturnsCallsFailuresAndRateInRequestOrder() throws Exception {
        hour("legacy_oa", "1.0.0", "list_todo", DAY, "a", 80, 20, 1, 1);
        hour("legacy_oa", "1.0.0", "search_todo", NEXT_HOUR, "a", 0, 0, 1, 1);
        hour("legacy_oa", "1.1.0", "list_todo", DAY, "a", 10, 30, 1, 1);
        hour("other_app", "1.0.0", "list_todo", DAY, "a", 9, 9, 1, 1);

        JsonNode versions = admin(get("/api/admin/tide/compare")
                .param("conch", "legacy_oa")
                .param("versions", "1.1.0,1.0.0,9.9.9"))
                .get("data").get("versions");
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0).get("version").asText()).isEqualTo("1.1.0");
        assertThat(versions.get(0).get("calls").asLong()).isEqualTo(40);
        assertThat(versions.get(0).get("failures").asLong()).isEqualTo(30);
        assertThat(versions.get(0).get("failureRate").decimalValue()).isEqualByComparingTo("0.75");
        assertThat(versions.get(1).get("calls").asLong()).isEqualTo(100);
        assertThat(versions.get(1).get("failures").asLong()).isEqualTo(20);
        assertThat(versions.get(1).get("failureRate").decimalValue()).isEqualByComparingTo("0.2");
        assertThat(versions.get(2).get("calls").asLong()).isZero();
        assertThat(versions.get(2).get("failures").asLong()).isZero();
        assertThat(versions.get(2).get("failureRate").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void rollbacksAreNewestFirstAndHonorConchAndTime() throws Exception {
        rollback("legacy_oa", "old", DAY);
        rollback("legacy_oa", "new", DAY.plusSeconds(10));
        rollback("other_app", "other", DAY.plusSeconds(20));
        rollback("legacy_oa", "outside", DAY.minusSeconds(1));

        JsonNode events = admin(get("/api/admin/tide/rollbacks")
                .param("conch", "legacy_oa")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z"))
                .get("data").get("events");
        assertThat(texts(events, "reason")).containsExactly("new", "old");
    }

    @Test
    void defaultWindowIsSevenDaysAndTheSpanStopsAt400() throws Exception {
        clock.setInstant(Instant.parse("2026-06-08T00:00:00Z"));
        hour("legacy_oa", "1.0.0", "list_todo", Instant.parse("2026-06-01T00:00:00Z"), "a", 1, 0, 1, 1);
        hour("legacy_oa", "1.0.0", "list_todo", Instant.parse("2026-05-31T23:00:00Z"), "a", 9, 0, 1, 1);
        hour("legacy_oa", "1.0.0", "list_todo", Instant.parse("2026-06-08T00:00:00Z"), "a", 8, 0, 1, 1);
        failure("legacy_oa", "1.0.0", "list_todo", 1, "extract", "TIMEOUT", "in", "in",
                Instant.parse("2026-06-07T00:00:00Z"));
        failure("legacy_oa", "1.0.0", "list_todo", 1, "extract", "TIMEOUT", "out", "out",
                Instant.parse("2026-05-31T00:00:00Z"));

        JsonNode summary = admin(get("/api/admin/tide/summary"));
        assertThat(summary.get("data").get("bucket").asText()).isEqualTo("day");
        assertThat(summary.get("data").get("from").asText()).isEqualTo("2026-06-01T00:00:00Z");
        assertThat(summary.get("data").get("to").asText()).isEqualTo("2026-06-08T00:00:00Z");
        assertThat(summary.get("data").get("series")).hasSize(1);
        assertThat(summary.get("data").get("series").get(0).get("success").asLong()).isEqualTo(1);

        JsonNode groups = admin(get("/api/admin/tide/failures")).get("data").get("groups");
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("recent").get(0).get("summary").asText()).isEqualTo("in");

        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        admin(get("/api/admin/tide/summary")
                .param("from", start.toString())
                .param("to", start.plus(Duration.ofDays(400)).toString()))
                .get("success");
        mockMvc.perform(get("/api/admin/tide/summary")
                        .param("from", start.toString())
                        .param("to", start.plus(Duration.ofDays(400)).plusSeconds(1).toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/admin/tide/summary")
                        .param("from", "2026-06-02T00:00:00Z")
                        .param("to", "2026-06-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidQueryParametersAre422AndAdminRoutesRequireAToken() throws Exception {
        mockMvc.perform(get("/api/admin/tide/summary").param("bucket", "week")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/admin/tide/failures").param("from", "2026-06-01")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/admin/tide/compare").param("versions", "1.0.0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/admin/tide/compare").param("conch", "legacy_oa").param("versions", "1.0,1.1.0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/admin/tide/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/tide/rollbacks")).andExpect(status().isUnauthorized());

        hour("legacy_oa", "1.0.0", "list_todo", DAY, "a", 1, 0, 1, 1);
        JsonNode shifted = admin(get("/api/admin/tide/summary")
                .param("from", "2026-06-01T08:00:00+08:00")
                .param("to", "2026-06-02T00:00:00Z")
                .param("bucket", "hour"));
        assertThat(shifted.get("data").get("series").get(0).get("bucketStart").asText())
                .isEqualTo("2026-06-01T00:00:00Z");
    }

    private JsonNode admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        String body = mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private String token() throws Exception {
        String response = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + TestSecrets.USERNAME + "\",\"password\":\""
                                + TestSecrets.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("data").get("token").asText();
    }

    private void hour(String conch, String version, String action, Instant hour, String instance, long success,
            long failure, long avg, long p95) {
        jdbc.update(
                "INSERT INTO hermit_hourly_counts (conch, conch_version, action, hour_start, instance_id, success, failure, "
                        + "avg_millis, p95_millis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                conch, version, action, Timestamp.from(hour), instance, success, failure, avg, p95);
    }

    private void failure(String conch, String version, String action, Integer stepIndex, String stepType,
            String errorCode, String summary, String traceId, Instant occurredAt) {
        jdbc.update(
                "INSERT INTO hermit_failure_reports (received_at, instance_id, crab_version, trace_id, conch, conch_version, "
                        + "action, step_index, step_type, error_code, summary, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(occurredAt), INSTANCE, "1.2.3", traceId, conch, version, action, stepIndex, stepType,
                errorCode, summary, Timestamp.from(occurredAt));
    }

    private int rollbackEvents;

    private void rollback(String conch, String reason, Instant occurredAt) {
        jdbc.update(
                "INSERT INTO hermit_rollback_events (received_at, instance_id, conch, from_version, to_version, reason, "
                        + "occurred_at, event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(occurredAt), INSTANCE, conch, "1.0.1", "1.0.0", reason, Timestamp.from(occurredAt),
                "evt-" + (++rollbackEvents));
    }

    private static JsonNode point(JsonNode series, String conch, String action, String bucketStart) {
        for (JsonNode node : series) {
            if (conch.equals(node.get("conch").asText())
                    && action.equals(node.get("action").asText())
                    && bucketStart.equals(node.get("bucketStart").asText())) {
                return node;
            }
        }
        throw new AssertionError("missing " + conch + " " + action + " " + bucketStart);
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.get(field).asText()));
        return values;
    }
}
