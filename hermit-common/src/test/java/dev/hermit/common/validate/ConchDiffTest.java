package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;

class ConchDiffTest {

    @Test
    void reportsActionCredentialAndConfigChanges() {
        ConchDoc oldDoc = conch("""
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: false }
                config:
                  base_url: { type: url, default: "http://old" }
                  timeout: { type: text, default: "5" }
                actions:
                  list_todo:
                    title: 旧标题
                    method: POST
                    idempotent: true
                    mcp: { read_only: false, destructive: false }
                    input:
                      page: { type: integer, description: 页码 }
                    output:
                      items: { type: string, description: 结果 }
                    steps:
                      - request: { path: /old }
                  approve:
                    title: 审批
                    description: 审批
                    input: {}
                    output:
                      ok: { type: boolean, description: 是否成功 }
                    examples:
                      - input: {}
                        output: { ok: true }
                    notes: 注意
                    related: [approve]
                """);
        ConchDoc newDoc = conch("""
                credentials:
                  username: { type: text, required: true, label: 改了标签 }
                  password: { type: secret, required: true }
                  otp: { type: secret, required: true }
                config:
                  base_url: { type: url, default: "http://new" }
                  region: { type: text, default: cn }
                actions:
                  list_todo:
                    title: 新标题
                    method: POST
                    idempotent: false
                    mcp: { read_only: true }
                    input:
                      page: { type: integer, description: 页码, required: true }
                    output:
                      items: { type: array, description: 结果 }
                    steps:
                      - request: { path: /new }
                  create_todo:
                    title: 新建
                    description: 新建
                    input: {}
                    output:
                      id: { type: string, description: 单号 }
                    examples:
                      - input: {}
                        output: { id: "1" }
                    notes: 注意
                    related: [create_todo]
                """);

        ConchDiff diff = ConchDiff.between(oldDoc, newDoc);
        assertThat(diff.addedActions()).containsExactly("create_todo");
        assertThat(diff.removedActions()).containsExactly("approve");
        assertThat(diff.changedActions()).containsExactly(new ActionChange(
                "list_todo", true, true, true, false, true));
        assertThat(diff.addedCredentials()).containsExactly("otp");
        assertThat(diff.removedCredentials()).isEmpty();
        assertThat(diff.credentialRequiredChanges())
                .containsExactly(new CredentialRequiredChange("password", false, true));
        assertThat(diff.newRequiredCredentials()).containsExactly("password", "otp");
        assertThat(diff.addedConfig()).containsExactly("region");
        assertThat(diff.removedConfig()).containsExactly("timeout");
        assertThat(diff.configDefaultChanges())
                .containsExactly(new ConfigDefaultChange("base_url", "http://old", "http://new"));
        assertThat(diff.toolChanges()).isEmpty();
    }

    @Test
    void explicitFalseDestructiveDoesNotDifferFromOmitted() {
        String action = """
                actions:
                  list_todo:
                    title: 查询
                    description: 说明
                    mcp: { read_only: true%s }
                    input:
                      page: { type: integer, description: 页码 }
                    output:
                      items: { type: string, description: 结果 }
                """;
        ConchDoc omitted = conch(action.formatted(""));
        ConchDoc explicit = conch(action.formatted(", destructive: false"));
        assertThat(omitted.actions().get("list_todo").mcp())
                .isEqualTo(explicit.actions().get("list_todo").mcp());
        assertThat(ConchDiff.between(omitted, explicit).changedActions()).isEmpty();
        assertThat(ConchDiff.between(omitted, explicit).toolChanges()).isEmpty();
    }

    @Test
    void methodChangeFollowsEffectiveMethod() {
        ConchDoc explicit = ConchParser.parse(action("""
                method: POST
                idempotent: false
                """));
        ConchDoc stillPost = ConchParser.parse(action("""
                method: POST
                idempotent: true
                """));
        assertThat(ConchDiff.between(explicit, stillPost).changedActions()).isEmpty();

        ConchDoc implicitPost = ConchParser.parse(action("""
                idempotent: false
                """));
        ConchDoc implicitGet = ConchParser.parse(action("""
                idempotent: true
                """));
        assertThat(ConchDiff.between(implicitPost, implicitGet).changedActions())
                .containsExactly(new ActionChange("list_todo", false, false, false, true, false));
    }

    private static ConchDoc conch(String body) {
        return ConchParser.parse("""
                conch: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                """ + body);
    }

    private static String action(String fields) {
        String base = """
                conch: legacy_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  list_todo:
                    title: 查询
                    description: 说明
                    input:
                      page: { type: integer, description: 页码 }
                    output:
                      items: { type: string, description: 结果 }
                    examples:
                      - input: { page: 1 }
                        output: { items: ok }
                    notes: 注意
                    related: [list_todo]
                """.stripIndent();
        String indented = fields.stripIndent().stripTrailing().replace("\n", "\n    ");
        return base + "    " + indented + "\n";
    }
}
