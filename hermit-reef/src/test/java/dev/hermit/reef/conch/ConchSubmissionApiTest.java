package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.common.pkg.VerifiedConch;
import dev.hermit.reef.security.AgentAccessFilter;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "hermit.reef.agent-token=" + ConchSubmissionApiTest.AGENT_TOKEN)
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class ConchSubmissionApiTest {

    static final String AGENT_TOKEN = "submission-agent-token";
    private static final Path DATA = TestDataDirs.create("reef-submissions");
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final String USER_PASS = "UserPassword-1234";

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
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM hermit_conch_submissions");
        jdbc.update("DELETE FROM hermit_conch_versions");
        jdbc.update("DELETE FROM hermit_conch_availability");
        jdbc.update("DELETE FROM hermit_crab_instances");
        jdbc.update("DELETE FROM hermit_agent_tokens");
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
                ALICE,
                passwordEncoder.encode(USER_PASS),
                Timestamp.from(ReefTestConfig.START));
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled, role) "
                        + "VALUES (?, ?, ?, FALSE, 'USER')",
                BOB,
                passwordEncoder.encode(USER_PASS),
                Timestamp.from(ReefTestConfig.START));
        cleanDir(DATA.resolve("conches"));
        cleanDir(DATA.resolve("submissions"));
        adminToken = login(TestSecrets.USERNAME, TestSecrets.PASSWORD);
        aliceToken = login(ALICE, USER_PASS);
        bobToken = login(BOB, USER_PASS);
    }

    @Test
    void fullApprovalWorkflowNonSquattingAndSignatureVerification() throws Exception {
        byte[] v1Yaml = sampleYaml("demo_oa", "1.0.0", "list_todos").getBytes(StandardCharsets.UTF_8);

        // Alice 提交 1.0.0
        MvcResult aliceSub = mockMvc.perform(multipart("/api/admin/submissions")
                        .file(new MockMultipartFile("file", "demo_oa.yaml", "text/yaml", v1Yaml))
                        .param("note", "首版待办查询规则")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conch").value("demo_oa"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.submittedBy").value(ALICE))
                .andExpect(jsonPath("$.data.submitNote").value("首版待办查询规则"))
                .andReturn();
        long aliceId = objectMapper.readTree(aliceSub.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 待审批不占正式版本，Agent 清单为空
        String instanceId = "123e4567-e89b-12d3-a456-426614174000";
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hermit_conch_versions", Integer.class)).isZero();
        mockMvc.perform(get("/agent/conches/manifest")
                        .header(AgentAccessFilter.INSTANCE_HEADER, instanceId)
                        .header(AgentAccessFilter.TOKEN_HEADER, AGENT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // Bob 也能提交同一版本号 1.0.0（不抢占），但看不到 Alice 的提交
        MvcResult bobSub = mockMvc.perform(multipart("/api/admin/submissions")
                        .file(new MockMultipartFile("file", "demo_oa.yaml", "text/yaml", v1Yaml))
                        .param("note", "另一个人的 1.0.0 提案")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andReturn();
        long bobId = objectMapper.readTree(bobSub.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/admin/submissions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(bobId));

        mockMvc.perform(get("/api/admin/submissions/" + aliceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/submissions/" + aliceId + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // USER 不能批准或驳回
        mockMvc.perform(post("/api/admin/submissions/" + aliceId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/submissions/" + aliceId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"no\"}"))
                .andExpect(status().isForbidden());

        // ADMIN 看到 2 条待审批，并可查看 YAML 原文
        mockMvc.perform(get("/api/admin/submissions/pending-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));

        MvcResult yamlRes = mockMvc.perform(get("/api/admin/submissions/" + aliceId + "/yaml")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(yamlRes.getResponse().getContentAsByteArray()).isEqualTo(v1Yaml);

        // ADMIN 批准 Alice 的 1.0.0 -> 原子落库 + 签名发布
        mockMvc.perform(post("/api/admin/submissions/" + aliceId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"校验通过，准予发布\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewedBy").value(TestSecrets.USERNAME))
                .andExpect(jsonPath("$.data.reviewComment").value("校验通过，准予发布"));

        // 正式版本记录 uploaded_by = alice, published_by = admin，签名包可用公钥验签
        Map<String, Object> verRow = jdbc.queryForMap(
                "SELECT uploaded_by, published_by, status, is_current FROM hermit_conch_versions "
                        + "WHERE conch = 'demo_oa' AND version = '1.0.0'");
        assertThat(verRow.get("uploaded_by")).isEqualTo(ALICE);
        assertThat(verRow.get("published_by")).isEqualTo(TestSecrets.USERNAME);
        assertThat(verRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(verRow.get("is_current")).isEqualTo(true);

        MvcResult keyRes = mockMvc.perform(get("/api/admin/signing-key")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String pem = objectMapper.readTree(keyRes.getResponse().getContentAsString())
                .path("data").path("publicKey").asText();
        PublicKey publicKey = Ed25519Keys.readPublicPem(pem);

        MvcResult pkgRes = mockMvc.perform(get("/agent/conches/demo_oa/1.0.0")
                        .header(AgentAccessFilter.INSTANCE_HEADER, instanceId)
                        .header(AgentAccessFilter.TOKEN_HEADER, AGENT_TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        VerifiedConch verified = ConchPackage.readVerified(pkgRes.getResponse().getContentAsByteArray(), publicKey);
        assertThat(verified.manifest().conch()).isEqualTo("demo_oa");
        assertThat(verified.manifest().version()).isEqualTo("1.0.0");

        // Bob 的同版本 1.0.0 在详情里显示版本冲突，批准时返回 409
        mockMvc.perform(get("/api/admin/submissions/" + bobId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionConflict").value(true))
                .andExpect(jsonPath("$.data.currentPublishedVersion").value("1.0.0"));

        mockMvc.perform(post("/api/admin/submissions/" + bobId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        // 驳回必须写原因
        mockMvc.perform(post("/api/admin/submissions/" + bobId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/admin/submissions/" + bobId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"1.0.0 已被采用，请基于 1.1.0 重新提交\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        // 1.0.0 已发布后，再次提交 1.0.0 被拒；提交 1.1.0 带实时 diff，且本人可撤回
        mockMvc.perform(multipart("/api/admin/submissions")
                        .file(new MockMultipartFile("file", "demo_oa.yaml", "text/yaml", v1Yaml))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isConflict());

        byte[] v11Yaml = sampleYaml("demo_oa", "1.1.0", "list_todos_v2").getBytes(StandardCharsets.UTF_8);
        MvcResult upgradeSub = mockMvc.perform(multipart("/api/admin/submissions")
                        .file(new MockMultipartFile("file", "demo_oa.yaml", "text/yaml", v11Yaml))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentPublishedVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data.diff.addedActions[0]").value("list_todos_v2"))
                .andReturn();
        long upgradeId = objectMapper.readTree(upgradeSub.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(post("/api/admin/submissions/" + upgradeId + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));

        List<String> actions = jdbc.queryForList("SELECT action FROM hermit_audit_log ORDER BY id", String.class);
        assertThat(actions).contains(
                "SUBMISSION_CREATED",
                "PUBLISH",
                "SUBMISSION_APPROVED",
                "SUBMISSION_REJECTED",
                "SUBMISSION_WITHDRAWN");
    }

    @Test
    void invalidYamlAndDisabledConchAreRejected() throws Exception {
        byte[] invalid = "conch: BAD NAME\nversion: not-semver\n".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/api/admin/submissions")
                        .file(new MockMultipartFile("file", "bad.yaml", "text/yaml", invalid))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        // 先发布并停用 demo_disabled，再提交应 409
        byte[] yaml = sampleYaml("demo_disabled", "1.0.0", "ping").getBytes(StandardCharsets.UTF_8);
        MvcResult created = mockMvc.perform(multipart("/api/admin/submissions")
                        .file(new MockMultipartFile("file", "demo_disabled.yaml", "text/yaml", yaml))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        mockMvc.perform(post("/api/admin/submissions/" + id + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/conches/demo_disabled/disable")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"维护中\"}"))
                .andExpect(status().isOk());

        byte[] yamlV2 = sampleYaml("demo_disabled", "1.1.0", "ping").getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/api/admin/submissions")
                        .file(new MockMultipartFile("file", "demo_disabled.yaml", "text/yaml", yamlV2))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isConflict());
    }

    @Test
    void agentSubmissionRequiresUserOwnedTokenAndSupportsPendingAuthenticatorQueue() throws Exception {
        String instanceId = "123e4567-e89b-12d3-a456-426614174001";
        String authYaml = """
                conch: corp_sso
                version: 1.2.0
                driver: auth
                min_crab_version: 1.14.0
                title: 统一登录
                description: 公司统一登录授权器
                credentials:
                  username: { type: text, label: 账号 }
                  password: { type: secret, label: 密码 }
                auth:
                  origins: ["https://sso.corp.example"]
                  steps:
                    - request:
                        method: POST
                        url: https://sso.corp.example/login
                        form: { u: "{{credential.username}}", p: "{{credential.password}}" }
                    - extract:
                        access_token: { jsonpath: "$.token" }
                  alive_check:
                    request:
                      method: GET
                      url: https://sso.corp.example/me
                      headers: { Authorization: "Bearer {{login.access_token}}" }
                  exports:
                    access_token: { description: 访问令牌 }
                """;
        String consumerYaml = """
                conch: corp_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.14.0
                base_url: https://oa.corp.example
                title: 协同办公
                description: 复用统一登录的 OA 规则包
                auth:
                  use: corp_sso
                  min_version: 1.2.0
                  alive_check:
                    request: { method: GET, path: /api/me }
                actions:
                  list_todos:
                    title: 待办
                    description: 查询待办
                    output:
                      ok: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { ok: ok }
                    notes: 只读查询
                    steps:
                      - request:
                          method: GET
                          path: /api/todos
                          headers: { Authorization: "Bearer {{auth.access_token}}" }
                """;

        // 环境变量静态 Agent 令牌没有归属用户，拒绝提交（403）
        mockMvc.perform(post("/agent/submissions")
                        .header(AgentAccessFilter.INSTANCE_HEADER, instanceId)
                        .header(AgentAccessFilter.TOKEN_HEADER, AGENT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("yaml", authYaml, "note", "静态令牌尝试"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // Alice 生成个人归属的数据库 Agent 令牌
        MvcResult issuedRes = mockMvc.perform(post("/api/admin/tokens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alice-crab\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String aliceAgentSecret = objectMapper.readTree(issuedRes.getResponse().getContentAsString())
                .path("data").path("secret").asText();

        // 1. 提交未发布的授权器 corp_sso -> PENDING，归属人记为 alice
        MvcResult ssoSub = mockMvc.perform(post("/agent/submissions")
                        .header(AgentAccessFilter.INSTANCE_HEADER, instanceId)
                        .header(AgentAccessFilter.TOKEN_HEADER, aliceAgentSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("yaml", authYaml, "note", "统一登录首版"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conch").value("corp_sso"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.submittedBy").value(ALICE))
                .andReturn();
        long ssoSubId = objectMapper.readTree(ssoSub.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 2. 授权器尚在待审队列时，匹配的业务包 corp_oa 允许一并进待审队列并附带提示；不匹配的业务包仍被拒绝
        String mismatchedConsumer = consumerYaml.replace("{{auth.access_token}}", "{{auth.missing_export}}");
        mockMvc.perform(post("/agent/submissions")
                        .header(AgentAccessFilter.INSTANCE_HEADER, instanceId)
                        .header(AgentAccessFilter.TOKEN_HEADER, aliceAgentSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("yaml", mismatchedConsumer))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        MvcResult oaSub = mockMvc.perform(post("/agent/submissions")
                        .header(AgentAccessFilter.INSTANCE_HEADER, instanceId)
                        .header(AgentAccessFilter.TOKEN_HEADER, aliceAgentSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("yaml", consumerYaml, "note", "依赖待审授权器"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conch").value("corp_oa"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.warnings[0]").exists())
                .andReturn();
        long oaSubId = objectMapper.readTree(oaSub.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 3. 管理员若先批准业务包 corp_oa，因 corp_sso 尚未发布被 409 拦截；先批准 corp_sso 再批准 corp_oa 即成功
        mockMvc.perform(post("/api/admin/submissions/" + oaSubId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/admin/submissions/" + ssoSubId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(post("/api/admin/submissions/" + oaSubId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    private static String sampleYaml(String conch, String version, String actionName) {
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.0.0
                base_url: "https://oa.example.internal"
                title: 示例 OA 规则
                description: 用于审批流程回归测试的示例规则包
                credentials: {}
                actions:
                  %s:
                    title: 查询待办
                    description: 获取待办列表
                    output:
                      items: { type: string, description: 结果, value: "{{items}}" }
                    examples:
                      - input: {}
                        output: { items: ok }
                    notes: 只读查询
                    steps:
                      - request:
                          method: GET
                          path: /api/todos
                      - extract:
                          items: { jsonpath: "$.items" }
                """.formatted(conch, version, actionName);
    }

    private static void cleanDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
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
