package dev.hermit.reef.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.hermit.reef.security.AgentAccessFilter;
import dev.hermit.reef.testsupport.MemoryLogAppender;
import dev.hermit.reef.web.TraceIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

abstract class AgentRouteRejection {

    static final String INSTANCE = "123e4567-e89b-12d3-a456-426614174000";
    private static final String TRACE = "trace-agent-missing";
    private static final String ARBITRARY_TOKEN = "arbitrary-agent-token";

    @Autowired
    MockMvc mockMvc;

    @Test
    void versionManifestAndReportsRejectMissingAndArbitraryTokensTheSameWay() throws Exception {
        assertSameUnauthorized(this::version);
        assertSameUnauthorized(this::manifest);
        assertSameUnauthorized(this::reports);
    }

    @Test
    void startupWarnsThatAgentRoutesWillBeRejected() {
        assertThat(MemoryLogAppender.LINES)
                .filteredOn(line -> line.contains("All /agent/** requests will be rejected")
                        && line.contains("Set HERMIT_REEF_AGENT_TOKEN."))
                .isNotEmpty()
                .allSatisfy(line -> {
                    assertThat(line).startsWith("WARN ");
                    assertThat(line).doesNotContain(ARBITRARY_TOKEN);
                });
    }

    @Test
    void missingInstanceIsStillInvalidInput() throws Exception {
        mockMvc.perform(get("/agent/version"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/agent/version").header(AgentAccessFilter.TOKEN_HEADER, ARBITRARY_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    private void assertSameUnauthorized(RequestBuilder request) throws Exception {
        MvcResult missing = exchange(request.build(), null);
        MvcResult arbitrary = exchange(request.build(), ARBITRARY_TOKEN);
        assertThat(missing.getResponse().getStatus()).isEqualTo(401);
        assertThat(arbitrary.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"message\":\"未登录或登录已过期\"")
                .contains("\"traceId\":\"" + TRACE + "\"");
    }

    private MvcResult exchange(MockHttpServletRequestBuilder request, String token) throws Exception {
        request.header(AgentAccessFilter.INSTANCE_HEADER, INSTANCE).header(TraceIds.HEADER, TRACE);
        if (token != null) {
            request.header(AgentAccessFilter.TOKEN_HEADER, token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MockHttpServletRequestBuilder version() {
        return get("/agent/version");
    }

    private MockHttpServletRequestBuilder manifest() {
        return get("/agent/conches/manifest").accept(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder reports() {
        return post("/agent/reports").contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    @FunctionalInterface
    private interface RequestBuilder {
        MockHttpServletRequestBuilder build();
    }
}
