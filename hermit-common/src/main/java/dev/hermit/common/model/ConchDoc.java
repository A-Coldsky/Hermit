package dev.hermit.common.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

import dev.hermit.common.version.SemVer;

/**
 * {@code credentials == null} 表示文档没有 credentials 键；空映射表示键在但没有字段。
 * 省略的 config 会变成空映射。省略的 scripts 也会变成空映射。
 * {@code groupsDeclared} 仅用于区分「没写 groups」和「显式写了空/null 的 groups」以便检查版本门槛，
 * 不进规范 JSON，也不参与文档相等性。
 */
public record ConchDoc(
        String conch,
        SemVer version,
        String driver,
        SemVer minCrabVersion,
        String baseUrl,
        String title,
        String description,
        String owner,
        List<String> tags,
        String changelog,
        Map<String, CredentialField> credentials,
        Map<String, ConfigField> config,
        Auth auth,
        Map<String, Action> actions,
        List<Map<String, Object>> healthCheck,
        McpUpstream upstream,
        ToolFilter tools,
        McpSnapshot snapshot,
        Map<String, String> scripts,
        Map<String, GroupDef> groups,
        @JsonIgnore boolean groupsDeclared
) {
    public ConchDoc {
        Objects.requireNonNull(conch, "conch");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(minCrabVersion, "minCrabVersion");
        tags = tags == null ? List.of() : List.copyOf(tags);
        credentials = credentials == null ? null : Frozen.ordered(credentials);
        config = config == null ? Map.of() : Frozen.ordered(config);
        actions = actions == null ? Map.of() : Frozen.ordered(actions);
        healthCheck = healthCheck == null ? List.of() : Frozen.mapList(healthCheck);
        scripts = scripts == null ? Map.of() : Frozen.ordered(scripts);
        groups = groups == null ? Map.of() : Frozen.ordered(groups);
        groupsDeclared = groupsDeclared || !groups.isEmpty();
    }

    public ConchDoc(
            String conch,
            SemVer version,
            String driver,
            SemVer minCrabVersion,
            String baseUrl,
            String title,
            String description,
            String owner,
            List<String> tags,
            String changelog,
            Map<String, CredentialField> credentials,
            Map<String, ConfigField> config,
            Auth auth,
            Map<String, Action> actions,
            List<Map<String, Object>> healthCheck,
            McpUpstream upstream,
            ToolFilter tools,
            McpSnapshot snapshot,
            Map<String, String> scripts,
            Map<String, GroupDef> groups
    ) {
        this(
                conch, version, driver, minCrabVersion, baseUrl, title, description, owner, tags,
                changelog, credentials, config, auth, actions, healthCheck, upstream, tools, snapshot,
                scripts, groups, groups != null && !groups.isEmpty());
    }

    @JsonIgnore
    @Override
    public boolean groupsDeclared() {
        return groupsDeclared;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConchDoc doc)) {
            return false;
        }
        return Objects.equals(conch, doc.conch)
                && Objects.equals(version, doc.version)
                && Objects.equals(driver, doc.driver)
                && Objects.equals(minCrabVersion, doc.minCrabVersion)
                && Objects.equals(baseUrl, doc.baseUrl)
                && Objects.equals(title, doc.title)
                && Objects.equals(description, doc.description)
                && Objects.equals(owner, doc.owner)
                && Objects.equals(tags, doc.tags)
                && Objects.equals(changelog, doc.changelog)
                && Objects.equals(credentials, doc.credentials)
                && Objects.equals(config, doc.config)
                && Objects.equals(auth, doc.auth)
                && Objects.equals(actions, doc.actions)
                && Objects.equals(healthCheck, doc.healthCheck)
                && Objects.equals(upstream, doc.upstream)
                && Objects.equals(tools, doc.tools)
                && Objects.equals(snapshot, doc.snapshot)
                && Objects.equals(scripts, doc.scripts)
                && Objects.equals(groups, doc.groups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                conch, version, driver, minCrabVersion, baseUrl, title, description, owner, tags,
                changelog, credentials, config, auth, actions, healthCheck, upstream, tools, snapshot,
                scripts, groups);
    }
}
