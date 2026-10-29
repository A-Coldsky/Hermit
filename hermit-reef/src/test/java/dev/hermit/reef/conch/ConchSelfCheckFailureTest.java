package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.pkg.PackageReason;
import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.reef.testsupport.MemoryLogAppender;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.sql.Timestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({ReefTestConfig.class, ConchSelfCheckFailureTest.FailingVerifier.class})
class ConchSelfCheckFailureTest {

    static final String MARKER = "SELF_CHECK_SHOULD_NOT_LEAK";
    private static final Path DATA = TestDataDirs.create("conch-self-check");

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

    private String token;
    private String privatePem;

    @BeforeEach
    void reset() throws Exception {
        privatePem = Files.readString(DATA.resolve("keys/signing.key"));
        jdbc.update("DELETE FROM hermit_conch_versions");
        jdbc.update("DELETE FROM hermit_audit_log");
        jdbc.update("DELETE FROM hermit_admin_users");
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
                TestSecrets.USERNAME,
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(ReefTestConfig.START));
        token = objectMapper.readTree(mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + TestSecrets.USERNAME + "\",\"password\":\"" + TestSecrets.PASSWORD
                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()).get("data").get("token").asText();
    }

    @AfterEach
    void secretsStayOutOfLogs() {
        for (String line : MemoryLogAppender.LINES) {
            if (line.contains(MARKER) || line.contains("BEGIN PRIVATE KEY")) {
                throw new AssertionError("self-check or private key leaked into logs");
            }
            for (String pemLine : privatePem.split("\\R")) {
                String trimmed = pemLine.trim();
                if (!trimmed.startsWith("-----") && trimmed.length() >= 16 && line.contains(trimmed)) {
                    throw new AssertionError("private key material appeared in logs");
                }
            }
        }
    }

    @Test
    void failedSelfCheckRollsBackThePublishAndKeepsTheResponseGeneric() throws Exception {
        byte[] sample = sample();
        mockMvc.perform(multipart("/api/admin/conches")
                        .file(new MockMultipartFile("file", "conch.yaml", "application/octet-stream", sample))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/api/admin/conches/legacy_oa/versions/1.0.0/publish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL"))
                .andExpect(jsonPath("$.error.message").value("internal error"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(MARKER);
        assertThat(body).doesNotContain("BEGIN PRIVATE KEY");
        assertThat(jdbc.queryForObject("SELECT status FROM hermit_conch_versions WHERE conch = 'legacy_oa'", String.class))
                .isEqualTo("UPLOADED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_conch_versions WHERE is_current = TRUE", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_audit_log WHERE action = 'PUBLISH'", Integer.class)).isZero();
        assertThat(Files.exists(DATA.resolve("conches/legacy_oa/1.0.0/conch.yaml"))).isTrue();
        assertThat(Files.exists(DATA.resolve("conches/legacy_oa/1.0.0/package.zip"))).isFalse();
    }

    @TestConfiguration
    static class FailingVerifier {

        @Bean
        @Primary
        PackageVerifier packageVerifier() {
            return (byte[] zip, PublicKey key) -> {
                throw new PackageVerificationException(PackageReason.BAD_SIGNATURE, MARKER);
            };
        }
    }

    private static byte[] sample() throws IOException {
        try (InputStream in = ConchSelfCheckFailureTest.class.getClassLoader()
                .getResourceAsStream("samples/legacy_oa.yaml")) {
            if (in == null) {
                throw new IllegalStateException("missing samples/legacy_oa.yaml");
            }
            return in.readAllBytes();
        }
    }
}
