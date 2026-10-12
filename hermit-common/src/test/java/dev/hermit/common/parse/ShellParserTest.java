package dev.hermit.common.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hermit.common.Fixtures;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.ShellDoc;

class ShellParserTest {

    @Test
    void preservesOrderAliasesAndRawTrees() {
        ShellDoc doc = Fixtures.parse("legacy_oa_valid.yaml");
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
        ShellDoc doc = ShellParser.parse("""
                connector: legacy_oa
                shell: legacy_oa
                version: 1.2.3
                min_agent_version: 1.0.0
                min_crab_version: 1.0.0
                """);
        assertThat(doc.shell()).isEqualTo("legacy_oa");
        assertThat(doc.version().toString()).isEqualTo("1.2.3");
        assertThat(doc.minCrabVersion().toString()).isEqualTo("1.0.0");
    }

    @Test
    void acceptsConnectorAndMinAgentVersionAlone() {
        ShellDoc doc = ShellParser.parse("""
                connector: legacy_oa
                version: 1.0.0
                min_agent_version: 2.0.0-rc.1
                """);
        assertThat(doc.shell()).isEqualTo("legacy_oa");
        assertThat(doc.minCrabVersion().toString()).isEqualTo("2.0.0-rc.1");
        assertThat(doc.credentials()).isNull();
        assertThat(doc.config()).isEmpty();
        assertThat(doc.actions()).isEmpty();
    }

    @Test
    void rejectsConflictingAliasesAndUnknownTopLevelKeys() {
        assertThatThrownBy(() -> ShellParser.parse(Fixtures.yaml("alias_conflict.yaml")))
                .isInstanceOf(ShellParseException.class)
                .hasMessageContaining("shell")
                .extracting(ex -> ((ShellParseException) ex).path())
                .isEqualTo("shell");
        assertThatThrownBy(() -> ShellParser.parse("""
                shell: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                min_agent_version: 1.0.1
                """)).isInstanceOf(ShellParseException.class).hasMessageContaining("min_crab_version");
        assertThatThrownBy(() -> ShellParser.parse("""
                shell: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                owners: 平台组
                """)).isInstanceOf(ShellParseException.class).hasMessageContaining("owners");
    }

    @Test
    void rejectsStepsThatAreNotSingleKeyMappings() {
        assertThatThrownBy(() -> ShellParser.parse("""
                shell: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    steps:
                      - request: { method: GET, path: /a }
                      - request: { method: GET }
                        extract: { id: { css: "#a" } }
                """)).isInstanceOf(ShellParseException.class)
                .hasMessageContaining("actions.list_todo.steps[1]");
        assertThatThrownBy(() -> ShellParser.parse("""
                shell: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    steps:
                      - just-a-string
                """)).isInstanceOf(ShellParseException.class)
                .hasMessageContaining("actions.list_todo.steps[0]");
    }

    @Test
    void emptyStepBodyIsAnEmptyMapAndMethodIsCaseSensitive() {
        ShellDoc doc = ShellParser.parse("""
                shell: legacy_oa
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
        assertThatThrownBy(() -> ShellParser.parse("""
                shell: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                actions:
                  list_todo:
                    method: get
                """)).isInstanceOf(ShellParseException.class).hasMessageContaining("method");
    }

    @Test
    void rejectsBadFieldTypesAndKeepsParamSchemaPieces() {
        assertThatThrownBy(() -> ShellParser.parse("""
                shell: legacy_oa
                version: 1.0.0
                min_crab_version: 1.0.0
                credentials:
                  username: { type: password }
                """)).isInstanceOf(ShellParseException.class).hasMessageContaining("credentials.username.type");
        ShellDoc doc = ShellParser.parse("""
                shell: legacy_oa
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
}
