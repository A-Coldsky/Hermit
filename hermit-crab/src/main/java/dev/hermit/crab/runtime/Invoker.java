package dev.hermit.crab.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.secure.CredentialDecryptException;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.sync.DisabledConches;
import dev.hermit.crab.sync.VersionSuccess;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.AccountNotFoundException;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.CallChannel;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.IdempotencyStore;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.store.ConchConfigStore;

public final class Invoker {
    private static final Logger LOG = Logger.getLogger(Invoker.class.getName());
    private static final int NAME_LIMIT = 256;
    public static final String IDEMPOTENCY_CONFLICT = "Idempotency-Key was already used with a different request body";
    /** A loaded capability turned off locally. Distinct from a missing action ({@code NOT_FOUND}). */
    public static final String CAPABILITY_DISABLED_MESSAGE = "该能力已在本机停用，调用前需要重新启用。";
    /** Power of two. Distinct keys share a stripe; stripes are not removed after a request. */
    static final int IDEMPOTENCY_STRIPES = 64;

    private final ConchRegistry registry;
    private final AccountStore accounts;
    private final CredentialVault vault;
    private final ConchConfigStore config;
    private final RuleEngine engine;
    private final CallLogStore callLogs;
    private final IdempotencyStore idempotency;
    private final CapabilityGate disabled;
    private final DisabledConches disabledConches;
    private final List<CrabRuntime.ExecutionListener> listeners = new CopyOnWriteArrayList<>();
    private final Object[] idempotencyLocks = newIdempotencyLocks();
    private volatile ReportQueue reportQueue;
    private volatile KnownSecrets knownSecrets = KnownSecrets.empty();
    private volatile DataSource versionSuccess;
    private volatile Clock versionClock = Clock.systemUTC();

    public Invoker(
            ConchRegistry registry,
            AccountStore accounts,
            CredentialVault vault,
            ConchConfigStore config,
            RuleEngine engine,
            CallLogStore callLogs,
            IdempotencyStore idempotency,
            CapabilityGate disabled
    ) {
        this(registry, accounts, vault, config, engine, callLogs, idempotency, disabled, DisabledConches.none());
    }

    public Invoker(
            ConchRegistry registry,
            AccountStore accounts,
            CredentialVault vault,
            ConchConfigStore config,
            RuleEngine engine,
            CallLogStore callLogs,
            IdempotencyStore idempotency,
            CapabilityGate disabled,
            DisabledConches disabledConches
    ) {
        this.registry = registry;
        this.accounts = accounts;
        this.vault = vault;
        this.config = config;
        this.engine = engine;
        this.callLogs = callLogs;
        this.idempotency = idempotency;
        this.disabled = disabled;
        this.disabledConches = disabledConches == null ? DisabledConches.none() : disabledConches;
    }

    public void addListener(CrabRuntime.ExecutionListener listener) {
        listeners.add(listener);
    }

    public void bindHooks(ReportQueue reportQueue) {
        this.reportQueue = reportQueue;
    }

    public void bindKnownSecrets(KnownSecrets knownSecrets) {
        this.knownSecrets = knownSecrets == null ? KnownSecrets.empty() : knownSecrets;
    }

    /** Successful official calls record the executed version so retention can keep it. */
    public void bindVersionSuccess(DataSource dataSource, Clock clock) {
        this.versionSuccess = dataSource;
        this.versionClock = clock == null ? Clock.systemUTC() : clock;
    }

    public InvokeOutcome invoke(
            ApiKeyPrincipal principal,
            String conch,
            String action,
            Map<String, Object> input,
            Optional<String> account,
            Optional<String> idempotencyKey,
            String traceId
    ) {
        return invoke(principal, conch, action, input, account, idempotencyKey, null, CallChannel.HTTP, traceId);
    }

