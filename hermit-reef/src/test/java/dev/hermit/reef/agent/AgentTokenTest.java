package dev.hermit.reef.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.hermit.reef.security.AgentAccessFilter;
import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import java.net.URI;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "hermit.reef.agent-token=" + AgentTokenTest.TOKEN,
        "hermit.reef.latest-crab-version=1.2.3",
        "hermit.reef.crab-download-url=https://example.invalid/crab.jar"
})
@AutoConfigureMockMvc
@Import(ReefTestConfig.class)
class AgentTokenTest {

    static final String TOKEN = "agent-token-test-value";
    private static final String INSTANCE = "123e4567-e89b-12d3-a456-426614174000";

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("agent-token").toString());
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void missingOrInvalidInstanceIs400BeforeTheTokenIsConsidered() throws Exception {
        mockMvc.perform(get("/agent/version").header(AgentAccessFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        mockMvc.perform(get("/agent/version").header(AgentAccessFilter.INSTANCE_HEADER, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/agent/version").header(AgentAccessFilter.INSTANCE_HEADER, "1-1-1-1-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongAndMissingTokensShareOneUnauthorizedBody() throws Exception {
        MvcResult missing = version(null, "trace-agent");
        MvcResult wrong = version("other-token", "trace-agent");
        assertThat(missing.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void obfuscatedAgentPathsCannotSkipTheAccessCheck() throws Exception {
        assertNotOk(URI.create("http://localhost//agent/version"));
        assertNotOk(URI.create("http://localhost/agent;x=1/version"));
        assertNotOk(URI.create("http://localhost/agent/%2e%2e/api/admin/me"));
    }

    @Test
    void unknownAgentPathStaysJsonAfterTheTokenCheck() throws Exception {
        mockMvc.perform(get("/agent/no-such")
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .header(AgentAccessFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("<div id=\"app\">"))));
        mockMvc.perform(get("/agent")
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                        .header(AgentAccessFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(content().string(not(containsString("<div id=\"app\">"))));
    }

    @Test
    void matchingTokenReturnsTheConfiguredVersion() throws Exception {
        mockMvc.perform(get("/agent/version")
                        .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE.toUpperCase(Locale.ROOT))
                        .header(AgentAccessFilter.TOKEN_HEADER, TOKEN)
                        .header("X-Trace-Id", "trace-agent-ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value("trace-agent-ok"))
                .andExpect(jsonPath("$.data.latestVersion").value("1.2.3"))
                .andExpect(jsonPath("$.data.downloadUrl").value("https://example.invalid/crab.jar"));
    }

    private void assertNotOk(URI uri) throws Exception {
        int withoutCredentials = mockMvc.perform(get(uri)).andReturn().getResponse().getStatus();
        int tokenWithoutInstance = mockMvc.perform(get(uri).header(AgentAccessFilter.TOKEN_HEADER, TOKEN))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(withoutCredentials).isIn(400, 401, 403);
        assertThat(tokenWithoutInstance).isIn(400, 401, 403);
    }

    private MvcResult version(String token, String traceId) throws Exception {
        var request = get("/agent/version")
                .header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE)
                .header("X-Trace-Id", traceId);
        if (token != null) {
            request.header(AgentAccessFilter.TOKEN_HEADER, token);
        }
        return mockMvc.perform(request).andReturn();
    }
}
