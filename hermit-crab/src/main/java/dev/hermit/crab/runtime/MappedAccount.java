package dev.hermit.crab.runtime;

import java.util.Map;

import dev.hermit.crab.engine.AccountContext;

public final class MappedAccount implements AccountContext {
    private final Map<String, String> credentials;
    private final Map<String, String> config;

    public MappedAccount(Map<String, String> credentials, Map<String, String> config) {
        this.credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
        this.config = config == null ? Map.of() : Map.copyOf(config);
    }

    @Override
    public Map<String, String> credentials() {
        return credentials;
    }

    @Override
    public Map<String, String> config() {
        return config;
    }

    @Override
    public String toString() {
        return "MappedAccount";
    }
}
