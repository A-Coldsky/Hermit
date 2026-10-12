package dev.hermit.crab.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.ShellRegistry;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.sync.RollbackGuard;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.AccountNotFoundException;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.IdempotencyStore;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.store.ShellConfigStore;

public final class Invoker {
    private static final Logger LOG = Logger.getLogger(Invoker.class.getName());
    private static final int NAME_LIMIT = 256;

    private final ShellRegistry registry;
    private final AccountStore accounts;
    private final CredentialVault vault;
    private final ShellConfigStore config;
    private final RuleEngine engine;
    private final CallLogStore callLogs;
    private final IdempotencyStore idempotency;
    private final CapabilityGate disabled;
    private final List<CrabRuntime.ExecutionListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Object> idempotencyLocks = new ConcurrentHashMap<>();
    private volatile ReportQueue reportQueue;
    private volatile RollbackGuard rollbackGuard;

    public Invoker(
            ShellRegistry registry,
            AccountStore accounts,
            CredentialVault vault,
            ShellConfigStore config,
            RuleEngine engine,
            CallLogStore callLogs,
            IdempotencyStore idempotency,
            CapabilityGate disabled
    ) {
        this.registry = registry;
        this.accounts = accounts;
        this.vault = vault;
        this.config = config;
        this.engine = engine;
        this.callLogs = callLogs;
        this.idempotency = idempotency;
        this.disabled = disabled;
    }

    public void addListener(CrabRuntime.ExecutionListener listener) {
        listeners.add(listener);
    }

    public void bindHooks(ReportQueue reportQueue, RollbackGuard rollbackGuard) {
        this.reportQueue = reportQueue;
        this.rollbackGuard = rollbackGuard;
    }

    public InvokeOutcome invoke(
            ApiKeyPrincipal principal,
            String shell,
            String action,
            Map<String, Object> input,
            Optional<String> account,
            Optional<String> idempotencyKey,
            String traceId
    ) {
        if (principal == null || shell == null || action == null || shell.length() > NAME_LIMIT || action.length() > NAME_LIMIT) {
            return done(traceId, ErrorCode.NOT_FOUND, "not found", null);
        }
        ShellDoc doc = registry.find(shell).orElse(null);
        Action capability = doc == null ? null : doc.actions().get(action);
        if (doc == null || capability == null || disabled.disabled(shell, action)) {
            return done(traceId, ErrorCode.NOT_FOUND, "not found", null);
        }
        if (!principal.permits(shell, action, CapabilityHttp.readOnly(capability))) {
            return done(traceId, ErrorCode.FORBIDDEN, "forbidden", null);
        }
        Account resolved;
        try {
            resolved = accounts.resolve(Origin.OFFICIAL, shell, account == null ? Optional.empty() : account);
        } catch (AccountNotFoundException ex) {
            if (ex.account() == null && accounts.list(Origin.OFFICIAL, shell).isEmpty()) {
                return done(traceId, ErrorCode.NOT_CONFIGURED, "no account configured", null);
            }
            return done(traceId, ErrorCode.ACCOUNT_NOT_FOUND, "account not found", null);
        } catch (IllegalArgumentException ex) {
            return done(traceId, ErrorCode.INVALID_INPUT, "invalid account name", null);
        }
        Map<String, String> stored = config.all(Origin.OFFICIAL, shell);
        List<String> missingConfig = DeclaredSettings.missingConfig(doc, stored);
        if (!missingConfig.isEmpty()) {
            return done(traceId, ErrorCode.NOT_CONFIGURED, "missing config: " + String.join(", ", missingConfig), null);
        }
        List<String> missingCredentials = DeclaredSettings.missingCredentials(
                doc, vault.fieldNames(Origin.OFFICIAL, shell, resolved.account()));
        if (!missingCredentials.isEmpty()) {
            return done(traceId, ErrorCode.NOT_CONFIGURED,
                    "missing credentials: " + String.join(", ", missingCredentials), null);
        }
        Map<String, String> merged = DeclaredSettings.mergedConfig(doc, stored);
        Map<String, String> secrets = vault.decryptForExecution(Origin.OFFICIAL, shell, resolved.account());
        Map<String, Object> rawInput = input == null ? Map.of() : input;
        if (CapabilityHttp.method(capability) == HttpMethod.POST && idempotencyKey != null && idempotencyKey.isPresent()) {
            return replayOrExecute(principal, doc, action, rawInput, resolved, secrets, merged, idempotencyKey.get(), traceId);
        }
        return execute(principal, doc, action, rawInput, resolved, secrets, merged, Optional.empty(), traceId);
    }

