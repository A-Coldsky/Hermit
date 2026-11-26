package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "hermit.reef.agent-token=reef-auth-dep-test-token")
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class ConchAuthDependencyTest {

    private static final Path DATA = TestDataDirs.create("conch-auth-dependency");

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
    void aConsumerNeedsItsAuthenticatorPublishedFirst() throws Exception {
        upload(CONSUMER)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errors[0].code").value("AUTHENTICATOR"))
                .andExpect(jsonPath("$.data.errors[0].path").value("auth.use"));

        // 只上传、没发布的授权器不算。
        upload(AUTHENTICATOR).andExpect(status().isOk());
        upload(CONSUMER).andExpect(status().isUnprocessableEntity());

        publish("corp_sso", "1.2.0").andExpect(status().isOk());
        upload(CONSUMER).andExpect(status().isOk());
        publish("corp_oa", "1.0.0").andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "SELECT auth_use FROM hermit_conch_versions WHERE conch = 'corp_oa'", String.class))
                .isEqualTo("corp_sso");
        assertThat(jdbc.queryForObject(
                        "SELECT auth_use FROM hermit_conch_versions WHERE conch = 'corp_sso'", String.class))
                .isNull();
        mockMvc.perform(get("/api/admin/conches/corp_oa").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authUse").value("corp_sso"));
        mockMvc.perform(get("/api/admin/conches/corp_sso").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.driver").value("auth"))
                .andExpect(jsonPath("$.data.authExports[0]").value("access_token"));
    }

    @Test
    void aConsumerMustMatchThePublishedAuthenticator() throws Exception {
        upload(AUTHENTICATOR).andExpect(status().isOk());
        publish("corp_sso", "1.2.0").andExpect(status().isOk());

        upload(CONSUMER.replace("min_version: 1.2.0", "min_version: 1.3.0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errors[0].code").value("AUTHENTICATOR"));
        upload(CONSUMER.replace("{{auth.access_token}}", "{{auth.refresh_token}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errors[0].code").value("AUTHENTICATOR"));
    }

    @Test
    void anAuthenticatorCannotDropAnExportAPublishedConsumerUses() throws Exception {
        publishBoth();

        String without = AUTHENTICATOR
                .replace("version: 1.2.0", "version: 1.3.0")
                .replace("access_token: { description: 访问令牌 }", "other: { description: 别的 }");
        upload(without)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errors[0].code").value("AUTHENTICATOR"))
                .andExpect(jsonPath("$.data.errors[0].message").value(org.hamcrest.Matchers.containsString("corp_oa")));

        // 业务包停用后不再拦。
        disable("corp_oa").andExpect(status().isOk());
        upload(without).andExpect(status().isOk());
        publish("corp_sso", "1.3.0").andExpect(status().isOk());
        // 这时业务包对不上了。仍然可以重新启用（否则停用的包发不了新版本，就再也启用不了），
        // 启用后发布改好的版本；照旧引用被删变量的版本发不出去。
        enable("corp_oa")
                .andExpect(status().isOk());
        upload(CONSUMER.replace("version: 1.0.0", "version: 1.0.1")).andExpect(status().isUnprocessableEntity());
        upload(CONSUMER.replace("version: 1.0.0", "version: 1.0.2").replace("auth.access_token", "auth.other"))
                .andExpect(status().isOk());
        publish("corp_oa", "1.0.2").andExpect(status().isOk());
    }

    @Test
    void anAuthenticatorInUseCannotBeDisabled() throws Exception {
        publishBoth();

        disable("corp_sso")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("corp_oa")));

        disable("corp_oa").andExpect(status().isOk());
        disable("corp_sso").andExpect(status().isOk());
        // 授权器停用期间，业务包不能重新启用，也不能发新版本。
        enable("corp_oa")
                .andExpect(status().isConflict());
        upload(CONSUMER.replace("version: 1.0.0", "version: 1.0.1")).andExpect(status().isUnprocessableEntity());

        enable("corp_sso")
                .andExpect(status().isOk());
        enable("corp_oa")
                .andExpect(status().isOk());
    }

    @Test
    void publishingChecksTheDependencyAgain() throws Exception {
        publishBoth();
        // 两个新版本上传时各自都对得上；授权器先发布之后，业务包的旧写法就对不上了。
        upload(CONSUMER.replace("version: 1.0.0", "version: 1.1.0"))
                .andExpect(status().isOk());
        disable("corp_oa").andExpect(status().isOk());
        upload(AUTHENTICATOR
                        .replace("version: 1.2.0", "version: 1.3.0")
                        .replace("access_token: { description: 访问令牌 }", "other: { description: 别的 }"))
                .andExpect(status().isOk());
        publish("corp_sso", "1.3.0").andExpect(status().isOk());

        publish("corp_oa", "1.1.0").andExpect(status().isConflict());
    }

    @Test
    void theManifestNamesTheAuthenticatorAConsumerUses() throws Exception {
        publishBoth();

        mockMvc.perform(get("/agent/conches/manifest")
                        .header(dev.hermit.reef.security.AgentAccessFilter.INSTANCE_HEADER,
                                "123e4567-e89b-12d3-a456-426614174000")
                        .header(dev.hermit.reef.security.AgentAccessFilter.TOKEN_HEADER, "reef-auth-dep-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.conch=='corp_oa')].authUse").value("corp_sso"))
                .andExpect(jsonPath("$.data[?(@.conch=='corp_oa')].driver").value("http"))
                .andExpect(jsonPath("$.data[?(@.conch=='corp_sso')].driver").value("auth"))
                .andExpect(jsonPath("$.data[?(@.conch=='corp_sso')].authUse").doesNotExist());
    }

    @Test
    void submissionApprovalReportsDependencyConflictAndExposesAuthenticatorMetadata() throws Exception {
        String authWithAllow = AUTHENTICATOR.replace("  exports:",
                "  allow_hosts: [\"https://*.corp.example\"]\n  exports:");
        MvcResult authUpload = upload(authWithAllow).andExpect(status().isOk()).andReturn();
        String uploadBody = authUpload.getResponse().getContentAsString();
        assertThat(uploadBody).doesNotContain("除了 base_url");
        publish("corp_sso", "1.2.0").andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/conches/corp_sso").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authAllowHosts[0]").value("https://*.corp.example"));

        // 业务包站点若与引用的授权器认证地址相同，不应误报「各自登录互相顶掉」的 SAME_AUTH 警告。
        String sameHostConsumer = CONSUMER.replace("https://oa.corp.example", "https://sso.corp.example")
                .replace("corp_oa", "sso_portal");
        MvcResult consumerUpload = upload(sameHostConsumer).andExpect(status().isOk()).andReturn();
        assertThat(consumerUpload.getResponse().getContentAsString()).doesNotContain("SAME_AUTH");

        // 提交待审后若授权器被停用，批准应返回 409 并带出具体原因，详情包含 authUse / authMinVersion。
        MvcResult subRes = mockMvc.perform(multipart("/api/admin/submissions")
                        .file(new MockMultipartFile("file", "oa.yaml", "application/yaml",
                                CONSUMER.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authUse").value("corp_sso"))
                .andExpect(jsonPath("$.data.authMinVersion").value("1.2.0"))
                .andReturn();
        long subId = objectMapper.readTree(subRes.getResponse().getContentAsString()).path("data").path("id").asLong();

        disable("corp_sso").andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/submissions/" + subId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("已停用")));

        // Emoji 代理对截断不会产生孤立高代理项，且悲观锁冲突映射为 409 CONFLICT。
        String emojiTitle = "A".repeat(255) + "\uD83E\uDD80";
        String truncated = ConchSubmissionTransaction.safeTitle(emojiTitle, "corp_oa");
        assertThat(truncated).hasSize(255);
        assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1))).isFalse();

        dev.hermit.reef.web.GlobalExceptionHandler handler = new dev.hermit.reef.web.GlobalExceptionHandler();
        var lockResp = handler.lockConflict(
                new org.springframework.dao.CannotAcquireLockException("deadlock"),
                new org.springframework.mock.web.MockHttpServletRequest());
        assertThat(lockResp.getStatusCode().value()).isEqualTo(409);
    }

    private void publishBoth() throws Exception {
        upload(AUTHENTICATOR).andExpect(status().isOk());
        publish("corp_sso", "1.2.0").andExpect(status().isOk());
        upload(CONSUMER).andExpect(status().isOk());
        publish("corp_oa", "1.0.0").andExpect(status().isOk());
    }

    private ResultActions upload(String yaml) throws Exception {
        return mockMvc.perform(multipart("/api/admin/conches")
                .file(new MockMultipartFile("file", "x.yaml", "application/yaml",
                        yaml.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + adminToken));
    }

    private ResultActions publish(String conch, String version) throws Exception {
        return mockMvc.perform(post("/api/admin/conches/" + conch + "/versions/" + version + "/publish")
                .header("Authorization", "Bearer " + adminToken));
    }

    private ResultActions disable(String conch) throws Exception {
        return toggle(conch, "disable");
    }

    private ResultActions enable(String conch) throws Exception {
        return toggle(conch, "enable");
    }

    private ResultActions toggle(String conch, String verb) throws Exception {
        return mockMvc.perform(post("/api/admin/conches/" + conch + "/" + verb)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"测试\"}"));
    }

    private static final String AUTHENTICATOR = """
            conch: corp_sso
            version: 1.2.0
            driver: auth
            min_crab_version: 1.13.0
            title: 公司统一登录
            description: 公司 SSO 的登录
            credentials:
              username: { type: text, label: 用户名 }
              password: { type: secret, label: 密码 }
            auth:
              origins: ["https://sso.corp.example"]
              steps:
                - request:
                    method: POST
                    url: https://sso.corp.example/login
                    form: { u: "{{credential.username}}", p: "{{credential.password}}" }
                - extract: { access_token: "$.token", other: "$.other" }
              alive_check:
                request:
                  method: GET
                  url: https://sso.corp.example/me
                  headers: { Authorization: "Bearer {{login.access_token}}" }
              exports:
                access_token: { description: 访问令牌 }
            """;

    private static final String CONSUMER = """
            conch: corp_oa
            version: 1.0.0
            driver: http
            min_crab_version: 1.13.0
            base_url: https://oa.corp.example
            title: OA
            description: 公司 OA
            auth:
              use: corp_sso
              min_version: 1.2.0
              alive_check:
                request: { method: GET, path: /api/me }
            actions:
              list_todos:
                title: 待办
                description: 列出待办
                method: GET
                output:
                  marker: { type: string, description: 标记, value: ok }
                examples:
                  - input: {}
                    output: { marker: ok }
                steps:
                  - request:
                      method: GET
                      path: /api/todos
                      headers: { Authorization: "Bearer {{auth.access_token}}" }
            """;
}
