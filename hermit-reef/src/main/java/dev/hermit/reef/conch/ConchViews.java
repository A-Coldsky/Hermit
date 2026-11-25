package dev.hermit.reef.conch;

import com.fasterxml.jackson.databind.JsonNode;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.Example;
import dev.hermit.common.model.GroupDef;
import dev.hermit.common.model.McpHints;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.validate.ValidationIssue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

record UploadResponse(String conch, String version, List<ValidationIssue> warnings, JsonNode diff) {
}

record PublishResponse(String conch, String version) {
}

record DiscardResponse(String conch, String version) {
}

record ConchAvailabilityView(
        String conch,
        boolean disabled,
        Instant disabledAt,
        String disabledBy,
        String disabledReason) {
}

record SigningKeyResponse(String publicKey) {
}

record ParseFailure(String path, String message) {
}

record ValidationFailure(List<ValidationIssue> errors, List<ValidationIssue> warnings) {
}

record ConchSummary(
        String conch,
        String version,
        boolean published,
        String title,
        String description,
        String owner,
        List<String> tags,
        int capabilityCount,
        Instant publishedAt,
        boolean disabled,
        Instant disabledAt,
        String disabledBy,
        String disabledReason,
        long pullCount,
        String driver,
        UpstreamView upstream,
        SnapshotView snapshot,
        List<ToolView> tools,
        String authUse) {
}

record ConchVersionItem(
        String version,
        String status,
        String uploadedBy,
        Instant uploadedAt,
        String publishedBy,
        Instant publishedAt,
        boolean current,
        int warningCount,
        List<ValidationIssue> warnings,
        long pullCount) {
}

record CredentialDeclaration(
        String type,
        String label,
        boolean required,
        String description,
        List<String> options) {

    static CredentialDeclaration from(CredentialField field) {
        return new CredentialDeclaration(field.type(), field.label(), field.required(), field.description(), field.options());
    }
}

record ConfigDeclaration(
        String type,
        String label,
        boolean required,
        String description,
        List<String> options,
        @com.fasterxml.jackson.annotation.JsonProperty("default") Object defaultValue) {

    static ConfigDeclaration from(ConfigField field) {
        return new ConfigDeclaration(
                field.type(), field.label(), field.required(), field.description(), field.options(), field.defaultValue());
    }
}

record ExampleView(Object input, Object output) {

    static ExampleView from(Example example) {
        return new ExampleView(example.input(), example.output());
    }
}

record McpView(boolean expose, boolean readOnly, boolean destructive) {

    static McpView from(McpHints hints) {
        return new McpView(hints.expose(), hints.readOnly(), hints.destructive());
    }
}

record GroupView(
        String name,
        String title,
        String description,
        List<GroupView> children) {

    static GroupView from(GroupDef group) {
        ArrayList<GroupView> children = new ArrayList<>(group.children().size());
        for (GroupDef child : group.children().values()) {
            children.add(new GroupView(child.name(), child.title(), child.description(), List.of()));
        }
        return new GroupView(group.name(), group.title(), group.description(), List.copyOf(children));
    }
}

record CapabilityView(
        String name,
        String title,
        String description,
        String group,
        String effectiveMethod,
        Map<String, Object> inputJsonSchema,
        Map<String, Object> outputJsonSchema,
        List<ExampleView> examples,
        String notes,
        List<String> related,
        McpView mcp) {

    static CapabilityView from(Action action) {
        return new CapabilityView(
                action.name(),
                action.title(),
                action.description(),
                action.group(),
                action.effectiveMethod().name(),
                action.inputJsonSchema(),
                action.outputJsonSchema(),
                action.examples().stream().map(ExampleView::from).toList(),
                action.notes(),
                // 省略和 null 在详情里仍是空数组，页面按 string[] 读取。
                action.related() == null ? List.of() : action.related(),
                McpView.from(action.mcp()));
    }
}

