package dev.hermit.crab.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.model.Action;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CapabilityHttp;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.store.ApiKeyPrincipal;
import io.modelcontextprotocol.spec.McpSchema;

final class McpCalls {
    static final String NO_CONFIRM = "客户端不支持确认，拒绝执行写操作";
    static final String REJECTED = "用户拒绝执行";

    private final CrabRuntime runtime;
    private final McpCatalog catalog;

    McpCalls(CrabRuntime runtime, McpCatalog catalog) {
        this.runtime = runtime;
        this.catalog = catalog;
    }

    McpSchema.CallToolResult call(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            JsonNode params
    ) {
        McpSchema.CallToolRequest request;
        try {
            request = McpJson.convert(params, McpSchema.CallToolRequest.class);
        } catch (IllegalArgumentException ex) {
            return toolError("INVALID_INPUT", "invalid params");
        }
        String name = request.name() == null ? "" : request.name();
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        if (catalog.directory(principal)) {
            return directory(principal, session, headerAccount, name, arguments);
        }
        Optional<McpCatalog.CapabilityRef> found = catalog.find(principal, name);
        if (found.isEmpty()) {
            return toolError("NOT_FOUND", "not found");
        }
        return invokeDirect(principal, headerAccount, found.get(), arguments);
    }

    private McpSchema.CallToolResult directory(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            String name,
            Map<String, Object> arguments
    ) {
        return switch (name) {
            case McpCatalog.LIST_SYSTEMS -> ok(catalog.listSystems(principal));
            case McpCatalog.SEARCH -> search(principal, arguments);
            case McpCatalog.DESCRIBE -> describe(principal, arguments);
            case McpCatalog.CALL -> callCapability(principal, session, headerAccount, arguments);
            default -> toolError("NOT_FOUND", "not found");
        };
    }

    private McpSchema.CallToolResult search(ApiKeyPrincipal principal, Map<String, Object> arguments) {
        Object query = arguments.get("query");
        if (!(query instanceof String text) || text.isBlank()) {
            return toolError("INVALID_INPUT", "query is required");
        }
        Object shell = arguments.get("shell");
        if (shell != null && !(shell instanceof String)) {
            return toolError("INVALID_INPUT", "invalid shell");
        }
        return ok(catalog.search(principal, text, shell instanceof String name ? name : null));
    }

    private McpSchema.CallToolResult describe(ApiKeyPrincipal principal, Map<String, Object> arguments) {
        Object name = arguments.get("name");
        if (!(name instanceof String text) || text.isBlank()) {
            return toolError("INVALID_INPUT", "name is required");
        }
        return catalog.describe(principal, text)
                .map(McpCalls::ok)
                .orElseGet(() -> toolError("NOT_FOUND", "not found"));
    }

    private McpSchema.CallToolResult callCapability(
            ApiKeyPrincipal principal,
            McpSession session,
            Optional<String> headerAccount,
            Map<String, Object> arguments
    ) {
        Object name = arguments.get("name");
        if (!(name instanceof String text) || text.isBlank()) {
            return toolError("INVALID_INPUT", "name is required");
        }
        Optional<McpCatalog.CapabilityRef> found = catalog.find(principal, text);
        if (found.isEmpty()) {
            return toolError("NOT_FOUND", "not found");
        }
        Object rawArguments = arguments.get("arguments");
        Map<String, Object> input = copyObject(rawArguments);
        if (rawArguments != null && input == null) {
            return toolError("INVALID_INPUT", "arguments must be an object");
        }
        if (input == null) {
            input = Map.of();
        }
        Optional<String> account = account(headerAccount, arguments.get("account"));
        if (account == null) {
            return toolError("INVALID_INPUT", "invalid account name");
        }
        if (!CapabilityHttp.readOnly(found.get().action())) {
            McpSchema.CallToolResult confirmed = confirm(session, found.get().action(), input, account);
            if (confirmed != null) {
                return confirmed;
            }
        }
        return invoke(principal, found.get(), input, account);
    }

