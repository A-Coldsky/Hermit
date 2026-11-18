package dev.hermit.crab.engine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.http.HostNames;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.AliveCheck;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;
import dev.hermit.common.template.UndefinedVariable;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.store.Origin;

public final class RuleEngine implements ConchExecutor {

    private static final Logger LOG = Logger.getLogger(RuleEngine.class.getName());

    /** 能力请求被上游以 HTTP 400 拒绝。不是规则失配，不计入回滚。 */
    static final String UPSTREAM_INPUT_REJECTED = "上游拒绝了输入（HTTP 400）。请修改参数后重试。";

    /** 这次请求现取的入参、凭证、配置和时钟。待输入登录不保存，接着登录也不覆盖回去。 */
    private static final Set<String> EPHEMERAL_VARS = Set.of("input", "credential", "config", "now", "login");

    /** 登录 assert cookie 失败时给调用方的说明：原因，以及下一步。 */
    private static final String LOGIN_COOKIE_MISSING =
            "登录未建立会话：上游没有发回会话 Cookie。请核对用户名和密码；若凭证正确，请重新测试登录，或检查登录页是否已改版。";

    private final ConchRegistry registry;
    private final SessionManager sessions;
    private final EngineSettings settings;
    private final KnownSecrets knownSecrets;
    private final TemplateEngine templates;
    private final FilterRegistry filters;
    private final InputPreparer inputs = new InputPreparer();
    private final StepRunner steps;
    /** HTTP body capture. Null leaves execution unchanged. JSON-RPC traces are not written here. */
    private DevTraceSink traceSink;

    public RuleEngine(ConchRegistry registry, SessionManager sessions) {
        this(registry, sessions, EngineSettings.defaults());
    }

    public RuleEngine(ConchRegistry registry, SessionManager sessions, EngineSettings settings) {
        this(registry, sessions, settings, KnownSecrets.empty());
    }

    public RuleEngine(
            ConchRegistry registry,
            SessionManager sessions,
            EngineSettings settings,
            KnownSecrets knownSecrets
    ) {
        this.registry = registry;
        this.sessions = sessions;
        this.settings = settings;
        this.knownSecrets = knownSecrets == null ? KnownSecrets.empty() : knownSecrets;
        this.filters = FilterRegistry.defaults();
        this.templates = new TemplateEngine(filters);
        this.steps = new StepRunner(templates, filters, sessions.clock(), settings.bodyIdleTimeout());
    }

    /**
     * Local HTTP calls capture a redacted body while this sink says the debug switch is on.
     * {@link #testLogin} does not capture. MCP JSON-RPC traces stay on
     * {@code DevTraceStore.recordJsonRpc} so a forwarder can record them without this method.
     */
    public void bindDevTraceSink(DevTraceSink sink) {
        this.traceSink = sink;
    }

    public ExecutionResult execute(ExecutionRequest request) {
        return execute(request, null);
    }

    /**
     * Runs {@code request} against {@code authorized} when that snapshot is non-null.
     * The conch name must equal {@code request.conch()}; otherwise the conch is missing.
     * Actions come only from the snapshot, even when the action is absent.
     * A null snapshot looks the conch up in the registry, matching {@link #execute(ExecutionRequest)}.
     */
    public ExecutionResult execute(ExecutionRequest request, ConchDoc authorized) {
        CallSecrets.begin();
        DevTraceScope.begin(openDraft(request, authorized));
        try {
            return executeCaptured(request, authorized);
        } finally {
            DevTraceScope.end();
            CallSecrets.end();
        }
    }

