package dev.hermit.reef.conch;

import dev.hermit.common.mcp.McpAddresses;
import dev.hermit.common.mcp.ToolSnapshots;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.McpAuth;
import dev.hermit.common.model.McpServerInfo;
import dev.hermit.common.model.McpSnapshot;
import dev.hermit.common.model.McpTool;
import dev.hermit.common.model.McpUpstream;
import java.util.ArrayList;
import java.util.List;

/**
 * 从已保存的规则包投影管理端和清单要的 MCP 字段。不解析 DNS，不连接上游。
 * 没有 {@code driver} 或空白时按 {@code http}。只有 {@code driver: mcp} 才填上游、快照和工具表。
 */
final class McpPackageView {

    private McpPackageView() {
    }

    static Projection of(ConchDoc doc) {
        boolean mcp = doc != null && "mcp".equals(doc.driver());
        return new Projection(
                driver(doc),
                mcp ? upstream(doc.upstream()) : null,
                mcp ? snapshot(doc.snapshot()) : null,
                mcp ? tools(doc) : null,
                mcp ? upstreamHost(doc.upstream()) : null);
    }

    private static String driver(ConchDoc doc) {
        if (doc == null || doc.driver() == null || doc.driver().isBlank()) {
            return "http";
        }
        return doc.driver();
    }

    private static UpstreamView upstream(McpUpstream upstream) {
        if (upstream == null) {
            return null;
        }
        McpAuth auth = upstream.auth();
        return new UpstreamView(
                upstream.url(),
                upstream.transport(),
                auth == null ? null : new UpstreamAuthView(auth.type()),
                upstream.allowedHosts());
    }

    private static SnapshotView snapshot(McpSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        McpServerInfo server = snapshot.server();
        return new SnapshotView(
                snapshot.fetchedAt(),
                snapshot.protocolVersion(),
                server == null ? null : new SnapshotServerView(server.name(), server.version()),
                snapshot.toolsSha256());
    }

    private static List<ToolView> tools(ConchDoc doc) {
        ArrayList<ToolView> rows = new ArrayList<>();
        for (Action action : doc.actions().values()) {
            McpTool tool = action.tool();
            if (tool == null) {
                continue;
            }
            rows.add(new ToolView(
                    action.name(),
                    tool.name(),
                    action.mcp().readOnly(),
                    action.effectiveDestructive(),
                    action.effectiveConfirm(),
                    tool.upstreamAnnotations(),
                    ToolSnapshots.schemaSha256(tool.inputSchema(), tool.outputSchema())));
        }
        return List.copyOf(rows);
    }

    private static String upstreamHost(McpUpstream upstream) {
        if (upstream == null) {
            return null;
        }
        McpAddresses.ParseResult parsed = McpAddresses.parse(upstream.url());
        return parsed.ok() ? parsed.url().host() : null;
    }

    record Projection(
            String driver,
            UpstreamView upstream,
            SnapshotView snapshot,
            List<ToolView> tools,
            String upstreamHost) {
    }
}
