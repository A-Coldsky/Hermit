package dev.hermit.crab.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.yaml.snakeyaml.LoaderOptions;

import dev.hermit.crab.store.CrabPaths;

public final class CrabConfigLoader {
    private static final long MAX_BYTES = 1_000_000L;
    private static final Set<String> ROOT = Set.of(
            "reef", "server", "sync", "report", "capabilities", "aliases", "engine", "mcp");
    private static final Set<String> REEF = Set.of("url", "token", "public_key");
    private static final Set<String> SERVER = Set.of("host", "port");
    private static final Set<String> SYNC = Set.of("interval_seconds");
    private static final Set<String> REPORT = Set.of("enabled", "interval_seconds");
    private static final Set<String> CAPABILITIES = Set.of("disabled");
    private static final Set<String> ENGINE = Set.of(
            "capability_timeout_seconds", "session_idle_minutes", "max_login_failures");
    private static final Set<String> MCP = Set.of("allowed_origins");

    private static final YAMLMapper YAML = yaml();

    private CrabConfigLoader() {
    }

    public static CrabConfig load(CrabPaths paths) {
        return load(paths.configFile());
    }

    public static CrabConfig load(Path file) {
        if (file == null || !Files.exists(file)) {
            return CrabConfig.defaults();
        }
        if (!Files.isRegularFile(file)) {
            throw new ConfigFileException(file + ": not a file");
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException ex) {
            throw new ConfigFileException(file + ": cannot be read");
        }
        if (size > MAX_BYTES) {
            throw new ConfigFileException(file + ": larger than 1 MB");
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ConfigFileException(file + ": cannot be read");
        }
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JacksonException ex) {
            throw new ConfigFileException(file + ": cannot parse YAML at line " + lineOf(ex));
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            return CrabConfig.defaults();
        }
        if (!root.isObject()) {
            throw new ConfigFileException(file + ": document must be a mapping");
        }
        rejectUnknown(file, root, "", ROOT);
        JsonNode reef = object(file, root, "reef", REEF);
        JsonNode server = object(file, root, "server", SERVER);
        JsonNode sync = object(file, root, "sync", SYNC);
        JsonNode report = object(file, root, "report", REPORT);
        JsonNode capabilities = object(file, root, "capabilities", CAPABILITIES);
        JsonNode engine = object(file, root, "engine", ENGINE);
        JsonNode mcp = object(file, root, "mcp", MCP);
        CrabConfig defaults = CrabConfig.defaults();
        try {
            return new CrabConfig(
                    text(file, reef, "url", "reef.url"),
                    text(file, reef, "token", "reef.token"),
                    text(file, reef, "public_key", "reef.public_key"),
                    textOr(file, server, "host", "server.host", defaults.serverHost()),
                    integer(file, server, "port", "server.port", defaults.serverPort(), 0, 65535),
                    integer(file, sync, "interval_seconds", "sync.interval_seconds", defaults.syncIntervalSeconds(), 1, Integer.MAX_VALUE),
                    bool(file, report, "enabled", "report.enabled", defaults.reportEnabled()),
                    integer(file, report, "interval_seconds", "report.interval_seconds",
                            defaults.reportIntervalSeconds(), 60, Integer.MAX_VALUE),
                    disabled(file, capabilities),
                    aliases(file, root.get("aliases")),
                    integer(file, engine, "capability_timeout_seconds", "engine.capability_timeout_seconds",
                            defaults.capabilityTimeoutSeconds(), 1, Integer.MAX_VALUE),
                    integer(file, engine, "session_idle_minutes", "engine.session_idle_minutes",
                            defaults.sessionIdleMinutes(), 1, Integer.MAX_VALUE),
                    integer(file, engine, "max_login_failures", "engine.max_login_failures",
                            defaults.maxLoginFailures(), 1, Integer.MAX_VALUE),
                    origins(file, mcp));
        } catch (IllegalArgumentException ex) {
            throw new ConfigFileException(file + ": " + ex.getMessage());
        }
    }

    private static JsonNode object(Path file, JsonNode parent, String name, Set<String> allowed) {
        if (parent == null || !parent.has(name) || parent.get(name).isNull()) {
            return null;
        }
        JsonNode node = parent.get(name);
        if (!node.isObject()) {
            throw new ConfigFileException(file + ": " + name + ": expected a mapping");
        }
        rejectUnknown(file, node, name, allowed);
        return node;
    }

    private static void rejectUnknown(Path file, JsonNode node, String prefix, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                String path = prefix.isEmpty() ? name : prefix + "." + name;
                throw new ConfigFileException(file + ": " + path + ": unknown key");
            }
        }
    }

    private static String text(Path file, JsonNode parent, String name, String path) {
        if (parent == null || !parent.has(name) || parent.get(name).isNull()) {
            return null;
        }
        JsonNode node = parent.get(name);
        if (!node.isTextual()) {
            throw new ConfigFileException(file + ": " + path + ": expected a string");
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    private static String textOr(Path file, JsonNode parent, String name, String path, String fallback) {
        String value = text(file, parent, name, path);
        return value == null ? fallback : value;
    }

    private static int integer(Path file, JsonNode parent, String name, String path, int fallback, int min, int max) {
        if (parent == null || !parent.has(name) || parent.get(name).isNull()) {
            return fallback;
        }
        JsonNode node = parent.get(name);
        if (!node.isIntegralNumber()) {
            throw new ConfigFileException(file + ": " + path + ": expected an integer");
        }
        long value = node.longValue();
        if (value < min || value > max) {
            throw new ConfigFileException(file + ": " + path + ": out of range");
        }
        return (int) value;
    }

    private static boolean bool(Path file, JsonNode parent, String name, String path, boolean fallback) {
        if (parent == null || !parent.has(name) || parent.get(name).isNull()) {
            return fallback;
        }
        JsonNode node = parent.get(name);
        if (!node.isBoolean()) {
            throw new ConfigFileException(file + ": " + path + ": expected a boolean");
        }
        return node.booleanValue();
    }

    private static List<String> disabled(Path file, JsonNode capabilities) {
        if (capabilities == null || !capabilities.has("disabled") || capabilities.get("disabled").isNull()) {
            return List.of();
        }
        JsonNode node = capabilities.get("disabled");
        if (!node.isArray()) {
            throw new ConfigFileException(file + ": capabilities.disabled: expected a list");
        }
        ArrayList<String> values = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new ConfigFileException(file + ": capabilities.disabled[" + index + "]: expected a string");
            }
            values.add(item.asText().strip());
            index++;
        }
        return List.copyOf(values);
    }

    private static List<String> origins(Path file, JsonNode mcp) {
        if (mcp == null || !mcp.has("allowed_origins") || mcp.get("allowed_origins").isNull()) {
            return List.of();
        }
        JsonNode node = mcp.get("allowed_origins");
        if (!node.isArray()) {
            throw new ConfigFileException(file + ": mcp.allowed_origins: expected a list");
        }
        ArrayList<String> values = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new ConfigFileException(file + ": mcp.allowed_origins[" + index + "]: expected a string");
            }
            String text = item.asText().strip();
            if (HttpOrigin.normalize(text) == null) {
                throw new ConfigFileException(file + ": mcp.allowed_origins[" + index + "]: expected an http(s) origin");
            }
            values.add(text);
            index++;
        }
        return List.copyOf(values);
    }

    private static LinkedHashMap<String, String> aliases(Path file, JsonNode node) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isObject()) {
            throw new ConfigFileException(file + ": aliases: expected a mapping");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                throw new ConfigFileException(file + ": aliases." + field.getKey() + ": expected a string");
            }
            values.put(field.getKey(), field.getValue().asText().strip());
        }
        return values;
    }

    private static int lineOf(JacksonException failure) {
        if (failure instanceof JsonProcessingException processing && processing.getLocation() != null) {
            return processing.getLocation().getLineNr();
        }
        return 0;
    }

    private static YAMLMapper yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit((int) MAX_BYTES);
        YAMLFactory factory = YAMLFactory.builder().loaderOptions(options).build();
        return new YAMLMapper(factory);
    }
}
