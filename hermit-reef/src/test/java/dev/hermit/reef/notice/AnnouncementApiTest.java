package dev.hermit.reef.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.testsupport.AdjustableClock;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class AnnouncementApiTest {

    private static final String USER_NAME = "alice";
    private static final String USER_PASSWORD = "AlicePassword-1234";

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("reef-announcements").toString());
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

    private String adminToken;
    private String userToken;

    @BeforeEach
    void reset() throws Exception {
        clock.setInstant(ReefTestConfig.START);
        jdbc.update("DELETE FROM hermit_announcements");
        jdbc.update("DELETE FROM hermit_audit_log");
        jdbc.update("DELETE FROM hermit_admin_login_failures");
        jdbc.update("DELETE FROM hermit_admin_users");
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled, role) "
                        + "VALUES (?, ?, ?, FALSE, 'ADMIN')",
                TestSecrets.USERNAME,
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(ReefTestConfig.START));
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled, role) "
                        + "VALUES (?, ?, ?, FALSE, 'USER')",
                USER_NAME,
                passwordEncoder.encode(USER_PASSWORD),
                Timestamp.from(ReefTestConfig.START));
        adminToken = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        userToken = login(USER_NAME, USER_PASSWORD);
    }

    @Test
    void adminCrudAndOrderingVisibleToUsers() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/admin/announcements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "普通通知",
                                "body", "请升级到最新版 Crab\n支持独立 JAR 启动",
                                "level", "INFO",
                                "pinned", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.level").value("INFO"))
                .andExpect(jsonPath("$.data.pinned").value(false))
                .andReturn();
        long firstId = objectMapper.readTree(first.getResponse().getContentAsString()).path("data").path("id").asLong();

        clock.setInstant(clock.instant().plus(Duration.ofMinutes(5)));
        MvcResult second = mockMvc.perform(post("/api/admin/announcements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "置顶维护通知",
                                "body", "今晚 22:00 升级 Reef",
                                "level", "IMPORTANT",
                                "pinned", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pinned").value(true))
                .andReturn();
        long secondId = objectMapper.readTree(second.getResponse().getContentAsString()).path("data").path("id").asLong();

        // USER 可读，且置顶公告排在前面
        mockMvc.perform(get("/api/admin/announcements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(secondId))
                .andExpect(jsonPath("$.data[1].id").value(firstId));

        // ADMIN 更新第一条为置顶且最新时间，顺序应跑到最前
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(5)));
        mockMvc.perform(put("/api/admin/announcements/" + firstId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "已更新的通知",
                                "body", "新版 JAR 已放入 downloads 目录",
                                "level", "WARNING",
                                "pinned", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("已更新的通知"))
                .andExpect(jsonPath("$.data.level").value("WARNING"))
                .andExpect(jsonPath("$.data.pinned").value(true));

        mockMvc.perform(get("/api/admin/announcements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(firstId))
                .andExpect(jsonPath("$.data[1].id").value(secondId));

        // ADMIN 删除第二条
        mockMvc.perform(delete("/api/admin/announcements/" + secondId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(secondId));

        mockMvc.perform(get("/api/admin/announcements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM hermit_audit_log WHERE action <> 'LOGIN_SUCCESS' ORDER BY id", String.class);
        assertThat(actions).containsExactly(
                "ANNOUNCEMENT_CREATED",
                "ANNOUNCEMENT_CREATED",
                "ANNOUNCEMENT_UPDATED",
                "ANNOUNCEMENT_DELETED");
    }

    @Test
    void userCannotMutateAnnouncementsAndValidationRejectsBadInput() throws Exception {
        mockMvc.perform(get("/api/admin/announcements"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/announcements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"body\":\"b\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/announcements/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"body\":\"b\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/admin/announcements/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/announcements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \",\"body\":\"正文\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        mockMvc.perform(post("/api/admin/announcements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"标题\",\"body\":\"正文\",\"level\":\"CRITICAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }
}
