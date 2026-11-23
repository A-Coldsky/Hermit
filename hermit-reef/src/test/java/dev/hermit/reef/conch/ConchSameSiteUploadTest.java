package dev.hermit.reef.conch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
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

/** 上传时提示已发布的同网站规则包：只提示，不阻止；只比较包里写的地址。 */
@SpringBootTest(properties = "hermit.reef.agent-token=reef-same-site-test-token")
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class ConchSameSiteUploadTest {

    private static final Path DATA = TestDataDirs.create("conch-same-site");

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
    void uploadWarnsAboutPublishedPackagesOnTheSameSite() throws Exception {
        // 第一位作者的包，已发布。
        assertThat(sameSite(upload(yaml("oa_base", "1.0.0", "https://oa.corp.com", null)))).isEmpty();
        publish("oa_base", "1.0.0");
        // 另一个网站的包，已发布。
        assertThat(sameSite(upload(yaml("erp_base", "1.0.0", "https://erp.corp.com", null)))).isEmpty();
        publish("erp_base", "1.0.0");
        // 同一网站、只上传没有发布的包：它自己收到提示，但别的 Crab 拿不到它，不会出现在别人的提示里。
        assertThat(sameSite(upload(yaml("oa_draft", "1.0.0", "https://oa.corp.com", null)))).hasSize(1);

        // 第二位作者不知道 oa_base：地址大小写、默认端口、路径不同，仍是同一网站。上传成功，带一条警告。
        JsonNode uploaded = upload(yaml("oa_leave", "1.0.0", "HTTPS://OA.corp.com:443/leave", null));
        List<JsonNode> hits = sameSite(uploaded);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).path("path").asText()).isEqualTo("base_url");
        assertThat(hits.get(0).path("message").asText())
                .contains("oa_base")
                .contains("维护者 平台组")
                .contains("https://oa.corp.com:443")
                .doesNotContain("erp_base")
                .doesNotContain("oa_draft");

        // 警告随版本保存，版本列表里也能看到。
        MvcResult versions = mockMvc.perform(get("/api/admin/conches/oa_leave/versions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(versions.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("SAME_SITE");

        // 自己的新版本不和自己的旧版本比。
        publish("oa_leave", "1.0.0");
        List<JsonNode> next = sameSite(upload(yaml("oa_leave", "1.1.0", "https://oa.corp.com", null)));
        assertThat(next).hasSize(1);
        assertThat(next.get(0).path("message").asText()).contains("oa_base").doesNotContain("oa_leave");

        // 配置项 base_url 的默认值也算包里写的地址；协议或端口不同就不是同一网站。
        assertThat(sameSite(upload(yaml("oa_other", "1.0.0", "http://127.0.0.1:9", "https://oa.corp.com/"))))
                .hasSize(2);
        assertThat(sameSite(upload(yaml("oa_plain", "1.0.0", "http://oa.corp.com", null)))).isEmpty();
        assertThat(sameSite(upload(yaml("oa_port", "1.0.0", "https://oa.corp.com:8443", null)))).isEmpty();
    }

    @Test
    void manySameSitePackagesAreListedUpToALimit() throws Exception {
        // 警告随版本存进一列 TEXT：同网站的包再多，也只逐个写前 5 个，其余合成一条。
        for (int index = 1; index <= 7; index++) {
            upload(yaml("crm_" + index, "1.0.0", "https://crm.corp.com", null));
            publish("crm_" + index, "1.0.0");
        }
        List<JsonNode> hits = sameSite(upload(yaml("crm_new", "1.0.0", "https://crm.corp.com", null)));
        assertThat(hits).hasSize(6);
        for (int index = 1; index <= 5; index++) {
            assertThat(hits.get(index - 1).path("message").asText()).contains("crm_" + index + "（");
        }
        assertThat(hits.get(5).path("message").asText())
                .contains("另有 2 个")
                .doesNotContain("crm_6")
                .doesNotContain("crm_7");
    }

    @Test
    void longTitlesAreCutShortInTheWarning() throws Exception {
        // 标题没有长度上限，几条警告加起来不能撑破 validation_json 这一列。
        upload(yaml("crm_long", "1.0.0", "https://crm.corp.com", null)
                .replaceFirst("(?m)^title: .*$", "title: " + "长".repeat(30_000)));
        publish("crm_long", "1.0.0");
        List<JsonNode> hits = sameSite(upload(yaml("crm_new", "1.0.0", "https://crm.corp.com", null)));
        assertThat(hits).hasSize(1);
        String message = hits.get(0).path("message").asText();
        assertThat(message).contains("crm_long（" + "长".repeat(60) + "…");
        assertThat(message.length()).isLessThan(400);
    }

    private static List<JsonNode> sameSite(JsonNode data) {
        ArrayList<JsonNode> hits = new ArrayList<>();
        for (JsonNode warning : data.path("warnings")) {
            if ("SAME_SITE".equals(warning.path("code").asText())) {
                hits.add(warning);
            }
        }
        return hits;
    }

    /**
     * 两位作者各写一个应用的包，都经同一个认证中心登录；第三位作者写的是认证中心（门户）自己的包。
     * 上传时写出认证地址，并提示已发布的包里谁也经它登录。都只是警告。
     */
    @Test
    void uploadShowsAuthOriginsAndPackagesBehindTheSameOne() throws Exception {
        JsonNode first = upload(ssoYaml("oa_sso", "1.0.0", "https://oa.corp.com", "https://sso.corp.com", null));
        assertThat(coded(first, "AUTH_ORIGIN_ADDED")).hasSize(1);
        assertThat(coded(first, "AUTH_ORIGIN_ADDED").get(0).path("message").asText())
                .contains("https://sso.corp.com:443")
                .contains("账号密码会提交到那里")
                .doesNotContain("当前发布的版本没有");
        assertThat(coded(first, "SAME_AUTH")).isEmpty();
        publish("oa_sso", "1.0.0");

        // 另一个应用，经同一个认证中心：不是同一网站，但提示共用认证地址。
        JsonNode second = upload(ssoYaml("erp_sso", "1.0.0", "https://erp.corp.com", "HTTPS://SSO.corp.com:443", null));
        assertThat(sameSite(second)).isEmpty();
        List<JsonNode> shared = coded(second, "SAME_AUTH");
        assertThat(shared).hasSize(1);
        assertThat(shared.get(0).path("path").asText()).isEqualTo("auth.origins");
        assertThat(shared.get(0).path("message").asText())
                .contains("oa_sso")
                .contains("https://sso.corp.com:443")
                .contains("出现登录页才提交账号密码");
        publish("erp_sso", "1.0.0");

        // 认证中心自己的包：它的 base_url 就是别人的认证地址。
        JsonNode portal = upload(yaml("sso_portal", "1.0.0", "https://sso.corp.com", null));
        assertThat(sameSite(portal)).isEmpty();
        assertThat(coded(portal, "SAME_AUTH")).extracting(node -> node.path("message").asText())
                .hasSize(2)
                .anySatisfy(text -> assertThat(text).contains("erp_sso"))
                .anySatisfy(text -> assertThat(text).contains("oa_sso"));
        assertThat(coded(portal, "AUTH_ORIGIN_ADDED")).isEmpty();

        // 认证地址引用配置项：默认值算包里写的地址；没有默认值时 Reef 看不到，只写出配置项名。
        JsonNode configured = upload(ssoYaml(
                "hr_sso", "1.0.0", "https://hr.corp.com", "{{config.sso_url}}", "https://sso.corp.com/"));
        assertThat(coded(configured, "SAME_AUTH")).hasSize(2);
        assertThat(coded(configured, "AUTH_ORIGIN_ADDED").get(0).path("message").asText())
                .contains("配置项 sso_url 填的地址");
        JsonNode blank = upload(ssoYaml("crm_sso", "1.0.0", "https://crm.corp.com", "{{config.sso_url}}", ""));
        assertThat(coded(blank, "SAME_AUTH")).isEmpty();

        // 新版本没有新增认证地址：不再提示新增。换了认证地址：提示，并写明当前发布的版本没有。
        assertThat(coded(upload(ssoYaml("oa_sso", "1.1.0", "https://oa.corp.com", "https://sso.corp.com:443", null)),
                "AUTH_ORIGIN_ADDED")).isEmpty();
        JsonNode moved = upload(ssoYaml("oa_sso", "1.2.0", "https://oa.corp.com", "https://login.corp.com", null));
        assertThat(coded(moved, "AUTH_ORIGIN_ADDED").get(0).path("message").asText())
                .contains("https://login.corp.com:443")
                .contains("当前发布的版本没有");
        assertThat(coded(moved, "SAME_AUTH")).isEmpty();

        // 详情里写出包里的认证地址。
        MvcResult detail = mockMvc.perform(get("/api/admin/conches/erp_sso")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(detail.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.path("authOrigins")).hasSize(1);
        assertThat(data.path("authOrigins").get(0).asText()).isEqualTo("HTTPS://SSO.corp.com:443");
    }

    private static List<JsonNode> coded(JsonNode uploaded, String code) {
        List<JsonNode> hits = new java.util.ArrayList<>();
        for (JsonNode warning : uploaded.path("warnings")) {
            if (code.equals(warning.path("code").asText())) {
                hits.add(warning);
            }
        }
        return hits;
    }

    private static String ssoYaml(String conch, String version, String baseUrl, String origin, String configDefault) {
        String config = configDefault == null
                ? ""
                : configDefault.isEmpty()
                        ? """
                          config:
                            sso_url: { type: url, label: 认证中心, required: false }
                          """
                        : """
                          config:
                            sso_url: { type: url, label: 认证中心, required: false, default: "%s" }
                          """.formatted(configDefault);
        String login = origin.contains("config.") ? "{{config.sso_url}}/login" : origin + "/login";
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.11.0
                base_url: "%s"
                title: 经认证中心登录
                description: 认证地址上传提示测试。
                owner: 平台组
                credentials:
                  password: { type: secret, label: 密码, required: true }
                %sauth:
                  origins: ["%s"]
                  steps:
                    - request: { method: GET, path: /, follow_redirects: true }
                    - if:
                        contains: 'name="password"'
                        then:
                          - request:
                              method: POST
                              url: "%s"
                              form: { password: "{{credential.password}}" }
                              follow_redirects: true
                actions:
                  get_me:
                    title: 当前用户
                    description: 返回常量。
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
                    notes: 无
                    related: []
                """.formatted(conch, version, baseUrl, config, origin, login);
    }

    private void publish(String conch, String version) throws Exception {
        mockMvc.perform(post("/api/admin/conches/" + conch + "/versions/" + version + "/publish")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private JsonNode upload(String yaml) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "conch.yaml", "application/yaml", yaml.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/admin/conches")
                        .file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private static String yaml(String conch, String version, String baseUrl, String configDefault) {
        String config = configDefault == null
                ? ""
                : """
                  config:
                    base_url: { type: url, label: 地址, required: false, default: "%s" }
                  """.formatted(configDefault);
        return """
                conch: %s
                version: %s
                driver: http
                min_crab_version: 1.0.0
                base_url: "%s"
                title: 协同办公
                description: 同网站上传提示测试。
                owner: 平台组
                credentials: {}
                %sactions:
                  get_me:
                    title: 当前用户
                    description: 返回常量。
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
                    notes: 无
                    related: []
                """.formatted(conch, version, baseUrl, config);
    }
}
