package dev.hermit.crab.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hermit.crab.engine.EngineSettings;

public record CrabConfig(
        String reefUrl,
        String reefToken,
        String reefPublicKey,
        String serverHost,
        int serverPort,
        int syncIntervalSeconds,
        boolean reportEnabled,
        int reportIntervalSeconds,
        List<String> disabledCapabilities,
        Map<String, String> aliases,
        int capabilityTimeoutSeconds,
        int sessionIdleMinutes,
        int maxLoginFailures
) {
    public CrabConfig {
        if (serverHost == null || serverHost.isBlank()) {
            throw new IllegalArgumentException("server.host is required");
        }
        if (serverPort < 0 || serverPort > 65535) {
            throw new IllegalArgumentException("server.port is out of range");
        }
        if (syncIntervalSeconds < 1) {
            throw new IllegalArgumentException("sync.interval_seconds must be positive");
        }
        if (reportIntervalSeconds < 1) {
            throw new IllegalArgumentException("report.interval_seconds must be positive");
        }
        if (capabilityTimeoutSeconds < 1) {
            throw new IllegalArgumentException("engine.capability_timeout_seconds must be positive");
        }
        if (sessionIdleMinutes < 1) {
            throw new IllegalArgumentException("engine.session_idle_minutes must be positive");
        }
        if (maxLoginFailures < 1) {
            throw new IllegalArgumentException("engine.max_login_failures must be positive");
        }
        disabledCapabilities = List.copyOf(disabledCapabilities == null ? List.of() : disabledCapabilities);
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        for (String disabled : disabledCapabilities) {
            requireTarget(disabled, "capabilities.disabled");
        }
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            requireAlias(alias.getKey(), alias.getValue());
        }
    }

    public static CrabConfig defaults() {
        return new CrabConfig(null, null, null, "127.0.0.1", 7777, 300, true, 60, List.of(), Map.of(), 60, 10, 3);
    }

    public EngineSettings engineSettings() {
        return new EngineSettings(
                Duration.ofSeconds(capabilityTimeoutSeconds),
                Duration.ofMinutes(sessionIdleMinutes),
                maxLoginFailures);
    }

    public CrabConfig withListen(String host, int port) {
        return new CrabConfig(
                reefUrl, reefToken, reefPublicKey, host, port, syncIntervalSeconds, reportEnabled,
                reportIntervalSeconds, disabledCapabilities, aliases, capabilityTimeoutSeconds, sessionIdleMinutes,
                maxLoginFailures);
    }

    public CrabConfig withAliases(Map<String, String> aliasMap) {
        return new CrabConfig(
                reefUrl, reefToken, reefPublicKey, serverHost, serverPort, syncIntervalSeconds, reportEnabled,
                reportIntervalSeconds, disabledCapabilities, aliasMap, capabilityTimeoutSeconds, sessionIdleMinutes,
                maxLoginFailures);
    }

    public CrabConfig withDisabled(List<String> disabled) {
        return new CrabConfig(
                reefUrl, reefToken, reefPublicKey, serverHost, serverPort, syncIntervalSeconds, reportEnabled,
                reportIntervalSeconds, disabled, aliases, capabilityTimeoutSeconds, sessionIdleMinutes,
                maxLoginFailures);
    }

    public static void requireAlias(String path, String target) {
        if (path == null || path.length() < 2 || !path.startsWith("/") || path.startsWith("//")
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf(' ') >= 0
                || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid alias path");
        }
        if (path.equals("/api") || path.startsWith("/api/")
                || path.equals("/console") || path.startsWith("/console/")
                || path.startsWith("/mcp")
                || path.startsWith("/openapi.json")) {
            throw new IllegalArgumentException("reserved alias path " + path);
        }
        requireTarget(target, "aliases");
    }

    public static void requireTarget(String target, String where) {
        int dot = target == null ? -1 : target.indexOf('.');
        if (target == null || dot <= 0 || dot != target.lastIndexOf('.') || dot == target.length() - 1) {
            throw new IllegalArgumentException(where + " entries must be shell.action");
        }
    }

    public static Map<String, String> aliasTargets(Map<String, String> aliases) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (aliases != null) {
            copy.putAll(aliases);
        }
        return Map.copyOf(copy);
    }
}
