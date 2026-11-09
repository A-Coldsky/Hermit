package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ValidationIssueJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unknownLineIsOmittedAndStoredJsonWithoutLineStillReads() throws Exception {
        String plain = mapper.writeValueAsString(new ValidationIssue("PARSE", "min_crab_version", "缺少 min_crab_version"));
        assertThat(plain).doesNotContain("line");
        assertThat(plain).contains("缺少 min_crab_version");

        ValidationIssue located = new ValidationIssue("DRIVER", "driver", "driver 只允许 http 或 mcp", 3);
        assertThat(mapper.writeValueAsString(located)).contains("\"line\":3");

        ValidationIssue read = mapper.readValue(
                "{\"code\":\"MISSING_RELATED\",\"path\":\"actions.ping.related\",\"message\":\"缺少 related\"}",
                ValidationIssue.class);
        assertThat(read.code()).isEqualTo("MISSING_RELATED");
        assertThat(read.path()).isEqualTo("actions.ping.related");
        assertThat(read.message()).isEqualTo("缺少 related");
        assertThat(read.line()).isNull();
        assertThat(new ValidationIssue("PARSE", "", "YAML 无法解析（第 0 行）", 0).line()).isNull();
    }
}
