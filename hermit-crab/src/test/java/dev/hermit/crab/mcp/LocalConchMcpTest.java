package dev.hermit.crab.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.sync.LocalConchBehaviorTest;
import io.modelcontextprotocol.spec.McpSchema;

class LocalConchMcpTest {

    @TempDir
    Path temp;

    @Test
    void fullScopeKeyListsAndCallsLocalTools() throws Exception {
        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp), CrabConfig.defaults(), name -> null)) {
            runtime.localConches().importBytes(
                    LocalConchBehaviorTest.ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.localConches().importBytes(
                    LocalConchBehaviorTest.save("local_save", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.registry().put(ConchParser.parse(LocalConchBehaviorTest.ping("official_ping", "1.0.0")));
            runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            runtime.admin().createAccount("local_save", "alice", Actors.CLI);
            CreatedApiKey openKey = runtime.admin().createApiKey("all", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
            CreatedApiKey explicitKey = runtime.admin().createApiKey(
                    "explicit", ApiKeyScope.explicit(true, Set.of(), Set.of(), false), Actors.CLI);
            CreatedApiKey readOnlyKey = runtime.admin().createApiKey(
                    "reader", ApiKeyScope.explicit(true, Set.of(), Set.of(), true), Actors.CLI);
            CreatedApiKey namedKey = runtime.admin().createApiKey(
                    "named", new ApiKeyScope(Set.of("local_ping"), Set.of(), false), Actors.CLI);
            CreatedApiKey narrowKey = runtime.admin().createApiKey(
                    "narrow", new ApiKeyScope(Set.of("official_ping"), Set.of(), false), Actors.CLI);
            CreatedApiKey actionKey = runtime.admin().createApiKey(
                    "action", new ApiKeyScope(Set.of(), Set.of("local_ping.ping"), false), Actors.CLI);
            ApiKeyPrincipal openPrincipal = runtime.apiKeys().authenticate(openKey.plaintext()).orElseThrow();
            ApiKeyPrincipal explicitPrincipal = runtime.apiKeys().authenticate(explicitKey.plaintext()).orElseThrow();
            ApiKeyPrincipal readOnlyPrincipal = runtime.apiKeys().authenticate(readOnlyKey.plaintext()).orElseThrow();
            ApiKeyPrincipal namedPrincipal = runtime.apiKeys().authenticate(namedKey.plaintext()).orElseThrow();
            ApiKeyPrincipal narrowPrincipal = runtime.apiKeys().authenticate(narrowKey.plaintext()).orElseThrow();
            ApiKeyPrincipal actionPrincipal = runtime.apiKeys().authenticate(actionKey.plaintext()).orElseThrow();
            McpCatalog catalog = new McpCatalog(runtime.registry(), runtime::disabled);
            McpCalls calls = new McpCalls(runtime, catalog);

            McpSchema.Tool localTool = tool(catalog, openPrincipal, "local_ping__ping");
            McpSchema.Tool officialTool = tool(catalog, openPrincipal, "official_ping__ping");
            assertThat(tool(catalog, openPrincipal, "local_save__save")).isNotNull();
            assertThat(localTool.description()).startsWith("[本地] ");
            assertThat(officialTool.description()).doesNotContain("[本地]");
            assertThat(catalog.resources(openPrincipal).stream()
                    .filter(resource -> resource.uri().contains("local_ping"))
                    .findFirst()
                    .orElseThrow()
                    .description()).startsWith("[本地] ");
            assertThat(String.valueOf(catalog.listSystems(openPrincipal).get("systems"))).contains("[本地] ");
            assertThat(catalog.describe(openPrincipal, "local_ping__ping").orElseThrow().get("description").toString())
                    .startsWith("[本地] ");
            assertThat(catalog.guide(openPrincipal, "hermit://conches/local_ping/guide").orElseThrow())
                    .contains("来源：本地规则包");

            JsonNode params = ApiJson.tree("{\"name\":\"local_ping__ping\",\"arguments\":{}}");
            assertThat(calls.call(openPrincipal, null, Optional.empty(), params).isError()).isFalse();
            assertThat(calls.call(explicitPrincipal, null, Optional.empty(), params).isError()).isFalse();
            assertThat(calls.call(namedPrincipal, null, Optional.empty(), params).isError()).isFalse();
            assertThat(calls.call(readOnlyPrincipal, null, Optional.empty(), params).isError()).isFalse();

            assertThat(names(catalog, readOnlyPrincipal)).contains("local_ping__ping").doesNotContain("local_save__save");
            JsonNode write = ApiJson.tree("{\"name\":\"local_save__save\",\"arguments\":{}}");
            McpSchema.CallToolResult readOnlyWrite = calls.call(readOnlyPrincipal, null, Optional.empty(), write);
            assertThat(readOnlyWrite.isError()).isTrue();
            assertThat(String.valueOf(readOnlyWrite.structuredContent())).contains("没有这个工具");

            assertThat(names(catalog, narrowPrincipal)).contains("official_ping__ping").doesNotContain("local_ping__ping");
            assertThat(names(catalog, actionPrincipal)).doesNotContain("local_ping__ping", "local_save__save");
            assertThat(catalog.describe(narrowPrincipal, "local_ping__ping")).isEmpty();
            McpSchema.CallToolResult hidden = calls.call(narrowPrincipal, null, Optional.empty(), params);
            assertThat(hidden.isError()).isTrue();
            assertThat(String.valueOf(hidden.structuredContent())).contains("没有这个工具");
        }
    }

    private static McpSchema.Tool tool(McpCatalog catalog, ApiKeyPrincipal principal, String name) {
        return catalog.tools(principal, "2025-11-25").stream()
                .filter(tool -> name.equals(tool.name()))
                .findFirst()
                .orElseThrow();
    }

    private static java.util.List<String> names(McpCatalog catalog, ApiKeyPrincipal principal) {
        return catalog.tools(principal, "2025-11-25").stream().map(McpSchema.Tool::name).toList();
    }
}
