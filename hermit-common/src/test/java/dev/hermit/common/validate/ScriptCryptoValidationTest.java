package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

class ScriptCryptoValidationTest {

    private static final ConchValidator.Context V114 = ConchValidator.Context.of(SemVer.parse("1.14.0"));

    private static String baseYaml(String minCrabVersion, String extraTop, String actionSteps) {
        return """
                conch: demo_crypto
                version: 1.0.0
                driver: http
                min_crab_version: %s
                base_url: https://oa.corp.example
                title: 加解密校验示例
                description: 验证 1.14.0 脚本加解密与登录变量访问规则
                credentials:
                  username: { type: text, label: 账号 }
                  password: { type: secret, label: 密码 }
                %s
                auth:
                  cookies:
                    xsrf: XSRF-TOKEN
                  steps:
                    - request: { method: POST, path: /login, form: { u: "{{credential.username}}" } }
                    - extract: { csrf_tok: "$.csrf" }
                actions:
                  ping:
                    title: 探测
                    description: 探测接口
                    method: GET
                    steps:
                %s
                    output:
                      ok: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { ok: ok }
                """.formatted(minCrabVersion, extraTop, actionSteps);
    }

    @Test
    void hermit114HelpersRequireMinCrabVersion114() {
        for (String helper : ScriptCryptoChecks.HERMIT_114_FUNCTIONS) {
            String on113 = baseYaml("1.13.0", "", """
                          - script:
                              into: sig
                              code: "const fn = hermit.%s; return typeof fn;"
                    """.formatted(helper));
            ValidationResult rejected = ConchValidator.validate(ConchParser.parse(on113), V114);
            assertThat(rejected.errors())
                    .as("helper " + helper)
                    .anyMatch(i -> i.code().equals(ValidationCodes.MIN_CRAB_VERSION)
                            && i.message().equals(ScriptCryptoChecks.CRYPTO_VERSION));

            String on114 = on113.replace("min_crab_version: 1.13.0", "min_crab_version: 1.14.0");
            assertThat(ConchValidator.validate(ConchParser.parse(on114), V114).errors()).isEmpty();
        }
    }

    @Test
    void hermit114InScriptLibraryOrTemplateLiteralAlsoGatesVersionButIgnoresCommentsAndStrings() {
        String inCommentAndString = baseYaml("1.13.0", "", """
                      - script:
                          into: sig
                          code: |
                            // hermit.md5('x')
                            /* hermit.aesEncrypt('CBC', k, iv, p) */
                            const literal = "hermit.rsaEncrypt(pk, 'x')";
                            const tpl = `prefix hermit.gbk('x') suffix`;
                            return literal + tpl;
                """);
        assertThat(ConchValidator.validate(ConchParser.parse(inCommentAndString), V114).errors()).isEmpty();

        String inTemplateExpr = baseYaml("1.13.0", """
                scripts:
                  signer: |
                    function makeSig(s) {
                      return `${hermit.hex(hermit.hmacSha256('k', s))}`;
                    }
                """, """
                      - script:
                          use: [signer]
                          into: sig
                          code: "return makeSig('hello');"
                """);
        assertThat(ConchValidator.validate(ConchParser.parse(inTemplateExpr), V114).errors())
                .anyMatch(i -> i.code().equals(ValidationCodes.MIN_CRAB_VERSION)
                        && i.message().equals(ScriptCryptoChecks.CRYPTO_VERSION));
    }

    @Test
    void credentialScriptReadingVarsLoginChecksVersionAndDeclaredLoginFields() {
        String undeclared = baseYaml("1.14.0", "", """
                      - script:
                          credential: true
                          into: signed
                          code: "return vars.login.csrf_tok + ':' + vars.login.xsrf + ':' + vars.login.missing_tok;"
                """);
        ValidationResult badField = ConchValidator.validate(ConchParser.parse(undeclared), V114);
        assertThat(badField.errors())
                .anyMatch(i -> i.code().equals(ValidationCodes.UNDECLARED_VARIABLE)
                        && i.message().contains("missing_tok"));

        String valid114 = undeclared.replace(" + ':' + vars.login.missing_tok", "");
        assertThat(ConchValidator.validate(ConchParser.parse(valid114), V114).errors()).isEmpty();

        String lowered = valid114.replace("min_crab_version: 1.14.0", "min_crab_version: 1.13.0");
        assertThat(ConchValidator.validate(ConchParser.parse(lowered), V114).errors())
                .anyMatch(i -> i.code().equals(ValidationCodes.MIN_CRAB_VERSION)
                        && i.message().equals(ScriptCryptoChecks.SECRET_VARS_VERSION));
    }

    @Test
    void plainScriptAccessingVarsLoginMemberWarnsWithoutFailingValidation() {
        String plain = baseYaml("1.13.0", "", """
                      - script:
                          into: check
                          code: "return vars.login && vars.login.csrf_tok ? 'leaked' : 'hidden';"
                """);
        ValidationResult res = ConchValidator.validate(ConchParser.parse(plain), V114);
        assertThat(res.errors()).isEmpty();
        assertThat(res.warnings())
                .anyMatch(w -> w.code().equals(ValidationCodes.SCRIPT_STEP)
                        && w.message().equals(ScriptCryptoChecks.PLAIN_SCRIPT_SECRET_WARNING));
    }
}
