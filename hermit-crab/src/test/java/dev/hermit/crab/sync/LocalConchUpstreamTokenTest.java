package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.upstream.McpOAuthToken;

class LocalConchUpstreamTokenTest {

    private static final String SHORT = "将覆盖已有的本地规则包 %s。确认后才会写入。";

    @TempDir
    Path temp;

    @Test
    void sameUpstreamOverwriteKeepsOauthToken() throws Exception {
        try (CrabRuntime runtime = open("same")) {
            importNew(runtime, oauth("vendor_mcp", "1.0.0", "https://old.example/mcp",
                    "https://old.example/oauth/token", null));
            runtime.admin().createAccount("vendor_mcp", "alice", Actors.CLI);
            plant(runtime, "vendor_mcp", "alice");
            importNew(runtime, oauth("other_mcp", "1.0.0", "https://other.example/mcp",
                    "https://other.example/oauth/token", null));
            runtime.admin().createAccount("other_mcp", "carol", Actors.CLI);
            plant(runtime, "other_mcp", "carol");

            ConchDoc before = runtime.registry().find("vendor_mcp").orElseThrow();
            String same = oauth("vendor_mcp", "1.0.1", "https://OLD.Example:443/mcp/v2",
                    "https://old.example/oauth/token2", null);
            assertThat(LocalConches.overwriteConfirm("vendor_mcp", before, ConchParser.parse(same)))
                    .isEqualTo(SHORT.formatted("vendor_mcp"));
            replace(runtime, same);

            assertThat(runtime.admin().credentialNames("vendor_mcp", "alice"))
                    .containsExactlyInAnyOrder(McpOAuthToken.FIELD, McpOAuthToken.CLIENT_SECRET, "password");
            assertThat(runtime.admin().credentialNames("other_mcp", "carol"))
                    .containsExactlyInAnyOrder(McpOAuthToken.FIELD, McpOAuthToken.CLIENT_SECRET, "password");
        }
    }

    @Test
    void anotherHostDropsOauthTokenForEveryAccount() throws Exception {
        try (CrabRuntime runtime = open("host")) {
            importNew(runtime, oauth("vendor_mcp", "1.0.0", "https://old.example/mcp",
                    "https://old.example/oauth/token", null));
            runtime.admin().createAccount("vendor_mcp", "alice", Actors.CLI);
            runtime.admin().createAccount("vendor_mcp", "bob", Actors.CLI);
            plant(runtime, "vendor_mcp", "alice");
            plant(runtime, "vendor_mcp", "bob");
            importNew(runtime, oauth("other_mcp", "1.0.0", "https://other.example/mcp",
                    "https://other.example/oauth/token", null));
            runtime.admin().createAccount("other_mcp", "carol", Actors.CLI);
            plant(runtime, "other_mcp", "carol");

            ConchDoc before = runtime.registry().find("vendor_mcp").orElseThrow();
            String moved = oauth("vendor_mcp", "1.1.0", "https://NEW.Example/mcp",
                    "https://NEW.Example/oauth/token", null);
            assertThat(LocalConches.overwriteConfirm("vendor_mcp", before, ConchParser.parse(moved)))
                    .isEqualTo("将覆盖已有的本地规则包 vendor_mcp。新增地址：https://new.example:443。"
                            + "上游主机将从 old.example 改为 new.example，"
                            + "确认后会清掉已保存的上游令牌。确认后才会写入。");
            replace(runtime, moved);

            assertThat(runtime.admin().credentialNames("vendor_mcp", "alice")).containsExactly("password");
            assertThat(runtime.admin().credentialNames("vendor_mcp", "bob")).containsExactly("password");
            assertThat(runtime.admin().listAccounts("vendor_mcp"))
                    .extracting(account -> account.account())
                    .containsExactlyInAnyOrder("alice", "bob");
            assertThat(runtime.admin().credentialNames("other_mcp", "carol"))
                    .containsExactlyInAnyOrder(McpOAuthToken.FIELD, McpOAuthToken.CLIENT_SECRET, "password");
        }
    }

