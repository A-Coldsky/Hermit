package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.retention.PurgeCounts;
import dev.hermit.reef.retention.RetentionController;
import dev.hermit.reef.testsupport.AdjustableClock;
import dev.hermit.reef.testsupport.MemoryLogAppender;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import dev.hermit.reef.web.TraceIds;
import dev.hermit.testdoubles.BoomController;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import({ReefTestConfig.class, BoomController.class})
class AdminApiTest {

    private static final String TRACE = "trace-fixed";

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("admin-api").toString());
        registry.add("hermit.web.reef-root", () -> "classpath:/ui-fixture/");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AdjustableClock clock;

    @Autowired
    AuditService auditService;

    @Autowired
    Environment environment;

    @BeforeEach
    void reset() {
        clock.setInstant(ReefTestConfig.START);
        jdbc.update("DELETE FROM hermit_admin_login_failures");
        jdbc.update("DELETE FROM hermit_audit_log");
        jdbc.update("DELETE FROM hermit_admin_users");
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
                TestSecrets.USERNAME,
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(ReefTestConfig.START));
    }

    @AfterEach
    void passwordStaysOutOfLogs() {
        for (String line : MemoryLogAppender.LINES) {
            if (line.contains(TestSecrets.PASSWORD)) {
                throw new AssertionError("password leaked into logs");
            }
        }
    }

    @Test
    void loginSucceedsAndMeReturnsTheUsername() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIds.HEADER, TRACE)
                        .content(credentials(TestSecrets.USERNAME, TestSecrets.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-06-01T08:00:00Z"))
                .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("token")
                .asText();
        assertThat(token.split("\\.")).hasSize(3);
        String headerJson = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(headerJson).contains("\"alg\":\"HS256\"");

        mockMvc.perform(get("/api/admin/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(TestSecrets.USERNAME))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        mockMvc.perform(get("/api/admin/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void unknownUserWrongPasswordAndLockoutShareOneBody() throws Exception {
        String unknown = failureBody("nobody", TestSecrets.PASSWORD);
        String wrong = failureBody(TestSecrets.USERNAME, "wrong-password");
        for (int i = 0; i < 4; i++) {
            failureBody(TestSecrets.USERNAME, "wrong-password");
        }
        String locked = failureBody(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        assertThat(wrong).isEqualTo(unknown).isEqualTo(locked);
        assertThat(objectMapper.readTree(locked).get("error").get("message").asText())
                .isEqualTo(LoginService.FAILURE_MESSAGE);
        Integer failures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_admin_login_failures WHERE username = ?",
                Integer.class,
                TestSecrets.USERNAME);
        assertThat(failures).isEqualTo(LoginService.MAX_FAILURES);
    }

    @Test
    void lockLastsUntilTheFailureWindowEnds() throws Exception {
        for (int i = 0; i < LoginService.MAX_FAILURES; i++) {
            failureBody(TestSecrets.USERNAME, "wrong-password");
        }
        clock.setInstant(ReefTestConfig.START.plus(Duration.ofMinutes(14)));
        assertThat(failureBody(TestSecrets.USERNAME, TestSecrets.PASSWORD)).contains(LoginService.FAILURE_MESSAGE);

        clock.setInstant(ReefTestConfig.START.plus(LoginService.LOCK_WINDOW));
        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(TestSecrets.USERNAME, TestSecrets.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void disabledUserGetsTheSameFailureBody() throws Exception {
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, TRUE)",
                "disabled-admin",
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(ReefTestConfig.START));
        String unknown = failureBody("nobody", "whatever");
        String disabled = failureBody("disabled-admin", TestSecrets.PASSWORD);
        assertThat(disabled).isEqualTo(unknown);
    }

    @Test
    void missingExpiredAndBadSignatureTokensAreTheSameUnauthorizedBody() throws Exception {
        String token = loginToken();
        Instant expiresAt = Instant.parse("2026-06-01T08:00:00Z");
        clock.setInstant(expiresAt.plusSeconds(61));
        String expired = meBody(token);
        String missing = meBody(null);
        String badSignature = meBody(signedWithOtherKey());
        String noneAlgorithm = meBody(noneAlgorithmToken());
        assertThat(expired).isEqualTo(missing).isEqualTo(badSignature).isEqualTo(noneAlgorithm);
        assertThat(objectMapper.readTree(expired).get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(objectMapper.readTree(expired).get("success").asBoolean()).isFalse();
    }

    @Test
    void loginWritesAuditAndTheFeedIsNewestFirst() throws Exception {
        String token = loginToken();
        jdbc.update("DELETE FROM hermit_audit_log");
        clock.setInstant(ReefTestConfig.START);
        auditService.record(TestSecrets.USERNAME, AuditAction.UPLOAD, "legacy_oa", "1.0.0", "uploaded");
        clock.setInstant(ReefTestConfig.START.plusSeconds(1));
        auditService.record(TestSecrets.USERNAME, AuditAction.PUBLISH, "legacy_oa", "1.0.0", "published");
        clock.setInstant(ReefTestConfig.START.plusSeconds(2));
        auditService.record(TestSecrets.USERNAME, AuditAction.DISABLE, "other", "2.0.0", "disabled");

        mockMvc.perform(get("/api/admin/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items[0].action").value("DISABLE"))
                .andExpect(jsonPath("$.data.items[1].action").value("PUBLISH"))
                .andExpect(jsonPath("$.data.items[2].action").value("UPLOAD"));

        mockMvc.perform(get("/api/admin/audit")
                        .param("conch", "legacy_oa")
                        .param("page", "1")
                        .param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].action").value("PUBLISH"))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mockMvc.perform(get("/api/admin/audit")
                        .param("conch", "legacy_oa")
                        .param("page", "2")
                        .param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.items[0].action").value("UPLOAD"));

        mockMvc.perform(get("/api/admin/audit").param("conch", "  ").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(3));
        mockMvc.perform(get("/api/admin/audit").param("size", "201").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/admin/audit").param("page", "0").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/audit").param("size", "200").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(200));
        mockMvc.perform(get("/api/admin/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mistypedQueryParametersAreInvalidInputAndNameTheField() throws Exception {
        String token = loginToken();
        String bad = "size-not-numeric-9f3c";
        int before = MemoryLogAppender.LINES.size();
        mockMvc.perform(get("/api/admin/audit").param("size", bad).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("invalid input: size"))
                .andExpect(content().string(not(containsString(bad))));
        mockMvc.perform(get("/api/admin/audit").param("page", bad).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("invalid input: page"))
                .andExpect(content().string(not(containsString(bad))));
        mockMvc.perform(post("/api/admin/alerts/" + bad + "/ack").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("invalid input: id"))
                .andExpect(content().string(not(containsString(bad))));
        mockMvc.perform(get("/api/admin/probe-required").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("invalid input: widget"));
        mockMvc.perform(get("/api/admin/probe-required").param("widget", bad)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("invalid input: widget"))
                .andExpect(content().string(not(containsString(bad))));
        mockMvc.perform(get("/api/admin/audit").param("size", "0").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/admin/audit").param("size", "201").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        assertThat(linesSince(before)).noneMatch(line -> line.contains("MethodArgumentTypeMismatchException"));
        assertThat(linesSince(before)).noneMatch(line -> line.contains(bad) && line.contains("ERROR"));
    }

    @Test
    void passwordLimitIsUtf8Bytes() throws Exception {
        String fits = "密".repeat(24);
        String tooLong = "密".repeat(25);
        assertThat(fits.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(tooLong.length()).isLessThan(72);
        assertThat(tooLong.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIds.HEADER, TRACE)
                        .content(credentials(TestSecrets.USERNAME, fits)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIds.HEADER, TRACE)
                        .content(credentials(TestSecrets.USERNAME, tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("invalid input"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString(tooLong))));
    }

    @Test
    void malformedLoginDoesNotEchoThePassword() throws Exception {
        int before = MemoryLogAppender.LINES.size();
        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reef-admin\",\"password\":\"" + TestSecrets.PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("invalid input"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString(TestSecrets.PASSWORD))));
        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reef-admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        assertThat(MemoryLogAppender.LINES.size()).isGreaterThan(before);
    }

    @Test
    void unmatchedPathsAreDenied() throws Exception {
        mockMvc.perform(get("/api/other").header(TraceIds.HEADER, "trace-from-caller"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("unauthorized"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value("trace-from-caller"));
        mockMvc.perform(post("/anything").header(TraceIds.HEADER, "trace-from-caller"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("unauthorized"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value("trace-from-caller"));
        String page = mockMvc.perform(get("/anything").header(TraceIds.HEADER, "trace-from-caller"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8"))
                .andExpect(content().string(containsString("<div id=\"app\">")))
                .andExpect(content().string(not(containsString(TestSecrets.PASSWORD))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        mockMvc.perform(get("/h2-console"))
                .andExpect(status().isOk())
                .andExpect(content().string(page));
        mockMvc.perform(get("/api/other").header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("forbidden"));
    }

    @Test
    void errorsUseTheEnvelopeAndDoNotLeakInternals() throws Exception {
        mockMvc.perform(get("/api/admin/login"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/login").header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/admin/boom")
                        .header(TraceIds.HEADER, TRACE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL"))
                .andExpect(jsonPath("$.error.message").value("internal error"))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("boom-internal-sentinel"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("IllegalStateException"))));
    }

    @Test
    void errorLogsCarryTheResponseTraceIdAndOmitTheBody() throws Exception {
        assertThat(environment.getProperty("logging.pattern.level")).contains("%X{traceId}");
        String secret = "request-body-secret-4c1e";
        int before = MemoryLogAppender.LINES.size();
        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIds.HEADER, TRACE)
                        .content("{\"username\":\"reef-admin\",\"password\":\"" + secret))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString(secret))));
        assertThat(linesSince(before))
                .anyMatch(line -> line.contains("status=400") && line.contains("traceId=" + TRACE));
        assertThat(linesSince(before)).noneMatch(line -> line.contains(secret));

        before = MemoryLogAppender.LINES.size();
        mockMvc.perform(get("/api/admin/boom")
                        .header(TraceIds.HEADER, TRACE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL"))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("boom-internal-sentinel"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("IllegalStateException"))));
        assertThat(linesSince(before))
                .anyMatch(line -> line.contains("status=500") && line.contains("traceId=" + TRACE));
        assertThat(linesSince(before)).anyMatch(line -> line.contains("traceId=" + TRACE)
                && line.contains("boom-internal-sentinel"));
        assertThat(linesSince(before)).noneMatch(line -> line.contains(secret));
    }

    @Test
    void authenticatedUnknownAdminRouteUsesTheNotFoundEnvelope() throws Exception {
        int before = MemoryLogAppender.LINES.size();
        mockMvc.perform(get("/api/admin/does-not-exist")
                        .header(TraceIds.HEADER, TRACE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("not found"))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("NoHandlerFound"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("Exception"))));
        assertThat(linesSince(before))
                .anyMatch(line -> line.contains("status=404") && line.contains("traceId=" + TRACE));
    }

    @Test
    void adminCanPurgeRetentionAndTheRunIsAudited() throws Exception {
        String token = loginToken();
        jdbc.update("DELETE FROM hermit_audit_log");
        jdbc.update("DELETE FROM hermit_failure_reports");
        jdbc.update("DELETE FROM hermit_rollback_events");
        jdbc.update("DELETE FROM hermit_admin_login_failures");
        jdbc.update("DELETE FROM hermit_hourly_counts");
        Instant now = ReefTestConfig.START;
        clock.setInstant(now);
        Instant reportCutoff = now.minus(Duration.ofDays(90));
        insertFailure(reportCutoff.minusSeconds(1), "old-failure");
        insertFailure(reportCutoff.plusSeconds(1), "new-failure");
        insertRollback(reportCutoff.minusSeconds(1), "old-rollback");
        insertRollback(now, "new-rollback");
        Instant loginCutoff = now.minus(LoginService.LOCK_WINDOW);
        insertLoginFailure(loginCutoff.minusSeconds(1));
        insertLoginFailure(loginCutoff.plusSeconds(1));
        jdbc.update(
                "INSERT INTO hermit_hourly_counts (conch, conch_version, action, hour_start, instance_id, success, "
                        + "failure, avg_millis, p95_millis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "legacy_oa", "1.0.0", "list_todo", Timestamp.from(now),
                "123e4567-e89b-12d3-a456-426614174000", 1L, 0L, 10L, 20L);

        mockMvc.perform(get("/api/admin/retention/purge").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(post("/api/admin/retention/purge").header(TraceIds.HEADER, TRACE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_failure_reports", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_audit_log WHERE action = 'RETENTION_PURGE'", Integer.class))
                .isZero();

        mockMvc.perform(post("/api/admin/retention/purge")
                        .header(TraceIds.HEADER, TRACE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(jsonPath("$.data.failureReports").value(1))
                .andExpect(jsonPath("$.data.rollbackEvents").value(1))
                .andExpect(jsonPath("$.data.loginFailures").value(1));
        assertThat(jdbc.queryForList("SELECT summary FROM hermit_failure_reports", String.class)).containsExactly("new-failure");
        assertThat(jdbc.queryForList("SELECT reason FROM hermit_rollback_events", String.class)).containsExactly("new-rollback");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_admin_login_failures", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_hourly_counts", Integer.class)).isEqualTo(1);
        String detail = RetentionController.auditDetail(new PurgeCounts(1, 1, 1));
        assertThat(jdbc.queryForObject(
                "SELECT actor FROM hermit_audit_log WHERE action = 'RETENTION_PURGE'", String.class))
                .isEqualTo(TestSecrets.USERNAME);
        assertThat(jdbc.queryForObject(
                "SELECT detail FROM hermit_audit_log WHERE action = 'RETENTION_PURGE'", String.class))
                .isEqualTo(detail);

        mockMvc.perform(post("/api/admin/retention/purge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failureReports").value(0))
                .andExpect(jsonPath("$.data.rollbackEvents").value(0))
                .andExpect(jsonPath("$.data.loginFailures").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_audit_log WHERE action = 'RETENTION_PURGE'", Integer.class))
                .isEqualTo(2);
    }

    private static java.util.List<String> linesSince(int before) {
        return MemoryLogAppender.LINES.subList(before, MemoryLogAppender.LINES.size());
    }

    private void insertFailure(Instant receivedAt, String summary) {
        jdbc.update(
                "INSERT INTO hermit_failure_reports (received_at, instance_id, crab_version, trace_id, conch, "
                        + "conch_version, action, step_index, step_type, error_code, summary, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(receivedAt), "123e4567-e89b-12d3-a456-426614174000", "1.0.0", summary,
                "legacy_oa", "1.0.0", "list_todo", 1, "request", "UPSTREAM_ERROR", summary,
                Timestamp.from(receivedAt));
    }

    private void insertRollback(Instant receivedAt, String reason) {
        jdbc.update(
                "INSERT INTO hermit_rollback_events (received_at, instance_id, conch, from_version, to_version, reason, "
                        + "occurred_at, event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(receivedAt), "123e4567-e89b-12d3-a456-426614174000", "legacy_oa", "1.0.1",
                "1.0.0", reason, Timestamp.from(receivedAt), reason);
    }

    private void insertLoginFailure(Instant failedAt) {
        jdbc.update(
                "INSERT INTO hermit_admin_login_failures (username, failed_at) VALUES (?, ?)",
                "guessed-user",
                Timestamp.from(failedAt));
    }

    private String failureBody(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TraceIds.HEADER, TRACE)
                        .content(credentials(username, password)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(TestSecrets.PASSWORD);
        return body;
    }

    private String meBody(String token) throws Exception {
        var request = get("/api/admin/me").header(TraceIds.HEADER, TRACE);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isUnauthorized()).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String loginToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(TestSecrets.USERNAME, TestSecrets.PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("token").asText();
    }

    private String credentials(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(username, password));
    }

    private String signedWithOtherKey() {
        byte[] other = new byte[32];
        java.util.Arrays.fill(other, (byte) 7);
        OctetSequenceKey key = new OctetSequenceKey.Builder(other)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.HS256)
                .build();
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(key));
        JwtEncoder encoder = new NimbusJwtEncoder(jwks);
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(TestSecrets.USERNAME)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static String noneAlgorithmToken() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString("{\"sub\":\"reef-admin\"}".getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

}
