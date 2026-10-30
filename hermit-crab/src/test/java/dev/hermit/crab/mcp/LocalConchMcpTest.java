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
    void localToolsStayVisibleAndOnlyAnExplicitKeyCanCall() throws Exception {
        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp), CrabConfig.defaults(), name -> null)) {
            runtime.localConches().importBytes(
                    LocalConchBehaviorTest.ping("local_ping", "1.0.0").getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.registry().put(ConchParser.parse(LocalConchBehaviorTest.ping("official_ping", "1.0.0")));
            runtime.admin().createAccount("local_ping", "alice", Actors.CLI);
            CreatedApiKey openKey = runtime.admin().createApiKey("all", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
            CreatedApiKey namedKey = runtime.admin().createApiKey(
                    "named", new ApiKeyScope(Set.of("local_ping"), Set.of(), false), Actors.CLI);
            ApiKeyPrincipal openPrincipal = runtime.apiKeys().authenticate(openKey.plaintext()).orElseThrow();
            ApiKeyPrincipal namedPrincipal = runtime.apiKeys().authenticate(namedKey.plaintext()).orElseThrow();
            McpCatalog catalog = new McpCatalog(runtime.registry(), runtime::disabled);
            McpCalls calls = new McpCalls(runtime, catalog);

            McpSchema.Tool localTool = catalog.tools(openPrincipal, "2025-11-25").stream()
                    .filter(tool -> "local_ping__ping".equals(tool.name()))
                    .findFirst()
                    .orElseThrow();
            McpSchema.Tool officialTool = catalog.tools(openPrincipal, "2025-11-25").stream()
                    .filter(tool -> "official_ping__ping".equals(tool.name()))
                    .findFirst()
                    .orElseThrow();
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
            McpSchema.CallToolResult denied = calls.call(openPrincipal, null, Optional.empty(), params);
            McpSchema.CallToolResult allowed = calls.call(namedPrincipal, null, Optional.empty(), params);
            assertThat(denied.isError()).isTrue();
            assertThat(String.valueOf(denied.structuredContent())).contains("FORBIDDEN");
            assertThat(allowed.isError()).isFalse();
        }
    }
}
