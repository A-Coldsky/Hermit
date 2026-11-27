package dev.hermit.crab.upstream;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.mcp.McpLimits;
import dev.hermit.common.mcp.ToolSnapshots;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.ToolFilter;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;

/**
 * 把上游 {@code tools/list} 合并进本地 MCP 包的 YAML。
 * 已有 action 保留本地名字、标题、描述和权限；新工具清洗描述，权限不采用上游注解。
 * {@code tools_sha256} 覆盖过滤前的完整列表。
 */
public final class McpToolMerge {
    static final String DESCRIPTION_FALLBACK = "（上游未提供描述）";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = yamlMapper();
    private static final Pattern CSI = Pattern.compile("\u001B\\[[\\x30-\\x3F]*[\\x20-\\x2F]*[\\x40-\\x7E]");
    private static final Pattern OSC = Pattern.compile("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");

    private McpToolMerge() {
    }

    public static byte[] rewrite(
            byte[] yaml,
            List<JsonNode> tools,
            String protocol,
            String serverName,
            String serverVersion,
            Instant fetchedAt
    ) {
        if (yaml == null || protocol == null || protocol.isBlank() || fetchedAt == null) {
            throw new Rejected(ErrorCode.INTERNAL, "本地规则包无法改写。");
        }
        ConchDoc doc;
        try {
            doc = ConchParser.parse(new String(yaml, StandardCharsets.UTF_8));
        } catch (ConchParseException ex) {
            throw new Rejected(ErrorCode.INTERNAL, "本地规则包无法改写。");
        }
        if (tools == null) {
            throw new Rejected(ErrorCode.UPSTREAM_ERROR, McpUpstreamSessions.LIST_INCOMPLETE);
        }
        LinkedHashMap<String, JsonNode> upstream = new LinkedHashMap<>();
        ArrayList<Object> hashed = new ArrayList<>();
        for (JsonNode tool : tools) {
            if (tool == null || !tool.isObject()) {
                throw new Rejected(ErrorCode.UPSTREAM_ERROR, McpUpstreamSessions.LIST_INCOMPLETE);
            }
            hashed.add(tool);
            String name = text(tool.get("name"));
            if (name == null || !validToolName(name)) {
                throw new Rejected(ErrorCode.UPSTREAM_ERROR, "上游工具名无效。");
            }
            if (upstream.putIfAbsent(name, tool) != null) {
                throw new Rejected(ErrorCode.UPSTREAM_ERROR, "上游工具名重复。");
            }
        }
        String toolsSha = ToolSnapshots.toolsSha256(hashed);
        LinkedHashMap<String, JsonNode> selected = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : upstream.entrySet()) {
            if (selected(entry.getKey(), doc.tools())) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        JsonNode rootNode;
        try {
            rootNode = YAML.readTree(yaml);
        } catch (java.io.IOException ex) {
            throw new Rejected(ErrorCode.INTERNAL, "本地规则包无法改写。");
        }
        if (!(rootNode instanceof ObjectNode root)) {
            throw new Rejected(ErrorCode.INTERNAL, "本地规则包无法改写。");
        }
        ObjectNode actions = root.get("actions") instanceof ObjectNode node ? node : root.putObject("actions");
        ArrayList<Map.Entry<String, ObjectNode>> existing = new ArrayList<>();
        actions.fields().forEachRemaining(entry -> {
            if (entry.getValue() instanceof ObjectNode node) {
                existing.add(Map.entry(entry.getKey(), node));
            }
        });
        LinkedHashSet<String> used = new LinkedHashSet<>();
        LinkedHashSet<String> keptUpstream = new LinkedHashSet<>();
        for (Map.Entry<String, ObjectNode> entry : existing) {
            String upstreamName = text(entry.getValue().get("tool"));
            if (upstreamName != null && selected.containsKey(upstreamName)) {
                used.add(entry.getKey());
                keptUpstream.add(upstreamName);
            }
        }
        for (Map.Entry<String, ObjectNode> entry : existing) {
            String upstreamName = text(entry.getValue().get("tool"));
            if (upstreamName != null && selected.containsKey(upstreamName)) {
                update(entry.getValue(), selected.get(upstreamName));
            } else if (upstreamName != null) {
                actions.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, JsonNode> entry : selected.entrySet()) {
            if (keptUpstream.contains(entry.getKey())) {
                continue;
            }
            String local = localName(entry.getKey(), used);
            actions.set(local, create(entry.getValue(), local));
        }
        ObjectNode snapshot = root.putObject("snapshot");
        snapshot.put("fetched_at", fetchedAt.toString());
        snapshot.put("protocol_version", protocol);
        if (serverName != null && !serverName.isBlank() && serverVersion != null && !serverVersion.isBlank()) {
            ObjectNode server = snapshot.putObject("server");
            server.put("name", serverName);
            server.put("version", serverVersion);
        }
        snapshot.put("tools_sha256", toolsSha);
        try {
            return YAML.writeValueAsBytes(root);
        } catch (java.io.IOException ex) {
            throw new Rejected(ErrorCode.INTERNAL, "本地规则包无法改写。");
        }
    }

    /**
     * 为 {@code hermit_dev__read_yaml(omitSchemas=true)} 生成省略大体积 schema/哈希后的轻量视图。
     * 仅对 {@code driver: mcp} 包脱去每个能力的 {@code input_schema}、{@code output_schema}、
     * {@code upstream_annotations}、{@code schema_sha256}（保留 {@code param_names} 参数名清单），
     * 其余类型包返回 {@code null}。
     */
    public static String compactYaml(byte[] yaml) {
        if (yaml == null) {
            return null;
        }
        JsonNode rootNode;
        try {
            rootNode = YAML.readTree(yaml);
        } catch (java.io.IOException ex) {
            return null;
        }
        if (!(rootNode instanceof ObjectNode root)) {
            return null;
        }
        if (!"mcp".equals(text(root.get("driver")))) {
            return null;
        }
        if (root.get("actions") instanceof ObjectNode actions) {
            actions.fields().forEachRemaining(entry -> {
                if (entry.getValue() instanceof ObjectNode action) {
                    if (action.get("input_schema") instanceof ObjectNode inSchema
                            && inSchema.get("properties") instanceof ObjectNode props) {
                        var names = action.putArray("param_names");
                        props.fieldNames().forEachRemaining(names::add);
                    }
                    action.remove("input_schema");
                    action.remove("output_schema");
                    action.remove("upstream_annotations");
                    action.remove("schema_sha256");
                }
            });
        }
        try {
            return YAML.writeValueAsString(root);
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    /**
     * 在原地修改本地 {@code driver: mcp} 规则包的分组、过滤与能力元数据（{@code title}、{@code description}、
     * {@code group}、{@code mcp}、{@code confirm}、{@code notes}、{@code related}），
     * 原样保留 {@code input_schema}、{@code output_schema}、{@code upstream_annotations}、
     * {@code schema_sha256} 与 {@code snapshot}，避免 AI 为多工具 MCP 包回传整份巨型 YAML。
     */
    public static ConfigureOutcome configure(byte[] yaml, Map<String, Object> spec) {
        if (yaml == null || spec == null) {
            throw new Rejected(ErrorCode.INVALID_INPUT, "需要配置参数。");
        }
        JsonNode rootNode;
        try {
            rootNode = YAML.readTree(yaml);
        } catch (java.io.IOException ex) {
            throw new Rejected(ErrorCode.INVALID_INPUT, "本地规则包无法解析为 YAML。");
        }
        if (!(rootNode instanceof ObjectNode root)) {
            throw new Rejected(ErrorCode.INVALID_INPUT, "本地规则包根节点必须是映射。");
        }
        if (!"mcp".equals(text(root.get("driver")))) {
            throw new Rejected(ErrorCode.INVALID_INPUT, "hermit_dev__mcp_configure 只适用于 driver: mcp 的本地规则包。");
        }
        if (spec.get("version") instanceof String ver && !ver.isBlank()) {
            root.put("version", ver.trim());
        }
        boolean usesGroups = false;
        if (spec.containsKey("groups")) {
            Object rawGroups = spec.get("groups");
            if (rawGroups == null || (rawGroups instanceof Map<?, ?> m && m.isEmpty())) {
                root.remove("groups");
            } else if (rawGroups instanceof Map<?, ?> groupsMap) {
                root.set("groups", YAML.valueToTree(groupsMap));
                usesGroups = true;
            } else {
                throw new Rejected(ErrorCode.INVALID_INPUT, "groups 必须是对象映射。");
            }
        }
        if (spec.containsKey("tools")) {
            Object rawTools = spec.get("tools");
            if (rawTools == null || (rawTools instanceof Map<?, ?> m && m.isEmpty())) {
                root.remove("tools");
            } else if (rawTools instanceof Map<?, ?> toolsMap) {
                root.set("tools", YAML.valueToTree(toolsMap));
            } else {
                throw new Rejected(ErrorCode.INVALID_INPUT, "tools 必须是包含 include / exclude 的对象。");
            }
        }
        ObjectNode actions = root.get("actions") instanceof ObjectNode node ? node : root.putObject("actions");
        LinkedHashSet<String> updatedActions = new LinkedHashSet<>();

        Object rawDefaults = spec.get("defaults");
        if (rawDefaults != null) {
            List<?> rules = rawDefaults instanceof List<?> list ? list : List.of(rawDefaults);
            for (Object item : rules) {
                if (!(item instanceof Map<?, ?> rule)) {
                    throw new Rejected(ErrorCode.INVALID_INPUT, "defaults 规则必须是对象。");
                }
                String match = rule.get("match") instanceof String s && !s.isBlank() ? s.trim() : "*";
                actions.fields().forEachRemaining(entry -> {
                    if (entry.getValue() instanceof ObjectNode action) {
                        String localName = entry.getKey();
                        String upstreamTool = text(action.get("tool"));
                        if (glob(match, localName) || (upstreamTool != null && glob(match, upstreamTool))) {
                            applyActionPatch(action, rule, true);
                            updatedActions.add(localName);
                        }
                    }
                });
            }
        }

        Object rawActions = spec.get("actions");
        if (rawActions != null) {
            if (!(rawActions instanceof Map<?, ?> actionPatches)) {
                throw new Rejected(ErrorCode.INVALID_INPUT, "actions 必须是 { 能力名: 配置 } 映射。");
            }
            for (Map.Entry<?, ?> entry : actionPatches.entrySet()) {
                String actionName = String.valueOf(entry.getKey());
                JsonNode target = actions.get(actionName);
                if (!(target instanceof ObjectNode actionNode)) {
                    throw new Rejected(ErrorCode.INVALID_INPUT, "本地 MCP 规则包中不存在能力 " + actionName + "。");
                }
                if (!(entry.getValue() instanceof Map<?, ?> patch)) {
                    throw new Rejected(ErrorCode.INVALID_INPUT, "actions." + actionName + " 必须是对象。");
                }
                applyActionPatch(actionNode, patch, false);
                updatedActions.add(actionName);
            }
        }

        // 检查是否有任意 action 声明了 group 或顶层存在非空 groups：若有则确保 min_crab_version >= 1.10.0。
        if (!usesGroups && root.get("groups") instanceof ObjectNode g && !g.isEmpty()) {
            usesGroups = true;
        }
        if (!usesGroups) {
            var it = actions.fields();
            while (it.hasNext()) {
                if (it.next().getValue() instanceof ObjectNode a && text(a.get("group")) != null) {
                    usesGroups = true;
                    break;
                }
            }
        }
        if (usesGroups) {
            String curMin = text(root.get("min_crab_version"));
            try {
                dev.hermit.common.version.SemVer minVer = curMin == null
                        ? dev.hermit.common.version.SemVer.parse("1.0.0")
                        : dev.hermit.common.version.SemVer.parse(curMin);
                if (minVer.compareTo(dev.hermit.common.model.CapabilityGroups.MIN_CRAB_VERSION) < 0) {
                    root.put("min_crab_version", dev.hermit.common.model.CapabilityGroups.MIN_CRAB_VERSION.toString());
                }
            } catch (IllegalArgumentException ignored) {
                // 留给后续 ConchValidator 给出规范报错。
            }
        }

        try {
            return new ConfigureOutcome(YAML.writeValueAsBytes(root), List.copyOf(updatedActions));
        } catch (java.io.IOException ex) {
            throw new Rejected(ErrorCode.INTERNAL, "本地规则包无法序列化为 YAML。");
        }
    }

    private static void applyActionPatch(ObjectNode action, Map<?, ?> patch, boolean isBulkDefault) {
        Set<String> allowed = isBulkDefault
                ? Set.of("match", "group", "expose", "readOnly", "read_only", "destructive", "confirm")
                : Set.of("title", "description", "group", "expose", "readOnly", "read_only", "destructive", "confirm",
                        "notes", "related", "mcp");
        for (Object rawKey : patch.keySet()) {
            String key = String.valueOf(rawKey);
            if (!allowed.contains(key)) {
                throw new Rejected(ErrorCode.INVALID_INPUT,
                        "不支持的能力配置字段 " + key + "（input_schema/tool/schema_sha256 由 mcp_refresh 维护，不可手工覆写）。");
            }
        }
        if (!isBulkDefault) {
            if (patch.containsKey("title") && patch.get("title") != null) {
                if (!(patch.get("title") instanceof String s) || s.isBlank()) {
                    throw new Rejected(ErrorCode.INVALID_INPUT, "title 必须是非空字符串。");
                }
                action.put("title", s.trim());
            }
            if (patch.containsKey("description") && patch.get("description") != null) {
                if (!(patch.get("description") instanceof String s) || s.isBlank()) {
                    throw new Rejected(ErrorCode.INVALID_INPUT, "description 必须是非空字符串。");
                }
                action.put("description", s.trim());
            }
            if (patch.containsKey("notes")) {
                Object n = patch.get("notes");
                if (n == null || (n instanceof String s && s.isBlank())) {
                    action.remove("notes");
                } else if (n instanceof String s) {
                    action.put("notes", s.trim());
                } else {
                    throw new Rejected(ErrorCode.INVALID_INPUT, "notes 必须是字符串或 null。");
                }
            }
            if (patch.containsKey("related")) {
                Object r = patch.get("related");
                if (r == null) {
                    action.remove("related");
                } else if (r instanceof List<?> list) {
                    action.set("related", YAML.valueToTree(list));
                } else {
                    throw new Rejected(ErrorCode.INVALID_INPUT, "related 必须是字符串数组。");
                }
            }
        }
        if (patch.containsKey("group")) {
            Object g = patch.get("group");
            if (g == null || (g instanceof String s && s.isBlank())) {
                action.remove("group");
            } else if (g instanceof String s) {
                action.put("group", s.trim());
            } else {
                throw new Rejected(ErrorCode.INVALID_INPUT, "group 必须是字符串或 null。");
            }
        }
        ObjectNode mcp = action.get("mcp") instanceof ObjectNode m ? m : action.putObject("mcp");
        if (patch.get("mcp") instanceof Map<?, ?> nestedMcp) {
            putBoolIfPresent(mcp, "expose", nestedMcp, "expose");
            putBoolIfPresent(mcp, "read_only", nestedMcp, "read_only");
            putBoolIfPresent(mcp, "read_only", nestedMcp, "readOnly");
            putBoolIfPresent(mcp, "destructive", nestedMcp, "destructive");
        }
        putBoolIfPresent(mcp, "expose", patch, "expose");
        putBoolIfPresent(mcp, "read_only", patch, "read_only");
        putBoolIfPresent(mcp, "read_only", patch, "readOnly");
        putBoolIfPresent(mcp, "destructive", patch, "destructive");
        if (patch.containsKey("confirm")) {
            Object c = patch.get("confirm");
            if (c == null) {
                action.remove("confirm");
            } else if (c instanceof Boolean b) {
                action.put("confirm", b);
            } else {
                throw new Rejected(ErrorCode.INVALID_INPUT, "confirm 必须是布尔值。");
            }
        }
    }

    private static void putBoolIfPresent(ObjectNode target, String targetField, Map<?, ?> source, String key) {
        if (!source.containsKey(key) || source.get(key) == null) {
            return;
        }
        if (!(source.get(key) instanceof Boolean flag)) {
            throw new Rejected(ErrorCode.INVALID_INPUT, key + " 必须是布尔值。");
        }
        target.put(targetField, flag);
    }

    public record ConfigureOutcome(byte[] yaml, List<String> updatedActions) {
    }

    static boolean selected(String name, ToolFilter filter) {
        if (filter == null || name == null) {
            return filter == null && name != null;
        }
        if (!filter.include().isEmpty()) {
            boolean included = false;
            for (String pattern : filter.include()) {
                if (glob(pattern, name)) {
                    included = true;
                    break;
                }
            }
            if (!included) {
                return false;
            }
        }
        for (String pattern : filter.exclude()) {
            if (glob(pattern, name)) {
                return false;
            }
        }
        return true;
    }

    static boolean glob(String pattern, String text) {
        if (pattern == null || text == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char value = pattern.charAt(i);
            if (value == '*') {
                regex.append(".*");
            } else if (value == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(value)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString()).matcher(text).matches();
    }

    static String localName(String upstream, Set<String> used) {
        StringBuilder cleaned = new StringBuilder();
        upstream.codePoints().forEach(codePoint -> {
            if (codePoint >= 'A' && codePoint <= 'Z') {
                cleaned.append((char) (codePoint + 32));
            } else if ((codePoint >= 'a' && codePoint <= 'z') || (codePoint >= '0' && codePoint <= '9')) {
                cleaned.append((char) codePoint);
            } else {
                cleaned.append('_');
            }
        });
        String raw = cleaned.toString();
        int start = 0;
        while (start < raw.length() && !Character.isLetter(raw.charAt(start))) {
            start++;
        }
        raw = start >= raw.length() ? "tool" : raw.substring(start);
        if (raw.length() > 64) {
            raw = raw.substring(0, 64);
        }
        if (raw.length() < 2) {
            raw = raw + "_";
        }
        String candidate = raw;
        int suffix = 2;
        while (used.contains(candidate) || !ConchValidator.isConchName(candidate)) {
            String tail = "_" + suffix;
            int keep = Math.min(raw.length(), 64 - tail.length());
            String base = keep <= 0 ? "tool" : raw.substring(0, keep);
            if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
                base = "tool";
            }
            if (base.length() + tail.length() > 64) {
                base = base.substring(0, 64 - tail.length());
            }
            candidate = base + tail;
            suffix++;
            if (suffix > 10000) {
                throw new Rejected(ErrorCode.UPSTREAM_ERROR, "上游工具名无效。");
            }
        }
        used.add(candidate);
        return candidate;
    }

    /** 新工具的标题和描述。已有 action 不走这里。 */
    static String cleanText(String raw, String fallback) {
        String stripped = raw == null ? "" : OSC.matcher(raw).replaceAll("");
        stripped = CSI.matcher(stripped).replaceAll("");
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < stripped.length(); ) {
            int codePoint = stripped.codePointAt(i);
            if (!hidden(codePoint)) {
                kept.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        String text = kept.toString().strip();
        if (text.isEmpty()) {
            text = fallback == null ? "" : fallback.strip();
        }
        return capUtf8(text, McpLimits.DESCRIPTION_MAX_BYTES);
    }

    private static void update(ObjectNode action, JsonNode tool) {
        Map<String, Object> input = inputSchema(tool.get("inputSchema"));
        Map<String, Object> output = outputSchema(tool.get("outputSchema"));
        Map<String, Object> annotations = annotations(tool.get("annotations"));
        action.put("tool", text(tool.get("name")));
        action.set("input_schema", YAML.valueToTree(input));
        if (output == null) {
            action.remove("output_schema");
        } else {
            action.set("output_schema", YAML.valueToTree(output));
        }
        action.set("upstream_annotations", YAML.valueToTree(annotations));
        action.put("schema_sha256", ToolSnapshots.schemaSha256(input, output));
    }

    private static ObjectNode create(JsonNode tool, String local) {
        Map<String, Object> input = inputSchema(tool.get("inputSchema"));
        Map<String, Object> output = outputSchema(tool.get("outputSchema"));
        Map<String, Object> annotations = annotations(tool.get("annotations"));
        String title = text(tool.get("title"));
        if (title == null && annotations.get("title") instanceof String annotated) {
            title = annotated;
        }
        ObjectNode action = YAML.createObjectNode();
        action.put("tool", text(tool.get("name")));
        action.put("title", cleanText(title, local));
        action.put("description", cleanText(text(tool.get("description")), DESCRIPTION_FALLBACK));
        // 上游注解只留在 upstream_annotations，不决定能否直接调用。
        ObjectNode mcp = action.putObject("mcp");
        mcp.put("expose", true);
        mcp.put("read_only", false);
        mcp.put("destructive", true);
        action.put("confirm", true);
        action.set("input_schema", YAML.valueToTree(input));
        if (output != null) {
            action.set("output_schema", YAML.valueToTree(output));
        }
        action.set("upstream_annotations", YAML.valueToTree(annotations));
        action.put("schema_sha256", ToolSnapshots.schemaSha256(input, output));
        return action;
    }

    private static Map<String, Object> inputSchema(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of("type", "object");
        }
        if (!node.isObject()) {
            throw new Rejected(ErrorCode.UPSTREAM_ERROR, "上游工具 schema 无效。");
        }
        return objectMap(node);
    }

    private static Map<String, Object> outputSchema(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw new Rejected(ErrorCode.UPSTREAM_ERROR, "上游工具 schema 无效。");
        }
        return objectMap(node);
    }

    private static Map<String, Object> annotations(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new Rejected(ErrorCode.UPSTREAM_ERROR, "上游工具注解无效。");
        }
        Map<String, Object> annotations = objectMap(node);
        for (String key : List.of("readOnlyHint", "destructiveHint", "idempotentHint")) {
            if (annotations.containsKey(key) && !(annotations.get(key) instanceof Boolean)) {
                throw new Rejected(ErrorCode.UPSTREAM_ERROR, "上游工具注解无效。");
            }
        }
        return annotations;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(JsonNode node) {
        Object converted = JSON.convertValue(node, Object.class);
        if (!(converted instanceof Map<?, ?> map)) {
            throw new Rejected(ErrorCode.UPSTREAM_ERROR, "上游工具 schema 无效。");
        }
        return (Map<String, Object>) dropMeta(map);
    }

    private static Object dropMeta(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("_meta".equals(String.valueOf(entry.getKey()))) {
                    continue;
                }
                copy.put(String.valueOf(entry.getKey()), dropMeta(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(dropMeta(item));
            }
            return copy;
        }
        return value;
    }

    private static boolean hidden(int codePoint) {
        if (codePoint == 0x7F || (codePoint < 0x20 && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
            return true;
        }
        return switch (codePoint) {
            case 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064,
                    0xFEFF, 0x180E, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069 -> true;
            default -> false;
        };
    }

    private static String capUtf8(String text, int maxBytes) {
        int count = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (count + size > maxBytes) {
                break;
            }
            count += size;
            index += Character.charCount(codePoint);
        }
        return text.substring(0, index);
    }

    private static boolean validToolName(String name) {
        if (name == null || name.length() > 256) {
            return false;
        }
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            if (codePoint < 0x20 || codePoint == 0x7F || Character.isWhitespace(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static YAMLMapper yamlMapper() {
        YAMLFactory factory = YAMLFactory.builder().loaderOptions(loaderOptions()).build();
        YAMLMapper mapper = new YAMLMapper(factory);
        mapper.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        mapper.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        return mapper;
    }

    private static org.yaml.snakeyaml.LoaderOptions loaderOptions() {
        org.yaml.snakeyaml.LoaderOptions options = new org.yaml.snakeyaml.LoaderOptions();
        options.setCodePointLimit(4 * 1024 * 1024);
        return options;
    }

    public static final class Rejected extends RuntimeException {
        private final ErrorCode code;

        Rejected(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }
}
