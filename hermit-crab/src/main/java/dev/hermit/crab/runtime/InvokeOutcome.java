package dev.hermit.crab.runtime;

import dev.hermit.crab.upstream.McpCallPayload;

public final class InvokeOutcome {
    private final int status;
    private final byte[] body;
    private final String traceId;
    private final boolean replayed;
    private final McpCallPayload mcpCall;
    private final boolean requestOnly;
    private final String allowMethod;

    public InvokeOutcome(int status, byte[] body, String traceId, boolean replayed) {
        this(status, body, traceId, replayed, null);
    }

    public InvokeOutcome(int status, byte[] body, String traceId, boolean replayed, McpCallPayload mcpCall) {
        this(status, body, traceId, replayed, mcpCall, false);
    }

    public InvokeOutcome(
            int status,
            byte[] body,
            String traceId,
            boolean replayed,
            McpCallPayload mcpCall,
            boolean requestOnly
    ) {
        this(status, body, traceId, replayed, mcpCall, requestOnly, null);
    }

    public InvokeOutcome(
            int status,
            byte[] body,
            String traceId,
            boolean replayed,
            McpCallPayload mcpCall,
            boolean requestOnly,
            String allowMethod
    ) {
        this.status = status;
        this.body = body;
        this.traceId = traceId;
        this.replayed = replayed;
        this.mcpCall = mcpCall;
        this.requestOnly = requestOnly;
        this.allowMethod = allowMethod;
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

    /** The id in the body is a request number. No trace was stored and {@link #traceId()} is null. */
    public boolean requestOnly() {
        return requestOnly;
    }

    /** 上游 MCP 结果。HTTP 回放没有这份实时载荷，信封里的 data 已经够用。 */
    public McpCallPayload mcpCall() {
        return mcpCall;
    }

    /** 405 时允许的 HTTP 方法名（写入响应头 {@code Allow}），其它响应为 null。 */
    public String allowMethod() {
        return allowMethod;
    }

    @Override
    public String toString() {
        return "InvokeOutcome[status=" + status + ", replayed=" + replayed + ", traceId=" + traceId + "]";
    }
}
