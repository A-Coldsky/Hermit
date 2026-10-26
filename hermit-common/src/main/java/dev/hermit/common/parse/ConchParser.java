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

import dev.hermit.common.model.Action;
import dev.hermit.common.model.AliveCheck;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.Example;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.McpHints;
import dev.hermit.common.model.ParamSpec;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.version.SemVer;

public final class ConchParser {

    private static final Set<String> TOP_LEVEL = Set.of(
            "conch", "connector", "version", "driver", "min_crab_version", "min_agent_version",
            "base_url", "title", "description", "owner", "tags", "changelog", "credentials",
            "config", "auth", "actions", "health_check");
    private static final Set<String> FIELD_TYPES = Set.of("text", "secret", "select", "url");
    private static final Set<String> CREDENTIAL_KEYS = Set.of("type", "label", "required", "description", "options");
    private static final Set<String> CONFIG_KEYS = Set.of(
            "type", "label", "required", "description", "options", "default");
    private static final Set<String> ACTION_KEYS = Set.of(
            "title", "description", "method", "input", "output", "steps", "idempotent",
            "examples", "notes", "related", "mcp");
    private static final Set<String> PARAM_KEYS = Set.of(
            "type", "description", "default", "enum", "minimum", "maximum", "items", "required");
    private static final Set<String> AUTH_KEYS = Set.of("steps", "alive_check");
    private static final Set<String> ALIVE_KEYS = Set.of("request", "expect_not_redirect");
    private static final Set<String> MCP_KEYS = Set.of("expose", "read_only", "destructive");
    private static final Set<String> EXAMPLE_KEYS = Set.of("input", "output");

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
        return new ConchDoc(
                conch, version, driver, minCrabVersion, baseUrl, title, description, owner, tags,
                changelog, credentials, config, auth, actions, healthCheck);
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
                mcp(obj.get("mcp"), child(path, "mcp")));
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
        return new McpHints(
                bool(obj.get("expose"), child(path, "expose"), true),
                bool(obj.get("read_only"), child(path, "read_only"), false),
                bool(obj.get("destructive"), child(path, "destructive"), false));
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
