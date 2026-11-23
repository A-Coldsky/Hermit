package dev.hermit.common.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.hermit.common.version.SemVer;

/**
 * {@code credentials == null} 表示文档没有 credentials 键；空映射表示键在但没有字段。
 * 省略的 config 会变成空映射。省略的 scripts 也会变成空映射。
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
        Map<String, GroupDef> groups
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
    }
}
