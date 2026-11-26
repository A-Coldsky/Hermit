package dev.hermit.common.validate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;

class ConchDestinationsTest {

    @Test
    void httpSubsetIsTheSameBoundaryAndANewOriginWidens() {
        ConchDoc before = http("http://127.0.0.1:9/", """
                - request: { method: GET, path: /ping }
                - set: { marker: ok }
                """);
        ConchDoc same = http("http://127.0.0.1:9", """
                - request: { method: GET, path: "/users/{{input.id}}" }
                """);
        ConchDestinations.Boundary unchanged = ConchDestinations.between(before, same);
        assertThat(unchanged.sameBoundary()).isTrue();
        assertThat(unchanged.addedDestinations()).isEmpty();
        assertThat(unchanged.undetermined()).isFalse();

        ConchDoc widened = http("http://127.0.0.1:9/", """
                - request: { method: GET, url: https://Other.Example/hook }
                - script: { code: "fetch('https://other.example:443/again')" }
                """);
        ConchDestinations.Boundary boundary = ConchDestinations.between(before, widened);
        assertThat(boundary.sameBoundary()).isFalse();
        assertThat(boundary.addedDestinations()).containsExactly("https://other.example:443");
    }

    @Test
    void templateInTheHostIsUndeterminedAndARelativeTemplateIsNot() {
        ConchDoc before = http("https://app.example/", """
                - request: { method: GET, path: /ping }
                """);
        ConchDoc templated = http("https://app.example/", """
                - request: { method: GET, url: "https://{{config.tenant}}.example/hook" }
                """);
        ConchDestinations.Boundary boundary = ConchDestinations.between(before, templated);
        assertThat(boundary.sameBoundary()).isFalse();
        assertThat(boundary.undetermined()).isTrue();
        assertThat(ConchDestinations.describe(boundary)).contains("无法静态确定的地址");

        ConchDoc relative = http("https://app.example/", """
                - request: { method: GET, path: "/{{input.id}}" }
                """);
        assertThat(ConchDestinations.between(before, relative).sameBoundary()).isTrue();
    }

    @Test
    void configBaseUrlAndScriptLibraryCount() {
        ConchDoc before = ConchParser.parse(httpYaml("https://app.example/", """
                - request: { method: GET, path: /ping }
                """, ""));
        ConchDoc configured = ConchParser.parse(httpYaml("https://app.example/", """
                - request: { method: GET, path: /ping }
                """, """
                config:
                  base_url: { type: url, default: "https://files.example/root" }
                scripts:
                  lib: |
                    const target = "http://files.example/lib";
                """));
        ConchDestinations.Boundary boundary = ConchDestinations.between(before, configured);
        assertThat(boundary.sameBoundary()).isFalse();
        assertThat(boundary.addedDestinations()).containsExactly("http://files.example:80", "https://files.example:443");
    }

    @Test
    void removingAnOriginStaysInsideTheOldSet() {
        ConchDoc before = http("https://app.example/", """
                - request: { method: GET, url: https://extra.example/hook }
                """);
        ConchDoc after = http("https://app.example/", """
                - request: { method: GET, path: /ping }
                """);
        assertThat(ConchDestinations.between(before, after).sameBoundary()).isTrue();
    }

    @Test
    void unreadablePreviousDocumentIsWidened() {
        ConchDoc after = http("https://app.example/", """
                - request: { method: GET, path: /ping }
                """);
        ConchDestinations.Boundary boundary = ConchDestinations.between(null, after);
        assertThat(boundary.sameBoundary()).isFalse();
        assertThat(boundary.undetermined()).isTrue();
        assertThat(boundary.addedDestinations()).containsExactly("https://app.example:443");
    }

    @Test
    void mcpPortAuthAndTokenEndpoint() {
        ConchDoc before = mcp("https://OLD.Example/mcp", "oauth", "https://old.example/oauth/token");
        ConchDoc same = mcp("https://old.example:443/mcp/v2", "oauth", "https://old.example/oauth/token2");
        assertThat(ConchDestinations.between(before, same).sameBoundary()).isTrue();

        ConchDoc port = mcp("https://old.example:8443/mcp", "oauth", "https://old.example/oauth/token");
        ConchDestinations.Boundary portBoundary = ConchDestinations.between(before, port);
        assertThat(portBoundary.sameBoundary()).isFalse();
        assertThat(portBoundary.addedDestinations()).containsExactly("https://old.example:8443");

        ConchDoc token = mcp("https://old.example/mcp", "oauth", "https://auth.example/oauth/token");
        assertThat(ConchDestinations.between(before, token).addedDestinations())
                .containsExactly("https://auth.example:443");

        ConchDoc headers = mcp("https://old.example/mcp", "headers", null);
        ConchDestinations.Boundary auth = ConchDestinations.between(before, headers);
        assertThat(auth.sameBoundary()).isFalse();
        assertThat(auth.authChanged()).isTrue();
        assertThat(auth.addedDestinations()).isEmpty();
    }

