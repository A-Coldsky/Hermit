package dev.hermit.reef.ui;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class AdminUiTest {

    private static final String[] PAGES = {
            "/",
            "/login",
            "/shells",
            "/shells/upload",
            "/shells/legacy_oa",
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

    @Autowired
    MockMvc mockMvc;

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
    void apiAndUnknownPathsStayRejected() throws Exception {
        mockMvc.perform(get("/api/admin/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mockMvc.perform(post("/"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/assets/app.js"))
                .andExpect(status().isUnauthorized());
    }
}
