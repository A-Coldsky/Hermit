package dev.hermit.crab.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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
    private volatile Function<String, Optional<ConchDoc>> stagedDeclarations = name -> Optional.empty();
    private volatile Function<String, Origin> originFallback = name -> null;

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

    /**
     * Looks up a verified staged package for credential names the installed doc does not declare.
     * The lookup takes the sync lock and must not be called from a thread that already holds it.
     */
    public void bindStagedDeclarations(Function<String, Optional<ConchDoc>> lookup) {
        this.stagedDeclarations = lookup == null ? name -> Optional.empty() : lookup;
    }

    /** Registry origin, then a local-file fallback, otherwise official. */
    public Origin originOf(String conch) {
        return origin(conch);
    }

    /** Used when the package is not in the registry, for example a disabled local file. */
    public void bindOriginFallback(Function<String, Origin> lookup) {
        this.originFallback = lookup == null ? name -> null : lookup;
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

    public boolean declaresCredential(ConchDoc doc, String field) {
        return DeclaredSettings.declaresCredential(doc, staged(doc.conch()), field);
    }

    public void writeCredentials(ConchDoc doc, String account, Map<String, String> fields, String actor) {
        requireAccount(doc.conch(), account);
        DeclaredSettings.checkCredentialFields(doc, staged(doc.conch()), fields);
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

    /** 先校验每一项，全部合法才在一个事务里写入。空串表示删除该项。 */
    public void putConfigs(ConchDoc doc, Map<String, String> values, String actor) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            DeclaredSettings.checkConfigValue(doc.config().get(entry.getKey()), entry.getValue());
        }
        config.putAll(origin(doc.conch()), doc.conch(), values, actor);
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
            return failed(doc, traceId, Invoker.missingConfig(missingConfig));
        }
        List<String> missingCredentials = missingCredentials(doc, resolved.account());
        if (!missingCredentials.isEmpty()) {
            return failed(doc, traceId, CredentialMessages.missing(
                    resolved.account(), missingCredentials, doc.credentials()));
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

    private ConchDoc staged(String conch) {
        Function<String, Optional<ConchDoc>> lookup = stagedDeclarations;
        if (lookup == null || conch == null) {
            return null;
        }
        Optional<ConchDoc> found = lookup.apply(conch);
        return found == null ? null : found.orElse(null);
    }

    /** Registry origin, then the fallback, otherwise {@link Origin#OFFICIAL}. */
    private Origin origin(String conch) {
        Origin stored = registry.origin(conch);
        if (stored != null) {
            return stored;
        }
        Origin fallback = originFallback.apply(conch);
        return fallback == null ? Origin.OFFICIAL : fallback;
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
