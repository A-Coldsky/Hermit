package dev.hermit.reef.tide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import dev.hermit.reef.web.TraceIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "hermit.reef.agent-token=" + ReportIngestTest.TOKEN)
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class ReportIngestTest {

    static final String TOKEN = "report-ingest-agent-token";
    private static final String INSTANCE = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER = "123e4567-e89b-12d3-a456-426614174001";
    private static final Instant SENT = Instant.parse("2026-06-01T00:00:00Z");

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("tide-reports").toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AdjustableClock clock;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        clock.setInstant(ReefTestConfig.START);
        jdbc.update("DELETE FROM failure_reports");
        jdbc.update("DELETE FROM hourly_counts");
        jdbc.update("DELETE FROM rollback_events");
    }

    @Test
    void validBatchWritesAllThreeTablesAndAcceptsAnUnpublishedShell() throws Exception {
        Integer versionTwo = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '2' AND \"success\" = TRUE",
                Integer.class);
        assertThat(versionTwo).isEqualTo(1);
        assertThat(indexNames()).contains("uk_failure_reports_instance_trace");
        Integer ackColumn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' "
                        + "AND TABLE_NAME = 'ALERTS' AND UPPER(COLUMN_NAME) = 'ACKNOWLEDGED_BY'",
                Integer.class);
        assertThat(ackColumn).isEqualTo(1);

        mockMvc.perform(agent(post("/agent/reports"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .header(TraceIds.HEADER, "trace-fixed")
                        .content(ReportJson.write(sample("not-semver"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.traceId").value("trace-fixed"))
                .andExpect(jsonPath("$.data.accepted.failures").value(1))
                .andExpect(jsonPath("$.data.accepted.counts").value(1))
                .andExpect(jsonPath("$.data.accepted.events").value(1));

        assertThat(count("failure_reports")).isEqualTo(1);
        assertThat(count("hourly_counts")).isEqualTo(1);
        assertThat(count("rollback_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT crab_version FROM failure_reports", String.class)).isEqualTo("not-semver");
        assertThat(jdbc.queryForObject("SELECT received_at FROM failure_reports", java.sql.Timestamp.class).toInstant())
                .isEqualTo(ReefTestConfig.START);
        assertThat(jdbc.queryForObject("SELECT error_code FROM failure_reports", String.class)).isEqualTo("RULE_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT success FROM hourly_counts", Long.class)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT reason FROM rollback_events", String.class)).isEqualTo("RULE_MISMATCH");
        assertThat(count("shell_versions")).isZero();
    }

    @Test
    void anyIllegalFieldRejectsTheWholeBatch() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode valid = mapper.readTree(ReportJson.write(sample("1.2.3")));
        List<String> rejected = List.of(
                with(mapper, valid, node -> ((ObjectNode) node.get("failures").get(0)).put("shell", "Bad")),
                with(mapper, valid, node -> ((ObjectNode) node.get("failures").get(0)).put("action", "a")),
                with(mapper, valid, node -> ((ObjectNode) node.get("counts").get(0)).put("shellVersion", "1.0")),
                with(mapper, valid, node -> ((ObjectNode) node.get("failures").get(0)).put("errorCode", "NOPE")),
                with(mapper, valid, node -> ((ObjectNode) node.get("events").get(0)).put("fromVersion", "1.0.0+build")),
                with(mapper, valid, node -> ((ObjectNode) node.get("events").get(0)).remove("eventId")),
                with(mapper, valid, node -> ((ObjectNode) node.get("events").get(0)).put("eventId", " ")),
                with(mapper, valid, node -> ((ObjectNode) node.get("events").get(0)).put("eventId", "e".repeat(65))),
                with(mapper, valid, node -> node.put("account", "bob")),
                with(mapper, valid, node -> node.remove("sentAt")),
                with(mapper, valid, node -> node.put("instanceId", OTHER)),
                with(mapper, valid, node -> ((ObjectNode) node.get("counts").get(0)).put("success", -1)),
                with(mapper, valid, node -> ((ObjectNode) node.get("counts").get(0)).put("failure", -1)),
                with(mapper, valid, node -> ((ObjectNode) node.get("counts").get(0)).put("avgMillis", -1)),
                with(mapper, valid, node -> ((ObjectNode) node.get("counts").get(0)).put("p95Millis", -1)),
                with(mapper, valid, node -> ((ObjectNode) node.get("counts").get(0))
                        .put("hourStart", "2026-06-01T00:30:00Z")),
                with(mapper, valid, node -> ((ObjectNode) node.get("failures").get(0)).put("action", "List")));
        for (String json : rejected) {
            MvcResult result = send(json, INSTANCE);
            assertThat(result.getResponse().getStatus()).isEqualTo(422);
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.get("error").get("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(body.get("error").get("message").asText()).isEqualTo("invalid input");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("bob");
            assertThat(storedRows()).isZero();
        }
    }

    @Test
    void instanceMismatchIs422AndAMissingInstanceIs400() throws Exception {
        MvcResult mismatch = send(ReportJson.write(sample("1.2.3")), OTHER);
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(422);
        assertThat(storedRows()).isZero();

        mockMvc.perform(agent(post("/agent/reports"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ReportJson.write(sample("1.2.3"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        assertThat(storedRows()).isZero();
    }

    @Test
    void duplicateTraceKeepsTheFirstRowAcrossRequestsAndInsideOneBatch() throws Exception {
        send(ReportJson.write(sample("1.2.3")), INSTANCE);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode again = (ObjectNode) mapper.readTree(ReportJson.write(sample("1.2.3")));
        ((ObjectNode) again.get("failures").get(0)).put("summary", "later");
        mockMvc.perform(agent(post("/agent/reports"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .content(mapper.writeValueAsString(again)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted.failures").value(1));
        assertThat(count("failure_reports")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT summary FROM failure_reports", String.class)).isEqualTo("状态码 302");

        ObjectNode pair = again.deepCopy();
        pair.set("failures", mapper.createArrayNode()
                .add(failureNode(mapper, "same-trace", "first"))
                .add(failureNode(mapper, "same-trace", "second")));
        jdbc.update("DELETE FROM failure_reports");
        mockMvc.perform(agent(post("/agent/reports"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .content(mapper.writeValueAsString(pair)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted.failures").value(2));
        assertThat(jdbc.queryForList("SELECT summary FROM failure_reports", String.class)).containsExactly("first");

        send(ReportJson.write(new ReportBatch(OTHER, "1.2.3", SENT, List.of(failure("same-trace", "other")),
                List.of(), List.of())), OTHER);
        assertThat(count("failure_reports")).isEqualTo(2);
    }

    @Test
    void sameHourIsOverwrittenByTheLatestReport() throws Exception {
        send(ReportJson.write(new ReportBatch(INSTANCE, "1.2.3", SENT, List.of(),
                List.of(count(1, 0, 10, 20)), List.of())), INSTANCE);
        send(ReportJson.write(new ReportBatch(INSTANCE, "1.2.3", SENT, List.of(),
                List.of(count(5, 4, 30, 70)), List.of())), INSTANCE);
        assertThat(count("hourly_counts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT success FROM hourly_counts", Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT failure FROM hourly_counts", Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT avg_millis FROM hourly_counts", Long.class)).isEqualTo(30L);
        assertThat(jdbc.queryForObject("SELECT p95_millis FROM hourly_counts", Long.class)).isEqualTo(70L);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode batch = (ObjectNode) mapper.readTree(ReportJson.write(new ReportBatch(
                INSTANCE, "1.2.3", SENT, List.of(), List.of(count(1, 1, 1, 1), count(9, 8, 7, 6)), List.of())));
        jdbc.update("DELETE FROM hourly_counts");
        send(mapper.writeValueAsString(batch), INSTANCE);
        assertThat(jdbc.queryForObject("SELECT success FROM hourly_counts", Long.class)).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT failure FROM hourly_counts", Long.class)).isEqualTo(8L);

        send(ReportJson.write(new ReportBatch(OTHER, "1.2.3", SENT, List.of(),
                List.of(count(2, 2, 2, 2)), List.of())), OTHER);
        assertThat(count("hourly_counts")).isEqualTo(2);
    }

    @Test
    void longSummaryIsTruncatedTo200CodePoints() throws Exception {
        String emoji = "😀";
        String raw = "a".repeat(199) + emoji + "b";
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = (ObjectNode) mapper.readTree(ReportJson.write(sample("1.2.3")));
        ((ObjectNode) node.get("failures").get(0)).put("summary", raw);
        send(mapper.writeValueAsString(node), INSTANCE);
        String stored = jdbc.queryForObject("SELECT summary FROM failure_reports", String.class);
        assertThat(stored).isEqualTo("a".repeat(199) + emoji);
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(200);
    }

    @Test
    void oddStepTypeAndPrereleaseVersionAreAccepted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = (ObjectNode) mapper.readTree(ReportJson.write(sample("1.2.3")));
        ((ObjectNode) node.get("failures").get(0)).put("stepType", "Custom-Step");
        ((ObjectNode) node.get("failures").get(0)).put("shellVersion", "1.0.0-rc.1");
        send(mapper.writeValueAsString(node), INSTANCE);
        assertThat(jdbc.queryForObject("SELECT step_type FROM failure_reports", String.class)).isEqualTo("Custom-Step");
        assertThat(jdbc.queryForObject("SELECT shell_version FROM failure_reports", String.class)).isEqualTo("1.0.0-rc.1");
    }

    @Test
    void bodyOverOneMegabyteIs413AndTheLimitItselfIs422WhenItIsNotJson() throws Exception {
        postBytes(new byte[ReportRules.MAX_BODY_BYTES + 1], 413);
        assertThat(storedRows()).isZero();
        postBytes(new byte[ReportRules.MAX_BODY_BYTES], 422);
        assertThat(storedRows()).isZero();
    }

    @Test
    void collectionLimitsRejectOnePastTheMaximumAndAcceptTheMaximum() throws Exception {
        expectStatus(batch(failures(ReportRules.MAX_FAILURES + 1), List.of(), List.of()), 422);
        assertThat(storedRows()).isZero();
        expectStatus(batch(List.of(), counts(ReportRules.MAX_COUNTS + 1), List.of()), 422);
        expectStatus(batch(List.of(), List.of(), events(ReportRules.MAX_EVENTS + 1)), 422);
        assertThat(storedRows()).isZero();

        expectStatus(batch(failures(ReportRules.MAX_FAILURES), List.of(), List.of()), 200);
        assertThat(count("failure_reports")).isEqualTo(ReportRules.MAX_FAILURES);
        jdbc.update("DELETE FROM failure_reports");
        expectStatus(batch(List.of(), counts(ReportRules.MAX_COUNTS), List.of()), 200);
        assertThat(count("hourly_counts")).isEqualTo(ReportRules.MAX_COUNTS);
        jdbc.update("DELETE FROM hourly_counts");
        expectStatus(batch(List.of(), List.of(), events(ReportRules.MAX_EVENTS)), 200);
        assertThat(count("rollback_events")).isEqualTo(ReportRules.MAX_EVENTS);
    }

    @Test
    void rollbackEventsDeduplicateByEventIdIncludingConcurrentIngest() throws Exception {
        String shared = "11111111-1111-4111-8111-111111111111";
        expectStatus(batch(List.of(), List.of(), List.of(event(shared, "first"))), 200);
        expectStatus(batch(List.of(failure("trace-kept", "kept")), List.of(), List.of(event(shared, "second"))), 200);
        assertThat(count("rollback_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_id FROM rollback_events", String.class)).isEqualTo(shared);
        assertThat(jdbc.queryForObject("SELECT reason FROM rollback_events", String.class)).isEqualTo("first");
        assertThat(jdbc.queryForObject("SELECT summary FROM failure_reports", String.class)).isEqualTo("kept");

        expectStatus(batch(List.of(), List.of(), List.of(event(
                "22222222-2222-4222-8222-222222222222", "other"))), 200);
        assertThat(count("rollback_events")).isEqualTo(2);

        jdbc.update("DELETE FROM rollback_events");
        String concurrentId = "33333333-3333-4333-8333-333333333333";
        String json = ReportJson.write(batch(List.of(), List.of(), List.of(event(concurrentId, "race"))));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return send(json, INSTANCE);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return send(json, INSTANCE);
                    }));
            start.countDown();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus())
                        .as(result.getResponse().getContentAsString())
                        .isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("rollback_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_id FROM rollback_events", String.class)).isEqualTo(concurrentId);
    }

    @Test
    void uniqueIndexRejectsASecondRawInsertOfTheSameTrace() throws Exception {
        send(ReportJson.write(sample("1.2.3")), INSTANCE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO failure_reports (received_at, instance_id, crab_version, trace_id, shell, shell_version, "
                        + "action, error_code, summary, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                java.sql.Timestamp.from(SENT), INSTANCE, "1.2.3", "t1", "legacy_oa", "1.0.0", "list_todo",
                "TIMEOUT", "dup", java.sql.Timestamp.from(SENT)))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("failure_reports")).isEqualTo(1);
    }

    private static MockHttpServletRequestBuilder agent(MockHttpServletRequestBuilder request) {
        return request.header(AgentAccessFilter.TOKEN_HEADER, TOKEN);
    }

    private MvcResult send(String json, String instance) throws Exception {
        return mockMvc.perform(agent(post("/agent/reports"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentAccessFilter.INSTANCE_HEADER, instance)
                        .content(json))
                .andReturn();
    }

    private void postBytes(byte[] body, int status) throws Exception {
        mockMvc.perform(agent(post("/agent/reports"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .content(body))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    private void expectStatus(ReportBatch batch, int status) throws Exception {
        mockMvc.perform(agent(post("/agent/reports"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .content(ReportJson.write(batch)))
                .andExpect(status().is(status));
    }

    private static String with(ObjectMapper mapper, JsonNode valid, java.util.function.Consumer<ObjectNode> edit)
            throws Exception {
        ObjectNode copy = valid.deepCopy();
        edit.accept(copy);
        return mapper.writeValueAsString(copy);
    }

    private static ObjectNode failureNode(ObjectMapper mapper, String traceId, String summary) {
        ObjectNode node = mapper.createObjectNode();
        node.put("traceId", traceId);
        node.put("shell", "legacy_oa");
        node.put("shellVersion", "1.0.0");
        node.put("action", "list_todo");
        node.putNull("stepIndex");
        node.putNull("stepType");
        node.put("errorCode", "TIMEOUT");
        node.put("summary", summary);
        node.put("occurredAt", "2026-06-01T00:00:00Z");
        return node;
    }

    private static ReportBatch sample(String crabVersion) {
        return new ReportBatch(INSTANCE, crabVersion, SENT,
                List.of(failure("t1", "状态码 302")),
                List.of(count(3, 1, 12, 40)),
                List.of(event("evt-sample", "RULE_MISMATCH")));
    }

    private static ReportBatch batch(List<FailureReport> failures, List<CountReport> counts,
            List<RollbackEvent> events) {
        return new ReportBatch(INSTANCE, "1.2.3", SENT, failures, counts, events);
    }

    private static FailureReport failure(String traceId, String summary) {
        return new FailureReport(traceId, "legacy_oa", "1.0.0", "list_todo", 1, "extract",
                ErrorCode.RULE_MISMATCH, summary, SENT);
    }

    private static CountReport count(long success, long failure, long avg, long p95) {
        return new CountReport("legacy_oa", "1.0.0", "list_todo", SENT, success, failure, avg, p95);
    }

    private static List<FailureReport> failures(int n) {
        List<FailureReport> reports = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            reports.add(new FailureReport("t" + i, "legacy_oa", "1.0.0", "list_todo", null, null,
                    ErrorCode.TIMEOUT, "s", SENT));
        }
        return reports;
    }

    private static List<CountReport> counts(int n) {
        List<CountReport> reports = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            reports.add(new CountReport("legacy_oa", "1.0.0", "list_todo",
                    Instant.EPOCH.plusSeconds(i * 3600L), 1, 0, 1, 1));
        }
        return reports;
    }

    private static RollbackEvent event(String eventId, String reason) {
        return new RollbackEvent("legacy_oa", "1.0.1", "1.0.0", reason, SENT, eventId);
    }

    private static List<RollbackEvent> events(int n) {
        List<RollbackEvent> reports = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            reports.add(event("evt-" + i, "r" + i));
        }
        return reports;
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private int storedRows() {
        return count("failure_reports") + count("hourly_counts") + count("rollback_events");
    }

    private List<String> indexNames() {
        return jdbc.queryForList(
                        "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA = 'PUBLIC'",
                        String.class)
                .stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
