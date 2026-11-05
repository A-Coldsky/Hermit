package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.VerifiedConch;
import dev.hermit.common.validate.ConchDiff;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationCodes;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.reef.security.AgentAccessFilter;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.testsupport.MemoryLogAppender;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "hermit.reef.agent-token=" + ConchApiTest.TOKEN)
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class ConchApiTest {

    static final String TOKEN = "reef-agent-token-test";
    private static final String SAMPLE_DESCRIPTION = "公司内部审批、公告、请假系统。本规则包只提供待办查询。";
    private static final String INSTANCE = "123e4567-e89b-12d3-a456-426614174000";
    private static final Path DATA = TestDataDirs.create("conch-api");

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
    private String privatePem;

    @BeforeEach
    void reset() throws Exception {
        privatePem = Files.readString(DATA.resolve("keys/signing.key"));
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
        cleanConchFiles();
        adminToken = login();
    }

    @AfterEach
    void privateKeyStaysOutOfLogs() {
        for (String line : MemoryLogAppender.LINES) {
            assertNoPrivateKey(line);
        }
    }

    @Test
    void rejectsEveryIllegalCommonFixture() throws Exception {
        List<Path> fixtures = illegalFixtures();
        assertThat(fixtures).extracting(path -> path.getFileName().toString())
                .contains(
                        "alias_conflict.yaml",
                        "mapping_output.yaml",
                        "missing_description.yaml",
                        "plaintext_password.yaml",
                        "undeclared_credential.yaml",
                        "unknown_step.yaml");
        for (Path fixture : fixtures) {
            byte[] yaml = Files.readAllBytes(fixture);
            MvcResult result = upload(yaml);
            assertThat(result.getResponse().getStatus()).isEqualTo(422);
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.get("error").get("code").asText()).isEqualTo("INVALID_INPUT");
            try {
                ConchDoc doc = ConchParser.parse(new String(yaml, StandardCharsets.UTF_8));
                ValidationResult expected = ConchValidator.validate(doc, ConchValidator.Context.of(null));
                assertThat(expected.errors()).isNotEmpty();
                assertThat(body.get("error").get("message").asText()).isEqualTo("输入无效");
                assertCodes(body.get("data").get("errors"), expected.errors().stream().map(issue -> issue.code()).toList());
                assertCodes(body.get("data").get("warnings"), expected.warnings().stream().map(issue -> issue.code()).toList());
            } catch (ConchParseException ex) {
                assertThat(body.get("data").get("path").asText()).isEqualTo(ex.path());
                assertThat(body.get("data").get("message").asText()).isEqualTo(ex.getMessage());
                assertThat(body.get("error").get("message").asText()).isEqualTo(ex.getMessage());
            }
        }
        assertThat(versionCount()).isZero();
    }

    @Test
    void uploadsTheSampleAndRejectsDuplicatesAndLowerVersions() throws Exception {
        byte[] sample = sample();
        JsonNode uploaded = json(upload(sample));
        assertThat(uploaded.get("success").asBoolean()).isTrue();
        assertThat(uploaded.get("data").get("conch").asText()).isEqualTo("legacy_oa");
        assertThat(uploaded.get("data").get("version").asText()).isEqualTo("1.0.0");
        assertCodes(uploaded.get("data").get("warnings"), List.of(ValidationCodes.MISSING_RELATED));
        assertThat(uploaded.get("data").get("diff").size()).isZero();
        assertThat(Files.readAllBytes(DATA.resolve("conches/legacy_oa/1.0.0/conch.yaml"))).isEqualTo(sample);

        JsonNode duplicate = json(upload(sample));
        assertThat(ConchUploadService.VERSION_EXISTS).isEqualTo("该规则包版本已存在");
        assertThat(ConchUploadService.VERSION_NOT_GREATER).isEqualTo("版本必须高于已有的最高版本");
        assertThat(duplicate.get("error").get("code").asText()).isEqualTo("CONFLICT");
        assertThat(duplicate.get("error").get("message").asText()).isEqualTo("该规则包版本已存在");

        JsonNode lower = json(upload(withVersion(sample, "0.9.0")));
        assertThat(lower.get("error").get("code").asText()).isEqualTo("CONFLICT");
        assertThat(lower.get("error").get("message").asText()).isEqualTo("版本必须高于已有的最高版本");

        JsonNode prerelease = json(upload(withVersion(sample, "1.0.0-rc.1")));
        assertThat(prerelease.get("error").get("message").asText()).isEqualTo(ConchUploadService.VERSION_NOT_GREATER);

        assertThat(json(upload(withVersion(sample, "1.2.0"))).get("success").asBoolean()).isTrue();
        JsonNode between = json(upload(withVersion(sample, "1.1.0")));
        assertThat(between.get("error").get("message").asText()).isEqualTo(ConchUploadService.VERSION_NOT_GREATER);
        assertThat(versionCount()).isEqualTo(2);
    }

    @Test
    void discardsUploadedVersionsWithoutTouchingPublishedPackages() throws Exception {
        assertThat(ReefErrorCode.CONFLICT.message()).isEqualTo("状态冲突");
        assertThat(ConchDiscardService.NOT_UPLOADED).isEqualTo("只能丢弃已上传、尚未发布的版本");

        byte[] spare = withConch(sample(), "spare_oa");
        assertThat(json(upload(spare)).get("success").asBoolean()).isTrue();
        mockMvc.perform(delete(discardPath("spare_oa", "1.0.0")).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conch").value("spare_oa"))
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.error").value(nullValue()));
        mockMvc.perform(get("/api/admin/conches/spare_oa/versions").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("资源不存在"));
        mockMvc.perform(get("/api/admin/conches").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(Files.exists(versionFile("spare_oa", "1.0.0", "conch.yaml"))).isFalse();
        assertThat(json(upload(spare)).get("success").asBoolean()).isTrue();
        assertThat(discardCount("spare_oa", "1.0.0")).isEqualTo(1);

        byte[] sample = sample();
        upload(sample);
        publish("1.0.0");
        byte[] publishedYaml = Files.readAllBytes(versionFile("legacy_oa", "1.0.0", "conch.yaml"));
        byte[] publishedZip = Files.readAllBytes(versionFile("legacy_oa", "1.0.0", "package.zip"));
        String sha = storedSha("legacy_oa", "1.0.0");
        PublicKey publicKey = Ed25519Keys.readPublicPem(Files.readString(DATA.resolve("keys/signing.pub")));
        assertThat(ConchPackage.readVerified(publishedZip, publicKey).manifest().sha256()).isEqualTo(sha);

        byte[] next = withVersion(sample, "1.1.0");
        byte[] later = withVersion(sample, "1.2.0");
        assertThat(json(upload(next)).get("success").asBoolean()).isTrue();
        assertThat(json(upload(later)).get("success").asBoolean()).isTrue();
        mockMvc.perform(delete(discardPath("legacy_oa", "1.1.0")).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.1.0"));
        assertVersions("legacy_oa", "1.2.0 UPLOADED", "1.0.0 PUBLISHED");
        assertThat(Files.exists(versionFile("legacy_oa", "1.1.0", "conch.yaml"))).isFalse();
        assertThat(Files.exists(versionFile("legacy_oa", "1.1.0", "package.zip"))).isFalse();
        assertPublishedIntact(publishedYaml, publishedZip, sha, publicKey);

        mockMvc.perform(delete(discardPath("legacy_oa", "1.1.0")).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("资源不存在"));
        mockMvc.perform(delete(discardPath("legacy_oa", "nope")).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("资源不存在"));
        assertThat(json(upload(next)).get("error").get("message").asText()).isEqualTo("版本必须高于已有的最高版本");
        mockMvc.perform(delete(discardPath("legacy_oa", "1.2.0")).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
        assertThat(json(upload(later)).get("data").get("version").asText()).isEqualTo("1.2.0");
        assertThat(Files.readAllBytes(versionFile("legacy_oa", "1.2.0", "conch.yaml"))).isEqualTo(later);

        mockMvc.perform(delete(discardPath("legacy_oa", "1.0.0")).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value(ConchDiscardService.NOT_UPLOADED));
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.0.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("只能发布已上传、尚未发布的版本"));
        JsonNode duplicate = json(upload(sample));
        assertThat(duplicate.get("error").get("code").asText()).isEqualTo("CONFLICT");
        assertThat(duplicate.get("error").get("message").asText()).isEqualTo("该规则包版本已存在");
        assertThat(duplicate.get("error").get("message").asText()).isNotEqualTo("conflict");
        assertThat(duplicate.get("error").get("message").asText()).isNotEqualTo(ReefErrorCode.CONFLICT.message());
        assertPublishedIntact(publishedYaml, publishedZip, sha, publicKey);
        assertThat(discardCount("legacy_oa", "1.0.0")).isZero();
        assertThat(discardCount("legacy_oa", "1.1.0")).isEqualTo(1);
        assertDiscardAudit("legacy_oa", "1.1.0");

        mockMvc.perform(agent("/agent/conches/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conch").value("legacy_oa"))
                .andExpect(jsonPath("$.data[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[0].sha256").value(sha));
        assertThat(download("1.0.0")).isEqualTo(publishedZip);
        assertThat(storedPull("legacy_oa", "1.0.0")).isEqualTo(1);

        mockMvc.perform(post("/api/admin/conches/legacy_oa/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"合同到期\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(discardPath("legacy_oa", "1.2.0")).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.2.0"));
        mockMvc.perform(delete(discardPath("legacy_oa", "1.0.0")).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(ConchDiscardService.NOT_UPLOADED));
        assertPublishedIntact(publishedYaml, publishedZip, sha, publicKey);
        assertThat(storedPull("legacy_oa", "1.0.0")).isEqualTo(1);
        assertVersions("legacy_oa", "1.0.0 PUBLISHED");
        mockMvc.perform(agent("/agent/conches/legacy_oa/1.0.0")).andExpect(status().isNotFound());
        assertThat(Files.readAllBytes(versionFile("legacy_oa", "1.0.0", "package.zip"))).isEqualTo(publishedZip);
    }

    @Test
    void userCannotDiscardAnUploadedVersion() throws Exception {
        byte[] sample = sample();
        upload(sample);
        publish("1.0.0");
        byte[] publishedZip = Files.readAllBytes(versionFile("legacy_oa", "1.0.0", "package.zip"));
        assertThat(json(upload(withVersion(sample, "1.1.0"))).get("success").asBoolean()).isTrue();
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "reef-user",
                                "password", "UserPassword-9f3a7c",
                                "role", "USER"))))
                .andExpect(status().isOk());
        MvcResult login = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "reef-user",
                                "password", "UserPassword-9f3a7c"))))
                .andExpect(status().isOk())
                .andReturn();
        String user = objectMapper.readTree(login.getResponse().getContentAsString()).get("data").get("token").asText();

        mockMvc.perform(delete(discardPath("legacy_oa", "1.1.0")).header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("没有权限执行这个操作"));
        mockMvc.perform(delete(discardPath("legacy_oa", "1.1.0")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("未登录或登录已过期"));
        assertVersions("legacy_oa", "1.1.0 UPLOADED", "1.0.0 PUBLISHED");
        assertThat(Files.readAllBytes(versionFile("legacy_oa", "1.1.0", "conch.yaml")))
                .isEqualTo(withVersion(sample, "1.1.0"));
        assertThat(Files.readAllBytes(versionFile("legacy_oa", "1.0.0", "package.zip"))).isEqualTo(publishedZip);
        assertThat(discardCount("legacy_oa", "1.1.0")).isZero();
        assertThat(discardCount("legacy_oa", "1.0.0")).isZero();
    }

    @Test
    void listsUploadedConchesAndPublishesRollbackAndDistribution() throws Exception {
        byte[] sample = sample();
        upload(sample);

        mockMvc.perform(get("/api/admin/conches").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conch").value("legacy_oa"))
                .andExpect(jsonPath("$.data[0].version").value(nullValue()))
                .andExpect(jsonPath("$.data[0].title").value("协同办公 OA"))
                .andExpect(jsonPath("$.data[0].owner").value("平台组"))
                .andExpect(jsonPath("$.data[0].capabilityCount").value(1))
                .andExpect(jsonPath("$.data[0].pullCount").value(0))
                .andExpect(jsonPath("$.data[0].publishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data[0].disabled").value(false))
                .andExpect(jsonPath("$.data[0].disabledAt").value(nullValue()))
                .andExpect(jsonPath("$.data[0].disabledBy").value(nullValue()))
                .andExpect(jsonPath("$.data[0].disabledReason").value(nullValue()));
        mockMvc.perform(get("/api/admin/conches/legacy_oa").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/admin/conches/legacy_oa/versions").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$.data[0].uploadedBy").value(TestSecrets.USERNAME))
                .andExpect(jsonPath("$.data[0].current").value(false))
                .andExpect(jsonPath("$.data[0].pullCount").value(0))
                .andExpect(jsonPath("$.data[0].warningCount").value(1))
                .andExpect(jsonPath("$.data[0].warnings.length()").value(1))
                .andExpect(jsonPath("$.data[0].warnings[0].code").value(ValidationCodes.MISSING_RELATED))
                .andExpect(jsonPath("$.data[0].warnings[0].path").value("actions.list_todo.related"))
                .andExpect(jsonPath("$.data[0].warnings[0].message").value("缺少 related"))
                .andExpect(jsonPath("$.data[0].publishedBy").value(nullValue()));

        MvcResult yaml = mockMvc.perform(get("/api/admin/conches/legacy_oa/versions/1.0.0/yaml")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8"))
                .andReturn();
        assertThat(yaml.getResponse().getContentAsByteArray()).isEqualTo(sample);
        assertNoPrivateKey(new String(yaml.getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1));

        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.0.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.0.0"));
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.0.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("只能发布已上传、尚未发布的版本"));

        String firstEtag = manifestEtag("1.0.0");
        byte[] firstZip = download("1.0.0");
        PublicKey publicKey = Ed25519Keys.readPublicPem(Files.readString(DATA.resolve("keys/signing.pub")));
        VerifiedConch verified = ConchPackage.readVerified(firstZip, publicKey);
        assertThat(verified.manifest().sha256()).hasSize(64);
        assertThat(verified.manifest().version()).isEqualTo("1.0.0");
        mockMvc.perform(agent("/agent/conches/manifest").header(HttpHeaders.IF_NONE_MATCH, firstEtag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, firstEtag))
                .andExpect(content().string(""));

        byte[] next = withOtp(withVersion(sample, "1.1.0"));
        MvcResult zipped = upload(zip("conch.yaml", next));
        assertThat(zipped.getResponse().getStatus()).isEqualTo(200);
        JsonNode diff = json(zipped).get("data").get("diff");
        assertThat(diff.get("addedCredentials").get(0).asText()).isEqualTo("otp");
        ConchDoc oldDoc = ConchParser.parse(new String(sample, StandardCharsets.UTF_8));
        ConchDoc newDoc = ConchParser.parse(new String(next, StandardCharsets.UTF_8));
        JsonNode expectedDiff = objectMapper.valueToTree(ConchDiff.between(oldDoc, newDoc));
        assertThat(diff).isEqualTo(expectedDiff);
        mockMvc.perform(get("/api/admin/conches/legacy_oa/diff")
                        .param("from", "1.0.0")
                        .param("to", "1.1.0")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedCredentials[0]").value("otp"));

        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.1.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
        String secondEtag = manifestEtag("1.1.0");
        assertThat(secondEtag).isNotEqualTo(firstEtag);
        mockMvc.perform(agent("/agent/conches/manifest").header(HttpHeaders.IF_NONE_MATCH, firstEtag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value("1.1.0"));

        String detail = mockMvc.perform(get("/api/admin/conches/legacy_oa")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.1.0"))
                .andExpect(jsonPath("$.data.disabled").value(false))
                .andExpect(jsonPath("$.data.actions.list_todo.effectiveMethod").value("GET"))
                .andExpect(jsonPath("$.data.credentials.password.type").value("secret"))
                .andExpect(jsonPath("$.data.credentials.password.required").value(true))
                .andExpect(jsonPath("$.data.config.base_url.default").value("http://oa.intranet.local"))
                .andExpect(jsonPath("$.data.actions.list_todo.mcp.readOnly").value(true))
                .andExpect(jsonPath("$.data.actions.list_todo.inputJsonSchema.type").value("object"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(detail).doesNotContain("\"steps\"");
        assertThat(detail).doesNotContain("login.aspx");
        assertThat(detail).doesNotContain("healthCheck");
        assertThat(detail).doesNotContain("{{credential.password");
        assertNoPrivateKey(detail);

        mockMvc.perform(post("/api/admin/conches/legacy_oa/rollback")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("不支持该请求方法"));
        assertThat(download("1.1.0")).isNotEmpty();
        assertThat(ConchPackage.readVerified(download("1.0.0"), publicKey).manifest().version()).isEqualTo("1.0.0");
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.0.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("只能发布已上传、尚未发布的版本"));

        upload(withVersion(sample, "1.2.0"));
        mockMvc.perform(agent("/agent/conches/legacy_oa/1.2.0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.2.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.1.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("只能发布已上传、尚未发布的版本"));

        Integer publishes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_audit_log WHERE action = 'PUBLISH'", Integer.class);
        assertThat(publishes).isEqualTo(3);
        Integer currents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_conch_versions WHERE is_current = TRUE", Integer.class);
        assertThat(currents).isEqualTo(1);
    }

    @Test
    void diffDefaultsToTheTwoHighestSemverVersions() throws Exception {
        byte[] sample = sample();
        assertThat(ConchCatalogService.DIFF_NEEDS_TWO_VERSIONS).isEqualTo("至少需要两个版本才能对比");
        assertThat(ConchCatalogService.DIFF_NEEDS_BOTH_ENDS).isEqualTo("版本对比需要同时提供 from 和 to");
        assertThat(upload(sample).getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/api/admin/conches/legacy_oa/diff").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("至少需要两个版本才能对比"));

        assertThat(upload(withVersion(sample, "1.2.0")).getResponse().getStatus()).isEqualTo(200);
        assertThat(upload(withOtp(withVersion(sample, "1.10.0"))).getResponse().getStatus()).isEqualTo(200);

        mockMvc.perform(get("/api/admin/conches/legacy_oa/diff")
                        .param("from", "1.0.0")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("版本对比需要同时提供 from 和 to"));
        mockMvc.perform(get("/api/admin/conches/legacy_oa/diff")
                        .param("to", "1.10.0")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("版本对比需要同时提供 from 和 to"));

        mockMvc.perform(get("/api/admin/conches/legacy_oa/diff").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedCredentials[0]").value("otp"))
                .andExpect(jsonPath("$.data.removedCredentials.length()").value(0));
    }

    @Test
    void rejectsBadZipsEmptyFilesAndOversizeUploads() throws Exception {
        MvcResult empty = upload(new byte[0]);
        assertThat(empty.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(empty).get("error").get("code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(json(empty).get("error").get("message").asText()).isEqualTo("输入无效");
        assertThat(upload(new byte[ConchPaths.MAX_UPLOAD_BYTES + 1]).getResponse().getStatus()).isEqualTo(422);
        assertThat(upload(zip("dir/conch.yaml", sample())).getResponse().getStatus()).isEqualTo(422);
        assertThat(upload(zip(new String[] {"conch.yaml", "readme.txt"}, sample(), "nope".getBytes(StandardCharsets.UTF_8)))
                .getResponse().getStatus()).isEqualTo(422);
        mockMvc.perform(multipart("/api/admin/conches").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/admin/conches/legacy_oa/diff").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("至少需要两个版本才能对比"));
        mockMvc.perform(post("/api/admin/conches/legacy_oa/rollback")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("不支持该请求方法"));
        mockMvc.perform(post("/api/admin/conches/missing/versions/1.0.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound());
        assertThat(versionCount()).isZero();
    }

    @Test
    void disablingAConchDropsItFromTheManifestUntilItIsEnabled() throws Exception {
        byte[] sample = sample();
        upload(sample);
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.0.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
        String etag = manifestEtag("1.0.0");
        byte[] zip = download("1.0.0");
        Path stored = DATA.resolve("conches/legacy_oa/1.0.0/package.zip");
        assertThat(Files.readAllBytes(stored)).isEqualTo(zip);

        mockMvc.perform(post("/api/admin/conches/missing/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"missing\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/conches/legacy_oa/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("输入无效"));
        mockMvc.perform(post("/api/admin/conches/legacy_oa/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "停".repeat(ConchAvailabilityService.MAX_REASON_LENGTH + 1) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/conches/legacy_oa/disable"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/conches/legacy_oa/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  合同到期  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conch").value("legacy_oa"))
                .andExpect(jsonPath("$.data.disabled").value(true))
                .andExpect(jsonPath("$.data.disabledBy").value(TestSecrets.USERNAME))
                .andExpect(jsonPath("$.data.disabledReason").value("合同到期"))
                .andExpect(jsonPath("$.data.disabledAt").value("2026-06-01T00:00:00Z"));
        mockMvc.perform(get("/api/admin/conches").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(jsonPath("$.data[0].disabled").value(true))
                .andExpect(jsonPath("$.data[0].disabledReason").value("合同到期"))
                .andExpect(jsonPath("$.data[0].version").value("1.0.0"));
        mockMvc.perform(get("/api/admin/conches/legacy_oa").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(jsonPath("$.data.disabled").value(true))
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.disabledBy").value(TestSecrets.USERNAME));
        String disableDetail = jdbc.queryForObject(
                "SELECT detail FROM hermit_audit_log WHERE action = 'DISABLE'", String.class);
        assertThat(objectMapper.readTree(disableDetail).get("reason").asText()).isEqualTo("合同到期");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM hermit_audit_log WHERE action = 'DISABLE'", String.class)).isEqualTo("1.0.0");

        mockMvc.perform(agent("/agent/conches/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(agent("/agent/conches/manifest").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(agent("/agent/conches/legacy_oa/1.0.0"))
                .andExpect(status().isNotFound());
        assertThat(Files.readAllBytes(stored)).isEqualTo(zip);
        PublicKey publicKey = Ed25519Keys.readPublicPem(Files.readString(DATA.resolve("keys/signing.pub")));
        assertThat(ConchPackage.readVerified(Files.readAllBytes(stored), publicKey).manifest().sha256())
                .isEqualTo(ConchPackage.readVerified(zip, publicKey).manifest().sha256());

        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.0.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("规则包已停用"));
        assertThat(json(upload(withVersion(sample, "1.1.0"))).get("success").asBoolean()).isTrue();
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.1.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("规则包已停用"));
        mockMvc.perform(post("/api/admin/conches/legacy_oa/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("规则包已经停用"));

        mockMvc.perform(post("/api/admin/conches/legacy_oa/enable")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"合同续签\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabled").value(false))
                .andExpect(jsonPath("$.data.disabledAt").value(nullValue()))
                .andExpect(jsonPath("$.data.disabledBy").value(nullValue()))
                .andExpect(jsonPath("$.data.disabledReason").value(nullValue()));
        assertThat(objectMapper.readTree(jdbc.queryForObject(
                "SELECT detail FROM hermit_audit_log WHERE action = 'ENABLE'", String.class))
                .get("reason").asText()).isEqualTo("合同续签");
        String restored = manifestEtag("1.0.0");
        assertThat(restored).isEqualTo(etag);
        assertThat(download("1.0.0")).isEqualTo(zip);
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.1.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.1.0"));
        mockMvc.perform(post("/api/admin/conches/legacy_oa/enable")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not disabled\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("规则包尚未停用"));
    }

    @Test
    void signingKeyEndpointReturnsOnlyThePublicPem() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/signing-key").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicKey").isNotEmpty())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertNoPrivateKey(body);
        String publicPem = objectMapper.readTree(body).get("data").get("publicKey").asText();
        assertThat(publicPem).isEqualTo(Files.readString(DATA.resolve("keys/signing.pub")));
        assertThat(publicPem).contains("BEGIN PUBLIC KEY");
        assertThat(Files.getPosixFilePermissions(DATA.resolve("keys/signing.key")))
                .isEqualTo(SigningKeyStore.PRIVATE_PERMS);
        mockMvc.perform(get("/api/admin/signing-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentConchRoutesUseTheInstanceAndTokenChecks() throws Exception {
        mockMvc.perform(get("/agent/conches/manifest").header(AgentAccessFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/agent/conches/manifest")
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .header(AgentAccessFilter.TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/agent/conches/legacy_oa/1.0.0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(agent("/agent/conches/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(header().exists(HttpHeaders.ETAG));
        mockMvc.perform(agent("/agent/conches/manifest").header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isNotModified());
    }

    @Test
    void publishingAVersionThatIsNotHigherConflicts() throws Exception {
        byte[] sample = sample();
        upload(sample);
        upload(withVersion(sample, "1.1.0"));
        publish("1.1.0");
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.0.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("发布版本必须高于当前已发布版本"));
        manifestEtag("1.1.0");
    }

    @Test
    void manifestEntrySerializesANullDescription() throws Exception {
        ManifestEntry entry = new ManifestEntry("legacy_oa", "1.0.0", "ab", "1.0.0", "标题", null, 0, List.of());
        JsonNode node = objectMapper.valueToTree(entry);
        assertThat(node.get("description").isNull()).isTrue();
        assertThat(node.get("title").asText()).isEqualTo("标题");
        assertThat(node.get("capabilityCount").intValue()).isZero();
        String withNull = etagOf(List.of(entry));
        String withText = etagOf(List.of(new ManifestEntry(
                entry.conch(), entry.version(), entry.sha256(), entry.minCrabVersion(),
                entry.title(), "说明", entry.capabilityCount(), List.of())));
        String renamed = etagOf(List.of(new ManifestEntry(
                entry.conch(), entry.version(), entry.sha256(), entry.minCrabVersion(),
                "另一个标题", null, entry.capabilityCount(), List.of())));
        String moreCapabilities = etagOf(List.of(new ManifestEntry(
                entry.conch(), entry.version(), entry.sha256(), entry.minCrabVersion(),
                entry.title(), null, 1, List.of("otp"))));
        assertThat(withText).isNotEqualTo(withNull);
        assertThat(renamed).isNotEqualTo(withNull);
        assertThat(moreCapabilities).isNotEqualTo(withNull);
    }

    @Test
    void pullCountTracksSuccessfulDownloadsAndManifestUsesTheCurrentVersion() throws Exception {
        byte[] sample = sample();
        upload(sample);
        mockMvc.perform(agent("/agent/conches/legacy_oa/1.0.0"))
                .andExpect(status().isNotFound());
        assertThat(storedPull("legacy_oa", "1.0.0")).isZero();
        mockMvc.perform(get("/api/admin/conches").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pullCount").value(0));
        mockMvc.perform(get("/api/admin/conches/legacy_oa/versions").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pullCount").value(0))
                .andExpect(jsonPath("$.data[0].status").value("UPLOADED"));

        publish("1.0.0");
        MvcResult firstManifest = manifest();
        String firstEtag = firstManifest.getResponse().getHeader(HttpHeaders.ETAG);
        assertManifestMetadata(firstManifest, "1.0.0", SAMPLE_DESCRIPTION, 1);
        mockMvc.perform(agent("/agent/conches/manifest").header(HttpHeaders.IF_NONE_MATCH, firstEtag))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
        assertThat(storedPull("legacy_oa", "1.0.0")).isZero();

        assertThat(download("1.0.0")).isNotEmpty();
        assertThat(download("1.0.0")).isNotEmpty();
        assertThat(storedPull("legacy_oa", "1.0.0")).isEqualTo(2);
        MvcResult afterDownloads = manifest();
        assertThat(afterDownloads.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(firstEtag);
        mockMvc.perform(agent("/agent/conches/manifest").header(HttpHeaders.IF_NONE_MATCH, firstEtag))
                .andExpect(status().isNotModified());
        assertThat(storedPull("legacy_oa", "1.0.0")).isEqualTo(2);
        assertPullCounts(2, "1.0.0");

        byte[] revised = withDescription(withVersion(sample, "1.1.0"), "只覆盖待办查询。");
        assertThat(json(upload(revised)).get("success").asBoolean()).isTrue();
        mockMvc.perform(agent("/agent/conches/legacy_oa/1.1.0"))
                .andExpect(status().isNotFound());
        assertThat(storedPull("legacy_oa", "1.1.0")).isZero();
        assertThat(storedPull("legacy_oa", "1.0.0")).isEqualTo(2);
        mockMvc.perform(get("/api/admin/conches/legacy_oa/versions").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(jsonPath("$.data[0].version").value("1.1.0"))
                .andExpect(jsonPath("$.data[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$.data[0].pullCount").value(0))
                .andExpect(jsonPath("$.data[1].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[1].pullCount").value(2));

        publish("1.1.0");
        MvcResult secondManifest = manifest();
        String secondEtag = secondManifest.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(secondEtag).isNotEqualTo(firstEtag);
        assertManifestMetadata(secondManifest, "1.1.0", "只覆盖待办查询。", 1);
        mockMvc.perform(agent("/agent/conches/manifest").header(HttpHeaders.IF_NONE_MATCH, firstEtag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].description").value("只覆盖待办查询。"));
        assertThat(download("1.1.0")).isNotEmpty();
        assertThat(storedPull("legacy_oa", "1.1.0")).isEqualTo(1);
        assertThat(storedPull("legacy_oa", "1.0.0")).isEqualTo(2);
        assertPullCounts(3, "1.1.0");
        mockMvc.perform(get("/api/admin/conches/legacy_oa/versions").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(jsonPath("$.data[0].version").value("1.1.0"))
                .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[0].pullCount").value(1))
                .andExpect(jsonPath("$.data[1].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[1].pullCount").value(2));
        MvcResult afterSecondDownload = manifest();
        assertThat(afterSecondDownload.getResponse().getHeader(HttpHeaders.ETAG)).isEqualTo(secondEtag);
        mockMvc.perform(agent("/agent/conches/manifest").header(HttpHeaders.IF_NONE_MATCH, secondEtag))
                .andExpect(status().isNotModified());
        assertThat(storedPull("legacy_oa", "1.0.0")).isEqualTo(2);
        assertThat(storedPull("legacy_oa", "1.1.0")).isEqualTo(1);

        mockMvc.perform(post("/api/admin/conches/legacy_oa/disable")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"停用\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(agent("/agent/conches/legacy_oa/1.1.0"))
                .andExpect(status().isNotFound());
        mockMvc.perform(agent("/agent/conches/legacy_oa/1.0.0"))
                .andExpect(status().isNotFound());
        mockMvc.perform(agent("/agent/conches/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(storedPull("legacy_oa", "1.0.0")).isEqualTo(2);
        assertThat(storedPull("legacy_oa", "1.1.0")).isEqualTo(1);
        mockMvc.perform(get("/api/admin/conches").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(jsonPath("$.data[0].disabled").value(true))
                .andExpect(jsonPath("$.data[0].pullCount").value(3));
        mockMvc.perform(get("/api/admin/conches/legacy_oa").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(jsonPath("$.data.version").value("1.1.0"))
                .andExpect(jsonPath("$.data.pullCount").value(3));
    }

    @Test
    void verificationFailureDoesNotIncrementPullCount() throws Exception {
        upload(sample());
        publish("1.0.0");
        Files.write(DATA.resolve("conches/legacy_oa/1.0.0/package.zip"), new byte[] {1, 2, 3, 4});
        mockMvc.perform(agent("/agent/conches/legacy_oa/1.0.0"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL"));
        assertThat(storedPull("legacy_oa", "1.0.0")).isZero();
        mockMvc.perform(agent("/agent/conches/manifest"))
                .andExpect(status().isInternalServerError());
        assertThat(storedPull("legacy_oa", "1.0.0")).isZero();
    }

    private String manifestEtag(String version) throws Exception {
        MvcResult result = mockMvc.perform(agent("/agent/conches/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conch").value("legacy_oa"))
                .andExpect(jsonPath("$.data[0].version").value(version))
                .andExpect(jsonPath("$.data[0].minCrabVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data[0].title").value("协同办公 OA"))
                .andExpect(jsonPath("$.data[0].description").value(SAMPLE_DESCRIPTION))
                .andExpect(jsonPath("$.data[0].capabilityCount").value(1))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();
        assertNoPrivateKey(result.getResponse().getContentAsString());
        String sha = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get(0).get("sha256").asText();
        PublicKey publicKey = Ed25519Keys.readPublicPem(Files.readString(DATA.resolve("keys/signing.pub")));
        assertThat(ConchPackage.readVerified(download(version), publicKey).manifest().sha256()).isEqualTo(sha);
        return result.getResponse().getHeader(HttpHeaders.ETAG);
    }

    private void publish(String version) throws Exception {
        mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/" + version + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
    }

    private MvcResult manifest() throws Exception {
        return mockMvc.perform(agent("/agent/conches/manifest"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();
    }

    private void assertManifestMetadata(MvcResult result, String version, String description, int capabilityCount)
            throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get(0).get("version").asText()).isEqualTo(version);
        assertThat(data.get(0).get("title").asText()).isEqualTo("协同办公 OA");
        assertThat(data.get(0).get("description").asText()).isEqualTo(description);
        assertThat(data.get(0).get("capabilityCount").intValue()).isEqualTo(capabilityCount);
        assertThat(data.get(0).get("requiredCredentials")).isNotNull();
        java.util.ArrayList<String> required = new java.util.ArrayList<>();
        data.get(0).get("requiredCredentials").forEach(node -> required.add(node.asText()));
        assertThat(required).containsExactly("password", "username");
        List<ManifestEntry> entries = objectMapper.convertValue(data, new TypeReference<List<ManifestEntry>>() {
        });
        assertThat(etagOf(entries)).isEqualTo(result.getResponse().getHeader(HttpHeaders.ETAG));
        ManifestEntry current = entries.get(0);
        assertThat(etagOf(List.of(shifted(current, current.title(), current.description() + "x", current.capabilityCount()))))
                .isNotEqualTo(etagOf(List.of(current)));
        assertThat(etagOf(List.of(shifted(current, current.title() + "x", current.description(), current.capabilityCount()))))
                .isNotEqualTo(etagOf(List.of(current)));
        assertThat(etagOf(List.of(shifted(current, current.title(), current.description(), current.capabilityCount() + 1))))
                .isNotEqualTo(etagOf(List.of(current)));
        assertThat(etagOf(List.of(new ManifestEntry(
                current.conch(), current.version(), current.sha256(), current.minCrabVersion(),
                current.title(), current.description(), current.capabilityCount(), List.of("otp")))))
                .isNotEqualTo(etagOf(List.of(current)));
    }

    private static ManifestEntry shifted(ManifestEntry entry, String title, String description, int capabilityCount) {
        return new ManifestEntry(
                entry.conch(), entry.version(), entry.sha256(), entry.minCrabVersion(),
                title, description, capabilityCount, entry.requiredCredentials());
    }

    private void assertPullCounts(int sum, String currentVersion) throws Exception {
        mockMvc.perform(get("/api/admin/conches").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conch").value("legacy_oa"))
                .andExpect(jsonPath("$.data[0].pullCount").value(sum));
        mockMvc.perform(get("/api/admin/conches/legacy_oa").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(currentVersion))
                .andExpect(jsonPath("$.data.pullCount").value(sum));
    }

    private long storedPull(String conch, String version) {
        Long value = jdbc.queryForObject(
                "SELECT pull_count FROM hermit_conch_versions WHERE conch = ? AND version = ?",
                Long.class,
                conch,
                version);
        return value == null ? 0 : value;
    }

    private String etagOf(List<ManifestEntry> entries) throws Exception {
        return "\"" + ConchHashes.sha256(objectMapper.writeValueAsBytes(entries)) + "\"";
    }

    private byte[] download(String version) throws Exception {
        MvcResult result = mockMvc.perform(agent("/agent/conches/legacy_oa/" + version))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"))
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        assertNoPrivateKey(new String(body, StandardCharsets.ISO_8859_1));
        assertThat(body[0]).isEqualTo((byte) 'P');
        assertThat(body[1]).isEqualTo((byte) 'K');
        return body;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder agent(String path) {
        return get(path)
                .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                .header(AgentAccessFilter.TOKEN_HEADER, TOKEN);
    }

    private MvcResult upload(byte[] bytes) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/admin/conches")
                        .file(new MockMultipartFile("file", "conch.yaml", "application/octet-stream", bytes))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andReturn();
        String text = result.getResponse().getContentAsString();
        if (!text.isEmpty()) {
            assertNoPrivateKey(text);
        }
        return result;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + TestSecrets.USERNAME + "\",\"password\":\"" + TestSecrets.PASSWORD
                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("token").asText();
    }

    private String bearer() {
        return "Bearer " + adminToken;
    }

    private int versionCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM hermit_conch_versions", Integer.class);
        return count == null ? 0 : count;
    }

    private static String discardPath(String conch, String version) {
        return "/api/admin/conches/" + conch + "/versions/" + version;
    }

    private static Path versionFile(String conch, String version, String name) {
        return DATA.resolve("conches").resolve(conch).resolve(version).resolve(name);
    }

    private void assertVersions(String conch, String... expected) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/conches/" + conch + "/versions")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<String> actual = new ArrayList<>();
        json(result).get("data").forEach(node -> actual.add(
                node.get("version").asText() + " " + node.get("status").asText()));
        assertThat(actual).containsExactly(expected);
    }

    private void assertPublishedIntact(byte[] yaml, byte[] zip, String sha, PublicKey publicKey) throws Exception {
        assertThat(Files.readAllBytes(versionFile("legacy_oa", "1.0.0", "conch.yaml"))).isEqualTo(yaml);
        assertThat(Files.readAllBytes(versionFile("legacy_oa", "1.0.0", "package.zip"))).isEqualTo(zip);
        assertThat(storedSha("legacy_oa", "1.0.0")).isEqualTo(sha);
        VerifiedConch verified = ConchPackage.readVerified(
                Files.readAllBytes(versionFile("legacy_oa", "1.0.0", "package.zip")), publicKey);
        assertThat(verified.manifest().sha256()).isEqualTo(sha);
        assertThat(verified.manifest().conch()).isEqualTo("legacy_oa");
        assertThat(verified.manifest().version()).isEqualTo("1.0.0");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM hermit_conch_versions WHERE conch = 'legacy_oa' AND version = '1.0.0'",
                String.class)).isEqualTo("PUBLISHED");
    }

    private String storedSha(String conch, String version) {
        return jdbc.queryForObject(
                "SELECT sha256 FROM hermit_conch_versions WHERE conch = ? AND version = ?",
                String.class,
                conch,
                version);
    }

    private int discardCount(String conch, String version) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_audit_log WHERE action = 'DISCARD' AND conch = ? AND version = ?",
                Integer.class,
                conch,
                version);
        return count == null ? 0 : count;
    }

    private void assertDiscardAudit(String conch, String version) {
        String actor = jdbc.queryForObject(
                "SELECT actor FROM hermit_audit_log WHERE action = 'DISCARD' AND conch = ? AND version = ?",
                String.class,
                conch,
                version);
        String detail = jdbc.queryForObject(
                "SELECT detail FROM hermit_audit_log WHERE action = 'DISCARD' AND conch = ? AND version = ?",
                String.class,
                conch,
                version);
        assertThat(actor).isEqualTo(TestSecrets.USERNAME);
        assertThat(detail).isNull();
    }

    private void assertNoPrivateKey(String text) {
        if (text.contains("BEGIN PRIVATE KEY") || text.contains("END PRIVATE KEY")) {
            throw new AssertionError("private key pem appeared");
        }
        for (String line : privatePem.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-----") || trimmed.length() < 16) {
                continue;
            }
            if (text.contains(trimmed)) {
                throw new AssertionError("private key material appeared");
            }
        }
    }

    private static void assertCodes(JsonNode array, List<String> expected) {
        List<String> actual = new ArrayList<>();
        array.forEach(node -> actual.add(node.get("code").asText()));
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private static byte[] sample() throws IOException {
        try (InputStream in = ConchApiTest.class.getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            if (in == null) {
                throw new IllegalStateException("missing samples/legacy_oa.yaml");
            }
            return in.readAllBytes();
        }
    }

    private static List<Path> illegalFixtures() throws IOException {
        try (Stream<Path> files = Files.list(fixtureDir())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .filter(path -> !path.getFileName().toString().equals("legacy_oa_valid.yaml"))
                    .sorted()
                    .toList();
        }
    }

    private static Path fixtureDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve("hermit-common/src/test/resources/conches");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("hermit-common conch fixtures not found");
    }

    private static byte[] withConch(byte[] yaml, String conch) {
        String text = new String(yaml, StandardCharsets.UTF_8);
        String updated = text.replace("\nconch: legacy_oa\n", "\nconch: " + conch + "\n");
        if (updated.equals(text)) {
            throw new IllegalStateException("conch line not found");
        }
        return updated.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withVersion(byte[] yaml, String version) {
        String text = new String(yaml, StandardCharsets.UTF_8);
        String updated = text.replace("\nversion: 1.0.0\n", "\nversion: " + version + "\n");
        if (updated.equals(text)) {
            throw new IllegalStateException("version line not found");
        }
        return updated.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withDescription(byte[] yaml, String description) {
        String text = new String(yaml, StandardCharsets.UTF_8);
        String needle = "\ndescription: " + SAMPLE_DESCRIPTION + "\n";
        String updated = text.replace(needle, "\ndescription: " + description + "\n");
        if (updated.equals(text)) {
            throw new IllegalStateException("description line not found");
        }
        return updated.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withOtp(byte[] yaml) {
        String text = new String(yaml, StandardCharsets.UTF_8);
        String needle = "\nconfig:\n";
        String updated = text.replace(
                needle,
                "\n  otp:\n    type: secret\n    label: 口令\n    required: true\n    description: 一次性口令\n\nconfig:\n");
        if (updated.equals(text)) {
            throw new IllegalStateException("config block not found");
        }
        return updated.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(String name, byte[] content) throws IOException {
        return zip(new String[] {name}, content);
    }

    private static byte[] zip(String[] names, byte[]... contents) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                zip.write(contents[i]);
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static void cleanConchFiles() {
        Path conches = DATA.resolve("conches");
        if (!Files.exists(conches)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(conches)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(conches))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
