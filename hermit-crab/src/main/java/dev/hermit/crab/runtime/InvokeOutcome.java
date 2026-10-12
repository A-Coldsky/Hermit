package dev.hermit.crab.runtime;

public final class InvokeOutcome {
    private final int status;
    private final byte[] body;
    private final String traceId;
    private final boolean replayed;

    public InvokeOutcome(int status, byte[] body, String traceId, boolean replayed) {
        this.status = status;
        this.body = body;
        this.traceId = traceId;
        this.replayed = replayed;
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

    @Override
    public String toString() {
        return "InvokeOutcome[status=" + status + ", replayed=" + replayed + ", traceId=" + traceId + "]";
    }
}
