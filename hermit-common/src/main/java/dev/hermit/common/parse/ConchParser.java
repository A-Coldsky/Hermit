package dev.hermit.common.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.yaml.snakeyaml.LoaderOptions;

import dev.hermit.common.mcp.McpAddresses;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.AliveCheck;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.Example;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.McpAuth;
import dev.hermit.common.model.McpHints;
import dev.hermit.common.model.McpServerInfo;
import dev.hermit.common.model.McpSnapshot;
import dev.hermit.common.model.McpTool;
import dev.hermit.common.model.McpUpstream;
import dev.hermit.common.model.ParamSpec;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.model.ToolFilter;
import dev.hermit.common.version.SemVer;

public final class ConchParser {

    private static final Set<String> TOP_LEVEL = Set.of(
            "conch", "connector", "version", "driver", "min_crab_version", "min_agent_version",
            "base_url", "title", "description", "owner", "tags", "changelog", "credentials",
            "config", "auth", "actions", "health_check", "upstream", "tools", "snapshot");
    private static final Set<String> FIELD_TYPES = Set.of("text", "secret", "select", "url");
    private static final Set<String> CREDENTIAL_KEYS = Set.of("type", "label", "required", "description", "options");
    private static final Set<String> CONFIG_KEYS = Set.of(
            "type", "label", "required", "description", "options", "default");
    private static final Set<String> ACTION_KEYS = Set.of(
            "title", "description", "method", "input", "output", "steps", "idempotent",
            "examples", "notes", "related", "mcp", "tool", "input_schema", "output_schema",
            "upstream_annotations", "schema_sha256", "confirm");
    private static final Set<String> PARAM_KEYS = Set.of(
            "type", "description", "default", "enum", "minimum", "maximum", "items", "required");
    private static final Set<String> AUTH_KEYS = Set.of("steps", "alive_check");
    private static final Set<String> ALIVE_KEYS = Set.of("request", "expect_not_redirect");
    private static final Set<String> MCP_KEYS = Set.of("expose", "read_only", "destructive");
    private static final Set<String> EXAMPLE_KEYS = Set.of("input", "output");
    private static final Set<String> UPSTREAM_KEYS = Set.of(
            "url", "transport", "auth", "headers", "timeout_ms", "allowed_hosts");
    private static final Set<String> MCP_AUTH_HEADERS = Set.of("type");
    private static final Set<String> MCP_AUTH_OAUTH = Set.of(
            "type", "scopes", "client_id", "authorization_endpoint", "token_endpoint");
    private static final Set<String> TOOL_FILTER_KEYS = Set.of("include", "exclude");
    private static final Set<String> SNAPSHOT_KEYS = Set.of(
            "fetched_at", "protocol_version", "server", "tools_sha256");
    private static final Set<String> SERVER_KEYS = Set.of("name", "version");

    private static final YAMLMapper YAML = buildMapper();

    private ConchParser() {
    }

    public static ConchDoc parse(String yaml) {
        if (yaml == null) {
            throw new ConchParseException("", "YAML 为空");
        }
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (JsonProcessingException ex) {
            throw new ConchParseException("", "YAML 无法解析（第 " + lineOf(ex) + " 行）");
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw new ConchParseException("", "YAML 为空");
        }
        if (!root.isObject()) {
            throw new ConchParseException("", "规则包文档必须是映射");
        }
        return parseDoc((ObjectNode) root);
    }

    private static ConchDoc parseDoc(ObjectNode node) {
        rejectUnknown(node, "", TOP_LEVEL);
        String conch = resolveAlias(node, "conch", "connector");
        String versionText = requiredText(node, "version");
        String minText = resolveAlias(node, "min_crab_version", "min_agent_version");
        SemVer version = semver(versionText, "version");
        SemVer minCrabVersion = semver(minText, "min_crab_version");
        String driver = text(node.get("driver"), "driver");
        String baseUrl = text(node.get("base_url"), "base_url");
        String title = text(node.get("title"), "title");
        String description = text(node.get("description"), "description");
        String owner = text(node.get("owner"), "owner");
        List<String> tags = stringList(node.get("tags"), "tags");
        String changelog = text(node.get("changelog"), "changelog");
        Map<String, CredentialField> credentials = credentials(node);
        Map<String, ConfigField> config = config(node);
        Auth auth = auth(node.get("auth"), "auth");
        Map<String, Action> actions = actions(node.get("actions"), "actions");
        List<Map<String, Object>> healthCheck = health(node.get("health_check"), "health_check");
        McpUpstream upstream = upstream(node.get("upstream"), "upstream");
        ToolFilter tools = toolFilter(node.get("tools"), "tools");
        McpSnapshot snapshot = snapshot(node.get("snapshot"), "snapshot");
        return new ConchDoc(
                conch, version, driver, minCrabVersion, baseUrl, title, description, owner, tags,
                changelog, credentials, config, auth, actions, healthCheck, upstream, tools, snapshot);
    }

