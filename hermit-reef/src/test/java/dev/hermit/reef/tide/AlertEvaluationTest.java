package dev.hermit.reef.tide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.hermit.reef.testsupport.AdjustableClock;
import dev.hermit.reef.testsupport.MemoryLogAppender;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class AlertEvaluationTest {

    private static final String CONCH = "legacy_oa";
    private static final String INSTANCE = "123e4567-e89b-12d3-a456-426614174000";
    private static final Instant PUBLISHED = Instant.parse("2026-06-08T00:00:00Z");
    private static final String TOKEN = "tide-webhook-secret";

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("tide-alerts").toString());
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
    AlertService alertService;

    @Autowired
    TideProperties properties;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private int traceSeq;

    @BeforeEach
    void reset() {
        clock.setInstant(PUBLISHED.plus(Duration.ofHours(2)));
        traceSeq = 0;
        properties.setWebhookUrl("");
        jdbc.update("DELETE FROM hermit_alerts");
        jdbc.update("DELETE FROM hermit_failure_reports");
        jdbc.update("DELETE FROM hermit_hourly_counts");
        jdbc.update("DELETE FROM hermit_rollback_events");
        jdbc.update("DELETE FROM hermit_audit_log");
        jdbc.update("DELETE FROM hermit_conch_versions");
        jdbc.update("DELETE FROM hermit_admin_users");
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
                TestSecrets.USERNAME,
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(clock.instant()));
    }

    @AfterEach
    void clearWebhook() {
        properties.setWebhookUrl("");
    }

    @Test
    void defaultsAndScheduleMatchTheContract() throws Exception {
        assertThat(properties.getNewVersionMinCalls()).isEqualTo(20);
        assertThat(properties.getRegressionDelta()).isEqualByComparingTo(new BigDecimal("0.20"));
        assertThat(properties.getNoBaselineFailureRate()).isEqualByComparingTo(new BigDecimal("0.50"));
        assertThat(properties.getBaselineWindowDays()).isEqualTo(7);
        assertThat(properties.getSurgeWindowMinutes()).isEqualTo(15);
        assertThat(properties.getSurgeMinCount()).isEqualTo(10);
        assertThat(properties.getQuietHours()).isEqualTo(24);
        assertThat(properties.getDedupHours()).isEqualTo(6);
        assertThat(properties.webhookUrlOrNull()).isNull();
        assertThat(properties.getEvaluateCron()).isEqualTo("0 */5 * * * *");
        assertThat(TideWebhookClient.TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        Scheduled scheduled = AlertJob.class.getMethod("evaluateAlerts").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${hermit.reef.tide.evaluate-cron:0 */5 * * * *}");
        assertThat(scheduled.zone()).isEqualTo("UTC");
    }

    @Test
    void newVersionWithoutBaselineFiresOnCallCountAndStrictFailureRate() {
        publish(CONCH, "1.1.0", PUBLISHED, true);
        hour(CONCH, "1.1.0", PUBLISHED, 0, 19);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isZero();

        jdbc.update("DELETE FROM hermit_hourly_counts");
        hour(CONCH, "1.1.0", PUBLISHED, 0, 20);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isEqualTo(1);

        jdbc.update("DELETE FROM hermit_alerts");
        jdbc.update("DELETE FROM hermit_hourly_counts");
        hour(CONCH, "1.1.0", PUBLISHED, 10, 10);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isZero();

        jdbc.update("DELETE FROM hermit_hourly_counts");
        hour(CONCH, "1.1.0", PUBLISHED, 9, 11);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isEqualTo(1);
    }

    @Test
    void regressionUsesThePreviousPublishedWindowAndIgnoresTrafficOutsideIt() {
        publish(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofDays(7)), false);
        publish(CONCH, "1.1.0", PUBLISHED, true);
        hour(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofDays(8)), 0, 100);
        hour(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofDays(1)), 20, 0);
        hour(CONCH, "1.1.0", PUBLISHED.minus(Duration.ofHours(1)), 100, 0);
        hour(CONCH, "1.1.0", PUBLISHED, 81, 19);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isZero();

        jdbc.update("DELETE FROM hermit_hourly_counts");
        hour(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofDays(8)), 0, 100);
        hour(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofDays(1)), 20, 0);
        hour(CONCH, "1.1.0", PUBLISHED.minus(Duration.ofHours(1)), 100, 0);
        hour(CONCH, "1.1.0", PUBLISHED, 80, 20);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isEqualTo(1);
        String detail = jdbc.queryForObject("SELECT detail_json FROM hermit_alerts", String.class);
        assertThat(detail).contains("\"baselineVersion\":\"1.0.0\"");
        assertThat(detail).contains("\"currentCalls\":100");
        assertThat(detail).contains("\"currentFailures\":20");
        assertThat(detail).contains("\"baselineCalls\":20");
    }

    @Test
    void previousVersionFollowsPublishTimeRatherThanSemver() {
        publish(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofDays(2)), false);
        publish(CONCH, "1.2.0", PUBLISHED.minus(Duration.ofDays(1)), false);
        publish(CONCH, "1.1.0", PUBLISHED, true);
        hour(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofHours(30)), 20, 0);
        hour(CONCH, "1.2.0", PUBLISHED.minus(Duration.ofHours(20)), 0, 20);
        hour(CONCH, "1.1.0", PUBLISHED, 0, 20);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isZero();
    }

    @Test
    void shortBaselineDoesNotCountAsAZeroFailureBaseline() {
        publish(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofDays(1)), false);
        publish(CONCH, "1.1.0", PUBLISHED, true);
        hour(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofHours(2)), 19, 0);
        hour(CONCH, "1.1.0", PUBLISHED, 10, 10);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isZero();

        jdbc.update("DELETE FROM hermit_hourly_counts");
        jdbc.update("DELETE FROM hermit_alerts");
        hour(CONCH, "1.0.0", PUBLISHED.minus(Duration.ofHours(2)), 20, 0);
        hour(CONCH, "1.1.0", PUBLISHED, 10, 10);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isEqualTo(1);
    }

    @Test
    void ruleMismatchSurgeFiresAtTenUnlessThisConchChangedWithinADay() {
        failures(CONCH, "RULE_MISMATCH", 9, clock.instant());
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isZero();

        failures(CONCH, "RULE_MISMATCH", 1, clock.instant());
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isEqualTo(1);

        jdbc.update("DELETE FROM hermit_alerts");
        publish(CONCH, "1.2.0", clock.instant().minus(Duration.ofHours(1)), true);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isZero();

        jdbc.update("DELETE FROM hermit_conch_versions");
        audit("PUBLISH", CONCH, clock.instant().minus(Duration.ofHours(1)));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isEqualTo(1);

        jdbc.update("DELETE FROM hermit_alerts");
        jdbc.update("DELETE FROM hermit_audit_log");
        jdbc.update(
                "INSERT INTO hermit_rollback_events (received_at, instance_id, conch, from_version, to_version, reason, "
                        + "occurred_at, event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(clock.instant()), INSTANCE, CONCH, "1.2.0", "1.1.0", "back",
                Timestamp.from(clock.instant()), "alert-rollback");
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isEqualTo(1);

        jdbc.update("DELETE FROM hermit_alerts");
        audit("ROLLBACK", CONCH, clock.instant().minus(Duration.ofHours(1)));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isZero();

        jdbc.update("DELETE FROM hermit_audit_log");
        publish("other_app", "9.0.0", clock.instant().minus(Duration.ofHours(1)), true);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isEqualTo(1);
    }

    @Test
    void quietWindowAndSurgeWindowAreInclusiveAtTheBoundary() {
        failures(CONCH, "RULE_MISMATCH", 10, clock.instant().minus(Duration.ofMinutes(15)));
        publish(CONCH, "1.2.0", clock.instant().minus(Duration.ofHours(24)), false);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isZero();

        jdbc.update("UPDATE hermit_conch_versions SET published_at = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(24)).minusSeconds(1)));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isEqualTo(1);

        jdbc.update("DELETE FROM hermit_alerts");
        jdbc.update("DELETE FROM hermit_failure_reports");
        failures(CONCH, "RULE_MISMATCH", 10, clock.instant().minus(Duration.ofMinutes(15)).minusSeconds(1));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.RULE_MISMATCH_SURGE)).isZero();

        failures(CONCH, "UPSTREAM_ERROR", 10, clock.instant().minus(Duration.ofHours(1)));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isZero();
    }

    @Test
    void upstreamSurgeFiresAtTenEvenAfterARecentPublish() {
        failures(CONCH, "UPSTREAM_ERROR", 9, clock.instant());
        alertService.evaluate();
        assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isZero();

        failures(CONCH, "UPSTREAM_ERROR", 1, clock.instant());
        publish(CONCH, "1.2.0", clock.instant().minus(Duration.ofMinutes(5)), true);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT conch_version FROM hermit_alerts", String.class)).isNull();
    }

    @Test
    void openAlertBlocksADuplicateForJustUnderSixHours() {
        failures(CONCH, "UPSTREAM_ERROR", 10, clock.instant());
        alertService.evaluate();
        alertService.evaluate();
        assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);

        jdbc.update("UPDATE hermit_alerts SET created_at = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(6)).plusSeconds(1)));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);

        jdbc.update("UPDATE hermit_alerts SET created_at = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(6))));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(2);
    }

    @Test
    void ackRecordsTheAdminAndStaysSilentInsideTheDedupWindow() throws Exception {
        failures(CONCH, "UPSTREAM_ERROR", 10, clock.instant());
        alertService.evaluate();
        long id = jdbc.queryForObject("SELECT id FROM hermit_alerts", Long.class);
        String token = token();
        Instant ackedAt = clock.instant();
        mockMvc.perform(get("/api/admin/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alerts[0].kind").value("UPSTREAM_ERROR_SURGE"))
                .andExpect(jsonPath("$.data.alerts[0].acknowledgedAt").value(nullValue()));
        mockMvc.perform(post("/api/admin/alerts/" + id + "/ack")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acknowledgedBy").value(TestSecrets.USERNAME))
                .andExpect(jsonPath("$.data.acknowledgedAt").value(ackedAt.toString()));

        clock.setInstant(ackedAt.plus(Duration.ofHours(1)));
        mockMvc.perform(post("/api/admin/alerts/" + id + "/ack")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acknowledgedAt").value(ackedAt.toString()));
        clock.setInstant(ackedAt);

        alertService.evaluate();
        assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);
        mockMvc.perform(get("/api/admin/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(jsonPath("$.data.alerts.length()").value(0));
        mockMvc.perform(get("/api/admin/alerts").param("status", "all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(jsonPath("$.data.alerts.length()").value(1));

        jdbc.update("UPDATE hermit_alerts SET created_at = ?",
                Timestamp.from(ackedAt.minus(Duration.ofHours(6))));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(2);
        mockMvc.perform(get("/api/admin/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(jsonPath("$.data.alerts.length()").value(1));
        mockMvc.perform(get("/api/admin/alerts").param("status", "all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(jsonPath("$.data.alerts.length()").value(2));
        mockMvc.perform(get("/api/admin/alerts").param("status", "later")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/admin/alerts/999/ack").header(HttpHeaders.AUTHORIZATION, "Bearer " + token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/alerts/" + id + "/ack")).andExpect(status().isUnauthorized());
    }

    @Test
    void webhookFailureDoesNotBlockTheAlertAndSuccessPostsTheNotice() throws Exception {
        failures(CONCH, "UPSTREAM_ERROR", 10, clock.instant());
        int logStart = MemoryLogAppender.LINES.size();
        properties.setWebhookUrl("http://127.0.0.1:9/hook?token=" + TOKEN);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);
        for (String line : MemoryLogAppender.LINES.subList(logStart, MemoryLogAppender.LINES.size())) {
            assertThat(line).doesNotContain(TOKEN);
        }

        jdbc.update("DELETE FROM hermit_alerts");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> bodies = new CopyOnWriteArrayList<>();
        AtomicInteger status = new AtomicInteger(500);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = new byte[0];
            exchange.sendResponseHeaders(status.get(), response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        try {
            properties.setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook?token=" + TOKEN);
            alertService.evaluate();
            assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);
            assertThat(bodies).hasSize(1);
            alertService.evaluate();
            assertThat(bodies).hasSize(1);

            jdbc.update("DELETE FROM hermit_alerts");
            status.set(200);
            alertService.evaluate();
            assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);
            JsonNode notice = objectMapper.readTree(bodies.get(bodies.size() - 1));
            long alertId = jdbc.queryForObject("SELECT id FROM hermit_alerts", Long.class);
            assertThat(notice.get("id").asLong()).isEqualTo(alertId);
            assertThat(notice.get("kind").asText()).isEqualTo("UPSTREAM_ERROR_SURGE");
            assertThat(notice.get("conch").asText()).isEqualTo(CONCH);
            assertThat(notice.get("version").isNull()).isTrue();
            assertThat(notice.get("detail").get("count").asLong()).isEqualTo(10);
            assertThat(notice.get("createdAt").asText()).isEqualTo(clock.instant().toString());
            assertThat(notice.get("adminUrl").asText()).isEqualTo("/alerts");
            assertThat(notice.toString()).doesNotContain(TOKEN);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void acknowledgedSurgeStaysSilentUntilTheDedupWindowEnds() throws Exception {
        failures(CONCH, "UPSTREAM_ERROR", 10, clock.instant());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> bodies = new CopyOnWriteArrayList<>();
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = new byte[0];
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        try {
            properties.setWebhookUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook?token=" + TOKEN);
            alertService.evaluate();
            long id = jdbc.queryForObject("SELECT id FROM hermit_alerts", Long.class);
            alertService.ack(id, "ada");
            assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);
            assertThat(bodies).hasSize(1);

            failures(CONCH, "UPSTREAM_ERROR", 20, clock.instant());
            alertService.evaluate();
            assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);
            assertThat(bodies).hasSize(1);

            jdbc.update("UPDATE hermit_alerts SET created_at = ?",
                    Timestamp.from(clock.instant().minus(Duration.ofHours(6)).plusSeconds(1)));
            alertService.evaluate();
            assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(1);
            assertThat(bodies).hasSize(1);

            jdbc.update("UPDATE hermit_alerts SET created_at = ?",
                    Timestamp.from(clock.instant().minus(Duration.ofHours(6))));
            alertService.evaluate();
            assertThat(alertCount(AlertKind.UPSTREAM_ERROR_SURGE)).isEqualTo(2);
            assertThat(bodies).hasSize(2);
            assertThat(bodies).allSatisfy(body -> assertThat(body).doesNotContain(TOKEN));
            JsonNode first = objectMapper.readTree(bodies.get(0));
            JsonNode second = objectMapper.readTree(bodies.get(1));
            assertThat(first.get("id").asLong()).isEqualTo(id);
            assertThat(first.get("adminUrl").asText()).isEqualTo("/alerts");
            Long openId = jdbc.queryForObject(
                    "SELECT id FROM hermit_alerts WHERE acknowledged_at IS NULL", Long.class);
            assertThat(second.get("id").asLong()).isEqualTo(openId);
            assertThat(second.get("id").asLong()).isNotEqualTo(id);
            assertThat(second.get("adminUrl").asText()).isEqualTo("/alerts");
            assertThat(second.get("kind").asText()).isEqualTo("UPSTREAM_ERROR_SURGE");
            Long open = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hermit_alerts WHERE acknowledged_at IS NULL", Long.class);
            assertThat(open).isEqualTo(1L);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void acknowledgedRegressionStaysSilentUntilTheDedupWindowEnds() {
        publish(CONCH, "1.1.0", PUBLISHED, true);
        hour(CONCH, "1.1.0", PUBLISHED, 0, 20);
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isEqualTo(1);
        long id = jdbc.queryForObject("SELECT id FROM hermit_alerts", Long.class);
        alertService.ack(id, "ada");

        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isEqualTo(1);

        jdbc.update("UPDATE hermit_alerts SET created_at = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(6)).plusSeconds(1)));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isEqualTo(1);

        jdbc.update("UPDATE hermit_alerts SET created_at = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(6))));
        alertService.evaluate();
        assertThat(alertCount(AlertKind.NEW_VERSION_REGRESSION)).isEqualTo(2);
    }

    private void publish(String conch, String version, Instant publishedAt, boolean current) {
        jdbc.update(
                "INSERT INTO hermit_conch_versions (conch, version, sha256, min_crab_version, package_path, status, "
                        + "uploaded_by, uploaded_at, published_by, published_at, is_current) "
                        + "VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?, ?, ?, ?, ?)",
                conch,
                version,
                "ab".repeat(32),
                "0.1.0",
                "conches/" + conch + "-" + version + ".zip",
                TestSecrets.USERNAME,
                Timestamp.from(publishedAt),
                TestSecrets.USERNAME,
                Timestamp.from(publishedAt),
                current);
    }

    private void hour(String conch, String version, Instant hour, long success, long failure) {
        jdbc.update(
                "INSERT INTO hermit_hourly_counts (conch, conch_version, action, hour_start, instance_id, success, failure, "
                        + "avg_millis, p95_millis) VALUES (?, ?, 'list_todo', ?, ?, ?, ?, 1, 1)",
                conch, version, Timestamp.from(hour), INSTANCE, success, failure);
    }

    private void failures(String conch, String errorCode, int count, Instant occurredAt) {
        for (int i = 0; i < count; i++) {
            traceSeq++;
            jdbc.update(
                    "INSERT INTO hermit_failure_reports (received_at, instance_id, crab_version, trace_id, conch, "
                            + "conch_version, action, step_index, step_type, error_code, summary, occurred_at) "
                            + "VALUES (?, ?, '1.2.3', ?, ?, '1.0.0', 'list_todo', 1, 'extract', ?, 's', ?)",
                    Timestamp.from(clock.instant()),
                    INSTANCE,
                    errorCode + "-" + traceSeq,
                    conch,
                    errorCode,
                    Timestamp.from(occurredAt));
        }
    }

    private void audit(String action, String conch, Instant at) {
        jdbc.update(
                "INSERT INTO hermit_audit_log (at, actor, action, conch, version, detail) VALUES (?, ?, ?, ?, ?, ?)",
                Timestamp.from(at), TestSecrets.USERNAME, action, conch, "1.0.0", action.toLowerCase());
    }

    private int alertCount(AlertKind kind) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_alerts WHERE kind = ?", Integer.class, kind.name());
        return count == null ? 0 : count;
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
}
