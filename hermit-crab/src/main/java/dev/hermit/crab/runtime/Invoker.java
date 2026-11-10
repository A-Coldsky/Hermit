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
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.upstream.McpCallPayload;
import dev.hermit.crab.upstream.McpForwarder;
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
    /** Named account is missing. HTTP and MCP share this sentence. */
    public static final String ACCOUNT_NOT_FOUND_MESSAGE = "没有这个账号。";
    /**
     * Selector is not {@code [A-Za-z0-9_-]{1,64}}. The HTTP gateway uses this exact sentence to
     * tell a header/query conflict from an earlier permission or capability rejection.
     */
    public static final String INVALID_ACCOUNT_NAME = "invalid account name";
    /** HTTP 破坏性调用缺这个头时返回 409，并且不查、不写幂等缓存。 */
    public static final String CONFIRM_REQUIRED = "该操作需要确认。请带上请求头 X-Hermit-Confirm: true 后重试。";
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
    private volatile McpForwarder mcpForwarder;

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

    /** {@code driver: mcp} 走转发器。没绑定时这类包失败关闭，不会落到 HTTP 步骤引擎。 */
    public void bindMcpForwarder(McpForwarder forwarder) {
        this.mcpForwarder = forwarder;
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
        return invoke(principal, conch, action, input, account, idempotencyKey, bodySha256, channel, traceId, false);
    }

    /**
     * {@code httpConfirmed} 只对 HTTP 入口有意义。MCP 的确认走 elicitation，不看这个头。
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
            String traceId,
            boolean httpConfirmed
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
        // grantsAllOfficial（all: true，或旧的空 conches 且空 actions）包含本地包。
        // 写了 conches 或 actions 的 Key 仍要求本地包出现在 conches 里。只读由后面的 permits 限制。
        if (origin == Origin.LOCAL && !principal.scope().allowsLocal(conch)) {
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
            return done(traceId, ErrorCode.ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND_MESSAGE, null);
        } catch (IllegalArgumentException ex) {
            return done(traceId, ErrorCode.INVALID_INPUT, INVALID_ACCOUNT_NAME, null);
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
                    CredentialMessages.missing(resolved.account(), missingCredentials, doc.credentials()), null);
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
        if (recorded == CallChannel.HTTP && capability.effectiveConfirm() && !httpConfirmed) {
            return done(traceId, ErrorCode.CONFLICT, CONFIRM_REQUIRED, null, null);
        }
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
        ExecutionResult result = executeAuthorized(request, doc, traceId);
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
        McpCallPayload payload = result.data() instanceof McpCallPayload call ? call : null;
        Object httpData = payload == null ? result.data() : payload.httpData();
        if (result.success()) {
            return done(traceId, null, null, httpData, payload);
        }
        Object errorData = payload != null && payload.upstreamToolError() ? payload.httpData() : null;
        return done(traceId, result.error().code(), result.error().message(), errorData, payload);
    }

    private ExecutionResult executeAuthorized(ExecutionRequest request, ConchDoc doc, String traceId) {
        if ("mcp".equals(doc.driver())) {
            McpForwarder forwarder = mcpForwarder;
            if (forwarder == null) {
                return new ExecutionResult(
                        false,
                        null,
                        new ExecutionError(ErrorCode.INTERNAL, "内部错误", null, null, "内部错误"),
                        0,
                        List.of(),
                        doc.version().toString(),
                        traceId);
            }
            return forwarder.execute(request, doc);
        }
        return engine.execute(request, doc);
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
        return done(traceId, code, message, data, null);
    }

    private static InvokeOutcome done(String traceId, ErrorCode code, String message, Object data, McpCallPayload payload) {
        int status = code == null ? 200 : StatusCodes.of(code);
        String json = ApiEnvelope.json(data, code, message, traceId);
        return new InvokeOutcome(status, ApiJson.bytes(json), traceId, false, payload);
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
