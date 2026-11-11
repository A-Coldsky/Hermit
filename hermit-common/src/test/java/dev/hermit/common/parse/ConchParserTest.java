package dev.hermit.common.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import dev.hermit.common.Fixtures;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.ConchDoc;

class ConchParserTest {

    @Test
    void preservesOrderAliasesAndRawTrees() {
        ConchDoc doc = Fixtures.parse("legacy_oa_valid.yaml");
        assertThat(doc.credentials().keySet()).containsExactly("username", "password");
        assertThat(doc.credentials().get("password").type()).isEqualTo("secret");
        assertThat(doc.credentials().get("password").required()).isTrue();
        assertThat(doc.credentials().get("username").required()).isTrue();
        assertThat(doc.config().keySet()).containsExactly("base_url");
        assertThat(doc.config().get("base_url").defaultValue()).isEqualTo("http://oa.intranet.local");
        assertThat(doc.tags()).containsExactly("审批", "请假");
        assertThat(doc.actions().keySet()).containsExactly("list_todo");
        assertThat(doc.auth().steps()).extracting(step -> step.type())
                .containsExactly("request", "extract", "request", "assert");
        assertThat(doc.auth().steps().get(2).def()).containsKey("form");
        assertThat(doc.auth().aliveCheck().expectNotRedirect()).isEqualTo("/login.aspx");
        assertThat(doc.auth().aliveCheck().request()).containsEntry("path", "/home.aspx");
        assertThat(doc.healthCheck()).containsExactly(Map.of("action", "list_todo"));
        assertThat(doc.actions().get("list_todo").input().get("page").defaultValue()).isEqualTo(1);
        assertThat(doc.actions().get("list_todo").idempotent()).isTrue();
        assertThat(doc.actions().get("list_todo").mcp().readOnly()).isTrue();
        assertThat(doc.actions().get("list_todo").mcp().expose()).isTrue();
        assertThat(doc.actions().get("list_todo").mcp().destructive()).isFalse();
    }

    @Test
    void acceptsEitherAliasWhenValuesMatch() {
        ConchDoc doc = ConchParser.parse("""
                connector: legacy_oa
                conch: legacy_oa
                version: 1.2.3
                min_agent_version: 1.0.0
                min_crab_version: 1.0.0
                """);
        assertThat(doc.conch()).isEqualTo("legacy_oa");
        assertThat(doc.version().toString()).isEqualTo("1.2.3");
        assertThat(doc.minCrabVersion().toString()).isEqualTo("1.0.0");
    }

    @Test
    void acceptsConnectorAndMinAgentVersionAlone() {
        ConchDoc doc = ConchParser.parse("""
                connector: legacy_oa
                version: 1.0.0
                min_agent_version: 2.0.0-rc.1
                """);
        assertThat(doc.conch()).isEqualTo("legacy_oa");
        assertThat(doc.minCrabVersion().toString()).isEqualTo("2.0.0-rc.1");
        assertThat(doc.credentials()).isNull();
        assertThat(doc.config()).isEmpty();
        assertThat(doc.actions()).isEmpty();
    }

