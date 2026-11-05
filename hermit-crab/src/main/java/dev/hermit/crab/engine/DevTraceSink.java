package dev.hermit.crab.engine;

import java.time.Instant;

/**
 * Opens an HTTP step trace at the start of {@link RuleEngine#execute} and stores the
 * already-redacted body when that call finishes.
 *
 * <p>MCP JSON-RPC traces do not use this sink. A forwarder records those with
 * {@link dev.hermit.crab.store.DevTraceStore#recordJsonRpc}.
 */
public interface DevTraceSink {
    /** Null when this call must not capture a body. */
    DevTraceDraft open(ExecutionRequest request);

    /** Persists a body that was already redacted and truncated. Does not check the debug switch again. */
    void save(ExecutionRequest request, Instant expiresAt, String body);
}