    private McpSchema.CallToolResult invokeDirect(
            ApiKeyPrincipal principal,
            Optional<String> headerAccount,
            McpCatalog.CapabilityRef ref,
            Map<String, Object> arguments
    ) {
        LinkedHashMap<String, Object> input = new LinkedHashMap<>(arguments);
        String selector = ref.action().input().containsKey("account") ? "_account" : "account";
        Object rawAccount = input.remove(selector);
        Optional<String> account = account(headerAccount, rawAccount);
        if (account == null) {
            return toolError("INVALID_INPUT", "invalid account name");
        }
        return invoke(principal, ref, input, account);
    }

    private McpSchema.CallToolResult confirm(
            McpSession session,
            Action action,
            Map<String, Object> input,
            Optional<String> account
    ) {
        if (session == null || !session.supportsElicitation() || !session.streaming()) {
            return toolError("FORBIDDEN", NO_CONFIRM);
        }
        StringBuilder message = new StringBuilder("确认执行 ");
        message.append(action.title() == null || action.title().isBlank() ? action.name() : action.title());
        message.append("？");
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            message.append('\n').append(entry.getKey()).append('=').append(ApiJson.write(entry.getValue()));
        }
        if (account.isPresent()) {
            message.append("\naccount=").append(ApiJson.write(account.get()));
        }
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("confirm", Map.of("type", "boolean", "description", "确认执行")));
        JsonNode answer;
        try {
            answer = session.elicit(McpSchema.ElicitFormRequest.builder(message.toString(), schema).build());
        } catch (TimeoutException ex) {
            return toolError("INTERNAL", "确认超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return toolError("INTERNAL", "internal error");
        }
        if (answer == null || !"accept".equals(answer.path("action").asText(""))) {
            return toolError("FORBIDDEN", REJECTED);
        }
        return null;
    }

    private McpSchema.CallToolResult invoke(
            ApiKeyPrincipal principal,
            McpCatalog.CapabilityRef ref,
            Map<String, Object> input,
            Optional<String> account
    ) {
        String traceId = UUID.randomUUID().toString();
        InvokeOutcome outcome;
        try {
            outcome = runtime.invoker().invoke(
                    principal,
                    ref.shell().shell(),
                    ref.action().name(),
                    input,
                    account,
                    Optional.empty(),
                    traceId);
        } catch (RuntimeException ex) {
            return toolError("INTERNAL", "internal error");
        }
        return fromOutcome(outcome);
    }

    private static McpSchema.CallToolResult fromOutcome(InvokeOutcome outcome) {
        JsonNode envelope;
        try {
            envelope = ApiJson.tree(outcome.body());
        } catch (IllegalArgumentException ex) {
            return toolError("INTERNAL", "internal error");
        }
        if (envelope.path("success").asBoolean(false)) {
            return ok(structured(envelope.get("data")));
        }
        String code = envelope.path("error").path("code").asText("INTERNAL");
        String message = envelope.path("error").path("message").asText("");
        return toolError(code, message);
    }

    private static Object structured(JsonNode data) {
        if (data == null || data.isNull()) {
            return Map.of();
        }
        if (data.isObject()) {
            return McpJson.mapper().convertValue(data, Map.class);
        }
        return Map.of("value", McpJson.mapper().convertValue(data, Object.class));
    }

    /** {@code null} means the selector was not a string. Blank falls back to the header. */
    private static Optional<String> account(Optional<String> header, Object raw) {
        if (raw == null) {
            return header;
        }
        if (!(raw instanceof String text)) {
            return null;
        }
        if (text.isBlank()) {
            return header;
        }
        return Optional.of(text);
    }

    private static Map<String, Object> copyObject(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    static McpSchema.CallToolResult ok(Object structured) {
        Object body = structured == null ? Map.of() : structured;
        return McpSchema.CallToolResult.builder()
                .addTextContent(ApiJson.write(body))
                .structuredContent(body)
                .isError(false)
                .build();
    }

    static McpSchema.CallToolResult toolError(String code, String message) {
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("code", code == null ? "INTERNAL" : code);
        body.put("message", message == null ? "" : message);
        return McpSchema.CallToolResult.builder()
                .addTextContent(ApiJson.write(body))
                .isError(true)
                .build();
    }
}
