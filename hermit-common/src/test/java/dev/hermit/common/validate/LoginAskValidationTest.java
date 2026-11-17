package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.version.SemVer;

class LoginAskValidationTest {

    @Test
    void acceptsTwoAsksWhenTheCrabVersionIsHighEnough() {
        ValidationResult result = validate(packageYaml("1.4.0", """
                - request: { method: GET, path: /captcha.png }
                - ask:
                    into: captcha
                    kind: image
                    label: 验证码
                    hint: 看图
                    timeout_s: 60
                - request: { method: POST, path: /send }
                - ask:
                    into: sms
                    kind: code
                    label: 短信验证码
                - extract:
                    token: { jsonpath: $.token }
                """), "1.4.0");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsAskBelowOneFourAndOutsideAuthSteps() {
        ValidationResult old = validate(packageYaml("1.3.0", """
                - request: { method: GET, path: /captcha.png }
                - ask: { into: captcha, kind: image, label: 验证码 }
                """), "1.4.0");
        assertThat(old.errors()).extracting(ValidationIssue::message)
                .contains("使用 ask 时 min_crab_version 必须不低于 1.4.0");

        ValidationResult outside = validate("""
                conch: captcha_oa
                version: 1.0.0
                driver: http
                min_crab_version: 1.4.0
                title: 标题
                description: 说明
                credentials:
                  password: { type: secret, required: true, label: 密码 }
                actions:
                  tick:
                    title: 能力
                    description: 说明
                    output:
                      seen: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { seen: ok }
                    notes: 注意
                    steps:
                      - ask: { into: captcha, kind: code, label: 验证码 }
                      - set: { seen: ok }
                """, "1.4.0");
        assertThat(outside.errors()).extracting(ValidationIssue::message)
                .contains("ask 只能写在 auth.steps");
    }

    @Test
    void rejectsReservedDuplicateKindHintAndAThirdAsk() {
        assertThat(messages("""
                - ask: { into: login, kind: code, label: 验证码 }
                """)).contains("不能写入保留变量 login");
        assertThat(messages("""
                - extract:
                    captcha: { jsonpath: $.captcha }
                - ask: { into: captcha, kind: code, label: 验证码 }
                """)).contains("ask into 与已声明的名字重复");
        assertThat(messages("""
                - ask: { into: captcha, kind: text, label: 验证码 }
                """)).contains("ask kind 只允许 image 或 code");
        assertThat(messages("""
                - ask: { into: captcha, kind: image, label: 验证码 }
                """)).contains("kind 为 image 时，前一步必须是 request");
        assertThat(messages("""
                - request: { method: GET, path: /captcha.png }
                - ask: { into: captcha, kind: image, label: "  ", hint: 1 }
                """)).contains("ask 缺少 label", "ask hint 必须是字符串");
        assertThat(messages("""
                - request: { method: GET, path: /captcha.png }
                - ask:
                    into: captcha
                    kind: image
                    label: 验证码
                    hint: "%s"
                    timeout_s: 59
                    extra: 1
                """.formatted("x".repeat(257)))).contains(
                "ask hint 超过 256 字节",
                "ask timeout_s 必须是 60 到 900 的整数",
                "未知的 ask 字段 extra");
        assertThat(messages("""
                - request: { method: GET, path: /one }
                - ask: { into: one, kind: image, label: 第一张 }
                - request: { method: GET, path: /two }
                - ask: { into: two, kind: image, label: 第二张 }
                - request: { method: GET, path: /three }
                - ask: { into: three, kind: image, label: 第三张 }
                """)).contains("一个登录最多 2 个 ask");
    }

    @Test
    void askIntoIsNotALoginSnapshotName() {
        String yaml = packageYaml("1.4.0", """
                - request: { method: GET, path: /captcha.png }
                - ask: { into: captcha, kind: image, label: 验证码 }
                - request: { method: POST, path: "/login/{{captcha}}" }
                """);
        assertThat(yaml).contains("{{login.token}}");
        ValidationResult result = validate(yaml.replace("{{login.token}}", "{{login.captcha}}"), "1.4.0");
        assertThat(result.errors()).extracting(ValidationIssue::message)
                .contains("引用了未声明的登录变量 captcha");
    }

    private static java.util.List<String> messages(String authSteps) {
        return validate(packageYaml("1.4.0", authSteps), "1.4.0").errors().stream()
                .map(ValidationIssue::message)
                .toList();
    }

    private static ValidationResult validate(String yaml, String crab) {
        ConchDoc doc = ConchParser.parse(yaml);
        return ConchValidator.validate(doc, ConchValidator.Context.of(SemVer.parse(crab)));
    }

    private static String packageYaml(String minCrab, String authSteps) {
        return """
                conch: captcha_oa
                version: 1.0.0
                driver: http
                min_crab_version: %s
                title: 标题
                description: 说明
                credentials:
                  password: { type: secret, required: true, label: 密码 }
                auth:
                  steps:
                %s
                actions:
                  tick:
                    title: 能力
                    description: 说明
                    output:
                      seen: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { seen: ok }
                    notes: 注意
                    steps:
                      - request: { method: GET, path: "/tick/{{login.token}}" }
                """.formatted(minCrab, margin(authSteps).indent(4).stripTrailing());
    }

    private static String margin(String text) {
        return text.stripTrailing().stripIndent();
    }
}
