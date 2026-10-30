package dev.hermit.crab.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.ConchRegistry;
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
import dev.hermit.crab.store.ConchConfigStore;
import dev.hermit.crab.sync.DisabledConches;

import javax.sql.DataSource;

public final class ConchAdmin {
    private final DataSource dataSource;
    private final AccountStore accounts;
    private final CredentialVault vault;
    private final ConchConfigStore config;
    private final ApiKeyService apiKeys;
    private final SessionManager sessions;
    private final RuleEngine engine;
    private final ConchRegistry registry;
    private final DisabledConches disabledConches;

    public ConchAdmin(
            DataSource dataSource,
            AccountStore accounts,
            CredentialVault vault,
            ConchConfigStore config,
            ApiKeyService apiKeys,
            SessionManager sessions,
            RuleEngine engine,
            ConchRegistry registry,
            DisabledConches disabledConches
    ) {
        this.dataSource = dataSource;
        this.accounts = accounts;
        this.vault = vault;
        this.config = config;
        this.apiKeys = apiKeys;
        this.sessions = sessions;
        this.engine = engine;
        this.registry = registry;
        this.disabledConches = disabledConches == null ? DisabledConches.none() : disabledConches;
    }

    public Account createAccount(String conch, String name, String actor) {
        return accounts.create(origin(conch), conch, name, actor);
    }

    public void deleteAccount(String conch, String name, String actor) {
        Origin source = origin(conch);
        accounts.delete(source, conch, name, actor);
        sessions.invalidate(new SessionKey(source, conch, name));
    }

    public void setDefault(String conch, String name, String actor) {
        accounts.setDefault(origin(conch), conch, name, actor);
    }

    public List<Account> listAccounts(String conch) {
        return accounts.list(origin(conch), conch);
    }

    public Account requireAccount(String conch, String name) {
        return accounts.resolve(origin(conch), conch, Optional.of(name));
    }

    public List<String> missingCredentials(ConchDoc doc, String account) {
        return DeclaredSettings.missingCredentials(doc, vault.fieldNames(origin(doc.conch()), doc.conch(), account));
    }

    public Set<String> credentialNames(String conch, String account) {
        return vault.fieldNames(origin(conch), conch, account);
    }

    /** Stored ciphertext for this account cannot be opened with the current master key. */
    public boolean credentialsUnreadable(String conch, String account) {
        return !vault.readable(origin(conch), conch, account);
    }

    public boolean loginPaused(String conch, String account) {
        return sessions.loginPaused(new SessionKey(origin(conch), conch, account));
    }

    public void writeCredentials(ConchDoc doc, String account, Map<String, String> fields, String actor) {
        requireAccount(doc.conch(), account);
        DeclaredSettings.checkCredentialFields(doc, fields);
        Origin source = origin(doc.conch());
        vault.write(source, doc.conch(), account, fields, actor);
        sessions.invalidate(new SessionKey(source, doc.conch(), account));
    }

    public Map<String, String> storedConfig(String conch) {
        return config.all(origin(conch), conch);
    }

    public void putConfig(ConchDoc doc, String key, String value, String actor) {
        ConfigField field = doc.config().get(key);
        DeclaredSettings.checkConfigValue(field, value);
        if (value.isEmpty()) {
            config.delete(origin(doc.conch()), doc.conch(), key, actor);
            return;
        }
        config.put(origin(doc.conch()), doc.conch(), key, value, actor);
    }

    public void deleteConfig(ConchDoc doc, String key, String actor) {
        if (!doc.config().containsKey(key)) {
            throw new IllegalArgumentException("unknown config field");
        }
        config.delete(origin(doc.conch()), doc.conch(), key, actor);
    }

    public ExecutionResult testLogin(ConchDoc doc, String account, String traceId, String actor) {
        Account resolved = requireAccount(doc.conch(), account);
        Origin source = origin(doc.conch());
        SessionKey key = new SessionKey(source, doc.conch(), resolved.account());
        sessions.resetLoginPause(key);
        sessions.invalidate(key);
        AuditLog.record(
                dataSource,
                actor,
                AuditKind.TEST_LOGIN,
                source.name() + "/" + doc.conch() + "/" + resolved.account(),
                "{\"account\":\"" + resolved.account() + "\"}");
        Map<String, String> stored = config.all(source, doc.conch());
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
            secrets = vault.decryptForExecution(source, doc.conch(), resolved.account());
        } catch (CredentialDecryptException ex) {
            return failed(doc, traceId, CredentialDecryptException.MESSAGE);
        }
        ExecutionRequest request = new ExecutionRequest(
                traceId,
                source,
                doc.conch(),
                "test_login",
                Map.of(),
                resolved.account(),
                new MappedAccount(secrets, DeclaredSettings.mergedConfig(doc, stored)));
        return engine.testLogin(request);
    }

    /** Registry origin, or {@link Origin#OFFICIAL} when the conch is not loaded. */
    private Origin origin(String conch) {
        return registry.origin(conch).orElse(Origin.OFFICIAL);
    }

    public CreatedApiKey createApiKey(String name, ApiKeyScope scope, String actor) {
        ApiKeyScopes.requireInstalled(registry, scope, disabledConches);
        return apiKeys.create(name, scope, actor);
    }

    public List<ApiKeySummary> listApiKeys() {
        return apiKeys.list();
    }

    public void revokeApiKey(String id, String actor) {
        apiKeys.revoke(id, actor);
    }

    private static ExecutionResult failed(ConchDoc doc, String traceId, String message) {
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
