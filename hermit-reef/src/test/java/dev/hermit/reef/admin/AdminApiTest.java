package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;
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

    @BeforeEach
    void reset() {
        clock.setInstant(ReefTestConfig.START);
        jdbc.update("DELETE FROM admin_login_failures");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM admin_users");
        jdbc.update(
                "INSERT INTO admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
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
                .andExpect(jsonPath("$.data.username").value(TestSecrets.USERNAME));
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
                "SELECT COUNT(*) FROM admin_login_failures WHERE username = ?",
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
                "INSERT INTO admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, TRUE)",
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
        jdbc.update("DELETE FROM audit_log");
        clock.setInstant(ReefTestConfig.START);
        auditService.record(TestSecrets.USERNAME, AuditAction.UPLOAD, "legacy_oa", "1.0.0", "uploaded");
        clock.setInstant(ReefTestConfig.START.plusSeconds(1));
        auditService.record(TestSecrets.USERNAME, AuditAction.PUBLISH, "legacy_oa", "1.0.0", "published");
        clock.setInstant(ReefTestConfig.START.plusSeconds(2));
        auditService.record(TestSecrets.USERNAME, AuditAction.ROLLBACK, "other", "2.0.0", "rolled");

        mockMvc.perform(get("/api/admin/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items[0].action").value("ROLLBACK"))
                .andExpect(jsonPath("$.data.items[1].action").value("PUBLISH"))
                .andExpect(jsonPath("$.data.items[2].action").value("UPLOAD"));

        mockMvc.perform(get("/api/admin/audit")
                        .param("shell", "legacy_oa")
                        .param("page", "1")
                        .param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].action").value("PUBLISH"))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mockMvc.perform(get("/api/admin/audit")
                        .param("shell", "legacy_oa")
                        .param("page", "2")
                        .param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.items[0].action").value("UPLOAD"));

        mockMvc.perform(get("/api/admin/audit").param("shell", "  ").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
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
    void errorsUseTheEnvelopeAndDoNotLeakInternals() throws Exception {
        mockMvc.perform(get("/api/no-such").header(TraceIds.HEADER, "trace-from-caller"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value("trace-from-caller"));
        mockMvc.perform(get("/h2-console"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/admin/login"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/login").header(HttpHeaders.AUTHORIZATION, "Bearer " + loginToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/test/boom").header(TraceIds.HEADER, TRACE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL"))
                .andExpect(jsonPath("$.error.message").value("internal error"))
                .andExpect(jsonPath("$.traceId").value(TRACE))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("boom-internal-sentinel"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("IllegalStateException"))));
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