    private static String resolveAlias(ObjectNode node, String canonical, String alias) {
        String primary = text(node.get(canonical), canonical);
        String secondary = text(node.get(alias), alias);
        if (primary != null && secondary != null && !primary.equals(secondary)) {
            throw new ConchParseException(canonical, canonical + " 与 " + alias + " 的值不一致");
        }
        if (primary != null) {
            return primary;
        }
        if (secondary != null) {
            return secondary;
        }
        throw new ConchParseException(canonical, "缺少 " + canonical);
    }

    private static String requiredText(ObjectNode node, String key) {
        String value = text(node.get(key), key);
        if (value == null) {
            throw new ConchParseException(key, "缺少 " + key);
        }
        return value;
    }

    private static SemVer semver(String text, String path) {
        try {
            return SemVer.parse(text);
        } catch (IllegalArgumentException ex) {
            throw new ConchParseException(path, ex.getMessage());
        }
    }

    private static Map<String, CredentialField> credentials(ObjectNode node) {
        if (!node.has("credentials")) {
            return null;
        }
        JsonNode value = node.get("credentials");
        if (value == null || value.isNull() || !value.isObject()) {
            throw new ConchParseException("credentials", "必须是映射");
        }
        LinkedHashMap<String, CredentialField> fields = new LinkedHashMap<>();
        value.properties().forEach(entry -> {
            String path = "credentials." + entry.getKey();
            fields.put(entry.getKey(), credentialField(entry.getValue(), path));
        });
        return fields;
    }

