package dev.hermit.reef.conch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "hermit.reef.agent-token=reef-groups-test-token")
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class ConchGroupsApiTest {

    private static final Path DATA = TestDataDirs.create("conch-groups-api");

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", DATA::toString);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private String adminToken;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM hermit_conch_versions");
        jdbc.update("DELETE FROM hermit_conch_availability");
        jdbc.update("DELETE FROM hermit_audit_log");
        jdbc.update("DELETE FROM hermit_admin_login_failures");
        jdbc.update("DELETE FROM hermit_admin_users");
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
                TestSecrets.USERNAME,
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(ReefTestConfig.START));
        MvcResult login = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", TestSecrets.USERNAME,
                                "password", TestSecrets.PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        adminToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    @Test
    void uploadsExposesAndDiffsGroupedConch() throws Exception {
        String v1 = """
                conch: grouped_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.10.0
                base_url: http://127.0.0.1:8080
                title: 分组示例
                description: 带一二级分组
                credentials: {}
                groups:
                  approval:
                    title: 审批管理
                    description: 流程审批
                    children:
                      leave:
                        title: 请假审批
                actions:
                  get_me:
                    title: 当前用户
                    description: 顶层能力
                    method: GET
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {ok: "1"}
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: "1"}
                    notes: 顶层
                    related: []
                  list_leave:
                    group: approval/leave
                    title: 查询请假
                    description: 请假列表
                    method: GET
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {ok: "1"}
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: "1"}
                    notes: 请假
                    related: []
                """;
        String v2 = """
                conch: grouped_oa
                version: 1.1.0
                driver: http
                min_crab_version: 1.10.0
                base_url: http://127.0.0.1:8080
                title: 分组示例
                description: 带一二级分组
                credentials: {}
                groups:
                  approval:
                    title: 审批中心
                    description: 流程审批
                    children:
                      leave:
                        title: 请假审批
                  hr:
                    title: 人事档案
                actions:
                  get_me:
                    group: hr
                    title: 当前用户
                    description: 归入人事
                    method: GET
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {ok: "1"}
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: "1"}
                    notes: 人事
                    related: []
                  list_leave:
                    group: approval/leave
                    title: 查询请假
                    description: 请假列表
                    method: GET
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {ok: "1"}
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: "1"}
                    notes: 请假
                    related: []
                """;

        upload("grouped_oa.yaml", v1);
        mockMvc.perform(post("/api/admin/conches/grouped_oa/versions/1.0.0/publish")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/conches/grouped_oa")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].name").value("approval"))
                .andExpect(jsonPath("$.data.groups[0].children[0].name").value("leave"))
                .andExpect(jsonPath("$.data.actions.list_leave.group").value("approval/leave"))
                .andExpect(jsonPath("$.data.actions.get_me.group").isEmpty());

        upload("grouped_oa.yaml", v2);
        mockMvc.perform(get("/api/admin/conches/grouped_oa/diff?from=1.0.0&to=1.1.0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedGroups[0]").value("hr"))
                .andExpect(jsonPath("$.data.changedGroups[0]").value("approval"))
                .andExpect(jsonPath("$.data.actionGroupChanges[0].name").value("get_me"))
                .andExpect(jsonPath("$.data.actionGroupChanges[0].after").value("hr"));

        // Rejects groups when min_crab_version < 1.10.0
        String tooLow = v1.replace("conch: grouped_oa", "conch: low_grouped").replace("min_crab_version: 1.10.0", "min_crab_version: 1.9.0");
        MockMultipartFile badFile = new MockMultipartFile(
                "file", "low.yaml", "application/yaml", tooLow.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/conches")
                        .file(badFile)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errors[0].code").value("MIN_CRAB_VERSION"));
    }

    @Test
    void uploadRejectsDuplicateTopLevelAndChildGroupNames() throws Exception {
        String duplicateGroupsYaml = """
                conch: dup_grouped_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.10.0
                base_url: http://127.0.0.1:8080
                title: 重复分组示例
                description: 测试重复分组名拦截
                credentials: {}
                groups:
                  approval:
                    title: 审批管理
                    children:
                      leave:
                        title: 请假审批
                      leave:
                        title: 重复请假
                  approval:
                    title: 重复审批
                actions:
                  list_leave:
                    group: approval
                    title: 查询请假
                    description: 请假列表
                    method: GET
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - set: {ok: "1"}
                    output:
                      ok: {type: string, description: 结果}
                    examples:
                      - input: {}
                        output: {ok: "1"}
                    notes: 请假
                    related: []
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "dup.yaml", "application/yaml", duplicateGroupsYaml.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/conches")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data.errors.length()").value(2))
                .andExpect(jsonPath("$.data.errors[0].code").value("GROUP_NAME"))
                .andExpect(jsonPath("$.data.errors[0].path").value("groups.approval.children.leave"))
                .andExpect(jsonPath("$.data.errors[0].line").value(15))
                .andExpect(jsonPath("$.data.errors[1].code").value("GROUP_NAME"))
                .andExpect(jsonPath("$.data.errors[1].path").value("groups.approval"))
                .andExpect(jsonPath("$.data.errors[1].line").value(17));
    }

    private void upload(String filename, String yaml) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, "application/yaml", yaml.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/admin/conches")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
