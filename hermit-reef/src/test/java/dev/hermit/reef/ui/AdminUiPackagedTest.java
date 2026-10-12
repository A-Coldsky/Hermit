package dev.hermit.reef.ui;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class AdminUiPackagedTest {

    private static final Pattern ASSET = Pattern.compile("/assets/([A-Za-z0-9._-]+)");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("admin-ui-packaged").toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void packagedIndexAndAssetUseTheClasspathRoot() throws Exception {
        assumeTrue(new ClassPathResource("hermit-web/reef-admin/index.html").exists(),
                "frontend resources are not in this build");
        MvcResult page = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, AdminUiController.HTML))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, AdminUiController.PAGE_CACHE))
                .andExpect(content().string(containsString("<div id=\"app\">")))
                .andReturn();
        Matcher matcher = ASSET.matcher(page.getResponse().getContentAsString());
        assumeTrue(matcher.find(), "built index has no hashed asset");
        mockMvc.perform(get("/assets/" + matcher.group(1)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, AdminUiController.ASSET_CACHE));
    }
}