    private static CredentialField credentialField(JsonNode node, String path) {
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, CREDENTIAL_KEYS);
        return new CredentialField(
                fieldType(obj, path),
                text(obj.get("label"), child(path, "label")),
                bool(obj.get("required"), child(path, "required"), false),
                text(obj.get("description"), child(path, "description")),
                options(obj.get("options"), child(path, "options")));
    }

    private static Map<String, ConfigField> config(ObjectNode node) {
        if (!node.has("config") || node.get("config").isNull()) {
            return Map.of();
        }
        JsonNode value = node.get("config");
        if (!value.isObject()) {
            throw new ConchParseException("config", "必须是映射");
        }
        LinkedHashMap<String, ConfigField> fields = new LinkedHashMap<>();
        value.properties().forEach(entry -> {
            String path = "config." + entry.getKey();
            fields.put(entry.getKey(), configField(entry.getValue(), path));
        });
        return fields;
    }

    private static ConfigField configField(JsonNode node, String path) {
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, CONFIG_KEYS);
        Object defaultValue = obj.has("default") ? toTree(obj.get("default"), child(path, "default")) : null;
        return new ConfigField(
                fieldType(obj, path),
                text(obj.get("label"), child(path, "label")),
                bool(obj.get("required"), child(path, "required"), false),
                text(obj.get("description"), child(path, "description")),
                options(obj.get("options"), child(path, "options")),
                defaultValue);
    }

    private static String fieldType(ObjectNode obj, String path) {
        String type = text(obj.get("type"), child(path, "type"));
        if (type == null || !FIELD_TYPES.contains(type)) {
            throw new ConchParseException(child(path, "type"), "type 只允许 text、secret、select、url");
        }
        return type;
    }

    private static Auth auth(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, AUTH_KEYS);
        List<Step> steps = steps(obj.get("steps"), child(path, "steps"));
        AliveCheck alive = alive(obj.get("alive_check"), child(path, "alive_check"));
        return new Auth(steps, alive);
    }

    private static AliveCheck alive(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, ALIVE_KEYS);
        JsonNode request = obj.get("request");
        Map<String, Object> requestMap = Map.of();
        if (request != null && !request.isNull() && !request.isMissingNode()) {
            if (!request.isObject()) {
                throw new ConchParseException(child(path, "request"), "必须是映射");
            }
            requestMap = castMap(toTree(request, child(path, "request")));
        }
        String expect = text(obj.get("expect_not_redirect"), child(path, "expect_not_redirect"));
        return new AliveCheck(requestMap, expect);
    }

    private static Map<String, Action> actions(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ConchParseException(path, "必须是映射");
        }
        LinkedHashMap<String, Action> actions = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String childPath = path + "." + entry.getKey();
            actions.put(entry.getKey(), action(entry.getKey(), entry.getValue(), childPath));
        });
        return actions;
    }

    private static Action action(String name, JsonNode node, String path) {
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, ACTION_KEYS);
        return new Action(
                name,
                text(obj.get("title"), child(path, "title")),
                text(obj.get("description"), child(path, "description")),
                method(obj.get("method"), child(path, "method")),
                params(obj.get("input"), child(path, "input")),
                output(obj.get("output"), child(path, "output")),
                steps(obj.get("steps"), child(path, "steps")),
                bool(obj.get("idempotent"), child(path, "idempotent"), false),
                examples(obj.get("examples"), child(path, "examples")),
                text(obj.get("notes"), child(path, "notes")),
                stringList(obj.get("related"), child(path, "related")),
                mcp(obj.get("mcp"), child(path, "mcp")),
                mcpTool(obj, path));
    }

    private static HttpMethod method(JsonNode node, String path) {
        String value = text(node, path);
        if (value == null) {
            return null;
        }
        if (!"GET".equals(value) && !"POST".equals(value)) {
            throw new ConchParseException(path, "method 只允许 GET 或 POST");
        }
        return HttpMethod.valueOf(value);
    }

    private static Map<String, ParamSpec> params(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ConchParseException(path, "必须是映射");
        }
        LinkedHashMap<String, ParamSpec> params = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String childPath = path + "." + entry.getKey();
            params.put(entry.getKey(), param(entry.getValue(), childPath));
        });
        return params;
    }

    private static ParamSpec param(JsonNode node, String path) {
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, PARAM_KEYS);
        Object defaultValue = obj.has("default") ? toTree(obj.get("default"), child(path, "default")) : null;
        return new ParamSpec(
                text(obj.get("type"), child(path, "type")),
                text(obj.get("description"), child(path, "description")),
                defaultValue,
                enums(obj.get("enum"), child(path, "enum")),
                number(obj.get("minimum"), child(path, "minimum")),
                number(obj.get("maximum"), child(path, "maximum")),
                obj.has("items") ? toTree(obj.get("items"), child(path, "items")) : null,
                bool(obj.get("required"), child(path, "required"), false));
    }

    private static Map<String, Object> output(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ConchParseException(path, "必须是映射");
        }
        return castMap(toTree(node, path));
    }

    private static List<Example> examples(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConchParseException(path, "必须是列表");
        }
        ArrayList<Example> examples = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            String childPath = path + "[" + i + "]";
            ObjectNode obj = object(node.get(i), childPath);
            rejectUnknown(obj, childPath, EXAMPLE_KEYS);
            Object input = obj.has("input") ? toTree(obj.get("input"), child(childPath, "input")) : null;
            Object output = obj.has("output") ? toTree(obj.get("output"), child(childPath, "output")) : null;
            examples.add(new Example(input, output));
        }
        return examples;
    }

    private static McpHints mcp(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return McpHints.defaults();
        }
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, MCP_KEYS);
        boolean destructiveSpecified = obj.has("destructive") && !obj.get("destructive").isNull();
        return new McpHints(
                bool(obj.get("expose"), child(path, "expose"), true),
                bool(obj.get("read_only"), child(path, "read_only"), false),
                bool(obj.get("destructive"), child(path, "destructive"), false),
                destructiveSpecified);
    }

    private static McpUpstream upstream(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, UPSTREAM_KEYS);
        String url = text(obj.get("url"), child(path, "url"));
        List<String> allowedHosts;
        if (obj.has("allowed_hosts")) {
            allowedHosts = stringList(obj.get("allowed_hosts"), child(path, "allowed_hosts"));
        } else {
            McpAddresses.ParseResult parsed = McpAddresses.parse(url);
            allowedHosts = parsed.ok() ? List.of(parsed.url().host()) : List.of();
        }
        return new McpUpstream(
                url,
                text(obj.get("transport"), child(path, "transport")),
                mcpAuth(obj.get("auth"), child(path, "auth")),
                headers(obj.get("headers"), child(path, "headers")),
                timeout(obj.get("timeout_ms"), child(path, "timeout_ms")),
                allowedHosts);
    }

    private static McpAuth mcpAuth(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        ObjectNode obj = object(node, path);
        String type = text(obj.get("type"), child(path, "type"));
        if ("headers".equals(type)) {
            rejectUnknown(obj, path, MCP_AUTH_HEADERS);
            return new McpAuth(type, List.of(), null, null, null);
        }
        rejectUnknown(obj, path, MCP_AUTH_OAUTH);
        if (!"oauth".equals(type)) {
            return new McpAuth(type, List.of(), null, null, null);
        }
        return new McpAuth(
                type,
                stringList(obj.get("scopes"), child(path, "scopes")),
                text(obj.get("client_id"), child(path, "client_id")),
                text(obj.get("authorization_endpoint"), child(path, "authorization_endpoint")),
                text(obj.get("token_endpoint"), child(path, "token_endpoint")));
    }

    private static Map<String, String> headers(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ConchParseException(path, "必须是映射");
        }
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String childPath = child(path, entry.getKey());
            if (entry.getValue() == null || !entry.getValue().isTextual()) {
                throw new ConchParseException(childPath, "必须是字符串");
            }
            headers.put(entry.getKey(), entry.getValue().textValue());
        });
        return headers;
    }

    private static Integer timeout(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new ConchParseException(path, "必须是整数");
        }
        return node.intValue();
    }

    private static ToolFilter toolFilter(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, TOOL_FILTER_KEYS);
        return new ToolFilter(
                stringList(obj.get("include"), child(path, "include")),
                stringList(obj.get("exclude"), child(path, "exclude")));
    }

    private static McpSnapshot snapshot(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, SNAPSHOT_KEYS);
        return new McpSnapshot(
                text(obj.get("fetched_at"), child(path, "fetched_at")),
                text(obj.get("protocol_version"), child(path, "protocol_version")),
                server(obj.get("server"), child(path, "server")),
                text(obj.get("tools_sha256"), child(path, "tools_sha256")));
    }

    private static McpServerInfo server(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        ObjectNode obj = object(node, path);
        rejectUnknown(obj, path, SERVER_KEYS);
        return new McpServerInfo(
                text(obj.get("name"), child(path, "name")),
                text(obj.get("version"), child(path, "version")));
    }

    private static McpTool mcpTool(ObjectNode obj, String path) {
        boolean present = obj.has("tool") || obj.has("input_schema") || obj.has("output_schema")
                || obj.has("upstream_annotations") || obj.has("schema_sha256") || obj.has("confirm");
        if (!present) {
            return null;
        }
        Map<String, Object> inputSchema = obj.has("input_schema")
                ? schemaMap(obj.get("input_schema"), child(path, "input_schema"))
                : null;
        Map<String, Object> outputSchema = obj.has("output_schema")
                ? schemaMap(obj.get("output_schema"), child(path, "output_schema"))
                : null;
        Map<String, Object> annotations = obj.has("upstream_annotations")
                ? schemaMap(obj.get("upstream_annotations"), child(path, "upstream_annotations"))
                : Map.of();
        return new McpTool(
                text(obj.get("tool"), child(path, "tool")),
                inputSchema,
                outputSchema,
                annotations,
                text(obj.get("schema_sha256"), child(path, "schema_sha256")),
                confirm(obj.get("confirm"), child(path, "confirm")));
    }

    private static Map<String, Object> schemaMap(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            throw new ConchParseException(path, "必须是映射");
        }
        if (!node.isObject()) {
            throw new ConchParseException(path, "必须是映射");
        }
        return castMap(toTree(node, path));
    }

    private static Boolean confirm(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new ConchParseException(path, "必须是布尔值");
        }
        return node.booleanValue();
    }

    private static List<Step> steps(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConchParseException(path, "必须是列表");
        }
        ArrayList<Step> steps = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            String childPath = path + "[" + i + "]";
            JsonNode item = node.get(i);
            if (item == null || !item.isObject() || item.size() != 1) {
                throw new ConchParseException(childPath, "步骤必须是只有一个键的映射");
            }
            Map.Entry<String, JsonNode> field = item.properties().iterator().next();
            JsonNode defNode = field.getValue();
            Map<String, Object> def;
            if (defNode == null || defNode.isNull() || defNode.isMissingNode()
                    || (defNode.isTextual() && defNode.textValue().isEmpty())) {
                def = Map.of();
            } else if (!defNode.isObject()) {
                throw new ConchParseException(childPath, "步骤内容必须是映射");
            } else {
                def = castMap(toTree(defNode, childPath));
            }
            steps.add(new Step(field.getKey(), def));
        }
        return steps;
    }

    private static List<Map<String, Object>> health(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConchParseException(path, "必须是列表");
        }
        ArrayList<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            String childPath = path + "[" + i + "]";
            if (!node.get(i).isObject()) {
                throw new ConchParseException(childPath, "必须是映射");
            }
            items.add(castMap(toTree(node.get(i), childPath)));
        }
        return items;
    }

    private static List<String> stringList(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConchParseException(path, "必须是列表");
        }
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            String childPath = path + "[" + i + "]";
            if (!node.get(i).isTextual()) {
                throw new ConchParseException(childPath, "必须是字符串");
            }
            values.add(node.get(i).textValue());
        }
        return values;
    }

    private static List<String> options(JsonNode node, String path) {
        return stringList(node, path);
    }

    private static List<Object> enums(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConchParseException(path, "必须是列表");
        }
        ArrayList<Object> values = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (item.isObject() || item.isArray()) {
                throw new ConchParseException(path + "[" + i + "]", "enum 项必须是标量");
            }
            values.add(toTree(item, path + "[" + i + "]"));
        }
        return values;
    }

    private static Number number(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new ConchParseException(path, "必须是数字");
        }
        return (Number) toTree(node, path);
    }

    private static Object toTree(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(entry ->
                    map.put(entry.getKey(), toTree(entry.getValue(), child(path, entry.getKey()))));
            return map;
        }
        if (node.isArray()) {
            ArrayList<Object> list = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                list.add(toTree(node.get(i), path + "[" + i + "]"));
            }
            return list;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) {
                throw new ConchParseException(path, "数字超出范围");
            }
            long value = node.longValue();
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return (int) value;
            }
            return value;
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        throw new ConchParseException(path, "不支持的 YAML 节点");
    }

    private static void rejectUnknown(ObjectNode node, String path, Set<String> known) {
        node.fieldNames().forEachRemaining(name -> {
            if (!known.contains(name)) {
                throw new ConchParseException(child(path, name), "未知字段");
            }
        });
    }

    private static ObjectNode object(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new ConchParseException(path, "必须是映射");
        }
        return (ObjectNode) node;
    }

    private static String text(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ConchParseException(path, "必须是字符串");
        }
        return node.textValue();
    }

    private static boolean bool(JsonNode node, String path, boolean defaultValue) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new ConchParseException(path, "必须是布尔值");
        }
        return node.booleanValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object tree) {
        return (Map<String, Object>) tree;
    }

    private static String child(String path, String key) {
        if (path == null || path.isEmpty()) {
            return key;
        }
        return path + "." + key;
    }

    private static int lineOf(JsonProcessingException ex) {
        if (ex.getLocation() == null) {
            return 0;
        }
        return ex.getLocation().getLineNr();
    }

    private static YAMLMapper buildMapper() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(4 * 1024 * 1024);
        YAMLFactory factory = YAMLFactory.builder().loaderOptions(options).build();
        return new YAMLMapper(factory);
    }
}
