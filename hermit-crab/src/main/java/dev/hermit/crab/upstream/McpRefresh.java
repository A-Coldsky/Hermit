package dev.hermit.crab.upstream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.validate.ConchDiff;
import dev.hermit.common.validate.ToolChange;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.runtime.StatusCodes;
import dev.hermit.crab.runtime.TrialRejected;
import dev.hermit.crab.sync.LocalConchException;
import dev.hermit.crab.sync.LocalConches;

/** 调试开关打开时，用上游 {@code tools/list} 重写本地 MCP 包的工具快照。不写调用日志。 */
public final class McpRefresh {
    private McpRefresh() {
    }

    public static Outcome apply(
            McpForwarder forwarder,
            LocalConches localConches,
            ConchRegistry registry,
            ConchDoc doc,
            ExecutionRequest request,
            String actor,
            Instant fetchedAt
    ) {
        McpForwarder.ListOutcome listed = forwarder.listTools(request, doc);
        if (listed.failure() != null) {
            throw rejected(listed.failure());
        }
        byte[] current;
        try {
            current = localConches.export(doc.conch()).bytes();
        } catch (LocalConchException ex) {
            throw new TrialRejected(404, ErrorCode.NOT_FOUND, "not found");
        }
        byte[] next;
        try {
            next = McpToolMerge.rewrite(
                    current,
                    listed.tools(),
                    listed.protocol(),
                    listed.serverName(),
                    listed.serverVersion(),
                    fetchedAt);
        } catch (McpToolMerge.Rejected ex) {
            throw new TrialRejected(StatusCodes.of(ex.code()), ex.code(), ex.getMessage());
        }
        localConches.importBytes(next, actor, true);
        ConchDoc updated = registry.find(doc.conch(), dev.hermit.crab.store.Origin.LOCAL).orElse(doc);
        ConchDiff diff = ConchDiff.between(doc, updated);
        ArrayList<String> added = new ArrayList<>();
        ArrayList<String> removed = new ArrayList<>();
        ArrayList<String> changed = new ArrayList<>();
        for (ToolChange change : diff.toolChanges()) {
            if (ToolChange.ADDED.equals(change.kind())) {
                added.add(change.name());
            } else if (ToolChange.REMOVED.equals(change.kind())) {
                removed.add(change.name());
            } else if (ToolChange.CHANGED.equals(change.kind())) {
                changed.add(change.name());
            }
        }
        return new Outcome(
                updated.version().toString(),
                List.copyOf(added),
                List.copyOf(removed),
                List.copyOf(changed),
                request.traceId());
    }

    private static TrialRejected rejected(ExecutionResult failure) {
        ErrorCode code = failure.error() == null ? ErrorCode.UPSTREAM_ERROR : failure.error().code();
        String message = failure.error() == null || failure.error().message() == null
                ? "上游响应不完整。"
                : failure.error().message();
        return new TrialRejected(StatusCodes.of(code), code, message);
    }

    public record Outcome(
            String version,
            List<String> added,
            List<String> removed,
            List<String> changed,
            String traceId
    ) {
    }
}
