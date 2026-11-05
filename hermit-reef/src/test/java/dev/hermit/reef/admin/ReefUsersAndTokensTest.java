package dev.hermit.reef.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.report.CountReport;
import dev.hermit.common.report.FailureReport;
import dev.hermit.common.report.ReportBatch;
import dev.hermit.common.report.ReportJson;
import dev.hermit.common.report.RollbackEvent;
import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.security.AgentAccessFilter;
import dev.hermit.reef.security.TokenDigests;
import dev.hermit.reef.testsupport.AdjustableClock;
import dev.hermit.reef.testsupport.MemoryLogAppender;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import dev.hermit.reef.web.TraceIds;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "hermit.reef.agent-token=" + ReefUsersAndTokensTest.CONFIG_TOKEN)
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class ReefUsersAndTokensTest {

    static final String CONFIG_TOKEN = "config-agent-token-value";
    private static final String REPLACEMENT_CONFIG = "replacement-config-token-value";

    private static final String INSTANCE = "123e4567-e89b-12d3-a456-426614174000";
    private static final String USER_INSTANCE = "123e4567-e89b-12d3-a456-426614174001";
    private static final String ADMIN_INSTANCE = "123e4567-e89b-12d3-a456-426614174002";
    private static final String CONFIG_INSTANCE = "123e4567-e89b-12d3-a456-426614174003";
    private static final String REPORT_INSTANCE = "123e4567-e89b-12d3-a456-426614174004";
    private static final String SHARED_INSTANCE = "123e4567-e89b-12d3-a456-426614174099";
    private static final String USER_NAME = "reef-user";
    private static final String OTHER_ADMIN = "other-admin";
    private static final String USER_PASSWORD = "UserPassword-2222";
    private static final String OTHER_PASSWORD = "OtherAdmin-3333";
    private static final String RESET_PASSWORD = "ResetPassword-4444";
    private static final String CHANGED_PASSWORD = "ChangedPassword-5555";
    private static final Instant DAY = Instant.parse("2026-06-01T00:00:00Z");
    private static final String CRAB_VERSION = "1234567890123456789012345678901234567890";

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("reef-users").toString());
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
    ReefProperties reefProperties;

    private final List<String> issuedSecrets = new ArrayList<>();
    private int logMark;

    @BeforeEach
    void reset() {
        logMark = MemoryLogAppender.LINES.size();
        issuedSecrets.clear();
        clock.setInstant(ReefTestConfig.START);
        reefProperties.setAgentToken(CONFIG_TOKEN);
        jdbc.update("DELETE FROM hermit_voided_config_tokens");
        jdbc.update("DELETE FROM hermit_crab_instances");
        jdbc.update("DELETE FROM hermit_agent_tokens");
        jdbc.update("DELETE FROM hermit_failure_reports");
        jdbc.update("DELETE FROM hermit_hourly_counts");
        jdbc.update("DELETE FROM hermit_rollback_events");
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
    void secretsStayOutOfLogs() {
        List<String> lines = List.copyOf(MemoryLogAppender.LINES.subList(logMark, MemoryLogAppender.LINES.size()));
        for (String line : lines) {
            assertThat(line)
                    .doesNotContain(TestSecrets.PASSWORD)
                    .doesNotContain(USER_PASSWORD)
                    .doesNotContain(OTHER_PASSWORD)
                    .doesNotContain(RESET_PASSWORD)
                    .doesNotContain(CHANGED_PASSWORD)
                    .doesNotContain(CONFIG_TOKEN)
                    .doesNotContain(REPLACEMENT_CONFIG);
            for (String secret : issuedSecrets) {
                assertThat(line).doesNotContain(secret);
            }
        }
    }

    @Test
    void jwtCarriesTheRoleAndMeReturnsIt() throws Exception {
        String token = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"role\":\"ADMIN\"");
        mockMvc.perform(get("/api/admin/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(TestSecrets.USERNAME))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void userCanBrowseButCannotWrite() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Issued adminToken = issue(admin, "admin-kept");
        createUser(admin, USER_NAME, USER_PASSWORD, "USER");
        String user = login(USER_NAME, USER_PASSWORD);

        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/admin/conches"),
                post("/api/admin/conches/missing/versions/1.0.0/publish"),
                delete("/api/admin/conches/missing/versions/1.0.0"),
                post("/api/admin/conches/missing/disable").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"stop\"}"),
                post("/api/admin/conches/missing/enable").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"go\"}"),
                post("/api/admin/alerts/1/ack"),
                get("/api/admin/audit"),
                post("/api/admin/retention/purge"),
                get("/api/admin/users"),
                post("/api/admin/users").contentType(MediaType.APPLICATION_JSON)
                        .content(userBody("another", USER_PASSWORD, "USER")),
                patch("/api/admin/users/" + TestSecrets.USERNAME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"),
                post("/api/admin/users/" + USER_NAME + "/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + RESET_PASSWORD + "\"}"),
                delete("/api/admin/tokens/" + adminToken.id()))) {
            mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.error.message").value("没有权限执行这个操作"));
        }

        mockMvc.perform(get("/api/admin/conches").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/signing-key").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicKey").isNotEmpty());
        mockMvc.perform(get("/api/admin/alerts").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/tide/summary").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/admin/crabs").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/conches/missing").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/admin/conches/missing/versions/1.0.0/yaml")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, adminToken.id()))
                .isNull();
    }

    @Test
    void passwordChangeAndResetLeaveTheSecretOutOfAuditAndLists() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        mockMvc.perform(post("/api/admin/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "wrong-old-password",
                                "newPassword", CHANGED_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("当前密码不正确"));
        mockMvc.perform(post("/api/admin/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", TestSecrets.PASSWORD,
                                "newPassword", CHANGED_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.username").value(TestSecrets.USERNAME))
                .andExpect(jsonPath("$.data.password").doesNotExist());
        login(TestSecrets.USERNAME, CHANGED_PASSWORD);
        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(TestSecrets.USERNAME, TestSecrets.PASSWORD)))
                .andExpect(status().isUnauthorized());

        String refreshed = login(TestSecrets.USERNAME, CHANGED_PASSWORD);
        createUser(refreshed, USER_NAME, USER_PASSWORD, "USER");
        mockMvc.perform(post("/api/admin/users/" + TestSecrets.USERNAME + "/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + RESET_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("不能重置自己的密码，请用右上角「修改密码」"));
        mockMvc.perform(post("/api/admin/users/" + USER_NAME + "/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + RESET_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        login(USER_NAME, RESET_PASSWORD);

        String users = body(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed));
        String audit = body(get("/api/admin/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed));
        assertThat(users).doesNotContain(CHANGED_PASSWORD).doesNotContain(USER_PASSWORD).doesNotContain(RESET_PASSWORD);
        assertThat(audit).doesNotContain(CHANGED_PASSWORD).doesNotContain(USER_PASSWORD).doesNotContain(RESET_PASSWORD);
        assertThat(jdbc.queryForList("SELECT action FROM hermit_audit_log", String.class))
                .contains("PASSWORD_CHANGED", "USER_CREATED", "USER_PASSWORD_RESET");
    }

    @Test
    void lastEnabledAdminAndSelfChangeAreRejected() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("至少要保留一个启用中的管理员"));
        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("至少要保留一个启用中的管理员"));

        createUser(admin, OTHER_ADMIN, OTHER_PASSWORD, "ADMIN");
        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("不能修改自己的角色或禁用自己"));

        mockMvc.perform(patch("/api/admin/users/" + OTHER_ADMIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));
        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("至少要保留一个启用中的管理员"));
        assertThat(jdbc.queryForObject(
                "SELECT role FROM hermit_admin_users WHERE username = ?", String.class, TestSecrets.USERNAME))
                .isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject(
                "SELECT disabled FROM hermit_admin_users WHERE username = ?", Boolean.class, TestSecrets.USERNAME))
                .isFalse();
    }

    @Test
    void duplicateUsernameAndUnknownRoleAreRejected() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        createUser(admin, USER_NAME, USER_PASSWORD, "USER");
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userBody(USER_NAME, OTHER_PASSWORD, "USER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("用户名已存在"));
        MvcResult rejectedResult = mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userBody("rooted", USER_PASSWORD, "ROOT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("请求体无法解析"))
                .andReturn();
        String rejected = rejectedResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(rejected).doesNotContain(USER_PASSWORD).doesNotContain("ROOT");
        mockMvc.perform(patch("/api/admin/users/" + USER_NAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void issuedTokenAuthenticatesUntilRevokedAndTheSecretIsNotStored() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        JsonNode usersBefore = read(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(usersBefore.get("data").get(0).get("tokenCount").asInt()).isEqualTo(1);

        Issued issued = issue(admin, "laptop");
        assertThat(issued.secret()).matches("hat_[A-Za-z0-9_-]{43}");
        assertThat(issued.prefix()).isEqualTo(issued.secret().substring(0, 12));
        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM hermit_agent_tokens WHERE id = ?", String.class, issued.id()).trim();
        assertThat(storedHash).isEqualTo(TokenDigests.sha256Hex(issued.secret())).doesNotContain(issued.secret())
                .doesNotContain("hat_");
        String publicKey = read(get("/api/admin/signing-key").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .get("data").get("publicKey").asText();
        assertThat(issued.publicKey()).isEqualTo(publicKey);
        assertThat(issued.raw()).doesNotContain(storedHash);

        JsonNode counted = read(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(counted.get("data").get(0).get("tokenCount").asInt()).isEqualTo(2);

        JsonNode listed = read(get("/api/admin/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(listed.toString()).doesNotContain(issued.secret()).doesNotContain(CONFIG_TOKEN);
        JsonNode config = find(listed.get("data"), "source", "CONFIG");
        assertThat(config.get("id").isNull()).isTrue();
        assertThat(config.get("prefix").isNull()).isTrue();
        assertThat(config.get("name").asText()).isEqualTo(AgentTokenService.CONFIG_NAME);
        assertThat(config.get("owner").asText()).isEqualTo(TestSecrets.USERNAME);
        assertThat(config.get("createdAt").isNull()).isTrue();
        JsonNode database = find(listed.get("data"), "source", "DATABASE");
        assertThat(database.get("prefix").asText()).isEqualTo(issued.prefix());
        assertThat(database.get("name").asText()).isEqualTo("laptop");

        mockMvc.perform(post("/api/admin/tokens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        agent(issued.secret(), INSTANCE).andExpect(status().isOk());
        Timestamp firstUsed = jdbc.queryForObject(
                "SELECT last_used_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, issued.id());
        Timestamp firstSeen = jdbc.queryForObject(
                "SELECT first_seen_at FROM hermit_crab_instances WHERE instance_id = ?", Timestamp.class, INSTANCE);
        clock.setInstant(ReefTestConfig.START.plusSeconds(30));
        agent(issued.secret(), INSTANCE).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT last_used_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, issued.id()))
                .isEqualTo(firstUsed);
        clock.setInstant(ReefTestConfig.START.plusSeconds(60));
        agent(issued.secret(), INSTANCE).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT last_used_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, issued.id())
                .toInstant()).isAfter(firstUsed.toInstant());
        assertThat(jdbc.queryForObject(
                "SELECT first_seen_at FROM hermit_crab_instances WHERE instance_id = ?", Timestamp.class, INSTANCE))
                .isEqualTo(firstSeen);

        createUser(admin, USER_NAME, USER_PASSWORD, "USER");
        String user = login(USER_NAME, USER_PASSWORD);
        Issued userToken = issue(user, "user-phone");
        JsonNode userAll = read(get("/api/admin/tokens").param("all", "true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user));
        assertThat(names(userAll.get("data"))).containsExactly("user-phone");
        JsonNode adminAll = read(get("/api/admin/tokens").param("all", "true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(names(adminAll.get("data"))).contains(AgentTokenService.CONFIG_NAME, "laptop", "user-phone");
        assertThat(adminAll.toString()).doesNotContain(issued.secret()).doesNotContain(userToken.secret());

        mockMvc.perform(delete("/api/admin/tokens/" + issued.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value("没有权限执行这个操作"));
        agent(issued.secret(), INSTANCE).andExpect(status().isOk());

        mockMvc.perform(delete("/api/admin/tokens/" + userToken.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revokedAt").isNotEmpty());
        mockMvc.perform(delete("/api/admin/tokens/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        agent(issued.secret(), INSTANCE).andExpect(status().isOk());
    }

    @Test
    void revokedTokenMatchesTheWrongTokenBodyAndASecondRevokeDoesNotAuditAgain() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Issued issued = issue(admin, "once");
        mockMvc.perform(delete("/api/admin/tokens/" + issued.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .header(TraceIds.HEADER, "trace-revoke"))
                .andExpect(status().isOk());
        String revoked = agentBody(issued.secret(), "trace-same");
        String wrong = agentBody("wrong-token-value", "trace-same");
        assertThat(revoked).isEqualTo(wrong).contains("\"code\":\"UNAUTHORIZED\"");
        mockMvc.perform(delete("/api/admin/tokens/" + issued.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revokedAt").isNotEmpty());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_audit_log WHERE action = 'AGENT_TOKEN_REVOKED'", Integer.class))
                .isEqualTo(1);
        String detail = jdbc.queryForObject(
                "SELECT detail FROM hermit_audit_log WHERE action = 'AGENT_TOKEN_CREATED'", String.class);
        String auditBody = body(get("/api/admin/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(detail).doesNotContain(issued.secret());
        assertThat(auditBody).doesNotContain(issued.secret()).doesNotContain(CONFIG_TOKEN);
        JsonNode after = read(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(after.get("data").get(0).get("tokenCount").asInt()).isEqualTo(1);
        JsonNode tokens = read(get("/api/admin/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(find(tokens.get("data"), "name", "once").get("revokedAt").isNull()).isFalse();
    }

    @Test
    void disabledUserTokenAndJwtAreUnauthorized() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        createUser(admin, USER_NAME, USER_PASSWORD, "USER");
        String user = login(USER_NAME, USER_PASSWORD);
        Issued issued = issue(user, "disabled-soon");
        mockMvc.perform(patch("/api/admin/users/" + USER_NAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabled").value(true));
        String disabled = agentBody(issued.secret(), "trace-disabled");
        String wrong = agentBody("wrong-token-value", "trace-disabled");
        assertThat(disabled).isEqualTo(wrong);
        mockMvc.perform(get("/api/admin/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + user)
                        .header(TraceIds.HEADER, "trace-disabled-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        agent(CONFIG_TOKEN, CONFIG_INSTANCE).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, issued.id()))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_voided_config_tokens", Integer.class)).isZero();
    }

    @Test
    void configTokenFollowsTheInitialAdmin() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        createUser(admin, OTHER_ADMIN, OTHER_PASSWORD, "ADMIN");
        String other = login(OTHER_ADMIN, OTHER_PASSWORD);
        Issued otherToken = issue(other, "other-laptop");

        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isOk());
        String blocked = agentBody(CONFIG_TOKEN, CONFIG_INSTANCE);
        String wrong = agentBody("wrong-token-value", blockedTrace(blocked));
        assertThat(blocked).isEqualTo(wrong);
        agent(otherToken.secret(), ADMIN_INSTANCE).andExpect(status().isOk());
        JsonNode whileDisabled = read(get("/api/admin/tokens").param("all", "true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other));
        assertThat(find(whileDisabled.get("data"), "source", "CONFIG").get("owner").asText())
                .isEqualTo(TestSecrets.USERNAME);
        assertThat(find(whileDisabled.get("data"), "source", "CONFIG").get("revokedAt").isNull()).isFalse();

        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":false}"))
                .andExpect(status().isOk());
        String stillBlocked = agentBody(CONFIG_TOKEN, "trace-config-stays");
        assertThat(stillBlocked).isEqualTo(agentBody("wrong-token-value", "trace-config-stays"));
        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));

        String demoted = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        JsonNode own = read(get("/api/admin/tokens").param("all", "true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + demoted));
        assertThat(sources(own.get("data"))).doesNotContain("CONFIG");
        JsonNode moved = read(get("/api/admin/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + other));
        JsonNode movedConfig = find(moved.get("data"), "source", "CONFIG");
        assertThat(movedConfig.get("owner").asText()).isEqualTo(OTHER_ADMIN);
        assertThat(movedConfig.get("revokedAt").isNull()).isFalse();
        assertThat(agentBody(CONFIG_TOKEN, "trace-config-moved")).isEqualTo(
                agentBody("wrong-token-value", "trace-config-moved"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_crab_instances WHERE instance_id = ?",
                Integer.class,
                CONFIG_INSTANCE)).isZero();
        JsonNode users = read(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + other));
        assertThat(user(users, OTHER_ADMIN).get("tokenCount").asInt()).isEqualTo(1);
        assertThat(user(users, TestSecrets.USERNAME).get("tokenCount").asInt()).isEqualTo(0);
    }

    @Test
    void crabsAreScopedToTheCallerAndConfigTokenHasNoName() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Issued adminToken = issue(admin, "admin-crab");
        createUser(admin, USER_NAME, USER_PASSWORD, "USER");
        String user = login(USER_NAME, USER_PASSWORD);
        issue(user, "user-crab");
        String userSecret = issuedSecrets.get(issuedSecrets.size() - 1);
        agent(userSecret, USER_INSTANCE).andExpect(status().isOk());
        agent(adminToken.secret(), ADMIN_INSTANCE).andExpect(status().isOk());
        agent(CONFIG_TOKEN, CONFIG_INSTANCE).andExpect(status().isOk());

        JsonNode own = read(get("/api/admin/crabs").header(HttpHeaders.AUTHORIZATION, "Bearer " + user));
        assertThat(own.get("data")).hasSize(1);
        assertThat(own.get("data").get(0).get("instanceId").asText()).isEqualTo(USER_INSTANCE);
        assertThat(own.get("data").get(0).get("tokenName").asText()).isEqualTo("user-crab");
        assertThat(own.get("data").get(0).get("owner").asText()).isEqualTo(USER_NAME);

        JsonNode all = read(get("/api/admin/crabs").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(instances(all.get("data"))).contains(USER_INSTANCE, ADMIN_INSTANCE, CONFIG_INSTANCE);
        JsonNode configRow = find(all.get("data"), "instanceId", CONFIG_INSTANCE);
        assertThat(configRow.get("tokenName").isNull()).isTrue();
        assertThat(configRow.get("owner").asText()).isEqualTo(TestSecrets.USERNAME);
    }

    @Test
    void tideRowsRecordTheOwnerAndTheHourlySummaryDoesNot() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        createUser(admin, USER_NAME, USER_PASSWORD, "USER");
        String user = login(USER_NAME, USER_PASSWORD);
        Issued userToken = issue(user, "reporter");
        String longVersion = CRAB_VERSION;
        assertThat(longVersion).hasSize(40);
        postReport(userToken.secret(), REPORT_INSTANCE, "user-trace", "evt-user", longVersion);
        postReport(CONFIG_TOKEN, CONFIG_INSTANCE, "admin-trace", "evt-admin", "1.2.3");
        jdbc.update(
                "INSERT INTO hermit_failure_reports (received_at, instance_id, crab_version, trace_id, conch, "
                        + "conch_version, action, step_index, step_type, error_code, summary, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Timestamp.from(DAY), INSTANCE, "1.0.0", "old-trace", "legacy_oa", "1.0.0", "list_todo", 1,
                "request", "UPSTREAM_ERROR", "old", Timestamp.from(DAY));

        JsonNode failures = read(get("/api/admin/tide/failures")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        JsonNode userGroup = groupByTrace(failures.get("data").get("groups"), "user-trace");
        JsonNode adminGroup = groupByTrace(failures.get("data").get("groups"), "admin-trace");
        JsonNode oldGroup = groupByTrace(failures.get("data").get("groups"), "old-trace");
        assertThat(userGroup.get("owner").asText()).isEqualTo(USER_NAME);
        assertThat(userGroup.get("recent").get(0).get("owner").asText()).isEqualTo(USER_NAME);
        assertThat(adminGroup.get("owner").asText()).isEqualTo(TestSecrets.USERNAME);
        assertThat(oldGroup.get("owner").isNull()).isTrue();
        assertThat(oldGroup.get("recent").get(0).get("owner").isNull()).isTrue();

        JsonNode rollbacks = read(get("/api/admin/tide/rollbacks")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(find(rollbacks.get("data").get("events"), "instanceId", REPORT_INSTANCE).get("owner").asText())
                .isEqualTo(USER_NAME);
        assertThat(find(rollbacks.get("data").get("events"), "instanceId", CONFIG_INSTANCE).get("owner").asText())
                .isEqualTo(TestSecrets.USERNAME);

        JsonNode summary = read(get("/api/admin/tide/summary")
                .param("from", DAY.toString())
                .param("to", "2026-06-02T00:00:00Z")
                .param("bucket", "hour")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(summary.get("data").get("series").get(0).has("owner")).isFalse();

        Long userId = jdbc.queryForObject(
                "SELECT id FROM hermit_admin_users WHERE username = ?", Long.class, USER_NAME);
        assertThat(jdbc.queryForObject(
                "SELECT owner_user_id FROM hermit_failure_reports WHERE trace_id = 'user-trace'", Long.class))
                .isEqualTo(userId);
        assertThat(jdbc.queryForObject(
                "SELECT owner_user_id FROM hermit_failure_reports WHERE trace_id = 'old-trace'", Long.class))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT owner_user_id FROM hermit_hourly_counts WHERE instance_id = ?", Long.class, REPORT_INSTANCE))
                .isEqualTo(userId);
        assertThat(jdbc.queryForObject(
                "SELECT owner_user_id FROM hermit_rollback_events WHERE event_id = 'evt-user'", Long.class))
                .isEqualTo(userId);
        assertThat(jdbc.queryForObject(
                "SELECT crab_version FROM hermit_failure_reports WHERE trace_id = 'user-trace'", String.class))
                .isEqualTo(longVersion);
        assertThat(jdbc.queryForObject(
                "SELECT crab_version FROM hermit_crab_instances WHERE instance_id = ?", String.class, REPORT_INSTANCE))
                .isEqualTo(longVersion.substring(0, 32));
        assertThat(read(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).toString())
                .doesNotContain(userToken.secret());
    }

    @Test
    void disablingRevokesDatabaseTokensAndReenableDoesNotRestoreThem() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        createUser(admin, USER_NAME, USER_PASSWORD, "USER");
        String user = login(USER_NAME, USER_PASSWORD);
        Issued first = issue(user, "phone");
        Issued second = issue(user, "laptop");
        agent(first.secret(), USER_INSTANCE).andExpect(status().isOk());
        agent(second.secret(), ADMIN_INSTANCE).andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/users/" + USER_NAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabled").value(true))
                .andExpect(jsonPath("$.data.tokenCount").value(0));
        Timestamp firstRevoked = jdbc.queryForObject(
                "SELECT revoked_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, first.id());
        Timestamp secondRevoked = jdbc.queryForObject(
                "SELECT revoked_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, second.id());
        assertThat(firstRevoked).isNotNull();
        assertThat(secondRevoked).isNotNull();
        String wrong = agentBody("wrong-token-value", "trace-revoked-user");
        assertThat(agentBody(first.secret(), "trace-revoked-user")).isEqualTo(wrong);
        assertThat(agentBody(second.secret(), "trace-revoked-user")).isEqualTo(wrong);
        mockMvc.perform(get("/api/admin/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isUnauthorized());
        agent(CONFIG_TOKEN, CONFIG_INSTANCE).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_voided_config_tokens", Integer.class)).isZero();
        List<String> details = revokeDetails();
        assertThat(details).hasSize(2);
        assertThat(details.get(0)).contains("\"name\":\"phone\"").contains("\"owner\":\"" + USER_NAME + "\"")
                .doesNotContain(first.secret());
        assertThat(details.get(1)).contains("\"name\":\"laptop\"").doesNotContain(second.secret());

        mockMvc.perform(patch("/api/admin/users/" + USER_NAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabled").value(false))
                .andExpect(jsonPath("$.data.tokenCount").value(0));
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, first.id()))
                .isEqualTo(firstRevoked);
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, second.id()))
                .isEqualTo(secondRevoked);
        assertThat(agentBody(first.secret(), "trace-still-revoked")).isEqualTo(
                agentBody("wrong-token-value", "trace-still-revoked"));
        assertThat(agentBody(second.secret(), "trace-still-revoked")).isEqualTo(
                agentBody("wrong-token-value", "trace-still-revoked"));
        mockMvc.perform(get("/api/admin/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isOk());
        login(USER_NAME, USER_PASSWORD);
        Issued fresh = issue(user, "fresh");
        agent(fresh.secret(), USER_INSTANCE).andExpect(status().isOk());
        assertThat(agentBody(first.secret(), "trace-old-stays")).isEqualTo(
                agentBody("wrong-token-value", "trace-old-stays"));
        assertThat(revokeDetails()).hasSize(2);
        agent(CONFIG_TOKEN, CONFIG_INSTANCE).andExpect(status().isOk());
    }

    @Test
    void disablingTheInitialAdminVoidsTheConfigTokenUntilItChanges() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Issued issued = issue(admin, "admin-phone");
        createUser(admin, OTHER_ADMIN, OTHER_PASSWORD, "ADMIN");
        String other = login(OTHER_ADMIN, OTHER_PASSWORD);
        agent(CONFIG_TOKEN, CONFIG_INSTANCE).andExpect(status().isOk());

        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenCount").value(0));
        assertThat(agentBody(CONFIG_TOKEN, "trace-config-disabled")).isEqualTo(
                agentBody("wrong-token-value", "trace-config-disabled"));
        assertThat(agentBody(issued.secret(), "trace-config-disabled")).isEqualTo(
                agentBody("wrong-token-value", "trace-config-disabled"));
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM hermit_agent_tokens WHERE id = ?", Timestamp.class, issued.id()))
                .isNotNull();
        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM hermit_voided_config_tokens", String.class).trim();
        assertThat(storedHash).isEqualTo(TokenDigests.sha256Hex(CONFIG_TOKEN)).doesNotContain(CONFIG_TOKEN);
        List<String> details = revokeDetails();
        assertThat(details).hasSize(2);
        assertThat(details.get(0)).contains("\"name\":\"admin-phone\"").doesNotContain(issued.secret());
        assertThat(details.get(1)).isEqualTo(configRevokeDetail(TestSecrets.USERNAME));
        JsonNode listed = read(get("/api/admin/tokens").param("all", "true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other));
        assertThat(find(listed.get("data"), "source", "CONFIG").get("revokedAt").isNull()).isFalse();
        mockMvc.perform(get("/api/admin/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabled").value(false))
                .andExpect(jsonPath("$.data.tokenCount").value(0));
        assertThat(agentBody(CONFIG_TOKEN, "trace-config-enabled")).isEqualTo(
                agentBody("wrong-token-value", "trace-config-enabled"));
        assertThat(agentBody(issued.secret(), "trace-config-enabled")).isEqualTo(
                agentBody("wrong-token-value", "trace-config-enabled"));
        mockMvc.perform(get("/api/admin/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk());
        login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Issued fresh = issue(admin, "admin-fresh");
        agent(fresh.secret(), ADMIN_INSTANCE).andExpect(status().isOk());
        assertThat(agentBody(issued.secret(), "trace-admin-old")).isEqualTo(
                agentBody("wrong-token-value", "trace-admin-old"));

        reefProperties.setAgentToken(REPLACEMENT_CONFIG);
        agent(REPLACEMENT_CONFIG, CONFIG_INSTANCE).andExpect(status().isOk());
        assertThat(agentBody(CONFIG_TOKEN, "trace-old-config")).isEqualTo(
                agentBody("wrong-token-value", "trace-old-config"));
        JsonNode restored = read(get("/api/admin/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        JsonNode config = find(restored.get("data"), "source", "CONFIG");
        assertThat(config.get("revokedAt").isNull()).isTrue();
        assertThat(config.get("owner").asText()).isEqualTo(TestSecrets.USERNAME);
        assertThat(jdbc.queryForObject(
                "SELECT u.username FROM hermit_crab_instances c JOIN hermit_admin_users u ON u.id = c.user_id "
                        + "WHERE c.instance_id = ?",
                String.class,
                CONFIG_INSTANCE)).isEqualTo(TestSecrets.USERNAME);
        assertThat(jdbc.queryForObject(
                "SELECT TRIM(token_hash) FROM hermit_voided_config_tokens", String.class))
                .isEqualTo(TokenDigests.sha256Hex(CONFIG_TOKEN));
        assertThat(revokeDetails()).hasSize(2);
    }

    @Test
    void configTokenCanBeVoidedAndARepeatDoesNotAuditAgain() throws Exception {
        mockMvc.perform(post("/api/admin/tokens/config/revoke"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("未登录或登录已过期"));
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        agent(CONFIG_TOKEN, CONFIG_INSTANCE).andExpect(status().isOk());

        MvcResult voided = mockMvc.perform(post("/api/admin/tokens/config/revoke")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(nullValue()))
                .andExpect(jsonPath("$.data.prefix").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").value(nullValue()))
                .andExpect(jsonPath("$.data.lastUsedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.name").value(AgentTokenService.CONFIG_NAME))
                .andExpect(jsonPath("$.data.source").value("CONFIG"))
                .andExpect(jsonPath("$.data.owner").value(TestSecrets.USERNAME))
                .andExpect(jsonPath("$.data.revokedAt").isNotEmpty())
                .andReturn();
        String raw = voided.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain(CONFIG_TOKEN).doesNotContain(TokenDigests.sha256Hex(CONFIG_TOKEN));
        String revokedAt = objectMapper.readTree(raw).get("data").get("revokedAt").asText();
        assertThat(agentBody(CONFIG_TOKEN, "trace-voided")).isEqualTo(agentBody("wrong-token-value", "trace-voided"));
        JsonNode afterVoid = read(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(user(afterVoid, TestSecrets.USERNAME).get("tokenCount").asInt()).isZero();

        clock.setInstant(ReefTestConfig.START.plusSeconds(120));
        mockMvc.perform(post("/api/admin/tokens/config/revoke")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revokedAt").value(revokedAt));
        assertThat(revokeDetails()).containsExactly(configRevokeDetail(TestSecrets.USERNAME));
        JsonNode users = read(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(user(users, TestSecrets.USERNAME).get("tokenCount").asInt()).isZero();

        reefProperties.setAgentToken(REPLACEMENT_CONFIG);
        agent(REPLACEMENT_CONFIG, ADMIN_INSTANCE).andExpect(status().isOk());
        assertThat(agentBody(CONFIG_TOKEN, "trace-replaced")).isEqualTo(
                agentBody("wrong-token-value", "trace-replaced"));
        JsonNode relisted = read(get("/api/admin/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(find(relisted.get("data"), "source", "CONFIG").get("revokedAt").isNull()).isTrue();
        assertThat(relisted.toString()).doesNotContain(REPLACEMENT_CONFIG).doesNotContain(CONFIG_TOKEN);
        users = read(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(user(users, TestSecrets.USERNAME).get("tokenCount").asInt()).isEqualTo(1);

        String secondRaw = mockMvc.perform(post("/api/admin/tokens/config/revoke")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revokedAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(secondRaw).doesNotContain(REPLACEMENT_CONFIG).doesNotContain(CONFIG_TOKEN);
        assertThat(agentBody(REPLACEMENT_CONFIG, "trace-both")).isEqualTo(agentBody("wrong-token-value", "trace-both"));
        assertThat(agentBody(CONFIG_TOKEN, "trace-both")).isEqualTo(agentBody("wrong-token-value", "trace-both"));
        assertThat(revokeDetails()).containsExactly(
                configRevokeDetail(TestSecrets.USERNAME),
                configRevokeDetail(TestSecrets.USERNAME));
        List<String> hashes = jdbc.queryForList(
                "SELECT token_hash FROM hermit_voided_config_tokens ORDER BY voided_at", String.class);
        assertThat(hashes.stream().map(String::trim).toList()).containsExactly(
                TokenDigests.sha256Hex(CONFIG_TOKEN),
                TokenDigests.sha256Hex(REPLACEMENT_CONFIG));

        createUser(admin, USER_NAME, USER_PASSWORD, "USER");
        String user = login(USER_NAME, USER_PASSWORD);
        mockMvc.perform(post("/api/admin/tokens/config/revoke")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("没有权限执行这个操作"));
        reefProperties.setAgentToken("");
        mockMvc.perform(post("/api/admin/tokens/config/revoke")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value(AgentTokenService.NO_CONFIG_TOKEN));
    }

    @Test
    void theSameInstanceIdKeepsOnlyTheLaterUser() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        Issued earlier = issue(admin, "earlier");
        createUser(admin, USER_NAME, USER_PASSWORD, "USER");
        String user = login(USER_NAME, USER_PASSWORD);
        Issued later = issue(user, "later");
        agent(earlier.secret(), SHARED_INSTANCE).andExpect(status().isOk());
        agent(later.secret(), SHARED_INSTANCE).andExpect(status().isOk());

        JsonNode crabs = read(get("/api/admin/crabs").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(crabs.get("data")).hasSize(1);
        assertThat(crabs.get("data").get(0).get("owner").asText()).isEqualTo(USER_NAME);
        assertThat(crabs.get("data").get(0).get("instanceId").asText()).isEqualTo(SHARED_INSTANCE);
        assertThat(crabs.get("data").get(0).get("tokenName").asText()).isEqualTo("later");
        JsonNode own = read(get("/api/admin/crabs").header(HttpHeaders.AUTHORIZATION, "Bearer " + user));
        assertThat(own.get("data")).hasSize(1);
        assertThat(own.get("data").get(0).get("owner").asText()).isEqualTo(USER_NAME);

        Long adminId = jdbc.queryForObject(
                "SELECT id FROM hermit_admin_users WHERE username = ?", Long.class, TestSecrets.USERNAME);
        jdbc.update(
                "INSERT INTO hermit_crab_instances (user_id, instance_id, token_id, first_seen_at, last_seen_at, "
                        + "crab_version) VALUES (?, ?, ?, ?, ?, NULL)",
                adminId,
                SHARED_INSTANCE,
                earlier.id(),
                Timestamp.from(ReefTestConfig.START),
                Timestamp.from(ReefTestConfig.START));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_crab_instances WHERE instance_id = ?", Integer.class, SHARED_INSTANCE))
                .isEqualTo(2);
        clock.setInstant(ReefTestConfig.START.plusSeconds(60));
        agent(later.secret(), SHARED_INSTANCE).andExpect(status().isOk());
        JsonNode again = read(get("/api/admin/crabs").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        assertThat(again.get("data")).hasSize(1);
        assertThat(again.get("data").get(0).get("owner").asText()).isEqualTo(USER_NAME);
        assertThat(again.get("data").get(0).get("tokenName").asText()).isEqualTo("later");
    }

    @Test
    void demotingTheInitialAdminMovesALiveConfigToken() throws Exception {
        String admin = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        createUser(admin, OTHER_ADMIN, OTHER_PASSWORD, "ADMIN");
        String other = login(OTHER_ADMIN, OTHER_PASSWORD);
        mockMvc.perform(patch("/api/admin/users/" + TestSecrets.USERNAME)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));
        agent(CONFIG_TOKEN, CONFIG_INSTANCE).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT u.username FROM hermit_crab_instances c JOIN hermit_admin_users u ON u.id = c.user_id "
                        + "WHERE c.instance_id = ?",
                String.class,
                CONFIG_INSTANCE)).isEqualTo(OTHER_ADMIN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_voided_config_tokens", Integer.class)).isZero();
        JsonNode listed = read(get("/api/admin/tokens").header(HttpHeaders.AUTHORIZATION, "Bearer " + other));
        JsonNode config = find(listed.get("data"), "source", "CONFIG");
        assertThat(config.get("owner").asText()).isEqualTo(OTHER_ADMIN);
        assertThat(config.get("revokedAt").isNull()).isTrue();
    }

    private List<String> revokeDetails() {
        return jdbc.queryForList(
                "SELECT detail FROM hermit_audit_log WHERE action = 'AGENT_TOKEN_REVOKED' ORDER BY id",
                String.class);
    }

    private static String configRevokeDetail(String owner) {
        return "{\"name\":\"" + AgentTokenService.CONFIG_NAME + "\",\"owner\":\"" + owner + "\",\"source\":\"CONFIG\"}";
    }

    private Issued issue(String bearer, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/tokens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.secret").isNotEmpty())
                .andExpect(jsonPath("$.data.token.id").isNumber())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        String secret = data.get("secret").asText();
        issuedSecrets.add(secret);
        return new Issued(
                data.get("token").get("id").asLong(),
                secret,
                data.get("token").get("prefix").asText(),
                data.get("publicKey").asText(),
                result.getResponse().getContentAsString());
    }

    private void createUser(String bearer, String username, String password, String role) throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userBody(username, password, role)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.role").value(role))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("token").asText();
    }

    private org.springframework.test.web.servlet.ResultActions agent(String token, String instance) throws Exception {
        return mockMvc.perform(get("/agent/version")
                .header(AgentAccessFilter.INSTANCE_HEADER, instance)
                .header(AgentAccessFilter.TOKEN_HEADER, token));
    }

    private String agentBody(String token, String traceId) throws Exception {
        return mockMvc.perform(get("/agent/version")
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .header(AgentAccessFilter.TOKEN_HEADER, token)
                        .header(TraceIds.HEADER, traceId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private void postReport(String token, String instance, String traceId, String eventId, String crabVersion)
            throws Exception {
        ReportBatch batch = new ReportBatch(instance, crabVersion, DAY,
                List.of(new FailureReport(traceId, "legacy_oa", "1.0.0", "list_todo", 2, "extract",
                        ErrorCode.RULE_MISMATCH, "状态码 302", DAY)),
                List.of(new CountReport("legacy_oa", "1.0.0", "list_todo", DAY, 3, 1, 12, 40)),
                List.of(new RollbackEvent("legacy_oa", "1.0.1", "1.0.0", "RULE_MISMATCH", DAY, eventId)));
        mockMvc.perform(post("/agent/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentAccessFilter.INSTANCE_HEADER, instance)
                        .header(AgentAccessFilter.TOKEN_HEADER, token)
                        .content(ReportJson.write(batch)))
                .andExpect(status().isOk());
    }

    private String credentials(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(username, password));
    }

    private String userBody(String username, String password, String role) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password, "role", role));
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return objectMapper.readTree(body(request));
    }

    private static String blockedTrace(String body) throws Exception {
        return new ObjectMapper().readTree(body).get("traceId").asText();
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull() && value.equals(child.asText())) {
                return node;
            }
        }
        throw new AssertionError("missing " + field + "=" + value + " in " + array);
    }

    private static JsonNode user(JsonNode body, String username) {
        return find(body.get("data"), "username", username);
    }

    private static JsonNode groupByTrace(JsonNode groups, String traceId) {
        for (JsonNode group : groups) {
            for (JsonNode recent : group.get("recent")) {
                if (traceId.equals(recent.get("traceId").asText())) {
                    return group;
                }
            }
        }
        throw new AssertionError("missing trace " + traceId + " in " + groups);
    }

    private static List<String> names(JsonNode tokens) {
        List<String> values = new ArrayList<>();
        tokens.forEach(node -> values.add(node.get("name").asText()));
        return values;
    }

    private static List<String> sources(JsonNode tokens) {
        List<String> values = new ArrayList<>();
        tokens.forEach(node -> values.add(node.get("source").asText()));
        return values;
    }

    private static List<String> instances(JsonNode crabs) {
        List<String> values = new ArrayList<>();
        crabs.forEach(node -> values.add(node.get("instanceId").asText()));
        return values;
    }

    private record Issued(long id, String secret, String prefix, String publicKey, String raw) {
    }
}