    @Test
    void tokenHostPortAndAuthTypeDropTokensWhenTheUrlHostStays() throws Exception {
        try (CrabRuntime runtime = open("identity")) {
            importNew(runtime, oauth("port_mcp", "1.0.0", "https://old.example/mcp",
                    "https://old.example/oauth/token", null));
            runtime.admin().createAccount("port_mcp", "alice", Actors.CLI);
            plant(runtime, "port_mcp", "alice");
            ConchDoc portBefore = runtime.registry().find("port_mcp").orElseThrow();
            String port = oauth("port_mcp", "1.0.1", "https://old.example:8443/mcp",
                    "https://old.example/oauth/token", null);
            assertThat(LocalConches.overwriteConfirm("port_mcp", portBefore, ConchParser.parse(port)))
                    .isEqualTo("将覆盖已有的本地规则包 port_mcp。新增地址：https://old.example:8443。确认后才会写入。");
            replace(runtime, port);
            assertThat(runtime.admin().credentialNames("port_mcp", "alice")).containsExactly("password");

            String allowed = "\"old.example\", \"auth.example\"";
            importNew(runtime, oauth("token_mcp", "1.0.0", "https://old.example/mcp",
                    "https://old.example/oauth/token", allowed));
            runtime.admin().createAccount("token_mcp", "alice", Actors.CLI);
            plant(runtime, "token_mcp", "alice");
            ConchDoc tokenBefore = runtime.registry().find("token_mcp").orElseThrow();
            String tokenHost = oauth("token_mcp", "1.0.1", "https://old.example/mcp",
                    "https://auth.example/oauth/token", allowed);
            assertThat(LocalConches.overwriteConfirm("token_mcp", tokenBefore, ConchParser.parse(tokenHost)))
                    .isEqualTo("将覆盖已有的本地规则包 token_mcp。新增地址：https://auth.example:443。"
                            + "令牌端点主机将从 old.example 改为 auth.example，"
                            + "确认后会清掉已保存的上游令牌。确认后才会写入。");
            replace(runtime, tokenHost);
            assertThat(runtime.admin().credentialNames("token_mcp", "alice")).containsExactly("password");

            importNew(runtime, oauth("auth_mcp", "1.0.0", "https://old.example/mcp", null, null));
            runtime.admin().createAccount("auth_mcp", "alice", Actors.CLI);
            plant(runtime, "auth_mcp", "alice");
            ConchDoc authBefore = runtime.registry().find("auth_mcp").orElseThrow();
            String headers = headers("auth_mcp", "1.0.1", "https://old.example/mcp");
            assertThat(LocalConches.overwriteConfirm("auth_mcp", authBefore, ConchParser.parse(headers)))
                    .isEqualTo("将覆盖已有的本地规则包 auth_mcp。认证方式有变化。确认后才会写入。");
            replace(runtime, headers);
            assertThat(runtime.admin().credentialNames("auth_mcp", "alice")).containsExactly("password");
        }
    }

    private CrabRuntime open(String name) {
        return CrabRuntime.open(new CrabPaths(temp.resolve(name)), CrabConfig.defaults(), key -> null);
    }

    private static void importNew(CrabRuntime runtime, String yaml) {
        try {
            runtime.localConches().importBytes(yaml.getBytes(StandardCharsets.UTF_8), Actors.CLI);
        } catch (LocalConchException ex) {
            throw new AssertionError(ex.getMessage() + " " + ex.errors(), ex);
        }
    }

    private static void replace(CrabRuntime runtime, String yaml) {
        try {
            runtime.localConches().importBytes(yaml.getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
        } catch (LocalConchException ex) {
            throw new AssertionError(ex.getMessage() + " " + ex.errors(), ex);
        }
    }

    private static void plant(CrabRuntime runtime, String conch, String account) throws Exception {
        ConchDoc doc = runtime.registry().find(conch).orElseThrow();
        runtime.admin().writeCredentials(doc, account, java.util.Map.of(
                "password", account + "-password-value",
                "client_secret", account + "-client-secret"), Actors.CLI);
        try (Connection connection = runtime.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO credentials (origin, conch, account, field, ciphertext, updated_at)
                     VALUES ('LOCAL', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                     """)) {
            statement.setString(1, conch);
            statement.setString(2, account);
            statement.setString(3, McpOAuthToken.FIELD);
            statement.setBytes(4, new byte[] {1, 2, 3, 4});
            statement.executeUpdate();
        }
    }

    private static String oauth(
            String conch,
            String version,
            String url,
            String tokenEndpoint,
            String allowedHosts
    ) {
        StringBuilder upstream = new StringBuilder();
        upstream.append("  url: ").append(url).append('\n');
        upstream.append("  transport: streamable_http\n");
        if (allowedHosts != null) {
            upstream.append("  allowed_hosts: [").append(allowedHosts).append("]\n");
        }
        upstream.append("  auth:\n");
        upstream.append("    type: oauth\n");
        upstream.append("    client_id: hermit\n");
        if (tokenEndpoint != null) {
            upstream.append("    token_endpoint: ").append(tokenEndpoint).append('\n');
        }
        return """
                conch: %s
                version: %s
                driver: mcp
                min_crab_version: 1.1.0
                title: 上游
                description: 本地开发包。
                credentials:
                  client_secret: {type: secret, label: 客户端机密, required: true}
                  password: {type: secret, label: 密码, required: true}
                upstream:
                %sactions:
                  search:
                    tool: search
                    title: 搜索
                    description: 查询。
                    mcp: {read_only: true, destructive: false}
                    input_schema: {type: object}
                """.formatted(conch, version, upstream);
    }

    private static String headers(String conch, String version, String url) {
        return """
                conch: %s
                version: %s
                driver: mcp
                min_crab_version: 1.1.0
                title: 上游
                description: 本地开发包。
                credentials:
                  client_secret: {type: secret, label: 客户端机密, required: true}
                  password: {type: secret, label: 密码, required: true}
                upstream:
                  url: %s
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  search:
                    tool: search
                    title: 搜索
                    description: 查询。
                    mcp: {read_only: true, destructive: false}
                    input_schema: {type: object}
                """.formatted(conch, version, url);
    }
}
