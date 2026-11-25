package dev.hermit.reef.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
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

    @Autowired
    CrabDownloadService downloadService;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void reset() throws Exception {
        properties.setCrabJarFile("");
        properties.setCrabDownloadUrl("");
        properties.setLatestCrabVersion("1.12.0");
        downloadService.setBundledSourceForTest(Optional::empty);
        cleanDownloads();
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
    void noneThenExternalFallback() throws Exception {
        mockMvc.perform(get("/api/admin/crab/download-info")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.source").value("NONE"))
                .andExpect(jsonPath("$.data.bundledAvailable").value(false))
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
    void bundledDefaultWorksOutOfTheBoxWithoutDownloadsDirectory() throws Exception {
        byte[] bundledBytes = fakeCrabFatJar("bundled-v0");
        String bundledSha = sha256(bundledBytes);
        Instant builtAt = Instant.parse("2026-11-24T10:00:00Z");
        downloadService.setBundledSourceForTest(() -> Optional.of(new CrabDownloadService.BundledDescriptor(
                new ByteArrayResource(bundledBytes),
                "hermit-crab-0.1.0-SNAPSHOT-all.jar",
                builtAt)));

        mockMvc.perform(get("/api/admin/crab/download-info")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.source").value("BUNDLED"))
                .andExpect(jsonPath("$.data.fileName").value("hermit-crab-0.1.0-SNAPSHOT-all.jar"))
                .andExpect(jsonPath("$.data.sizeBytes").value(bundledBytes.length))
                .andExpect(jsonPath("$.data.sha256").value(bundledSha))
                .andExpect(jsonPath("$.data.bundledAvailable").value(true))
                .andExpect(jsonPath("$.data.bundledSha256").value(bundledSha))
                .andExpect(jsonPath("$.data.downloadUrl").value("/api/admin/crab/download"));

        MvcResult dl = mockMvc.perform(get("/api/admin/crab/download")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"hermit-crab-0.1.0-SNAPSHOT-all.jar\""))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(bundledBytes.length)))
                .andReturn();
        assertThat(dl.getResponse().getContentAsByteArray()).isEqualTo(bundledBytes);
    }

    @Test
    void adminUploadKeepsOnlyCurrentAndPreviousSupportsRollbackAndReset() throws Exception {
        byte[] bundledBytes = fakeCrabFatJar("bundled-default");
        String bundledSha = sha256(bundledBytes);
        downloadService.setBundledSourceForTest(() -> Optional.of(new CrabDownloadService.BundledDescriptor(
                new ByteArrayResource(bundledBytes),
                "hermit-crab-0.1.0-SNAPSHOT-all.jar",
                Instant.parse("2026-11-24T08:00:00Z"))));

        byte[] v1 = fakeCrabFatJar("upload-v1");
        byte[] v2 = fakeCrabFatJar("upload-v2");
        byte[] v3 = fakeCrabFatJar("upload-v3");
        String sha1 = sha256(v1);
        String sha2 = sha256(v2);
        String sha3 = sha256(v3);

        // 普通用户不能上传、回退或重置
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-0.1.0-SNAPSHOT-all.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(v1))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/crab/rollback")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/crab/override")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // 第 1 次上传：覆盖内置版本，previousUpload 为空
        MvcResult up1 = mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-0.1.0-SNAPSHOT-all.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(v1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("UPLOADED"))
                .andExpect(jsonPath("$.data.sha256").value(sha1))
                .andExpect(jsonPath("$.data.uploadedBy").value(TestSecrets.USERNAME))
                .andExpect(jsonPath("$.data.previousUpload").isEmpty())
                .andExpect(jsonPath("$.data.bundledAvailable").value(true))
                .andReturn();
        assertThat(up1.getResponse().getContentAsString()).doesNotContain(DATA.toString());
        assertThat(listManagedJars()).hasSize(1);

        // 重复上传完全相同的当前包返回 409，不破坏历史槽位
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-0.1.0-SNAPSHOT-all.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(v1))
                .andExpect(status().isConflict());

        // 第 2 次上传（同名文件）：当前=v2，上一版=v1，磁盘保留 2 个文件
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-0.1.0-SNAPSHOT-all.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("UPLOADED"))
                .andExpect(jsonPath("$.data.sha256").value(sha2))
                .andExpect(jsonPath("$.data.previousUpload.sha256").value(sha1));
        assertThat(listManagedJars()).hasSize(2);

        // 第 3 次上传：当前=v3，上一版=v2，v1 被自动清理，磁盘仍只保留 2 个文件
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-1.13.0-all.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(v3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("UPLOADED"))
                .andExpect(jsonPath("$.data.fileName").value("hermit-crab-1.13.0-all.jar"))
                .andExpect(jsonPath("$.data.sha256").value(sha3))
                .andExpect(jsonPath("$.data.previousUpload.fileName").value("hermit-crab-0.1.0-SNAPSHOT-all.jar"))
                .andExpect(jsonPath("$.data.previousUpload.sha256").value(sha2));
        assertThat(listManagedJars()).hasSize(2);

        // 普通用户下载到最新 v3
        MvcResult dl3 = mockMvc.perform(get("/api/admin/crab/download")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"hermit-crab-1.13.0-all.jar\""))
                .andReturn();
        assertThat(dl3.getResponse().getContentAsByteArray()).isEqualTo(v3);

        // 回退到上一版本：当前变为 v2，上一版变为 v3，磁盘仍为 2 个文件
        mockMvc.perform(post("/api/admin/crab/rollback")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("UPLOADED"))
                .andExpect(jsonPath("$.data.sha256").value(sha2))
                .andExpect(jsonPath("$.data.previousUpload.sha256").value(sha3));
        assertThat(listManagedJars()).hasSize(2);

        MvcResult dl2 = mockMvc.perform(get("/api/admin/crab/download")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(dl2.getResponse().getContentAsByteArray()).isEqualTo(v2);

        // 恢复内置默认版本：清理全部已上传文件，立刻切回 BUNDLED
        mockMvc.perform(delete("/api/admin/crab/override")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("BUNDLED"))
                .andExpect(jsonPath("$.data.sha256").value(bundledSha))
                .andExpect(jsonPath("$.data.previousUpload").isEmpty());
        assertThat(listManagedJars()).isEmpty();

        MvcResult dlBundled = mockMvc.perform(get("/api/admin/crab/download")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(dlBundled.getResponse().getContentAsByteArray()).isEqualTo(bundledBytes);

        // 审计日志完整记录三种操作
        List<String> actions = jdbc.queryForList("SELECT action FROM hermit_audit_log ORDER BY id", String.class);
        assertThat(actions).contains("CRAB_JAR_UPLOADED", "CRAB_JAR_ROLLED_BACK", "CRAB_JAR_RESET");
    }

    @Test
    void uploadRejectsInvalidThinNonCrabAndPinnedConfig() throws Exception {
        // 空文件
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-all.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[0]))
                .andExpect(status().isBadRequest());

        // 非法文件名
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "not-a-jar.zip")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(fakeCrabFatJar("x")))
                .andExpect(status().isBadRequest());

        // 非 ZIP 内容
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-all.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("plain text".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());

        // Main-Class 不是 CrabMain（如误传 reef.jar）
        byte[] wrongMain = buildJar("dev.hermit.reef.ReefApplication", true, true, "reef");
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-reef.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(wrongMain))
                .andExpect(status().isBadRequest());

        // 普通轻量 jar（缺少 fat jar 依赖标志）
        byte[] thinJar = buildJar(CrabDownloadService.CRAB_MAIN_CLASS, true, false, "thin");
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-0.1.0-SNAPSHOT.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(thinJar))
                .andExpect(status().isBadRequest());

        // 超过 64 MB 的声明长度立即拒绝
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-huge-all.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(CrabDownloadService.MAX_UPLOAD_BYTES + 1))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isPayloadTooLarge());

        // 未声明 Content-Length（chunked）但实际流超过 64 MB 同样拒绝并清理临时文件
        java.io.InputStream oversizedStream = new java.io.InputStream() {
            private long remaining = CrabDownloadService.MAX_UPLOAD_BYTES + 1024;

            @Override
            public int read() {
                if (remaining <= 0) {
                    return -1;
                }
                remaining--;
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                int chunk = (int) Math.min(len, remaining);
                remaining -= chunk;
                return chunk;
            }
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> downloadService.upload(
                        "hermit-crab-stream-huge-all.jar", -1, oversizedStream, TestSecrets.USERNAME))
                .isInstanceOf(dev.hermit.reef.web.ApiException.class)
                .extracting(ex -> ((dev.hermit.reef.web.ApiException) ex).status())
                .isEqualTo(413);
        assertThat(listManagedJars()).isEmpty();
        Path managedDir = DATA.resolve("downloads").resolve("managed");
        if (Files.isDirectory(managedDir)) {
            try (Stream<Path> left = Files.list(managedDir)) {
                assertThat(left.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList()).isEmpty();
            }
        }

        // 没有已上传版本时不能回退也不能重置
        mockMvc.perform(post("/api/admin/crab/rollback")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/admin/crab/override")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        // 配置了 hermit.reef.crab-jar-file 时拒绝页面上传
        properties.setCrabJarFile("pinned.jar");
        mockMvc.perform(post("/api/admin/crab/upload")
                        .param("name", "hermit-crab-all.jar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(fakeCrabFatJar("pinned-conflict")))
                .andExpect(status().isConflict());
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

        String expectedSha = sha256(crabBytes);

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

    private static byte[] fakeCrabFatJar(String marker) throws IOException {
        return buildJar(CrabDownloadService.CRAB_MAIN_CLASS, true, true, marker);
    }

    private static byte[] buildJar(String mainClass, boolean includeMainClass, boolean includeFatMarker, String marker)
            throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(out, manifest)) {
            if (includeMainClass) {
                jos.putNextEntry(new JarEntry(CrabDownloadService.CRAB_MAIN_ENTRY));
                jos.write(("main:" + marker).getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            if (includeFatMarker) {
                jos.putNextEntry(new JarEntry(CrabDownloadService.FAT_JAR_MARKER_ENTRY));
                jos.write(("javalin:" + marker).getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private List<Path> listManagedJars() throws IOException {
        Path managed = DATA.resolve("downloads").resolve("managed");
        if (!Files.isDirectory(managed)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(managed)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".jar")).toList();
        }
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
