package dev.hermit.crab.runtime;

import java.util.Map;
import java.util.Optional;

import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.Origin;

public record Invocation(
        String traceId,
        Origin origin,
        ApiKeyPrincipal principal,
        String shell,
        String action,
        String account,
        Map<String, Object> input,
        Optional<String> idempotencyKey
) {
    @Override
    public String toString() {
        return "Invocation[traceId=" + traceId + ", shell=" + shell + ", action=" + action + ", account=" + account + "]";
    }
}
