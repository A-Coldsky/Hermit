package dev.hermit.crab.runtime;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.CapabilityKind;
import dev.hermit.common.model.HttpMethod;

/**
 * Shared by the local gateway and, later, MCP. An explicit method wins; otherwise
 * {@code mcp.read_only} or {@code idempotent} selects GET. Read-only means the capability kind is READ:
 * {@code mcp.read_only} without {@code mcp.destructive}. A capability marked both is sensitive, so a
 * read-only API key can neither see nor run it.
 */
public final class CapabilityHttp {
    private CapabilityHttp() {
    }

    public static HttpMethod method(Action action) {
        return action.effectiveMethod();
    }

    public static boolean readOnly(Action action) {
        return action.kind() == CapabilityKind.READ;
    }
}
