package dev.hermit.crab.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.ShellRegistry;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.ApiKeyService;
import dev.hermit.crab.store.ApiKeySummary;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.secure.CredentialDecryptException;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.store.ShellConfigStore;

import javax.sql.DataSource;

public final class ShellAdmin {
    private final DataSource dataSource;
    private final AccountStore accounts;
    private final CredentialVault vault;
    private final ShellConfigStore config;
    private final ApiKeyService apiKeys;
    private final SessionManager sessions;
    private final RuleEngine engine;
    private final ShellRegistry registry;

    public ShellAdmin(
            DataSource dataSource,
            AccountStore accounts,
            CredentialVault vault,
            ShellConfigStore config,
            ApiKeyService apiKeys,
            SessionManager sessions,
            RuleEngine engine,
            ShellRegistry registry
    ) {
        this.dataSource = dataSource;
        this.accounts = accounts;
        this.vault = vault;
        this.config = config;
        this.apiKeys = apiKeys;
        this.sessions = sessions;
        this.engine = engine;
        this.registry = registry;
    }

    public Account createAccount(String shell, String name, String actor) {
        return accounts.create(Origin.OFFICIAL, shell, name, actor);
    }

    public void deleteAccount(String shell, String name, String actor) {
        accounts.delete(Origin.OFFICIAL, shell, name, actor);
        sessions.invalidate(new SessionKey(Origin.OFFICIAL, shell, name));
    }

    public void setDefault(String shell, String name, String actor) {
        accounts.setDefault(Origin.OFFICIAL, shell, name, actor);
    }

    public List<Account> listAccounts(String shell) {
        return accounts.list(Origin.OFFICIAL, shell);
    }

    public Account requireAccount(String shell, String name) {
        return accounts.resolve(Origin.OFFICIAL, shell, Optional.of(name));
    }

    public List<String> missingCredentials(ShellDoc doc, String account) {
        return DeclaredSettings.missingCredentials(doc, vault.fieldNames(Origin.OFFICIAL, doc.shell(), account));
    }

    public Set<String> credentialNames(String shell, String account) {
        return vault.fieldNames(Origin.OFFICIAL, shell, account);
    }

    /** Stored ciphertext for this account cannot be opened with the current master key. */
    public boolean credentialsUnreadable(String shell, String account) {
        return !vault.readable(Origin.OFFICIAL, shell, account);
    }

    public boolean loginPaused(String shell, String account) {
        return sessions.loginPaused(new SessionKey(Origin.OFFICIAL, shell, account));
    }

    public void writeCredentials(ShellDoc doc, String account, Map<String, String> fields, String actor) {
        requireAccount(doc.shell(), account);
        DeclaredSettings.checkCredentialFields(doc, fields);
        vault.write(Origin.OFFICIAL, doc.shell(), account, fields, actor);
        sessions.invalidate(new SessionKey(Origin.OFFICIAL, doc.shell(), account));
    }

    public Map<String, String> storedConfig(String shell) {
        return config.all(Origin.OFFICIAL, shell);
    }

    public void putConfig(ShellDoc doc, String key, String value, String actor) {
        ConfigField field = doc.config().get(key);
        DeclaredSettings.checkConfigValue(field, value);
        if (value.isEmpty()) {
            config.delete(Origin.OFFICIAL, doc.shell(), key, actor);
            return;
        }
        config.put(Origin.OFFICIAL, doc.shell(), key, value, actor);
    }

    public void deleteConfig(ShellDoc doc, String key, String actor) {
        if (!doc.config().containsKey(key)) {
            throw new IllegalArgumentException("unknown config field");
        }
        config.delete(Origin.OFFICIAL, doc.shell(), key, actor);
    }

    public ExecutionResult testLogin(ShellDoc doc, String account, String traceId, String actor) {
        Account resolved = requireAccount(doc.shell(), account);
        SessionKey key = new SessionKey(Origin.OFFICIAL, doc.shell(), resolved.account());
        sessions.resetLoginPause(key);
        sessions.invalidate(key);
        AuditLog.record(
                dataSource,
                actor,
                AuditKind.TEST_LOGIN,
                Origin.OFFICIAL.name() + "/" + doc.shell() + "/" + resolved.account(),
                "{\"account\":\"" + resolved.account() + "\"}");
        Map<String, String> stored = config.all(Origin.OFFICIAL, doc.shell());
        List<String> missingConfig = DeclaredSettings.missingConfig(doc, stored);
        if (!missingConfig.isEmpty()) {
            return failed(doc, traceId, "missing config: " + String.join(", ", missingConfig));
        }
        List<String> missingCredentials = missingCredentials(doc, resolved.account());
        if (!missingCredentials.isEmpty()) {
            return failed(doc, traceId, "missing credentials: " + String.join(", ", missingCredentials));
        }
        Map<String, String> secrets;
        try {
            secrets = vault.decryptForExecution(Origin.OFFICIAL, doc.shell(), resolved.account());
        } catch (CredentialDecryptException ex) {
            return failed(doc, traceId, CredentialDecryptException.MESSAGE);
        }
        ExecutionRequest request = new ExecutionRequest(
                traceId,
                Origin.OFFICIAL,
                doc.shell(),
                "test_login",
                Map.of(),
                resolved.account(),
                new MappedAccount(secrets, DeclaredSettings.mergedConfig(doc, stored)));
        return engine.testLogin(request);
    }

    public CreatedApiKey createApiKey(String name, ApiKeyScope scope, String actor) {
        ApiKeyScopes.requireInstalled(registry, scope);
        return apiKeys.create(name, scope, actor);
    }

    public List<ApiKeySummary> listApiKeys() {
        return apiKeys.list();
    }

    public void revokeApiKey(String id, String actor) {
        apiKeys.revoke(id, actor);
    }

    private static ExecutionResult failed(ShellDoc doc, String traceId, String message) {
        return new ExecutionResult(
                false,
                null,
                new ExecutionError(ErrorCode.NOT_CONFIGURED, message, null, null, message),
                0,
                List.of(),
                doc.version().toString(),
                traceId);
    }
}