    private ExecutionResult executeCaptured(ExecutionRequest request, ConchDoc authorized) {
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String version = null;
        try {
            ConchDoc conch = authorizedConch(request, authorized);
            if (conch == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "规则包不存在", null, null, "规则包不存在",
                        List.of(), version, request, credentials, List.of(), start);
            }
            version = conch.version().toString();
            Action action = conch.actions().get(request.action());
            if (action == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "能力不存在", null, null, "能力不存在",
                        List.of(), version, request, credentials, List.of(), start);
            }
            Map<String, Object> input;
            try {
                input = inputs.prepare(action, request.rawInput());
            } catch (InvalidInput ex) {
                return finish(false, null, ErrorCode.INVALID_INPUT, ex.getMessage(), null, null, ex.getMessage(),
                        List.of(), version, request, credentials, List.of(), start);
            }
            SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
            boolean locked;
            try {
                // 等锁时间算进能力总超时。
                locked = sessions.tryLock(key, deadline);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return internal(request, credentials, List.of(), version, start, ex);
            }
            if (!locked) {
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", null, null, "执行超时",
                        List.of(), version, request, credentials, List.of(), start);
            }
            try {
                return locked(request, conch, action, input, credentials, config, key, version, start, deadline);
            } finally {
                sessions.unlock(key);
            }
        } catch (RuntimeException ex) {
            return internal(request, credentials, List.of(), version, start, ex);
        }
    }

    private ConchDoc authorizedConch(ExecutionRequest request, ConchDoc authorized) {
        if (authorized == null) {
            return registry.find(request.conch()).orElse(null);
        }
        if (!authorized.conch().equals(request.conch())) {
            return null;
        }
        return authorized;
    }

    /**
     * Runs auth steps only. The caller invalidates the session first and does not clear a login
     * pause. Success records an auth success, which clears the pause. Failure leaves it.
     * Success does not include auth step logs; {@code login} keeps those logs on the failure path.
     */
    public ExecutionResult testLogin(ExecutionRequest request) {
        CallSecrets.begin();
        try {
            return testLoginCaptured(request);
        } finally {
            CallSecrets.end();
        }
    }

    private ExecutionResult testLoginCaptured(ExecutionRequest request) {
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String version = null;
        try {
            ConchDoc conch = registry.find(request.conch()).orElse(null);
            if (conch == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "规则包不存在", null, null, "规则包不存在",
                        List.of(), version, request, credentials, List.of(), start);
            }
            version = conch.version().toString();
            SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
            boolean locked;
            try {
                locked = sessions.tryLock(key, deadline);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return internal(request, credentials, List.of(), version, start, ex);
            }
            if (!locked) {
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", null, null, "执行超时",
                        List.of(), version, request, credentials, List.of(), start);
            }
            LoginScope scope = null;
            try {
                URI base = baseUri(conch, config);
                scope = new LoginScope(
                        conch, request.rawInput(), credentials, config, key, base, deadline);
                login(scope);
                return finish(true, Map.of("ok", true), null, null, null, null, null, List.of(), version, request,
                        credentials, scope.redactionValues(), start);
            } catch (StepFailure ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version, request,
                        credentials, redactionOf(scope, key), start);
            } catch (DeadlineExceeded ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                        request, credentials, redactionOf(scope, key), start);
            } catch (RuleProblem ex) {
                return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                        credentials, redactionOf(scope, key), start);
            } catch (RuntimeException ex) {
                return internal(request, credentials, redactionOf(scope, key), version, start, ex);
            } finally {
                sessions.unlock(key);
            }
        } catch (RuntimeException ex) {
            return internal(request, credentials, List.of(), version, start, ex);
        }
    }

    private ExecutionResult locked(
            ExecutionRequest request,
            ConchDoc conch,
            Action action,
            Map<String, Object> input,
            Map<String, String> credentials,
            Map<String, String> config,
            SessionKey key,
            String version,
            long start,
            long deadline
    ) {
        LoginScope scope = null;
        try {
            if (sessions.loginPaused(key)) {
                return finish(false, null, ErrorCode.AUTH_FAILED, "该账号已暂停自动登录", null, null,
                        "该账号已暂停自动登录", List.of(), version, request, credentials,
                        sessions.redactionValues(key), start);
            }
            URI base = baseUri(conch, config);
            scope = new LoginScope(conch, input, credentials, config, key, base, deadline);
            ensureSession(scope);
            requireLoginRefs(scope, action.steps());
            Attempt attempt = runAction(action, scope);
            if (attempt.lost) {
                sessions.markLoggedOut(key);
                if (!action.idempotent()) {
                    return expired(attempt.logs, version, request, credentials, scope, start);
                }
                ensureSession(scope);
                requireLoginRefs(scope, action.steps());
                attempt = runAction(action, scope);
                if (attempt.lost) {
                    sessions.markLoggedOut(key);
                    return expired(attempt.logs, version, request, credentials, scope, start);
                }
            }
            if (attempt.failure != null) {
                StepFailure failure = attempt.failure;
                Integer index = failure.index < 0 ? null : failure.index;
                return finish(false, null, failure.code, failure.summary, index, failure.type, failure.summary,
                        failure.logs, version, request, credentials, scope.redactionValues(), start);
            }
            Object data;
            try {
                data = templates.render(action.outputTemplate(), attempt.vars);
            } catch (TemplateException ex) {
                String text = undefinedOr(ex);
                return finish(false, null, ErrorCode.RULE_MISMATCH, text, null, null, text,
                        attempt.logs, version, request, credentials, scope.redactionValues(), start);
            }
            sessions.touch(key);
            return finish(true, data, null, null, null, null, null, attempt.logs, version, request, credentials,
                    scope.redactionValues(), start);
        } catch (StepFailure ex) {
            Integer index = ex.index < 0 ? null : ex.index;
            return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version, request,
                    credentials, redactionOf(scope, key), start);
        } catch (DeadlineExceeded ex) {
            Integer index = ex.index < 0 ? null : ex.index;
            return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                    request, credentials, redactionOf(scope, key), start);
        } catch (RuleProblem ex) {
            return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                    credentials, redactionOf(scope, key), start);
        } catch (RuntimeException ex) {
            return internal(request, credentials, redactionOf(scope, key), version, start, ex);
        }
    }

    /** 当前会话名单，加上这次调用已经见过的登录值。scope 还没建好时只用会话名单。 */
    private List<String> redactionOf(LoginScope scope, SessionKey key) {
        return scope == null ? sessions.redactionValues(key) : scope.redactionValues();
    }

    private void ensureSession(LoginScope scope) {
        if (!sessions.loggedIn(scope.key)) {
            login(scope);
            return;
        }
        Auth auth = scope.conch.auth();
        AliveCheck alive = auth == null ? null : auth.aliveCheck();
        if (!sessions.idle(scope.key) || alive == null) {
            return;
        }
        requireLoginRefs(scope, alive.request());
        Map<String, Object> vars = baseVars(scope);
        Object rendered;
        try {
            rendered = templates.render(alive.request(), vars);
        } catch (TemplateException ex) {
            throw new StepFailure(ErrorCode.AUTH_FAILED, undefinedOr(ex), -1, "request", List.of());
        }
        Map<String, Object> spec = Values.map(rendered);
        if (spec == null) {
            throw new StepFailure(ErrorCode.RULE_MISMATCH, "alive_check 无效", -1, "request", List.of());
        }
        long started = System.nanoTime();
        HttpResult result;
        try {
            result = HttpCalls.execute(
                    spec,
                    sessions.client(scope.key),
                    () -> sessions.persist(scope.key),
                    scope.base,
                    alive.expectNotRedirect(),
                    scope.deadline,
                    HttpCalls.sensitiveValues(vars, sessions.cookies(scope.key)),
                    settings.bodyIdleTimeout());
        } catch (RuleProblem ex) {
            recordAlive(spec, ex.method, ex.path, ex.status, ex.responseHeaders, ex.responseBody, ex.wire, started,
                    false, ex.transport, ex.warnings);
            throw ex;
        }
        boolean aliveOk = !(result.sessionLost() || result.status() == 401 || result.status() == 403);
        recordAlive(spec, result.method(), result.path(), result.status(), result.headers(), result.body(),
                result.wire(), started, aliveOk, null, result.warnings());
        if (result.sessionLost() || result.status() == 401 || result.status() == 403) {
            sessions.markLoggedOut(scope.key);
            login(scope);
            return;
        }
        sessions.touch(scope.key);
    }

    /**
     * 用户在控制台提交验证码后，从 {@code ask} 的下一步接着登录。不使会话失效，Cookie 还在。
     * 失败丢掉待输入，并且不记入自动登录暂停。
     */
    public ExecutionResult continueLogin(ExecutionRequest request, String value) {
        CallSecrets.begin();
        DevTraceScope.begin(openDraft(request, null));
        try {
            return continueCaptured(request, value);
        } finally {
            DevTraceScope.end();
            CallSecrets.end();
        }
    }

    /** 重跑 {@code ask} 前一个 request。图片验证码换图，短信或口令则重新发送。 */
    public ExecutionResult resendLogin(ExecutionRequest request) {
        CallSecrets.begin();
        DevTraceScope.begin(openDraft(request, null));
        try {
            return resendCaptured(request);
        } finally {
            DevTraceScope.end();
            CallSecrets.end();
        }
    }

    private ExecutionResult continueCaptured(ExecutionRequest request, String value) {
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String rejected = LoginInputMessages.rejectValue(value);
        List<String> supplied = value == null ? List.of() : List.of(value);
        String version = null;
        try {
            ConchDoc conch = registry.find(request.conch()).orElse(null);
            version = conch == null ? null : conch.version().toString();
            if (rejected != null) {
                return finish(false, null, ErrorCode.INVALID_INPUT, rejected, null, null, rejected, List.of(),
                        version, request, credentials, supplied, start);
            }
            if (conch == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "规则包不存在", null, null, "规则包不存在",
                        List.of(), version, request, credentials, supplied, start);
            }
            SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
            boolean locked;
            try {
                locked = sessions.tryLock(key, deadline);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return internal(request, credentials, supplied, version, start, ex);
            }
            if (!locked) {
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", null, null, "执行超时",
                        List.of(), version, request, credentials, supplied, start);
            }
            LoginScope scope = null;
            try {
                PendingLogins.Pending pending = sessions.pending().current(key);
                if (pending == null) {
                    return finish(false, null, ErrorCode.NOT_FOUND, LoginInputMessages.NONE, null, null,
                            LoginInputMessages.NONE, List.of(), version, request, credentials, supplied, start);
                }
                if (!samePackage(conch, pending)) {
                    sessions.pending().discard(key);
                    return finish(false, null, ErrorCode.CONFLICT, LoginInputMessages.UPDATED, null, null,
                            LoginInputMessages.UPDATED, List.of(), version, request, credentials, supplied, start);
                }
                sessions.pending().discard(key);
                URI base = baseUri(conch, config);
                scope = new LoginScope(conch, request.rawInput(), credentials, config, key, base, deadline);
                scope.remember(value);
                login(scope, pending, value, false);
                return finish(true, Map.of("ok", true), null, null, null, null, null, List.of(), version, request,
                        credentials, scope.redactionValues(), start);
            } catch (StepFailure ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version,
                        request, credentials, redactionOf(scope, key, supplied), start);
            } catch (DeadlineExceeded ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                        request, credentials, redactionOf(scope, key, supplied), start);
            } catch (RuleProblem ex) {
                return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                        credentials, redactionOf(scope, key, supplied), start);
            } catch (RuntimeException ex) {
                return internal(request, credentials, redactionOf(scope, key, supplied), version, start, ex);
            } finally {
                sessions.unlock(key);
            }
        } catch (RuntimeException ex) {
            return internal(request, credentials, supplied, version, start, ex);
        }
    }

    private ExecutionResult resendCaptured(ExecutionRequest request) {
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String version = null;
        try {
            ConchDoc conch = registry.find(request.conch()).orElse(null);
            version = conch == null ? null : conch.version().toString();
            if (conch == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "规则包不存在", null, null, "规则包不存在",
                        List.of(), version, request, credentials, List.of(), start);
            }
            SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
            boolean locked;
            try {
                locked = sessions.tryLock(key, deadline);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return internal(request, credentials, List.of(), version, start, ex);
            }
            if (!locked) {
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", null, null, "执行超时",
                        List.of(), version, request, credentials, List.of(), start);
            }
            LoginScope scope = null;
            try {
                PendingLogins.Pending pending = sessions.pending().current(key);
                if (pending == null) {
                    return finish(false, null, ErrorCode.NOT_FOUND, LoginInputMessages.NONE, null, null,
                            LoginInputMessages.NONE, List.of(), version, request, credentials, List.of(), start);
                }
                if (!samePackage(conch, pending)) {
                    sessions.pending().discard(key);
                    return finish(false, null, ErrorCode.CONFLICT, LoginInputMessages.UPDATED, null, null,
                            LoginInputMessages.UPDATED, List.of(), version, request, credentials, List.of(), start);
                }
                Auth auth = conch.auth();
                PendingLogins.ResendDecision decision = sessions.pending().beginResend(key);
                if (!decision.accepted()) {
                    return finish(false, null, decision.code(), decision.message(), null, null, decision.message(),
                            List.of(), version, request, credentials, List.of(), start);
                }
                PendingLogins.Pending ticket = decision.pending();
                int stepIndex = ticket.askIndex() - 1;
                if (auth == null || stepIndex < 0 || stepIndex >= auth.steps().size()) {
                    return finish(false, null, ErrorCode.AUTH_FAILED, LoginInputMessages.NO_RESEND, null, null,
                            LoginInputMessages.NO_RESEND, List.of(), version, request, credentials, List.of(), start);
                }
                URI base = baseUri(conch, config);
                scope = new LoginScope(conch, request.rawInput(), credentials, config, key, base, deadline);
                Map<String, Object> vars = baseVars(scope);
                overlay(vars, ticket.vars());
                ArrayList<StepLog> logs = new ArrayList<>();
                Outcome outcome = runSteps(
                        List.of(auth.steps().get(stepIndex)),
                        vars,
                        true,
                        null,
                        logs,
                        key,
                        base,
                        deadline,
                        "login",
                        conch.scripts(),
                        stepIndex,
                        "image".equals(ticket.kind()),
                        conch);
                if ("image".equals(ticket.kind())) {
                    byte[] bytes = outcome == null ? null : outcome.bodyBytes();
                    String reason = CaptchaImages.reject(bytes);
                    if (reason != null) {
                        return finish(false, null, ErrorCode.AUTH_FAILED, reason, stepIndex, "request", reason, logs,
                                version, request, credentials, scope.redactionValues(), start);
                    }
                    CaptchaImages.Accepted accepted = CaptchaImages.accept(bytes);
                    if (!sessions.pending().completeResend(key, ticket.id(), accepted.bytes(), accepted.mediaType())) {
                        return finish(false, null, ErrorCode.NOT_FOUND, LoginInputMessages.NONE, null, null,
                                LoginInputMessages.NONE, logs, version, request, credentials, scope.redactionValues(),
                                start);
                    }
                } else if (!sessions.pending().completeResend(key, ticket.id(), null, null)) {
                    return finish(false, null, ErrorCode.NOT_FOUND, LoginInputMessages.NONE, null, null,
                            LoginInputMessages.NONE, logs, version, request, credentials, scope.redactionValues(),
                            start);
                }
                return finish(true, Map.of("ok", true), null, null, null, null, null, List.of(), version, request,
                        credentials, scope.redactionValues(), start);
            } catch (StepFailure ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version,
                        request, credentials, redactionOf(scope, key, List.of()), start);
            } catch (DeadlineExceeded ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                        request, credentials, redactionOf(scope, key, List.of()), start);
            } catch (RuleProblem ex) {
                return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                        credentials, redactionOf(scope, key, List.of()), start);
            } catch (RuntimeException ex) {
                return internal(request, credentials, redactionOf(scope, key, List.of()), version, start, ex);
            } finally {
                sessions.unlock(key);
            }
        } catch (RuntimeException ex) {
            return internal(request, credentials, List.of(), version, start, ex);
        }
    }

    private static boolean samePackage(ConchDoc conch, PendingLogins.Pending pending) {
        return conch.version().toString().equals(pending.version())
                && ConchDigest.sha256(conch).equals(pending.contentHash());
    }

    private List<String> redactionOf(LoginScope scope, SessionKey key, List<String> extra) {
        LinkedHashSet<String> values = new LinkedHashSet<>(redactionOf(scope, key));
        if (extra != null) {
            values.addAll(extra);
        }
        return List.copyOf(values);
    }

    private StepFailure waitingFailure(LoginScope scope, PendingLogins.Pending pending) {
        ArrayList<StepLog> logs = new ArrayList<>();
        logs.add(askLog(pending.askIndex(), 0));
        traceAsk(pending.askIndex(), 0, pending.kind(), pending.label());
        return new StepFailure(
                ErrorCode.LOGIN_INPUT_REQUIRED,
                LoginInputMessages.required(scope.conch.conch()),
                pending.askIndex(),
                "ask",
                logs);
    }

    private StepFailure pauseForAsk(
            Step step,
            int at,
            Map<String, Object> vars,
            boolean previousRequest,
            byte[] previousBytes,
            List<StepLog> logs,
            long started,
            ConchDoc conch,
            SessionKey key,
            boolean auth
    ) {
        long took = elapsed(started);
        if (!auth || conch == null) {
            logs.add(askLog(at, took));
            return new StepFailure(ErrorCode.RULE_MISMATCH, "ask 只能写在 auth.steps", at, "ask", logs);
        }
        String kind = step.def().get("kind") instanceof String text ? text : "";
        String label = step.def().get("label") instanceof String text ? text : "";
        String into = step.def().get("into") instanceof String text ? text : "";
        String hint = step.def().get("hint") instanceof String text ? text : null;
        int timeout = askTimeout(step.def().get("timeout_s"));
        byte[] image = null;
        String media = null;
        if ("image".equals(kind)) {
            String reason = previousRequest ? CaptchaImages.reject(previousBytes) : CaptchaImages.UNSUPPORTED;
            if (reason != null) {
                logs.add(askLog(at, took));
                traceAsk(at, took, kind, label);
                return new StepFailure(ErrorCode.AUTH_FAILED, reason, at, "ask", logs);
            }
            CaptchaImages.Accepted accepted = CaptchaImages.accept(previousBytes);
            image = accepted.bytes();
            media = accepted.mediaType();
        }
        sessions.pending().store(key, new PendingLogins.Draft(
                conch.version().toString(),
                ConchDigest.sha256(conch),
                at,
                into,
                kind,
                label,
                hint,
                timeout,
                previousRequest,
                freezeVars(vars, "image".equals(kind)),
                image,
                media));
        logs.add(askLog(at, took));
        traceAsk(at, took, kind, label);
        return new StepFailure(
                ErrorCode.LOGIN_INPUT_REQUIRED,
                LoginInputMessages.required(conch.conch()),
                at,
                "ask",
                logs);
    }

    private static int askTimeout(Object raw) {
        if (raw instanceof Number number) {
            int value = number.intValue();
            if (value >= 60 && value <= 900) {
                return value;
            }
        }
        return 300;
    }

    private static StepLog askLog(int index, long took) {
        return new StepLog(index, "ask", took, null, LoginInputMessages.WAITING, null, null);
    }

    private static void traceAsk(int index, long took, String kind, String label) {
        DevTraceDraft draft = DevTraceScope.current();
        if (draft == null) {
            return;
        }
        LinkedHashMap<String, Object> piece = new LinkedHashMap<>();
        piece.put("type", "ask");
        piece.put("kind", kind);
        piece.put("label", label);
        draft.add("login", index, took, piece);
    }

    private static boolean imageAskFollows(List<Step> steps, int index) {
        if (index + 1 >= steps.size()) {
            return false;
        }
        Step next = steps.get(index + 1);
        return "ask".equals(next.type()) && "image".equals(next.def().get("kind"));
    }

    private static void overlay(Map<String, Object> vars, Map<String, Object> saved) {
        if (saved == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : saved.entrySet()) {
            if (!EPHEMERAL_VARS.contains(entry.getKey())) {
                vars.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static Map<String, Object> freezeVars(Map<String, Object> vars, boolean dropResponseBody) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            if (EPHEMERAL_VARS.contains(entry.getKey())) {
                continue;
            }
            Object value = entry.getValue();
            if (dropResponseBody && "response".equals(entry.getKey()) && value instanceof Map<?, ?> map) {
                LinkedHashMap<String, Object> response = new LinkedHashMap<>();
                for (Map.Entry<?, ?> item : map.entrySet()) {
                    response.put(String.valueOf(item.getKey()), "body".equals(item.getKey()) ? "" : item.getValue());
                }
                copy.put("response", response);
                continue;
            }
            if (value instanceof Map<?, ?> map) {
                copy.put(entry.getKey(), new LinkedHashMap<>(map));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    private void login(LoginScope scope) {
        login(scope, null, null, true);
    }

    /**
     * {@code resume} 非空时从 {@code ask} 的下一步接着跑。失败不记入自动登录暂停。
     * 调用方已经丢掉待输入，避免再次进入时被「已经在等输入」短路。
     */
    private void login(
            LoginScope scope,
            PendingLogins.Pending resume,
            String supplied,
            boolean countAuthFailure
    ) {
        scope.loggedInThisCall = true;
        scope.loggingIn = true;
        try {
            if (resume == null) {
                PendingLogins.Pending waiting = sessions.pending().current(scope.key);
                if (waiting != null && samePackage(scope.conch, waiting)) {
                    throw waitingFailure(scope, waiting);
                }
                if (waiting != null) {
                    sessions.pending().discard(scope.key);
                }
            }
            Auth auth = scope.conch.auth();
            if (auth == null || auth.steps().isEmpty()) {
                scope.replaceSnapshot(Map.of());
                sessions.markLoggedIn(scope.key);
                sessions.recordAuthSuccess(scope.key);
                return;
            }
            DevTraceDraft draft = DevTraceScope.current();
            if (draft != null) {
                draft.login(true, null);
            }
            ArrayList<StepLog> logs = new ArrayList<>();
            Map<String, Object> vars = baseVars(scope);
            List<Step> steps = auth.steps();
            int indexBase = 0;
            if (resume != null) {
                scope.rememberFrozen(resume.vars());
                overlay(vars, resume.vars());
                vars.put(resume.into(), supplied);
                scope.remember(supplied);
                indexBase = resume.askIndex() + 1;
                int from = Math.min(indexBase, auth.steps().size());
                steps = auth.steps().subList(from, auth.steps().size());
            }
            try {
                runSteps(steps, vars, true, null, logs, scope.key, scope.base, scope.deadline, "login",
                        scope.conch.scripts(), indexBase, false, scope.conch);
                Map<String, String> snapshot = LoginSnapshots.capture(auth.steps(), vars);
                LoginSnapshots.checkSize(snapshot);
                scope.replaceSnapshot(snapshot);
                sessions.markLoggedIn(scope.key);
                sessions.recordAuthSuccess(scope.key);
                if (draft != null) {
                    draft.login(true, true);
                }
            } catch (StepFailure ex) {
                if (draft != null) {
                    draft.login(true, false);
                }
                if (countAuthFailure && ex.code == ErrorCode.AUTH_FAILED) {
                    sessions.recordAuthFailure(scope.key);
                }
                throw ex;
            } catch (SessionLost ex) {
                if (draft != null) {
                    draft.login(true, false);
                }
                throw new StepFailure(ErrorCode.AUTH_FAILED, "登录失败", -1, "request", logs);
            } catch (RuntimeException ex) {
                if (draft != null) {
                    draft.login(true, false);
                }
                throw ex;
            }
        } finally {
            scope.loggingIn = false;
        }
    }

    private Attempt runAction(Action action, LoginScope scope) {
        ArrayList<StepLog> logs = new ArrayList<>();
        Map<String, Object> vars = baseVars(scope);
        try {
            runSteps(action.steps(), vars, false, expectPrefix(scope.conch), logs, scope.key, scope.base,
                    scope.deadline, "action", scope.conch.scripts());
            return Attempt.ok(vars, logs);
        } catch (SessionLost ex) {
            return Attempt.lost(logs);
        } catch (StepFailure ex) {
            return Attempt.failed(ex);
        }
    }

    private Outcome runSteps(
            List<Step> definitions,
            Map<String, Object> vars,
            boolean auth,
            String expectPrefix,
            List<StepLog> logs,
            SessionKey key,
            URI base,
            long deadline,
            String phase,
            Map<String, String> libraries
    ) {
        return runSteps(definitions, vars, auth, expectPrefix, logs, key, base, deadline, phase, libraries,
                0, false, null);
    }

    private Outcome runSteps(
            List<Step> definitions,
            Map<String, Object> vars,
            boolean auth,
            String expectPrefix,
            List<StepLog> logs,
            SessionKey key,
            URI base,
            long deadline,
            String phase,
            Map<String, String> libraries,
            int indexBase,
            boolean hideResponseBody,
            ConchDoc conch
    ) {
        CloseableHttpClient client = sessions.client(key);
        Runnable persist = () -> sessions.persist(key);
        DevTraceDraft draft = DevTraceScope.current();
        boolean capture = draft != null;
        boolean loginPhase = "login".equals(phase);
        boolean deriveNext = false;
        byte[] previousBytes = null;
        boolean previousRequest = false;
        Outcome last = null;
        try {
        for (int i = 0; i < definitions.size(); i++) {
            Step step = definitions.get(i);
            int at = indexBase + i;
            if (draft != null) {
                boolean extractOrScript = "extract".equals(step.type()) || "script".equals(step.type());
                draft.collectDerived(loginPhase || (deriveNext && extractOrScript), !loginPhase);
            }
            if (System.nanoTime() >= deadline) {
                throw new DeadlineExceeded(at, step.type(), logs);
            }
            long started = System.nanoTime();
            if ("ask".equals(step.type())) {
                throw pauseForAsk(step, at, vars, previousRequest, previousBytes, logs, started, conch, key, auth);
            }
            DevTraceScope.takePending();
            try {
                Outcome outcome = steps.run(
                        step,
                        vars,
                        auth ? null : expectPrefix,
                        client,
                        persist,
                        sessions.cookies(key),
                        base,
                        deadline,
                        capture,
                        libraries);
                last = outcome;
                if (outcome.capture() != null
                        && "request".equals(step.type())
                        && (hideResponseBody || imageAskFollows(definitions, i))) {
                    outcome.capture().put("responseBody", null);
                }
                logs.add(new StepLog(
                        at,
                        step.type(),
                        elapsed(started),
                        outcome.method(),
                        outcome.path(),
                        outcome.status(),
                        outcome.extractCount()));
                if (capture && outcome.capture() != null && draft != null) {
                    stampCredential(outcome.capture(), credentialBearingRequest(loginPhase, step));
                    draft.add(phase, at, elapsed(started), outcome.capture());
                }
                if (!loginPhase && "request".equals(step.type())) {
                    deriveNext = templates.referencesCredential(step.def());
                }
                previousRequest = "request".equals(step.type());
                previousBytes = previousRequest ? outcome.bodyBytes() : null;
                // 只打断能力请求。登录步骤仍走 assert，失败保持 AUTH_FAILED。
                // 下标用这一段里的位置：能力步骤的 indexBase 是 0，登录续跑不会进这里。
                rejectCapabilityStatus(auth, outcome, i, step.type(), logs, definitions);
            } catch (RuleProblem ex) {
                ErrorCode code = ex.code;
                String summary = ex.summary;
                if (auth && code == ErrorCode.RULE_MISMATCH && step.type().equals("assert")) {
                    code = ErrorCode.AUTH_FAILED;
                    if (StepRunner.COOKIE_MISSING_SUMMARY.equals(summary)) {
                        summary = LOGIN_COOKIE_MISSING;
                    }
                }
                logs.add(new StepLog(at, step.type(), elapsed(started), ex.method, ex.path, ex.status, null));
                recordFailure(phase, at, step, elapsed(started), ex, credentialBearingRequest(loginPhase, step));
                throw new StepFailure(code, summary, at, step.type(), logs);
            } catch (TemplateException ex) {
                logs.add(new StepLog(at, step.type(), elapsed(started), null, null, null, null));
                recordFailure(phase, at, step, elapsed(started), null, credentialBearingRequest(loginPhase, step));
                ErrorCode code = auth ? ErrorCode.AUTH_FAILED : ErrorCode.RULE_MISMATCH;
                throw new StepFailure(code, undefinedOr(ex), at, step.type(), logs);
            } catch (DeadlineExceeded ex) {
                throw new DeadlineExceeded(at, step.type(), logs);
            }
        }
        } finally {
            if (draft != null) {
                draft.collectDerived(false);
            }
        }
        return last;
    }

    private static String undefinedOr(TemplateException ex) {
        String undefined = UndefinedVariable.fromTemplateFailure(ex.getMessage());
        return undefined == null ? ex.getMessage() : undefined;
    }

    private ExecutionResult expired(
            List<StepLog> logs,
            String version,
            ExecutionRequest request,
            Map<String, String> credentials,
            LoginScope scope,
            long start
    ) {
        Integer index = null;
        String type = null;
        if (!logs.isEmpty()) {
            StepLog last = logs.get(logs.size() - 1);
            index = last.index();
            type = last.type();
        }
        return finish(false, null, ErrorCode.SESSION_EXPIRED, "会话已失效", index, type, "会话已失效", logs, version,
                request, credentials, scope.redactionValues(), start);
    }

    /**
     * 在渲染 alive_check 或能力步骤之前，把静态引用的登录名字准备好。
     * 渲染期的 {@link LoginScope.LoginView} 只读，不再触发登录。
     */
    private void requireLoginRefs(LoginScope scope, Object tree) {
        for (String name : loginNames(tree)) {
            scope.require(name);
        }
    }

    private Set<String> loginNames(Object node) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectLoginNames(node, names);
        return names;
    }

    private void collectLoginNames(Object node, Set<String> names) {
        if (node instanceof Step step) {
            collectLoginNames(step.def(), names);
            return;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                collectLoginNames(item, names);
            }
            return;
        }
        Set<String> paths;
        try {
            paths = templates.referencedPaths(node);
        } catch (RuntimeException ex) {
            return;
        }
        for (String path : paths) {
            String name = loginField(path);
            if (name != null) {
                names.add(name);
            }
        }
    }

    /** {@code login.名字} 的第一段。裸的 {@code login} 和 {@code login[} 不是字段名。 */
    private static String loginField(String ref) {
        if (ref == null || !ref.startsWith("login.")) {
            return null;
        }
        String rest = ref.substring("login.".length());
        if (rest.isEmpty() || !isIdentifierStart(rest.charAt(0))) {
            return null;
        }
        int end = 1;
        while (end < rest.length() && isIdentifierPart(rest.charAt(end))) {
            end++;
        }
        if (end < rest.length()) {
            char next = rest.charAt(end);
            if (next != '.' && next != '[') {
                return null;
            }
        }
        return rest.substring(0, end);
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || Character.isDigit(c);
    }

    private ExecutionResult internal(
            ExecutionRequest request,
            Map<String, String> credentials,
            List<String> cookies,
            String version,
            long start,
            Throwable failure
    ) {
        Redactor redactor = redactor(credentials, cookies);
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        LOG.log(Level.WARNING, "traceId={0} code=INTERNAL detail={1}",
                new Object[] {request.traceId(), redactor.redact(writer.toString())});
        return finish(false, null, ErrorCode.INTERNAL, "内部错误", null, null, "内部错误", List.of(), version, request,
                credentials, cookies, start);
    }

    private ExecutionResult finish(
            boolean success,
            Object data,
            ErrorCode code,
            String message,
            Integer index,
            String type,
            String summary,
            List<StepLog> logs,
            String version,
            ExecutionRequest request,
            Map<String, String> credentials,
            List<String> cookies,
            long start
    ) {
        Redactor redactor = redactor(credentials, cookies);
        if (code != null) {
            LOG.fine("traceId=" + (request.traceId() == null ? "" : request.traceId()) + " code=" + code);
        }
        ArrayList<StepLog> scrubbedLogs = new ArrayList<>();
        for (StepLog log : logs) {
            scrubbedLogs.add(new StepLog(
                    log.index(),
                    redactor.redact(log.type()),
                    log.durationMillis(),
                    redactor.redact(log.method()),
                    redactor.redact(log.path()),
                    log.status(),
                    log.extractCount()));
        }
        ExecutionError error = null;
        if (!success) {
            error = new ExecutionError(
                    code,
                    HttpCalls.presentUpstream(redactor.redact(message)),
                    index,
                    redactor.redact(type),
                    HttpCalls.presentUpstream(redactor.redact(summary)));
        }
        Object masked = success ? OutputMask.apply(data, redactor) : null;
        commitTrace(request, redactor(credentials, cookies, true));
        return new ExecutionResult(
                success,
                masked,
                error,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                scrubbedLogs,
                version,
                request.traceId());
    }

    private DevTraceDraft openDraft(ExecutionRequest request, ConchDoc authorized) {
        if (traceSink == null || request.origin() != Origin.LOCAL) {
            return null;
        }
        ConchDoc doc = authorized;
        if (doc != null && !doc.conch().equals(request.conch())) {
            return null;
        }
        if (doc == null) {
            doc = registry.find(request.conch()).orElse(null);
        }
        // HTTP steps only. A forwarder records MCP JSON-RPC with DevTraceStore.recordJsonRpc.
        if (doc == null || !"http".equals(doc.driver())) {
            return null;
        }
        try {
            return traceSink.open(request);
        } catch (RuntimeException ex) {
            LOG.warning("dev trace open failed: " + ex.getClass().getName());
            return null;
        }
    }

    private void recordAlive(
            Map<String, Object> spec,
            String method,
            String path,
            Integer status,
            Map<String, String> responseHeaders,
            String responseBody,
            Map<String, Object> wire,
            long started,
            boolean ok,
            Map<String, Object> transport,
            List<TraceWarning> warnings
    ) {
        DevTraceDraft draft = DevTraceScope.current();
        if (draft == null) {
            return;
        }
        draft.aliveCheck(true, ok);
        Map<String, Object> piece = DevTraceDraft.requestPiece(
                spec, method, path, status, responseHeaders, responseBody, wire);
        if (transport != null) {
            piece.put("transport", transport);
        }
        HttpCalls.attachWarnings(piece, warnings);
        stampCredential(piece, true);
        draft.add("alive_check", -1, elapsed(started), piece);
    }

    private boolean credentialBearingRequest(boolean loginPhase, Step step) {
        return "request".equals(step.type()) && (loginPhase || templates.referencesCredential(step.def()));
    }

    private static void stampCredential(Map<String, Object> piece, boolean credentialBearing) {
        if (credentialBearing && piece != null) {
            piece.put("credentialBearing", Boolean.TRUE);
        }
    }

    private static void recordFailure(
            String phase,
            int index,
            Step step,
            long durationMs,
            RuleProblem problem,
            boolean credentialBearing
    ) {
        DevTraceDraft draft = DevTraceScope.current();
        if (draft == null) {
            return;
        }
        Map<String, Object> pending = DevTraceScope.takePending();
        if (pending != null) {
            stampCredential(pending, credentialBearing);
            draft.add(phase, index, durationMs, pending);
            return;
        }
        Map<String, Object> piece = switch (step.type()) {
            case "assert" -> DevTraceDraft.assertPiece(step.def(), false);
            case "extract" -> DevTraceDraft.extractPiece(step.def(), 0, Map.of());
            case "set", "script" -> DevTraceDraft.variablePiece(step.type(), namesOnly(step.def()));
            case "request" -> {
                Map<String, Object> request = DevTraceDraft.requestPiece(
                        null,
                        problem == null ? null : problem.method,
                        problem == null ? null : problem.path,
                        problem == null ? null : problem.status,
                        problem == null ? null : problem.responseHeaders,
                        problem == null ? null : problem.responseBody,
                        problem == null ? null : problem.wire);
                if (problem != null && problem.transport != null) {
                    request.put("transport", problem.transport);
                }
                if (problem != null) {
                    HttpCalls.attachWarnings(request, problem.warnings);
                }
                yield request;
            }
            default -> Map.of("type", step.type());
        };
        stampCredential(piece, credentialBearing);
        draft.add(phase, index, durationMs, piece);
    }

    private static Map<String, Object> namesOnly(Map<String, Object> def) {
        LinkedHashMap<String, Object> names = new LinkedHashMap<>();
        if (def == null) {
            return names;
        }
        for (String key : def.keySet()) {
            if ("code".equals(key) || "timeout_ms".equals(key) || ConchRegistry.RESERVED.contains(key)) {
                continue;
            }
            names.put(key, null);
        }
        return names;
    }

    private void commitTrace(ExecutionRequest request, Redactor redactor) {
        DevTraceDraft draft = DevTraceScope.current();
        if (draft == null || traceSink == null) {
            return;
        }
        SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
        List<String> names;
        boolean stored;
        try {
            names = sessions.cookieNames(key);
        } catch (RuntimeException ex) {
            names = List.of();
        }
        try {
            stored = sessions.loggedIn(key);
        } catch (RuntimeException ex) {
            stored = false;
        }
        try {
            traceSink.save(request, draft.expiresAt(), draft.render(redactor, names, stored));
        } catch (RuntimeException ex) {
            LOG.warning("dev trace save failed: " + ex.getClass().getName());
        }
    }

    private Redactor redactor(Map<String, String> credentials, List<String> cookies) {
        return redactor(credentials, cookies, false);
    }

    private Redactor redactor(Map<String, String> credentials, List<String> cookies, boolean includeTraceDerived) {
        LinkedHashSet<String> secrets = new LinkedHashSet<>(knownSecrets.copy());
        if (credentials != null) {
            for (String value : credentials.values()) {
                if (value != null) {
                    secrets.add(value);
                }
            }
        }
        if (cookies != null) {
            for (String value : cookies) {
                if (value != null) {
                    secrets.add(value);
                }
            }
        }
        secrets.addAll(CallSecrets.current());
        DevTraceDraft draft = DevTraceScope.current();
        if (draft != null) {
            secrets.addAll(draft.derivedSecrets());
            if (includeTraceDerived) {
                secrets.addAll(draft.traceSecrets());
            }
        }
        return Redactor.of(secrets);
    }

    private static Map<String, Object> baseVars(LoginScope scope) {
        LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
        vars.put("input", scope.input);
        vars.put("credential", scope.credentials);
        vars.put("config", scope.config);
        vars.put("login", scope.view);
        return vars;
    }

    private static URI baseUri(ConchDoc conch, Map<String, String> config) {
        String configured = config.get("base_url");
        String raw = configured == null || configured.isBlank() ? conch.baseUrl() : configured;
        if (raw == null || raw.isBlank()) {
            throw new RuleProblem(ErrorCode.INTERNAL, "缺少 base_url", null, null, null);
        }
        try {
            URI uri = HostNames.withoutTrailingDot(URI.create(raw));
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw new RuleProblem(ErrorCode.INTERNAL, "base_url 无效", null, null, null);
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new RuleProblem(ErrorCode.INTERNAL, "base_url 无效", null, null, null);
        }
    }

    /**
     * 能力请求在提取之前处理最终状态。400、401/403、登录重定向，以及 HttpCalls 已经拦住的 5xx，保持原映射。
     * 其它非 2xx：后续 status 断言覆盖了实际状态码时放行；非 GET 的 3xx 且不是登录重定向时放行。
     * 其余（例如没有 status 断言的 GET 404）不再进入 extract，避免抽成空列表。
     */
    private static void rejectCapabilityStatus(
            boolean auth,
            Outcome outcome,
            int index,
            String type,
            List<StepLog> logs,
            List<Step> definitions
    ) {
        if (auth || outcome.status() == null) {
            return;
        }
        if (lostSession(outcome)) {
            throw new SessionLost();
        }
        int status = outcome.status();
        if (status == 400) {
            throw new StepFailure(ErrorCode.INVALID_INPUT, UPSTREAM_INPUT_REJECTED, index, type, logs);
        }
        if (status >= 200 && status < 300) {
            return;
        }
        if (status >= 300 && status < 400 && !isGet(outcome.method())) {
            return;
        }
        if (statusExpectationCovers(definitions, index, status)) {
            return;
        }
        throw new StepFailure(ErrorCode.UPSTREAM_ERROR, HttpCalls.upstreamStatus(status), index, type, logs);
    }

    private static boolean isGet(String method) {
        return method == null || method.isBlank() || "GET".equalsIgnoreCase(method);
    }

    /**
     * 这次请求之后、下一次 request 之前，是否有 assert.status 覆盖实际状态码。
     * 字面量数字或数字列表对不上则不算覆盖。含模板等非字面量时无法预判，交给 assert。
     */
    private static boolean statusExpectationCovers(List<Step> definitions, int requestIndex, int actual) {
        for (int j = requestIndex + 1; j < definitions.size(); j++) {
            Step step = definitions.get(j);
            if ("request".equals(step.type())) {
                break;
            }
            if (!"assert".equals(step.type()) || !step.def().containsKey("status")) {
                continue;
            }
            Boolean covered = literalStatusIncludes(step.def().get("status"), actual);
            if (covered == null || covered) {
                return true;
            }
        }
        return false;
    }

    /** {@code null} 表示不是字面量状态码，渲染后才知道是否匹配。 */
    private static Boolean literalStatusIncludes(Object expected, int actual) {
        if (expected instanceof Number number) {
            return number.intValue() == actual;
        }
        if (expected instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty() || trimmed.contains("{{")) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed) == actual;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (expected instanceof List<?> list) {
            if (list.isEmpty()) {
                return false;
            }
            boolean unknown = false;
            for (Object item : list) {
                Boolean one = literalStatusIncludes(item, actual);
                if (one == null) {
                    unknown = true;
                } else if (one) {
                    return true;
                }
            }
            return unknown ? null : false;
        }
        return null;
    }

    /** 重定向到登录页，或上游直接返回 401/403，都是会话失效。 */
    private static boolean lostSession(Outcome outcome) {
        if (outcome.sessionLost()) {
            return true;
        }
        Integer status = outcome.status();
        return status != null && (status == 401 || status == 403);
    }

    private static String expectPrefix(ConchDoc conch) {
        if (conch.auth() == null || conch.auth().aliveCheck() == null) {
            return null;
        }
        return conch.auth().aliveCheck().expectNotRedirect();
    }

    private static Map<String, String> copyStrings(Map<String, String> source) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, String> entry : source.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static final class Attempt {
        private final Map<String, Object> vars;
        private final List<StepLog> logs;
        private final boolean lost;
        private final StepFailure failure;

        private Attempt(Map<String, Object> vars, List<StepLog> logs, boolean lost, StepFailure failure) {
            this.vars = vars;
            this.logs = logs == null ? List.of() : List.copyOf(logs);
            this.lost = lost;
            this.failure = failure;
        }

        private static Attempt ok(Map<String, Object> vars, List<StepLog> logs) {
            return new Attempt(vars, logs, false, null);
        }

        private static Attempt lost(List<StepLog> logs) {
            return new Attempt(Map.of(), logs, true, null);
        }

        private static Attempt failed(StepFailure failure) {
            return new Attempt(Map.of(), failure.logs, false, failure);
        }
    }

    /**
     * 一次调用持有的规则包。版本切换不在这次调用里重读注册表。
     * {@code loggedInThisCall} 挡住同一次调用里的第二次自动登录；幂等重放直接再调 {@link #login}。
     */
    private final class LoginScope {
        private final ConchDoc conch;
        private final Map<String, Object> input;
        private final Map<String, String> credentials;
        private final Map<String, String> config;
        private final SessionKey key;
        private final URI base;
        private final long deadline;
        private final LoginView view = new LoginView();
        /** 这次调用读到的登录值，以及每次替换快照前后的值。清掉快照之后仍打码。 */
        private final LinkedHashSet<String> seenLogin = new LinkedHashSet<>();
        private boolean loggingIn;
        private boolean loggedInThisCall;

        private LoginScope(
                ConchDoc conch,
                Map<String, Object> input,
                Map<String, String> credentials,
                Map<String, String> config,
                SessionKey key,
                URI base,
                long deadline
        ) {
            this.conch = conch;
            this.input = input;
            this.credentials = credentials;
            this.config = config;
            this.key = key;
            this.base = base;
            this.deadline = deadline;
        }

        private String lookup(String name) {
            String stored = sessions.loginSnapshot(key).get(name);
            if (stored != null) {
                remember(stored);
                return stored;
            }
            String cookie = sessions.cookieValue(key, cookieName(name));
            remember(cookie);
            return cookie;
        }

        private void remember(String value) {
            if (value != null && !value.isEmpty()) {
                seenLogin.add(value);
            }
        }

        /** 接着登录一开始就打码。规则与 {@link CallSecrets} 相同：长度至少 4，且不是掩码本身。 */
        private void rememberFrozen(Map<String, Object> vars) {
            rememberTree(vars, 0);
        }

        private void rememberTree(Object value, int depth) {
            if (value == null || depth > 8) {
                return;
            }
            if (value instanceof String text) {
                if (text.length() >= 4 && !"***".equals(text)) {
                    seenLogin.add(text);
                }
                return;
            }
            if (value instanceof Map<?, ?> map) {
                for (Object item : map.values()) {
                    rememberTree(item, depth + 1);
                }
                return;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    rememberTree(item, depth + 1);
                }
            }
        }

        private void remember(Iterable<String> values) {
            if (values == null) {
                return;
            }
            for (String value : values) {
                remember(value);
            }
        }

        /** 替换前后的快照值都留下，避免失效路径清掉快照后打码名单丢了正文令牌。 */
        private void replaceSnapshot(Map<String, String> snapshot) {
            remember(sessions.loginSnapshot(key).values());
            sessions.replaceLoginSnapshot(key, snapshot);
            remember(snapshot == null ? List.of() : snapshot.values());
        }

        private List<String> redactionValues() {
            LinkedHashSet<String> values = new LinkedHashSet<>(sessions.redactionValues(key));
            values.addAll(seenLogin);
            return List.copyOf(values);
        }

        private String cookieName(String name) {
            Auth auth = conch.auth();
            if (auth == null) {
                return name;
            }
            String alias = auth.cookies().get(name);
            return alias == null || alias.isBlank() ? name : alias;
        }

        private void require(String name) {
            if (lookup(name) != null) {
                return;
            }
            if (loggingIn || loggedInThisCall) {
                throw missing(name);
            }
            login(this);
            if (lookup(name) == null) {
                throw missing(name);
            }
        }

        private StepFailure missing(String name) {
            return new StepFailure(ErrorCode.AUTH_FAILED, "登录没有建立变量 " + name, -1, "request", List.of());
        }

        private final class LoginView extends AbstractMap<String, Object> {
            @Override
            public boolean containsKey(Object key) {
                return key instanceof String;
            }

            @Override
            public Object get(Object key) {
                if (!(key instanceof String name)) {
                    return null;
                }
                String value = lookup(name);
                if (value == null) {
                    throw missing(name);
                }
                return value;
            }

            @Override
            public Set<Map.Entry<String, Object>> entrySet() {
                return Set.of();
            }

            @Override
            public String toString() {
                return "login";
            }
        }
    }
}
