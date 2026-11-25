package dev.hermit.reef.conch;

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

@SpringBootTest(properties = "hermit.reef.agent-token=reef-expiry-test-token")
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class ConchSessionExpiryUploadTest {

    private static final Path DATA = TestDataDirs.create("conch-session-expiry");

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
    void acceptsValid112ExpiryConchAndRejectsLowerMinVersion() throws Exception {
        String valid = yaml("1.12.0");
        mockMvc.perform(multipart("/api/admin/conches")
                        .file(new MockMultipartFile("file", "oa.yaml", "application/yaml",
                                valid.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conch").value("oa_200_expiry"));

        String tooLow = yaml("1.11.0");
        mockMvc.perform(multipart("/api/admin/conches")
                        .file(new MockMultipartFile("file", "oa.yaml", "application/yaml",
                                tooLow.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errors[0].code").value("MIN_CRAB_VERSION"));
    }

    @Test
    void rejectsAuthenticatorPackagesUntilDistributionLands() throws Exception {
        // 写法能校验，但 Reef 还没有依赖校验和分发，Crab 也不能运行：先不让发布。
        String authenticator = """
                conch: corp_sso
                version: 1.0.0
                driver: auth
                min_crab_version: 1.13.0
                title: 统一登录
                description: 说明
                credentials: {}
                auth:
                  origins: ["https://sso.corp.example"]
                  steps:
                    - request: {method: GET, url: https://sso.corp.example/login}
                  alive_check:
                    request: {method: GET, url: https://sso.corp.example/me}
                """;
        String consumer = yaml("1.13.0")
                .replace("credentials: {}\n", "")
                .replace("  steps:\n    - request: {method: POST, path: /login}\n    - assert: {status: 200}\n",
                        "  use: corp_sso\n");
        for (String body : new String[] {authenticator, consumer}) {
            mockMvc.perform(multipart("/api/admin/conches")
                            .file(new MockMultipartFile("file", "x.yaml", "application/yaml",
                                    body.getBytes(StandardCharsets.UTF_8)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.data.errors.length()").value(1))
                    .andExpect(jsonPath("$.data.errors[0].code").value("AUTHENTICATOR"));
        }
    }

    private static String yaml(String minCrab) {
        return """
                conch: oa_200_expiry
                version: 1.0.0
                driver: http
                min_crab_version: %s
                base_url: http://127.0.0.1:8080
                title: 200失效示例
                description: 返回200业务码表示登录失效
                credentials: {}
                auth:
                  expired_when:
                    - json_equals: {"$.code": [401, 10001]}
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200}
                  alive_check:
                    request: {method: GET, path: /api/me}
                    expect:
                      status: 200
                      json_equals: {"$.code": 0}
                actions:
                  list:
                    title: 列表
                    description: 查询列表
                    method: GET
                    idempotent: true
                    mcp: {expose: true, read_only: true}
                    steps:
                      - request: {method: GET, path: /api/items}
                      - assert: {not_contains: "请重新登录", on_fail: session_expired}
                    output:
                      ok: {type: string, description: 结果, value: ok}
                    examples:
                      - input: {}
                        output: {ok: ok}
                    notes: 查询
                    related: []
                """.formatted(minCrab);
    }
}
