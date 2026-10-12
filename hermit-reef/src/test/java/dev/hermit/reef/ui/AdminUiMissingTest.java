package dev.hermit.reef.ui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class AdminUiMissingTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("admin-ui-missing").toString());
        registry.add("hermit.web.reef-root", () -> "classpath:/ui-missing/");
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void missingIndexIsJsonNotFound() throws Exception {
        for (String path : new String[] {"/", "/login", "/shells/legacy_oa", "/tide"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
    }
}
