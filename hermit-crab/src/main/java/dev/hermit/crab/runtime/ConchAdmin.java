package dev.hermit.crab.runtime;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.PendingLogins;
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
    private final Clock clock;
    private volatile Consumer<SessionKey> accountRemoved = key -> { };
    private volatile Identities identities;
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
        this(dataSource, accounts, vault, config, apiKeys, sessions, engine, registry, disabledConches, Clock.systemUTC());
    }

    public ConchAdmin(
            DataSource dataSource,
            AccountStore accounts,
            CredentialVault vault,
            ConchConfigStore config,
            ApiKeyService apiKeys,
            SessionManager sessions,
            RuleEngine engine,
            ConchRegistry registry,
            DisabledConches disabledConches,
            Clock clock
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
        this.clock = clock == null ? Clock.systemUTC() : clock;
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
        return createAccount(origin(conch), conch, name, actor);
    }

    public Account createAccount(Origin source, String conch, String name, String actor) {
        return accounts.create(source, conch, name, actor);
    }

    public void deleteAccount(String conch, String name, String actor) {
        deleteAccount(origin(conch), conch, name, actor);
    }

    public void deleteAccount(Origin source, String conch, String name, String actor) {
        accounts.delete(source, conch, name, actor);
        SessionKey key = new SessionKey(source, conch, name);
        Identities bound = identities;
        if (bound != null) {
            bound.accountDeleted(key, actor);
        }
        sessions.forget(key);
        accountRemoved.accept(key);
    }

    void bindIdentities(Identities identities) {
        this.identities = identities;
    }

    /** 删除账号后清掉别处按账号缓存的状态（MCP 上游会话、OAuth 刷新锁）。 */
    public void bindAccountRemoved(Consumer<SessionKey> hook) {
        this.accountRemoved = hook == null ? key -> { } : hook;
    }

    public void setDefault(String conch, String name, String actor) {
        setDefault(origin(conch), conch, name, actor);
    }

    public void setDefault(Origin source, String conch, String name, String actor) {
        accounts.setDefault(source, conch, name, actor);
    }

    public List<Account> listAccounts(String conch) {
        return listAccounts(origin(conch), conch);
    }

    public List<Account> listAccounts(Origin source, String conch) {
        return accounts.list(source, conch);
    }

    public Account requireAccount(String conch, String name) {
        return requireAccount(origin(conch), conch, name);
    }

    public Account requireAccount(Origin source, String conch, String name) {
        return accounts.resolve(source, conch, Optional.of(name));
    }

    public List<String> missingCredentials(ConchDoc doc, String account) {
        return missingCredentials(origin(doc.conch()), doc, account);
    }

    public List<String> missingCredentials(Origin source, ConchDoc doc, String account) {
        return DeclaredSettings.missingCredentials(doc, vault.fieldNames(source, doc.conch(), account));
    }

    public Set<String> credentialNames(String conch, String account) {
        return credentialNames(origin(conch), conch, account);
    }

    public Set<String> credentialNames(Origin source, String conch, String account) {
        return vault.fieldNames(source, conch, account);
    }

    /** Stored ciphertext for this account cannot be opened with the current master key. */
    public boolean credentialsUnreadable(String conch, String account) {
        return credentialsUnreadable(origin(conch), conch, account);
    }

    public boolean credentialsUnreadable(Origin source, String conch, String account) {
        return !vault.readable(source, conch, account);
    }

    public boolean loginPaused(String conch, String account) {
        return loginPaused(origin(conch), conch, account);
    }

    public boolean loginPaused(Origin source, String conch, String account) {
        return sessions.loginPaused(new SessionKey(source, conch, account));
    }

    public boolean declaresCredential(ConchDoc doc, String field) {
        return declaresCredential(origin(doc.conch()), doc, field);
    }

    public boolean declaresCredential(Origin source, ConchDoc doc, String field) {
        return DeclaredSettings.declaresCredential(doc, stagedFor(source, doc.conch()), field);
    }

    public void writeCredentials(ConchDoc doc, String account, Map<String, String> fields, String actor) {
        writeCredentials(origin(doc.conch()), doc, account, fields, actor);
    }

    public void writeCredentials(Origin source, ConchDoc doc, String account, Map<String, String> fields, String actor) {
        requireAccount(source, doc.conch(), account);
        DeclaredSettings.checkCredentialFields(doc, stagedFor(source, doc.conch()), fields);
        vault.write(source, doc.conch(), account, fields, actor);
        sessions.invalidate(new SessionKey(source, doc.conch(), account));
    }

    public Map<String, String> storedConfig(String conch) {
        return storedConfig(origin(conch), conch);
    }

    public Map<String, String> storedConfig(Origin source, String conch) {
        return config.all(source, conch);
    }

    public void putConfig(ConchDoc doc, String key, String value, String actor) {
        putConfig(origin(doc.conch()), doc, key, value, actor);
    }

    public void putConfig(Origin source, ConchDoc doc, String key, String value, String actor) {
        ConfigField field = doc.config().get(key);
        DeclaredSettings.checkConfigValue(field, value);
        String beforeBase = "base_url".equals(key) ? effectiveBaseUrl(source, doc) : null;
        if (value.isEmpty()) {
            config.delete(source, doc.conch(), key, actor);
        } else {
            config.put(source, doc.conch(), key, value, actor);
        }
        boolean baseUrlChanged = "base_url".equals(key)
                && !Objects.equals(beforeBase, effectiveBaseUrl(source, doc));
        addressesMayHaveChanged(source, doc.conch(), baseUrlChanged, actor);
    }

    /** 先校验每一项，全部合法才在一个事务里写入。空串表示删除该项。 */
    public void putConfigs(ConchDoc doc, Map<String, String> values, String actor) {
        putConfigs(origin(doc.conch()), doc, values, actor);
    }

    public void putConfigs(Origin source, ConchDoc doc, Map<String, String> values, String actor) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            DeclaredSettings.checkConfigValue(doc.config().get(entry.getKey()), entry.getValue());
        }
        boolean touchesBase = values.containsKey("base_url");
        String beforeBase = touchesBase ? effectiveBaseUrl(source, doc) : null;
        config.putAll(source, doc.conch(), values, actor);
        boolean baseUrlChanged = touchesBase
                && !Objects.equals(beforeBase, effectiveBaseUrl(source, doc));
        addressesMayHaveChanged(source, doc.conch(), baseUrlChanged, actor);
    }

    private String effectiveBaseUrl(Origin source, ConchDoc doc) {
        return DeclaredSettings.mergedConfig(doc, config.all(source, doc.conch())).get("base_url");
    }

    /**
     * 改了配置，包的站点或认证地址可能变了：先让不再有相同地址的账号离开身份，
     * 若 base_url 变化，再清理该包账号自己的登录状态和登录快照，不误清同一身份里未变地址的其它成员。
     */
    private void addressesMayHaveChanged(Origin source, String conch, boolean baseUrlChanged, String actor) {
        Identities bound = identities;
        if (bound != null) {
            bound.revalidate(actor);
        }
        if (baseUrlChanged) {
            for (Account account : accounts.list(source, conch)) {
                sessions.clearAccountLogin(new SessionKey(source, conch, account.account()));
            }
        }
    }

    public void deleteConfig(ConchDoc doc, String key, String actor) {
        deleteConfig(origin(doc.conch()), doc, key, actor);
    }

    public void deleteConfig(Origin source, ConchDoc doc, String key, String actor) {
        if (!doc.config().containsKey(key)) {
            throw new IllegalArgumentException("unknown config field");
        }
        String beforeBase = "base_url".equals(key) ? effectiveBaseUrl(source, doc) : null;
        config.delete(source, doc.conch(), key, actor);
        boolean baseUrlChanged = "base_url".equals(key)
                && !Objects.equals(beforeBase, effectiveBaseUrl(source, doc));
        addressesMayHaveChanged(source, doc.conch(), baseUrlChanged, actor);
    }

    public ExecutionResult testLogin(ConchDoc doc, String account, String traceId, String actor) {
        return testLogin(origin(doc.conch()), doc, account, traceId, actor);
    }

    public ExecutionResult testLogin(Origin source, ConchDoc doc, String account, String traceId, String actor) {
        Account resolved = requireAccount(source, doc.conch(), account);
        SessionKey key = new SessionKey(source, doc.conch(), resolved.account());
        AuditLog.record(
                dataSource,
                clock.instant(),
                actor,
                AuditKind.TEST_LOGIN,
                source.name() + "/" + doc.conch() + "/" + resolved.account(),
                "{\"account\":\"" + resolved.account() + "\"}");
        Map<String, String> stored = config.all(source, doc.conch());
        List<String> missingConfig = DeclaredSettings.missingConfig(doc, stored);
        if (!missingConfig.isEmpty()) {
            return failed(doc, traceId, Invoker.missingConfig(missingConfig));
        }
        List<String> missingCredentials = missingCredentials(source, doc, resolved.account());
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
        sessions.invalidate(key);
        ExecutionRequest request = new ExecutionRequest(
                traceId,
                source,
                doc.conch(),
                dev.hermit.crab.store.DevTraceStore.TEST_LOGIN_ACTION,
                Map.of(),
                resolved.account(),
                new MappedAccount(secrets, DeclaredSettings.mergedConfig(doc, stored)));
        return engine.testLogin(request);
    }

    public PendingLogins.View loginInput(String conch, String account) {
        return loginInput(origin(conch), conch, account);
    }

    public PendingLogins.View loginInput(Origin source, String conch, String account) {
        Account resolved = requireAccount(source, conch, account);
        return sessions.pending().view(new SessionKey(source, conch, resolved.account()));
    }

    public PendingLogins.Image loginInputImage(String conch, String account) {
        return loginInputImage(origin(conch), conch, account);
    }

    public PendingLogins.Image loginInputImage(Origin source, String conch, String account) {
        Account resolved = requireAccount(source, conch, account);
        return sessions.pending().image(new SessionKey(source, conch, resolved.account()));
    }

    public void abandonLoginInput(String conch, String account) {
        abandonLoginInput(origin(conch), conch, account);
    }

    public void abandonLoginInput(Origin source, String conch, String account) {
        Account resolved = requireAccount(source, conch, account);
        sessions.pending().discard(new SessionKey(source, conch, resolved.account()));
    }

    /** 不使会话失效。验证码留在这次调用的打码名单里，不写审计。 */
    public ExecutionResult submitLoginInput(ConchDoc doc, String account, String traceId, String value) {
        return submitLoginInput(origin(doc.conch()), doc, account, traceId, value);
    }

    public ExecutionResult submitLoginInput(Origin source, ConchDoc doc, String account, String traceId, String value) {
        return runLoginInput(source, doc, account, traceId, request -> engine.continueLogin(request, value));
    }

    public ExecutionResult resendLoginInput(ConchDoc doc, String account, String traceId) {
        return resendLoginInput(origin(doc.conch()), doc, account, traceId);
    }

    public ExecutionResult resendLoginInput(Origin source, ConchDoc doc, String account, String traceId) {
        return runLoginInput(source, doc, account, traceId, engine::resendLogin);
    }

    private ExecutionResult runLoginInput(
            Origin source,
            ConchDoc doc,
            String account,
            String traceId,
            Function<ExecutionRequest, ExecutionResult> call
    ) {
        Account resolved = requireAccount(source, doc.conch(), account);
        Map<String, String> stored = config.all(source, doc.conch());
        List<String> missingConfig = DeclaredSettings.missingConfig(doc, stored);
        if (!missingConfig.isEmpty()) {
            return failed(doc, traceId, Invoker.missingConfig(missingConfig));
        }
        List<String> missingCredentials = missingCredentials(source, doc, resolved.account());
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
                dev.hermit.crab.store.DevTraceStore.LOGIN_INPUT_ACTION,
                Map.of(),
                resolved.account(),
                new MappedAccount(secrets, DeclaredSettings.mergedConfig(doc, stored)));
        return call.apply(request);
    }

    /** 暂存更新只属于 Reef 正式包，本地包不读暂存声明。 */
    private ConchDoc stagedFor(Origin source, String conch) {
        if (source == Origin.LOCAL) {
            return null;
        }
        return staged(conch);
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
        ConchRegistry.Active active = registry.installed(conch).orElse(null);
        if (active != null) {
            return active.origin();
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
