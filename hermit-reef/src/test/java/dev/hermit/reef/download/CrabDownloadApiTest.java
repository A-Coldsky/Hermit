package dev.hermit.reef.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class CrabDownloadApiTest {

    private static final Path DATA = TestDataDirs.create("reef-crab-download");
    private static final String USER_NAME = "bob";
    private static final String USER_PASSWORD = "BobPassword-1234";

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

    @Autowired
    ReefProperties properties;

    private String userToken;

    @BeforeEach
    void reset() throws Exception {
        properties.setCrabJarFile("");
        properties.setCrabDownloadUrl("");
        properties.setLatestCrabVersion("1.12.0");
        cleanDownloads();
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
        userToken = login(USER_NAME, USER_PASSWORD);
    }

    @Test
    void noneThenExternalFallback() throws Exception {
        mockMvc.perform(get("/api/admin/crab/download-info")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.source").value("NONE"))
                .andExpect(jsonPath("$.data.latestCrabVersion").value("1.12.0"));

        mockMvc.perform(get("/api/admin/crab/download")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isNotFound());

        properties.setCrabDownloadUrl("https://files.example.internal/hermit-crab-all.jar");
        mockMvc.perform(get("/api/admin/crab/download-info")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.source").value("EXTERNAL"))
                .andExpect(jsonPath("$.data.externalUrl").value("https://files.example.internal/hermit-crab-all.jar"));
    }

    @Test
    void localJarTakesPrecedenceStreamsBytesAndNeverLeaksDataDir() throws Exception {
        Path downloads = Files.createDirectories(DATA.resolve("downloads"));
        byte[] otherBytes = "not-crab".getBytes(StandardCharsets.UTF_8);
        Path otherJar = downloads.resolve("other-tool.jar");
        Files.write(otherJar, otherBytes);
        Files.setLastModifiedTime(otherJar, FileTime.from(Instant.parse("2026-11-20T12:00:00Z")));

        byte[] crabBytes = "PK\u0003\u0004fake-crab-fat-jar-content".getBytes(StandardCharsets.UTF_8);
        Path crabJar = downloads.resolve("hermit-crab-0.1.0-all.jar");
        Files.write(crabJar, crabBytes);
        Files.setLastModifiedTime(crabJar, FileTime.from(Instant.parse("2026-11-19T12:00:00Z")));

        String expectedSha = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(crabBytes));

        MvcResult infoResult = mockMvc.perform(get("/api/admin/crab/download-info")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.source").value("LOCAL"))
                .andExpect(jsonPath("$.data.fileName").value("hermit-crab-0.1.0-all.jar"))
                .andExpect(jsonPath("$.data.sizeBytes").value(crabBytes.length))
                .andExpect(jsonPath("$.data.sha256").value(expectedSha))
                .andExpect(jsonPath("$.data.downloadUrl").value("/api/admin/crab/download"))
                .andReturn();
        assertThat(infoResult.getResponse().getContentAsString()).doesNotContain(DATA.toString());

        MvcResult downloadResult = mockMvc.perform(get("/api/admin/crab/download")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"hermit-crab-0.1.0-all.jar\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        assertThat(downloadResult.getResponse().getContentAsByteArray()).isEqualTo(crabBytes);
    }

    @Test
    void rejectsSymlinksAndPathTraversalPinnedFile() throws Exception {
        Path downloads = Files.createDirectories(DATA.resolve("downloads"));
        Path outside = DATA.resolve("outside-secret.jar");
        Files.writeString(outside, "secret-data");
        Files.createSymbolicLink(downloads.resolve("hermit-crab-link.jar"), outside);

        mockMvc.perform(get("/api/admin/crab/download-info")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.source").value("NONE"));

        properties.setCrabJarFile("../outside-secret.jar");
        mockMvc.perform(get("/api/admin/crab/download")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    private void cleanDownloads() throws IOException {
        Path downloads = DATA.resolve("downloads");
        if (Files.exists(downloads)) {
            try (Stream<Path> walk = Files.walk(downloads)) {
                for (Path p : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.deleteIfExists(DATA.resolve("outside-secret.jar"));
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
