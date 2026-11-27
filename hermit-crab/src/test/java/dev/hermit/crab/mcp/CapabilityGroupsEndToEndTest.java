package dev.hermit.crab.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.cli.CrabCommand;
import dev.hermit.crab.config.ConfigFileException;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.config.CrabConfigLoader;
import dev.hermit.crab.gateway.CrabHttpServer;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.upstream.McpToolMerge;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityGroupsEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path temp;

    private static String actionYaml(String name, String group, String title, boolean readOnly) {
        String groupLine = group == null ? "" : "    group: " + group + "\n";
        return """
                  %s:
                %s    title: %s
                    description: 说明 %s
                    method: GET
                    idempotent: true
                    mcp: {expose: true, read_only: %s}
                    steps:
                      - set: {called: "%s"}
                    output:
                      called: {type: string, description: 执行的能力}
                    examples:
                      - input: {}
                        output: {called: "%s"}
                    notes: 备注 %s
                    related: []
                """.formatted(name, groupLine, title, title, readOnly, name, name, name);
    }

    private static String groupedConchYaml(String conchName, int extraUngrouped) {
        StringBuilder actions = new StringBuilder();
        actions.append(actionYaml("get_me", null, "获取当前用户", true));
        for (int i = 1; i <= extraUngrouped; i++) {
            actions.append(actionYaml("top_" + i, null, "顶层能力 " + i, true));
        }
        actions.append(actionYaml("list_all_todos", "approval", "查询全部待办", true));
        actions.append(actionYaml("list_leave", "approval/leave", "查询请假待办", true));
        actions.append(actionYaml("approve_leave", "approval/leave", "审批请假单", false));
        actions.append(actionYaml("list_expense", "approval/expense", "查询报销待办", true));
        actions.append(actionYaml("get_employee", "hr", "查询员工信息", true));
        return """
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.10.0
                base_url: http://127.0.0.1:9
                title: 办公系统
                description: 带一二级分组的示例规则包
                credentials: {}
                groups:
                  approval:
                    title: 审批管理
                    description: 待办与各类审批流程
                    children:
                      leave:
                        title: 请假审批
                        description: 请假单查询与审批
                      expense:
                        title: 报销审批
                        description: 费用报销处理
                  hr:
                    title: 人事档案
                    description: 员工信息查询
                actions:
                %s
                """.formatted(conchName, actions);
    }

    private static String flatConchYaml(String conchName) {
        return """
                conch: %s
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                base_url: http://127.0.0.1:9
                title: 老规则包
                description: 无分组平铺包
                credentials: {}
                actions:
                %s
                """.formatted(conchName, actionYaml("ping_old", null, "健康探测", true));
    }

    @Test
    void loadsMcpDirectLimitFromConfigAndRejectsOutOfRange() throws Exception {
        Path cfg = temp.resolve("config.yml");
        Files.writeString(cfg, """
                mcp:
                  direct_limit: 0
                """, StandardCharsets.UTF_8);
        assertThat(CrabConfigLoader.load(cfg).mcpDirectLimit()).isEqualTo(0);

        Path unset = temp.resolve("unset.yml");
        Files.writeString(unset, """
                server:
                  port: 7777
                """, StandardCharsets.UTF_8);
        assertThat(CrabConfigLoader.load(unset).mcpDirectLimit()).as("默认只给目录工具").isEqualTo(0);

        Path direct = temp.resolve("direct.yml");
        Files.writeString(direct, """
                mcp:
                  direct_limit: 40
                """, StandardCharsets.UTF_8);
        assertThat(CrabConfigLoader.load(direct).mcpDirectLimit()).isEqualTo(40);

        Path invalid = temp.resolve("bad.yml");
        Files.writeString(invalid, """
                mcp:
                  direct_limit: -1
                """, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CrabConfigLoader.load(invalid))
                .isInstanceOf(ConfigFileException.class)
                .hasMessageContaining("mcp.direct_limit");
    }

    @Test
    void progressiveMcpDirectoryGroupGuidesSwitchesOpenApiAndApiKeyScopes() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("crab-home"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(0);
        try (CrabRuntime runtime = CrabRuntime.open(paths, config);
             CrabHttpServer server = new CrabHttpServer(runtime)) {
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.port();

            runtime.registry().put(ConchParser.parse(groupedConchYaml("sample_oa", 0)), Origin.OFFICIAL);
            runtime.registry().put(ConchParser.parse(flatConchYaml("old_flat")), Origin.OFFICIAL);
            runtime.admin().createAccount(Origin.OFFICIAL, "sample_oa", "main", Actors.CLI);
            runtime.admin().createAccount(Origin.OFFICIAL, "old_flat", "main", Actors.CLI);

            CreatedApiKey fullKey = runtime.admin().createApiKey(
                    "full", ApiKeyScope.explicit(true, Set.of(), Set.of(), Set.of(), false), Actors.CLI);

            // 1) MCP direct_limit=0 forces directory mode even with <40 capabilities
            String session = mcpInit(baseUrl, fullKey.plaintext());
            JsonNode tools = mcpPost(baseUrl, fullKey.plaintext(), session, 2, "tools/list", null)
                    .path("result").path("tools");
            assertThat(tools).hasSize(4);

            // 2) list_systems returns 1st/2nd level group tree + inline topLevelCapabilities (<=10)
            //    and omits groups on old flat package
            JsonNode systems = mcpCall(baseUrl, fullKey.plaintext(), session, 3, "list_systems", Map.of())
                    .path("result").path("structuredContent").path("systems");
            JsonNode oaSys = findByField(systems, "conch", "sample_oa");
            JsonNode flatSys = findByField(systems, "conch", "old_flat");
            assertThat(oaSys.path("capabilities").asInt()).isEqualTo(6);
            assertThat(oaSys.path("groups")).hasSize(2);
            assertThat(oaSys.path("groups").get(0).path("group").asText()).isEqualTo("approval");
            assertThat(oaSys.path("groups").get(0).path("capabilities").asInt()).isEqualTo(4);
            assertThat(oaSys.path("groups").get(0).path("children")).hasSize(2);
            assertThat(oaSys.path("groups").get(0).path("children").get(0).path("group").asText())
                    .isEqualTo("approval/leave");
            assertThat(oaSys.path("ungroupedCount").asInt()).isEqualTo(1);
            assertThat(oaSys.path("topLevelCapabilities")).hasSize(1);
            assertThat(oaSys.path("topLevelCapabilities").get(0).path("name").asText()).isEqualTo("sample_oa__get_me");
            assertThat(flatSys.has("groups")).isFalse();
            // 没有分组的包同样附上能力名和标题，调用方不用再猜关键词。
            assertThat(flatSys.has("ungroupedCount")).isFalse();
            assertThat(flatSys.path("topLevelCapabilities")).hasSize(flatSys.path("capabilities").asInt());
            assertThat(flatSys.path("topLevelCapabilities").get(0).path("name").asText()).startsWith("old_flat__");
            assertThat(flatSys.path("topLevelCapabilities").get(0).has("readOnly")).isTrue();

            // 3) search_capabilities: browse by 1st-level group, 2nd-level group, ungrouped, and group-title query
            JsonNode byParent = mcpCall(baseUrl, fullKey.plaintext(), session, 4, "search_capabilities",
                    Map.of("conch", "sample_oa", "group", "approval"))
                    .path("result").path("structuredContent");
            assertThat(byParent.path("total").asInt()).isEqualTo(4);
            assertThat(byParent.path("matches")).hasSize(4);

            JsonNode byChild = mcpCall(baseUrl, fullKey.plaintext(), session, 5, "search_capabilities",
                    Map.of("conch", "sample_oa", "group", "approval/leave"))
                    .path("result").path("structuredContent");
            assertThat(byChild.path("total").asInt()).isEqualTo(2);
            assertThat(byChild.path("matches").get(0).path("groupTitle").asText()).isEqualTo("审批管理 / 请假审批");

            JsonNode byUngrouped = mcpCall(baseUrl, fullKey.plaintext(), session, 6, "search_capabilities",
                    Map.of("conch", "sample_oa", "ungrouped", true))
                    .path("result").path("structuredContent");
            assertThat(byUngrouped.path("total").asInt()).isEqualTo(1);
            assertThat(byUngrouped.path("matches").get(0).path("name").asText()).isEqualTo("sample_oa__get_me");

            JsonNode byGroupTitle = mcpCall(baseUrl, fullKey.plaintext(), session, 7, "search_capabilities",
                    Map.of("query", "费用报销处理"))
                    .path("result").path("structuredContent");
            assertThat(byGroupTitle.path("matches")).hasSize(1);
            assertThat(byGroupTitle.path("matches").get(0).path("name").asText()).isEqualTo("sample_oa__list_expense");

            JsonNode described = mcpCall(baseUrl, fullKey.plaintext(), session, 8, "describe_capability",
                    Map.of("name", "sample_oa__list_leave"))
                    .path("result").path("structuredContent");
            assertThat(described.path("group").asText()).isEqualTo("approval/leave");
            assertThat(described.path("groupTitle").asText()).isEqualTo("审批管理 / 请假审批");

            // 4) Resources: overview + per-group guides
            JsonNode resources = mcpPost(baseUrl, fullKey.plaintext(), session, 9, "resources/list", null)
                    .path("result").path("resources");
            assertThat(resources.toString()).contains("hermit://conches/sample_oa/guide");
            assertThat(resources.toString()).contains("hermit://conches/sample_oa/groups/approval/guide");
            assertThat(resources.toString()).contains("hermit://conches/sample_oa/groups/approval/leave/guide");
            assertThat(resources.toString()).contains("hermit://conches/sample_oa/groups/hr/guide");

            String overviewGuide = mcpPost(baseUrl, fullKey.plaintext(), session, 10, "resources/read",
                    Map.of("uri", "hermit://conches/sample_oa/guide"))
                    .path("result").path("contents").get(0).path("text").asText();
            assertThat(overviewGuide).contains("## 分组目录").contains("hermit://conches/sample_oa/groups/approval/guide");
            assertThat(overviewGuide).contains("sample_oa__get_me");
            assertThat(overviewGuide).doesNotContain("### sample_oa__list_leave");

            String leaveGuide = mcpPost(baseUrl, fullKey.plaintext(), session, 11, "resources/read",
                    Map.of("uri", "hermit://conches/sample_oa/groups/approval/leave/guide"))
                    .path("result").path("contents").get(0).path("text").asText();
            assertThat(leaveGuide).contains("sample_oa__list_leave").doesNotContain("sample_oa__get_employee");

            // 5) OpenAPI tags
            JsonNode openApi = httpGet(baseUrl + "/openapi.json", Map.of()).bodyJson();
            assertThat(openApi.path("paths").path("/api/sample_oa/list_leave").path("get").path("tags").get(0).asText())
                    .isEqualTo("审批管理 / 请假审批");
            assertThat(openApi.path("paths").path("/api/sample_oa/get_me").path("get").has("tags")).isFalse();
            assertThat(openApi.path("tags").toString()).contains("审批管理 / 请假审批");

            // 6) Console API: create API key with group + extra top-level action, enforce on HTTP & MCP
            HttpResult createdKeyRes = consolePost(baseUrl + "/console/api/apikeys", Map.of(
                    "name", "approval-bot",
                    "readOnly", true,
                    "all", false,
                    "conches", List.of("sample_oa"),
                    "groups", List.of("sample_oa:approval"),
                    "actions", List.of("sample_oa.get_me")));
            assertThat(createdKeyRes.status()).isEqualTo(200);
            String groupKey = createdKeyRes.bodyJson().path("data").path("plaintext").asText();
            assertThat(createdKeyRes.bodyJson().path("data").path("scope").path("groups").get(0).asText())
                    .isEqualTo("sample_oa:approval");

            // Allowed: approval direct, approval/leave child (readOnly), and top-level get_me
            assertThat(httpGet(baseUrl + "/api/sample_oa/list_all_todos", Map.of("Authorization", "Bearer " + groupKey)).status())
                    .isEqualTo(200);
            assertThat(httpGet(baseUrl + "/api/sample_oa/list_leave", Map.of("Authorization", "Bearer " + groupKey)).status())
                    .isEqualTo(200);
            assertThat(httpGet(baseUrl + "/api/sample_oa/get_me", Map.of("Authorization", "Bearer " + groupKey)).status())
                    .isEqualTo(200);
            // Rejected: write action in approval/leave (readOnly=true) & hr group
            assertThat(httpGet(baseUrl + "/api/sample_oa/approve_leave", Map.of("Authorization", "Bearer " + groupKey)).status())
                    .isEqualTo(403);
            assertThat(httpGet(baseUrl + "/api/sample_oa/get_employee", Map.of("Authorization", "Bearer " + groupKey)).status())
                    .isEqualTo(403);

            // Group-scoped key cannot read unauthorized hr group guide
            String scopedSession = mcpInit(baseUrl, groupKey);
            JsonNode hrGuideErr = mcpPost(baseUrl, groupKey, scopedSession, 2, "resources/read",
                    Map.of("uri", "hermit://conches/sample_oa/groups/hr/guide"));
            assertThat(hrGuideErr.has("error")).isTrue();

            // Invalid group scope rejected at key creation
            HttpResult badScope = consolePost(baseUrl + "/console/api/apikeys", Map.of(
                    "name", "bad",
                    "readOnly", true,
                    "all", false,
                    "conches", List.of(),
                    "groups", List.of("sample_oa:nonexistent"),
                    "actions", List.of()));
            assertThat(badScope.status()).isEqualTo(400);

            // 7) Console API: group switches (1st-level disable, 2nd-level re-enable, 404 unknown group)
            HttpResult disableApproval = consolePost(baseUrl + "/console/api/conches/sample_oa/groups/disable",
                    Map.of("group", "approval"));
            assertThat(disableApproval.status()).isEqualTo(200);
            assertThat(disableApproval.bodyJson().path("data").path("actions")).hasSize(4);
            assertThat(httpGet(baseUrl + "/api/sample_oa/list_leave", Map.of("Authorization", "Bearer " + fullKey.plaintext())).status())
                    .isEqualTo(404);
            assertThat(httpGet(baseUrl + "/api/sample_oa/get_employee", Map.of("Authorization", "Bearer " + fullKey.plaintext())).status())
                    .isEqualTo(200);

            HttpResult enableLeave = consolePost(baseUrl + "/console/api/conches/sample_oa/groups/enable",
                    Map.of("group", "approval/leave"));
            assertThat(enableLeave.status()).isEqualTo(200);
            assertThat(httpGet(baseUrl + "/api/sample_oa/list_leave", Map.of("Authorization", "Bearer " + fullKey.plaintext())).status())
                    .isEqualTo(200);
            assertThat(httpGet(baseUrl + "/api/sample_oa/list_expense", Map.of("Authorization", "Bearer " + fullKey.plaintext())).status())
                    .isEqualTo(404);

            HttpResult missingGroup = consolePost(baseUrl + "/console/api/conches/sample_oa/groups/disable",
                    Map.of("group", "nope"));
            assertThat(missingGroup.status()).isEqualTo(404);
            assertThat(missingGroup.bodyJson().path("error").path("message").asText()).isEqualTo("分组不存在");

            // 8) Local conch still requires package name in conches even when groups is set
            runtime.registry().put(ConchParser.parse(groupedConchYaml("local_oa", 0)), Origin.LOCAL);
            runtime.admin().createAccount(Origin.LOCAL, "local_oa", "main", Actors.CLI);
            CreatedApiKey localGroupOnly = runtime.admin().createApiKey(
                    "local-group-only",
                    ApiKeyScope.explicit(false, Set.of(), Set.of("local_oa:hr"), Set.of(), true),
                    Actors.CLI);
            assertThat(httpGet(baseUrl + "/api/local_oa/get_employee",
                    Map.of("Authorization", "Bearer " + localGroupOnly.plaintext())).status()).isEqualTo(403);
            CreatedApiKey localNamed = runtime.admin().createApiKey(
                    "local-named",
                    ApiKeyScope.explicit(false, Set.of("local_oa"), Set.of("local_oa:hr"), Set.of(), true),
                    Actors.CLI);
            assertThat(httpGet(baseUrl + "/api/local_oa/get_employee",
                    Map.of("Authorization", "Bearer " + localNamed.plaintext())).status()).isEqualTo(200);
            assertThat(httpGet(baseUrl + "/api/local_oa/list_leave",
                    Map.of("Authorization", "Bearer " + localNamed.plaintext())).status()).isEqualTo(403);
        }
    }

    @Test
    void largeUngroupedCountDoesNotInlineIntoListSystems() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("crab-large"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(0);
        try (CrabRuntime runtime = CrabRuntime.open(paths, config);
             CrabHttpServer server = new CrabHttpServer(runtime)) {
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.port();
            // 1 + 12 = 13 ungrouped actions (> 10 inline cap)
            runtime.registry().put(ConchParser.parse(groupedConchYaml("big_oa", 12)), Origin.OFFICIAL);
            CreatedApiKey key = runtime.admin().createApiKey(
                    "full", ApiKeyScope.explicit(true, Set.of(), Set.of(), Set.of(), false), Actors.CLI);
            String session = mcpInit(baseUrl, key.plaintext());
            JsonNode systems = mcpCall(baseUrl, key.plaintext(), session, 2, "list_systems", Map.of())
                    .path("result").path("structuredContent").path("systems");
            JsonNode sys = systems.get(0);
            assertThat(sys.path("ungroupedCount").asInt()).isEqualTo(13);
            assertThat(sys.has("topLevelCapabilities")).isFalse();
        }
    }

    @Test
    void cliSupportsGroupsFlagAndMcpRefreshPreservesGroups() throws Exception {
        Path home = temp.resolve("cli-home");
        Files.createDirectories(home.resolve("local-conches"));
        Files.writeString(home.resolve("local-conches/local_oa.yaml"), groupedConchYaml("local_oa", 0), StandardCharsets.UTF_8);

        Map<String, String> env = Map.of("HERMIT_HOME", home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CrabCommand().run(
                new String[]{"apikey", "create", "grp-key", "--conches", "local_oa", "--groups", "local_oa:approval", "--read-only"},
                env::get, new ByteArrayInputStream(new byte[0]), new PrintStream(out), new PrintStream(err));
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isEqualTo(0);

        out.reset();
        err.reset();
        int listCode = new CrabCommand().run(
                new String[]{"apikey", "list"},
                env::get, new ByteArrayInputStream(new byte[0]), new PrintStream(out), new PrintStream(err));
        assertThat(listCode).isEqualTo(0);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("groups=local_oa:approval");

        // McpToolMerge preserves groups and action.group during refresh
        String mcpYaml = """
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.10.0
                title: 客户系统
                description: MCP
                credentials: {}
                upstream:
                  url: https://crm.intra.example.com/mcp
                  transport: streamable_http
                  auth: {type: headers}
                groups:
                  customer:
                    title: 客户管理
                actions:
                  search_customer:
                    group: customer
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称搜索
                    input_schema: {type: object}
                """;
        JsonNode upstreamTool = JSON.readTree("""
                {"name":"searchCustomer","description":"updated","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}}}
                """);
        byte[] rewritten = McpToolMerge.rewrite(
                mcpYaml.getBytes(StandardCharsets.UTF_8),
                List.of(upstreamTool),
                "2025-11-25",
                "crm",
                "1.0.0",
                Instant.parse("2026-11-22T00:00:00Z"));
        ConchDoc parsed = ConchParser.parse(new String(rewritten, StandardCharsets.UTF_8));
        assertThat(parsed.groups()).containsKey("customer");
        assertThat(parsed.actions().get("search_customer").group()).isEqualTo("customer");
    }

    @Test
    void malformedGuideUriReturnsResourceNotFoundInsteadOfInternalError() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("crab-malformed-uri"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(40);
        try (CrabRuntime runtime = CrabRuntime.open(paths, config);
             CrabHttpServer server = new CrabHttpServer(runtime)) {
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.port();
            runtime.registry().put(ConchParser.parse(groupedConchYaml("sample_oa", 0)), Origin.OFFICIAL);
            CreatedApiKey key = runtime.admin().createApiKey(
                    "full", ApiKeyScope.explicit(true, Set.of(), Set.of(), Set.of(), false), Actors.CLI);
            String session = mcpInit(baseUrl, key.plaintext());

            for (String badUri : List.of("hermit://conches/guide", "hermit://conches//guide")) {
                JsonNode reply = mcpPost(baseUrl, key.plaintext(), session, 2, "resources/read",
                        Map.of("uri", badUri));
                assertThat(reply.has("error")).as(badUri + " -> " + reply).isTrue();
                assertThat(reply.path("error").path("code").asInt())
                        .as(badUri)
                        .isEqualTo(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND);
                assertThat(reply.path("error").path("code").asInt())
                        .isNotEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
                assertThat(reply.path("error").path("data").path("code").asText()).isEqualTo(McpErrors.NOT_FOUND);
            }
        }
    }

    @Test
    void defaultConfigExposesDirectoryToolsEvenWithZeroVisibleCapabilities() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("crab-zero-limit"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        try (CrabRuntime runtime = CrabRuntime.open(paths, config);
             CrabHttpServer server = new CrabHttpServer(runtime)) {
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.port();
            CreatedApiKey key = runtime.admin().createApiKey(
                    "full", ApiKeyScope.explicit(true, Set.of(), Set.of(), Set.of(), false), Actors.CLI);
            String session = mcpInit(baseUrl, key.plaintext());

            JsonNode tools = mcpPost(baseUrl, key.plaintext(), session, 2, "tools/list", null)
                    .path("result").path("tools");
            assertThat(tools).hasSize(4);
            assertThat(List.of(
                    tools.get(0).path("name").asText(),
                    tools.get(1).path("name").asText(),
                    tools.get(2).path("name").asText(),
                    tools.get(3).path("name").asText()))
                    .containsExactlyInAnyOrder(
                            McpCatalog.LIST_SYSTEMS,
                            McpCatalog.SEARCH,
                            McpCatalog.DESCRIBE,
                            McpCatalog.CALL);

            JsonNode systems = mcpCall(baseUrl, key.plaintext(), session, 3, McpCatalog.LIST_SYSTEMS, Map.of());
            assertThat(systems.path("result").path("isError").asBoolean(true)).as(systems.toString()).isFalse();
            assertThat(systems.path("result").path("structuredContent").path("systems")).isEmpty();
        }
    }

    @Test
    void directModeGroupedGuideListsAllTopLevelAndOmitsSearchCapabilities() throws Exception {
        CrabPaths directPaths = new CrabPaths(temp.resolve("crab-direct-guide"));
        CrabConfig directConfig = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(40);
        try (CrabRuntime runtime = CrabRuntime.open(directPaths, directConfig);
             CrabHttpServer server = new CrabHttpServer(runtime)) {
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.port();
            // 13 ungrouped (> 10) + 5 grouped = 18 visible capabilities (<= direct_limit 40 -> direct mode)
            runtime.registry().put(ConchParser.parse(groupedConchYaml("direct_oa", 12)), Origin.OFFICIAL);
            CreatedApiKey key = runtime.admin().createApiKey(
                    "full", ApiKeyScope.explicit(true, Set.of(), Set.of(), Set.of(), false), Actors.CLI);
            String session = mcpInit(baseUrl, key.plaintext());

            JsonNode tools = mcpPost(baseUrl, key.plaintext(), session, 2, "tools/list", null)
                    .path("result").path("tools");
            assertThat(tools).hasSize(18);
            assertThat(tools.toString()).doesNotContain(McpCatalog.SEARCH);

            String guide = mcpPost(baseUrl, key.plaintext(), session, 3, "resources/read",
                    Map.of("uri", "hermit://conches/direct_oa/guide"))
                    .path("result").path("contents").get(0).path("text").asText();
            assertThat(guide).contains("## 顶层能力")
                    .contains("### direct_oa__get_me")
                    .contains("### direct_oa__top_1")
                    .contains("### direct_oa__top_12")
                    .contains("## 分组目录")
                    .contains("tools/list")
                    .doesNotContain(McpCatalog.SEARCH);
        }

        // 目录模式下仍截断 >10 的顶层能力并提示 search_capabilities。
        CrabPaths dirPaths = new CrabPaths(temp.resolve("crab-dir-guide"));
        CrabConfig dirConfig = CrabConfig.defaults().withListen("127.0.0.1", 0).withMcpDirectLimit(0);
        try (CrabRuntime runtime = CrabRuntime.open(dirPaths, dirConfig);
             CrabHttpServer server = new CrabHttpServer(runtime)) {
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.port();
            runtime.registry().put(ConchParser.parse(groupedConchYaml("dir_oa", 12)), Origin.OFFICIAL);
            CreatedApiKey key = runtime.admin().createApiKey(
                    "full", ApiKeyScope.explicit(true, Set.of(), Set.of(), Set.of(), false), Actors.CLI);
            String session = mcpInit(baseUrl, key.plaintext());

            String guide = mcpPost(baseUrl, key.plaintext(), session, 2, "resources/read",
                    Map.of("uri", "hermit://conches/dir_oa/guide"))
                    .path("result").path("contents").get(0).path("text").asText();
            assertThat(guide).contains("## 顶层能力")
                    .contains("共有 13 个未分组顶层能力")
                    .contains(McpCatalog.SEARCH)
                    .contains("ungrouped: true")
                    .doesNotContain("### dir_oa__top_12");
        }
    }

    private record HttpResult(int status, String body, String session) {
        JsonNode bodyJson() throws Exception {
            return JSON.readTree(body);
        }
    }

    private static HttpResult httpGet(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).GET();
        headers.forEach(req::header);
        HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(res.statusCode(), res.body(), res.headers().firstValue("Mcp-Session-Id").orElse(null));
    }

    private static HttpResult consolePost(String url, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Hermit-Console", "1")
                .POST(HttpRequest.BodyPublishers.ofString(ApiJson.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(res.statusCode(), res.body(), null);
    }

    @Test
    void localImportRejectsDuplicateGroupAndChildKeys() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("dup-groups"));
        try (CrabRuntime runtime = CrabRuntime.open(paths, CrabConfig.defaults())) {
            String dupTop = """
                    conch: dup_top
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.10.0
                    base_url: http://127.0.0.1:9
                    title: 重复一级分组
                    description: 说明
                    credentials: {}
                    groups:
                      hr:
                        title: 人事一
                      hr:
                        title: 人事二
                    actions:
                    %s
                    """.formatted(actionYaml("get_emp", "hr", "查员工", true));
            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    dupTop.getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(dev.hermit.crab.sync.LocalConchException.class)
                    .satisfies(ex -> {
                        var err = (dev.hermit.crab.sync.LocalConchException) ex;
                        assertThat(err.errors()).anyMatch(i ->
                                "GROUP_NAME".equals(i.code()) && "groups.hr".equals(i.path()));
                    });

            String dupChild = """
                    conch: dup_child
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.10.0
                    base_url: http://127.0.0.1:9
                    title: 重复二级分组
                    description: 说明
                    credentials: {}
                    groups:
                      approval:
                        title: 审批
                        children:
                          leave:
                            title: 请假一
                          leave:
                            title: 请假二
                    actions:
                    %s
                    """.formatted(actionYaml("list_leave", "approval/leave", "查请假", true));
            assertThatThrownBy(() -> runtime.localConches().importBytes(
                    dupChild.getBytes(StandardCharsets.UTF_8), Actors.CLI))
                    .isInstanceOf(dev.hermit.crab.sync.LocalConchException.class)
                    .satisfies(ex -> {
                        var err = (dev.hermit.crab.sync.LocalConchException) ex;
                        assertThat(err.errors()).anyMatch(i ->
                                "GROUP_NAME".equals(i.code()) && "groups.approval.children.leave".equals(i.path()));
                    });
        }
    }

    private static String mcpInit(String baseUrl, String key) throws Exception {
        String body = ApiJson.write(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test", "version", "1.0"))));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(res.statusCode()).isEqualTo(200);
        return res.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }

    private static JsonNode mcpPost(String baseUrl, String key, String session, int id, String method, Object params)
            throws Exception {
        Map<String, Object> payload = params == null
                ? Map.of("jsonrpc", "2.0", "id", id, "method", method)
                : Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .header("Mcp-Session-Id", session)
                .POST(HttpRequest.BodyPublishers.ofString(ApiJson.write(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return JSON.readTree(res.body());
    }

    private static JsonNode mcpCall(String baseUrl, String key, String session, int id, String name, Map<String, Object> args)
            throws Exception {
        return mcpPost(baseUrl, key, session, id, "tools/call", Map.of("name", name, "arguments", args));
    }

    private static JsonNode findByField(JsonNode array, String field, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("missing " + field + "=" + value + " in " + array);
    }
}
