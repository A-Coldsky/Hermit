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

    private static List<JsonNode> sameSite(JsonNode data) {
        ArrayList<JsonNode> hits = new ArrayList<>();
        for (JsonNode warning : data.path("warnings")) {
            if ("SAME_SITE".equals(warning.path("code").asText())) {
                hits.add(warning);
            }
        }
        return hits;
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