    /**
     * {@code bodySha256} is the raw-body digest for an idempotency key. Null matches only another
     * null (in-process callers). HTTP passes {@link BodyDigests#sha256}. {@code channel} is the
     * entry that reached this invoker; null is recorded as {@link CallChannel#HTTP}.
     */
    public InvokeOutcome invoke(
            ApiKeyPrincipal principal,
            String conch,
            String action,
            Map<String, Object> input,
            Optional<String> account,
            Optional<String> idempotencyKey,
            String bodySha256,
            CallChannel channel,
            String traceId
    ) {
        if (principal == null || conch == null || action == null || conch.length() > NAME_LIMIT || action.length() > NAME_LIMIT) {
            return done(traceId, ErrorCode.NOT_FOUND, "not found", null);
        }
        // 墓碑只表示 Reef 不再分发。包仍在注册表里时，调用继续走后面的权限和能力检查。
        // 注册表里已经没有这份包（旧数据清掉了 current）才返回 CONCH_DISABLED。
        // 已经带着授权快照进入引擎的请求仍执行那份快照（审计 A01）。
        ConchDoc doc = registry.find(conch).orElse(null);
        if (doc == null && disabledConches.contains(conch)) {
            return done(traceId, ErrorCode.CONCH_DISABLED, DisabledConches.CALL_MESSAGE, null);
        }
        Action capability = doc == null ? null : doc.actions().get(action);
        if (doc == null || capability == null) {
            return done(traceId, ErrorCode.NOT_FOUND, "not found", null);
        }
        if (disabled.disabled(conch, action)) {
            return done(traceId, ErrorCode.CAPABILITY_DISABLED, CAPABILITY_DISABLED_MESSAGE, null);
        }
        Origin origin = registry.origin(conch);
        if (origin == null) {
            origin = Origin.OFFICIAL;
        }
        // 空的 conches 表示全部正式包。本地包必须被点名，否则这里就是 403，目录里仍能看见该工具。
        if (origin == Origin.LOCAL && !principal.scope().conches().contains(conch)) {
            return done(traceId, ErrorCode.FORBIDDEN, "forbidden", null);
        }
        if (!principal.permits(conch, action, CapabilityHttp.readOnly(capability))) {
            return done(traceId, ErrorCode.FORBIDDEN, "forbidden", null);
        }
        Account resolved;
        try {
            resolved = accounts.resolve(origin, conch, account == null ? Optional.empty() : account);
        } catch (AccountNotFoundException ex) {
            if (ex.account() == null && accounts.list(origin, conch).isEmpty()) {
                return done(traceId, ErrorCode.NOT_CONFIGURED, "no account configured", null);
            }
            return done(traceId, ErrorCode.ACCOUNT_NOT_FOUND, "account not found", null);
        } catch (IllegalArgumentException ex) {
            return done(traceId, ErrorCode.INVALID_INPUT, "invalid account name", null);
        }
        Map<String, String> stored = config.all(origin, conch);
        List<String> missingConfig = DeclaredSettings.missingConfig(doc, stored);
        if (!missingConfig.isEmpty()) {
            return done(traceId, ErrorCode.NOT_CONFIGURED, "missing config: " + String.join(", ", missingConfig), null);
        }
        List<String> missingCredentials = DeclaredSettings.missingCredentials(
                doc, vault.fieldNames(origin, conch, resolved.account()));
        if (!missingCredentials.isEmpty()) {
            return done(traceId, ErrorCode.NOT_CONFIGURED,
                    "missing credentials: " + String.join(", ", missingCredentials), null);
        }
        Map<String, String> merged = DeclaredSettings.mergedConfig(doc, stored);
        Map<String, String> secrets;
        try {
            secrets = vault.decryptForExecution(origin, conch, resolved.account());
        } catch (CredentialDecryptException ex) {
            return done(traceId, ErrorCode.NOT_CONFIGURED, CredentialDecryptException.MESSAGE, null);
        }
        Map<String, Object> rawInput = input == null ? Map.of() : input;
        CallChannel recorded = channel == null ? CallChannel.HTTP : channel;
        if (CapabilityHttp.method(capability) == HttpMethod.POST && idempotencyKey != null && idempotencyKey.isPresent()) {
            return replayOrExecute(
                    principal, doc, origin, action, rawInput, resolved, secrets, merged,
                    idempotencyKey.get(), bodySha256, recorded, traceId);
        }
        return execute(principal, doc, origin, action, rawInput, resolved, secrets, merged, Optional.empty(), recorded, traceId);
    }

    private InvokeOutcome replayOrExecute(
            ApiKeyPrincipal principal,
            ConchDoc doc,
            Origin origin,
            String action,
            Map<String, Object> input,
            Account account,
            Map<String, String> secrets,
            Map<String, String> merged,
            String key,
            String bodySha256,
            CallChannel channel,
            String traceId
    ) {
        String lockId = origin.name() + "\0" + doc.conch() + "\0" + account.account() + "\0" + action + "\0" + key;
        Object lock = idempotencyLocks[stripe(lockId)];
        synchronized (lock) {
            Optional<IdempotencyStore.Cached> stored = idempotency.findFresh(
                    origin, doc.conch(), account.account(), action, key);
            if (stored.isPresent()) {
                if (!sameBody(stored.get().bodySha256(), bodySha256)) {
                    return done(traceId, ErrorCode.IDEMPOTENCY_CONFLICT, IDEMPOTENCY_CONFLICT, null);
                }
                return replay(stored.get().envelope());
            }
            InvokeOutcome outcome = execute(
                    principal, doc, origin, action, input, account, secrets, merged, Optional.of(key), channel, traceId);
            idempotency.save(
                    origin,
                    doc.conch(),
                    account.account(),
                    action,
                    key,
                    new String(outcome.body(), java.nio.charset.StandardCharsets.UTF_8),
                    bodySha256);
            return outcome;
        }
    }