    private InvokeOutcome replayOrExecute(
            ApiKeyPrincipal principal,
            ShellDoc doc,
            String action,
            Map<String, Object> input,
            Account account,
            Map<String, String> secrets,
            Map<String, String> merged,
            String key,
            String traceId
    ) {
        String lockId = Origin.OFFICIAL.name() + "\0" + doc.shell() + "\0" + account.account() + "\0" + action + "\0" + key;
        Object lock = idempotencyLocks.computeIfAbsent(lockId, ignored -> new Object());
        synchronized (lock) {
            Optional<String> stored = idempotency.findFresh(
                    Origin.OFFICIAL, doc.shell(), account.account(), action, key);
            if (stored.isPresent()) {
                return replay(stored.get());
            }
            InvokeOutcome outcome = execute(principal, doc, action, input, account, secrets, merged, Optional.of(key), traceId);
            idempotency.save(Origin.OFFICIAL, doc.shell(), account.account(), action, key, new String(outcome.body(), java.nio.charset.StandardCharsets.UTF_8));
            return outcome;
        }
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
            ShellDoc doc,
            String action,
            Map<String, Object> input,
            Account account,
            Map<String, String> secrets,
            Map<String, String> merged,
            Optional<String> idempotencyKey,
            String traceId
    ) {
        ExecutionRequest request = new ExecutionRequest(
                traceId,
                Origin.OFFICIAL,
                doc.shell(),
                action,
                input,
                account.account(),
                new MappedAccount(secrets, merged));
        ExecutionResult result = engine.execute(request);
        Invocation invocation = new Invocation(
                traceId,
                Origin.OFFICIAL,
                principal,
                doc.shell(),
                action,
                account.account(),
                input,
                idempotencyKey);
        publish(invocation, result);
        log(principal, result, secrets, doc.shell(), action, account.account());
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
        String version = result.shellVersion() == null ? "" : result.shellVersion();
        ReportQueue reports = reportQueue;
        if (reports != null) {
            try {
                reports.record(invocation.shell(), version, invocation.action(), result);
            } catch (RuntimeException ex) {
                LOG.warning("report hook failed: " + ex.getClass().getName());
            }
        }
        RollbackGuard guard = rollbackGuard;
        if (guard != null) {
            try {
                guard.onResult(invocation.shell(), version, result);
            } catch (RuntimeException ex) {
                LOG.warning("rollback hook failed: " + ex.getClass().getName());
            }
        }
    }

    private void log(
            ApiKeyPrincipal principal,
            ExecutionResult result,
            Map<String, String> secrets,
            String shell,
            String action,
            String account
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
                    Origin.OFFICIAL,
                    shell,
                    action,
                    account,
                    result.shellVersion(),
                    result.success(),
                    code,
                    message,
                    result.durationMillis(),
                    principal.id()), result.stepLogs());
        } catch (RuntimeException ex) {
            LOG.warning("call log failed: " + ex.getClass().getName());
        }
    }

    private static Set<String> secretValues(Map<String, String> secrets) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
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

    /** Capability disable flags, config file union in-memory switches. */
    public interface CapabilityGate {
        boolean disabled(String shell, String action);
    }
}
