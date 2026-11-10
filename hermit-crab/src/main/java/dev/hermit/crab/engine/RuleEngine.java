package dev.hermit.crab.engine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import dev.hermit.common.error.ErrorCode;
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
        this.steps = new StepRunner(templates, filters);
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
        DevTraceScope.begin(openDraft(request, authorized));
        try {
            return executeCaptured(request, authorized);
        } finally {
            DevTraceScope.end();
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
                return finish(false, null, ErrorCode.NOT_FOUND, "conch 不存在", null, null, "conch 不存在",
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
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String version = null;
        try {
            ConchDoc conch = registry.find(request.conch()).orElse(null);
            if (conch == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "conch 不存在", null, null, "conch 不存在",
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
            try {
                URI base = baseUri(conch, config);
                login(conch, request.rawInput(), credentials, config, key, base, deadline);
                return finish(true, Map.of("ok", true), null, null, null, null, null, List.of(), version, request,
                        credentials, sessions.seenCookieValues(key), start);
            } catch (StepFailure ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version, request,
                        credentials, sessions.seenCookieValues(key), start);
            } catch (DeadlineExceeded ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                        request, credentials, sessions.seenCookieValues(key), start);
            } catch (RuleProblem ex) {
                return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                        credentials, sessions.seenCookieValues(key), start);
            } catch (RuntimeException ex) {
                return internal(request, credentials, sessions.seenCookieValues(key), version, start, ex);
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
        try {
            if (sessions.loginPaused(key)) {
                return finish(false, null, ErrorCode.AUTH_FAILED, "该账号已暂停自动登录", null, null,
                        "该账号已暂停自动登录", List.of(), version, request, credentials,
                        sessions.seenCookieValues(key), start);
            }
            URI base = baseUri(conch, config);
            ensureSession(conch, input, credentials, config, key, base, deadline);
            Attempt attempt = runAction(conch, action, input, credentials, config, key, base, deadline);
            if (attempt.lost) {
                sessions.markLoggedOut(key);
                if (!action.idempotent()) {
                    return expired(attempt.logs, version, request, credentials, key, start);
                }
                ensureSession(conch, input, credentials, config, key, base, deadline);
                attempt = runAction(conch, action, input, credentials, config, key, base, deadline);
                if (attempt.lost) {
                    sessions.markLoggedOut(key);
                    return expired(attempt.logs, version, request, credentials, key, start);
                }
            }
            if (attempt.failure != null) {
                StepFailure failure = attempt.failure;
                Integer index = failure.index < 0 ? null : failure.index;
                return finish(false, null, failure.code, failure.summary, index, failure.type, failure.summary,
                        failure.logs, version, request, credentials, sessions.seenCookieValues(key), start);
            }
            Object data;
            try {
                data = templates.render(action.outputTemplate(), attempt.vars);
            } catch (TemplateException ex) {
                String text = undefinedOr(ex);
                return finish(false, null, ErrorCode.RULE_MISMATCH, text, null, null, text,
                        attempt.logs, version, request, credentials, sessions.seenCookieValues(key), start);
            }
            sessions.touch(key);
            return finish(true, data, null, null, null, null, null, attempt.logs, version, request, credentials,
                    sessions.seenCookieValues(key), start);
        } catch (StepFailure ex) {
            Integer index = ex.index < 0 ? null : ex.index;
            return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version, request,
                    credentials, sessions.seenCookieValues(key), start);
        } catch (DeadlineExceeded ex) {
            Integer index = ex.index < 0 ? null : ex.index;
            return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                    request, credentials, sessions.seenCookieValues(key), start);
        } catch (RuleProblem ex) {
            return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                    credentials, sessions.seenCookieValues(key), start);
        } catch (RuntimeException ex) {
            return internal(request, credentials, sessions.seenCookieValues(key), version, start, ex);
        }
    }

    private void ensureSession(
            ConchDoc conch,
            Map<String, Object> input,
            Map<String, String> credentials,
            Map<String, String> config,
            SessionKey key,
            URI base,
            long deadline
    ) {
        if (!sessions.loggedIn(key)) {
            login(conch, input, credentials, config, key, base, deadline);
            return;
        }
        Auth auth = conch.auth();
        AliveCheck alive = auth == null ? null : auth.aliveCheck();
        if (!sessions.idle(key) || alive == null) {
            return;
        }
        Map<String, Object> vars = baseVars(input, credentials, config);
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
                    sessions.client(key),
                    () -> sessions.persist(key),
                    base,
                    alive.expectNotRedirect(),
                    deadline);
        } catch (RuleProblem ex) {
            recordAlive(spec, ex.method, ex.path, ex.status, null, null, started, false);
            throw ex;
        }
        boolean aliveOk = !(result.sessionLost() || result.status() == 401 || result.status() == 403);
        recordAlive(spec, result.method(), result.path(), result.status(), result.headers(), result.body(), started, aliveOk);
        if (result.sessionLost() || result.status() == 401 || result.status() == 403) {
            sessions.markLoggedOut(key);
            login(conch, input, credentials, config, key, base, deadline);
            return;
        }
        sessions.touch(key);
    }

    private void login(
            ConchDoc conch,
            Map<String, Object> input,
            Map<String, String> credentials,
            Map<String, String> config,
            SessionKey key,
            URI base,
            long deadline
    ) {
        Auth auth = conch.auth();
        if (auth == null || auth.steps().isEmpty()) {
            sessions.markLoggedIn(key);
            sessions.recordAuthSuccess(key);
            return;
        }
        DevTraceDraft draft = DevTraceScope.current();
        if (draft != null) {
            draft.login(true, null);
        }
        ArrayList<StepLog> logs = new ArrayList<>();
        Map<String, Object> vars = baseVars(input, credentials, config);
        try {
            runSteps(auth.steps(), vars, true, null, logs, key, base, deadline, "login");
            sessions.markLoggedIn(key);
            sessions.recordAuthSuccess(key);
            if (draft != null) {
                draft.login(true, true);
            }
        } catch (StepFailure ex) {
            if (draft != null) {
                draft.login(true, false);
            }
            if (ex.code == ErrorCode.AUTH_FAILED) {
                sessions.recordAuthFailure(key);
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
    }

    private Attempt runAction(
            ConchDoc conch,
            Action action,
            Map<String, Object> input,
            Map<String, String> credentials,
            Map<String, String> config,
            SessionKey key,
            URI base,
            long deadline
    ) {
        ArrayList<StepLog> logs = new ArrayList<>();
        Map<String, Object> vars = baseVars(input, credentials, config);
        try {
            runSteps(action.steps(), vars, false, expectPrefix(conch), logs, key, base, deadline, "action");
            return Attempt.ok(vars, logs);
        } catch (SessionLost ex) {
            return Attempt.lost(logs);
        } catch (StepFailure ex) {
            return Attempt.failed(ex);
        }
    }

    private void runSteps(
            List<Step> definitions,
            Map<String, Object> vars,
            boolean auth,
            String expectPrefix,
            List<StepLog> logs,
            SessionKey key,
            URI base,
            long deadline,
            String phase
    ) {
        CloseableHttpClient client = sessions.client(key);
        Runnable persist = () -> sessions.persist(key);
        DevTraceDraft draft = DevTraceScope.current();
        boolean capture = draft != null;
        boolean collectDerived = draft != null && "login".equals(phase);
        if (collectDerived) {
            draft.collectDerived(true);
        }
        try {
        for (int i = 0; i < definitions.size(); i++) {
            Step step = definitions.get(i);
            if (System.nanoTime() >= deadline) {
                throw new DeadlineExceeded(i, step.type(), logs);
            }
            long started = System.nanoTime();
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
                        capture);
                logs.add(new StepLog(
                        i,
                        step.type(),
                        elapsed(started),
                        outcome.method(),
                        outcome.path(),
                        outcome.status(),
                        outcome.extractCount()));
                if (capture && outcome.capture() != null && draft != null) {
                    draft.add(phase, i, elapsed(started), outcome.capture());
                }
                // 只打断能力请求。登录步骤仍走 assert，失败保持 AUTH_FAILED。
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
                logs.add(new StepLog(i, step.type(), elapsed(started), ex.method, ex.path, ex.status, null));
                recordFailure(phase, i, step, elapsed(started), ex);
                throw new StepFailure(code, summary, i, step.type(), logs);
            } catch (TemplateException ex) {
                logs.add(new StepLog(i, step.type(), elapsed(started), null, null, null, null));
                recordFailure(phase, i, step, elapsed(started), null);
                ErrorCode code = auth ? ErrorCode.AUTH_FAILED : ErrorCode.RULE_MISMATCH;
                throw new StepFailure(code, undefinedOr(ex), i, step.type(), logs);
            } catch (DeadlineExceeded ex) {
                throw new DeadlineExceeded(i, step.type(), logs);
            }
        }
        } finally {
            if (collectDerived) {
                draft.collectDerived(false);
            }
        }
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
            SessionKey key,
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
                request, credentials, sessions.seenCookieValues(key), start);
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
                    redactor.redact(message),
                    index,
                    redactor.redact(type),
                    redactor.redact(summary));
        }
        Object masked = success ? OutputMask.apply(data, redactor) : null;
        commitTrace(request, redactor);
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
            long started,
            boolean ok
    ) {
        DevTraceDraft draft = DevTraceScope.current();
        if (draft == null) {
            return;
        }
        draft.aliveCheck(true, ok);
        draft.add("alive_check", -1, elapsed(started),
                DevTraceDraft.requestPiece(spec, method, path, status, responseHeaders, responseBody));
    }

    private static void recordFailure(String phase, int index, Step step, long durationMs, RuleProblem problem) {
        DevTraceDraft draft = DevTraceScope.current();
        if (draft == null) {
            return;
        }
        Map<String, Object> pending = DevTraceScope.takePending();
        if (pending != null) {
            draft.add(phase, index, durationMs, pending);
            return;
        }
        Map<String, Object> piece = switch (step.type()) {
            case "assert" -> DevTraceDraft.assertPiece(step.def(), false);
            case "extract" -> DevTraceDraft.extractPiece(step.def(), 0, Map.of());
            case "set", "script" -> DevTraceDraft.variablePiece(step.type(), namesOnly(step.def()));
            case "request" -> DevTraceDraft.requestPiece(
                    null,
                    problem == null ? null : problem.method,
                    problem == null ? null : problem.path,
                    problem == null ? null : problem.status,
                    null,
                    null);
            default -> Map.of("type", step.type());
        };
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
        DevTraceDraft draft = DevTraceScope.current();
        if (draft != null) {
            secrets.addAll(draft.derivedSecrets());
        }
        return Redactor.of(secrets);
    }

    private static Map<String, Object> baseVars(
            Map<String, Object> input,
            Map<String, String> credentials,
            Map<String, String> config
    ) {
        LinkedHashMap<String, Object> vars = new LinkedHashMap<>();
        vars.put("input", input);
        vars.put("credential", credentials);
        vars.put("config", config);
        return vars;
    }

    private static URI baseUri(ConchDoc conch, Map<String, String> config) {
        String configured = config.get("base_url");
        String raw = configured == null || configured.isBlank() ? conch.baseUrl() : configured;
        if (raw == null || raw.isBlank()) {
            throw new RuleProblem(ErrorCode.INTERNAL, "缺少 base_url", null, null, null);
        }
        try {
            URI uri = URI.create(raw);
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
}