    /** Both absent matches. A stored null (schema &lt; 10) does not match a real digest. */
    static boolean sameBody(String stored, String incoming) {
        boolean storedAbsent = stored == null || stored.isEmpty();
        boolean incomingAbsent = incoming == null || incoming.isEmpty();
        if (storedAbsent || incomingAbsent) {
            return storedAbsent && incomingAbsent;
        }
        return stored.equals(incoming);
    }

    private InvokeOutcome replay(String envelope) {
        JsonNode node = ApiJson.tree(envelope);
        return new InvokeOutcome(
                ApiEnvelope.status(node),
                ApiJson.bytes(envelope),
                ApiEnvelope.traceId(node),
                true);
    }

    private InvokeOutcome execute(
            ApiKeyPrincipal principal,
            ConchDoc doc,
            Origin origin,
            String action,
            Map<String, Object> input,
            Account account,
            Map<String, String> secrets,
            Map<String, String> merged,
            Optional<String> idempotencyKey,
            CallChannel channel,
            String traceId
    ) {
        ExecutionRequest request = new ExecutionRequest(
                traceId,
                origin,
                doc.conch(),
                action,
                input,
                account.account(),
                new MappedAccount(secrets, merged));
        ExecutionResult result = engine.execute(request, doc);
        Invocation invocation = new Invocation(
                traceId,
                origin,
                principal,
                doc.conch(),
                action,
                account.account(),
                input,
                idempotencyKey);
        publish(invocation, result);
        log(principal, result, secrets, origin, doc.conch(), action, account.account(), channel);
        if (result.success()) {
            return done(traceId, null, null, result.data());
        }
        return done(traceId, result.error().code(), result.error().message(), null);
    }

    private void publish(Invocation invocation, ExecutionResult result) {
        for (CrabRuntime.ExecutionListener listener : listeners) {
            try {
                listener.onResult(invocation, result);
            } catch (RuntimeException ex) {
                LOG.warning("execution listener failed: " + ex.getClass().getName());
            }
        }
        if (invocation.origin() == Origin.LOCAL) {
            return;
        }
        String version = result.conchVersion() == null ? "" : result.conchVersion();
        ReportQueue reports = reportQueue;
        if (reports != null) {
            try {
                reports.record(invocation.conch(), version, invocation.action(), result);
            } catch (RuntimeException ex) {
                LOG.warning("report hook failed: " + ex.getClass().getName());
            }
        }
        if (result.success()) {
            DataSource source = versionSuccess;
            if (source != null) {
                try {
                    VersionSuccess.record(source, invocation.conch(), version, Instant.now(versionClock));
                } catch (RuntimeException ex) {
                    LOG.warning("version success hook failed: " + ex.getClass().getName());
                }
            }
        }
    }

    private void log(
            ApiKeyPrincipal principal,
            ExecutionResult result,
            Map<String, String> secrets,
            Origin origin,
            String conch,
            String action,
            String account,
            CallChannel channel
    ) {
        String message = null;
        String code = null;
        if (!result.success() && result.error() != null) {
            code = result.error().code().name();
            message = Redactor.of(secretValues(secrets)).redact(result.error().message());
        }
        try {
            callLogs.append(new CallLogStore.NewCall(
                    result.traceId() == null ? "" : result.traceId(),
                    origin,
                    conch,
                    action,
                    account,
                    result.conchVersion(),
                    result.success(),
                    code,
                    message,
                    result.durationMillis(),
                    principal.id(),
                    channel), result.stepLogs());
        } catch (RuntimeException ex) {
            LOG.warning("call log failed: " + ex.getClass().getName());
        }
    }

    private Set<String> secretValues(Map<String, String> secrets) {
        LinkedHashSet<String> values = new LinkedHashSet<>(knownSecrets.copy());
        if (secrets != null) {
            for (String value : secrets.values()) {
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static InvokeOutcome done(String traceId, ErrorCode code, String message, Object data) {
        int status = code == null ? 200 : StatusCodes.of(code);
        String json = ApiEnvelope.json(data, code, message, traceId);
        return new InvokeOutcome(status, ApiJson.bytes(json), traceId, false);
    }

    int idempotencyStripes() {
        return idempotencyLocks.length;
    }

    private static Object[] newIdempotencyLocks() {
        if (Integer.bitCount(IDEMPOTENCY_STRIPES) != 1) {
            throw new IllegalStateException("idempotency stripes must be a power of two");
        }
        Object[] locks = new Object[IDEMPOTENCY_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private static int stripe(String lockId) {
        return lockId.hashCode() & (IDEMPOTENCY_STRIPES - 1);
    }

    /** Capability disable flags, config file union in-memory switches. */
    public interface CapabilityGate {
        boolean disabled(String conch, String action);
    }
}
