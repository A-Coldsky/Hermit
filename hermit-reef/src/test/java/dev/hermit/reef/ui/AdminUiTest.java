package dev.hermit.reef.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.admin.LoginRequest;
import dev.hermit.reef.security.AgentAccessFilter;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import dev.hermit.reef.testsupport.TestSecrets;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
class AdminUiTest {

    private static final String[] PAGES = {
            "/",
            "/login",
            "/conches",
            "/conches/upload",
            "/conches/legacy_oa",
            "/tide",
            "/alerts",
            "/audit",
            "/settings"
    };

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("admin-ui").toString());
        registry.add("hermit.web.reef-root", () -> "classpath:/ui-fixture/");
    }

    private static final String INSTANCE = "123e4567-e89b-12d3-a456-426614174000";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void spaPagesAreAnonymousHtml() throws Exception {
        for (String path : PAGES) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, AdminUiController.HTML))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, AdminUiController.PAGE_CACHE))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(content().string(containsString("<div id=\"app\">")));
        }
    }

    @Test
    void assetsAreCachedAndMissingAssetsAreJson() throws Exception {
        mockMvc.perform(get("/assets/app.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/javascript"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, AdminUiController.ASSET_CACHE))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(content().string(containsString("ui-fixture")));
        mockMvc.perform(get("/assets/missing.js"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mockMvc.perform(get("/assets/foo..bar.js"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void unknownPagesAreTheSpaAndApisStayJson() throws Exception {
        String shell = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        for (String path : new String[] {"/whatever", "/nope", "/nope/nested", "/favicon.ico", "/h2-console", "/shellfish"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, AdminUiController.HTML))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, AdminUiController.PAGE_CACHE))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                    .andExpect(content().string(shell))
                    .andExpect(content().string(not(containsString(TestSecrets.PASSWORD))));
        }
        for (String path : new String[] {"/shells", "/shells/", "/shells/legacy_oa", "/shells/a/b"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isFound())
                    .andExpect(header().string(HttpHeaders.LOCATION, "/conches"))
                    .andExpect(content().string(not(containsString("<div id=\"app\">"))));
        }
        mockMvc.perform(get("/shells").queryParam("next", "https://evil.example/phish"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/conches"))
                .andExpect(content().string(not(containsString("evil.example"))));

        mockMvc.perform(get("/api/admin/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(content().string(not(containsString("<div id=\"app\">"))));
        mockMvc.perform(get("/agent/no-such").header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(content().string(not(containsString("<div id=\"app\">"))));
        mockMvc.perform(post("/"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/shells"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/assets/app.js"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedInUnknownPageIsTheSameStaticFile() throws Exception {
        jdbc.update("DELETE FROM hermit_admin_users");
        jdbc.update(
                "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled) VALUES (?, ?, ?, FALSE)",
                TestSecrets.USERNAME,
                passwordEncoder.encode(TestSecrets.PASSWORD),
                Timestamp.from(ReefTestConfig.START));
        MvcResult login = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(TestSecrets.USERNAME, TestSecrets.PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data")
                .get("token")
                .asText();
        String anonymous = mockMvc.perform(get("/nope"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String signedIn = mockMvc.perform(get("/nope").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, AdminUiController.HTML))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(signedIn).isEqualTo(anonymous).doesNotContain(token).doesNotContain(TestSecrets.PASSWORD);
        mockMvc.perform(get("/api/admin/does-not-exist").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("<div id=\"app\">"))));
        mockMvc.perform(get("/api/other").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
