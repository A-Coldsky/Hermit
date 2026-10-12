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
import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;
import dev.hermit.crab.secure.Redactor;

public final class RuleEngine {

    private static final Logger LOG = Logger.getLogger(RuleEngine.class.getName());

    private final ShellRegistry registry;
    private final SessionManager sessions;
    private final EngineSettings settings;
    private final TemplateEngine templates;
    private final FilterRegistry filters;
    private final InputPreparer inputs = new InputPreparer();
    private final StepRunner steps;

    public RuleEngine(ShellRegistry registry, SessionManager sessions) {
        this(registry, sessions, EngineSettings.defaults());
    }

    public RuleEngine(ShellRegistry registry, SessionManager sessions, EngineSettings settings) {
        this.registry = registry;
        this.sessions = sessions;
        this.settings = settings;
        this.filters = FilterRegistry.defaults();
        this.templates = new TemplateEngine(filters);
        this.steps = new StepRunner(templates, filters);
    }

    public ExecutionResult execute(ExecutionRequest request) {
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String version = null;
        try {
            ShellDoc shell = registry.find(request.shell()).orElse(null);
            if (shell == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "shell 不存在", null, null, "shell 不存在",
                        List.of(), version, request, credentials, List.of(), start);
            }
            version = shell.version().toString();
            Action action = shell.actions().get(request.action());
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
            SessionKey key = new SessionKey(request.origin(), request.shell(), request.account());
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
                return locked(request, shell, action, input, credentials, config, key, version, start, deadline);
            } finally {
                sessions.unlock(key);
            }
        } catch (RuntimeException ex) {
            return internal(request, credentials, List.of(), version, start, ex);
        }
    }

    /**
     * Runs auth steps only. The caller resets the login pause and invalidates the session first.
     * Success does not include auth step logs; {@code login} keeps those logs on the failure path.
     */
    public ExecutionResult testLogin(ExecutionRequest request) {
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String version = null;
        try {
            ShellDoc shell = registry.find(request.shell()).orElse(null);
            if (shell == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "shell 不存在", null, null, "shell 不存在",
                        List.of(), version, request, credentials, List.of(), start);
            }
            version = shell.version().toString();
            SessionKey key = new SessionKey(request.origin(), request.shell(), request.account());
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
                URI base = baseUri(shell, config);
                login(shell, request.rawInput(), credentials, config, key, base, deadline);
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
            ShellDoc shell,
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
            URI base = baseUri(shell, config);
            ensureSession(shell, input, credentials, config, key, base, deadline);
            Attempt attempt = runAction(shell, action, input, credentials, config, key, base, deadline);
            if (attempt.lost) {
                sessions.markLoggedOut(key);
                if (!action.idempotent()) {
                    return expired(attempt.logs, version, request, credentials, key, start);
                }
                ensureSession(shell, input, credentials, config, key, base, deadline);
                attempt = runAction(shell, action, input, credentials, config, key, base, deadline);
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
                return finish(false, null, ErrorCode.RULE_MISMATCH, ex.getMessage(), null, null, ex.getMessage(),
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
            ShellDoc shell,
            Map<String, Object> input,
            Map<String, String> credentials,
            Map<String, String> config,
            SessionKey key,
            URI base,
            long deadline
    ) {
        if (!sessions.loggedIn(key)) {
            login(shell, input, credentials, config, key, base, deadline);
            return;
        }
        Auth auth = shell.auth();
        AliveCheck alive = auth == null ? null : auth.aliveCheck();
        if (!sessions.idle(key) || alive == null) {
            return;
        }
        Map<String, Object> vars = baseVars(input, credentials, config);
        Object rendered;
        try {
            rendered = templates.render(alive.request(), vars);
        } catch (TemplateException ex) {
            throw new StepFailure(ErrorCode.AUTH_FAILED, ex.getMessage(), -1, "request", List.of());
        }
        Map<String, Object> spec = Values.map(rendered);
        if (spec == null) {
            throw new StepFailure(ErrorCode.RULE_MISMATCH, "alive_check 无效", -1, "request", List.of());
        }
        HttpResult result = HttpCalls.execute(
                spec,
                sessions.client(key),
                () -> sessions.persist(key),
                base,
                alive.expectNotRedirect(),
                deadline);
        if (result.sessionLost() || result.status() == 401 || result.status() == 403) {
            sessions.markLoggedOut(key);
            login(shell, input, credentials, config, key, base, deadline);
            return;
        }
        sessions.touch(key);
    }

    private void login(
            ShellDoc shell,
            Map<String, Object> input,
            Map<String, String> credentials,
            Map<String, String> config,
            SessionKey key,
            URI base,
            long deadline
    ) {
        Auth auth = shell.auth();
        if (auth == null || auth.steps().isEmpty()) {
            sessions.markLoggedIn(key);
            sessions.recordAuthSuccess(key);
            return;
        }
        ArrayList<StepLog> logs = new ArrayList<>();
        Map<String, Object> vars = baseVars(input, credentials, config);
        try {
            runSteps(auth.steps(), vars, true, null, logs, key, base, deadline);
            sessions.markLoggedIn(key);
            sessions.recordAuthSuccess(key);
        } catch (StepFailure ex) {
            if (ex.code == ErrorCode.AUTH_FAILED) {
                sessions.recordAuthFailure(key);
            }
            throw ex;
        } catch (SessionLost ex) {
            throw new StepFailure(ErrorCode.AUTH_FAILED, "登录失败", -1, "request", logs);
        }
    }

    private Attempt runAction(
            ShellDoc shell,
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
            runSteps(action.steps(), vars, false, expectPrefix(shell), logs, key, base, deadline);
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
            long deadline
    ) {
        CloseableHttpClient client = sessions.client(key);
        Runnable persist = () -> sessions.persist(key);
        for (int i = 0; i < definitions.size(); i++) {
            Step step = definitions.get(i);
            if (System.nanoTime() >= deadline) {
                throw new DeadlineExceeded(i, step.type(), logs);
            }
            long started = System.nanoTime();
            try {
                Outcome outcome = steps.run(
                        step,
                        vars,
                        auth ? null : expectPrefix,
                        client,
                        persist,
                        sessions.cookies(key),
                        base,
                        deadline);
                logs.add(new StepLog(
                        i,
                        step.type(),
                        elapsed(started),
                        outcome.method(),
                        outcome.path(),
                        outcome.status(),
                        outcome.extractCount()));
                if (!auth && outcome.sessionLost()) {
                    throw new SessionLost();
                }
            } catch (RuleProblem ex) {
                ErrorCode code = ex.code;
                if (auth && code == ErrorCode.RULE_MISMATCH && step.type().equals("assert")) {
                    code = ErrorCode.AUTH_FAILED;
                }
                logs.add(new StepLog(i, step.type(), elapsed(started), ex.method, ex.path, ex.status, null));
                throw new StepFailure(code, ex.summary, i, step.type(), logs);
            } catch (TemplateException ex) {
                logs.add(new StepLog(i, step.type(), elapsed(started), null, null, null, null));
                ErrorCode code = auth ? ErrorCode.AUTH_FAILED : ErrorCode.RULE_MISMATCH;
                throw new StepFailure(code, ex.getMessage(), i, step.type(), logs);
            } catch (DeadlineExceeded ex) {
                throw new DeadlineExceeded(i, step.type(), logs);
            }
        }
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
        return new ExecutionResult(
                success,
                masked,
                error,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                scrubbedLogs,
                version,
                request.traceId());
    }

    private static Redactor redactor(Map<String, String> credentials, List<String> cookies) {
        LinkedHashSet<String> secrets = new LinkedHashSet<>();
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

    private static URI baseUri(ShellDoc shell, Map<String, String> config) {
        String configured = config.get("base_url");
        String raw = configured == null || configured.isBlank() ? shell.baseUrl() : configured;
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

    private static String expectPrefix(ShellDoc shell) {
        if (shell.auth() == null || shell.auth().aliveCheck() == null) {
            return null;
        }
        return shell.auth().aliveCheck().expectNotRedirect();
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