    @Test
    void rejectsConflictingAliasesAndUnknownTopLevelKeys() {
        assertThatThrownBy(() -> ConchParser.parse(Fixtures.yaml("alias_conflict.yaml")))
                .isInstanceOf(ConchParseException.class)
                .hasMessageContaining("conch")
                .extracting(ex -> ((ConchParseException) ex).path())
                .isEqualTo("conch");
        assertThatThrownBy(() -> ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                min_agent_version: 1.0.1
                """)).isInstanceOf(ConchParseException.class).hasMessageContaining("min_crab_version");
        assertThatThrownBy(() -> ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                owners: 平台组
                """)).isInstanceOf(ConchParseException.class).hasMessageContaining("owners");
    }

    @Test
    void rejectsStepsThatAreNotSingleKeyMappings() {
        assertThatThrownBy(() -> ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    steps:
                      - request: { method: GET, path: /a }
                      - request: { method: GET }
                        extract: { id: { css: "#a" } }
                """)).isInstanceOf(ConchParseException.class)
                .hasMessageContaining("actions.list_todo.steps[1]");
        assertThatThrownBy(() -> ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    steps:
                      - just-a-string
                """)).isInstanceOf(ConchParseException.class)
                .hasMessageContaining("actions.list_todo.steps[0]");
    }

    @Test
    void emptyStepBodyIsAnEmptyMapAndMethodIsCaseSensitive() {
        ConchDoc doc = ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    method: POST
                    steps:
                      - request:
                """);
        assertThat(doc.actions().get("list_todo").method()).isEqualTo(HttpMethod.POST);
        assertThat(doc.actions().get("list_todo").steps().get(0).def()).isEmpty();
        assertThatThrownBy(() -> ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    method: get
                """)).isInstanceOf(ConchParseException.class).hasMessageContaining("method");
    }

    @Test
    void rejectsBadFieldTypesAndKeepsParamSchemaPieces() {
        assertThatThrownBy(() -> ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                credentials:
                  username: { type: password }
                """)).isInstanceOf(ConchParseException.class).hasMessageContaining("credentials.username.type");
        ConchDoc doc = ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                credentials: {}
                actions:
                  list_todo:
                    input:
                      status:
                        type: string
                        description: 状态
                        required: true
                        enum: [open, done]
                        minimum: 0
                        maximum: 2
                        items: { type: string }
                """);
        var spec = doc.actions().get("list_todo").input().get("status");
        assertThat(spec.required()).isTrue();
        assertThat(spec.enumValues()).isEqualTo(List.of("open", "done"));
        assertThat(spec.minimum()).isEqualTo(0);
        assertThat(spec.maximum()).isEqualTo(2);
        assertThat(spec.items()).isEqualTo(Map.of("type", "string"));
        assertThat(doc.actions().get("list_todo").inputJsonSchema().get("required")).isEqualTo(List.of("status"));
    }

    @Test
    void tabAndDeeperIndentReportTheSnakeYamlLine() {
        String tab = yaml(
                "conch: demo",
                "version: 1.0.0",
                "min_crab_version: 1.0.0",
                "driver: http",
                "base_url: http://example.test",
                "\ttitle: broken");
        String deeper = yaml(
                "conch: demo",
                "version: 1.0.0",
                "min_crab_version: 1.0.0",
                "actions:",
                "  list: {}",
                "    extra: 1");
        assertReportedLine(tab, 6, true);
        assertReportedLine(deeper, 6, true);
    }

    @Test
    void mappingValueOnTheSameLineStaysThere() {
        String colon = yaml(
                "conch: demo",
                "version: 1.0.0",
                "min_crab_version: 1.0.0",
                "driver: http",
                "base_url: http://example.test",
                "title: a: b");
        assertReportedLine(colon, 6, false);
    }

    @Test
    void unclosedFlowSequenceKeepsTheFollowingLine() {
        String open = yaml(
                "conch: demo",
                "version: 1.0.0",
                "min_crab_version: 1.0.0",
                "driver: http",
                "base_url: http://example.test",
                "tags: [");
        assertReportedLine(open, 7, true);
    }

    @Test
    void duplicateActionNamesFailWithTheLine() {
        String yaml = """
                conch: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    title: 一
                  list_todo:
                    title: 二
                """;
        assertThatThrownBy(() -> ConchParser.parse(yaml))
                .isInstanceOf(ConchParseException.class)
                .hasMessageContaining("能力名重复：list_todo")
                .hasMessageContaining("第")
                .hasMessageContaining("行");
    }

    private static void assertReportedLine(String yaml, int snakeLine, boolean jacksonIsEarlier) {
        JsonProcessingException failure = yamlFailure(yaml);
        int jackson = failure.getLocation().getLineNr();
        int snake = snakeProblemLine(failure);
        assertThat(snake).isEqualTo(snakeLine);
        if (jacksonIsEarlier) {
            assertThat(jackson).isEqualTo(snakeLine - 1);
        } else {
            assertThat(jackson).isEqualTo(snakeLine);
        }
        assertThatThrownBy(() -> ConchParser.parse(yaml))
                .isInstanceOf(ConchParseException.class)
                .hasMessage("YAML 无法解析（第 " + snakeLine + " 行）");
    }

    private static String yaml(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static JsonProcessingException yamlFailure(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(4 * 1024 * 1024);
        YAMLMapper mapper = new YAMLMapper(YAMLFactory.builder().loaderOptions(options).build());
        try {
            mapper.readTree(yaml);
        } catch (JsonProcessingException ex) {
            return ex;
        }
        throw new AssertionError("expected a YAML syntax error");
    }

    private static int snakeProblemLine(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof MarkedYAMLException marked && marked.getProblemMark() != null) {
                Mark mark = marked.getProblemMark();
                return mark.getLine() + 1;
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        throw new AssertionError("SnakeYAML problem mark is missing");
    }
}
