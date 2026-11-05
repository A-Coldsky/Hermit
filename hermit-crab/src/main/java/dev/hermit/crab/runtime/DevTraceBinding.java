package dev.hermit.crab.runtime;

import java.time.Instant;

import dev.hermit.crab.engine.DevTraceDraft;
import dev.hermit.crab.engine.DevTraceSink;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.store.DebugStateStore;
import dev.hermit.crab.store.DevTraceStore;
import dev.hermit.crab.store.Origin;

/**
 * HTTP step traces for local conches while the debug switch is on.
 * The switch is read here, once, when the call starts. A later expiry does not drop a trace
 * that this call already opened. MCP JSON-RPC traces use {@link DevTraceStore#recordJsonRpc}.
 */
public final class DevTraceBinding implements DevTraceSink {
    private final DebugStateStore debug;
    private final DevTraceStore traces;

    public DevTraceBinding(DebugStateStore debug, DevTraceStore traces) {
        this.debug = debug;
        this.traces = traces;
    }

    @Override
    public DevTraceDraft open(ExecutionRequest request) {
        if (request == null || request.origin() != Origin.LOCAL) {
            return null;
        }
        DebugStateStore.DebugSnapshot snapshot = debug.get();
        if (!snapshot.enabled()) {
            return null;
        }
        return new DevTraceDraft(snapshot.expiresAt());
    }

    @Override
    public void save(ExecutionRequest request, Instant expiresAt, String body) {
        if (request == null || body == null || request.traceId() == null || request.traceId().isBlank()) {
            return;
        }
        traces.save(new DevTraceStore.NewTrace(
                request.traceId(),
                request.origin(),
                request.conch(),
                request.account(),
                request.action(),
                expiresAt,
                body));
    }
}
