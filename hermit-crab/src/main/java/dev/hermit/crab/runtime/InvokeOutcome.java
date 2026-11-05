package dev.hermit.crab.runtime;

import dev.hermit.crab.upstream.McpCallPayload;

public final class InvokeOutcome {
    private final int status;
    private final byte[] body;
    private final String traceId;
    private final boolean replayed;
    private final McpCallPayload mcpCall;

    public InvokeOutcome(int status, byte[] body, String traceId, boolean replayed) {
        this(status, body, traceId, replayed, null);
    }

    public InvokeOutcome(int status, byte[] body, String traceId, boolean replayed, McpCallPayload mcpCall) {
        this.status = status;
        this.body = body;
        this.traceId = traceId;
        this.replayed = replayed;
        this.mcpCall = mcpCall;
    }

    public int status() {
        return status;
    }

    public byte[] body() {
        return body;
    }

    public String traceId() {
        return traceId;
    }

    public boolean replayed() {
        return replayed;
    }

    /** 上游 MCP 结果。HTTP 回放没有这份实时载荷，信封里的 data 已经够用。 */
    public McpCallPayload mcpCall() {
        return mcpCall;
    }

    @Override
    public String toString() {
        return "InvokeOutcome[status=" + status + ", replayed=" + replayed + ", traceId=" + traceId + "]";
    }
}
