package dev.hermit.crab.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import dev.hermit.crab.store.Origin;

public record ExecutionRequest(
        String traceId,
        Origin origin,
        String shell,
        String action,
        Map<String, Object> rawInput,
        String account,
        AccountContext accountContext
) {
    public ExecutionRequest {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(accountContext, "accountContext");
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        if (rawInput != null) {
            copy.putAll(rawInput);
        }
        rawInput = Collections.unmodifiableMap(copy);
    }
}
