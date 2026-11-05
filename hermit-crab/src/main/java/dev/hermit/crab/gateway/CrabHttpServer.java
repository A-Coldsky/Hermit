package dev.hermit.crab.gateway;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.mcp.McpEndpoint;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.BodyDigests;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.IdempotencyKeys;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.runtime.Invoker;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.CallChannel;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpResponseException;

/**
 * Local HTTP server. {@code /mcp} is the caller-side MCP endpoint. Unmatched routes use the same
 * envelope as capability calls and do not echo the request path.
 */
public final class CrabHttpServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(CrabHttpServer.class.getName());
    /**
     * 不是合法账号名。头和查询参数冲突时用它走一遍 Invoker：权限、能力停用和未加载墓碑会先返回；
     * 这些检查都通过时停在账号名校验，不执行规则，也不打上游。
     */
    private static final String ACCOUNT_ADMISSION_PROBE = "*";
    private static final HandlerType[] METHODS = {
            HandlerType.GET,
            HandlerType.POST,
            HandlerType.PUT,
            HandlerType.PATCH,
            HandlerType.DELETE,
            HandlerType.HEAD,
            HandlerType.OPTIONS
    };

    private final CrabRuntime runtime;
    private final McpEndpoint mcp;
    private final Javalin app;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CrabHttpServer(CrabRuntime runtime) {
        this.runtime = runtime;
        checkAliases(runtime.config().aliases());
        this.mcp = new McpEndpoint(runtime);
        this.app = build();
    }

    public void start() {
        app.start(runtime.config().serverHost(), runtime.config().serverPort());
        started.set(true);
    }

    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        mcp.close();
        if (started.get()) {
            app.stop();
        }
    }

    private Javalin build() {
        Javalin http = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.maxRequestSize = RequestBodies.MAX_BYTES;
        });
        http.before(ctx -> {
            GatewayResponses.trace(ctx);
            String path = ctx.path();
            if (consoleApi(path)) {
                if (!Loopback.address(ctx.req().getRemoteAddr()) || !"1".equals(ctx.header("X-Hermit-Console"))) {
                    GatewayResponses.writeError(ctx, 403, ErrorCode.FORBIDDEN, "console access denied");
                    ctx.skipRemainingHandlers();
                }
            } else if (consolePage(path) && !Loopback.address(ctx.req().getRemoteAddr())) {
                // 浏览器导航带不了 X-Hermit-Console，页面和静态资源只认套接字上的回环地址。
                GatewayResponses.writeError(ctx, 403, ErrorCode.FORBIDDEN, "console access denied");
                ctx.skipRemainingHandlers();
            }
        });
        mcp.register(http);
        http.get("/openapi.json", ctx -> {
            byte[] body = OpenApiDocument.bytes(runtime, http.port());
            GatewayResponses.write(ctx, 200, body, GatewayResponses.trace(ctx), false);
        });
        route(http, "/api/{conch}/{action}", ctx -> handleApi(ctx, ctx.pathParam("conch"), ctx.pathParam("action")));
        for (Map.Entry<String, String> alias : runtime.config().aliases().entrySet()) {
            int dot = alias.getValue().indexOf('.');
            String conch = alias.getValue().substring(0, dot);
            String action = alias.getValue().substring(dot + 1);
            route(http, alias.getKey(), ctx -> handleApi(ctx, conch, action));
        }
        ConsoleApi.register(http, runtime);
        ConsoleUi.register(http);
        http.exception(HttpResponseException.class, (exception, ctx) -> {
            if (GatewayResponses.written(ctx)) {
                return;
            }
            int status = exception.getStatus();
            if (status == 404) {
                GatewayResponses.writeError(ctx, 404, ErrorCode.NOT_FOUND, "not found");
            } else if (status == 413) {
                GatewayResponses.writeError(ctx, 400, ErrorCode.INVALID_INPUT, "request body too large");
            } else {
                GatewayResponses.writeError(ctx, 500, ErrorCode.INTERNAL, "internal error");
            }
        });
        http.exception(Exception.class, (exception, ctx) -> {
            LOG.warning("request failed: " + exception.getClass().getName());
            GatewayResponses.writeError(ctx, 500, ErrorCode.INTERNAL, "internal error");
        });
        return http;
    }

    private void handleApi(Context ctx, String conch, String action) throws IOException {
        Optional<Action> enabled = runtime.enabledAction(conch, action);
        String token = token(ctx);
        if (token == null) {
            GatewayResponses.writeError(ctx, 401, ErrorCode.AUTH_FAILED, "invalid api key");
            return;
        }
        Optional<ApiKeyPrincipal> principal = runtime.apiKeys().authenticate(token);
        if (principal.isEmpty()) {
            GatewayResponses.writeError(ctx, 401, ErrorCode.AUTH_FAILED, "invalid api key");
            return;
        }
        String method = ctx.req().getMethod();
        if (enabled.isPresent() && !enabled.get().effectiveMethod().name().equals(method)) {
            GatewayResponses.writeError(ctx, 405, ErrorCode.INVALID_INPUT, "method not allowed");
            return;
        }
        Map<String, Object> input = Map.of();
        Optional<String> idempotency = Optional.empty();
        String bodySha256 = null;
        Optional<String> queryAccount = Optional.empty();
        String selector = "account";
        if (enabled.isPresent() && CapabilityHttp.method(enabled.get()) == HttpMethod.GET) {
            selector = accountQueryName(enabled.get(), HttpMethod.GET);
            queryAccount = queryValue(ctx, selector);
            input = queryInput(ctx, selector);
        } else if (enabled.isPresent() && CapabilityHttp.method(enabled.get()) == HttpMethod.POST) {
            byte[] raw;
            try {
                raw = RequestBodies.read(ctx.req());
                input = ApiJson.objectMap(raw);
            } catch (RequestBodies.BodyTooLargeException ex) {
                GatewayResponses.writeError(ctx, 400, ErrorCode.INVALID_INPUT, "request body too large");
                return;
            } catch (IllegalArgumentException ex) {
                GatewayResponses.writeError(ctx, 400, ErrorCode.INVALID_INPUT, bodyMessage(ex));
                return;
            }
            String key = ctx.header("Idempotency-Key");
            if (key != null) {
                if (!IdempotencyKeys.valid(key)) {
                    GatewayResponses.writeError(ctx, 400, ErrorCode.INVALID_INPUT, "invalid Idempotency-Key");
                    return;
                }
                idempotency = Optional.of(key);
                bodySha256 = BodyDigests.sha256(raw);
            }
            selector = accountQueryName(enabled.get(), HttpMethod.POST);
            queryAccount = queryValue(ctx, selector);
        }
        Optional<String> headerAccount = headerAccount(ctx);
        ApiKeyPrincipal caller = principal.get();
        if (headerAccount.isPresent() && queryAccount.isPresent() && !headerAccount.get().equals(queryAccount.get())) {
            if (writeEarlierRejection(ctx, caller, conch, action)) {
                return;
            }
            GatewayResponses.writeError(ctx, 400, ErrorCode.INVALID_INPUT, accountMismatch(selector));
            return;
        }
        Optional<String> accountName = headerAccount.isPresent() ? headerAccount : queryAccount;
        InvokeOutcome outcome = runtime.invoker().invoke(
                caller,
                conch,
                action,
                input,
                accountName,
                idempotency,
                bodySha256,
                CallChannel.HTTP,
                GatewayResponses.trace(ctx));
        GatewayResponses.writeOutcome(ctx, outcome);
    }

    /**
     * GET 入参已经占用 {@code account} 时，查询参数改用 {@code _account}，避免把业务字段当成账号选择。
     * POST 的正文和查询参数不在同一个位置，查询参数始终是 {@code account}。
     */
    static String accountQueryName(Action action, HttpMethod method) {
        if (method == HttpMethod.GET && action.input().containsKey("account")) {
            return "_account";
        }
        return "account";
    }

    static String accountMismatch(String selector) {
        return "请求头 X-Hermit-Account 与查询参数 " + selector + " 不一致。";
    }

    /**
     * @return 已经写过更早的拒绝（权限、能力停用、不存在）时为 true。账号名探针被拒绝时为 false，调用方再写冲突 400。
     */
    private boolean writeEarlierRejection(Context ctx, ApiKeyPrincipal principal, String conch, String action) {
        InvokeOutcome gate = runtime.invoker().invoke(
                principal,
                conch,
                action,
                Map.of(),
                Optional.of(ACCOUNT_ADMISSION_PROBE),
                Optional.empty(),
                null,
                CallChannel.HTTP,
                GatewayResponses.trace(ctx));
        if (invalidAccountName(gate)) {
            return false;
        }
        GatewayResponses.writeOutcome(ctx, gate);
        return true;
    }

    private static boolean invalidAccountName(InvokeOutcome outcome) {
        if (outcome.status() != 400) {
            return false;
        }
        JsonNode error = ApiJson.tree(outcome.body()).path("error");
        return ErrorCode.INVALID_INPUT.name().equals(error.path("code").asText())
                && Invoker.INVALID_ACCOUNT_NAME.equals(error.path("message").asText());
    }

    private static Optional<String> headerAccount(Context ctx) {
        String account = ctx.header("X-Hermit-Account");
        if (account == null || account.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(account);
    }

    private static Optional<String> queryValue(Context ctx, String name) {
        List<String> values = ctx.queryParamMap().get(name);
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(values.get(0));
    }

    private static Map<String, Object> queryInput(Context ctx, String skip) {
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : ctx.queryParamMap().entrySet()) {
            if (skip != null && skip.equals(entry.getKey())) {
                continue;
            }
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                input.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return input;
    }

    private static String token(Context ctx) {
        String authorization = ctx.header("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearer = authorization.substring(7).trim();
            if (!bearer.isEmpty()) {
                return bearer;
            }
        }
        String header = ctx.header("X-Hermit-Key");
        if (header == null) {
            return null;
        }
        String value = header.trim();
        return value.isEmpty() ? null : value;
    }

    private static String bodyMessage(IllegalArgumentException ex) {
        String message = ex.getMessage();
        if ("invalid json".equals(message) || "request body must be a json object".equals(message)) {
            return message;
        }
        return "invalid json";
    }

    private static void route(Javalin http, String path, io.javalin.http.Handler handler) {
        for (HandlerType method : METHODS) {
            http.addHttpHandler(method, path, handler);
        }
    }

    private static boolean consoleApi(String path) {
        return "/console/api".equals(path) || path.startsWith("/console/api/");
    }

    private static boolean consolePage(String path) {
        return "/console".equals(path) || path.startsWith("/console/");
    }

    static void checkAliases(Map<String, String> aliases) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            CrabConfig.requireAlias(alias.getKey(), alias.getValue());
            if (alias.getKey().indexOf('{') >= 0 || alias.getKey().indexOf('}') >= 0) {
                throw new IllegalArgumentException("alias path cannot contain parameters");
            }
            if (!seen.add(alias.getKey())) {
                throw new IllegalStateException("alias conflict");
            }
        }
    }
}
