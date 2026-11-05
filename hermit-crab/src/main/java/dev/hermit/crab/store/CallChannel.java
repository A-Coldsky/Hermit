package dev.hermit.crab.store;

/**
 * How a capability call entered {@code Invoker}. Separate from {@code call_logs.source}, which
 * records whether the conch is {@code LOCAL} or {@code REEF}.
 */
public enum CallChannel {
    /** External OpenAPI ({@code /api} and aliases). */
    HTTP,
    MCP,
    /**
     * Console and CLI trial runs. Test login does not write a call log.
     */
    CONSOLE;

    /**
     * Blank means no filter. Any other value that is not one of the enum names is rejected
     * without echoing the query value.
     */
    public static CallChannel parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        for (CallChannel channel : values()) {
            if (channel.name().equals(trimmed)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("channel is invalid");
    }
}