    @Test
    void ipv6KeepsBrackets() {
        ConchDoc before = http("http://[::1]:8080/", """
                - request: { method: GET, path: /ping }
                """);
        ConchDoc after = http("http://[::1]:8080/", """
                - request: { method: GET, url: "http://[::1]:9090/hook" }
                """);
        assertThat(ConchDestinations.between(before, after).addedDestinations())
                .containsExactly("http://[::1]:9090");
    }

    @Test
    void authUseExchangeAndAllowHostsWideningAffectBoundary() {
        ConchDoc plain = http("https://app.example/", """
                - request: { method: GET, path: /ping }
                """);
        ConchDoc consumer = ConchParser.parse("""
                conch: demo_status
                version: 1.1.0
                driver: http
                min_crab_version: 1.13.0
                base_url: https://app.example/
                title: 标题
                description: 说明够长可以通过
                auth:
                  use: corp_sso
                  exchange:
                    - request: { method: POST, url: "https://sso.example/exchange" }
                actions:
                  ping:
                    title: 探测
                    description: 返回标记说明。
                    steps:
                      - request: { method: GET, path: /ping }
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 本地
                    related: []
                """);
        ConchDestinations.Boundary switchedToConsumer = ConchDestinations.between(plain, consumer);
        assertThat(switchedToConsumer.sameBoundary()).isFalse();
        assertThat(switchedToConsumer.authChanged()).isTrue();
        assertThat(switchedToConsumer.addedDestinations()).containsExactly("https://sso.example:443");

        ConchDoc switchedSso = ConchParser.parse("""
                conch: demo_status
                version: 1.2.0
                driver: http
                min_crab_version: 1.13.0
                base_url: https://app.example/
                title: 标题
                description: 说明够长可以通过
                auth:
                  use: other_sso
                  exchange:
                    - request: { method: POST, url: "https://sso.example/exchange" }
                actions:
                  ping:
                    title: 探测
                    description: 返回标记说明。
                    steps:
                      - request: { method: GET, path: /ping }
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 本地
                    related: []
                """);
        ConchDestinations.Boundary changedUse = ConchDestinations.between(consumer, switchedSso);
        assertThat(changedUse.sameBoundary()).isFalse();
        assertThat(changedUse.authChanged()).isTrue();

        ConchDoc restrictedAuth = authenticator("[\"https://oa.corp.example\"]");
        ConchDoc unrestrictedAuth = authenticator("[]");
        ConchDoc widerAuth = authenticator("[\"https://oa.corp.example\", \"https://hr.corp.example\"]");
        assertThat(ConchDestinations.between(restrictedAuth, unrestrictedAuth).sameBoundary()).isFalse();
        assertThat(ConchDestinations.between(restrictedAuth, unrestrictedAuth).authChanged()).isTrue();
        assertThat(ConchDestinations.between(unrestrictedAuth, restrictedAuth).sameBoundary()).isTrue();
        assertThat(ConchDestinations.between(restrictedAuth, widerAuth).sameBoundary()).isFalse();
        assertThat(ConchDestinations.between(widerAuth, restrictedAuth).sameBoundary()).isTrue();
    }

    private static ConchDoc authenticator(String allowHosts) {
        return ConchParser.parse("""
                conch: corp_sso
                version: 1.0.0
                driver: auth
                min_crab_version: 1.13.0
                title: 登录
                description: 统一登录组件说明
                credentials:
                  password: { type: secret, label: 密码 }
                auth:
                  origins: ["https://sso.corp.example"]
                  allow_hosts: %s
                  steps:
                    - request:
                        method: POST
                        url: https://sso.corp.example/login
                        form: { p: "{{credential.password}}" }
                  alive_check:
                    request: { method: GET, url: https://sso.corp.example/me }
                """.formatted(allowHosts));
    }

    private static ConchDoc http(String baseUrl, String steps) {
        return ConchParser.parse(httpYaml(baseUrl, steps, ""));
    }

    private static String httpYaml(String baseUrl, String steps, String extra) {
        return """
                conch: demo_status
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: %s
                title: 标题
                description: 说明
                credentials: {}
                %s
                actions:
                  ping:
                    title: 探测
                    description: 返回标记。
                    steps:
                %s
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 本地
                    related: []
                """.formatted(baseUrl, extra, indent(steps, 6));
    }

    private static ConchDoc mcp(String url, String authType, String tokenEndpoint) {
        String extra = "oauth".equals(authType) ? "\n    client_id: hermit" : "";
        if (tokenEndpoint != null) {
            extra += "\n    token_endpoint: " + tokenEndpoint;
        }
        return ConchParser.parse("""
                conch: demo_mcp
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 上游
                description: 本地。
                credentials: {}
                upstream:
                  url: %s
                  transport: streamable_http
                  auth:
                    type: %s%s
                actions:
                  search:
                    tool: search
                    title: 搜索
                    description: 查询。
                    input_schema: { type: object }
                """.formatted(url, authType, extra));
    }

    private static String indent(String text, int spaces) {
        String pad = " ".repeat(spaces);
        return pad + text.strip().replace("\n", "\n" + pad);
    }
}
