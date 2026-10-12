package dev.hermit.crab.runtime;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.HttpMethod;

/**
 * Shared by the local gateway and, later, MCP. An explicit method wins; otherwise
 * {@code mcp.read_only} or {@code idempotent} selects GET. Read-only is only {@code mcp.read_only}.
 */
public final class CapabilityHttp {
    private CapabilityHttp() {
    }

    public static HttpMethod method(Action action) {
        return action.effectiveMethod();
    }

    public static boolean readOnly(Action action) {
        return action.mcp().readOnly();
    }
}
