package dev.hermit.crab.upstream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.AccountContext;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.gateway.CrabHttpServer;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.Invoker;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.testing.SecretChecks;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class McpForwardTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "crm-token-aaaa";

    @TempDir
    Path temp;

    @Test
    void jsonAndSseForwardingKeepsPermissionsAndMasksSecrets() throws Exception {
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("forward"), upstream, 4, 30)) {
            upstream.mode = Mode.JSON;
            JsonNode ok = lab.call("search_customer", Map.of("q", "ada", "extra", 1), null, null);
            assertThat(ok.path("success").asBoolean()).isTrue();
            assertThat(ok.path("data").path("name").asText()).isEqualTo("ada");
            assertThat(upstream.tools()).isEqualTo(1);
            assertThat(upstream.initializes()).isEqualTo(1);
            Hit call = upstream.toolHits().get(0);
            assertThat(call.authorization).isEqualTo("Bearer " + TOKEN);
            assertThat(call.tenant).isEqualTo("main");
            assertThat(call.origin).isNull();
            assertThat(call.cookie).isNull();
            assertThat(call.session).isEqualTo(upstream.sessionId);
            assertThat(call.protocol).isEqualTo("2025-11-25");
            assertThat(call.accept).contains("application/json").contains("text/event-stream");
            assertThat(call.contentType).startsWith("application/json");
            assertThat(call.body).contains("extra");
            SecretChecks.absent(call.authorization, lab.key.plaintext());
            SecretChecks.absent(call.body, lab.key.plaintext());
            Hit opened = upstream.initHits().get(0);
            assertThat(opened.session).isNull();
            assertThat(opened.body).contains("2025-11-25").contains("hermit-crab").contains("1.5.0");
            assertThat(opened.body).contains("\"capabilities\":{}");

            upstream.mode = Mode.SSE;
            upstream.plainError = true;
            JsonNode forwarded = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(forwarded.path("success").asBoolean()).isTrue();
            assertThat(forwarded.path("data").path("content").get(0).path("text").asText()).contains("Error:");
            assertThat(forwarded.path("data").path("content").get(0).path("annotations").path("audience").get(0).asText())
                    .isEqualTo("user");
            assertThat(forwarded.path("data").path("content").get(1).path("type").asText()).isEqualTo("image");
            assertThat(upstream.tools()).isEqualTo(2);

            String trace = ok.path("traceId").asText();
            CallLogStore.CallDetail detail = lab.runtime.callLogs().findByTraceId(trace).orElseThrow();
            assertThat(detail.steps()).hasSize(1);
            assertThat(detail.steps().get(0).type()).isEqualTo("mcp_call");
            assertThat(detail.steps().get(0).path()).isEqualTo("searchCustomer");
            assertThat(detail.steps().get(0).status()).isEqualTo(200);
            SecretChecks.absent(detail.call().errorMessage(), TOKEN);
            SecretChecks.absent(detail.steps().get(0).path(), TOKEN);
            SecretChecks.absent(detail.steps().get(0).path(), upstream.sessionId);

            JsonNode openapi = lab.get("/openapi.json", null);
            JsonNode search = openapi.path("paths").path("/api/vendor_crm/search_customer").path("post");
            JsonNode write = openapi.path("paths").path("/api/vendor_crm/create_ticket").path("post");
            assertThat(search.path("x-hermit-driver").asText()).isEqualTo("mcp");
            assertThat(search.path("responses").path("200").path("content").path("application/json")
                    .path("schema").path("properties").path("data").path("type").asText()).isEqualTo("object");
            assertThat(parameter(search, "X-Hermit-Confirm")).isNull();
            assertThat(parameter(search, "X-Hermit-Confirm-Token")).isNull();
            assertThat(parameter(write, "X-Hermit-Confirm").path("required").asBoolean()).isTrue();
            assertThat(parameter(write, "X-Hermit-Confirm-Token").path("required").asBoolean()).isTrue();
            assertThat(lab.mcp("tools/list", null).path("result").path("tools").toString()).doesNotContain("来源：");
            String guide = lab.mcp("resources/read", Map.of("uri", "hermit://conches/vendor_crm/guide"))
                    .path("result").path("contents").get(0).path("text").asText();
            assertThat(guide).contains("来源：源站 MCP").contains("不可信");
        }
    }

    @Test
    void session404RebuildsOnceAndStopsOnTheSecond() throws Exception {
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("rebuild"), upstream, 4, 30)) {
            upstream.failTools = 1;
            JsonNode ok = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(ok.path("success").asBoolean()).isTrue();
            assertThat(upstream.initializes()).isEqualTo(2);
            assertThat(upstream.tools()).isEqualTo(2);
            assertThat(upstream.deletes()).isEqualTo(1);
            assertThat(upstream.initHits().get(1).session).isNull();

            upstream.failTools = 2;
            upstream.resetCounts();
            JsonNode lost = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(lost.path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");
            assertThat(lost.path("error").path("message").asText()).isEqualTo(McpForwarder.SESSION_LOST);
            assertThat(upstream.initializes()).isEqualTo(1);
            assertThat(upstream.tools()).isEqualTo(2);
        }
    }

    @Test
    void mapsUpstreamFailuresWithoutRetryingOrLeakingBodies() throws Exception {
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("errors"), upstream, 4, 30)) {
            upstream.toolsStatus = 401;
            JsonNode auth = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(auth.path("error").path("code").asText()).isEqualTo("AUTH_FAILED");
            assertThat(auth.path("error").path("message").asText()).isEqualTo(McpForwarder.AUTH_REJECTED);
            SecretChecks.absent(auth.toString(), "secret-body");

            upstream.toolsStatus = 200;
            upstream.rpcCode = -32602;
            upstream.rpcMessage = "Tool searchCustomer not found";
            JsonNode missing = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(missing.path("error").path("code").asText()).isEqualTo("RULE_MISMATCH");
            assertThat(missing.path("error").path("message").asText()).isEqualTo(McpForwarder.UNKNOWN_TOOL);
            assertThat(upstream.initializes()).isEqualTo(1);

            upstream.rpcMessage = "missing field";
            JsonNode bad = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(bad.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(bad.path("error").path("message").asText()).isEqualTo(McpForwarder.BAD_ARGUMENTS);

            upstream.rpcCode = -32601;
            upstream.rpcMessage = "Method not found";
            JsonNode method = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(method.path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");

            upstream.rpcCode = 0;
            upstream.toolError = "Input validation error: q " + TOKEN;
            JsonNode input = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(input.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(input.path("error").path("message").asText()).contains("Input validation error");
            SecretChecks.absent(input.toString(), TOKEN);
            ExecutionResult inputResult = lab.results.get(lab.results.size() - 1);
            assertThat(inputResult.error().failedStepType()).isNull();
            assertThat(inputResult.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);

            upstream.toolError = "intentional tool failure " + TOKEN;
            JsonNode failed = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(lab.lastStatus).isEqualTo(502);
            assertThat(failed.path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");
            assertThat(failed.path("data").get(0).path("text").asText()).contains("intentional").doesNotContain(TOKEN);
            ExecutionResult tide = lab.results.get(lab.results.size() - 1);
            assertThat(tide.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(tide.error().failedStepIndex()).isEqualTo(0);
            assertThat(tide.error().failedStepType()).isEqualTo("mcp_tool_error");

            upstream.toolError = null;
            upstream.toolsStatus = 302;
            JsonNode redirect = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(redirect.path("error").path("message").asText()).contains("302");
            assertThat(upstream.tools()).isEqualTo(7);

            upstream.toolsStatus = 406;
            int inits = upstream.initializes();
            JsonNode rejected = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(rejected.path("error").path("message").asText()).contains("406");
            assertThat(upstream.initializes()).isEqualTo(inits);

            upstream.toolsStatus = 200;
            upstream.drop = true;
            int tools = upstream.tools();
            JsonNode dropped = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(dropped.path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");
            assertThat(upstream.tools()).isEqualTo(tools + 1);
        }
    }

    @Test
    void limitsSizeTimeoutAndAddresses() throws Exception {
        try (Upstream upstream = new Upstream();
                Lab slow = Lab.open(temp.resolve("timeout"), upstream, 4, 8)) {
            upstream.delayMs = 1_000;
            slow.doc = ConchParser.parse(packageYaml(upstream.port(), "1.0.0", "headers", 200, ""));
            slow.install();
            JsonNode timed = slow.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(timed.path("error").path("code").asText()).isEqualTo("UPSTREAM_ERROR");
            assertThat(timed.path("error").path("message").asText()).isEqualTo(McpForwarder.UPSTREAM_TIMEOUT);
            assertThat(upstream.cancels()).isZero();
        }
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("deadline"), upstream, 4, 1)) {
            upstream.delayMs = 3_000;
            JsonNode timed = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(timed.path("error").path("code").asText()).isEqualTo("TIMEOUT");
            assertThat(timed.path("error").path("message").asText()).isEqualTo(McpForwarder.DEADLINE);
            assertThat(upstream.cancels()).isEqualTo(1);
        }
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("huge"), upstream, 4, 30)) {
            upstream.hugeEvent = true;
            JsonNode event = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(event.path("error").path("message").asText()).isEqualTo(McpForwarder.TOO_LARGE);
            upstream.hugeEvent = false;
            upstream.hugeBody = true;
            JsonNode body = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(body.path("error").path("message").asText()).isEqualTo(McpForwarder.TOO_LARGE);
        }
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("redirect-host"), upstream, 4, 30)) {
            lab.doc = ConchParser.parse(addressYaml("http://169.254.169.254/mcp"));
            lab.install();
            JsonNode link = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(link.path("error").path("message").asText()).isEqualTo(McpForwarder.ADDRESS_DENIED);
            lab.doc = ConchParser.parse(addressYaml("http://[fd00:ec2::254]/mcp"));
            lab.install();
            JsonNode metadata = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(metadata.path("error").path("message").asText()).isEqualTo(McpForwarder.ADDRESS_DENIED);
        }
    }

    @Test
    void confirmHeaderIdempotencyAndDirectMcpRefusal() throws Exception {
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("confirm"), upstream, 4, 30)) {
            JsonNode missing = lab.call("create_ticket", Map.of(), "same-key", null);
            assertThat(lab.lastStatus).isEqualTo(409);
            assertThat(missing.path("error").path("code").asText()).isEqualTo("CONFLICT");
            String token = lab.lastConfirmToken;
            assertThat(token).isEqualTo(Invoker.snapshotToken(lab.doc)).matches("[0-9a-f]{64}");
            assertThat(missing.path("error").path("message").asText()).isEqualTo(Invoker.confirmRequired(token));
            assertThat(upstream.hits).isEmpty();

            JsonNode almost = lab.call("create_ticket", Map.of(), "same-key", "TRUE");
            assertThat(almost.path("error").path("code").asText()).isEqualTo("CONFLICT");
            assertThat(almost.path("error").path("message").asText()).isEqualTo(Invoker.confirmRequired(token));
            assertThat(upstream.hits).isEmpty();

            JsonNode onlyTrue = lab.call("create_ticket", Map.of(), "same-key", " true ");
            assertThat(lab.lastStatus).isEqualTo(409);
            assertThat(onlyTrue.path("error").path("message").asText()).isEqualTo(Invoker.confirmRequired(token));
            assertThat(upstream.hits).isEmpty();

            JsonNode created = lab.call("create_ticket", Map.of(), "same-key", " true ", " " + token + " ");
            assertThat(created.path("success").asBoolean()).isTrue();
            assertThat(upstream.tools()).isEqualTo(1);

            JsonNode again = lab.call("create_ticket", Map.of(), "same-key", null);
            assertThat(again.path("error").path("code").asText()).isEqualTo("CONFLICT");
            assertThat(upstream.tools()).isEqualTo(1);

            JsonNode replay = lab.call("create_ticket", Map.of(), "same-key", "true", token);
            assertThat(replay.path("success").asBoolean()).isTrue();
            assertThat(replay.path("traceId").asText()).isEqualTo(created.path("traceId").asText());
            assertThat(upstream.tools()).isEqualTo(1);

            CreatedApiKey full = lab.key;
            CreatedApiKey readOnly = lab.runtime.admin().createApiKey(
                    "read", new ApiKeyScope(Set.of(), Set.of(), true), Actors.CLI);
            lab.key = readOnly;
            JsonNode forbidden = lab.call("create_ticket", Map.of(), null, "true");
            assertThat(lab.lastStatus).isEqualTo(403);
            assertThat(upstream.tools()).isEqualTo(1);

            lab.runtime.setCapabilityDisabled("vendor_crm", "search_customer", true, Actors.CLI);
            JsonNode disabled = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(lab.lastStatus).isEqualTo(404);
            assertThat(disabled.path("error").path("code").asText()).isEqualTo("CAPABILITY_DISABLED");
            assertThat(upstream.tools()).isEqualTo(1);

            lab.key = full;
            JsonNode refused = lab.mcp("tools/call", Map.of(
                    "name", "vendor_crm__create_ticket",
                    "arguments", Map.of()));
            assertThat(refused.toString()).contains(McpCallsNeed.NO_CONFIRM);
            assertThat(upstream.tools()).isEqualTo(1);
        }
    }

    @Test
    void confirmTokenBindsTheSnapshotAndRejectsReplacedContent() throws Exception {
        ConchDoc note = ConchParser.parse(noteYaml("http://127.0.0.1:9/old", false));
        ConchDoc noteAgain = ConchParser.parse(noteYaml("http://127.0.0.1:9/old", false));
        assertThat(noteAgain).isNotSameAs(note);
        String noteToken = Invoker.snapshotToken(note);
        assertThat(Invoker.snapshotToken(note)).isEqualTo(noteToken);
        assertThat(Invoker.snapshotToken(noteAgain)).isEqualTo(noteToken);
        assertThat(Invoker.snapshotToken(ConchParser.parse(noteYaml("http://127.0.0.1:9/new", false))))
                .isNotEqualTo(noteToken);
        assertThat(Invoker.snapshotToken(ConchParser.parse(noteYaml("http://127.0.0.1:9/old", true))))
                .isNotEqualTo(noteToken);
        assertThat(Invoker.snapshotToken(ConchParser.parse(noteYamlReorderedHeaders())))
                .isEqualTo(Invoker.snapshotToken(ConchParser.parse(noteYamlOrderedHeaders())));

        String confirmed = Invoker.snapshotToken(ConchParser.parse(mcpYaml("http://127.0.0.1:9/mcp", "true")));
        assertThat(Invoker.snapshotToken(ConchParser.parse(mcpYaml("http://127.0.0.1:9/mcp", "false"))))
                .isNotEqualTo(confirmed);
        assertThat(Invoker.snapshotToken(ConchParser.parse(mcpYaml("http://127.0.0.1:9/other", "true"))))
                .isNotEqualTo(confirmed);

        try (Upstream upstream = new Upstream();
                Upstream replaced = new Upstream();
                Lab lab = Lab.open(temp.resolve("confirm-snapshot"), upstream, 4, 30)) {
            JsonNode missing = lab.call("create_ticket", Map.of(), null, null);
            assertThat(lab.lastStatus).isEqualTo(409);
            String token = lab.lastConfirmToken;
            assertThat(token).isEqualTo(Invoker.snapshotToken(lab.doc));
            assertThat(missing.path("error").path("message").asText()).isEqualTo(Invoker.confirmRequired(token));
            assertThat(upstream.hits).isEmpty();

            JsonNode onlyTrue = lab.call("create_ticket", Map.of(), null, "true");
            assertThat(lab.lastStatus).isEqualTo(409);
            assertThat(onlyTrue.path("error").path("message").asText()).isEqualTo(Invoker.confirmRequired(token));
            assertThat(upstream.hits).isEmpty();

            JsonNode created = lab.call("create_ticket", Map.of(), null, "true", token);
            assertThat(created.path("success").asBoolean()).isTrue();
            assertThat(upstream.toolNames()).containsExactly("createTicket");

            String swappedYaml = packageYaml(replaced.port(), "1.0.0", "headers", null, "")
                    .replace("tool: createTicket", "tool: createTicketV2");
            ConchDoc swapped = ConchParser.parse(swappedYaml);
            assertThat(swapped.version()).isEqualTo(lab.doc.version());
            assertThat(Invoker.snapshotToken(swapped)).isNotEqualTo(token);
            lab.doc = swapped;
            lab.install();

            int before = replaced.tools();
            JsonNode rejected = lab.call("create_ticket", Map.of(), null, "true", token);
            assertThat(lab.lastStatus).isEqualTo(409);
            assertThat(rejected.path("error").path("message").asText()).isEqualTo(Invoker.CHANGED_AFTER_CONFIRM);
            assertThat(lab.lastConfirmToken).isNull();
            assertThat(replaced.tools()).isEqualTo(before);
            assertThat(replaced.hits).isEmpty();
            assertThat(upstream.toolNames()).containsExactly("createTicket");
        }
    }

    @Test
    void accountsVersionsAndOauthStayApart() throws Exception {
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("accounts"), upstream, 4, 30)) {
            lab.account("bob");
            lab.call("search_customer", Map.of("q", "ada"), null, null);
            int before = upstream.initializes();
            lab.accountHeader = "bob";
            lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(upstream.initializes()).isEqualTo(before + 1);
            assertThat(upstream.sessionIds()).hasSize(2);

            lab.accountHeader = null;
            lab.doc = ConchParser.parse(packageYaml(upstream.port(), "1.1.0", "headers", null, ""));
            lab.install();
            lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(upstream.deletes()).isGreaterThanOrEqualTo(1);
            assertThat(upstream.sessionIds().size()).isGreaterThanOrEqualTo(3);

            lab.doc = ConchParser.parse(oauthYaml(upstream.port()));
            lab.install();
            JsonNode oauth = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(oauth.path("error").path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(oauth.path("error").path("message").asText()).isEqualTo(McpForwarder.OAUTH_NOT_CONNECTED);
        }
    }

    @Test
    void hostOutsideTheAllowlistNeverConnects() throws Exception {
        ConchDoc doc = ConchParser.parse(packageYaml(9, "1.0.0", "headers", null, """
                allowed_hosts: ["example.com"]
                """));
        try (SessionManager accounts = new SessionManager();
                McpForwarder forwarder = new McpForwarder(accounts, KnownSecrets.empty(), Duration.ofSeconds(2), 1)) {
            ExecutionResult result = forwarder.execute(new ExecutionRequest(
                    "trace-host",
                    Origin.OFFICIAL,
                    doc.conch(),
                    "search_customer",
                    Map.of("q", "ada"),
                    "alice",
                    new AccountContext() {
                        @Override
                        public Map<String, String> credentials() {
                            return Map.of("token", TOKEN);
                        }

                        @Override
                        public Map<String, String> config() {
                            return Map.of();
                        }
                    }), doc);
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
            assertThat(result.error().message()).isEqualTo(McpForwarder.HOST_DENIED);
        }
    }

    @Test
    void runtimeRejectsARemoteSchemaRef() {
        ConchDoc doc = ConchParser.parse("""
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 引用
                description: 远程引用在执行前被拒绝。
                credentials: {}
                upstream:
                  url: http://127.0.0.1:9/mcp
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  remote_ref:
                    tool: remoteRef
                    title: 远程引用
                    description: 带远程引用的入参。
                    mcp: {read_only: true}
                    input_schema:
                      type: object
                      properties:
                        q:
                          $ref: https://example.com/q.schema.json
                """);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new dev.hermit.crab.engine.InputPreparer().prepare(doc.actions().get("remote_ref"), Map.of("q", "ada")))
                .isInstanceOf(dev.hermit.crab.engine.InvalidInput.class)
                .hasMessageContaining("远程 schema");
    }

    @Test
    void schemaIsValidatedAsWrittenAndDoesNotFetchDialects() throws Exception {
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("schema"), upstream, 4, 30)) {
            JsonNode extra = lab.call("search_customer", Map.of("q", "ada", "extra", true), null, null);
            assertThat(extra.path("success").asBoolean()).isTrue();
            JsonNode listed = lab.mcp("tools/list", null);
            String schema = tool(listed, "vendor_crm__search_customer").path("inputSchema").toString();
            assertThat(schema).doesNotContain("$schema").doesNotContain("additionalProperties");
        }
    }

    @Test
    void protocolIsOfferedOnceAndReverseRequestsGetMethodNotFound() throws Exception {
        try (Upstream upstream = new Upstream();
                Lab lab = Lab.open(temp.resolve("protocol"), upstream, 4, 30)) {
            upstream.negotiated = "1999-01-01";
            JsonNode bad = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(bad.path("error").path("message").asText()).isEqualTo(McpForwarder.BAD_PROTOCOL);
            assertThat(upstream.initializes()).isEqualTo(1);

            upstream.negotiated = "2025-06-18";
            upstream.resetCounts();
            lab.doc = ConchParser.parse(packageYaml(upstream.port(), "1.2.0", "headers", null, ""));
            lab.install();
            JsonNode ok = lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(ok.path("success").asBoolean()).isTrue();
            assertThat(upstream.initHits().get(0).body).contains("2025-11-25");
            assertThat(upstream.toolHits().get(0).protocol).isEqualTo("2025-06-18");
            assertThat(upstream.initializes()).isEqualTo(1);

            upstream.reverse = true;
            lab.call("search_customer", Map.of("q", "ada"), null, null);
            assertThat(upstream.replies()).isEqualTo(1);
            assertThat(upstream.replyIds()).contains(0);
        }
    }

    @Test
    void perAccountConcurrencyIsConfigured() throws Exception {
        try (Upstream upstream = new Upstream()) {
            upstream.hold = new CountDownLatch(1);
            try (Lab serial = Lab.open(temp.resolve("serial"), upstream, 1, 30)) {
                Thread first = new Thread(() -> serial.call("search_customer", Map.of("q", "a"), null, null));
                Thread second = new Thread(() -> serial.call("search_customer", Map.of("q", "b"), null, null));
                first.start();
                second.start();
                long wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (upstream.entered.get() < 1 && System.nanoTime() < wait) {
                    Thread.sleep(20);
                }
                Thread.sleep(300);
                assertThat(upstream.entered.get()).isEqualTo(1);
                upstream.hold.countDown();
                first.join(5_000);
                second.join(5_000);
                assertThat(upstream.maxInFlight.get()).isEqualTo(1);
            }
        }
        try (Upstream upstream = new Upstream()) {
            upstream.hold = new CountDownLatch(1);
            try (Lab parallel = Lab.open(temp.resolve("parallel"), upstream, 2, 30)) {
                Thread first = new Thread(() -> parallel.call("search_customer", Map.of("q", "a"), null, null));
                Thread second = new Thread(() -> parallel.call("search_customer", Map.of("q", "b"), null, null));
                first.start();
                second.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (upstream.entered.get() < 2 && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertThat(upstream.entered.get()).isEqualTo(2);
                assertThat(upstream.maxInFlight.get()).isGreaterThanOrEqualTo(2);
                upstream.hold.countDown();
                first.join(5_000);
                second.join(5_000);
            }
        }
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter;
            }
        }
        return null;
    }

    private static JsonNode tool(JsonNode listed, String name) {
        for (JsonNode tool : listed.path("result").path("tools")) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        return JSON.missingNode();
    }

    private static String noteYaml(String stepUrl, boolean readOnly) {
        return """
                conch: note_pad
                version: 1.0.0
                min_crab_version: 1.0.0
                title: 笔记
                description: 记一笔。
                actions:
                  save:
                    title: 保存
                    description: 保存一笔。
                    mcp: {read_only: %s, destructive: true}
                    steps:
                      - request:
                          method: POST
                          url: %s
                """.formatted(readOnly, stepUrl);
    }

    private static String noteYamlOrderedHeaders() {
        return """
                conch: note_pad
                version: 1.0.0
                min_crab_version: 1.0.0
                title: 笔记
                description: 记一笔。
                actions:
                  save:
                    title: 保存
                    description: 保存一笔。
                    mcp: {read_only: false, destructive: true}
                    steps:
                      - request:
                          method: POST
                          url: http://127.0.0.1:9/old
                          headers:
                            A: "1"
                            B: "2"
                """;
    }

    private static String noteYamlReorderedHeaders() {
        return """
                conch: note_pad
                version: 1.0.0
                min_crab_version: 1.0.0
                title: 笔记
                description: 记一笔。
                actions:
                  save:
                    title: 保存
                    description: 保存一笔。
                    mcp: {read_only: false, destructive: true}
                    steps:
                      - request:
                          method: POST
                          url: http://127.0.0.1:9/old
                          headers:
                            B: "2"
                            A: "1"
                """;
    }

    private static String mcpYaml(String url, String confirm) {
        return """
                conch: vendor_crm
                version: 1.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户系统
                description: 源站提供的服务。
                upstream:
                  url: %s
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  create_ticket:
                    tool: createTicket
                    title: 创建工单
                    description: 创建一张工单。
                    confirm: %s
                    mcp: {read_only: false, destructive: true}
                """.formatted(url, confirm);
    }

    private static String packageYaml(int port, String version, String auth, Integer timeout, String extra) {
        String timeoutLine = timeout == null ? "" : "timeout_ms: " + timeout + "\n";
        return """
                conch: vendor_crm
                version: %s
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户系统（源站 MCP）
                description: 源站提供的 MCP 服务。
                credentials:
                  token: {type: secret, label: 令牌, required: true}
                config:
                  tenant: {type: text, label: 租户, required: false, default: main}
                upstream:
                  url: http://127.0.0.1:%d/mcp
                  transport: streamable_http
                  auth: {type: %s}
                  %s
                  headers:
                    Authorization: "Bearer {{credential.token}}"
                    X-Tenant: "{{config.tenant}}"
                  %s
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称搜索。
                    mcp: {read_only: true}
                    idempotent: true
                    input_schema:
                      $schema: https://json-schema.org/draft/2020-12/schema
                      type: object
                      properties:
                        q: {type: string}
                      required: [q]
                    output_schema: {type: object}
                  create_ticket:
                    tool: createTicket
                    title: 创建工单
                    description: 创建一张工单。
                    mcp: {read_only: false, destructive: true}
                    input_schema: {type: object}
                """.formatted(version, port, auth, extra == null ? "" : extra, timeoutLine);
    }

    private static String addressYaml(String url) {
        return """
                conch: vendor_crm
                version: 9.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 地址
                description: 地址检查。
                credentials:
                  token: {type: secret, label: 令牌, required: true}
                upstream:
                  url: %s
                  transport: streamable_http
                  auth: {type: headers}
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称搜索。
                    mcp: {read_only: true}
                    input_schema: {type: object}
                """.formatted(url);
    }

    private static String oauthYaml(int port) {
        return """
                conch: vendor_crm
                version: 3.0.0
                driver: mcp
                min_crab_version: 1.1.0
                title: 客户系统（源站 MCP）
                description: 尚未连接。
                credentials: {}
                upstream:
                  url: http://127.0.0.1:%d/mcp
                  transport: streamable_http
                  auth:
                    type: oauth
                    scopes: [mcp]
                    client_id: hermit-crab
                    authorization_endpoint: http://127.0.0.1:%d/authorize
                    token_endpoint: http://127.0.0.1:%d/token
                actions:
                  search_customer:
                    tool: searchCustomer
                    title: 搜索客户
                    description: 按名称搜索。
                    mcp: {read_only: true}
                    input_schema:
                      type: object
                      properties:
                        q: {type: string}
                      required: [q]
                """.formatted(port, port, port);
    }

    /** 测试里要断言的确认文案。生产常量在 mcp 包，这里只锁同一句。 */
    private static final class McpCallsNeed {
        static final String NO_CONFIRM = "客户端不支持确认，拒绝执行写操作";
    }

    private enum Mode {
        JSON, SSE
    }

    private static final class Lab implements AutoCloseable {
        final Upstream upstream;
        final CrabRuntime runtime;
        final CrabHttpServer server;
        final List<ExecutionResult> results = new CopyOnWriteArrayList<>();
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        CreatedApiKey key;
        ConchDoc doc;
        String accountHeader;
        int lastStatus;
        String lastConfirmToken;

        Lab(Upstream upstream, CrabRuntime runtime, CrabHttpServer server, CreatedApiKey key, ConchDoc doc) {
            this.upstream = upstream;
            this.runtime = runtime;
            this.server = server;
            this.key = key;
            this.doc = doc;
        }

        static Lab open(Path root, Upstream upstream, int concurrency, int timeoutSeconds) throws Exception {
            CrabRuntime runtime = CrabRuntime.open(
                    new CrabPaths(root),
                    CrabConfig.defaults().withListen("127.0.0.1", 0).withLimits(timeoutSeconds, concurrency),
                    name -> null);
            ConchDoc doc = ConchParser.parse(packageYaml(upstream.port(), "1.0.0", "headers", null, ""));
            runtime.registry().put(doc);
            runtime.admin().createAccount("vendor_crm", "alice", Actors.CLI);
            runtime.admin().writeCredentials(doc, "alice", Map.of("token", TOKEN), Actors.CLI);
            CreatedApiKey key = runtime.admin().createApiKey(
                    "mcp", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
            CrabHttpServer server = new CrabHttpServer(runtime);
            server.start();
            Lab lab = new Lab(upstream, runtime, server, key, doc);
            runtime.invoker().addListener((invocation, result) -> lab.results.add(result));
            return lab;
        }

        void install() {
            runtime.registry().put(doc);
            if (doc.credentials() != null && !doc.credentials().isEmpty()
                    && runtime.admin().listAccounts("vendor_crm").stream().noneMatch(account -> account.account().equals("alice"))) {
                runtime.admin().createAccount("vendor_crm", "alice", Actors.CLI);
            }
            if (doc.credentials() != null && !doc.credentials().isEmpty()) {
                runtime.admin().writeCredentials(doc, "alice", Map.of("token", TOKEN), Actors.CLI);
            }
        }

        void account(String name) {
            if (runtime.admin().listAccounts("vendor_crm").stream().noneMatch(account -> account.account().equals(name))) {
                runtime.admin().createAccount("vendor_crm", name, Actors.CLI);
            }
            runtime.admin().writeCredentials(doc, name, Map.of("token", TOKEN), Actors.CLI);
        }

        JsonNode call(String action, Map<String, Object> body, String idempotency, String confirm) {
            return call(action, body, idempotency, confirm, null);
        }

        JsonNode call(String action, Map<String, Object> body, String idempotency, String confirm, String confirmToken) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + "/api/vendor_crm/" + action))
                        .timeout(Duration.ofSeconds(8))
                        .header("Authorization", "Bearer " + key.plaintext())
                        .header("Content-Type", "application/json");
                if (accountHeader != null) {
                    builder.header("X-Hermit-Account", accountHeader);
                }
                if (idempotency != null) {
                    builder.header("Idempotency-Key", idempotency);
                }
                if (confirm != null) {
                    builder.header("X-Hermit-Confirm", confirm);
                }
                if (confirmToken != null) {
                    builder.header("X-Hermit-Confirm-Token", confirmToken);
                }
                builder.POST(HttpRequest.BodyPublishers.ofString(ApiJson.write(body)));
                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                lastStatus = response.statusCode();
                lastConfirmToken = response.headers().firstValue("X-Hermit-Confirm-Token").orElse(null);
                return ApiJson.tree(response.body());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        JsonNode get(String path, String keyText) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(5));
            if (keyText != null) {
                builder.header("Authorization", "Bearer " + keyText);
            }
            HttpResponse<String> response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
            lastStatus = response.statusCode();
            return ApiJson.tree(response.body());
        }

        JsonNode mcp(String method, Map<String, Object> params) throws Exception {
            if (mcpSession == null) {
                JsonNode opened = mcpPost("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
                        """);
                mcpSession = openedSession;
                assertThat(opened.path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
                mcpPost("""
                        {"jsonrpc":"2.0","method":"notifications/initialized"}
                        """);
            }
            String body = params == null
                    ? """
                            {"jsonrpc":"2.0","id":2,"method":"%s"}
                            """.formatted(method)
                    : """
                            {"jsonrpc":"2.0","id":2,"method":"%s","params":%s}
                            """.formatted(method, ApiJson.write(params));
            return mcpPost(body);
        }

        private String mcpSession;
        private String openedSession;

        private JsonNode mcpPost(String body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + "/mcp"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + key.plaintext())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            if (mcpSession != null) {
                builder.header("Mcp-Session-Id", mcpSession);
            }
            HttpResponse<String> response = http.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String session = response.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (session != null) {
                openedSession = session;
            }
            if (response.body() == null || response.body().isBlank()) {
                return JSON.createObjectNode();
            }
            return ApiJson.tree(response.body());
        }

        private String base() {
            return "http://127.0.0.1:" + server.port();
        }

        @Override
        public void close() {
            server.close();
            runtime.close();
        }
    }

    private static final class Hit {
        final String kind;
        final String session;
        final String protocol;
        final String authorization;
        final String tenant;
        final String origin;
        final String cookie;
        final String accept;
        final String contentType;
        final String body;
        final boolean apiKey;

        Hit(String kind, String session, String protocol, String authorization, String tenant, String origin,
                String cookie, String accept, String contentType, String body, boolean apiKey) {
            this.kind = kind;
            this.session = session;
            this.protocol = protocol;
            this.authorization = authorization;
            this.tenant = tenant;
            this.origin = origin;
            this.cookie = cookie;
            this.accept = accept;
            this.contentType = contentType;
            this.body = body;
            this.apiKey = apiKey;
        }
    }

    private static final class Upstream implements AutoCloseable {
        final Javalin app;
        final int port;
        final List<Hit> hits = new CopyOnWriteArrayList<>();
        final List<String> sessions = new CopyOnWriteArrayList<>();
        final AtomicInteger initializes = new AtomicInteger();
        final AtomicInteger tools = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicInteger replies = new AtomicInteger();
        final AtomicInteger entered = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        volatile String sessionId = "upstream-session-1";
        volatile Mode mode = Mode.JSON;
        volatile int failTools;
        volatile int toolsStatus = 200;
        volatile int rpcCode;
        volatile String rpcMessage;
        volatile String toolError;
        volatile boolean plainError;
        volatile boolean drop;
        volatile boolean hugeEvent;
        volatile boolean hugeBody;
        volatile boolean reverse;
        volatile long delayMs;
        volatile String negotiated = "2025-11-25";
        volatile CountDownLatch hold;
        final List<Integer> replyIds = new CopyOnWriteArrayList<>();

        Upstream() {
            app = Javalin.create(config -> config.showJavalinBanner = false);
            app.post("/mcp", this::post);
            app.delete("/mcp", ctx -> {
                deletes.incrementAndGet();
                record(ctx, "delete");
                ctx.status(200);
            });
            app.start(0);
            port = app.port();
        }

        int port() {
            return port;
        }

        int initializes() {
            return initializes.get();
        }

        int tools() {
            return tools.get();
        }

        int deletes() {
            return deletes.get();
        }

        int cancels() {
            return cancels.get();
        }

        int replies() {
            return replies.get();
        }

        List<String> sessionIds() {
            return List.copyOf(sessions);
        }

        List<Integer> replyIds() {
            return List.copyOf(replyIds);
        }

        List<Hit> initHits() {
            return hits.stream().filter(hit -> "initialize".equals(hit.kind)).toList();
        }

        List<Hit> toolHits() {
            return hits.stream().filter(hit -> "tools/call".equals(hit.kind)).toList();
        }

        List<String> toolNames() {
            ArrayList<String> names = new ArrayList<>();
            for (Hit hit : toolHits()) {
                names.add(ApiJson.tree(hit.body).path("params").path("name").asText());
            }
            return names;
        }

        void resetCounts() {
            initializes.set(0);
            tools.set(0);
            deletes.set(0);
            cancels.set(0);
            replies.set(0);
            hits.clear();
        }

        private void post(io.javalin.http.Context ctx) throws Exception {
            String body = ctx.body();
            JsonNode node = body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
            if (node.has("error")) {
                replies.incrementAndGet();
                replyIds.add(node.path("id").isNumber() ? node.path("id").asInt() : -1);
                ctx.status(202);
                return;
            }
            String method = node.path("method").asText("");
            record(ctx, method.isBlank() ? "post" : method);
            if ("notifications/cancelled".equals(method)) {
                cancels.incrementAndGet();
                ctx.status(202);
                return;
            }
            if ("notifications/initialized".equals(method)) {
                ctx.status(202);
                return;
            }
            if ("initialize".equals(method)) {
                initializes.incrementAndGet();
                sessionId = "upstream-session-" + initializes.get() + "-" + System.nanoTime();
                sessions.add(sessionId);
                write(ctx, sessionId, """
                        {"result":{"protocolVersion":"%s","capabilities":{},"serverInfo":{"name":"mock","version":"0"}},"jsonrpc":"2.0","id":%s}
                        """.formatted(negotiated, node.path("id").asText("1")), false);
                return;
            }
            if (!"tools/call".equals(method)) {
                ctx.status(400);
                return;
            }
            tools.incrementAndGet();
            if (hold != null) {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                entered.incrementAndGet();
                hold.await(5, TimeUnit.SECONDS);
                inFlight.decrementAndGet();
            }
            if (failTools > 0) {
                failTools--;
                byte[] missing = """
                        {"jsonrpc":"2.0","error":{"code":-32001,"message":"Session not found"},"id":null}
                        """.getBytes(StandardCharsets.UTF_8);
                ctx.res().setStatus(404);
                ctx.res().setContentType("application/json");
                ctx.res().getOutputStream().write(missing);
                ctx.res().flushBuffer();
                return;
            }
            if (toolsStatus != 200) {
                ctx.status(toolsStatus);
                if (toolsStatus >= 300 && toolsStatus < 400) {
                    ctx.header("Location", "http://127.0.0.1:9/elsewhere");
                }
                ctx.result("secret-body");
                return;
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (drop) {
                ctx.status(200);
                ctx.contentType("text/event-stream");
                ctx.header("mcp-session-id", sessionId);
                var out = ctx.res().getOutputStream();
                out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                ctx.res().flushBuffer();
                return;
            }
            String payload = payload(node.path("id"));
            if (hugeEvent) {
                writeRaw(ctx, "data: " + "x".repeat(McpUpstreamClient.MAX_EVENT_BYTES + 200) + "\n\n");
                return;
            }
            if (hugeBody) {
                String event = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n\n";
                int times = (McpUpstreamClient.MAX_RESPONSE_BYTES / event.length()) + 2;
                var out = ctx.res().getOutputStream();
                ctx.res().setStatus(200);
                ctx.res().setContentType("text/event-stream");
                ctx.res().setHeader("mcp-session-id", sessionId);
                byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < times; i++) {
                    out.write(bytes);
                }
                out.flush();
                return;
            }
            if (reverse) {
                reverse = false;
                ctx.status(200);
                ctx.contentType("text/event-stream");
                ctx.header("mcp-session-id", sessionId);
                var out = ctx.res().getOutputStream();
                out.write("""
                        data: {"jsonrpc":"2.0","id":0,"method":"sampling/createMessage","params":{}}

                        """.getBytes(StandardCharsets.UTF_8));
                out.flush();
                ctx.res().flushBuffer();
                long wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (replies.get() == 0 && System.nanoTime() < wait) {
                    Thread.sleep(10);
                }
                out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            write(ctx, sessionId, payload, mode == Mode.SSE);
        }

        private String payload(JsonNode id) {
            String idText = id.isNumber() ? id.asText() : "1";
            if (rpcCode != 0) {
                return """
                        {"result":null,"jsonrpc":"2.0","id":%s,"error":{"code":%d,"message":"%s"}}
                        """.formatted(idText, rpcCode, rpcMessage);
            }
            if (toolError != null) {
                return """
                        {"result":{"content":[{"type":"text","text":"%s"}],"isError":true},"jsonrpc":"2.0","id":%s}
                        """.formatted(toolError, idText);
            }
            if (plainError) {
                return """
                        {"result":{"content":[{"type":"text","text":"Error: audience","annotations":{"audience":["user"],"priority":0.2}},{"type":"image","data":"abcd","mimeType":"image/png"}]},"jsonrpc":"2.0","id":%s}
                        """.formatted(idText);
            }
            return """
                    {"result":{"content":[{"type":"text","text":"ok"}],"structuredContent":{"name":"ada","secret":"%s"}},"jsonrpc":"2.0","id":%s}
                    """.formatted(sessionId, idText);
        }

        private void write(io.javalin.http.Context ctx, String session, String json, boolean sse) throws Exception {
            ctx.status(200);
            ctx.header("mcp-session-id", session);
            if (!sse) {
                ctx.contentType("application/json; charset=utf-8");
                ctx.result(json);
                return;
            }
            writeRaw(ctx, ": keepalive\n\nid: 1\ndata:\n\ndata: " + json + "\n\n");
        }

        private void writeRaw(io.javalin.http.Context ctx, String body) throws Exception {
            ctx.status(200);
            ctx.contentType("text/event-stream");
            ctx.header("mcp-session-id", sessionId);
            var out = ctx.res().getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
            ctx.res().flushBuffer();
        }

        private void record(io.javalin.http.Context ctx, String kind) {
            String body = ctx.body();
            String authorization = ctx.header("Authorization");
            hits.add(new Hit(
                    kind,
                    ctx.header("Mcp-Session-Id"),
                    ctx.header("MCP-Protocol-Version"),
                    authorization,
                    ctx.header("X-Tenant"),
                    ctx.header("Origin"),
                    ctx.header("Cookie"),
                    ctx.header("Accept"),
                    ctx.header("Content-Type"),
                    body,
                    authorization != null && authorization.contains("hk_")));
        }

        @Override
        public void close() {
            app.stop();
        }
    }
}
