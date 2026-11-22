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
        assertThat(diff.addedScripts()).isEmpty();
        assertThat(diff.removedScripts()).isEmpty();
        assertThat(diff.changedScripts()).isEmpty();
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

    @Test
    void scriptLibraryChangesAreNamedAndMarkCapabilitiesThatStillUseThem() {
        ConchDoc oldDoc = conch("""
                scripts:
                  alpha: "return 1; // MARKER_ALPHA_OLD"
                  gamma: "return 3; // MARKER_GAMMA_OLD"
                  beta: "return 2; // MARKER_BETA"
                  login_pack: "return 0; // MARKER_LOGIN_OLD"
                auth:
                  steps:
                    - script:
                        use: [login_pack]
                        code: "return login_pack();"
                actions:
                  run:
                    title: 加密
                    steps:
                      - script:
                          use: [gamma, beta]
                          code: "return gamma();"
                  gone:
                    title: 仍引用
                    steps:
                      - script:
                          use: [alpha]
                          code: "return alpha();"
                  idle:
                    title: 闲置
                    steps:
                      - set:
                          marker: ok
                """);
        ConchDoc newDoc = conch("""
                scripts:
                  gamma: "return 30; // MARKER_GAMMA_NEW"
                  beta: "return 2; // MARKER_BETA"
                  login_pack: "return 9; // MARKER_LOGIN_NEW"
                  delta: "return 4; // MARKER_DELTA_NEW"
                auth:
                  steps:
                    - script:
                        use: [login_pack]
                        code: "return login_pack();"
                actions:
                  run:
                    title: 加密
                    steps:
                      - script:
                          use: [gamma, beta]
                          code: "return gamma();"
                  gone:
                    title: 仍引用
                    steps:
                      - script:
                          use: [alpha]
                          code: "return alpha();"
                  idle:
                    title: 闲置
                    steps:
                      - set:
                          marker: ok
                """);

        assertThat(oldDoc.actions().get("run").steps())
                .isEqualTo(newDoc.actions().get("run").steps());
        assertThat(oldDoc.actions().get("gone").steps())
                .isEqualTo(newDoc.actions().get("gone").steps());

        ConchDiff diff = ConchDiff.between(oldDoc, newDoc);
        assertThat(diff.removedScripts()).containsExactly("alpha");
        assertThat(diff.changedScripts()).containsExactly("gamma", "login_pack");
        assertThat(diff.addedScripts()).containsExactly("delta");
        assertThat(diff.changedActions()).containsExactly(
                new ActionChange("run", false, false, true, false, false),
                new ActionChange("gone", false, false, true, false, false));
        assertThat(diff.toString())
                .doesNotContain("MARKER_GAMMA_NEW")
                .doesNotContain("MARKER_LOGIN_NEW")
                .doesNotContain("MARKER_ALPHA_OLD")
                .doesNotContain("MARKER_DELTA_NEW");
    }

    @Test
    void addedScriptLibraryDoesNotMarkACapabilityUntilUseChanges() {
        ConchDoc oldDoc = conch("""
                scripts:
                  pack: "return 1;"
                actions:
                  run:
                    title: 能力
                    steps:
                      - script:
                          use: [pack]
                          code: "return pack();"
                """);
        ConchDoc addedOnly = conch("""
                scripts:
                  pack: "return 1;"
                  extra: "return 2; // MARKER_EXTRA"
                actions:
                  run:
                    title: 能力
                    steps:
                      - script:
                          use: [pack]
                          code: "return pack();"
                """);
        ConchDiff unused = ConchDiff.between(oldDoc, addedOnly);
        assertThat(unused.addedScripts()).containsExactly("extra");
        assertThat(unused.removedScripts()).isEmpty();
        assertThat(unused.changedScripts()).isEmpty();
        assertThat(unused.changedActions()).isEmpty();
        assertThat(unused.toString()).doesNotContain("MARKER_EXTRA");

        ConchDoc used = conch("""
                scripts:
                  pack: "return 1;"
                  extra: "return 2;"
                actions:
                  run:
                    title: 能力
                    steps:
                      - script:
                          use: [pack, extra]
                          code: "return extra();"
                """);
        assertThat(ConchDiff.between(oldDoc, used).changedActions())
                .containsExactly(new ActionChange("run", false, false, true, false, false));
        assertThat(ConchDiff.between(oldDoc, oldDoc).addedScripts()).isEmpty();
        assertThat(ConchDiff.between(oldDoc, oldDoc).changedActions()).isEmpty();
    }

    @Test
    void scriptLibraryChangesMarkActionsUsingThemInsideIfAndRepeat() {
        String actions = """
                actions:
                  in_then:
                    title: 分支内
                    steps:
                      - if:
                          present: ok
                          then:
                            - script:
                                use: [gamma]
                                code: "return gamma();"
                  in_else:
                    title: 否则分支内
                    steps:
                      - if:
                          present: ok
                          then:
                            - set: {a: "1"}
                          else:
                            - script:
                                use: [alpha]
                                code: "return alpha();"
                  in_repeat:
                    title: 循环内
                    steps:
                      - repeat:
                          max: 2
                          steps:
                            - script:
                                use: [gamma]
                                code: "return gamma();"
                  in_nested:
                    title: 嵌套内
                    steps:
                      - repeat:
                          max: 2
                          steps:
                            - if:
                                present: ok
                                then:
                                  - script:
                                      use: [alpha]
                                      code: "return alpha();"
                  untouched:
                    title: 未受影响
                    steps:
                      - if:
                          present: ok
                          then:
                            - script:
                                use: [beta]
                                code: "return beta();"
                      - repeat:
                          max: 2
                          steps:
                            - script:
                                use: [beta]
                                code: "return beta();"
                """;
        ConchDoc oldDoc = conch("""
                scripts:
                  alpha: "return 1;"
                  beta: "return 2;"
                  gamma: "return 3;"
                """ + actions);
        ConchDoc newDoc = conch("""
                scripts:
                  beta: "return 2;"
                  gamma: "return 30;"
                """ + actions);

        ConchDiff diff = ConchDiff.between(oldDoc, newDoc);
        assertThat(diff.removedScripts()).containsExactly("alpha");
        assertThat(diff.changedScripts()).containsExactly("gamma");
        assertThat(diff.changedActions()).containsExactly(
                new ActionChange("in_then", false, false, true, false, false),
                new ActionChange("in_else", false, false, true, false, false),
                new ActionChange("in_repeat", false, false, true, false, false),
                new ActionChange("in_nested", false, false, true, false, false));
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