record ConchDetail(
        String conch,
        String version,
        boolean published,
        String driver,
        String minCrabVersion,
        String baseUrl,
        List<String> authOrigins,
        String title,
        String description,
        String owner,
        List<String> tags,
        String changelog,
        Map<String, CredentialDeclaration> credentials,
        Map<String, ConfigDeclaration> config,
        List<GroupView> groups,
        Map<String, CapabilityView> actions,
        boolean disabled,
        Instant disabledAt,
        String disabledBy,
        String disabledReason,
        long pullCount,
        UpstreamView upstream,
        SnapshotView snapshot,
        List<ToolView> tools,
        String authUse,
        List<String> authExports) {

    static ConchDetail from(ConchDoc doc, AvailabilityRow availability, long pullCount, boolean published) {
        Map<String, CredentialDeclaration> credentials = new java.util.LinkedHashMap<>();
        if (doc.credentials() != null) {
            doc.credentials().forEach((name, field) -> credentials.put(name, CredentialDeclaration.from(field)));
        }
        Map<String, ConfigDeclaration> config = new java.util.LinkedHashMap<>();
        doc.config().forEach((name, field) -> config.put(name, ConfigDeclaration.from(field)));
        ArrayList<GroupView> groups = new ArrayList<>(doc.groups().size());
        for (GroupDef group : doc.groups().values()) {
            groups.add(GroupView.from(group));
        }
        Map<String, CapabilityView> actions = new java.util.LinkedHashMap<>();
        doc.actions().forEach((name, action) -> actions.put(name, CapabilityView.from(action)));
        McpPackageView.Projection mcp = McpPackageView.of(doc);
        return new ConchDetail(
                doc.conch(),
                doc.version().toString(),
                published,
                mcp.driver(),
                doc.minCrabVersion().toString(),
                doc.baseUrl(),
                doc.auth() == null ? List.of() : doc.auth().origins(),
                doc.title(),
                doc.description(),
                doc.owner(),
                doc.tags(),
                doc.changelog(),
                credentials,
                config,
                List.copyOf(groups),
                actions,
                availability != null && availability.disabled(),
                availability != null && availability.disabled() ? availability.disabledAt() : null,
                availability != null && availability.disabled() ? availability.disabledBy() : null,
                availability != null && availability.disabled() ? availability.disabledReason() : null,
                pullCount,
                mcp.upstream(),
                mcp.snapshot(),
                mcp.tools(),
                AuthDependencies.authUse(doc),
                doc.auth() == null ? List.of() : List.copyOf(doc.auth().exports().keySet()));
    }
}

/** {@code upstream.auth} 只公开类型。请求头模板不出现在管理 API 里。 */
record UpstreamAuthView(String type) {
}

/**
 * 包内上游。{@code allowedHosts} 是解析后的列表：省略时已由解析器填成 URL 主机。
 */
record UpstreamView(
        String url,
        String transport,
        UpstreamAuthView auth,
        List<String> allowedHosts) {
}

/** 快照里的上游服务器名和版本。没有 {@code server} 键时为 null。 */
record SnapshotServerView(String name, String version) {
}

/**
 * 写进包里的快照。{@code fetchedAt} 是 YAML 原文，不是 Reef 读到这份包的时间。
 */
record SnapshotView(
        String fetchedAt,
        String protocolVersion,
        SnapshotServerView server,
        String toolsSha256) {
}

/**
 * 工具表的一行。破坏性和确认是有效值，与 {@code ConchDiff} 的工具差异相同。
 * {@code schemaSha256} 按 {@code ToolSnapshots.schemaSha256} 计算，不看 YAML 里声明的原文。
 */
record ToolView(
        String name,
        String upstreamName,
        String group,
        boolean readOnly,
        boolean destructive,
        boolean confirm,
        Map<String, Object> upstreamAnnotations,
        String schemaSha256) {
}

record ManifestEntry(
        String conch,
        String version,
        String sha256,
        String minCrabVersion,
        String title,
        String description,
        int capabilityCount,
        List<String> requiredCredentials,
        String driver,
        String upstreamHost,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String authUse) {
    ManifestEntry {
        requiredCredentials = requiredCredentials == null ? List.of() : List.copyOf(requiredCredentials);
        if (driver == null || driver.isBlank()) {
            driver = "http";
        }
    }
}
